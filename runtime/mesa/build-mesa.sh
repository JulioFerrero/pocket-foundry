#!/bin/bash
# Runs inside the build container: Mesa from lfdevs/mesa-for-android-container, for Adreno 8xx on KGSL,
# plus llvmpipe for phones without a GPU driver.
set -euo pipefail

COMMIT=6f90e92dfdacc4759d8e9ccb328805d0f0b4439b   # tag mesa-27.0.0-devel-20261025
PREFIX=/data/data/app.pocketfoundry/files/usr
SRC=/work/mesa

mkdir -p /build && cd /build
[ -f mesa-$COMMIT.tar.gz ] || curl -fL -o mesa-$COMMIT.tar.gz \
    https://codeload.github.com/lfdevs/mesa-for-android-container/tar.gz/$COMMIT
rm -rf "$SRC" && mkdir -p "$SRC" && tar -xzf mesa-$COMMIT.tar.gz -C "$SRC" --strip-components=1
cd "$SRC"
for p in /runtime/mesa/patches/*.patch; do patch -p1 --forward < "$p"; done

meson setup build \
    --prefix=$PREFIX --libdir=lib --buildtype=release -Db_ndebug=true \
    -Dplatforms=x11 \
    -Dgallium-drivers=zink,freedreno,llvmpipe \
    -Dvulkan-drivers=freedreno \
    -Dfreedreno-kmds=kgsl,msm \
    -Dglx=dri -Degl=enabled -Dgbm=disabled -Dglvnd=enabled \
    -Dgles1=disabled -Dgles2=enabled \
    -Dllvm=enabled -Dshared-llvm=disabled \
    -Dgallium-va=disabled -Dgallium-rusticl=false -Dteflon=false \
    -Dvulkan-layers=[] -Dtools=[] -Dbuild-tests=false -Dhtml-docs=disabled \
    -Dvalgrind=disabled -Dlibunwind=disabled -Dlmsensors=disabled \
    -Dxlib-lease=disabled -Dandroid-libbacktrace=disabled \
    -Dzstd=enabled -Dexpat=enabled

ninja -C build
rm -rf /out/mesa && DESTDIR=/out/mesa ninja -C build install
find /out/mesa -name '*.so*' -type f -exec strip --strip-unneeded {} +
du -sh /out/mesa$PREFIX/lib
