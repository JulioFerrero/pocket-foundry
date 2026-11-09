#!/bin/sh
# Bumps the version and tags it; GitHub builds the tag and publishes it (.github/workflows/release.yml):
#   tools/release.sh 0.8.17-beta.1   a beta, from dev: a pre-release, which the app's update check skips
#   tools/release.sh 0.8.17          a release, from main, once dev's betas work and dev is merged into it
set -e
cd "$(dirname "$0")/.."

version=$1
echo "$version" | grep -Eq '^[0-9]+\.[0-9]+(\.[0-9]+)?(-beta\.[0-9]+)?$' ||
    { echo "usage: $0 <version: 0.8.17-beta.1 from dev, 0.8.17 from main>"; exit 1; }
tag=v$version
fail() { echo "$*" >&2; exit 1; }
case $version in *-beta.*) branch=dev ;; *) branch=main ;; esac

[ "$(git branch --show-current)" = $branch ] || fail "$version comes from $branch."
[ -z "$(git status --porcelain --ignore-submodules=dirty)" ] || fail "Commit or stash your changes first."
git fetch -q origin --tags
[ "$(git rev-parse HEAD)" = "$(git rev-parse origin/$branch)" ] ||
    fail "$branch and origin/$branch differ: pull or push first."
! git rev-parse -q --verify "refs/tags/$tag" >/dev/null || fail "$tag already exists."

# A new versionName bumps versionCode by one: every beta installs over the one before.
if ! grep -qxF "versionName=$version" gradle.properties; then
    code=$(sed -n 's/^versionCode=//p' gradle.properties)
    sed -i '' -e "s/^versionCode=.*/versionCode=$((code + 1))/" -e "s/^versionName=.*/versionName=$version/" \
        gradle.properties
    git commit -q -m "Pocket Foundry $version" gradle.properties
fi
git tag -a "$tag" -m "Pocket Foundry $version"
git push -q origin $branch "$tag"

# dev carries on from the release, when it has nothing newer.
if [ $branch = main ] && git merge-base --is-ancestor origin/dev HEAD 2>/dev/null; then
    git push -q origin HEAD:dev
    git branch -f dev HEAD
fi
echo "GitHub builds and publishes $tag: https://github.com/JulioFerrero/pocket-foundry/actions"
