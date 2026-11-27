#!/usr/bin/env bash
# Runs after every csfullcycle run (tools/probe.sh and tools/restart.sh call it, whatever the result): the Trade Post jar
# of mode trade-post must not stay in the private rig copy's mods folder. Idempotent; cstradepost's own cleanup does it.
set -uo pipefail
bash "$(dirname "${BASH_SOURCE[0]}")/../cstradepost/cleanup.sh" "${1:-}"
