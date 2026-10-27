#!/bin/sh
# Build runtime pieces in an arm64 Debian container (Docker Desktop on the Mac).
#   runtime/build.sh glibc mesa sdl wm assemble
# Output goes to runtime/out/ (gitignored); `assemble` also fills runtime/out/apk
# with what the APK packs.
set -e
cd "$(dirname "$0")"
[ $# -gt 0 ] || { echo "usage: $0 glibc|mesa|sdl|wm|assemble ..."; exit 1; }
mkdir -p out cache

docker build --platform linux/arm64 -t pocketfoundry-build -f Dockerfile .

for target in "$@"; do
    case "$target" in
        glibc|mesa|sdl|wm|assemble)
            docker run --rm --platform linux/arm64 \
                -v "$PWD:/runtime:ro" -v "$PWD/out:/out" -v "$PWD/cache:/build" \
                pocketfoundry-build bash "/runtime/$target/build-$target.sh" ;;
        *)
            echo "unknown target: $target"; exit 1 ;;
    esac
done
