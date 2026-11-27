#!/usr/bin/env bash
# Run by tools/probe.sh before the rig starts. Mode serene needs the Serene Seasons stand-in (rigjars-serene.txt names it);
# mode trade-post the Trade Post jar (rigjars-trade-post.txt), copied by cstradepost's own script, which refuses to run
# unless the private rig copy is in use.
set -euo pipefail
here="$(dirname "${BASH_SOURCE[0]}")"
case "${1:-}" in
  serene) bash "$here/../sereneshim/build.sh" ;;
  trade-post) bash "$here/../cstradepost/prepare.sh" with ;;
esac
