#!/bin/bash
# Runs inside the build container: our own X clients, the window manager and the driver check.
set -euo pipefail
BIN=/out/programs$PREFIX/bin
rm -rf /out/programs && mkdir -p $BIN
gcc -O2 -Wall -o $BIN/pocketfoundry-wm /runtime/programs/pocketfoundry-wm.c -lxcb -lxcb-randr
gcc -O2 -Wall -o $BIN/pocketfoundry-drivercheck /runtime/programs/pocketfoundry-drivercheck.c -lGL -lX11
strip $BIN/*
echo "built: $(file -b $BIN/pocketfoundry-wm)"
