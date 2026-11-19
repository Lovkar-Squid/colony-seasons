#!/usr/bin/env bash
# The extra jars of the csfestival modes, made after tools/probe.sh has built the mod and the probe.
#   bash tools/csfestival/prepare.sh <mode>
#   nomixin  tools/out/csfestival-nomixin/colony_seasons-nomixin.jar: the mod built again with jar from the same
#            build/ and resources/, without its mixin (no [[mixins]] entry in neoforge.mods.toml, no mixin config, no
#            mixin class); tools/csfestival/modjar-nomixin.txt makes probe.sh run it instead of the real jar, which
#            stays as it is
#   voyager  the Voyager 0.4.0 jar (its path is VOYAGER_JAR in tools/rigpaths.sh) copied to tools/out/, read only;
#            rigjars-voyager.txt puts it next to the mod
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."
. tools/rigpaths.sh
mode="${1:-}"
case "$mode" in
  nomixin)
    out="tools/out/csfestival-nomixin"
    rm -rf "$out"
    mkdir -p "$out/classes" "$out/res"
    cp -r build/. "$out/classes/"
    rm -rf "$out/classes/me/lovkar/seasons/mixin"
    cp -r resources/. "$out/res/"
    rm -f "$out/res/colony_seasons.mixins.json"
    # the [[mixins]] table and its config line go; everything else of the file stays as it is
    sed -i '/^\[\[mixins\]\]/,/^config=/d' "$out/res/META-INF/neoforge.mods.toml"
    if grep -q '^config="colony_seasons.mixins.json"' "$out/res/META-INF/neoforge.mods.toml"; then
      echo "prepare.sh: the mixin entry is still in the nomixin toml" >&2
      exit 1
    fi
    "$JDK_BIN/jar.exe" cf "$out/colony_seasons-nomixin.jar" -C "$out/classes" . -C "$out/res" .
    "$JDK_BIN/jar.exe" tf "$out/colony_seasons-nomixin.jar" > "$out/listing.txt"
    if grep -q 'mixin' "$out/listing.txt"; then
      echo "prepare.sh: the nomixin jar still holds a mixin file" >&2
      exit 1
    fi
    echo "built $out/colony_seasons-nomixin.jar (no mixin)"
    ;;
  voyager)
    mkdir -p tools/out
    cp "$VOYAGER_JAR" tools/out/voyager-0.4.0.jar
    echo "copied the Voyager 0.4.0 jar to tools/out/"
    ;;
esac
