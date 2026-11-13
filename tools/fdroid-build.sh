#!/bin/bash
# Builds this checkout's commit as F-Droid's build server does: run as root in F-Droid's image
# (registry.gitlab.com/fdroid/fdroidserver:buildserver-trixie), with the recipe from tools/fdroid-recipe.sh, F-Droid's
# own tools and its paths, the way fdroiddata's CI job runs it. The unsigned APK lands in build/fdroid/;
# .github/workflows/release.yml signs it, and F-Droid's own build of the same commit has to come out the same.
set -euo pipefail
cd "$(dirname "$0")/.."
SRC=$PWD
source /etc/profile.d/bsenv.sh   # the image's home_vagrant, fdroidserver and ANDROID_HOME
export ANDROID_HOME=/opt/android-sdk serverwebroot=/tmp
appid=dev.pocketfoundry
code=$(sed -n 's/^versionCode=//p' gradle.properties)

# fdroid build clones this checkout as F-Droid's vagrant user.
git config --system --add safe.directory '*'
git submodule update -q --init third_party/termux-x11
mkdir -p "$home_vagrant/metadata" "$home_vagrant/tmp" "$home_vagrant/.android"
# Without Binaries and AllowedAPKSigningKeys: with them fdroid build compares its APK with our release, not out yet.
tools/fdroid-recipe.sh HEAD "$SRC" | grep -v -e '^Binaries:' -e '^AllowedAPKSigningKeys:' \
    > "$home_vagrant/metadata/$appid.yml"

apt-get update -qq
apt-get install -qq -y --no-install-recommends sudo openjdk-21-jdk-headless > /dev/null
update-alternatives --set java /usr/lib/jvm/java-21-openjdk-amd64/bin/java
sdkmanager "platform-tools" "build-tools;31.0.0" > /dev/null
[ -d "$fdroidserver" ] || git clone -q --depth 1 https://gitlab.com/fdroid/fdroidserver.git "$fdroidserver"
git -C "$home_vagrant/gradlew-fdroid" pull -q
chown -R vagrant "$home_vagrant"

cd "$home_vagrant"
# fdroid build runs the recipe's sudo lines, then takes sudo away for the build itself.
sudo --preserve-env --user vagrant env PATH="$fdroidserver:$PATH" PYTHONPATH="$fdroidserver:$fdroidserver/examples" \
    PYTHONUNBUFFERED=true HOME="$home_vagrant" GRADLE_USER_HOME="$home_vagrant/.gradle" \
    fdroid build --verbose --test --refresh-scanner --on-server --no-tarball "$appid:$code"
mkdir -p "$SRC/build/fdroid"
cp "$home_vagrant/tmp/${appid}_$code.apk" "$SRC/build/fdroid/"
echo "built: build/fdroid/${appid}_$code.apk"
