#!/usr/bin/env bash
# Renders the decoration blocks for the look check (tools/render_decor.py) with the rig's paths and Blender.
#   bash tools/render_decor.sh [name ...]     (names: wreath-day, closeup-night, square-night, sheet, ...)
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
. tools/rigpaths.sh
export BLENDER="${BLENDER:-C:/Program Files/Blender Foundation/Blender 5.1/blender.exe}"
python tools/render_decor.py "$@"
