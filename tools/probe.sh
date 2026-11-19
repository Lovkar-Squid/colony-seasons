#!/usr/bin/env bash
# Builds the mod and one probe, then runs the probe on the shared test rig.
#   bash tools/probe.sh <probe> [mode]
# <probe> is a folder under tools/ (csboot, csconfig, ...). Optional files in that folder:
#   rigjars.txt             extra jars for the rig, one per line (or comma separated)
#   rigconfig-<mode>/       the config folder for -Mode <mode>
#   rigconfig/              the config folder when no mode is given, and for a mode without its own folder
#                           (except mode 'defaults', which must have its own folder and never falls back)
# The run fails on a probe FAIL line and on any ERROR/Exception line that names the mod or the probe.
# Prints the rig's summary and exits with run-rig's exit code. The rig takes one run at a time and a
# run may wait up to 40 minutes for another session's: start this in the background where needed.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
. tools/rigpaths.sh
probe="${1:-}"
mode="${2:-}"
if [ -z "$probe" ] || [ ! -d "tools/$probe" ]; then
  echo "usage: bash tools/probe.sh <probe> [mode]   (tools/<probe>/ must exist)" >&2
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
    jars="$jars,$line"
  done < "tools/$probe/rigjars.txt"
fi

args=(-Name "$probe" -Jars "$jars" -ErrorMatch "colony_seasons|me\.lovkar\.seasons|$probe")
if [ -n "$mode" ] && [ -d "tools/$probe/rigconfig-$mode" ]; then
  args+=(-Mode "$mode" -Config "$repo/tools/$probe/rigconfig-$mode")
elif [ -n "$mode" ] && [ "$mode" != "defaults" ] && [ -d "tools/$probe/rigconfig" ]; then
  # a mode without its own folder reads the probe's one rigconfig/ (csconfig seeded)
  args+=(-Mode "$mode" -Config "$repo/tools/$probe/rigconfig")
elif [ -n "$mode" ]; then
  # mode "defaults" never falls back to a seeded folder: it would check the defaults against seeded values
  echo "probe $probe has no config folder for mode '$mode' (tools/$probe/rigconfig-$mode)" >&2
  exit 2
elif [ -d "tools/$probe/rigconfig" ]; then
  args+=(-Config "$repo/tools/$probe/rigconfig")
fi

# a config file left in the rig by an earlier run (a seeded one) must not leak into a defaults run
rm -f "$RUN_RIG_DIR/rig/config/colony_seasons-server.toml"

powershell -NoProfile -ExecutionPolicy Bypass -File "$RUN_RIG_DIR/run-rig.ps1" "${args[@]}"
