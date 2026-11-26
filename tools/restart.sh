#!/usr/bin/env bash
# Restart check: two server runs on ONE kept world. Phase 1 (mode restart-save) sets state and lets the world save,
# phase 2 (mode restart-load) starts the server again on the same world and reads the state back. A probe whose
# tools/<probe>/phases.txt exists runs the phases listed there instead (csfullcycle: restart-save,restart-mid,restart-load).
#   bash tools/restart.sh <probe>
# <probe> is a folder under tools/ whose probe understands the modes restart-save and restart-load (cscalendar; S3 and S4
# add their own). Optional there: rigjars.txt (extra jars, %REPO% = this repo), rigconfig-restart/ (seeded config).
# It uses tools/rig/run-rig-keep.ps1, a copy of the rig's run-rig.ps1 that wipes the world once instead of per run and
# holds the rig lock across both runs. Like probe.sh it may wait up to 40 minutes for another session's run.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
. tools/rigpaths.sh
probe="${1:-}"
if [ -z "$probe" ] || [ ! -d "tools/$probe" ]; then
  echo "usage: bash tools/restart.sh <probe>   (tools/<probe>/ must exist and know the modes restart-save and restart-load)" >&2
  exit 2
fi
repo="$(pwd -W)"
version="$(sed -n 's/^version="\([^"]*\)".*/\1/p' resources/META-INF/neoforge.mods.toml | head -1)"
if [ -z "$version" ]; then echo "no version in resources/META-INF/neoforge.mods.toml" >&2; exit 2; fi

powershell -NoProfile -ExecutionPolicy Bypass -File "$RIG_DIR/build-addon.ps1" -Repo "$repo" -Probe "$probe"

# an optional tools/<probe>/rigjars-restart.txt lists extra jars for the restart run only; the probe's prepare.sh makes them
# first (as `prepare.sh restart`; cstradepost: the Trade Post jar, which dropjars.txt takes out again before its last phase)
if [ -f "tools/$probe/rigjars-restart.txt" ] && [ -f "tools/$probe/prepare.sh" ]; then bash "tools/$probe/prepare.sh" restart; fi
jars="$repo/colony_seasons-$version.jar,$RIG_DIR/probes/$probe.jar"
for list in "tools/$probe/rigjars.txt" "tools/$probe/rigjars-restart.txt"; do
  if [ -f "$list" ]; then
    while IFS= read -r line || [ -n "$line" ]; do
      line="${line%$'\r'}"
      case "$line" in ''|'#'*) continue ;; esac
      jars="$jars,${line//%REPO%/$repo}"
    done < "$list"
  fi
done

# a probe with more than two phases names them in tools/<probe>/phases.txt (one line: mode words, comma-separated)
phases="restart-save,restart-load"
if [ -f "tools/$probe/phases.txt" ]; then
  phases="$(head -1 "tools/$probe/phases.txt" | tr -d '\r ')"
fi
args=(-Name "$probe" -Jars "$jars" -Phases "$phases" -RigDir "$RUN_RIG_DIR" -ErrorMatch "colony_seasons|me\.lovkar\.seasons|$probe")
if [ -d "tools/$probe/rigconfig-restart" ]; then
  args+=(-Config "$repo/tools/$probe/rigconfig-restart")
fi

# an optional tools/<probe>/dropjars.txt: lines "<phase> <jar name>", the jar is deleted from the rig's mods before that phase
if [ -f "tools/$probe/dropjars.txt" ]; then
  drops=""
  while IFS= read -r line || [ -n "$line" ]; do
    line="${line%$'\r'}"
    case "$line" in ''|'#'*) continue ;; esac
    drops="${drops:+$drops,}${line%% *}=${line#* }"
  done < "tools/$probe/dropjars.txt"
  if [ -n "$drops" ]; then args+=(-Drop "$drops"); fi
fi

# a config file left in the rig by an earlier run must not leak into this one
rm -f "$RUN_RIG_DIR/rig/config/colony_seasons-server.toml"

rc=0
powershell -NoProfile -ExecutionPolicy Bypass -File "$repo/tools/rig/run-rig-keep.ps1" "${args[@]}" || rc=$?
# an optional cleanup.sh runs after the rig, whatever the result, and the rig's exit code is kept
if [ -f "tools/$probe/cleanup.sh" ]; then bash "tools/$probe/cleanup.sh" restart || true; fi
exit "$rc"
