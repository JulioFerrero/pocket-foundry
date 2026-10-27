#!/bin/bash
# Runs inside the build container.
set -euo pipefail
mkdir -p /out/wm
gcc -O2 -Wall -o /out/wm/pocketfoundry-wm "$(dirname "$(realpath "$0")")/pocketfoundry-wm.c" -lxcb -lxcb-randr
strip /out/wm/pocketfoundry-wm
echo "built: $(file -b /out/wm/pocketfoundry-wm)"
