#!/bin/bash
# A piece (build.sh): Mesa from lfdevs/mesa-for-android-container, with Zink for the phone's own Vulkan
# driver and the Vulkan drivers the app downloads. Those, freedreno and llvmpipe for the CPU are built in
# JulioFerrero/pocket-foundry-drivers: llvmpipe links LLVM, three quarters of the runtime packed.
set -euo pipefail
. "$RUNTIME/toolchain.sh"

# The source: the submodule src, at tag mesa-27.0.0-devel-20261025-r1.
SRC=$WORK/mesa

unpack "$RUNTIME/mesa/src" "$SRC"
cd "$SRC"
for p in "$RUNTIME"/mesa/patches/*.patch; do patch -p1 --silent < "$p"; done

# Without SPIRV-Tools, which Mesa only uses to print a shader it can't parse: 0.7 MB, and Debian's arm64 copy is found
# only on arm64 machines.
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
    -Dzstd=enabled -Dexpat=enabled \
    -Dspirv-tools=disabled

ninja -C build
rm -rf "$OUT/mesa" && DESTDIR="$OUT/mesa" ninja -C build install
find "$OUT/mesa" -name '*.so*' -type f -exec $STRIP --strip-unneeded {} +
du -sh "$OUT/mesa$PREFIX/lib"
