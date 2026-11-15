#!/usr/bin/env bash
# Builds the signed arm64 APK and publishes it as a GitHub release.
#
#     scripts/release.sh            # release v<versionName> from app/build.gradle.kts
#     scripts/release.sh --dry-run  # build and show what would be published
#
# Needs the release key (see "Releasing" in the README), the Android SDK
# (ANDROID_HOME), a JDK and the GitHub CLI.
set -euo pipefail
cd "$(dirname "$0")/.."

dry_run=false
[[ "${1:-}" == "--dry-run" ]] && dry_run=true

version=$(sed -n 's/^ *versionName = "\(.*\)"$/\1/p' app/build.gradle.kts)
tag="v$version"
[[ -n "$version" ]] || { echo "versionName not found in app/build.gradle.kts" >&2; exit 1; }

if ! $dry_run; then
    [[ -z "$(git status --porcelain)" ]] || { echo "the working tree has changes: commit them first" >&2; exit 1; }
    git fetch --quiet origin main
    [[ "$(git rev-parse HEAD)" == "$(git rev-parse origin/main)" ]] || { echo "HEAD is not origin/main: push first" >&2; exit 1; }
    ! gh release view "$tag" >/dev/null 2>&1 || { echo "$tag already exists: bump versionName and versionCode" >&2; exit 1; }
fi

./gradlew --console=plain -q :core:test :app:lintRelease :app:assembleRelease -Pfulltalk.abis=arm64-v8a

built=app/build/outputs/apk/release/app-release.apk
apksigner=$(ls "$ANDROID_HOME"/build-tools/*/apksigner | sort -V | tail -1)
certificate=$("$apksigner" verify --print-certs "$built" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')
[[ -n "$certificate" ]] || { echo "the APK is not signed: set fulltalk.keystore (see the README)" >&2; exit 1; }

out=build/release
mkdir -p "$out"
cp "$built" "$out/fulltalk-dev.apk"
sha256=$(sha256sum "$out/fulltalk-dev.apk" | cut -d' ' -f1)

notes="$out/notes.md"
cat > "$notes" <<NOTES
Download **fulltalk-dev.apk** below and open it on the phone (Android 8.0 or newer, 64-bit ARM).

Android may refuse to install an app that comes from outside the Play Store and asks for an accessibility service. The way past each block is in the README: [If Android gets in the way](https://github.com/RafaelFariaFerreira/fulltalk-android#if-android-gets-in-the-way).

| | |
|---|---|
| APK SHA-256 | \`$sha256\` |
| Signing certificate SHA-256 | \`$certificate\` |
NOTES

echo "$tag  $out/fulltalk-dev.apk  $(du -h "$out/fulltalk-dev.apk" | cut -f1)"
echo "sha256       $sha256"
echo "certificate  $certificate"
if $dry_run; then
    echo "dry run: nothing published"
    exit 0
fi

gh release create "$tag" "$out/fulltalk-dev.apk" \
    --title "fulltalk dev $version" --notes-file "$notes" --generate-notes
