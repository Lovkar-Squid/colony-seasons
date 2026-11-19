#!/usr/bin/env bash
# The machine paths of this PC's build and test rig, in one place. Sourced by build.sh, probe.sh and
# checks.sh. Nothing here ships: a later chunk that wants the scripts to run elsewhere changes only this file.
RIG_DIR="D:/Claude/addon-rig"                          # build-addon.ps1, run-rig.ps1, basemods/, probes/
JDK_BIN="D:/Claude/enchanting-addon/tools/jdk21/bin"   # javac, jar, java
PRIVATE_RIG_DIR="D:/Claude/colony-seasons-rig"            # made by tools/private-rig.sh when the shared rig port is blocked
SEASONS_LIBS="D:/Claude/voyager-libs"                  # NeoForge, Minecraft assets jar and friends
MC_ASSETS_JAR="client-1.21.1-20240808.144430-extra.jar"
export SEASONS_LIBS MC_ASSETS_JAR
# run-rig.ps1 comes from the private copy when there is one (see private-rig.sh), else from the shared rig
if [ -f "$PRIVATE_RIG_DIR/run-rig.ps1" ]; then RUN_RIG_DIR="$PRIVATE_RIG_DIR"; else RUN_RIG_DIR="$RIG_DIR"; fi
