#!/usr/bin/env bash
# The clothes preview renders (docs/renders/s6/): reads MineColonies' citizen models out of its jar, then renders the
# sheets in Blender headless. Arguments go to tools/entity_preview.py ([--out <folder>] [--date YYYY-MM-DD] [sheet ...]).
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
. tools/rigpaths.sh
python -I tools/mc_models.py "$MINECOLONIES_JAR"
# not -I: the sheets are shrunk to a 256-colour palette with Pillow, which may live in the user's site-packages
python tools/entity_preview.py "$@"
