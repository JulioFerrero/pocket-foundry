#!/bin/sh
# Builds runtime pieces into runtime/out/ in an arm64 Debian container:
#   runtime/build.sh glibc mesa sdl wm assemble
set -e
cd "$(dirname "$0")"
[ $# -gt 0 ] || { echo "usage: $0 glibc|mesa|sdl|wm|assemble ..."; exit 1; }
mkdir -p out cache

docker build --platform linux/arm64 -t pocketfoundry-build -f Dockerfile .

# tools/release.sh only ships a runtime whose stamps match the commit.
stamp() {
    if [ -n "$(git status --porcelain -- "$1" Dockerfile)" ]; then echo uncommitted
    else echo "$(git rev-parse "HEAD:./$1") $(git rev-parse HEAD:./Dockerfile)"; fi
}

for target in "$@"; do
    case "$target" in
        glibc|mesa|sdl|wm|assemble)
            docker run --rm --platform linux/arm64 \
                -v "$PWD:/runtime:ro" -v "$PWD/out:/out" -v "$PWD/cache:/build" \
                pocketfoundry-build bash "/runtime/$target/build-$target.sh"
            stamp "$target" > "out/$target.stamp" ;;
        *)
            echo "unknown target: $target"; exit 1 ;;
    esac
done
