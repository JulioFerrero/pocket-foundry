#!/bin/bash
# A piece (build.sh). The game's statically linked SDL forwards every call to this one (SDL3_DYNAMIC_API).
set -euo pipefail
. "$RUNTIME/toolchain.sh"

# The source: the submodule src, at tag release-3.4.20. Its release tarball adds only the revision, given below.
VERSION=3.4.20
REVISION=SDL-release-3.4.20-0-gf00dc919c
SRC=$WORK/sdl

grep -q "VERSION \"$VERSION\"" "$RUNTIME/sdl/src/CMakeLists.txt" || { echo "src isn't SDL $VERSION" >&2; exit 1; }
unpack "$RUNTIME/sdl/src" "$SRC"
cd "$SRC"
for p in "$RUNTIME"/sdl/patches/*.patch; do patch -p1 --silent < "$p"; done
mkdir -p src/pocketfoundry && cp "$RUNTIME"/sdl/pocketfoundry/* src/pocketfoundry/

# io_uring off: Android's syscall filter kills processes that use it.
# SDL only offers the virtual joystick with HIDAPI on; HIDAPI's own drivers stay off.
# Built for size: the game draws with OpenGL itself, so SDL's GPU API and Vulkan renderer go, and what's left (window,
# input, sound) costs little time. 0.87 MB packed before, 0.49 after.
cmake -S . -B build -G Ninja $CMAKE_CROSS \
    -DCMAKE_BUILD_TYPE=MinSizeRel -DCMAKE_INSTALL_PREFIX=$PREFIX -DCMAKE_INSTALL_LIBDIR=lib \
    -DSDL_SHARED=ON -DSDL_STATIC=OFF -DSDL_TEST_LIBRARY=OFF -DSDL_TESTS=OFF -DSDL_EXAMPLES=OFF \
    -DSDL_INSTALL_DOCS=OFF -DSDL_RPATH=OFF -DSDL_REVISION=$REVISION \
    -DSDL_POCKETFOUNDRY=ON \
    -DSDL_X11=ON -DSDL_X11_XTEST=OFF -DSDL_WAYLAND=OFF -DSDL_KMSDRM=OFF -DSDL_OPENGL=ON -DSDL_OPENGLES=ON -DSDL_VULKAN=ON \
    -DSDL_ALSA=OFF -DSDL_JACK=OFF -DSDL_PIPEWIRE=OFF -DSDL_PULSEAUDIO=OFF -DSDL_SNDIO=OFF -DSDL_OSS=OFF \
    -DSDL_DISKAUDIO=OFF -DSDL_DUMMYAUDIO=ON \
    -DSDL_VIRTUAL_JOYSTICK=ON -DSDL_HIDAPI=ON -DSDL_HIDAPI_JOYSTICK=OFF -DSDL_HIDAPI_LIBUSB=OFF \
    -DSDL_LIBUDEV=OFF -DSDL_DBUS=OFF -DSDL_IBUS=OFF -DSDL_LIBURING=OFF \
    -DSDL_DUMMYCAMERA=OFF -DSDL_OPENVR=OFF -DSDL_FRIBIDI=OFF -DSDL_LIBTHAI=OFF \
    -DSDL_GPU=OFF -DSDL_RENDER_VULKAN=OFF
ninja -C build
SDL=$OUT/sdl$PREFIX
rm -rf "$OUT/sdl" && DESTDIR="$OUT/sdl" ninja -C build install
$STRIP --strip-unneeded "$SDL"/lib/libSDL3.so.*.*

mkdir -p "$SDL/bin"
for t in padtest inputtest; do
    $CC -O2 -o "$SDL/bin/pocketfoundry-$t" "$RUNTIME/sdl/$t.c" -I"$SDL/include" -L"$SDL/lib" -lSDL3
    $STRIP "$SDL/bin/pocketfoundry-$t"
done
ls -la "$SDL/lib/"
