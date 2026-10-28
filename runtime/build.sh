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

# What a piece was built from: its directory's and the Dockerfile's committed trees, or
# "uncommitted". tools/release.sh only ships a runtime whose pieces match the commit.
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
