#!/data/data/com.termux/files/usr/bin/sh
# Phase 2 experiment, run from Termux: start a program from the Debian rootfs
# straight through glibc's loader, without proot. This is what the APK will do.
#   noproot.sh factorio [args]     the game, with its save and config
#   noproot.sh run <program> [args]  any Debian program, e.g. run /usr/bin/xdpyinfo
ROOT=$PREFIX/var/lib/proot-distro/containers/debian/rootfs
LIB=$ROOT/usr/lib/aarch64-linux-gnu
# Debian's glibc dies on Android's syscall filter (set_robust_list -> SIGSYS);
# Termux's port is patched for it. It must be at least Debian's 2.41.
GLIBC=$PREFIX/glibc/lib
GAME=$ROOT/opt/factorio
W=$HOME/pocketfoundry-np
mkdir -p "$W/home"

# Paths inside these files are absolute to the container; point them at the rootfs.
sed "s#\"/usr/lib/#\"$ROOT/usr/lib/#" \
    "$ROOT/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json" > "$W/freedreno_icd.json"
# __PATH__executable__ would resolve to the loader, so give Factorio absolute paths.
[ -f "$W/config.ini" ] || sed \
    -e "s#^read-data=.*#read-data=$GAME/data#" \
    -e "s#^write-data=.*#write-data=$GAME#" \
    "$GAME/config/config.ini" > "$W/config.ini"

cmd=$1
shift
case "$cmd" in
    factorio) set -- "$GAME/bin/arm64/factorio" --config "$W/config.ini" "$@" ;;
    run)      prog=$1; shift; set -- "$ROOT$prog" "$@" ;;
esac

# env -i drops Termux's LD_PRELOAD, a bionic library glibc can't load.
exec env -i \
    HOME="$W/home" TMPDIR="$PREFIX/tmp" LANG=C.UTF-8 TZ=Europe/Madrid TZDIR="$ROOT/usr/share/zoneinfo" \
    DISPLAY="${DISPLAY:-$PREFIX/tmp/.X11-unix/X0}" PULSE_SERVER=127.0.0.1 \
    SDL_VIDEODRIVER=x11 SDL_VIDEO_DRIVER=x11 SDL_AUDIODRIVER=pulseaudio SDL_AUDIO_DRIVER=pulseaudio \
    GCONV_PATH="$GLIBC/gconv" LIBGL_DRIVERS_PATH="$LIB/dri" \
    MESA_LOADER_DRIVER_OVERRIDE=zink MESA_VK_WSI_DEBUG=sw VK_DRIVER_FILES="$W/freedreno_icd.json" \
    $NP_ENV \
    "$GLIBC/ld-linux-aarch64.so.1" --library-path "$GLIBC:$LIB:$LIB/pulseaudio" "$@"
