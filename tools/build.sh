#!/usr/bin/env bash
# Builds the mod: colony_seasons-<version>.jar at the repo root. Exits non-zero when the build fails.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
. tools/rigpaths.sh
powershell -NoProfile -ExecutionPolicy Bypass -File "$RIG_DIR/build-addon.ps1" -Repo "$(pwd -W)"
