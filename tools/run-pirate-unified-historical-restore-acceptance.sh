#!/bin/sh
set -eu

# Runs the same deterministic, unfunded historical restore in two separate
# JVM/native processes with separate HOME, XDG, temp, and wallet-storage roots.

usage() {
	printf '%s\n' "Usage: $0 <absolute-pinned-artifact.zip> <absolute-staged-bundle-directory> <new-receipt.md>" >&2
}

require_absolute() {
	label=$1
	value=$2
	case $value in
		/*) ;;
		*) printf '%s path must be absolute: %s\n' "$label" "$value" >&2; exit 2 ;;
	esac
}

require_command() {
	command -v "$1" >/dev/null 2>&1 || {
		printf 'Required command not found: %s\n' "$1" >&2
		exit 1
	}
}

run_restore() {
	label=$1
	run_root=$2
	log=$3
	report_suffix=$4
	mkdir -m 700 "$run_root"
	mkdir -m 700 "$run_root/home" "$run_root/data" "$run_root/cache" \
		"$run_root/config" "$run_root/tmp" "$run_root/surefire"
	storage=$run_root/wallet-storage
	[ ! -e "$storage" ] || {
		printf '%s storage was not fresh\n' "$label" >&2
		return 1
	}

	maven_status=0
	if env -i PATH="$PATH" HOME="$run_root/home" \
		XDG_DATA_HOME="$run_root/data" \
		XDG_CACHE_HOME="$run_root/cache" \
		XDG_CONFIG_HOME="$run_root/config" \
		PIRATE_BLOCK_CACHE_DIR="$run_root/block-cache" \
		PIRATE_DEBUG_LOG_PATH="$run_root/pirate-debug.log" \
		mvn -o -Dmaven.repo.local="$maven_repo" \
			-Duser.home="$run_root/home" \
			-Djava.io.tmpdir="$run_root/tmp" \
			-Dsurefire.reportNameSuffix="$report_suffix" \
			-DskipJUnitTests=false \
			-Dtest=PirateUnifiedHistoricalRestoreTests \
			-Dqortal.runPirateUnifiedHistoricalRestoreTests=true \
			-Dqortal.pirateUnifiedBundlePath="$bundle" \
			-Dqortal.pirateUnifiedHistoricalRestoreStoragePath="$storage" \
			test > "$log" 2>&1; then
		:
	else
		maven_status=$?
	fi

	# Surefire does not expose reportsDirectory as a user property. Quarantine
	# only this process's uniquely suffixed reports, then remove the originals.
	for report in "$repo_root"/target/surefire-reports/TEST-*-"$report_suffix".xml \
			"$repo_root"/target/surefire-reports/*-"$report_suffix".txt; do
		if [ -f "$report" ]; then
			cp "$report" "$run_root/surefire/"
			rm -f "$report"
		fi
	done

	if [ "$maven_status" -ne 0 ]; then
		printf '%s\n' "$label historical-restore JVM failed; raw evidence will be deleted" >&2
		grep -E 'Tests run: [0-9]+, Failures:|Historical note was not recovered into total balance' \
			"$log" | tail -n 4 >&2 || true
		return 1
	fi

	grep -F 'Tests run: 1, Failures: 0, Errors: 0, Skipped: 0' "$log" >/dev/null
	grep -F 'BUILD SUCCESS' "$log" >/dev/null
	report="$run_root/surefire/TEST-org.qortal.controller.PirateUnifiedHistoricalRestoreTests-$report_suffix.xml"
	[ -f "$report" ] || {
		printf '%s did not produce its uniquely suffixed Surefire XML report\n' "$label" >&2
		return 1
	}
	testsuite=$(grep -m 1 '<testsuite ' "$report")
	printf '%s\n' "$testsuite" | grep -F 'tests="1"' >/dev/null
	printf '%s\n' "$testsuite" | grep -F 'errors="0"' >/dev/null
	printf '%s\n' "$testsuite" | grep -F 'skipped="0"' >/dev/null
	printf '%s\n' "$testsuite" | grep -F 'failures="0"' >/dev/null
	[ -d "$storage" ] || {
		printf '%s did not create its fresh wallet storage\n' "$label" >&2
		return 1
	}
}

[ "$#" -eq 3 ] || {
	usage
	exit 2
}

artifact=$1
bundle=$2
receipt=$3
require_absolute artifact "$artifact"
require_absolute bundle "$bundle"
require_absolute receipt "$receipt"
require_command find
require_command git
require_command grep
require_command mvn
require_command sha256sum
require_command uname
require_command unzip

host_os=$(uname -s)
host_arch=$(uname -m)
if [ "$host_os" != Linux ] || [ "$host_arch" != x86_64 ]; then
	printf 'Historical-restore acceptance currently supports only Linux x86_64, not %s %s\n' \
		"$host_os" "$host_arch" >&2
	exit 1
fi

if [ ! -f "$artifact" ] || [ -L "$artifact" ]; then
	printf '%s\n' 'Pinned artifact must be a regular non-symlink file' >&2
	exit 1
fi
if [ ! -d "$bundle" ] || [ -L "$bundle" ]; then
	printf '%s\n' 'Staged bundle must be a non-symlink directory' >&2
	exit 1
fi
[ ! -e "$receipt" ] || {
	printf 'Receipt already exists: %s\n' "$receipt" >&2
	exit 1
}

script_dir=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
repo_root=$(CDPATH='' cd -- "$script_dir/.." && pwd)
cd "$repo_root"
[ -z "$(git status --porcelain)" ] || {
	printf '%s\n' 'Historical-restore acceptance requires a clean Core tree' >&2
	exit 1
}

initial_home=${HOME:?HOME is required to resolve the existing Maven repository}
maven_repo=${MAVEN_REPO_LOCAL:-$initial_home/.m2/repository}
[ -d "$maven_repo" ] || {
	printf 'Maven repository does not exist: %s\n' "$maven_repo" >&2
	exit 1
}

umask 077
runtime=$(mktemp -d "${TMPDIR:-/tmp}/qortal-pirate-historical-restore.XXXXXX")
run_id=${runtime##*.}
original_report_suffix=pirate-historical-restore-original-$run_id
restored_report_suffix=pirate-historical-restore-restored-$run_id
lock=$repo_root/target/.pirate-historical-restore-acceptance.lock
lock_owned=false
cleanup() {
	rm -f "$repo_root"/target/surefire-reports/TEST-*-"$original_report_suffix".xml \
		"$repo_root"/target/surefire-reports/*-"$original_report_suffix".txt \
		"$repo_root"/target/surefire-reports/TEST-*-"$restored_report_suffix".xml \
		"$repo_root"/target/surefire-reports/*-"$restored_report_suffix".txt
	case $runtime in
		"${TMPDIR:-/tmp}"/qortal-pirate-historical-restore.*)
			[ ! -e "$runtime" ] || find "$runtime" -depth -delete
			;;
	esac
	if [ "$lock_owned" = true ]; then
		rmdir "$lock" 2>/dev/null || true
	fi
}
trap cleanup 0 HUP INT TERM
mkdir -p "$repo_root/target"
if mkdir "$lock" 2>/dev/null; then
	lock_owned=true
else
	printf '%s\n' 'Another historical-restore acceptance appears to be using this Core clone' >&2
	exit 1
fi

library=$bundle/librust-linux-x86_64.so
if [ ! -f "$library" ] || [ -L "$library" ]; then
	printf '%s\n' 'Linux x86_64 staged library is missing or is a symlink' >&2
	exit 1
fi
archive_library_hash_line=$(unzip -p "$artifact" librust-linux-x86_64.so | sha256sum)
archive_library_sha256=${archive_library_hash_line%% *}
library_hash_line=$(sha256sum "$library")
library_sha256=${library_hash_line%% *}
[ "$archive_library_sha256" = "$library_sha256" ] || {
	printf '%s\n' 'Staged Linux x86_64 library does not match the supplied pinned archive' >&2
	exit 1
}

run_restore original-computer "$runtime/original" "$runtime/original.log" "$original_report_suffix"
run_restore fresh-second-computer "$runtime/restored" "$runtime/restored.log" "$restored_report_suffix"

secret_pattern='seedPhrase|private_key|privateKey|spending_key|spendingKey|viewing_key|viewingKey|mnemonic|ARRRWalletEncryption|"seed"[[:space:]]*:|"entropy"[[:space:]]*:'
address_pattern='(zs1|ztestsapling1|zregtestsapling1)[[:alnum:]]{60,}'
if grep -E -i "$secret_pattern" "$runtime/original.log" "$runtime/restored.log" >/dev/null 2>&1 \
		|| grep -E "$address_pattern" "$runtime/original.log" "$runtime/restored.log" >/dev/null 2>&1 \
		|| grep -R -E -i "$secret_pattern|$address_pattern" "$runtime/original/surefire" \
			"$runtime/restored/surefire" >/dev/null 2>&1; then
	printf '%s\n' 'Historical-restore raw evidence contained secret-shaped wallet material' >&2
	exit 1
fi

# Delete every wallet-capable input before publishing a receipt that claims
# deletion. The remaining runtime directory holds only the receipt staging file.
[ ! -e "$runtime/original" ] || find "$runtime/original" -depth -delete
[ ! -e "$runtime/restored" ] || find "$runtime/restored" -depth -delete
rm -f -- "$runtime/original.log" "$runtime/restored.log"
for raw_evidence in "$runtime/original" "$runtime/restored" \
		"$runtime/original.log" "$runtime/restored.log"; do
	[ ! -e "$raw_evidence" ] || {
		printf 'Historical-restore raw evidence was not deleted: %s\n' "$raw_evidence" >&2
		exit 1
	}
done

commit=$(git rev-parse HEAD)
artifact_hash_line=$(sha256sum "$artifact")
artifact_sha256=${artifact_hash_line%% *}
fixture_hash_line=$(sha256sum src/test/java/org/qortal/controller/PirateUnifiedLoopbackLightwalletd.java)
fixture_sha256=${fixture_hash_line%% *}
timestamp=$(date -u '+%Y-%m-%dT%H:%M:%SZ')

mkdir -p "$(dirname -- "$receipt")"
receipt_tmp=$runtime/receipt.md
{
	printf '%s\n\n' '# Pirate Unified fresh-install historical restore acceptance'
	printf '%s\n\n' 'Result: **PASS**'
	printf -- "- Timestamp: \`%s\`\n" "$timestamp"
	printf -- "- Core commit: \`%s\` (clean tree)\n" "$commit"
	printf -- "- Artifact SHA-256: \`%s\`\n" "$artifact_sha256"
	printf -- "- Linux x86_64 JNI SHA-256: \`%s\`\n" "$library_sha256"
	printf -- "- Historical fixture source SHA-256: \`%s\`\n" "$fixture_sha256"
	printf '%s\n' "- Independent native/JVM processes: \`2\`"
	printf '%s\n' "- Executed platform: \`Linux x86_64\`"
	printf '%s\n' "- Process-local HOME/XDG/temp/storage roots: \`2\`, both fresh and distinct"
	printf -- '- Deterministic account entropy: same in both processes; not retained in evidence\n'
	printf '%s\n' "- Explicit recovery birthday: \`152855\`"
	printf '%s\n' "- Historical note height: \`152856\`"
	printf '%s\n' "- Deterministic tip: \`152858\`"
	printf '%s\n' "- Recovered confirmed transactions per process: \`1\`"
	printf '%s\n' "- Recovered total balance per process: \`123456789\` arrrtoshis"
	printf '%s\n' "- Recovered verified balance per process: \`123456789\` arrrtoshis"
	printf '%s\n' "- Transaction/address/value metadata assertions: \`PASS\`"
	printf '%s\n' "- Birthday-to-tip compact range assertion: \`PASS\`"
	printf '%s\n' "- Forbidden transaction RPCs: \`0\`"
	printf '%s\n' "- Unexpected RPCs: \`0\`"
	printf '%s\n\n' "- Raw-log and Surefire secret scan: \`PASS\`; raw evidence deleted"
	printf '%s\n' 'This proves that two clean local installations using the same account entropy and an explicit recovery birthday independently recover the same earlier synthetic Sapling transaction and reported balance from the pinned native library. It does not test the Core tip-by-default initialization policy and does not prove production lightwalletd interoperability, canonical mainnet history, a funded send, witness usability against a real chain, QDN publication, deployment, default enablement, or Home behavior.'
} > "$receipt_tmp"
mv "$receipt_tmp" "$receipt"
printf 'PASS receipt=%s\n' "$receipt"
