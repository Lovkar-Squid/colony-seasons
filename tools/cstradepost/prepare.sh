#!/usr/bin/env bash
# The extra jar of the cstradepost modes that run next to Trade Post for MineColonies, made after tools/probe.sh (or
# tools/restart.sh) has built the mod and the probe.
#   bash tools/cstradepost/prepare.sh <mode>
#   with      copies the Trade Post jar (TRADEPOST_JAR in tools/rigpaths.sh, read only) to tools/out/;
#             rigjars-with.txt puts it next to the mod
#   restart   the same for tools/restart.sh (rigjars-restart.txt); the last phase drops it again (dropjars.txt)
#   without   nothing: the mod runs alone
# Refuses to run unless the private rig copy (tools/private-rig.sh) is the one in use: the jar goes into that copy's
# mods folder for the run and nowhere else, and tools/cstradepost/cleanup.sh takes it out again afterwards.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."
. tools/rigpaths.sh
mode="${1:-}"
case "$mode" in
  with|restart)
    if [ "$RUN_RIG_DIR" != "$PRIVATE_RIG_DIR" ]; then
      echo "prepare.sh: the private rig copy is not in use ($PRIVATE_RIG_DIR/run-rig.ps1 is missing): run bash tools/private-rig.sh first" >&2
      exit 1
    fi
    if [ ! -f "$TRADEPOST_JAR" ]; then
      echo "prepare.sh: the Trade Post jar is not at $TRADEPOST_JAR" >&2
      exit 1
    fi
    mkdir -p tools/out
    cp "$TRADEPOST_JAR" tools/out/
    echo "copied $(basename "$TRADEPOST_JAR") to tools/out/ (read only source)"
    ;;
esac
