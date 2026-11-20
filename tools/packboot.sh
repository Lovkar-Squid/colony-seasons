#!/usr/bin/env bash
# Pack boot (S9; a live check that never blocks a merge): boots the owner's full modpack set on a headless dedicated
# server, once without this mod (the baseline) and once with the built jar added, and reports what the jar adds: ERROR
# lines that name colony_seasons or me.lovkar.seasons, and crash reports, that the baseline does not have.
#   bash tools/packboot.sh            (build first: bash tools/build.sh)
#
# What it reads and writes:
# - the pack instance's mods folder, %USERPROFILE%\curseforge\minecraft\Instances\MineColonies Ultimate\mods, is found
#   at run time and only READ: its jars are copied. Its listing (names, sizes, times) is compared before and after the
#   run, and any difference fails the run. The expanded user folder is never printed.
# - the server install (libraries, eula.txt, user_jvm_args.txt, server.properties) comes from the private test rig
#   (tools/rigpaths.sh, RUN_RIG_DIR), copied once into one work folder under the system temp folder, outside the repo;
#   that folder is kept between runs and only its mods/, world/, logs/ and crash-reports/ are replaced (after checking
#   this script's own marker file there).
# - the port: 25571 if free, else the next free one, never in a range Windows reserves (netsh excludedportrange); set
#   only in the work folder's copy of server.properties.
# Mods that cannot load on a dedicated server (client-only ones) are moved to the work folder's aside/ and the baseline
# is booted again, at most 10 rounds; the run with the jar uses the same final set, and the set-aside jars are listed.
# Each boot waits for "Done (", sends "stop", and is killed (its own process only) after 15 minutes.
# Exit: 0 clean, 1 a new error or crash or the instance changed, 2 it cannot run here (the reason is printed).
set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
. tools/rigpaths.sh

cannot() { echo "packboot: cannot run: $*"; exit 2; }

[ -n "${USERPROFILE:-}" ] || cannot "USERPROFILE is not set (this check runs on the owner's Windows PC)"
command -v cygpath >/dev/null || cannot "cygpath not found (run it from Git Bash)"
INSTANCE="$(cygpath -u "$USERPROFILE")/curseforge/minecraft/Instances/MineColonies Ultimate/mods"
SHOWN='%USERPROFILE%\curseforge\minecraft\Instances\MineColonies Ultimate\mods'
[ -d "$INSTANCE" ] || cannot "no pack instance at $SHOWN"
ls "$INSTANCE"/*.jar >/dev/null 2>&1 || cannot "no jars in $SHOWN"

version="$(sed -n 's/^version="\([^"]*\)".*/\1/p' resources/META-INF/neoforge.mods.toml | head -1)"
JAR="$(pwd)/colony_seasons-$version.jar"
[ -f "$JAR" ] || cannot "no colony_seasons-$version.jar at the repo root: run bash tools/build.sh first"

SRC="$RUN_RIG_DIR/rig"
[ -d "$SRC/libraries" ] && [ -f "$SRC/user_jvm_args.txt" ] || cannot "the test rig's server install is missing ($RUN_RIG_DIR/rig/libraries)"
JAVA="$JDK_BIN/java.exe"
[ -f "$JAVA" ] || cannot "no java.exe in JDK_BIN ($JDK_BIN)"

WORK="$(cygpath -u "${TEMP:-${TMP:-/tmp}}")/colony-seasons-packboot"
MARK="$WORK/.colony-seasons-packboot"
if [ -d "$WORK" ] && [ ! -f "$MARK" ]; then
  cannot "the work folder exists but is not this script's (no marker file): $(cygpath -w "$WORK")"
fi
mkdir -p "$WORK/server" "$WORK/aside" && echo "colony seasons packboot work folder: safe to delete" > "$MARK"
SERVER="$WORK/server"

listing() { (cd "$INSTANCE" && find . -maxdepth 1 -type f -printf '%f %s %T@\n' | sort); }
BEFORE="$(listing)"

# ---- the server install, copied once
if [ ! -d "$SERVER/libraries" ]; then
  echo "packboot: copying the rig's server install into the work folder (once)"
  cp -r "$SRC/libraries" "$SERVER/" || cannot "copying the server libraries failed"
fi
cp "$SRC/eula.txt" "$SERVER/" 2>/dev/null || echo "eula=true" > "$SERVER/eula.txt"
cp "$SRC/server.properties" "$SERVER/server.properties"
grep -v '^-Xm[sx]' "$SRC/user_jvm_args.txt" > "$SERVER/user_jvm_args.txt"
printf -- '-Xmx6G\n-Xms2G\n' >> "$SERVER/user_jvm_args.txt"
ARGS_FILE="$(cd "$SERVER" && ls libraries/net/neoforged/neoforge/*/win_args.txt 2>/dev/null | head -1)"
[ -n "$ARGS_FILE" ] || cannot "no NeoForge win_args.txt in the server install"

# ---- a free port outside Windows' reserved ranges
reserved="$(netsh interface ipv4 show excludedportrange protocol=tcp 2>/dev/null | awk '$1 ~ /^[0-9]+$/ && $2 ~ /^[0-9]+$/ {print $1" "$2}')"
port_free() {
  local p="$1" a b
  while read -r a b; do
    [ -n "$a" ] && [ "$p" -ge "$a" ] && [ "$p" -le "$b" ] && return 1
  done <<< "$reserved"
  netstat -an 2>/dev/null | grep -qE "[:.]$p[[:space:]]" && return 1
  return 0
}
PORT=""
for p in $(seq 25571 25578) $(seq 25700 25760); do
  if port_free "$p"; then PORT="$p"; break; fi
done
[ -n "$PORT" ] || cannot "no free port in 25571-25578 or 25700-25760"
sed -i "s/^server-port=.*/server-port=$PORT/; s/^query.port=.*/query.port=$((PORT + 1000))/; s/^enable-rcon=.*/enable-rcon=false/" "$SERVER/server.properties"
echo "packboot: pack $SHOWN ($(ls "$INSTANCE"/*.jar | wc -l) jars), port $PORT, work folder %TEMP%\\colony-seasons-packboot"

# ---- the boot runner (PowerShell: it writes "stop" to the server and kills only its own process on a timeout)
cat > "$WORK/boot.ps1" <<'PS'
param([string]$Dir, [string]$Java, [string]$ArgsFile, [int]$TimeoutSec = 900)
$ErrorActionPreference = 'Stop'
$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = $Java
$psi.Arguments = "@user_jvm_args.txt @$ArgsFile nogui"
$psi.WorkingDirectory = $Dir
$psi.UseShellExecute = $false
$psi.RedirectStandardInput = $true
$psi.RedirectStandardOutput = $true
$psi.RedirectStandardError = $true
$psi.CreateNoWindow = $true
$proc = [System.Diagnostics.Process]::Start($psi)
$proc.BeginOutputReadLine()
$proc.BeginErrorReadLine()
$log = Join-Path $Dir 'logs\latest.log'
$deadline = (Get-Date).AddSeconds($TimeoutSec)
$done = $false
while ((Get-Date) -lt $deadline -and -not $proc.HasExited) {
  Start-Sleep -Seconds 3
  if ((Test-Path $log) -and (Select-String -Path $log -Pattern 'Done (' -SimpleMatch -Quiet)) { $done = $true; break }
}
if ($done) {
  $proc.StandardInput.WriteLine('stop')
  $proc.StandardInput.Flush()
  if (-not $proc.WaitForExit(180000)) { Stop-Process -Id $proc.Id -Force; 'BOOT KILLED after stop' }
  'BOOT DONE'
} elseif ($proc.HasExited) {
  "BOOT EXITED $($proc.ExitCode) without Done"
} else {
  Stop-Process -Id $proc.Id -Force
  "BOOT TIMEOUT after $TimeoutSec s (killed its own process $($proc.Id))"
}
PS

# ---- the mod set: every jar of the pack; known client-only mods aside from the start
SET="$WORK/mods-set"
rm -rf "$SET" "$WORK/aside" "$WORK/newer.txt" && mkdir -p "$SET" "$WORK/aside"
cp "$INSTANCE"/*.jar "$SET/"
rm -f "$SET"/colony_seasons-*.jar
for pattern in sodium iris oculus embeddium rubidium entityculling immediatelyfast betterf3 fancymenu drippyloadingscreen \
               mousetweaks controlling toastcontrol searchables loadingscreen; do
  for j in "$SET"/*.jar; do
    case "$(basename "$j" | tr 'A-Z' 'a-z')" in *"$pattern"*) mv "$j" "$WORK/aside/" ;; esac
  done
done

boot() {  # boot <label> <with-jar 0|1>
  local label="$1" with="$2"
  rm -rf "$SERVER/mods" "$SERVER/world" "$SERVER/logs" "$SERVER/crash-reports" "$SERVER/config" "$SERVER/defaultconfigs"
  mkdir -p "$SERVER/mods" "$SERVER/config"
  echo 'versionCheck = false' > "$SERVER/config/fml.toml"     # no update checks from the test server
  cp "$SET"/*.jar "$SERVER/mods/"
  [ "$with" = 1 ] && cp "$JAR" "$SERVER/mods/"
  echo "packboot: boot '$label' with $(ls "$SERVER/mods"/*.jar | wc -l) jars"
  powershell -NoProfile -ExecutionPolicy Bypass -File "$(cygpath -w "$WORK/boot.ps1")" -Dir "$(cygpath -w "$SERVER")" \
    -Java "$(cygpath -w "$JAVA")" -ArgsFile "$ARGS_FILE" -TimeoutSec 900 | tr -d '\r' | tee "$WORK/$label.result"
  cp "$SERVER/logs/latest.log" "$WORK/$label.log" 2>/dev/null || : > "$WORK/$label.log"
  rm -rf "$WORK/$label-crash" && mkdir -p "$WORK/$label-crash"
  cp "$SERVER"/crash-reports/*.txt "$WORK/$label-crash/" 2>/dev/null || true
  grep -q 'BOOT DONE' "$WORK/$label.result"
}

# ---- the baseline: rounds until it reaches Done, the jars that cannot load set aside
round=1
while :; do
  if boot "baseline-$round" 0; then break; fi
  [ "$round" -ge 10 ] && cannot "the baseline never reached Done in 10 rounds (logs in the work folder)"
  # only the mods NeoForge's crash report names ("-- Mod loading issue for: x --", then "Mod file: .../x.jar"); a mod
  # that needs a newer NeoForge than the rig's server install is set aside the same way, and said so
  bad="$(cat "$WORK/baseline-$round-crash"/*.txt 2>/dev/null | grep -iE '^[[:space:]]*mod file: ' | grep -oE '[^\\/]+\.jar' | sort -u)"
  newer="$(cat "$WORK/baseline-$round-crash"/*.txt 2>/dev/null | grep -ioE 'mod [a-z0-9_]+ requires neoforge [0-9.]+ or above' | sort -u)"
  [ -n "$newer" ] && echo "$newer" >> "$WORK/newer.txt"
  moved=0
  for name in $bad; do
    if [ -f "$SET/$name" ]; then mv "$SET/$name" "$WORK/aside/"; moved=$((moved + 1)); echo "packboot: set aside $name (it does not load here)"; fi
  done
  [ "$moved" -gt 0 ] || cannot "the baseline did not reach Done and no jar could be named (see %TEMP%\\colony-seasons-packboot\\baseline-$round.log)"
  round=$((round + 1))
done
BASE="baseline-$round"

# ---- with the jar
WITH_OK=0
boot "with-jar" 1 && WITH_OK=1

ours() { grep -E 'ERROR' "$1" | grep -iE 'colony_seasons|me\.lovkar\.seasons' | sed -E 's/^\[[^]]*\] //' | sort -u; }
NEW_ERRORS="$(comm -13 <(ours "$WORK/$BASE.log") <(ours "$WORK/with-jar.log"))"
CRASHES="$(ls "$WORK/with-jar-crash" 2>/dev/null)"
OUR_WARN="$(grep -E 'WARN' "$WORK/with-jar.log" | grep -ciE 'colony_seasons|me\.lovkar\.seasons')"
LOADED="$(grep -c 'colony_seasons' "$WORK/with-jar.log")"

echo "packboot: set aside ($(ls "$WORK/aside" | wc -l)): $(ls "$WORK/aside" | tr '\n' ' ')"
if [ -f "$WORK/newer.txt" ]; then
  echo "packboot: of those, needing a newer NeoForge than the rig's server install: $(sort -u "$WORK/newer.txt" | tr '\n' ';')"
fi
echo "packboot: baseline reached Done in round $round; with the jar: $([ "$WITH_OK" = 1 ] && echo 'Done' || echo 'NOT Done')"
echo "packboot: ERROR lines in the logs: baseline $(grep -c 'ERROR' "$WORK/$BASE.log"), with the jar $(grep -c 'ERROR' "$WORK/with-jar.log"); lines naming the mod $LOADED, its WARN lines $OUR_WARN"

AFTER="$(listing)"
status=0
if [ "$BEFORE" != "$AFTER" ]; then echo "packboot: FAIL the pack instance's mods folder changed during the run"; status=1; fi
if [ "$WITH_OK" != 1 ]; then echo "packboot: FAIL the server with the jar did not reach Done ($(cat "$WORK/with-jar.result" | tail -1))"; status=1; fi
if [ -n "$CRASHES" ]; then echo "packboot: FAIL crash report(s) with the jar: $CRASHES"; status=1; fi
if [ -n "$NEW_ERRORS" ]; then echo "packboot: FAIL new ERROR lines naming the mod:"; echo "$NEW_ERRORS" | head -20; status=1; fi
[ "$status" = 0 ] && echo "packboot: PASS no new ERROR naming colony_seasons and no crash with the jar added"
exit "$status"
