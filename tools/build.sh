#!/bin/sh
# Builds the APK. GNU patch goes first in PATH: Termux:X11's CMake applies
# patches with `patch`, and Apple's silently skips them.
set -e
cd "$(dirname "$0")/.."
PATH="$(brew --prefix gpatch)/libexec/gnubin:$PATH"
export PATH
[ -f runtime/out/apk/assets/runtime.tar.xz ] || { echo "Build the runtime first: runtime/build.sh all"; exit 1; }
[ -z "$(runtime/build.sh --stale)" ] ||
    echo "The runtime isn't built from this checkout; the APK gets the old one. To rebuild: runtime/build.sh all" >&2
exec ./gradlew "${@:-:app:assembleDebug}"
