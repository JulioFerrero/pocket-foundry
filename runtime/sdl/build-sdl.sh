#!/bin/bash
# Runs inside the build container: SDL 3.4 with Pocket Foundry's audio driver and virtual
# gamepad (runtime/sdl/pocketfoundry, hooked in by runtime/sdl/patches). The game picks it
# up through SDL3_DYNAMIC_API; its own statically linked SDL forwards every call.
set -euo pipefail

VERSION=3.4.18
SHA256=9c75cf16330322c217dedd2e0609f1124f1b54b8633e763467b4684d0f4334a3
PREFIX=/data/data/app.pocketfoundry/files/usr
SRC=/work/sdl

mkdir -p /build && cd /build
[ -f SDL3-$VERSION.tar.gz ] || curl -fL -o SDL3-$VERSION.tar.gz \
    https://github.com/libsdl-org/SDL/releases/download/release-$VERSION/SDL3-$VERSION.tar.gz
echo "$SHA256  SDL3-$VERSION.tar.gz" | sha256sum -c -
rm -rf "$SRC" && mkdir -p "$SRC" && tar -xzf SDL3-$VERSION.tar.gz -C "$SRC" --strip-components=1
cd "$SRC"
for p in /runtime/sdl/patches/*.patch; do patch -p1 < "$p"; done
mkdir -p src/pocketfoundry && cp /runtime/sdl/pocketfoundry/* src/pocketfoundry/

# Only what the game needs on our X server. io_uring is off because Android's
# syscall filter kills processes that use it; the rest would find nothing to talk to.
# The virtual joystick is only offered with HIDAPI on; its own drivers stay off.
cmake -S . -B build -G Ninja \
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX=$PREFIX -DCMAKE_INSTALL_LIBDIR=lib \
    -DSDL_SHARED=ON -DSDL_STATIC=OFF -DSDL_TEST_LIBRARY=OFF -DSDL_TESTS=OFF -DSDL_EXAMPLES=OFF \
    -DSDL_INSTALL_DOCS=OFF -DSDL_RPATH=OFF \
    -DSDL_POCKETFOUNDRY=ON \
    -DSDL_X11=ON -DSDL_X11_XTEST=OFF -DSDL_WAYLAND=OFF -DSDL_KMSDRM=OFF -DSDL_OPENGL=ON -DSDL_OPENGLES=ON -DSDL_VULKAN=ON \
    -DSDL_ALSA=OFF -DSDL_JACK=OFF -DSDL_PIPEWIRE=OFF -DSDL_PULSEAUDIO=OFF -DSDL_SNDIO=OFF -DSDL_OSS=OFF \
    -DSDL_DISKAUDIO=OFF -DSDL_DUMMYAUDIO=ON \
    -DSDL_VIRTUAL_JOYSTICK=ON -DSDL_HIDAPI=ON -DSDL_HIDAPI_JOYSTICK=OFF -DSDL_HIDAPI_LIBUSB=OFF \
    -DSDL_LIBUDEV=OFF -DSDL_DBUS=OFF -DSDL_IBUS=OFF -DSDL_LIBURING=OFF \
    -DSDL_DUMMYCAMERA=OFF -DSDL_OPENVR=OFF -DSDL_FRIBIDI=OFF -DSDL_LIBTHAI=OFF
ninja -C build
rm -rf /out/sdl && DESTDIR=/out/sdl ninja -C build install
strip --strip-unneeded /out/sdl$PREFIX/lib/libSDL3.so.*.*

# A small tool that prints the gamepad events SDL sees (no game needed).
mkdir -p /out/sdl$PREFIX/bin
gcc -O2 -o /out/sdl$PREFIX/bin/pocketfoundry-padtest /runtime/sdl/padtest.c \
    -I/out/sdl$PREFIX/include -L/out/sdl$PREFIX/lib -lSDL3
strip /out/sdl$PREFIX/bin/pocketfoundry-padtest
grep -E "SDL_POCKETFOUNDRY|SDL_AUDIO_DRIVER_|SDL_VIDEO_DRIVER_X11 |SDL_JOYSTICK_VIRTUAL" build/include-config-release/build_config/SDL_build_config.h || true
ls -la /out/sdl$PREFIX/lib/
