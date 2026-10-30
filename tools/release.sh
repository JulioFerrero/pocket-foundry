#!/bin/sh
# Builds a signed release APK and publishes it as a GitHub release:
#   tools/release.sh 0.3            commit, tag v0.3, build, upload, publish
#   tools/release.sh 0.3 --draft    the same, left as a draft
set -e
cd "$(dirname "$0")/.."

version=$1
echo "$version" | grep -Eq '^[0-9]+\.[0-9]+(\.[0-9]+)?$' || { echo "usage: $0 <version, e.g. 0.3> [--draft]"; exit 1; }
draft=
[ "$2" = "--draft" ] && draft=--draft
tag=v$version
fail() { echo "$*" >&2; exit 1; }

[ "$(git branch --show-current)" = main ] || fail "Releases come from main."
[ -z "$(git status --porcelain --ignore-submodules=dirty)" ] || fail "Commit or stash your changes first."
git fetch -q origin main --tags
[ "$(git rev-parse HEAD)" = "$(git rev-parse origin/main)" ] || fail "main and origin/main differ: pull or push first."
! git rev-parse -q --verify "refs/tags/$tag" >/dev/null || fail "$tag already exists."
gh auth status >/dev/null 2>&1 || fail "Log in to GitHub first: gh auth login"
signing=${POCKETFOUNDRY_SIGNING:-../pocketfoundry-keys/keystore.properties}
[ -f "$signing" ] || fail "Pocket Foundry's key is missing: a release signed with another key won't install over earlier ones."

# Every runtime piece built from this commit, and assemble after the others.
stale=
for piece in glibc mesa sdl wm assemble; do
    built=$(cat "runtime/out/$piece.stamp" 2>/dev/null || true)
    now="$(git rev-parse "HEAD:runtime/$piece") $(git rev-parse HEAD:runtime/Dockerfile)"
    if [ "$built" != "$now" ] || { [ $piece != assemble ] && [ "runtime/out/$piece.stamp" -nt runtime/out/assemble.stamp ]; }; then
        stale="$stale $piece"
    fi
done
case "$stale" in *assemble*) ;; ?*) stale="$stale assemble" ;; esac
[ -z "$stale" ] || fail "The runtime isn't built from this commit. Run: runtime/build.sh$stale"

# A new versionName bumps versionCode by one.
if ! grep -q "versionName '$version'" app/build.gradle; then
    perl -pi -e 's/^(\s*versionCode )(\d+)$/$1.($2+1)/e; s/^(\s*versionName )\x27[^\x27]*\x27$/$1\x27'"$version"'\x27/' app/build.gradle
    trap 'git checkout -q app/build.gradle' EXIT
fi

tools/build.sh :app:assembleRelease
apk=app/build/outputs/apk/release/app-release.apk

# Signed with Pocket Foundry's key, and nothing of Factorio's inside.
sdk=$(sed -n 's/^sdk.dir=//p' local.properties)
apksigner=$(ls -d "$sdk"/build-tools/*/apksigner | tail -1)
prop() { sed -n "s/^$1=//p" "$signing"; }
ours=$(PF_STOREPASS=$(prop storePassword) keytool -exportcert -keystore "$(prop storeFile)" -alias "$(prop keyAlias)" \
       -storepass:env PF_STOREPASS | shasum -a 256 | cut -d' ' -f1)
"$apksigner" verify --print-certs "$apk" | grep -q "certificate SHA-256 digest: $ours" ||
    fail "The APK isn't signed with Pocket Foundry's key."
game='(^|/)(data/(base|core)/|bin/x64/|factorio$)|\.dat0$'
! unzip -Z1 "$apk" | grep -Eq "$game" || fail "The APK holds game files."
! xz -dc runtime/out/apk/assets/runtime.tar.xz | tar -t | grep -Eq "$game" || fail "The runtime holds game files."

out=build/release
name=pocket-foundry-$version.apk
rm -rf "$out" && mkdir -p "$out"
cp "$apk" "$out/$name"
(cd "$out" && shasum -a 256 "$name" > "$name.sha256")

previous=$(git describe --tags --abbrev=0 2>/dev/null || true)
if ! git diff --quiet app/build.gradle; then
    git commit -q -m "Pocket Foundry $version" app/build.gradle
fi
trap - EXIT
git tag -a "$tag" -m "Pocket Foundry $version"
git push -q origin main "$tag"

notes=$out/notes.md
# One line per paragraph: GitHub keeps a release's line breaks.
{
    echo "Play your own copy of Factorio on Android: the official Linux ARM64 build, run without emulation on the phone's GPU."
    echo
    echo "**Install:** open \`$name\` on the phone (Android 10 or later; Adreno 6xx or newer, or another GPU with an imported driver), then import the Linux (arm64) .tar.xz from [factorio.com/download](https://factorio.com/download). The app holds no part of Factorio: you bring your own copy. A new version installs over the last and keeps the game and saves."
    echo
    if [ -n "$previous" ]; then
        echo "**Since $previous:**"
        git log --reverse --format='- %s' "$previous..$tag" | grep -v "^- Pocket Foundry [0-9.]*$" || true
    else
        echo "**First release.** What's in it: see the [README](https://github.com/JulioFerrero/pocket-foundry#readme)."
    fi
    echo
    echo "**Source:** this release is built from [$tag](https://github.com/JulioFerrero/pocket-foundry/tree/$tag), GPL-3.0. The runtime inside (glibc, Mesa, SDL, Termux:X11's X server) comes from the sources and build scripts in \`runtime/\` and \`third_party/\` at that tag. SHA-256 of the APK: \`$(cut -d' ' -f1 "$out/$name.sha256")\`"
} > "$notes"

gh release create "$tag" $draft --title "Pocket Foundry $version" --notes-file "$notes" "$out/$name" "$out/$name.sha256"
