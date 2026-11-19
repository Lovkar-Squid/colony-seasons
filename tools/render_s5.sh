#!/usr/bin/env bash
# Renders the S5 blueprints for the owner and the pack icon (tools/render_s5.py) with the rig's paths and Blender.
#   bash tools/render_s5.sh [name ...]     (names: hut-l1..hut-l5, tree-small, tree-full, tree-rich, tree-grand,
#                                           levels, compare, icon)
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
. tools/rigpaths.sh
export BLENDER="${BLENDER:-C:/Program Files/Blender Foundation/Blender 5.1/blender.exe}"
python tools/render_s5.py "$@"
