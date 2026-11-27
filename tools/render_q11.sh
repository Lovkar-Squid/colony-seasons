#!/usr/bin/env bash
# Renders docs/renders/q11/ from the shipped street pieces (tools/render_q11.py): each new block and a marked street by day
# and lit at night. One Blender process: never run it beside a probe.
cd "$(dirname "${BASH_SOURCE[0]}")/.." || exit 1
. tools/rigpaths.sh
python tools/render_q11.py "$@"
