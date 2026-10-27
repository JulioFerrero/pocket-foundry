#!/bin/sh
# Build the APK. Termux:X11's CMake applies its own patches with `patch`, and
# Apple's patch silently skips them, so GNU patch must come first in PATH.
set -e
cd "$(dirname "$0")/.."
PATH="$(brew --prefix gpatch)/libexec/gnubin:$PATH"
export PATH
[ -f runtime/out/apk/assets/runtime.tar.xz ] || { echo "Build the runtime first: runtime/build.sh glibc mesa sdl wm assemble"; exit 1; }
exec ./gradlew "${@:-:app:assembleDebug}"
