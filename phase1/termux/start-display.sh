#!/data/data/com.termux/files/usr/bin/sh
# Start the X server, PulseAudio and the Termux:X11 window. Safe to rerun.
set -e

termux-wake-lock

if [ ! -S "$PREFIX/tmp/.X11-unix/X0" ]; then
    setsid termux-x11 :0 -ac > "$PREFIX/tmp/termux-x11.log" 2>&1 < /dev/null &
    i=0
    while [ ! -S "$PREFIX/tmp/.X11-unix/X0" ] && [ $i -lt 50 ]; do
        sleep 0.2
        i=$((i + 1))
    done
fi

# The Debian side connects over TCP: PULSE_SERVER=127.0.0.1
pulseaudio --check 2>/dev/null || pulseaudio --start --exit-idle-time=-1 \
    --load="module-native-protocol-tcp auth-ip-acl=127.0.0.1 auth-anonymous=1"

am start -n com.termux.x11/.MainActivity > /dev/null
