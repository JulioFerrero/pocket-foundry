#!/bin/sh
# Releases, beta or not. You tag; GitHub builds the tag and publishes it (.github/workflows/release.yml):
#   tools/release.sh 0.8.17-beta.1           a beta, from dev: a pre-release, which Obtainium skips by default
#   tools/release.sh 0.8.17                  a release, from main, once dev's betas work and dev is merged into it
#   tools/release.sh check|publish 0.8.17    GitHub's side, after its build: the checks, then the release
# Versions before 0.8.18 have their own update check (Updates.kt, since removed), which reads what this writes: the
# version, the assets' names and the notes' "Since" list. Its format stays, so those find their way to a newer one.
set -e
cd "$(dirname "$0")/.."
fail() { echo "$*" >&2; exit 1; }
VERSION='^[0-9]+\.[0-9]+(\.[0-9]+)?(-beta\.[0-9]+)?$'
beta() { case $1 in *-beta.*) return 0 ;; *) return 1 ;; esac; }
# The commit that sets a version: the notes leave these out.
bump() { echo "Pocket Foundry $1"; }
BUMP='^- Pocket Foundry [0-9.]+(-beta\.[0-9]+)?$'

case $1 in check|publish) mode=$1; version=$2 ;; *) mode=tag; version=$1 ;; esac
echo "$version" | grep -Eq "$VERSION" ||
    fail "usage: $0 [check|publish] <version: 0.8.17-beta.1 from dev, 0.8.17 from main>"
tag=v$version
name=pocket-foundry-$version.apk
out=build/release
# The signed APK: release.yml's, built as F-Droid builds it, or a local Gradle build.
apk=${APK:-app/build/outputs/apk/release/app-release.apk}
prop() { sed -n "s/^$1=//p" "$POCKETFOUNDRY_SIGNING"; }

# The key's certificate, as Android shows it: the notes print it so a download can be checked against it.
certificate() {
    PF_STOREPASS=$(prop storePassword) keytool -exportcert -keystore "$(prop storeFile)" -alias "$(prop keyAlias)" \
        -storepass:env PF_STOREPASS | sha256sum | cut -d' ' -f1
}

case $mode in
tag)
    if beta "$version"; then branch=dev; else branch=main; fi
    [ "$(git branch --show-current)" = $branch ] || fail "$version comes from $branch."
    [ -z "$(git status --porcelain --ignore-submodules=dirty)" ] || fail "Commit or stash your changes first."
    git fetch -q origin --tags
    [ "$(git rev-parse HEAD)" = "$(git rev-parse origin/$branch)" ] ||
        fail "$branch and origin/$branch differ: pull or push first."
    ! git rev-parse -q --verify "refs/tags/$tag" >/dev/null || fail "$tag already exists."

    # F-Droid builds releases, not betas, and shows each one's news from the fastlane folder, named by its versionCode.
    if ! beta "$version"; then
        next=$(sed -n 's/^versionCode=//p' gradle.properties)
        grep -qxF "versionName=$version" gradle.properties || next=$((next + 1))
        news=fastlane/metadata/android/en-US/changelogs/$next.txt
        [ -f "$news" ] || fail "Write and commit $news first: what's new in $version, for F-Droid."
        [ "$(wc -m < "$news")" -le 500 ] || fail "$news is over F-Droid's 500 characters."
    fi

    # So every beta installs over the one before.
    if ! grep -qxF "versionName=$version" gradle.properties; then
        code=$(sed -n 's/^versionCode=//p' gradle.properties)
        sed -i '' -e "s/^versionCode=.*/versionCode=$((code + 1))/" -e "s/^versionName=.*/versionName=$version/" \
            gradle.properties
        git commit -q -m "$(bump "$version")" gradle.properties
    fi
    git tag -a "$tag" -m "$(bump "$version")"
    git push -q origin $branch "$tag"

    if [ $branch = main ] && git merge-base --is-ancestor origin/dev HEAD 2>/dev/null; then
        git push -q origin HEAD:dev
        git branch -f dev HEAD
    fi
    echo "GitHub builds and publishes $tag: https://github.com/JulioFerrero/pocket-foundry/actions"
    ;;
check)
    [ "$(sed -n 's/^versionName=//p' gradle.properties)" = "$version" ] || fail "gradle.properties isn't at $version."
    apksigner=$(ls -d "$ANDROID_HOME"/build-tools/*/apksigner | tail -1)
    "$apksigner" verify --print-certs "$apk" | grep -q "certificate SHA-256 digest: $(certificate)" ||
        fail "The APK isn't signed with Pocket Foundry's key."
    game='(^|/)(data/(base|core)/|bin/(arm64|x64)/|factorio$)|level\.dat0$'   # Factorio's data, binary or a save
    ! unzip -Z1 "$apk" | grep -Eq "$game" || fail "The APK holds game files."
    ! unzip -p "$apk" assets/runtime.tar.xz | xz -dc | tar -t | grep -Eq "$game" || fail "The runtime holds game files."
    rm -rf "$out" && mkdir -p "$out"
    cp "$apk" "$out/$name"
    (cd "$out" && sha256sum "$name" > "$name.sha256")
    ;;
publish)
    beta "$version" && prerelease=1 || prerelease=
    # A beta's changes since the last tag, a release's since the last release.
    if [ -n "$prerelease" ]; then previous=$(git describe --tags --abbrev=0 "$tag^")
    else previous=$(git describe --tags --abbrev=0 --exclude='*-*' "$tag^"); fi
    repo=$GITHUB_SERVER_URL/$GITHUB_REPOSITORY
    run=$repo/actions/runs/$GITHUB_RUN_ID
    notes=$out/notes.md
    # One line per paragraph: GitHub keeps a release's line breaks.
    {
        if [ -n "$prerelease" ]; then
            echo "**Beta, for testing.** Install it by hand, or turn on Include prereleases for Pocket Foundry in Obtainium. It updates to the release once that's out."
            echo
        fi
        echo "Play your own copy of Factorio on Android: the official Linux ARM64 build, run without emulation on the phone's GPU."
        echo
        echo "**Install:** open \`$name\` on the phone (Android 10 or later) and import the Linux (arm64) .tar.xz from [factorio.com/download](https://factorio.com/download). Pocket Foundry then asks before downloading a driver for your GPU from pocket-foundry-drivers: Turnip on Adreno 6xx or newer, experimental builds on some Mali and Xclipse GPUs, MobileGlues or the CPU elsewhere. Without downloads it plays through the phone's own driver (experimental) or an imported one. The app holds no part of Factorio: you bring your own copy. A new version installs over the last and keeps the game and saves."
        echo
        echo "**Updates:** Pocket Foundry doesn't check for them itself. Add it to [Obtainium](https://github.com/ImranR98/Obtainium) with this repository's link, and Obtainium installs each new version."
        echo
        echo "**Signing certificate (SHA-256):** \`$(certificate)\`"
        echo
        echo "**Since $previous:**"
        git log --reverse --no-merges --format='- %s' "$previous..$tag" | grep -Ev "$BUMP" || true
        echo
        echo "**Built on GitHub** from [$tag]($repo/tree/$tag), GPL-3.0, in [this run]($run): the APK and the runtime inside it (glibc, Mesa, libhybris, SDL, Termux:X11's X server), from the sources and build scripts in \`runtime/\` and \`third_party/\` at that tag. To check a download came from that build: \`gh attestation verify $name -R $GITHUB_REPOSITORY\`. SHA-256 of the APK: \`$(cut -d' ' -f1 "$out/$name.sha256")\`"
    } > "$notes"
    gh release create "$tag" --verify-tag ${prerelease:+--prerelease} --title "Pocket Foundry $version" \
        --notes-file "$notes" "$out/$name" "$out/$name.sha256"
    ;;
esac
