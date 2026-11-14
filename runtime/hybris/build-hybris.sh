#!/bin/bash
# A piece (build.sh): libhybris from taowen/libhybris (branch arlinux), for the "Phone's driver". It loads
# Android's own Vulkan driver into the game's glibc process, and its compat layer (MaliBridge's work among it) adds
# what Zink needs. Our patch shows the frames through Termux:X11.
set -euo pipefail
. "$RUNTIME/toolchain.sh"

# The sources, submodules: libhybris (taowen's branch arlinux) and android-headers (as taowen/arlinux-rootfs pins it),
# with Vulkan headers newer than Debian's: those of the Mesa we build.
SRC=$WORK/hybris
LIB=$PREFIX/lib/hybris

unpack "$RUNTIME/hybris/libhybris" "$SRC"
unpack "$RUNTIME/hybris/android-headers" "$SRC/headers"
MESA=$RUNTIME/mesa/src
mkdir -p "$SRC/headers/include" "$SRC/headers/src/vulkan/registry"
cp -R "$MESA/include/vulkan" "$MESA/include/vk_video" "$SRC/headers/include/"
cp "$MESA/src/vulkan/registry/vk.xml" "$SRC/headers/src/vulkan/registry/"
cd "$SRC"
for p in "$RUNTIME"/hybris/patches/*.patch; do patch -p1 --silent < "$p"; done
# The compat layer copies the feature structures Zink chains to vkCreateDevice and refuses any it has no size for:
# list those of the Vulkan our Mesa knows, which newer phone drivers support (maintenance9 on Android 17's Mali).
# Its registry files core structures under VK_BASE/COMPUTE/GRAPHICS_VERSION_1_x, which the headers call VK_VERSION_1_x:
# under a name the headers don't define, structures drop out of the list (VkPhysicalDeviceFeatures2 did once).
sizes=hybris/vulkan/layer/device_create_sizes.inc
python3 tools/generate-device-create-sizes.py "$SRC/headers/src/vulkan/registry/vk.xml" $sizes
sed -i -E 's/VK_(BASE|COMPUTE|GRAPHICS)_VERSION_/VK_VERSION_/g' $sizes
for guard in $(grep -oE 'defined\(VK_[A-Za-z0-9_]+\)' $sizes | sort -u | sed -E 's/defined\((.*)\)/\1/'); do
    case $guard in VK_ENABLE_BETA_EXTENSIONS|VK_USE_PLATFORM_*) continue ;; esac
    grep -rq "#define $guard " "$SRC/headers/include/vulkan" || { echo "$sizes: the headers don't define $guard"; exit 1; }
done

# As taowen/arlinux-rootfs builds it (tools/build/linux-gpu.sh). EGL and GLES are for MobileGlues, a catalog driver
# that turns the game's OpenGL into the phone's OpenGL ES; it draws offscreen, so only EGL's null platform goes.
cd hybris
export PKG_CONFIG_PATH=$SRC/arlinux/graphics-protocols CPPFLAGS="-I$SRC/headers/include"
NOCONFIGURE=1 ./autogen.sh
./configure --host=$T --prefix=$PREFIX --libdir=$LIB --with-android-headers=$SRC/headers \
    --enable-arch=arm64 --enable-wayland --enable-x11 --disable-wayland_serverside_buffers \
    --enable-adreno-quirks --enable-mali-quirks --enable-property-cache \
    --with-default-hybris-ld-library-path=/vendor/lib64/egl:/vendor/lib64/hw:/vendor/lib64:/system/lib64:/system_ext/lib64
make -C common -j"$(nproc)" SUBDIRS=. libhybris-common.la
dirs="common/q libsync platforms/common properties hardware ui gralloc vulkan egl glesv2"
for dir in $dirs; do make -C $dir -j"$(nproc)"; done

rm -rf "$OUT/hybris"
make -C common install-libLTLIBRARIES DESTDIR="$OUT/hybris"
for dir in $dirs; do make -C $dir install DESTDIR="$OUT/hybris"; done

# hybris's own libvulkan goes too: the runtime has Khronos's loader.
HYBRIS=$OUT/hybris$LIB
rm -rf "$OUT/hybris$PREFIX/include"
cd "$HYBRIS"
rm -rf pkgconfig libvulkan.so* libhybris/eglplatform_fbdev.so libhybris/eglplatform_hwcomposer.so
find . \( -name '*.la' -o -name '*.so' -type l \) -delete
find . -name '*.so*' -type f -exec $STRIP --strip-unneeded {} +
cp "$SRC/LICENSE.Apache2" licenses/libhybris-vulkan/
cat > hybris_icd.json <<EOF
{
    "file_format_version": "1.0.1",
    "ICD": { "library_path": "$LIB/libhybris-vulkan-icd.so.0", "api_version": "1.3.0" }
}
EOF

# Khronos's timeline semaphore layer (Vulkan-ExtensionLayer, Apache-2.0): the timeline semaphores Zink needs, on Android
# drivers from before Vulkan 1.2 without them (a Huawei P40's Mali-G76 r18p0). It takes them over even where the driver
# has its own, so the app turns it on only where the driver check finds Zink stopping for them (DriverCheck.kt).
# Its source: the submodule vulkan-extensionlayer.
LAYER_SRC=$WORK/vulkan-extensionlayer
unpack "$RUNTIME/hybris/vulkan-extensionlayer" "$LAYER_SRC"
cd "$LAYER_SRC"
for p in "$RUNTIME"/hybris/timeline/*.patch; do patch -p1 --silent < "$p"; done
cd layers
flags="-O2 -fPIC -DNDEBUG -I. -I../utils -I$SRC/headers/include"
$CC $flags -c timeline_semaphore.c
$CXX $flags -c hash_table.cpp
layer=libVkLayer_khronos_timeline_semaphore.so
$CXX -shared -o "$HYBRIS/$layer" timeline_semaphore.o hash_table.o \
    -Wl,--version-script=libVkLayer_khronos_timeline_semaphore.map -lpthread
$STRIP --strip-unneeded "$HYBRIS/$layer"
mkdir -p "$HYBRIS/licenses/vulkan-extensionlayer"
cp "$LAYER_SRC/LICENSES/Apache-2.0.txt" "$HYBRIS/licenses/vulkan-extensionlayer/"
cat > "$HYBRIS/VkLayer_khronos_timeline_semaphore.json" <<EOF
{
    "file_format_version": "1.0.0",
    "layer": {
        "name": "VK_LAYER_KHRONOS_timeline_semaphore",
        "type": "GLOBAL",
        "library_path": "$LIB/$layer",
        "api_version": "1.4.0",
        "implementation_version": "1",
        "description": "Khronos timeline semaphore layer",
        "device_extensions": [{ "name": "VK_KHR_timeline_semaphore", "spec_version": "2" }]
    }
}
EOF
du -sh "$HYBRIS"
