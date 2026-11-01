#!/bin/sh
# Builds runtime pieces into runtime/out/ in an arm64 Debian container:
#   runtime/build.sh glibc mesa sdl wm assemble
#   runtime/build.sh --stale    prints the pieces not built from this commit
set -e
cd "$(dirname "$0")"
[ $# -gt 0 ] || { echo "usage: $0 --stale | glibc|mesa|sdl|wm|assemble ..."; exit 1; }
PREFIX=/data/data/app.pocketfoundry/files/usr   # where the app unpacks the runtime

# tools/release.sh only ships a runtime whose stamps match the commit.
stamp() {
    if [ -n "$(git status --porcelain -- "$1" Dockerfile)" ]; then echo uncommitted
    else echo "$(git rev-parse "HEAD:./$1") $(git rev-parse HEAD:./Dockerfile)"; fi
}

# Stale: a stamp that doesn't match, or a piece rebuilt after assemble packed it. Either means assemble too.
if [ "$1" = --stale ]; then
    stale=
    for piece in glibc mesa sdl wm assemble; do
        if [ "$(cat "out/$piece.stamp" 2>/dev/null || true)" != "$(stamp "$piece")" ] ||
           { [ $piece != assemble ] && [ "out/$piece.stamp" -nt out/assemble.stamp ]; }; then
            stale="$stale $piece"
        fi
    done
    case "$stale" in *assemble*) ;; ?*) stale="$stale assemble" ;; esac
    echo $stale
    exit
fi

mkdir -p out cache
docker build --platform linux/arm64 -t pocketfoundry-build -f Dockerfile .

for target in "$@"; do
    case "$target" in
        glibc|mesa|sdl|wm|assemble)
            docker run --rm --platform linux/arm64 -e PREFIX="$PREFIX" \
                -v "$PWD:/runtime:ro" -v "$PWD/out:/out" -v "$PWD/cache:/cache" \
                pocketfoundry-build bash "/runtime/$target/build-$target.sh"
            stamp "$target" > "out/$target.stamp" ;;
        *)
            echo "unknown target: $target"; exit 1 ;;
    esac
done
