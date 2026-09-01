#!/usr/bin/env bash
set -euo pipefail

# Stages a verified Pirate Unified Wallet JNI QDN bundle. Host-specific bundles
# are for isolated testing; the "all" bundle contains every supported desktop
# library and is the only shape accepted by the guarded release workflow.
# This script deliberately does not publish or sign anything.

readonly EXPECTED_SHA256='059781c5a2cdeb8c5d60f1130c4bf3a217822d39438e560bc11633993df0e1e9'
readonly RELEASE_URL='https://github.com/PirateNetwork/Pirate-Unified-Light-Wallet/releases/download/v1.1.9/pirate-unified-wallet-qortal-jni-artifacts-v1.1.9.zip'

usage() {
	cat <<'EOF'
Usage: tools/stage-pirate-unified-qdn-bundle.sh <artifact.zip> <output-directory> [platform|all]

Platforms: linux-x86_64, linux-aarch64, macos-x86_64, macos-aarch64, windows-x86_64

Use "all" to stage the five-library cross-platform production bundle.
When omitted, platform defaults to the publishing host.

The artifact must be the Pirate Unified Wallet v1.1.9 Qortal JNI archive with
the SHA-256 pinned in this script. The output directory must not exist.
EOF
}

sha256() {
	if command -v sha256sum >/dev/null; then
		sha256sum "$1" | awk '{print $1}'
	else
		shasum -a 256 "$1" | awk '{print $1}'
	fi
}

detect_platform() {
	local os arch
	os=$(uname -s)
	arch=$(uname -m)
	case "${os}:${arch}" in
		Linux:x86_64) printf '%s\n' 'linux-x86_64' ;;
		Linux:aarch64) printf '%s\n' 'linux-aarch64' ;;
		Darwin:x86_64) printf '%s\n' 'macos-x86_64' ;;
		Darwin:arm64) printf '%s\n' 'macos-aarch64' ;;
		*) return 1 ;;
	esac
}

library_for_platform() {
	case $1 in
		linux-x86_64) printf '%s\n' 'librust-linux-x86_64.so' ;;
		linux-aarch64) printf '%s\n' 'librust-linux-aarch64.so' ;;
		macos-x86_64) printf '%s\n' 'librust-macos-x86_64.dylib' ;;
		macos-aarch64) printf '%s\n' 'librust-macos-aarch64.dylib' ;;
		windows-x86_64) printf '%s\n' 'librust-windows-x86_64.dll' ;;
		*) return 1 ;;
	esac
}

if (( $# < 2 || $# > 3 )); then
	usage >&2
	exit 2
fi

artifact=$1
output_directory=$2
platform=${3:-}

if [[ ! -f $artifact ]]; then
	echo "Artifact does not exist: $artifact" >&2
	exit 1
fi
if [[ -e $output_directory ]]; then
	echo "Refusing to overwrite existing output directory: $output_directory" >&2
	exit 1
fi
if [[ -z $platform ]]; then
	if ! platform=$(detect_platform); then
		echo 'Unable to detect a supported platform; pass it as the third argument.' >&2
		exit 1
	fi
fi

declare -a libraries
if [[ $platform == all ]]; then
	libraries=(
		'librust-linux-x86_64.so'
		'librust-linux-aarch64.so'
		'librust-macos-x86_64.dylib'
		'librust-macos-aarch64.dylib'
		'librust-windows-x86_64.dll'
	)
	bundle_kind='cross-platform'
else
	if ! library=$(library_for_platform "$platform"); then
		echo "Unsupported platform: $platform" >&2
		usage >&2
		exit 2
	fi
	libraries=("$library")
	bundle_kind='host-specific'
fi

actual_sha256=$(sha256 "$artifact")
if [[ $actual_sha256 != "$EXPECTED_SHA256" ]]; then
	echo "Artifact SHA-256 mismatch: expected $EXPECTED_SHA256, got $actual_sha256" >&2
	exit 1
fi

if ! command -v unzip >/dev/null; then
	echo 'unzip is required to stage the QDN bundle.' >&2
	exit 1
fi

parent_directory=$(dirname "$output_directory")
mkdir -p "$parent_directory"
staging_directory=$(mktemp -d "$parent_directory/.pirate-unified-qdn.XXXXXX")
trap 'rm -rf "$staging_directory"' EXIT

for file in "${libraries[@]}" LICENSE-qortal-jni.txt qortal-handoff.md; do
	if ! unzip -Z1 "$artifact" "$file" | grep -qx "$file"; then
		echo "Verified artifact is missing $file" >&2
		exit 1
	fi
	unzip -p "$artifact" "$file" > "$staging_directory/$file"
done

{
	printf '%s\n' 'Pirate Unified Wallet Qortal JNI bundle'
	printf '%s\n' "bundle-kind: $bundle_kind"
	printf '%s\n' "release-url: $RELEASE_URL"
	printf '%s\n' "artifact-sha256: $EXPECTED_SHA256"
	printf '%s\n' "platform: $platform"
	for library in "${libraries[@]}"; do
		printf '%s\n' "native-library: $library"
	done
} > "$staging_directory/MANIFEST.txt"

mv "$staging_directory" "$output_directory"
trap - EXIT
printf 'Staged verified %s QDN bundle: %s\n' "$bundle_kind" "$output_directory"
