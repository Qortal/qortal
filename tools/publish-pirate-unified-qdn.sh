#!/usr/bin/env bash
set -euo pipefail

# Builds, signs, and submits the QDN ARBITRARY_DATA transaction for a staged
# Pirate Unified Wallet JNI bundle. Test publication is host-specific; final
# release publication is guarded and requires the five-library bundle. The
# workflow is deliberately two phase: prepare a fee-paying transaction, sign
# it locally or offline, then submit the signed transaction. The source ZIP is
# never published.

readonly EXPECTED_ARTIFACT_SHA256='059781c5a2cdeb8c5d60f1130c4bf3a217822d39438e560bc11633993df0e1e9'
readonly DEFAULT_IDENTIFIER='LiteWalletJNI-2026-08-unified-v1.1.9-test'

usage() {
	cat <<'EOF'
Usage:
  tools/publish-pirate-unified-qdn.sh validate <test|release> <bundle-directory>
  tools/publish-pirate-unified-qdn.sh prepare <bundle-directory> <test-name> [identifier] [unsigned-output]
  tools/publish-pirate-unified-qdn.sh prepare-release <bundle-directory> <release-name> <release-identifier> [unsigned-output]
  tools/publish-pirate-unified-qdn.sh sign <unsigned-input> [signed-output]
  tools/publish-pirate-unified-qdn.sh submit <signed-input> [signature-output]
  tools/publish-pirate-unified-qdn.sh verify <transaction-signature>
  tools/publish-pirate-unified-qdn.sh publish <bundle-directory> <test-name> [identifier]
  tools/publish-pirate-unified-qdn.sh release <bundle-directory> <release-name> <release-identifier>

Environment:
  QORTAL_API_URL  Qortal Core API URL (default: http://127.0.0.1:12391)
  QORTAL_API_KEY  API key for the local Qortal Core instance (prompted for if absent)
  QDN_FEE         Fee in QORT atomic units (default: 1000000, i.e. 0.01 QORT)

Commands:
  validate Test-only preflight for a one-library bundle, or release preflight
           for the required five-library cross-platform bundle. It reads no API
           key and does not contact Qortal Core.
  prepare  Test-only: validate a one-library host-specific bundle and build an
           unsigned, fee-paying transaction. No transaction is signed or
           broadcast.
  prepare-release
           Release-only: validate the all-architecture bundle and build an
           unsigned, fee-paying transaction. A release name and a new immutable
           identifier are both required. No transaction is signed or broadcast.
  sign     Prompt for the private key that owns the QDN name and produce an
           already-signed transaction. Run only on a trusted local signer.
  submit   Broadcast an already-signed transaction and print its immutable
           transaction signature. No private key is read by this command.
  verify   Print the fields that must match the intended ARBITRARY_DATA QDN
           publication.
  publish  Test-only convenience flow for a trusted local signer: prepare,
           sign, then ask for an explicit final confirmation before broadcast.
  release  Release-only convenience flow. It accepts only the five-library
           cross-platform bundle and requires typing PUBLISH RELEASE before
           broadcast.

The bundle must be the output of stage-pirate-unified-qdn-bundle.sh. A test
publication must use a test name and a new identifier. A final release must use
the "all" staged bundle, a release name controlled by the operator, and a new
immutable identifier.
EOF
}

die() {
	echo "Error: $*" >&2
	exit 1
}

require_command() {
	command -v "$1" >/dev/null 2>&1 || die "Required command is unavailable: $1"
}

require_api_settings() {
	QORTAL_API_URL=${QORTAL_API_URL:-http://127.0.0.1:12391}
	QORTAL_API_URL=${QORTAL_API_URL%/}
	[[ $QORTAL_API_URL =~ ^https?:// ]] || die 'QORTAL_API_URL must begin with http:// or https://'

	if [[ -z ${QORTAL_API_KEY:-} ]]; then
		read -r -s -p 'Qortal API key: ' QORTAL_API_KEY
		printf '\n'
	fi
	[[ -n $QORTAL_API_KEY ]] || die 'A Qortal API key is required.'
}

require_fee() {
	QDN_FEE=${QDN_FEE:-1000000}
	[[ $QDN_FEE =~ ^[1-9][0-9]*$ ]] || die 'QDN_FEE must be a positive whole number of QORT atomic units.'
}

url_encode() {
	jq -rn --arg value "$1" '$value | @uri'
}

base58_file_payload() {
	local input_file=$1
	[[ -f $input_file ]] || die "Transaction file does not exist: $input_file"
	local payload
	payload=$(tr -d '\r\n' < "$input_file")
	[[ $payload =~ ^[1-9A-HJ-NP-Za-km-z]+$ ]] || die "Transaction file is not Base58: $input_file"
	printf '%s' "$payload"
}

write_new_file() {
	local output_file=$1
	local payload=$2
	[[ ! -e $output_file ]] || die "Refusing to overwrite existing file: $output_file"
	local output_parent
	output_parent=$(dirname "$output_file")
	[[ -d $output_parent ]] || die "Output directory does not exist: $output_parent"
	( umask 077; printf '%s\n' "$payload" > "$output_file" )
	printf 'Wrote: %s\n' "$output_file"
}

safe_file_component() {
	local value=$1
	printf '%s' "$value" | tr -cs 'A-Za-z0-9._-' '_'
}

convenience_file_stem() {
	local publication_kind=$1
	local publication_name=$2
	local identifier=$3
	local timestamp safe_name safe_identifier
	timestamp=$(date -u +%Y%m%dT%H%M%SZ)
	safe_name=$(safe_file_component "$publication_name")
	safe_identifier=$(safe_file_component "$identifier")
	printf 'qdn-unified-wallet-%s-%s-%s-%s-%s' \
		"$publication_kind" "$safe_name" "$safe_identifier" "$timestamp" "$$"
}

sha256() {
	if command -v sha256sum >/dev/null 2>&1; then
		sha256sum "$1" | awk '{print $1}'
	else
		shasum -a 256 "$1" | awk '{print $1}'
	fi
}

validate_bundle() {
	local expected_bundle_kind=$1
	local bundle_directory=$2
	[[ $expected_bundle_kind == host-specific || $expected_bundle_kind == cross-platform ]] \
		|| die "Invalid expected bundle kind: $expected_bundle_kind"
	[[ -d $bundle_directory ]] || die "Bundle directory does not exist: $bundle_directory"
	bundle_directory=$(cd "$bundle_directory" && pwd -P)

	local required_file
	for required_file in MANIFEST.txt LICENSE-qortal-jni.txt qortal-handoff.md; do
		[[ -f $bundle_directory/$required_file ]] || die "Bundle is missing $required_file"
	done

	grep -Fqx "artifact-sha256: $EXPECTED_ARTIFACT_SHA256" "$bundle_directory/MANIFEST.txt" \
		|| die 'Bundle manifest does not identify the pinned Pirate v1.1.9 artifact.'
	grep -Fqx "bundle-kind: $expected_bundle_kind" "$bundle_directory/MANIFEST.txt" \
		|| die "Bundle is not a $expected_bundle_kind bundle."

	local -a native_libraries=()
	local library_name
	while IFS= read -r library_name; do
		native_libraries+=("$library_name")
	done < <(find "$bundle_directory" -maxdepth 1 -type f \( \
		-name 'librust-linux-x86_64.so' -o \
		-name 'librust-linux-aarch64.so' -o \
		-name 'librust-macos-x86_64.dylib' -o \
		-name 'librust-macos-aarch64.dylib' -o \
		-name 'librust-windows-x86_64.dll' \
	\) -printf '%f\n' | sort)

	if [[ $expected_bundle_kind == host-specific ]]; then
		(( ${#native_libraries[@]} == 1 )) || die 'Test bundle must contain exactly one supported native library.'
		grep -Fqx "platform: all" "$bundle_directory/MANIFEST.txt" \
			&& die 'Test bundle must not use platform: all.'
	else
		local -a required_release_libraries=(
			'librust-linux-x86_64.so'
			'librust-linux-aarch64.so'
			'librust-macos-x86_64.dylib'
			'librust-macos-aarch64.dylib'
			'librust-windows-x86_64.dll'
		)
		(( ${#native_libraries[@]} == ${#required_release_libraries[@]} )) \
			|| die 'Release bundle must contain all five supported native libraries.'
		grep -Fqx 'platform: all' "$bundle_directory/MANIFEST.txt" \
			|| die 'Release bundle manifest must use platform: all.'
		for library_name in "${required_release_libraries[@]}"; do
			[[ -f $bundle_directory/$library_name ]] \
				|| die "Release bundle is missing $library_name"
		done
	fi

	for library_name in "${native_libraries[@]}"; do
		grep -Fqx "native-library: $library_name" "$bundle_directory/MANIFEST.txt" \
			|| die "Bundle manifest native-library entry does not match $library_name."
	done

	local file_count
	file_count=$(find "$bundle_directory" -maxdepth 1 -type f -printf '%f\n' | wc -l)
	local expected_file_count=$(( ${#native_libraries[@]} + 3 ))
	(( file_count == expected_file_count )) \
		|| die 'Bundle must contain only its native library files, manifest, license, and handoff file.'

	printf 'Validated %s bundle: %s\n' "$expected_bundle_kind" "$bundle_directory" >&2
	for library_name in "${native_libraries[@]}"; do
		printf 'Native library: %s\n' "$library_name" >&2
		printf 'Native library SHA-256: %s\n' "$(sha256 "$bundle_directory/$library_name")" >&2
	done
	printf '%s' "$bundle_directory"
}

api_post() {
	local path=$1
	local content_type=$2
	local body=$3
	curl --fail --silent --show-error --request POST \
		-H "X-API-KEY: $QORTAL_API_KEY" \
		-H "Content-Type: $content_type" \
		--data-binary "$body" \
		"$QORTAL_API_URL$path"
}

api_post_stdin() {
	local path=$1
	local content_type=$2
	curl --fail --silent --show-error --request POST \
		-H "X-API-KEY: $QORTAL_API_KEY" \
		-H "Content-Type: $content_type" \
		--data-binary @- \
		"$QORTAL_API_URL$path"
}

prepare_transaction() {
	local expected_bundle_kind=$1
	local bundle_directory=$2
	local publication_name=$3
	local identifier=$4
	local output_file=$5

	require_command curl
	require_command jq
	require_api_settings
	require_fee
	bundle_directory=$(validate_bundle "$expected_bundle_kind" "$bundle_directory")
	[[ -n $publication_name ]] || die 'Publication name must not be empty.'
	[[ -n $identifier ]] || die 'Identifier must not be empty.'

	local encoded_name encoded_identifier raw_unsigned
	encoded_name=$(url_encode "$publication_name")
	encoded_identifier=$(url_encode "$identifier")

	printf 'Building unsigned ARBITRARY_DATA transaction for name "%s", identifier "%s", fee %s atomic units...\n' \
		"$publication_name" "$identifier" "$QDN_FEE"
	raw_unsigned=$(api_post "/arbitrary/ARBITRARY_DATA/$encoded_name/$encoded_identifier?fee=$QDN_FEE" text/plain "$bundle_directory")
	[[ $raw_unsigned =~ ^[1-9A-HJ-NP-Za-km-z]+$ ]] || die 'Qortal Core did not return an unsigned Base58 transaction.'
	write_new_file "$output_file" "$raw_unsigned"
	printf 'Prepared fee-paying transaction. Sign this file locally or on an offline signer: %s\n' "$output_file"
}

sign_transaction() {
	local input_file=$1
	local output_file=$2

	require_command curl
	require_command jq
	require_api_settings
	local raw_unsigned private_key sign_request signed_transaction
	raw_unsigned=$(base58_file_payload "$input_file")
	read -r -s -p 'Private key for the QDN name owner: ' private_key
	printf '\n'
	[[ $private_key =~ ^[1-9A-HJ-NP-Za-km-z]+$ ]] || die 'Private key is not Base58.'
	sign_request=$(jq -cn --arg privateKey "$private_key" --arg transactionBytes "$raw_unsigned" \
		'{privateKey: $privateKey, transactionBytes: $transactionBytes}')
	private_key=''
	signed_transaction=$(printf '%s' "$sign_request" | api_post_stdin '/transactions/sign' application/json)
	sign_request=''
	[[ $signed_transaction =~ ^[1-9A-HJ-NP-Za-km-z]+$ ]] || die 'Qortal Core did not return a signed Base58 transaction.'
	write_new_file "$output_file" "$signed_transaction"
	printf 'Signed transaction is ready for broadcast: %s\n' "$output_file"
}

submit_transaction() {
	local input_file=$1
	local output_file=$2

	require_command curl
	require_command jq
	require_api_settings
	local signed_transaction response signature
	signed_transaction=$(base58_file_payload "$input_file")
	response=$(curl --fail --silent --show-error --request POST \
		-H "X-API-KEY: $QORTAL_API_KEY" \
		-H 'X-API-VERSION: 2' \
		-H 'Content-Type: text/plain' \
		--data-binary "$signed_transaction" \
		"$QORTAL_API_URL/transactions/process")
	signature=$(printf '%s' "$response" | jq -er '.signature // empty') \
		|| die "Qortal Core did not accept the signed transaction: $response"
	[[ $signature =~ ^[1-9A-HJ-NP-Za-km-z]+$ ]] || die 'Qortal Core returned an invalid transaction signature.'
	write_new_file "$output_file" "$signature"
	printf 'Broadcast accepted. Immutable QDN transaction signature: %s\n' "$signature"
	printf 'Wait for confirmation, then run: %s verify %s\n' "$0" "$signature"
}

verify_transaction() {
	local signature=$1
	require_command curl
	require_command jq
	QORTAL_API_URL=${QORTAL_API_URL:-http://127.0.0.1:12391}
	QORTAL_API_URL=${QORTAL_API_URL%/}
	[[ $QORTAL_API_URL =~ ^https?:// ]] || die 'QORTAL_API_URL must begin with http:// or https://'
	[[ $signature =~ ^[1-9A-HJ-NP-Za-km-z]+$ ]] || die 'Transaction signature is not Base58.'
	curl --fail --silent --show-error \
		"$QORTAL_API_URL/transactions/signature/$(url_encode "$signature")" \
		| jq '{type, service, name, identifier, method, size, fee, blockHeight, signature}'
}

main() {
	(( $# >= 1 )) || { usage >&2; exit 2; }
	local command=$1
	shift

	case $command in
		help|--help|-h)
			usage
			;;
		prepare)
			(( $# >= 2 && $# <= 4 )) || { usage >&2; exit 2; }
			prepare_transaction host-specific "$1" "$2" "${3:-$DEFAULT_IDENTIFIER}" \
				"${4:-qdn-unified-wallet-to-sign.base58}"
			;;
		validate)
			(( $# == 2 )) || { usage >&2; exit 2; }
			case $1 in
				test)
					validate_bundle host-specific "$2" >/dev/null
					;;
				release)
					validate_bundle cross-platform "$2" >/dev/null
					;;
				*)
					die 'validate mode must be test or release.'
					;;
			esac
			;;
		prepare-release)
			(( $# >= 3 && $# <= 4 )) || { usage >&2; exit 2; }
			prepare_transaction cross-platform "$1" "$2" "$3" \
				"${4:-qdn-unified-wallet-release-to-sign.base58}"
			;;
		sign)
			(( $# >= 1 && $# <= 2 )) || { usage >&2; exit 2; }
			sign_transaction "$1" "${2:-qdn-unified-wallet-signed.base58}"
			;;
		submit)
			(( $# >= 1 && $# <= 2 )) || { usage >&2; exit 2; }
			submit_transaction "$1" "${2:-qdn-unified-wallet-signature.txt}"
			;;
		verify)
			(( $# == 1 )) || { usage >&2; exit 2; }
			verify_transaction "$1"
			;;
		publish)
			(( $# >= 2 && $# <= 3 )) || { usage >&2; exit 2; }
			local identifier=${3:-$DEFAULT_IDENTIFIER}
			local file_stem
			file_stem=$(convenience_file_stem test "$2" "$identifier")
			local unsigned_file="$file_stem-to-sign.base58"
			local signed_file="$file_stem-signed.base58"
			local signature_file="$file_stem-signature.txt"
			prepare_transaction host-specific "$1" "$2" "$identifier" "$unsigned_file"
			sign_transaction "$unsigned_file" "$signed_file"
			read -r -p 'Broadcast this signed test-only QDN publication? Type PUBLISH to continue: ' confirmation
			[[ $confirmation == PUBLISH ]] || die 'Broadcast cancelled. The signed transaction was not submitted.'
			submit_transaction "$signed_file" "$signature_file"
			;;
		release)
			(( $# == 3 )) || { usage >&2; exit 2; }
			local file_stem
			file_stem=$(convenience_file_stem release "$2" "$3")
			local unsigned_file="$file_stem-to-sign.base58"
			local signed_file="$file_stem-signed.base58"
			local signature_file="$file_stem-signature.txt"
			prepare_transaction cross-platform "$1" "$2" "$3" "$unsigned_file"
			sign_transaction "$unsigned_file" "$signed_file"
			read -r -p 'Broadcast this signed all-architecture QDN RELEASE? Type PUBLISH RELEASE to continue: ' confirmation
			[[ $confirmation == 'PUBLISH RELEASE' ]] || die 'Release broadcast cancelled. The signed transaction was not submitted.'
			submit_transaction "$signed_file" "$signature_file"
			;;
		*)
			die "Unknown command: $command"
			;;
	esac
}

main "$@"
