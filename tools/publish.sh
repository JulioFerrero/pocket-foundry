#!/bin/sh
# Run by .github/workflows/release.yml after the release build:
#   tools/publish.sh check 0.8.17     the APK is signed with Pocket Foundry's key and holds no game files
#   tools/publish.sh publish 0.8.17   the GitHub release, a pre-release for a beta (0.8.17-beta.1)
set -e
cd "$(dirname "$0")/.."

mode=$1
version=$2
tag=v$version
name=pocket-foundry-$version.apk
out=build/release
apk=app/build/outputs/apk/release/app-release.apk
fail() { echo "$*" >&2; exit 1; }
prop() { sed -n "s/^$1=//p" "$POCKETFOUNDRY_SIGNING"; }

# The key's certificate, as Android shows it: the notes print it so a download can be checked against it.
certificate() {
    PF_STOREPASS=$(prop storePassword) keytool -exportcert -keystore "$(prop storeFile)" -alias "$(prop keyAlias)" \
        -storepass:env PF_STOREPASS | sha256sum | cut -d' ' -f1
}

case $mode in
check)
    [ "$(sed -n 's/^versionName=//p' gradle.properties)" = "$version" ] || fail "gradle.properties isn't at $version."
    apksigner=$(ls -d "$ANDROID_HOME"/build-tools/*/apksigner | tail -1)
    "$apksigner" verify --print-certs "$apk" | grep -q "certificate SHA-256 digest: $(certificate)" ||
        fail "The APK isn't signed with Pocket Foundry's key."
    game='(^|/)(data/(base|core)/|bin/(arm64|x64)/|factorio$)|level\.dat0$'   # Factorio's data, binary or a save
    ! unzip -Z1 "$apk" | grep -Eq "$game" || fail "The APK holds game files."
    ! xz -dc runtime/out/apk/assets/runtime.tar.xz | tar -t | grep -Eq "$game" || fail "The runtime holds game files."
    rm -rf "$out" && mkdir -p "$out"
    cp "$apk" "$out/$name"
    (cd "$out" && sha256sum "$name" > "$name.sha256")
    ;;
publish)
    case $version in *-*) beta=1 ;; *) beta= ;; esac
    # A beta lists what changed since the last tag; a release, since the last release, its betas included.
    if [ -n "$beta" ]; then previous=$(git describe --tags --abbrev=0 "$tag^" 2>/dev/null || true)
    else previous=$(git describe --tags --abbrev=0 --exclude='*-*' "$tag^" 2>/dev/null || true); fi
    run=$GITHUB_SERVER_URL/$GITHUB_REPOSITORY/actions/runs/$GITHUB_RUN_ID
    notes=$out/notes.md
    # One line per paragraph: GitHub keeps a release's line breaks.
    {
        if [ -n "$beta" ]; then
            echo "**Beta, for testing.** The app's update check skips betas: install this one by hand. It updates to the release once that's out."
            echo
        fi
        echo "Play your own copy of Factorio on Android: the official Linux ARM64 build, run without emulation on the phone's GPU."
        echo
        echo "**Install:** open \`$name\` on the phone (Android 10 or later; Adreno 6xx or newer, other GPUs through the phone's own driver (experimental) or an imported one, or any phone on the CPU, slowly), then import the Linux (arm64) .tar.xz from [factorio.com/download](https://factorio.com/download). The app holds no part of Factorio: you bring your own copy. A new version installs over the last and keeps the game and saves."
        echo
        echo "**Signing certificate (SHA-256):** \`$(certificate)\`"
        echo
        if [ -n "$previous" ]; then
            echo "**Since $previous:**"
            bump='^- Pocket Foundry [0-9.]+(-beta\.[0-9]+)?$'
            git log --reverse --format='- %s' "$previous..$tag" | grep -Ev "$bump" || true
        else
            echo "**First release.** What's in it: see the [README](https://github.com/JulioFerrero/pocket-foundry#readme)."
        fi
        echo
        echo "**Built on GitHub** from [$tag](https://github.com/JulioFerrero/pocket-foundry/tree/$tag), GPL-3.0, in [this run]($run): the APK and the runtime inside it (glibc, Mesa, libhybris, SDL, Termux:X11's X server), from the sources and build scripts in \`runtime/\` and \`third_party/\` at that tag. To check a download came from that build: \`gh attestation verify $name -R JulioFerrero/pocket-foundry\`. SHA-256 of the APK: \`$(cut -d' ' -f1 "$out/$name.sha256")\`"
    } > "$notes"
    gh release create "$tag" --verify-tag ${beta:+--prerelease} --title "Pocket Foundry $version" \
        --notes-file "$notes" "$out/$name" "$out/$name.sha256"
    ;;
*)
    fail "usage: $0 check|publish <version>"
    ;;
esac
