#!/usr/bin/env bash
# Run by tools/probe.sh before the rig starts. Builds the stand-in jar the bridge modes need (rigjars-<mode>.txt names it).
set -euo pipefail
case "${1:-}" in
  bridge-*) bash "$(dirname "${BASH_SOURCE[0]}")/../sereneshim/build.sh" ;;
esac
