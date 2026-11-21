#!/usr/bin/env bash
# Builds the mod and one probe, then runs the probe on the shared test rig.
#   bash tools/probe.sh <probe> [mode]
# <probe> is a folder under tools/ (csboot, csconfig, ...). Optional files in that folder:
#   rigjars.txt             extra jars for the rig in every mode, one per line (%REPO% stands for this repo's folder)
#   rigjars-<mode>.txt      the same, for -Mode <mode> only (the Serene Seasons stand-in joins only the bridge modes)
#   modjar-<mode>.txt       one jar run INSTEAD of the mod's jar in that mode (%REPO% as above; csfestival nomixin)
#   prepare.sh              run as `bash tools/<probe>/prepare.sh <mode>` before the rig starts (builds extra jars)
#   rigconfig-<mode>/       the config folder for -Mode <mode>
#   rigconfig/              the config folder when no mode is given, and for a mode without its own folder
#                           (except mode 'defaults', which never falls back to it); with neither, nothing is seeded
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

if [ -f "tools/$probe/prepare.sh" ]; then bash "tools/$probe/prepare.sh" "$mode"; fi

modjar="$repo/colony_seasons-$version.jar"
# a mode may run the mod built another way instead (csfestival nomixin: the jar without its mixin, made by prepare.sh)
if [ -n "$mode" ] && [ -f "tools/$probe/modjar-$mode.txt" ]; then
  while IFS= read -r line || [ -n "$line" ]; do
    line="${line%$'\r'}"
    case "$line" in ''|'#'*) continue ;; esac
    modjar="${line//%REPO%/$repo}"
  done < "tools/$probe/modjar-$mode.txt"
fi
jars="$modjar,$RIG_DIR/probes/$probe.jar"
for list in "tools/$probe/rigjars.txt" ${mode:+"tools/$probe/rigjars-$mode.txt"}; do
  if [ -f "$list" ]; then
    while IFS= read -r line || [ -n "$line" ]; do
      line="${line%$'\r'}"
      case "$line" in ''|'#'*) continue ;; esac
      jars="$jars,${line//%REPO%/$repo}"
    done < "$list"
  fi
done

args=(-Name "$probe" -Jars "$jars" -ErrorMatch "colony_seasons|me\.lovkar\.seasons|$probe")
if [ -n "$mode" ]; then
  args+=(-Mode "$mode")
fi
if [ -n "$mode" ] && [ -d "tools/$probe/rigconfig-$mode" ]; then
  args+=(-Config "$repo/tools/$probe/rigconfig-$mode")
elif [ "$mode" != "defaults" ] && [ -d "tools/$probe/rigconfig" ]; then
  # no mode, or a mode without its own folder: the probe's one rigconfig/ (csconfig seeded).
  # Mode "defaults" never falls back to it: it would check the defaults against seeded values.
  args+=(-Config "$repo/tools/$probe/rigconfig")
fi
# no config folder at all: the run seeds nothing (a mode is still passed to the probe)

# a config file left in the rig by an earlier run (a seeded one) must not leak into a defaults run; the client
# file too, which csconfig opens itself (S10i), as a dedicated server never does
rm -f "$RUN_RIG_DIR/rig/config/colony_seasons-server.toml" "$RUN_RIG_DIR/rig/config/colony_seasons-client.toml"

powershell -NoProfile -ExecutionPolicy Bypass -File "$RUN_RIG_DIR/run-rig.ps1" "${args[@]}"
