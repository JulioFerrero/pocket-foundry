#!/bin/sh
# Builds the runtime into runtime/out/ in an arm64 Debian container, piece by piece, then packs it for the app:
#   runtime/build.sh all          the pieces whose sources changed since they were built, then the pack
#   runtime/build.sh mesa sdl     those pieces, then the pack
#   runtime/build.sh --stale      what `all` would build
# With --here first, it builds on this machine instead, Debian 13 set up by deps.sh: F-Droid's build server, which
# cross-compiles on x86-64.
set -e
cd "$(dirname "$0")"
here=
[ "$1" = --here ] && { here=1; shift; }
PREFIX=/data/data/dev.pocketfoundry/files/usr   # where the app unpacks the runtime (core/system/Paths.kt)
# In build order: hybris compiles against Mesa's Vulkan headers.
PIECES="glibc mesa sdl programs hybris"

# This script counts as a source because the pieces are built for its PREFIX.
sources() {
    from="$1 Dockerfile deps.sh toolchain.sh aarch64.ini build.sh"
    [ "$1" = hybris ] && from="$from mesa"
    if [ -n "$(git status --porcelain -- $from)" ]; then echo "uncommitted $(date +%s)"
    else for f in $from; do git rev-parse "HEAD:./$f"; done | tr '\n' ' '; echo; fi
}

stale() {
    for piece in $PIECES; do
        [ "$(cat "out/$piece.stamp" 2>/dev/null)" = "$(sources $piece)" ] || echo $piece
    done
}

named() {
    for name in "$@"; do
        case " $PIECES " in *" $name "*) ;; *) echo "unknown piece: $name" >&2; exit 1 ;; esac
    done
    for piece in $PIECES; do
        for name in "$@"; do
            if [ "$name" = $piece ]; then echo $piece; fi
        done
    done
}

# The app keeps what it knows about the drivers until this version changes.
version() {
    { for piece in $PIECES; do cat "out/$piece.stamp"; done; sources assemble; } | shasum -a 256 | cut -c1-16
}

[ $# -gt 0 ] || { echo "usage: $0 all | --stale | $PIECES ..."; exit 1; }
case "$1" in
    --stale)
        pieces=$(stale)
        [ -z "$pieces" ] && [ "$(cat out/assemble.stamp 2>/dev/null)" = "$(version)" ] || pieces="$pieces assemble"
        echo $pieces
        exit ;;
    all) pieces=$(stale) ;;
    *) pieces=$(named "$@") ;;
esac

mkdir -p out
# The commit's time stands for the build's, so the same commit packs the same bytes (assemble, and compilers' dates).
export SOURCE_DATE_EPOCH=$(git log -1 --format=%ct)
# A piece: its script sees this folder as RUNTIME (sources are its submodules), writes to OUT and unpacks to WORK.
if [ -n "$here" ]; then
    run() {
        env PREFIX="$PREFIX" VERSION="${VERSION:-}" SOURCE_DATE_EPOCH="$SOURCE_DATE_EPOCH" \
            RUNTIME="$PWD" OUT="$PWD/out" WORK="$PWD/work" \
            bash "$1/build-$1.sh"
    }
else
    docker build --platform linux/arm64 -t pocketfoundry-build -f Dockerfile .
    # WORK stays in the container: a Mac bind mount is slow for builds.
    run() {
        docker run --rm --platform linux/arm64 -e PREFIX="$PREFIX" -e VERSION="${VERSION:-}" -e SOURCE_DATE_EPOCH \
            -e RUNTIME=/runtime -e OUT=/out -e WORK=/work \
            -v "$PWD:/runtime:ro" -v "$PWD/out:/out" \
            pocketfoundry-build bash "/runtime/$1/build-$1.sh"
    }
fi
for piece in $pieces; do
    run $piece
    sources $piece > "out/$piece.stamp"
done
version=$(version)
if [ "$(cat out/assemble.stamp 2>/dev/null)" != "$version" ]; then
    # The container writes out/ as root on Linux: the version goes in from there.
    VERSION=$version run assemble
    echo "$version" > out/assemble.stamp
fi
