# Publishing and testing a Pirate Unified Wallet QDN bundle

Qortal selects the Pirate Chain LiteWallet JNI bundle with the
`pirateChainWalletQdnSignature` setting. The value is an exact QDN transaction
signature, rather than a mutable resource name, so a node always retrieves the
reviewed publication that was selected for it.

There are two intentionally separate publication paths:

- A small **host-specific test bundle**, with one native library, for isolated
  validation on a single operating system and architecture.
- One **cross-platform release bundle**, with all five supported native
  libraries, for the production QDN signature.

The scripts reject a host-specific bundle in the release path and reject the
cross-platform bundle in the test path.

## January candidate publication (legacy only)

The January 2026 branch work already published an isolated candidate bundle:

```text
name:       PirateChainWallet
identifier: LiteWalletJNI-2
signature:  4DtYWqBSsPaeY8u42zpWQuxogN1N9USbYFuidgaXfxNv5gneNtkVXSd7Lani7dGq7WpTZZzPfBcBhG349FXbQiUn
```

It is a confirmed `ARBITRARY_DATA` PUT transaction, but it contains the legacy
LiteWallet JNI implementation. It can test the immutable-QDN selector only.
Do **not** use it with `pirateChainWalletUnified: true`, and do not treat a
successful test of this bundle as a test of the current Pirate client.

## Current Unified Wallet candidate

The current integration target is the Qortal JNI artifact in Pirate Unified
Wallet release `v1.1.9`. Pin the content hash as well as the release URL:

```text
release:  v1.1.9
artifact: pirate-unified-wallet-qortal-jni-artifacts-v1.1.9.zip
sha256:   059781c5a2cdeb8c5d60f1130c4bf3a217822d39438e560bc11633993df0e1e9
```

The v1.1.9 release is confirmed on QDN. Its immutable transaction matches the
intended name and identifier below.

### Historical v1.1.7 Linux x86_64 test publication

The first host-specific test publication is confirmed and indexed on QDN:

```text
name:       testARRR
identifier: LiteWalletJNI-2026-08-unified
signature:  4Cchkr4DFsz3SDGTeL1rQUeKWevj93k1bJ3XnE7oaNvDonxPKNneekgCqFqZzbgaUYURp7yPpygcM1H4CGSHhCnc
block:      2692360
fee:        0.01000000 QORT
bundle:     Linux x86_64 only (librust-linux-x86_64.so)
```

It is an `ARBITRARY_DATA` `PUT` transaction. Its QDN search record names this
exact signature as the latest resource transaction for `testARRR` and the
identifier above. Use it only for Linux x86_64 Unified Wallet smoke tests; a
different staged publication is required for every other host platform.

### Confirmed v1.1.9 production Unified Wallet publication

The reviewed cross-platform release is confirmed and is the Core default:

```text
name:       PirateChainWallet
identifier: LiteWalletJNI-2026-08-unified-v1.1.9
signature:  5drafi8G5WTjGVGh8B66runy51DDxupEEFaT67rAXgosDf6u22NWGntHZZ3jxv7Sq7AXtker7dxeXUkgxQ5yPcG1
block:      2707698
fee:        0.01000000 QORT
bundle:     Cross-platform (all five supported native libraries)
```

It is an `ARBITRARY_DATA` `PUT` publication. New Core configurations select
this exact transaction and enable `pirateChainWalletUnified` by default.
Operators overriding the signature to load a legacy bundle must set
`pirateChainWalletUnified: false` explicitly.

Download, verify, and extract it into a new local staging directory:

```bash
curl -fL \
  https://github.com/PirateNetwork/Pirate-Unified-Light-Wallet/releases/download/v1.1.9/pirate-unified-wallet-qortal-jni-artifacts-v1.1.9.zip \
  -o pirate-unified-wallet-qortal-jni-artifacts-v1.1.9.zip
echo '059781c5a2cdeb8c5d60f1130c4bf3a217822d39438e560bc11633993df0e1e9  pirate-unified-wallet-qortal-jni-artifacts-v1.1.9.zip' | sha256sum --check
unzip pirate-unified-wallet-qortal-jni-artifacts-v1.1.9.zip -d LiteWalletJNI-2026-08-unified-v1.1.9
```

The extracted archive contains the five desktop native libraries, the upstream
Qortal handoff, and its license. The stage script selects either one matching
library for a test or all five for a final release. Do not add the old
`coinparams.json`, `saplingoutput_base64`, or `saplingspend_base64` files: the
Unified Wallet ignores them and Qortal no longer requires them in unified mode.

For example, an x86_64 Linux test publisher can stage the small, host-specific
publication reproducibly with:

```bash
tools/stage-pirate-unified-qdn-bundle.sh \
  pirate-unified-wallet-qortal-jni-artifacts-v1.1.9.zip \
  LiteWalletJNI-2026-08-unified-v1.1.9-publish \
  linux-x86_64
```

The staging tool refuses an unexpected archive checksum or an existing output
directory, and writes a manifest into the candidate publication.

## Test mode: publish a host-specific candidate

Publish the staged directory to QDN as `ARBITRARY_DATA`, using a test name and a
distinct identifier, for example `LiteWalletJNI-2026-08-unified-v1.1.9-test`. It must retain
the exact native filename for the test host:

- Linux x86_64: `librust-linux-x86_64.so`
- Linux aarch64: `librust-linux-aarch64.so`
- macOS x86_64: `librust-macos-x86_64.dylib`
- macOS Apple Silicon: `librust-macos-aarch64.dylib`
- Windows x86_64: `librust-windows-x86_64.dll`
- `LICENSE-qortal-jni.txt`
- `qortal-handoff.md`

Build the unsigned QDN transaction with a normal transaction fee:

```text
POST /arbitrary/ARBITRARY_DATA/<test-name>/<test-identifier>?fee=<atomic-fee>
body: absolute path to the candidate directory
```

Then sign it with the owner of `<test-name>` and submit the signed transaction.
Record the resulting transaction signature after it has been confirmed. Do not
point a test at a name/identifier alone: this setting is intentionally pinned to
that immutable signature.

The QDN name owner is the only input required from the operator for this step.
The publication is external state, so it is deliberately not submitted by the
core code or by the test build.

### Build, sign, and submit the publication

`tools/publish-pirate-unified-qdn.sh` validates the staged directory before
calling Qortal Core and deliberately keeps the publication in two phases. Set
the local API details and prepare the transaction with the registered **test**
name (the API key is prompted for if it is not exported):

```bash
tools/publish-pirate-unified-qdn.sh validate \
  test \
  "$PWD/LiteWalletJNI-2026-08-unified-v1.1.9-publish"

export QORTAL_API_URL='http://127.0.0.1:12391'
export QORTAL_API_KEY='<local-node-api-key>'
export QDN_FEE='1000000' # 0.01 QORT, in atomic units

tools/publish-pirate-unified-qdn.sh prepare \
  "$PWD/LiteWalletJNI-2026-08-unified-v1.1.9-publish" \
  '<registered-test-name>' \
  'LiteWalletJNI-2026-08-unified-v1.1.9-test'
```

This creates `qdn-unified-wallet-to-sign.base58`; it has an explicit fee but
has not signed or broadcast anything. No memPoW/noncing step is involved. Sign
it on the trusted local owner node, or take that file to an offline signer and
bring back only the signed transaction:

```bash
tools/publish-pirate-unified-qdn.sh sign qdn-unified-wallet-to-sign.base58
```

The script prompts rather than accepting the private key as an argument, so it
does not enter shell history or a `curl` command line. It holds the key only
long enough to pass the signing request to the local Qortal Core endpoint via
standard input. Then, as a separate explicit action, broadcast the signed
transaction:

```bash
tools/publish-pirate-unified-qdn.sh submit qdn-unified-wallet-signed.base58
```

It writes `qdn-unified-wallet-signature.txt` and prints the immutable Qortal
transaction signature. After confirmation, verify the immutable publication:

```bash
tools/publish-pirate-unified-qdn.sh verify "$(tr -d '\r\n' < qdn-unified-wallet-signature.txt)"
```

For a trusted local signer, `publish` runs those three steps and still requires
typing `PUBLISH` immediately before the broadcast:

```bash
tools/publish-pirate-unified-qdn.sh publish \
  "$PWD/LiteWalletJNI-2026-08-unified-v1.1.9-publish" \
  '<registered-test-name>' \
  'LiteWalletJNI-2026-08-unified-v1.1.9-test'
```

The convenience command uses a timestamped output-file prefix, so a retry
never overwrites a prior unsigned transaction, signed transaction, or recorded
signature. The explicit `prepare`, `sign`, and `submit` commands retain their
predictable filenames and accept optional output-file arguments for an offline
or manually managed flow.

## Release mode: publish one cross-platform bundle

Only use this after the pinned upstream archive and the Qortal integration have
been reviewed, and after the relevant architecture tests are complete. The
release bundle contains all five native libraries and therefore one immutable
QDN signature serves every supported desktop platform. It is not the source
ZIP, and it contains no legacy parameter files.

Stage it into a new directory. The staging script verifies the upstream archive
checksum, requires all five native filenames, and refuses to overwrite an
existing directory:

```bash
tools/stage-pirate-unified-qdn-bundle.sh \
  pirate-unified-wallet-qortal-jni-artifacts-v1.1.9.zip \
  LiteWalletJNI-2026-08-unified-v1.1.9-release \
  all
```

The resulting directory contains exactly these eight files:

- `librust-linux-x86_64.so`
- `librust-linux-aarch64.so`
- `librust-macos-x86_64.dylib`
- `librust-macos-aarch64.dylib`
- `librust-windows-x86_64.dll`
- `MANIFEST.txt`
- `LICENSE-qortal-jni.txt`
- `qortal-handoff.md`

Run the offline preflight before involving a Qortal API key or private key:

```bash
tools/publish-pirate-unified-qdn.sh validate \
  release \
  "$PWD/LiteWalletJNI-2026-08-unified-v1.1.9-release"
```

Choose the registered production QDN name controlled by the release signer and
a **new, versioned identifier**. Do not reuse the identifier of a prior
production publication. Prepare the fee-paying transaction first; this does
not sign or broadcast it:

```bash
export QORTAL_API_URL='http://127.0.0.1:12391'
export QDN_FEE='1000000' # 0.01 QORT in atomic units

tools/publish-pirate-unified-qdn.sh prepare-release \
  "$PWD/LiteWalletJNI-2026-08-unified-v1.1.9-release" \
  '<registered-production-name>' \
  'LiteWalletJNI-2026-08-unified-v1.1.9'
```

This writes `qdn-unified-wallet-release-to-sign.base58`. Sign it on the trusted
owner node (or an offline signer), then submit it separately:

```bash
tools/publish-pirate-unified-qdn.sh sign \
  qdn-unified-wallet-release-to-sign.base58 \
  qdn-unified-wallet-release-signed.base58

tools/publish-pirate-unified-qdn.sh submit \
  qdn-unified-wallet-release-signed.base58 \
  qdn-unified-wallet-release-signature.txt

tools/publish-pirate-unified-qdn.sh verify \
  "$(tr -d '\r\n' < qdn-unified-wallet-release-signature.txt)"
```

For a trusted local signer, the guarded convenience command runs those steps
and requires the exact confirmation `PUBLISH RELEASE` before broadcasting:

```bash
tools/publish-pirate-unified-qdn.sh release \
  "$PWD/LiteWalletJNI-2026-08-unified-v1.1.9-release" \
  '<registered-production-name>' \
  'LiteWalletJNI-2026-08-unified-v1.1.9'
```

It writes a fresh timestamped set of unsigned, signed, and signature files on
every run. This preserves prior attempts rather than refusing to overwrite
them. Use the explicit three-step commands above when the signer is offline or
when you need fixed output filenames.

After the transaction confirms, record its block height and independently
verify that it is an `ARBITRARY_DATA` `PUT` with the intended name, identifier,
fee, and size. Update the production record above with that height, then run
the focused controller tests before distributing the JAR or updating the pull
request.

## Start an isolated test node

Use a separate Qortal working directory and a separate `settings.json`; if it
runs alongside a production node, use different API and P2P ports. At minimum,
give it separate repository, data, temporary-data, and wallet paths. Add the
confirmed test transaction signature:

```json
{
  "repositoryPath": "db-pirate-client-test",
  "dataPath": "data-pirate-client-test",
  "tempDataPath": "data-pirate-client-test/_temp",
  "walletsPath": "wallets-pirate-client-test",
  "apiPort": 12491,
  "listenPort": 12492,
  "listenDataPort": 12494,
  "uPnPEnabled": false,
  "pirateChainWalletQdnSignature": "4Cchkr4DFsz3SDGTeL1rQUeKWevj93k1bJ3XnE7oaNvDonxPKNneekgCqFqZzbgaUYURp7yPpygcM1H4CGSHhCnc",
  "pirateChainWalletUnified": true,
  "pirateChainWalletDebugLogging": true
}
```

The transaction signature selects the immutable JNI bundle; it does not select
an ARRR address. The latter is deterministically derived from the Qortal
account entropy. On startup, Core logs the exact selected QDN transaction.

For a clean, new Unified Wallet namespace, Core starts at the current
lightwallet height rather than the historical `arrrDefaultBirthday`. Existing
Unified storage and a legacy `wallet-*.dat` migration continue from the
configured conservative birthday. If recovering a Qortal-derived ARRR address
that may have received funds before its first Unified Wallet startup, set an
explicit historical height in the isolated configuration, for example:

```json
"arrrNewWalletBirthday": 2000000
```

`pirateChainWalletDebugLogging` is test-only and defaults to `false`. When
enabled it asks the Unified JNI to write a redacted JSONL trace. On Linux, the
candidate client writes it by default to:

```text
~/.local/share/piratewallet/logs/debug.log
```

After a stalled scan, preserve the file and inspect the final lines rather
than deleting the wallet namespace; it contains the native sync-engine error
that the legacy `syncstatus` response cannot represent.

Build the candidate JAR from this branch, then copy it into the isolated
working directory, save the preceding JSON as its `settings.json`, and start
it from there:

```bash
mvn -DskipTests package
cp target/qortal-6.1.9.jar /path/to/qortal-pirate-client-test/
cd /path/to/qortal-pirate-client-test
java -jar qortal-6.1.9.jar
```

The production signature continues to use its original cache directory. Any
non-production signature uses a full-signature cache directory, so it cannot
replace the production bundle. Unified wallet state is stored separately at
`wallets-pirate-client-test/PirateChain/unified/<entropy-hash>/`; it does not
overwrite an old `wallet-<entropy-hash>.dat` cache. Once a Unified Wallet has
actually synchronized, Qortal moves that old snapshot (and its checksum) to a
unique `.legacy-before-unified` archive name; it never deletes it. A JVM cannot
unload a native library, therefore never test two different LiteWallet JNI
bundles in the same Qortal process.

## Validate

Start the ARRR wallet controller on the isolated node. `start` and `syncstatus`
require its local API key in the `X-API-KEY` header. Use a new, unfunded entropy
value for this first test; do not migrate or fund a production ARRR wallet yet.

```bash
# Run this once on a fresh test node; retain the returned key privately.
export QORTAL_API_KEY="$(curl -sS -X POST http://127.0.0.1:12491/admin/apikey/generate)"
export TEST_ENTROPY='5oSXF53qENtdUyKhqSxYzP57m6RhVFP9BJKRr9E5kRGV'

curl -sS -X POST -H "X-API-KEY: $QORTAL_API_KEY" \
  http://127.0.0.1:12491/crosschain/arrr/start
curl -sS http://127.0.0.1:12491/crosschain/arrr/status
curl -sS -X POST -H "X-API-KEY: $QORTAL_API_KEY" \
  --data "$TEST_ENTROPY" \
  'http://127.0.0.1:12491/crosschain/arrr/syncstatus?json=true'
```

Pass criteria for the first test are: the load status reports the library as
ready, `syncstatus` reports the new `in_progress`/`scanned_height` schema, and
the test wallet remains at zero balance. P2SH funding, redemption, and legacy
wallet migration require their own regression test before this bundle can
replace production.

The shown entropy is public and must never receive funds; it only triggers an
unfunded smoke-test wallet. First confirm that QDN download, native-library
load, wallet initialization, and lightwallet synchronization succeed. Only
after those checks should the test use a separately generated, funded test
wallet or attempt a send. The controller reports a missing native file or a JNI
load failure as a retryable status instead of claiming that the library is ready.
