#!/usr/bin/env bash
# Renders docs/renders/q12/ from the shipped level-3 square blueprint and the shipped touch pieces (tools/render_q12.py): the
# four seasons' touches and the tree's foot in the Winter Festival's window. One Blender process: never run it beside a probe.
cd "$(dirname "${BASH_SOURCE[0]}")/.." || exit 1
. tools/rigpaths.sh
python tools/render_q12.py "$@"
