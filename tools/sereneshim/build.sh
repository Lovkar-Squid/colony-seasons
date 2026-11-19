#!/usr/bin/env bash
# Builds the Serene Seasons stand-in (tools/sereneshim/src) into tools/out/sereneshim.jar.
# The jar is for the rig only (cscalendar bridge modes): it is never part of colony_seasons-<version>.jar.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."
. tools/rigpaths.sh
out="tools/out/sereneshim"
rm -rf "$out"; mkdir -p "$out/classes"
cp="$SEASONS_LIBS/neoforge-21.1.249-client.jar"
for j in "$SEASONS_LIBS"/*.jar; do
  case "$(basename "$j")" in neoforge-*-client.jar|voyager*|minecolonies*|structurize*|blockui*|domum*|exposure*|mctradepost*) continue ;; esac
  cp="$cp;$j"
done
find tools/sereneshim/src -name '*.java' > "$out/src.args"
MSYS_NO_PATHCONV=1 "$JDK_BIN/javac.exe" -encoding UTF-8 --release 21 -proc:none -nowarn -cp "$cp" -d "$out/classes" @"$out/src.args"
"$JDK_BIN/jar.exe" cf "tools/out/sereneshim.jar" -C "$out/classes" . -C tools/sereneshim/res .
echo "built tools/out/sereneshim.jar"
