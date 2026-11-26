#!/usr/bin/env bash
# Runs after every cstradepost run (tools/probe.sh and tools/restart.sh call it, whatever the result): the Trade Post jar
# must not stay in the private rig copy's mods folder, where another probe's run would find it. Every run of the rig wipes
# that folder first anyway; this is the belt to that brace. Never touches the shared rig, the owner's instances or his server.
set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."
. tools/rigpaths.sh
if [ "$RUN_RIG_DIR" = "$PRIVATE_RIG_DIR" ] && [ -d "$PRIVATE_RIG_DIR/rig/mods" ]; then
  rm -f "$PRIVATE_RIG_DIR"/rig/mods/mctradepost*.jar
  if ls "$PRIVATE_RIG_DIR"/rig/mods/mctradepost*.jar >/dev/null 2>&1; then
    echo "cleanup.sh: the Trade Post jar is still in the private rig's mods" >&2
    exit 1
  fi
  echo "the Trade Post jar is out of the private rig's mods"
fi
