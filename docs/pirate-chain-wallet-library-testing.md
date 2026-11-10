# Publishing and testing a Pirate Unified Wallet QDN bundle

## Trade refund test build

The refund fixes on `pirate-changes` keep ARRR trades in `ALICE_REFUNDING_A` after
broadcast and require three confirmations of the foreign-chain spend before
marking `ALICE_REFUNDED`. The API exposes `foreignRefundConfirmed` so the client
does not trust an unaudited legacy terminal state. Manual recovery uses the exact buyer record's wallet,
allows a losing buyer to recover its independent funding after another buyer
redeems the QORT offer, and continues other attempts when one wallet fails.

A versioned 64-byte checkpoint uses the existing `lastTransactionSignature`
field in ARRR refund states. It is committed before native broadcasting and
survives database and trade-backup round trips. Keep this field intact when restoring
trade backups. Refunding entries cannot be deleted through tradebot cleanup.
Known native validation rejections clear the checkpoint and log the cause at ERROR;
timeouts, lost responses and uncertain broadcast failures keep the quarantine.
Bulk refund failures log the AT, attempt address and full exception at ERROR while
preserving the endpoint's boolean response.

Known broadcasts are checked for expiry before replacement. An unknown response
is quarantined for at least 30 ARRR blocks and ten minutes before a fresh status
and UTXO check permits another attempt. A missing or shallow spend remains pending.
Older broadcast-only `ALICE_REFUNDED` records are audited gradually, at most one
per minute; still-funded entries return to recovery after the same quarantine.
Unavailable legacy checks retry later. This avoids immediate rebroadcast on upgrade.

The reader now handles CompactSize transparent fields and Overwinter/Sapling/
V5/current Ironwood V6 prefixes. The public `/crosschain/arrr/tradefees` endpoint
returns atomic `fundingFee` and `refundFee` values without initializing a wallet.
Use the matching Q-Trade `0.0.3-test.1` test build to consume that quote and the
corrected pending/refund states.

Funding now snapshots the actual redeem/refund reserve in `TradeBotData.fundingFee`
and captures `fundingOutputIndex`. HSQLDB automatically upgrades schema 53 to 54
with a nullable fee and output index default 0; no re-index is required. Database
and JSON backups preserve both fields. The funding transaction fee is separate
from the reserve inside the HTLC. Later changes to `feeRequired` do not change the
stored reserve. Sellers and legacy buyers recover the reserve and index from the
actual unspent funding output; where the original fee remains unavailable Core
falls back to 10000 with a warning. Preserve these fields during backup restore.

Output zero works with the existing JNI. Nonzero outputs require the companion
Stashi native patch: `funding_output_index` is used for selection, signing and
serialization. Core uses `redeemp2sh_indexed` for these outputs so older libraries
reject explicitly instead of silently spending output zero. The default QDN pin
has not changed; this source fix does not publish or select a new native bundle.

Offline regression coverage includes maturity gates, three-confirmation settlement,
lost acknowledgements and restarts, native txid byte order, expiry and UTXO checks,
per-wallet manual recovery, losing bids, legacy-state auditing and batch isolation.
Use an isolated checkout for Maven so an IDE compiler cannot replace packaged classes.

For owner testing, build Core with `mvn -o -Dmaven.gitcommitid.nativegit=true -DskipJUnitTests=true package`.
Before a rollout, stop Core and take a consistent copy of the complete configured
repository directory, wallet directory, settings and trade backups; retain the previous
JAR and native bundle signature. In the Stashi checkout, build for a Linux x86_64
test host with `QORTAL_JNI_TARGET=x86_64-unknown-linux-gnu scripts/build-qortal-jni.sh`
(or use its combined JNI workflow for all five platforms), then use the existing
reviewed staging/publication procedure below to obtain and select a new immutable `pirateChainWalletQdnSignature` before
testing nonzero outputs. The staging pins require a separate reviewed update for
that new artifact; the existing helper still accepts only the previously reviewed
bundle. Replace the JAR and restart using the owner's existing lifecycle. Verify the fee endpoint,
normal ARRR trading and an expired/aborted buy's refund through confirmation. Restart
while a refund is pending and verify it remains pending without immediate resubmission.
Check a reserve of 20000 and a nonzero funding output through refund and redemption
with the patched native bundle, and verify checkpoint clearing for a known native
rejection. No funded acceptance test is performed by the offline suite. To roll
back, stop Core and restore the previous JAR, native signature and consistent
pre-upgrade repository copy together (schema 54 contains new trade metadata).
Retain the post-upgrade database and wallet data for reconciliation; never discard
wallet state or roll back independently of transactions broadcast during testing.

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

## One-command publication

Run from the Core source checkout:

```bash
tools/prep-and-publish-arrr-wallet.sh
```

Choose the **inner combined JNI ZIP**, or the build directory containing that
ZIP. For the current build it is
`stashi-qortal-jni-0.3.9-deb736549e9b.zip`; the outer GitHub Actions download
must first be extracted to get this file. Enter the registered QDN name and a
new identifier, then the name owner's private key at the hidden signing prompt.
The default identifier includes a timestamp and is marked as a test candidate.

The helper verifies the pinned archive checksum, stages all five libraries,
reads the local API key from `~/qortal/apikey.txt`, prepares a fee-paying
`ARBITRARY_DATA` transaction, and runs the existing `sign` and `submit` commands
back to back. Invoking this helper is a request to broadcast; it has no second
broadcast prompt. It prints the immutable transaction signature and verification
command. Wait for confirmation before selecting the signature and restarting
Core. The `prepare-release` command used internally enforces the five-library
bundle shape; this does not certify a test candidate for production.

`QORTAL_API_URL` defaults to `http://127.0.0.1:12391`. `QORTAL_API_KEY` overrides
file discovery; `QORTAL_API_KEY_FILE` selects another file. If neither supplies
a key, the helper prompts privately. `QDN_FEE` defaults to `1000000` atomic
units (0.01 QORT). `QDN_OUTPUT_DIR` changes the output parent directory;
otherwise bundles and transaction files are retained in unique directories
under `${XDG_STATE_HOME:-$HOME/.local/state}/qortal/arrr-publications`, including
on failure. Private keys and API key values are not written to those files.

Only the reviewed combined archive is accepted. A later native build requires
reviewing its provenance/checksum and updating the staging and publishing pins;
choosing an arbitrary ZIP does not bypass verification. The explicit staging,
prepare, sign and submit steps below remain available for offline signing.

## Q-App address validation and spend readiness

Q-Apps should validate ARRR recipients against the selected Core instead of
requiring a `zs` prefix or exactly 78 characters. Core accepts mainnet Sapling
(`zs…`) and Ironwood (`pirate1…`) shielded addresses and checks their Bech32
checksum, receiver length and padding. Use the public endpoint:

```text
GET /crosschain/arrr/validateaddress?address=<URL-encoded-complete-address>
```

The JSON response is `true` or `false`. Missing, malformed, wrong-network and
checksum-invalid addresses return `false`. No API key, wallet entropy, wallet
initialization or external server connection is needed. This is format
validation, not proof that a wallet owns an address or can spend. An app using
the selected Core's base URL can validate on blur or debounce input changes:

```javascript
const response = await fetch(
  `${coreBaseUrl}/crosschain/arrr/validateaddress?address=${encodeURIComponent(recipient)}`
);
if (!response.ok) throw new Error('Core address validation is unavailable');
const valid = await response.json();
if (typeof valid !== 'boolean') throw new Error('Unexpected Core validation response');
```

Discard responses for an input or selected Core that has changed since the
request. If an older Core returns 404, display that validation is unavailable
and request a Core update; a successful HTTP response alone does not mean an
address is valid. The app must replace its old prefix/length gate to use this
endpoint. Installing the Core update does not change an app's existing checks.

For wallet diagnostics, the API-key-protected endpoint below accepts the same
Base58-encoded 32-byte entropy body as `/walletbalance`:

```text
POST /crosschain/arrr/spendability
Content-Type: text/plain
X-API-KEY: <Core API key>
```

It returns the Unified Wallet's `spendable`, `rescan_required`, `target_height`,
`anchor_height`, `validated_anchor_height`, `repair_queued` and `reason_code`
fields. Unlike balance queries, it does not require height synchronization.
It selects/initializes the requested wallet through the existing controller,
which can start normal synchronization, but does not start a full rescan or
send a transaction. It requires a Unified Wallet bundle; legacy bundles return
an error rather than fabricated readiness.

Keep this protected query inside the wallet host (for example Hub), using its
existing wallet permission flow. Do not give a Q-App wallet entropy or the Core
API key to call it. A Hub bridge returning readiness to Q-Apps must be added
separately; the public validator requires no such bridge.

Total or verified balance alone does not establish native spend readiness.
For example, the history-only loopback fixture recovers a verified balance
while reporting `ERR_RESCAN_REQUIRED` because it lacks a validated spending
anchor. Native sends retain their own safety checks. A readiness response is
a snapshot, not a reservation or guarantee of sufficient funds. Never clear a
rescan requirement or automatically retry an ambiguous broadcast to make a
payment proceed. Confirm a real affected wallet's reason and back it up before
planning recovery.

Pirate trade funding now returns a balance issue only for the native
`Insufficient funds:` response. Other funding failures return a network issue
and log their actual reason at WARN level as `Unable to fund PirateChain
P2SH-A: …`. The send endpoint also preserves the native error message and
distinguishes insufficient funds from other errors. These diagnostics do not
establish the cause of a tester's failure without that tester's status or log.

### Progressive transaction recipient recovery (3 November 2026)

Core returns the locally scanned transaction history promptly. An outgoing row
can initially contain `[UNKNOWN]` until the native wallet recovers its recipient
from the full transaction using this wallet's outgoing viewing keys. Incoming
senders marked `[PRIVATE]` are not revealed by this recipient lookup. Missing
keys or unavailable raw transactions can leave a recipient unknown indefinitely;
neither Core nor Q-Wallets substitutes a guessed address.

Each Unified history request permits a background batch of at most four detail
lookups. A failed lookup releases its claim, enters a cooldown, and allows the
rest of the batch to proceed. Untried transactions take priority over failed
transactions whose cooldown has elapsed, so a slow or unrecoverable newest row
cannot continually starve older rows. Background work claims only the exact
current wallet, yields immediately when its context is busy, and never selects
an account that was active when an earlier request was made. Recovering details
for a stored transaction does not require another live synchronization gate.

Q-Wallets 1.3.10 continues checking incomplete outputs while ARRR is open. The
first quiet refresh follows after five seconds; unchanged or failed responses
back off to 15, 30, then at most 60 seconds between reads. A decrease in unknown
outputs restores the five-second delay. Successful detail recovery is visible
on the next read. Existing history stays visible after a failed quiet refresh,
and manual refresh finishes independently of the background loop. Completion,
leaving ARRR, switching accounts or restarting the wallet session ends that loop.
Private incoming senders alone do not cause repeated history requests.

This fix requires both the updated Core JAR and Q-Wallets client. It uses the
existing native `get_transaction_details` action: no new JNI publication,
database index build, blockchain re-index or wallet rescan is introduced. Stop
Core, retain the previous JAR, install the testing JAR and restart it; publish
or load the Q-Wallets testing ZIP through the usual QDN testing workflow. Check
that recoverable outgoing rows gradually update and that leaving ARRR stops
client refreshes. Recovery failures log a stage and exception type without
logging wallet entropy or transaction details. To roll back, restore the previous
JAR and client version. Publication and live wallet testing remain owner actions.

### Multiple Hub accounts on one Core (2 November 2026)

Core uses one active JNI wallet context, with a separate encrypted storage
namespace for each account. Requests from different Hub instances must select,
read or spend, and release that context as one serialized operation.

The controller now queues requests before claiming a wallet switch. Sync status
keeps its requested account locked through both initialization and the read,
and failed initialization propagates instead of returning the previous account's
status. JSON status obeys the same lock as other wallet operations. Native
background sync is started before releasing the selected context; a delayed
Java worker can no longer start sync for the next account by accident. Late
status workers check that their captured wallet is still selected. Wallet init
cooldowns are scoped to the failed account, and a queued status request timing
out no longer triggers a server rotation for another account. Interrupted init
retries stop promptly. Concurrent first requests also share one controller.

These Java concurrency changes work with the existing v1.2.6 JNI bundle and
do not alter wallet storage or keys. The current branch also selects the
patched JNI 0.3.9 bundle described below. Rebuild Core and restart the JVM to
apply the changes.

Verification includes three offline session regression tests and an opt-in
test using the checksum-verified shipped Linux JNI library, isolated wallet
directories and a loopback-only lightwalletd. One public fixture wallet recovers
synthetic historical funds; another remains empty. The test checks queued
requests and repeatedly reopens both namespaces, verifying their distinct
addresses and balances. No real wallet, send or broadcast is involved.

All 21 selected controller, session, two-wallet native, spend-response and
trade-bot tests passed. The three new session regressions all fail against the
original controller, confirming the selection/read gap is covered. Run:

```bash
PIRATE_DEBUG_LOG_PATH=/absolute/path/to/isolated-native.log \
mvn -DskipJUnitTests=false \
  -Dtest=PirateWalletSessionTests,PirateChainWalletControllerTests,PirateUnifiedMultipleWalletTests \
  -Dqortal.runPirateUnifiedMultipleWalletTests=true \
  -Dqortal.pirateUnifiedBundlePath=/absolute/path/to/verified-v1.2.6-bundle \
  test
```

**The native engine still has one active storage context.** Changing accounts
resets its native runtime and can interrupt a historical scan. This correction
does not implement parallel rescans or certify two deep restores under constant
account switching. Let historical recovery complete before switching accounts,
or use separate Core processes for simultaneous recovery. Native context
isolation would require further work. After installing the rebuilt Core, test
two Hub accounts with already-synchronized wallets and verify each address,
balance and status; test recovery separately. Retain the prior JAR/settings and
wallet/trade backups so the Core change can be rolled back by restoring the
previous JAR and restarting, without replacing wallet data.

### Trade funding: missing Ironwood Merkle path (2 November 2026)

The reported log `Transaction build error: Missing Ironwood merkle path` has a
specific defect in the pinned Stashi `v1.2.6` native library. Its
`qortal_send_p2sh` adapter calls `get_unspent_selectable_notes_filtered`, which
restores Ironwood note plaintext and positions but does not construct Merkle
paths. The P2SH builder then requires the omitted path. Wallet height and the
overall spendability status can both be current while this adapter still fails.
This error occurs before signing/broadcasting the funding transaction. It does
not establish that every other trade failure has the same cause.

The native fix and offline regression tests are preserved in
[`stashi-v1.2.6-qortal-p2sh-witnesses.patch`](../tools/patches/stashi-v1.2.6-qortal-p2sh-witnesses.patch).
The patch targets upstream commit
`dedf63381996c5770945d16a4afa63ec7e35a676` (`v1.2.6`). It makes P2SH funding
reuse ordinary sends' confirmation, account/key-group, shard-scan and witness
selection gates, including separate Sapling and Ironwood anchor heights. The
Ironwood builder uses the same pool anchor as the hydrated notes. Funding output
zero, the extra redeem-script output, fees and redeem/refund contracts are
unchanged. No wallet schema or data migration is introduced.

Apply it to a separate clean checkout of that exact release, preserving any
existing native-wallet work. From the native repository root, with the patch's
absolute path substituted:

```bash
git apply --check /absolute/path/to/stashi-v1.2.6-qortal-p2sh-witnesses.patch
git apply /absolute/path/to/stashi-v1.2.6-qortal-p2sh-witnesses.patch
cd crates
cargo test --locked -p pirate-wallet-service --lib qortal_funding_
cargo test --locked -p pirate-wallet-service --lib qortal
cargo test --locked -p pirate-qortal-jni
cargo test --locked -p pirate-core qortal_p2sh
cd ..
bash scripts/build-qortal-jni.sh
```

The new tests use synthetic encrypted wallet databases and local shardtrees.
They cover witness hydration for both pools with different checkpoint heights,
missing witness state, recent notes and isolation of other key groups. They do
not synchronize a real wallet or broadcast a transaction.

Local Linux x86_64 verification passed 40 wallet-service Qortal tests (including
the three new regressions), five JNI tests, seven native P2SH tests and 11 Core
trade/response tests, with no failures or skips. All three new regressions fail
when the helper is switched back to the original raw-note loader. The patch
also applies cleanly to the pristine pinned release. The subsequent JNI 0.3.9
workflow built and smoke-tested all five native platforms, as recorded below.
Funded settlement remains a separate acceptance test.

**The existing v1.2.6 QDN publication remains unpatched.** Updating Java alone,
retrying the trade, changing servers or rescanning does not fix this missing
native-loader step. A corrected native build must be integrated and published
under a new reviewed release/version, archive checksum and immutable QDN
signature. The staging scripts intentionally reject a modified archive presented
as the official v1.2.6 artifact; do not weaken those checks. Build all five native
targets using upstream's supported toolchains and Linux ABI baseline before
preparing a cross-platform release.

Before funded testing, back up the wallet directories and trade-bot data and
retain the previous JAR/settings/native bundle. Select the newly published
signature, restart the JVM, and verify synchronization and spendability. Then
complete a small funded trade, confirm funding output zero and the script
output, verify change is spendable in a second trade, and exercise both redeem
and refund flows. These acceptance tests require separately authorized funds
and publication; the offline regressions alone do not certify settlement.
To recover from a failed candidate, stop it and restore the prior JAR and
signature/settings, then restart. Retain wallet/trade data and backups; an older
bundle still has this defect, so rollback is containment, not a trading fix.

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

## Current Unified Wallet candidate: JNI 0.3.9

**Qortal JNI 0.3.9 is based on upstream Stashi Wallet v1.2.6, plus the Qortal
P2SH witness fix.** These are separate version schemes: `0.3.9` identifies our
JNI adapter/binary artifact, while `v1.2.6` identifies the upstream wallet
release. The patched artifact is built by the owner fork listed below.

| Component | Version / source |
| --- | --- |
| Upstream wallet base | `PirateNetwork/Stashi-Wallet` `v1.2.6`, commit `dedf63381996c5770945d16a4afa63ec7e35a676` |
| Patched native source | `crowetic/Stashi-Wallet`, commit `deb736549e9b6869f08284acc5a12ebef944120a` |
| Qortal JNI artifact | `0.3.9` |

The staging and publication scripts pin this exact archive:

```text
artifact: stashi-qortal-jni-0.3.9-deb736549e9b.zip
sha256:   55bd5979c01d424ce4b26428ac35b51aed2da2131ceb18c29443e0b46c18e018
size:     333124923 bytes
build:    https://github.com/crowetic/Stashi-Wallet/actions/runs/41546354523
```

That workflow passed 57 Rust and 13 Python tests, all five native builds and
platform JNI smoke tests, Linux GLIBC checks, and combined-bundle integrity
checks. Core's loopback two-account test also passed with the exact packaged
Linux library. These tests do not replace funded trade acceptance. The broader
inherited upstream CI reported Cargo Audit/Cargo Deny dependency findings;
review/remediation is still outstanding. This is a testing candidate.

### Confirmed JNI 0.3.9 default publication

The owner published the combined bundle. Its transaction was verified through
the local Core API on 2 November 2026:

```text
name:       PirateChainWallet
identifier: LiteWalletJNI-2026-11-qortal-v0.3.9-test
signature:  aWGjhZHpPdo1gCpvriFKmAAMubSyoMvkuPxARFXao2y57hVjZw9E11nEj7Bd1P6WikXPsXzqtaqxD5zeWV8u86Y
block:      2782663
fee:        0.01000000 QORT
size:       333125312 bytes (QDN publication; distinct from the source ZIP size)
bundle:     Cross-platform (all five supported native libraries)
```

It is an `ARBITRARY_DATA` (service 100) `PUT` publication. This `pirate-changes`
test build selects the exact transaction when `pirateChainWalletQdnSignature`
is absent from settings, and enables `pirateChainWalletUnified` by default.
An explicit old signature override still takes precedence: remove it or replace
it with the signature above. Restart the JVM after selecting another bundle.

### Historical v1.2.6 default publication

The previous, unpatched cross-platform publication remains available:

```text
name:       PirateChainWallet
identifier: LiteWalletJNI-2026-10-unified-v1.2.6
signature:  3mK3T9pGj6FYSiD4KtkadNBzMDChcgLX4mtPRCnDrFpnSg3VGHasrYA9ByFNc84ok1yA8M2W13UYgXp6tJwMaKML
block:      2774289
fee:        0.01000000 QORT
size:       333259728 bytes
bundle:     Cross-platform (all five supported native libraries)
```

It is an `ARBITRARY_DATA` (service 100) `PUT` publication. Select it explicitly
only when testing or reverting to the prior native bundle; it retains the
P2SH witness defect. Preserve the prior JAR/settings and wallet/trade backups.
Do not delete wallet data when reverting a JAR or bundle selection.

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

### Historical v1.1.9 production Unified Wallet publication

The previous cross-platform default remains confirmed on QDN:

```text
name:       PirateChainWallet
identifier: LiteWalletJNI-2026-08-unified-v1.1.9
signature:  5drafi8G5WTjGVGh8B66runy51DDxupEEFaT67rAXgosDf6u22NWGntHZZ3jxv7Sq7AXtker7dxeXUkgxQ5yPcG1
block:      2707698
fee:        0.01000000 QORT
bundle:     Cross-platform (all five supported native libraries)
```

It is an `ARBITRARY_DATA` `PUT` publication. An explicit signature override
can still select this Unified Wallet bundle. Operators selecting a legacy
pre-Unified bundle must set `pirateChainWalletUnified: false` explicitly.

### Compatibility review: 26 October 2026

The combined `pirate-changes` build incorporates upstream `develop`
`181996b2cd1b84e844d33c2721614259679eae01`, including Phil's client-signed
BTC-family payments (`17e06908b`), payment-status checks (`5c434bb8c`), and
dust-threshold correction (`1864a7765`). It also retains the October 14 Pirate
fixes in `07baa1798`. Local `develop` tracks `qortal-upstream/develop`;
the combined test candidate is delivered on `crowetic/qortal:pirate-changes`.

Pirate's wallet project has moved to
[Stashi-Wallet](https://github.com/PirateNetwork/Stashi-Wallet). Its latest
published Qortal JNI artifact at review time is
[v1.2.6](https://github.com/PirateNetwork/Stashi-Wallet/releases/tag/v1.2.6),
published October 2, before the October 14 incident:

```text
artifact: pirate-unified-wallet-qortal-jni-artifacts-v1.2.6.zip
sha256:   60c91d1abfba16f97e22bf0f78b4aa846f9bd5b7455ab7d2f7155a3f5f9d1bc5
```

Core loads JNI rather than launching `pirate-qortal-cli`, as described in the
[upstream handoff](https://github.com/PirateNetwork/Stashi-Wallet/blob/v1.2.6/docs/qortal-handoff.md).
The v1.1.9 and v1.2.6 network parameters and light-client consensus-validation
sources are identical. No rollback-specific Java, protobuf, or JNI replacement
was identified as required by this review. Pirate's mandatory full-node
consensus fixes are documented in its
[6.0.10 security release notes](https://github.com/PirateNetwork/pirate/blob/v6.0.14-b14db80/doc/release-notes/release-notes-6.0.10.md);
they must be applied on the daemon side independently.

Validation of the combined source:

- All 49 focused offline tests passed, covering JNI serialization, wallet
  migration, addresses, history, controller behavior, Pirate trade state,
  client-signed BTC-family payments, API JSON, and active-chat membership.
- The opt-in production compact-block gate passed. `arrr.qortal.link` and
  `arrr2.qortal.link` served linked recent blocks at heights 4,185,775 and
  4,185,776. `arrr3.qortal.link` and `lightd1.pirate.black` returned HTTP 502;
  `lightd.pirate.black` timed out. These are observations, not endpoint changes.
- In separate Linux x86_64 JVMs and fresh isolated storage, both the pinned
  v1.1.9 library and the checksum-verified v1.2.6 library initialized a public,
  unfunded test wallet four blocks before the live tip and synchronized to
  height 4,185,776. Balance remained zero and history empty; no transaction
  was signed or broadcast.
- The broader `Pirate*Tests` run had seven failures in the existing live
  historical HTLC/address-history tests. Both responding Qortal endpoints
  returned empty `GetTaddressTxids` results for the fixture addresses even
  over the full height range; direct lookup still returned the known funding
  transaction at height 1,908,942. This does not establish whether the cause
  is server indexing or historical fixture assumptions. It prevents claiming
  that live HTLC discovery is verified. Recheck after daemon/index recovery.

At the start of this review, the default QDN signature selected v1.1.9.
The confirmed v1.2.6 publication above became the default at that review.
v1.2.6 adds relevant sync-interruption, transaction-accounting, recovery-deadline, and transport
fixes. Downloading or testing its ZIP alone does not update existing nodes;
they must run a build selecting the new immutable signature, with any explicit
old signature override removed or updated.
The scripts pinned the v1.2.6 archive checksum at that review; they now pin
the patched JNI 0.3.9 archive above. Retained publications and wallet data remain
available.

Before release, complete funded send, HTLC completion/refund, legacy migration,
and recovery of wallet state that scanned the discarded chain against the
recovered servers and matching Hub build. A fresh-tip smoke test does not
validate deep rollback recovery. Back up wallet directories and trade-bot data
before those tests. Do not downgrade a node managing local-funding trades in
state 80; retain its matching JAR, settings, and backups for recovery, as
described in [local-wallet-signing.md](local-wallet-signing.md).

## Stage the JNI 0.3.9 candidate

Download the combined artifact from the [verified fork workflow](https://github.com/crowetic/Stashi-Wallet/actions/runs/41546354523)
and extract the outer GitHub artifact download to obtain the inner
`stashi-qortal-jni-0.3.9-deb736549e9b.zip`. Verify it:

```bash
sha256sum --check <<'EOF'
55bd5979c01d424ce4b26428ac35b51aed2da2131ceb18c29443e0b46c18e018  stashi-qortal-jni-0.3.9-deb736549e9b.zip
EOF
```

The archive contains all five native libraries, Java wrapper, handoff, license,
and build provenance/checksums. The stage script selects one matching library
for a host-specific test or all five for a combined publication, and writes a
QDN manifest recording the archive hash, JNI version, upstream base and commit.
Do not add the old `coinparams.json`, `saplingoutput_base64`, or
`saplingspend_base64` files; unified mode does not require them.

For example, stage the Linux x86_64 publication with:

```bash
tools/stage-pirate-unified-qdn-bundle.sh \
  stashi-qortal-jni-0.3.9-deb736549e9b.zip \
  LiteWalletJNI-2026-11-qortal-v0.3.9-publish \
  linux-x86_64
```

The staging tool refuses an unexpected checksum or an existing output directory.

## Test mode: publish a host-specific candidate

Publish the staged directory to QDN as `ARBITRARY_DATA`, using a test name and a
distinct identifier, for example `LiteWalletJNI-2026-11-qortal-v0.3.9-test`. It must retain
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
  "$PWD/LiteWalletJNI-2026-11-qortal-v0.3.9-publish"

export QORTAL_API_URL='http://127.0.0.1:12391'
export QORTAL_API_KEY='<local-node-api-key>'
export QDN_FEE='1000000' # 0.01 QORT, in atomic units

tools/publish-pirate-unified-qdn.sh prepare \
  "$PWD/LiteWalletJNI-2026-11-qortal-v0.3.9-publish" \
  '<registered-test-name>' \
  'LiteWalletJNI-2026-11-qortal-v0.3.9-test'
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
  "$PWD/LiteWalletJNI-2026-11-qortal-v0.3.9-publish" \
  '<registered-test-name>' \
  'LiteWalletJNI-2026-11-qortal-v0.3.9-test'
```

The convenience command uses a timestamped output-file prefix, so a retry
never overwrites a prior unsigned transaction, signed transaction, or recorded
signature. The explicit `prepare`, `sign`, and `submit` commands retain their
predictable filenames and accept optional output-file arguments for an offline
or manually managed flow.

## Release mode: publish one cross-platform bundle

Only use this after the pinned fork archive and the Qortal integration have
been reviewed, and after the relevant architecture tests are complete. The
release bundle contains all five native libraries and therefore one immutable
QDN signature serves every supported desktop platform. It is not the source
ZIP, and it contains no legacy parameter files.

Stage it into a new directory. The staging script verifies the fork archive
checksum, requires all five native filenames, and refuses to overwrite an
existing directory:

```bash
tools/stage-pirate-unified-qdn-bundle.sh \
  stashi-qortal-jni-0.3.9-deb736549e9b.zip \
  LiteWalletJNI-2026-11-qortal-v0.3.9-release \
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
  "$PWD/LiteWalletJNI-2026-11-qortal-v0.3.9-release"
```

Choose the registered production QDN name controlled by the release signer and
a **new, versioned identifier**. Do not reuse the identifier of a prior
production publication. Prepare the fee-paying transaction first; this does
not sign or broadcast it:

```bash
export QORTAL_API_URL='http://127.0.0.1:12391'
export QDN_FEE='1000000' # 0.01 QORT in atomic units

tools/publish-pirate-unified-qdn.sh prepare-release \
  "$PWD/LiteWalletJNI-2026-11-qortal-v0.3.9-release" \
  '<registered-production-name>' \
  'LiteWalletJNI-2026-11-qortal-v0.3.9'
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
  "$PWD/LiteWalletJNI-2026-11-qortal-v0.3.9-release" \
  '<registered-production-name>' \
  'LiteWalletJNI-2026-11-qortal-v0.3.9'
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
confirmed JNI 0.3.9 test transaction signature, replacing the placeholder before
starting the node:

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
  "pirateChainWalletQdnSignature": "<confirmed-jni-0.3.9-test-signature>",
  "pirateChainWalletUnified": true,
  "pirateChainWalletDebugLogging": true
}
```

The transaction signature selects the immutable JNI bundle; it does not select
an ARRR address. The latter is deterministically derived from the Qortal
account entropy. On startup, Core logs the exact selected QDN transaction.

A clean entropy-backed Unified Wallet with no local registry or legacy cache
starts at the current lightwallet height. This avoids scanning old history for
a genuinely new wallet, and the Unified registry persists that starting point.
Existing Unified storage and legacy-cache migrations continue to use the
configured `arrrDefaultBirthday`.

When restoring a Qortal account whose derived ARRR address may already have
received funds, set a positive `arrrNewWalletBirthday` before that storage
namespace is first initialized. The configured height is used as-is for that
clean initialization. It is not an in-place rescan control for a namespace that
has already been initialized. For example:

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

### Expired sends despite a synchronized status

The Unified JNI compatibility `info.latest_block_height` can retain the target
of its last completed sync. Core must query the selected lightwalletd's live
height when checking a Unified wallet, rather than compare two cached heights.
A wallet more than two blocks behind or ahead needs normal incremental sync;
an unavailable live height or changed endpoint cannot establish readiness.
This check applies to the sync scheduler and the send/trade-funding gate.

For example, a construction target of 4,188,212 with the native 40-block expiry
window is already expired when the node is past 4,188,353. Repeating that send
cannot repair the construction height. Let incremental sync catch up first.
The Core log reports `walletHeight`, `liveChainHeight` and `lag` when the wallet
is out of date. No wallet reset or full rescan is implied by this error.

The opt-in `PirateUnifiedWalletFreshnessTests` reproduces a cached JNI target
against an advanced provider height using the pinned native library, isolated
storage and loopback-only history. It also checks rollback, unavailable-height
and endpoint-change cases. Set a dedicated absolute `PIRATE_DEBUG_LOG_PATH`
when running native tests so their process cannot clear a user's native log.

```bash
PIRATE_DEBUG_LOG_PATH=/tmp/qortal-native-fixture-debug.log \
mvn -DskipJUnitTests=false -Dtest=PirateUnifiedWalletFreshnessTests \
  -Dqortal.runPirateUnifiedWalletFreshnessTests=true \
  -Dqortal.pirateUnifiedBundlePath=/path/to/extracted-pinned-bundle test
```

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

## Validation for wallet initialization, recovery, and server readiness

The ordinary unit suite now checks that a clean entropy-backed wallet defaults
to the live tip, explicit recovery birthdays are honored before first
initialization, existing wallet paths keep the configured birthday, every JNI
entrypoint is serialized, server chain identity and height corroboration are
enforced, and a server is not admitted unless bounded `GetLightdInfo` and
`GetLatestBlock` responses agree. Run the focused set with:

```bash
mvn -DskipJUnitTests=false \
  -Dtest=LiteWalletJniTests,PirateWalletMigrationTests,PirateChainWalletControllerTests,PirateLightClientTrustTests \
  test
```

The historical recovery diagnostic uses a hand-built loopback lightwalletd and
a deterministic synthetic Sapling note. It is separate from the protobuf and
gRPC Java sources generated by Maven. The diagnostic passes an explicit
recovery birthday to the native wallet and runs the same
entropy-backed wallet in two distinct JVM/native processes with fresh HOME,
XDG, temporary, block-cache, log, and wallet-storage roots. Both processes must
scan from height `152855` through `152858`, recover exactly one confirmed
transaction, and report exactly `123456789` arrrtoshis as total and verified
balance with the historical control library. It is useful for comparing native
scanner behavior against that synthetic pre-ZIP-212 input, but it is not a
release gate for Core's tip-by-default initialization path. On Linux x86_64,
run it from a clean committed tree:

```bash
tools/run-pirate-unified-historical-restore-acceptance.sh \
  /absolute/path/pirate-unified-wallet-qortal-jni-artifacts-v1.1.6.zip \
  /absolute/path/to/the/staged-v1.1.7-bundle \
  /absolute/new/path/pirate-historical-restore-receipt.md
```

That command is the historical control. A proposed native bundle can be run
against it, using a distinct new receipt path, as a compatibility diagnostic.
Failure to recognize this synthetic note does not by itself establish a
regression in a current Pirate wallet or block the current release. The script
refuses an existing receipt, verifies that the staged library exactly matches
the supplied archive, hashes the archive, library, and fixture, scans raw
evidence for secret-shaped material, deletes wallet-capable evidence, and only
then writes a sanitized receipt.

The production lightwallet gate is separately opt-in and read-only. It requires
at least two configured mainnet endpoints to pass chain-name admission, matching
positive info/latest heights, and a two-block nonempty hash-link check:

```bash
mvn -DskipJUnitTests=false \
  -Dtest=PirateProductionLightwalletTests \
  -Dqortal.runPirateProductionLightwalletTests=true \
  test
```

Changing the Java lightwallet endpoint now schedules native-wallet cancellation
and reinitialization on the selected endpoint. It is no longer logged or treated
as an in-place native reconnect.
