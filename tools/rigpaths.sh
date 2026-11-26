#!/usr/bin/env bash
# The machine paths of this PC's build and test rig, in one place. Sourced by build.sh, probe.sh and
# checks.sh. Nothing here ships: a later chunk that wants the scripts to run elsewhere changes only this file.
RIG_DIR="D:/Claude/addon-rig"                          # build-addon.ps1, run-rig.ps1, basemods/, probes/
JDK_BIN="D:/Claude/enchanting-addon/tools/jdk21/bin"   # javac, jar, java
PRIVATE_RIG_DIR="D:/Claude/colony-seasons-rig"            # made by tools/private-rig.sh when the shared rig port is blocked
SEASONS_LIBS="D:/Claude/voyager-libs"                  # NeoForge, Minecraft assets jar and friends
MC_ASSETS_JAR="client-1.21.1-20240808.144430-extra.jar"
export SEASONS_LIBS MC_ASSETS_JAR
MINECOLONIES_JAR="$RIG_DIR/basemods/minecolonies-1.1.1420-1.21.1.jar"   # the pack's MineColonies (citizen models and skins for the previews)
BLENDER="C:/Program Files/Blender Foundation/Blender 5.1/blender.exe"   # headless preview renders (tools/preview.sh)
VOYAGER_JAR="D:/Claude/voyager-040/repo/voyager-0.4.0.jar"   # read only: the csfestival probe runs it next to the mod (mode voyager)
TRADEPOST_JAR="$SEASONS_LIBS/mctradepost-1.06.012-1.21.1.jar"   # read only: the cstradepost probe runs it next to the mod (modes with, restart) and check_market.py reads its price list
export JDK_BIN MINECOLONIES_JAR BLENDER VOYAGER_JAR TRADEPOST_JAR
# run-rig.ps1 comes from the private copy when there is one (see private-rig.sh), else from the shared rig
if [ -f "$PRIVATE_RIG_DIR/run-rig.ps1" ]; then RUN_RIG_DIR="$PRIVATE_RIG_DIR"; else RUN_RIG_DIR="$RIG_DIR"; fi
