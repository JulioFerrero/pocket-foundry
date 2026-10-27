#!/data/data/com.termux/files/usr/bin/sh
# One-time Phase 1 setup, run inside Termux:
#   ~/pocketfoundry/setup.sh ~/factorio_linux-arm64_2.1.23.tar.xz
# The archive is your own copy from factorio.com (Linux arm64). See README.md for
# the steps on the computer side (APKs, adb settings).
set -e

GAME_TAR=$1
[ -f "$GAME_TAR" ] || { echo "usage: $0 <factorio_linux-arm64_*.tar.xz>"; exit 1; }

MESA_TAG=mesa-27.0.0-devel-20261025
MESA_FILE=mesa-for-android-container_27.0.0-devel-20261025_debian_trixie_arm64.tar.gz
MESA_SHA256=b711d1d9ab5537ee9085da8896dfe2115e791b33aa317fe37538f276c967d687

export DEBIAN_FRONTEND=noninteractive
APT="-y -o Dpkg::Options::=--force-confnew"

echo "== Termux packages"
apt-get update
apt-get $APT upgrade
apt-get $APT install x11-repo
apt-get update
apt-get $APT install termux-x11-nightly pulseaudio proot-distro curl

echo "== Debian 13 (the game needs glibc >= 2.38)"
[ -d "$PREFIX/var/lib/proot-distro/containers/debian" ] || proot-distro install debian:trixie
ROOTFS=$PREFIX/var/lib/proot-distro/containers/debian/rootfs

echo "== Debian packages: X11, GL, Vulkan and audio client libraries SDL loads"
proot-distro login debian -- sh -c "export DEBIAN_FRONTEND=noninteractive
apt-get update -q
apt-get install -y -q --no-install-recommends \
    libx11-6 libxext6 libxcursor1 libxi6 libxrandr2 libxss1 libxfixes3 \
    libxkbcommon0 libxkbcommon-x11-0 libxtst6 \
    libgl1 libegl1 libglx-mesa0 libegl-mesa0 libgl1-mesa-dri libgbm1 \
    libvulkan1 mesa-vulkan-drivers libpulse0 libudev1 libdbus-1-3 \
    xdotool x11-utils procps xz-utils ca-certificates \
    mesa-utils vulkan-tools pulseaudio-utils"

echo "== Mesa with Adreno 8xx support, over Debian's"
curl -fL -o "$HOME/$MESA_FILE" \
    "https://github.com/lfdevs/mesa-for-android-container/releases/download/$MESA_TAG/$MESA_FILE"
echo "$MESA_SHA256  $HOME/$MESA_FILE" | sha256sum -c
proot-distro login debian -- sh -c "tar -xzf '$HOME/$MESA_FILE' -C / && ldconfig"
rm "$HOME/$MESA_FILE"

echo "== Factorio into /opt/factorio"
mkdir -p "$ROOTFS/opt"
tar -xJf "$GAME_TAR" -C "$ROOTFS/opt"

echo "== Termux:X11: fullscreen landscape at half the panel resolution, taps click"
timeout 20 termux-x11-preference forceOrientation:landscape fullscreen:true hideCutout:true \
    additionalKbdVisible:false "touchMode:Direct touch" \
    displayResolutionMode:scaled displayScale:200 \
    || echo "Couldn't reach Termux:X11: open it once, then rerun this script."

echo "Done. Start the game with ~/pocketfoundry/run-factorio.sh"
