#!/usr/bin/env bash
# Restart check: two server runs on ONE kept world. Phase 1 (mode restart-save) sets state and lets the world save,
# phase 2 (mode restart-load) starts the server again on the same world and reads the state back.
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

jars="$repo/colony_seasons-$version.jar,$RIG_DIR/probes/$probe.jar"
if [ -f "tools/$probe/rigjars.txt" ]; then
  while IFS= read -r line || [ -n "$line" ]; do
    line="${line%$'\r'}"
    case "$line" in ''|'#'*) continue ;; esac
    jars="$jars,${line//%REPO%/$repo}"
  done < "tools/$probe/rigjars.txt"
fi

args=(-Name "$probe" -Jars "$jars" -Phases "restart-save,restart-load" -RigDir "$RUN_RIG_DIR" -ErrorMatch "colony_seasons|me\.lovkar\.seasons|$probe")
if [ -d "tools/$probe/rigconfig-restart" ]; then
  args+=(-Config "$repo/tools/$probe/rigconfig-restart")
fi

# a config file left in the rig by an earlier run must not leak into this one
rm -f "$RUN_RIG_DIR/rig/config/colony_seasons-server.toml"

powershell -NoProfile -ExecutionPolicy Bypass -File "$repo/tools/rig/run-rig-keep.ps1" "${args[@]}"
