#!/usr/bin/env bash
# Every static check of the repo. Prints ALL OK only when each one passed; exits non-zero otherwise.
# Run it after `bash tools/build.sh` (the jar check needs the built jar). Later chunks add their checks here.
cd "$(dirname "${BASH_SOURCE[0]}")/.." || exit 1
. tools/rigpaths.sh
failed=0

step() {
  local name="$1"; shift
  echo "== $name"
  if "$@"; then :; else echo "!! FAILED: $name"; failed=1; fi
}

step "lang keys"  python -I tools/check_lang.py
step "models"     python -I tools/check_models.py
step "clock"      python -I tools/check_clock.py
step "clothes data" python -I tools/check_clothes.py "$RIG_DIR/basemods"
step "block files" python -I tools/check_blocks.py
step "kits"       python -I tools/check_kits.py
step "skins"      python -I tools/check_skins.py
step "public text" python -I tools/check_public.py
step "blueprints" python -I tools/build_pack.py --check "$RIG_DIR/basemods"
step "placing code" python -I tools/check_decor.py
step "test piece"  python -I tools/csdecorgate/make_piece.py --check
step "square hut block" python -I tools/gen_square_hut.py --check
step "festival"    python -I tools/check_festival.py
step "packets"     python -I tools/check_packets.py
step "windows"     python -I tools/check_windows.py
step "links"       python -I tools/check_links.py
step "0.1.0 reader" python -I tools/check_reader.py
step "market prices" python -I tools/check_market.py
step "script syntax" bash -c 'for f in tools/packboot.sh tools/probe.sh tools/restart.sh tools/build.sh tools/cstradepost/prepare.sh tools/cstradepost/cleanup.sh tools/csfullcycle/prepare.sh tools/csfullcycle/cleanup.sh; do bash -n "$f" || exit 1; done'

version="$(sed -n 's/^version="\([^"]*\)".*/\1/p' resources/META-INF/neoforge.mods.toml | head -1)"
jar="colony_seasons-$version.jar"
if [ -f "$jar" ]; then
  step "jar reads sequentially" "$JDK_BIN/java.exe" -Xshare:off tools/JarCheck.java "$jar"
  step "no Trade Post reference" python -I tools/check_nodeps.py
  step "market arithmetic" "$JDK_BIN/java.exe" -Xshare:off -cp "$jar" tools/unit/MarketMathTest.java
else
  echo "== jar reads sequentially"
  echo "!! FAILED: $jar not found (run bash tools/build.sh first)"
  failed=1
fi

if [ "$failed" -eq 0 ]; then
  echo "ALL OK"
else
  echo "CHECKS FAILED"
  exit 1
fi
