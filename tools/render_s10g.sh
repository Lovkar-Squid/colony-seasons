#!/usr/bin/env bash
# Renders of chunk S10g1 (tools/render_s10g.py) with the rig's paths and Blender.
#   bash tools/render_s10g.sh [name ...]     (names: blocks, l1, l2, l3, l4, l5, levels; none = all)
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
. tools/rigpaths.sh
export BLENDER="${BLENDER:-C:/Program Files/Blender Foundation/Blender 5.1/blender.exe}"
python tools/render_s10g.py "$@"
