#!/usr/bin/env bash
# Renders docs/renders/q6/ from the shipped square blueprints (tools/render_q6.py): five levels by day and the level-5
# winter square at night. One Blender process: never run it beside a probe.
cd "$(dirname "${BASH_SOURCE[0]}")/.." || exit 1
. tools/rigpaths.sh
python tools/render_q6.py "$@"
