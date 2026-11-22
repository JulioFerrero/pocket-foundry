#!/bin/bash
# A piece (build.sh), after sdl: our own X clients, the window manager, the driver check and the speed test.
set -euo pipefail
. "$RUNTIME/toolchain.sh"
BIN=$OUT/programs$PREFIX/bin
rm -rf "$OUT/programs" && mkdir -p "$BIN"
$CC -O2 -Wall -o "$BIN/pocketfoundry-wm" "$RUNTIME/programs/pocketfoundry-wm.c" -lxcb -lxcb-randr
$CC -O2 -Wall -o "$BIN/pocketfoundry-drivercheck" "$RUNTIME/programs/pocketfoundry-drivercheck.c" -lGL -lX11
$CC -O2 -Wall -o "$BIN/pocketfoundry-speedtest" "$RUNTIME/programs/pocketfoundry-speedtest.c" -lGL -lX11 -lm
# The speed test again, through SDL as the game draws: the sdl piece's, built before this one.
SDL=$OUT/sdl$PREFIX
$CC -O2 -Wall -DSPEEDTEST_SDL -o "$BIN/pocketfoundry-speedtest-sdl" "$RUNTIME/programs/pocketfoundry-speedtest.c" \
    -I"$SDL/include" -L"$SDL/lib" -lSDL3 -lm
$STRIP "$BIN"/*
echo "built: $(file -b "$BIN/pocketfoundry-wm")"
