#!/bin/bash
# A piece (build.sh): Phone's OpenGL ES, MobileGlues, as pocket-foundry-drivers builds it (build/gles.sh), so the app
# has it without downloading it. The recipe, its patches and the EGL shim (gles/mgx11.c) are the submodule drivers's;
# the source, the submodule src, is MobileGlues at the recipe's commit. assemble packs it for the app.
set -euo pipefail
. "$RUNTIME/toolchain.sh"

DRIVERS=$RUNTIME/gles/drivers
RECIPE=$DRIVERS/drivers/mobileglues.json
SRC=$WORK/gles
spec() { jq -r "$1" "$RECIPE"; }

# The two submodules move together. Our container sees no git (only this folder is in it): F-Droid's build does.
ref=$(spec .source.ref)
[ "$(git -C "$RUNTIME/gles/src" rev-parse HEAD 2>/dev/null || echo "$ref")" = "$ref" ] ||
    { echo "src isn't MobileGlues $ref, the recipe's" >&2; exit 1; }
unpack "$RUNTIME/gles/src" "$SRC"
cd "$SRC"
for p in $(spec '.build.patches[]'); do patch -p1 --silent < "$DRIVERS/$p"; done

# Its C++ library inside it: the runtime's libstdc++ is older than this compiler's.
cmake -S "$(spec .build.cmake)" -B build -G Ninja $CMAKE_CROSS -DCMAKE_BUILD_TYPE=Release \
      -DCMAKE_SHARED_LINKER_FLAGS="-static-libstdc++ -static-libgcc"
ninja -C build

PKG=$OUT/gles/mobileglues
rm -rf "$OUT/gles" && mkdir -p "$PKG"
cp build/libmobileglues.so "$PKG/"
$CC -O2 -shared -fPIC -fvisibility=hidden -Wall -Werror -o "$PKG/libmgx11.so" "$DRIVERS/gles/mgx11.c" \
    -ldl -lX11 -lxcb -lxcb-dri3 -lxcb-present -lpthread
$STRIP --strip-unneeded "$PKG"/*.so
# LICENSE from the top, LICENSE-<its folder> from below, as the drivers' package has them.
for f in $(spec '.build.licences[]'); do
    folder=$(dirname "$f")
    name=$(basename "$f")
    [ "$folder" = . ] || name=${name%%.*}-$(basename "$folder")
    cp "$f" "$PKG/$name"
done
cp "$RECIPE" "$OUT/gles/mobileglues.json"
ls -la "$PKG"
