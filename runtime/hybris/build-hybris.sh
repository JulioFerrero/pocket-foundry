#!/bin/bash
# Runs inside the build container: libhybris from taowen/libhybris (branch arlinux), for the "Phone's driver". It loads
# Android's own Vulkan driver into the game's glibc process, and its compat layer (MaliBridge's work among it) adds
# what Zink needs. Our patch shows the frames through Termux:X11.
set -euo pipefail

COMMIT=226b247d90adcb5eb1b120885fac8b96063726ee    # branch arlinux, 2026-11-02
HEADERS=2c6ac3dcc4f8db593dd69906b0ec22822abfed91   # Halium/android-headers, as taowen/arlinux-rootfs pins it
# Vulkan headers newer than Debian's: those of the Mesa we build (its build fetched the tarball).
MESA=$(sed -n 's/^COMMIT=\([0-9a-f]*\).*/\1/p' /runtime/mesa/build-mesa.sh)
SRC=/work/hybris
LIB=$PREFIX/lib/hybris

cd /cache
# No checksums: GitHub's generated tarballs aren't byte-stable; the commits pin the sources.
[ -f libhybris-$COMMIT.tar.gz ] || curl -fL -o libhybris-$COMMIT.tar.gz \
    https://codeload.github.com/taowen/libhybris/tar.gz/$COMMIT
[ -f android-headers-$HEADERS.tar.gz ] || curl -fL -o android-headers-$HEADERS.tar.gz \
    https://codeload.github.com/Halium/android-headers/tar.gz/$HEADERS
rm -rf "$SRC" && mkdir -p "$SRC/headers"
tar -xzf libhybris-$COMMIT.tar.gz -C "$SRC" --strip-components=1
tar -xzf android-headers-$HEADERS.tar.gz -C "$SRC/headers" --strip-components=1
tar -xzf mesa-$MESA.tar.gz -C "$SRC/headers" --strip-components=1 --wildcards '*/include/vulkan' '*/include/vk_video' \
    '*/src/vulkan/registry/vk.xml'
cd "$SRC"
for p in /runtime/hybris/patches/*.patch; do patch -p1 --silent < "$p"; done
# The compat layer copies the feature structures Zink chains to vkCreateDevice and refuses any it has no size for:
# list those of the Vulkan our Mesa knows, which newer phone drivers support (maintenance9 on Android 17's Mali).
python3 tools/generate-device-create-sizes.py "$SRC/headers/src/vulkan/registry/vk.xml" \
    hybris/vulkan/layer/device_create_sizes.inc

# As taowen/arlinux-rootfs builds it (tools/build/linux-gpu.sh), less EGL and GLES: the game's OpenGL is Zink.
cd hybris
export PKG_CONFIG_PATH=$SRC/arlinux/graphics-protocols CPPFLAGS="-I$SRC/headers/include"
NOCONFIGURE=1 ./autogen.sh
./configure --prefix=$PREFIX --libdir=$LIB --with-android-headers=$SRC/headers \
    --enable-arch=arm64 --enable-wayland --enable-x11 --disable-wayland_serverside_buffers \
    --enable-adreno-quirks --enable-mali-quirks --enable-property-cache \
    --with-default-hybris-ld-library-path=/vendor/lib64/egl:/vendor/lib64/hw:/vendor/lib64:/system/lib64:/system_ext/lib64
make -C common -j"$(nproc)" SUBDIRS=. libhybris-common.la
dirs="common/q libsync platforms/common properties hardware ui gralloc vulkan"
for dir in $dirs; do make -C $dir -j"$(nproc)"; done

rm -rf /out/hybris
make -C common install-libLTLIBRARIES DESTDIR=/out/hybris
for dir in $dirs; do make -C $dir install DESTDIR=/out/hybris; done

# Only what the game loads: no development files, nor hybris's own libvulkan (the runtime has Khronos's loader).
rm -rf /out/hybris$PREFIX/include
cd /out/hybris$LIB
rm -rf pkgconfig libvulkan.so*
find . \( -name '*.la' -o -name '*.so' -type l \) -delete
find . -name '*.so*' -type f -exec strip --strip-unneeded {} +
cp "$SRC/LICENSE.Apache2" licenses/libhybris-vulkan/
cat > hybris_icd.json <<EOF
{
    "file_format_version": "1.0.1",
    "ICD": { "library_path": "$LIB/libhybris-vulkan-icd.so.0", "api_version": "1.3.0" }
}
EOF
du -sh /out/hybris$LIB
