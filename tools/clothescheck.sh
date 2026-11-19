#!/usr/bin/env bash
# The winter clothes without a game client (chunk S6):
#   (a) the decision of who wears what (ClothesRules, HatModels, Season compiled with tools/clothescheck/ClothesCheck.java
#       against Gson only, no Minecraft class), including MineColonies' December hat and the shipped allow-list;
#   (b) the static checks of the clothes data (tools/check_clothes.py, also part of tools/checks.sh).
# Prints PASS or FAIL last; exits non-zero on a failure.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
. tools/rigpaths.sh
out="tools/out/clothescheck"
rm -rf "$out"
mkdir -p "$out"
gson="$(ls "$SEASONS_LIBS"/gson-*.jar | head -1)"
"$JDK_BIN/javac.exe" -encoding UTF-8 --release 21 -proc:none -cp "$gson" -d "$out" \
  src/me/lovkar/seasons/calendar/Season.java \
  src/me/lovkar/seasons/client/ClothesRules.java \
  src/me/lovkar/seasons/client/HatModels.java \
  tools/clothescheck/ClothesCheck.java
failed=0
echo "== clothes rules (no game)"
# an absolute class path: Git Bash rewrites a relative "dir;D:/x.jar" into nonsense before java sees it
"$JDK_BIN/java.exe" -Xshare:off -cp "$(pwd -W)/$out;$gson" ClothesCheck resources/assets/colony_seasons/clothes/hat_models.json || failed=1
echo "== clothes data"
python -I tools/check_clothes.py "$RIG_DIR/basemods" || failed=1
if [ "$failed" -eq 0 ]; then echo "PASS"; else echo "FAIL"; exit 1; fi
