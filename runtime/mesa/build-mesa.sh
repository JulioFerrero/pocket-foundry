#!/bin/bash
# A piece (build.sh): Mesa from lfdevs/mesa-for-android-container, with Zink for the phone's own Vulkan
# driver and the Vulkan drivers the app downloads. Those, freedreno and llvmpipe for the CPU are built in
# JulioFerrero/pocket-foundry-drivers: llvmpipe links LLVM, three quarters of the runtime packed.
set -euo pipefail
. "$RUNTIME/toolchain.sh"

COMMIT=eb3e7fd4f009cab04b1482aad4843c0bde28a786   # tag mesa-27.0.0-devel-20261025-r1
SRC=$WORK/mesa

cd "$CACHE"
# No checksum: GitHub's generated tarballs aren't byte-stable; the commit pins the source.
[ -f mesa-$COMMIT.tar.gz ] || curl -fL -o mesa-$COMMIT.tar.gz \
    https://codeload.github.com/lfdevs/mesa-for-android-container/tar.gz/$COMMIT
rm -rf "$SRC" && mkdir -p "$SRC" && tar -xzf mesa-$COMMIT.tar.gz -C "$SRC" --strip-components=1
cd "$SRC"
for p in "$RUNTIME"/mesa/patches/*.patch; do patch -p1 --silent < "$p"; done

meson setup build $MESON_CROSS \
    --prefix=$PREFIX --libdir=lib --buildtype=release -Db_ndebug=true \
    -Dplatforms=x11 \
    -Dgallium-drivers=zink \
    -Dvulkan-drivers= \
    -Dglx=dri -Degl=enabled -Dgbm=disabled -Dglvnd=enabled \
    -Dgles1=disabled -Dgles2=enabled \
    -Dllvm=disabled \
    -Dgallium-va=disabled -Dgallium-rusticl=false -Dteflon=false \
    -Dvulkan-layers=[] -Dtools=[] -Dbuild-tests=false -Dhtml-docs=disabled \
    -Dvalgrind=disabled -Dlibunwind=disabled -Dlmsensors=disabled \
    -Dxlib-lease=disabled -Dandroid-libbacktrace=disabled \
    -Dzstd=enabled -Dexpat=enabled

ninja -C build
rm -rf "$OUT/mesa" && DESTDIR="$OUT/mesa" ninja -C build install
find "$OUT/mesa" -name '*.so*' -type f -exec $STRIP --strip-unneeded {} +
du -sh "$OUT/mesa$PREFIX/lib"
