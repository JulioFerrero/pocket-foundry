#!/bin/sh
# Runs inside Debian. No window manager runs, so SDL's fullscreen request is
# ignored and the game opens at 1920x1080. Keep its window the size of the X
# screen (also after a resolution change) until the game exits.
GAME=/opt/factorio/bin/arm64/factorio

i=0
until pgrep -f "$GAME" > /dev/null; do
    sleep 1
    i=$((i + 1))
    [ $i -lt 60 ] || exit 0
done

while pgrep -f "$GAME" > /dev/null; do
    root=$(xdpyinfo | awk '/dimensions:/ {print $2}')
    for w in $(xdotool search --class factorio 2>/dev/null); do
        size=$(xdotool getwindowgeometry "$w" | awk '/Geometry:/ {print $2}')
        [ "$size" = "$root" ] || xdotool windowsize "$w" "${root%x*}" "${root#*x}"
    done
    # Each check goes through proot, which slows the game down; keep them rare.
    sleep 5
done
