#!/bin/sh
# Prints F-Droid's build recipe for a commit: the metadata/dev.pocketfoundry.yml we keep in fdroiddata, and what
# tools/fdroid-build.sh builds our releases with. Both build the same way so the APKs match: F-Droid ships ours, signed
# with Pocket Foundry's key, only when its own build of the commit comes out the same.
#   tools/fdroid-recipe.sh [commit [repository]]     HEAD and GitHub by default
# Needs Termux:X11 checked out (tools/setup.sh): the recipe names its NDK.
set -e
cd "$(dirname "$0")/.."
commit=$(git rev-parse "${1:-HEAD}")
repo=${2:-https://github.com/JulioFerrero/pocket-foundry.git}
prop() { git show "$commit:gradle.properties" | sed -n "s/^$1=//p"; }
version=$(prop versionName)
code=$(prop versionCode)
ndk=$(sed -n 's/.*termuxX11NdkVersion = "\(.*\)"/\1/p' third_party/termux-x11/lorie/version.gradle)
[ -n "$version" ] && [ -n "$code" ] && [ -n "$ndk" ] || { echo "No version or NDK found" >&2; exit 1; }
# The runtime's tools and arm64 libraries (runtime/deps.sh), from its Debian snapshot: the recipe's sudo lines run
# before our source is there, so they say what deps.sh does.
deps() { git show "$commit:runtime/deps.sh" | sh -s -- "$@"; }
packages=$(deps --list amd64 | fold -s -w 100 | sed -e 's/ *$//' -e '2,$s/^/        /')
snapshot=$(deps --snapshot)

# What F-Droid's scanner flags in the submodules: scanignore, text it takes for binaries by their names (glibc's Hurd
# linker scripts, a test ld.so.conf, libhybris's symbol lists) and glibc's info directory, which make install copies;
# scandelete, test data no piece builds from (charsets, time zones, freedreno traces) and a file of SDL's Xcode folder.
cat <<EOF
AntiFeatures:
  NonFreeDep:
    en-US: Needs your own copy of Factorio, a paid game.
Categories:
  - Emulator
License: GPL-3.0-only
AuthorName: JulioFerrero
WebSite: https://pocketfoundry.dev
SourceCode: https://github.com/JulioFerrero/pocket-foundry
IssueTracker: https://github.com/JulioFerrero/pocket-foundry/issues
Changelog: https://github.com/JulioFerrero/pocket-foundry/releases

AutoName: Pocket Foundry

RepoType: git
Repo: $repo
Binaries: https://github.com/JulioFerrero/pocket-foundry/releases/download/v%v/pocket-foundry-%v.apk

Builds:
  - versionName: $version
    versionCode: $code
    commit: $commit
    subdir: app
    submodules: true
    sudo:
      - dpkg --add-architecture arm64
      - echo 'APT::Snapshot "$snapshot";' > /etc/apt/apt.conf.d/50snapshot
      - 'printf "Package: *\nPin: origin snapshot.debian.org\nPin-Priority: 1001\n" > /etc/apt/preferences.d/snapshot'
      - apt-get update
      - apt-get install -y --no-install-recommends --allow-downgrades $packages
    gradle:
      - yes
    prebuild:
      - ../tools/setup.sh
      - sed -i -e 's/-Xmx4g/-Xmx2g/' ../gradle.properties
    scanignore:
      - runtime/glibc/src/elf/tst-ldconfig-cache.root/etc/ld.so.conf
      - runtime/glibc/src/htl/libpthread.a
      - runtime/glibc/src/htl/libpthread_pic.a
      - runtime/glibc/src/htl/libpthread_syms.a
      - runtime/glibc/src/manual/dir
      - runtime/hybris/libhybris/utils/generate_nfc/symbols
    scandelete:
      - runtime/glibc/src/iconvdata/testdata
      - runtime/glibc/src/timezone/testdata
      - runtime/mesa/src/src/freedreno/tests/traces
      - runtime/sdl/src/Xcode/SDL/pkg-support/resources/SDL_DS_Store
    build: ../runtime/build.sh --here all
    ndk: $ndk

AllowedAPKSigningKeys: 0e301617d64bc294bcc2d1dbee71a8c83f8fcc7b7c3616edc26e9b2e89c7e35f

AutoUpdateMode: Version
UpdateCheckMode: Tags ^v[0-9.]+$
CurrentVersion: $version
CurrentVersionCode: $code
EOF
