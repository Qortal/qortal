#!/usr/bin/env bash
set -euo pipefail

# Running this command prepares, signs, and broadcasts a new ARRR JNI bundle.
# Artifact validation and transaction handling stay in the existing helpers.

usage() {
	cat <<'EOF'
Usage: tools/prep-and-publish-arrr-wallet.sh [combined-build.zip|build-directory]

Prompts for the combined Stashi Qortal JNI ZIP (or a directory containing
exactly one ZIP), registered QDN name, and new identifier. Stages all five
native libraries, prepares ARBITRARY_DATA, then signs and submits it.
The existing signing helper prompts privately for the QDN name owner's key.

QORTAL_API_URL       Core API URL (default: http://127.0.0.1:12391)
QORTAL_API_KEY       Optional API key override
QORTAL_API_KEY_FILE  API key file (default: ~/qortal/apikey.txt)
QDN_FEE             Fee in atomic units (default: 1000000 = 0.01 QORT)
QDN_OUTPUT_DIR      Parent for saved bundles and transaction files
                    (default: ~/.local/state/qortal/arrr-publications,
                     respecting XDG_STATE_HOME)

Only the combined archive pinned in stage-pirate-unified-qdn-bundle.sh is
accepted. JNI 0.3.9 is based on upstream Stashi v1.2.6, with Qortal fixes.
EOF
}

die() {
	printf 'Error: %s\n' "$*" >&2
	exit 1
}

if (( $# == 1 )) && [[ $1 == --help || $1 == -h ]]; then
	usage
	exit 0
fi
(( $# <= 1 )) || { usage >&2; exit 2; }

script_directory=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
stager="$script_directory/stage-pirate-unified-qdn-bundle.sh"
publisher="$script_directory/publish-pirate-unified-qdn.sh"
for command in curl jq unzip; do
	command -v "$command" >/dev/null 2>&1 || die "Required command not found: $command"
done

artifact=${1:-}
if [[ -z $artifact ]]; then
	read -r -p 'Stashi combined JNI build ZIP (or directory containing it): ' artifact
fi
# A literal ~/ pasted at a prompt does not undergo shell tilde expansion.
case $artifact in
	'~') artifact=$HOME ;;
	'~/'*) artifact="$HOME/${artifact:2}" ;;
esac
if [[ -d $artifact ]]; then
	shopt -s nullglob
	archives=("$artifact"/*.zip)
	shopt -u nullglob
	(( ${#archives[@]} == 1 )) || die 'Build directory must contain exactly one combined JNI ZIP; select the ZIP directly otherwise.'
	artifact=${archives[0]}
fi
[[ -f $artifact ]] || die "Combined build ZIP does not exist: $artifact"
artifact=$(cd -- "$(dirname -- "$artifact")" && printf '%s/%s' "$PWD" "$(basename -- "$artifact")")

output_parent=${QDN_OUTPUT_DIR:-${XDG_STATE_HOME:-$HOME/.local/state}/qortal/arrr-publications}
mkdir -p -- "$output_parent"
umask 077
publication_directory=$(mktemp -d "$output_parent/qortal-jni-0.3.9-XXXXXX")
publication_directory=$(cd -- "$publication_directory" && pwd -P)
printf 'Publication files: %s\n' "$publication_directory"
# Keep staging and transaction files even if signing or submission fails.
"$stager" "$artifact" "$publication_directory/bundle" all
"$publisher" validate release "$publication_directory/bundle"

read -r -p 'Registered QDN name [PirateChainWallet]: ' publication_name
publication_name=${publication_name:-PirateChainWallet}
default_identifier="LiteWalletJNI-$(date -u +%Y-%m)-qortal-v0.3.9-test-$(date -u +%Y%m%dT%H%M%SZ)-$$"
read -r -p "New QDN identifier [$default_identifier]: " identifier
identifier=${identifier:-$default_identifier}

if [[ -z ${QORTAL_API_KEY:-} ]]; then
	api_key_file=${QORTAL_API_KEY_FILE:-$HOME/qortal/apikey.txt}
	if [[ -r $api_key_file ]]; then
		QORTAL_API_KEY=$(tr -d '\r\n' < "$api_key_file")
		printf 'Read Core API key from %s\n' "$api_key_file"
	fi
	if [[ -z ${QORTAL_API_KEY:-} ]]; then
		read -r -s -p 'Local Core API key: ' QORTAL_API_KEY
		printf '\n'
	fi
fi
[[ -n $QORTAL_API_KEY ]] || die 'A Core API key is required.'
export QORTAL_API_KEY
export QORTAL_API_URL=${QORTAL_API_URL:-http://127.0.0.1:12391}
export QDN_FEE=${QDN_FEE:-1000000}

printf 'Publishing all five native libraries as ARBITRARY_DATA: %s / %s\n' "$publication_name" "$identifier"
"$publisher" prepare-release "$publication_directory/bundle" "$publication_name" "$identifier" \
	"$publication_directory/unsigned.base58"
"$publisher" sign "$publication_directory/unsigned.base58" "$publication_directory/signed.base58"
"$publisher" submit "$publication_directory/signed.base58" "$publication_directory/signature.txt"

signature=$(tr -d '\r\n' < "$publication_directory/signature.txt")
printf '\nAfter confirmation, verify with:\n  %q verify %q\n' "$publisher" "$signature"
printf 'Then select pirateChainWalletQdnSignature: %s and restart Core.\n' "$signature"
