#!/data/data/com.termux/files/usr/bin/sh
# Phase 2 experiment, run from Termux: the game on the assembled runtime
# (runtime/out/runtime.tar.xz unpacked to ~/rt), as the APK will run it. Paths
# baked in for the app's prefix are overridden with environment variables.
#   runtime-test.sh [factorio args]
RT=$HOME/rt/usr
GAME=$PREFIX/var/lib/proot-distro/containers/debian/rootfs/opt/factorio
W=$HOME/pocketfoundry-np
SOCK=$PREFIX/tmp/.X11-unix/X0
mkdir -p "$W/home"

sed "s#/data/data/app.pocketfoundry/files/usr#$RT#" \
    "$RT/share/vulkan/icd.d/freedreno_icd.aarch64.json" > "$W/rt_icd.json"

run() {
    env -i HOME="$W/home" TMPDIR="$PREFIX/tmp" LANG=C.UTF-8 DISPLAY="$SOCK" \
        GCONV_PATH="$RT/lib/gconv" LIBGL_DRIVERS_PATH="$RT/lib/dri" \
        MESA_LOADER_DRIVER_OVERRIDE=zink MESA_VK_WSI_DEBUG=sw VK_DRIVER_FILES="$W/rt_icd.json" \
        "$RT/lib/ld-linux-aarch64.so.1" --library-path "$RT/lib" "$@"
}

"$(dirname "$0")/start-display.sh"
pkill -f "[f]abrica-wm"
run "$RT/bin/pocketfoundry-wm" &
exec 3>&-
run "$GAME/bin/arm64/factorio" --config "$W/config.ini" "$@"
