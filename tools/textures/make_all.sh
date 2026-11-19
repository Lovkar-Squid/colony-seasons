#!/usr/bin/env bash
# Writes the decoration blocks' textures, models and blockstates (tools/textures/make_all.py) with the rig's paths.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."
. tools/rigpaths.sh
python tools/textures/make_all.py
