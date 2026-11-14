#!/bin/sh
# Run once and after pulling: fetches Termux:X11 and applies our patches. Safe to rerun.
set -e
cd "$(dirname "$0")/.."
x11=third_party/termux-x11
git submodule update --init $x11
git -C $x11 submodule update --init --recursive --depth 1
# A copy of the patches last applied, in the submodule's git folder: the same set again needs nothing, and a changed
# one (after a pull) goes on a tree put back as it was. Patches overlap, so applying them one by one again can't work.
applied=$(git -C $x11 rev-parse --absolute-git-dir)/pocketfoundry-patches
if [ -d "$applied" ] && diff -r "$applied" third_party/patches/termux-x11 > /dev/null; then
    echo "Termux:X11's patches are applied"
    exit
fi
if [ -n "$(git -C $x11 status --porcelain --untracked-files=no --ignore-submodules)" ] || [ -d "$applied" ]; then
    # Back to the submodule's commit: its changed files, and the files our patches created.
    git -C $x11 checkout -q -- .
    for p in "$applied"/*.patch third_party/patches/termux-x11/*.patch; do
        [ -f "$p" ] || continue
        awk '/^--- \/dev\/null/ { new = 1; next } new && sub(/^\+\+\+ b\//, "") { print } { new = 0 }' "$p" |
            while read -r f; do rm -f "$x11/$f"; done
    done
fi
rm -rf "$applied"
for p in third_party/patches/termux-x11/*.patch; do
    git -C $x11 apply "../../$p"
    echo "applied: $(basename "$p")"
done
mkdir -p "$applied"
cp third_party/patches/termux-x11/*.patch "$applied/"
