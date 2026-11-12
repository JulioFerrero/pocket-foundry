#!/bin/sh
# Installs what the runtime's pieces build with, as root on Debian 13 (trixie): in our arm64 container (Dockerfile), or
# on an x86-64 machine that cross-compiles, as F-Droid's build server does. Tools run on this machine; libraries are
# arm64's, which the pieces link and the runtime ships.
#   deps.sh --list [arch]   prints the packages instead, for a machine of arch (this one's by default). F-Droid's
#                           recipe installs those for amd64 in its sudo lines, before our source is there to run this.
set -e
arch=${2:-$(dpkg --print-architecture)}
if [ "$arch" = arm64 ]; then
    compilers="gcc g++"
    # Nothing to emulate: the runtime's own programs run here as they are.
    emulator=
else
    compilers="gcc g++ gcc-aarch64-linux-gnu g++-aarch64-linux-gnu"
    # assemble runs our glibc's localedef, an arm64 program.
    emulator=qemu-user
fi

tools="$compilers $emulator make gawk bison flex python3 texinfo gettext jq curl ca-certificates xz-utils patch file
       pkgconf meson ninja-build cmake autoconf automake libtool
       python3-mako python3-yaml python3-packaging glslang-tools"
# Mesa, SDL and libhybris build against these; linux-libc-dev and libc6-dev are glibc's own headers to build it.
dev="linux-libc-dev libc6-dev libdrm-dev libexpat1-dev zlib1g-dev libzstd-dev
     libx11-dev libx11-xcb-dev libxext-dev libxfixes-dev libxrandr-dev libxxf86vm-dev
     libxshmfence-dev libxcb1-dev libxcb-glx0-dev libxcb-dri2-0-dev libxcb-dri3-dev
     libxcb-present-dev libxcb-sync-dev libxcb-shm0-dev libxcb-randr0-dev
     libxcb-xfixes0-dev libglvnd-dev libxcursor-dev libxi-dev libxss-dev
     libwayland-dev libwayland-egl-backend-dev"
# What assemble copies into the runtime.
ship="libgl1 libglx0 libegl1 libglvnd0 libvulkan1
      libx11-6 libx11-xcb1 libxext6 libxrandr2 libxi6 libxcursor1 libxfixes3 libxss1
      libxrender1 libxkbcommon0 libxkbcommon-x11-0 libxxf86vm1"
# The X keyboard data and Xlib's locale files, the same for every architecture.
data="xkb-data libx11-data"
# shellcheck disable=SC2086
packages=$(echo $tools $data $(for p in $dev $ship; do printf '%s:arm64 ' "$p"; done))

if [ "${1:-}" = --list ]; then
    echo "$packages"
    exit
fi
export DEBIAN_FRONTEND=noninteractive
[ "$arch" = arm64 ] || dpkg --add-architecture arm64
apt-get update -qq
# shellcheck disable=SC2086
apt-get install -y -qq --no-install-recommends $packages > /dev/null
rm -rf /var/lib/apt/lists/*
