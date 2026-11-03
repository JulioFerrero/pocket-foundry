#!/bin/bash
# Runs inside the build container.
set -euo pipefail
mkdir -p /out/wm
gcc -O2 -Wall -o /out/wm/pocketfoundry-wm /runtime/wm/pocketfoundry-wm.c -lxcb -lxcb-randr
# The driver check, an X client like the window manager.
gcc -O2 -Wall -o /out/wm/pocketfoundry-drivercheck /runtime/wm/pocketfoundry-drivercheck.c -lGL -lX11
strip /out/wm/pocketfoundry-wm /out/wm/pocketfoundry-drivercheck
echo "built: $(file -b /out/wm/pocketfoundry-wm)"
