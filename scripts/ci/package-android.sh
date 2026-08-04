#!/usr/bin/env bash

set -euo pipefail

mode="${1:-}"
release_ref="${2:-}"

case "$mode" in
    latest | release) ;;
    *)
        echo "Usage: $0 <latest|release> [vX.Y.Z]" >&2
        exit 2
        ;;
esac

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

required_signing_variables=(
    ANDROID_KEYSTORE_BASE64
    ANDROID_KEYSTORE_PASSWORD
    ANDROID_KEY_ALIAS
    ANDROID_KEY_PASSWORD
)
for variable_name in "${required_signing_variables[@]}"; do
    if [[ -z "${!variable_name:-}" ]]; then
        echo "Missing required signing variable: $variable_name" >&2
        exit 1
    fi
done

signing_dir="$(mktemp -d "${TMPDIR:-/tmp}/ubuntu-manager-signing.XXXXXX")"
cleanup() {
    rm -rf -- "$signing_dir"
}
trap cleanup EXIT

keystore_path="$signing_dir/release-keystore.p12"
printf '%s' "$ANDROID_KEYSTORE_BASE64" | base64 --decode > "$keystore_path"
chmod 600 "$keystore_path"

export ANDROID_KEYSTORE_PATH="$keystore_path"
export CI_RELEASE_BUILD=true

./gradlew --no-daemon clean testDebugUnitTest lintRelease assembleRelease

apk_path="$repo_root/app/build/outputs/apk/release/app-release.apk"
if [[ ! -f "$apk_path" ]]; then
    echo "Signed release APK was not produced at $apk_path" >&2
    exit 1
fi

android_sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$android_sdk" || ! -d "$android_sdk/build-tools" ]]; then
    echo "ANDROID_SDK_ROOT or ANDROID_HOME does not point to an Android SDK" >&2
    exit 1
fi

apksigner="$(find "$android_sdk/build-tools" -mindepth 2 -maxdepth 2 -type f -name apksigner | sort -V | tail -n 1)"
aapt="$(find "$android_sdk/build-tools" -mindepth 2 -maxdepth 2 -type f -name aapt | sort -V | tail -n 1)"
if [[ -z "$apksigner" || -z "$aapt" ]]; then
    echo "Android build tools are missing apksigner or aapt" >&2
    exit 1
fi

"$apksigner" verify --verbose --print-certs "$apk_path"
apk_entries="$signing_dir/apk-entries.txt"
unzip -Z1 "$apk_path" > "$apk_entries"
if ! grep -Fxq 'lib/arm64-v8a/libcntermux_sparse.so' "$apk_entries"; then
    echo "APK does not contain the required ARM64 sparse-copy library" >&2
    exit 1
fi

package_line="$("$aapt" dump badging "$apk_path" | sed -n '1p')"
version_code="$(printf '%s\n' "$package_line" | sed -n "s/.*versionCode='\([^']*\)'.*/\1/p")"
version_name="$(printf '%s\n' "$package_line" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p")"
if [[ -z "$version_code" || -z "$version_name" ]]; then
    echo "Unable to read version metadata from APK" >&2
    exit 1
fi

if [[ "$mode" == "release" ]]; then
    normalized_ref="${release_ref#refs/tags/}"
    expected_ref="v$version_name"
    if [[ "$normalized_ref" != "$expected_ref" ]]; then
        echo "Release ref $normalized_ref does not match APK version $expected_ref" >&2
        exit 1
    fi
fi

dist_dir="$repo_root/dist"
mkdir -p "$dist_dir"
find "$dist_dir" -mindepth 1 -maxdepth 1 -type f -delete

latest_name="UbuntuManager-latest-arm64-v8a.apk"
install -m 0644 "$apk_path" "$dist_dir/$latest_name"
(
    cd "$dist_dir"
    sha256sum "$latest_name" > "$latest_name.sha256"
)

if [[ "$mode" == "release" ]]; then
    versioned_name="UbuntuManager-v${version_name}-arm64-v8a.apk"
    install -m 0644 "$apk_path" "$dist_dir/$versioned_name"
    (
        cd "$dist_dir"
        sha256sum "$versioned_name" > "$versioned_name.sha256"
    )
fi

mapping_path="$repo_root/app/build/outputs/mapping/release/mapping.txt"
if [[ -f "$mapping_path" ]]; then
    install -m 0644 "$mapping_path" "$dist_dir/UbuntuManager-v${version_name}-mapping.txt"
fi

commit_sha="$(git rev-parse HEAD 2>/dev/null || printf 'unknown')"
{
    printf 'versionName=%s\n' "$version_name"
    printf 'versionCode=%s\n' "$version_code"
    printf 'commit=%s\n' "$commit_sha"
    printf 'mode=%s\n' "$mode"
} > "$dist_dir/build-info.txt"

if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
    {
        printf 'version_name=%s\n' "$version_name"
        printf 'version_code=%s\n' "$version_code"
    } >> "$GITHUB_OUTPUT"
fi

printf 'Packaged signed Ubuntu Manager %s (%s) in %s\n' "$version_name" "$version_code" "$dist_dir"
