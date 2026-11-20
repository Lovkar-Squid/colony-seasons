#!/usr/bin/env bash
# Run by tools/probe.sh before the rig starts. Mode serene needs the Serene Seasons stand-in (rigjars-serene.txt names it).
set -euo pipefail
case "${1:-}" in
  serene) bash "$(dirname "${BASH_SOURCE[0]}")/../sereneshim/build.sh" ;;
esac
