#!/usr/bin/env bash
# Makes (or refreshes) a private copy of the shared test rig, with its own server port.
#
# Why: the shared rig's server listens on port 25669, and Windows can reserve that port for Hyper-V /
# WinNAT ("netsh interface ipv4 show excludedportrange protocol=tcp"). Then every run dies with
# "FAILED TO BIND TO PORT ... Address already in use: bind" before the first tick. The shared folder
# is not ours to edit, so this copies it (about 400 MB) next to the repo and changes the port only there.
# tools/rigpaths.sh uses the copy for run-rig.ps1 as soon as it exists. Safe to run again.
#   bash tools/private-rig.sh [port]        (default port 25571, below the reserved range)
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
. tools/rigpaths.sh
port="${1:-25571}"
dest="$PRIVATE_RIG_DIR"
mkdir -p "$dest/rig"
cp "$RIG_DIR/run-rig.ps1" "$dest/run-rig.ps1"
cp -r "$RIG_DIR/basemods" "$dest/"
# the server install and its settings; worlds, configs, logs, mods and crash reports are made fresh by every run
for item in libraries eula.txt user_jvm_args.txt server.properties ops.json whitelist.json banned-ips.json banned-players.json; do
  if [ -e "$RIG_DIR/rig/$item" ] && [ ! -e "$dest/rig/$item" ]; then cp -r "$RIG_DIR/rig/$item" "$dest/rig/"; fi
done
# the shared rig already ran the launcher once: the server needs its libraries folder next to server.properties
sed -i "s/^server-port=.*/server-port=$port/; s/^query.port=.*/query.port=$((port - 100))/" "$dest/rig/server.properties"
echo "private rig ready in $dest (server port $port)"
