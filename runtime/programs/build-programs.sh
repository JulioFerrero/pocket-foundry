#!/bin/bash
# Runs inside the build container: our own X clients, the window manager, the driver check and the speed test.
set -euo pipefail
BIN=/out/programs$PREFIX/bin
rm -rf /out/programs && mkdir -p $BIN
gcc -O2 -Wall -o $BIN/pocketfoundry-wm /runtime/programs/pocketfoundry-wm.c -lxcb -lxcb-randr
gcc -O2 -Wall -o $BIN/pocketfoundry-drivercheck /runtime/programs/pocketfoundry-drivercheck.c -lGL -lX11
gcc -O2 -Wall -o $BIN/pocketfoundry-speedtest /runtime/programs/pocketfoundry-speedtest.c -lGL -lX11 -lm
strip $BIN/*
echo "built: $(file -b $BIN/pocketfoundry-wm)"
