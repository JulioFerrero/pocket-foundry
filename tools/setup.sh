#!/bin/sh
# Run once and after pulling: fetches Termux:X11 and applies our patches. Safe to rerun.
set -e
cd "$(dirname "$0")/.."
git submodule update --init third_party/termux-x11
git -C third_party/termux-x11 submodule update --init --recursive --depth 1
for p in third_party/patches/termux-x11/*.patch; do
    if git -C third_party/termux-x11 apply --reverse --check "../../$p" 2>/dev/null; then
        echo "already applied: $(basename "$p")"
    else
        git -C third_party/termux-x11 apply "../../$p"
        echo "applied: $(basename "$p")"
    fi
done
