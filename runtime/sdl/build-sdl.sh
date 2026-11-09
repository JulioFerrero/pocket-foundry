#!/bin/bash
# Runs inside the build container. The game's statically linked SDL forwards every call to this one (SDL3_DYNAMIC_API).
set -euo pipefail

VERSION=3.4.20
SHA256=f3bb06f55bed9557227a700af2ea62e49497ebdf58037732a731a5fb3ef8581f
SRC=/work/sdl

cd /cache
[ -f SDL3-$VERSION.tar.gz ] || curl -fL -o SDL3-$VERSION.tar.gz \
    https://github.com/libsdl-org/SDL/releases/download/release-$VERSION/SDL3-$VERSION.tar.gz
echo "$SHA256  SDL3-$VERSION.tar.gz" | sha256sum -c -
rm -rf "$SRC" && mkdir -p "$SRC" && tar -xzf SDL3-$VERSION.tar.gz -C "$SRC" --strip-components=1
cd "$SRC"
for p in /runtime/sdl/patches/*.patch; do patch -p1 --silent < "$p"; done
mkdir -p src/pocketfoundry && cp /runtime/sdl/pocketfoundry/* src/pocketfoundry/

# io_uring off: Android's syscall filter kills processes that use it.
# SDL only offers the virtual joystick with HIDAPI on; HIDAPI's own drivers stay off.
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

mkdir -p /out/sdl$PREFIX/bin
for t in padtest inputtest; do
    gcc -O2 -o /out/sdl$PREFIX/bin/pocketfoundry-$t /runtime/sdl/$t.c \
        -I/out/sdl$PREFIX/include -L/out/sdl$PREFIX/lib -lSDL3
    strip /out/sdl$PREFIX/bin/pocketfoundry-$t
done
ls -la /out/sdl$PREFIX/lib/
