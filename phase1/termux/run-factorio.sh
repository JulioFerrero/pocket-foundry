#!/data/data/com.termux/files/usr/bin/sh
# Run Factorio inside the Debian container on the Termux:X11 display.
#   GL=zink  Zink OpenGL on top of Turnip Vulkan (default)
#   GL=kgsl  freedreno OpenGL straight on the Adreno: no flicker, but ~22 FPS
#            and a 3.5 minute load on the Adreno 830
# Extra arguments go to Factorio, e.g. --load-game /opt/factorio/saves/x.zip
# FACTORIO_ENV="VAR=value ..." adds environment variables for experiments.
GL=${GL:-zink}

"$(dirname "$0")/start-display.sh"

case "$GL" in
    # Turnip's X11 present path shows frames before the GPU has finished them
    # (black flashes). The software WSI path copies each finished frame instead.
    zink) GL_ENV="MESA_LOADER_DRIVER_OVERRIDE=zink MESA_VK_WSI_DEBUG=sw VK_DRIVER_FILES=/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json" ;;
    *)    GL_ENV="MESA_LOADER_DRIVER_OVERRIDE=$GL" ;;
esac

# One game at a time: Factorio holds a lock until its process is gone.
pkill -TERM -f "[o]pt/factorio/bin/arm64/factorio"
pkill -f "[f]it-window.sh"
i=0
while pgrep -f "[o]pt/factorio/bin/arm64/factorio" > /dev/null && [ $i -lt 30 ]; do
    sleep 0.5
    i=$((i + 1))
done
pkill -KILL -f "[o]pt/factorio/bin/arm64/factorio"

proot-distro login debian --shared-tmp -- env DISPLAY=:0 \
    sh "$(dirname "$(realpath "$0")")/fit-window.sh" > /dev/null 2>&1 &

# SDL 3 reads both the old and new names of the driver variables.
exec proot-distro login debian --shared-tmp -- env \
    DISPLAY=:0 PULSE_SERVER=127.0.0.1 \
    SDL_VIDEODRIVER=x11 SDL_VIDEO_DRIVER=x11 \
    SDL_AUDIODRIVER=pulseaudio SDL_AUDIO_DRIVER=pulseaudio \
    $GL_ENV $FACTORIO_ENV \
    /opt/factorio/bin/arm64/factorio "$@"
