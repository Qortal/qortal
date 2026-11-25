# PR #391 review follow-up

Reviewed baseline: `09bc1a393` on `qdn-performance`, against the developer's ten
reported findings. The owner explicitly approved the recovery migration on this
branch. General database-query/index optimizations remain separate.

## Finding-by-finding disposition

| # | Finding | Resolution and evidence |
| --- | --- | --- |
| 1 | Startup overwrites committed recovery state or resurrects a rejected attempt | Startup now reconciles missing rows instead of overwriting existing rows. Transactional deletion records prevent a stale snapshot from restoring an intentionally deleted intent. `PirateRecoveryStartupTests` exercises the actual Controller importer, committed refund retry metadata, a pre-funding backup, native rejection before broadcast, rollback of an uncommitted deletion, and empty-database recovery. Explicit manual import remains an intentional restore operation. |
| 2 | Script-only settlement can settle the wrong output | Persist `funding_txid` with `funding_output_index`. Refund/redeem confirmation and replacement spends use that exact pair, the expected redeem script and the required confirmation depth. Cache keys include the pair and depth. `PirateOutpointTests` uses asymmetric transaction IDs, two outputs to one script, and the same output index in another transaction. Legacy ambiguity retains the record; an old unbound confirmed marker cannot authorize deletion. |
| 3 | Funding recovery never retries a missing offer message | The waiting-for-AT-lock handler retries only the announcement while the offer/lock remain valid. A per-trade worker claim prevents concurrent submissions and is released after failures. Workers recheck persisted state before and after proof of work. The regression injects a repository failure, advances four tips, verifies one queued worker and a later retry, and verifies that funding is never repeated. |
| 4 | Legacy audit starves the sixth record | Select the oldest due eligible record, retaining the global one-minute and per-record retry limits. Cache the selection for the minute rather than rescanning for every trade. The six-record, first-five-missing-AT regression advances thirty minute-spaced tips and verifies the sixth is visited. |
| 5 | Disabled ARRR wallet causes scheduler/API null dereferences | Skip ARRR progress before repository access while disabled. Both new APIs use the spendability endpoint's disabled-wallet error handling. Scheduler and API regressions pass. The controlled error is `FOREIGN_BLOCKCHAIN_NETWORK_ISSUE` (1201); its existing HTTP mapping remains 500. |
| 6 | Forwarding promotes a direct-only reply to relay availability | Relay capability now requires both local relay availability and an upstream relay-capable route. Direct holder addressing is preserved. Forwarding tests cover a direct-only reply through a relay-enabled intermediary and verify that no payload relay route is registered. |
| 7 | Optional XMR tool cannot install cleanly | Removed the obsolete `tools/xmr-hub-client` wallet experiment at the owner's request. Core and the integrated Hub wallet do not use it. The retained public account page has a small separate Playwright-only harness; clean installs passed with Node 20.19.0 and 24, and its Chromium test passed. |
| 8 | Obsolete probe ignores an explicit Monero network override | The removed experiment also removes its incorrect selector. Core's network selection tests cover Qortal mainnet with Monero stagenet. Documentation now describes the explicit override and directs product verification to Hub's integrated worker. Removing the experiment does not remove Hub's wallet engine or SDK. |
| 9 | Native publication fixture has an outdated artifact SHA | Updated the valid fixture to the publisher's pinned SHA. Nested-directory and symlink cases also assert their specific rejection reason, so an unrelated preflight failure cannot mask them. All fifteen script checks and fourteen publication-workflow checks pass. |
| 10 | Combined guide denies an existing schema migration | Corrected the combined and native guides: 53→54 introduced the funding fee/index; this follow-up adds 54→55 for the transaction ID and deletion records. Both are additive recovery changes and require no blockchain reindex. Rollback guidance distinguishes data compatibility from missing safeguards in an older executable. |

### Follow-up review of `27919bf19`

The second review correctly identified three remaining paths under findings 2
and 4. The earlier assessment below describes the first review's validation;
it did not establish that these paths were safe.

- Funding now commits the acknowledged transaction ID before the first output
  lookup. An index of `-1` explicitly means unknown. Recovery retries that exact
  transaction and cannot substitute a different address-history deposit. Tests
  reopen the real HSQLDB repository and run the Controller startup importer after
  an indexing delay and a simulated exit during the first lookup. Both retain
  the ID, then recover output 2 without repeating funding. JSON round trips also
  retain the partial acknowledgement; uppercase native IDs are normalized.
- While waiting for an offer message, the seller skips redeemed or pending-spend
  candidates and continues checking later messages. These script-only checks
  cannot mark an open offer `BOB_DONE`. Existing redemption recovery still
  requires the exact funding pair, script and three confirmations. A regression
  checks both spend statuses alone and ahead of a valid funded candidate.
- Audit selection and cooldown cleanup now share one eligibility predicate.
  An old confirmed marker without a verified funding pair remains eligible and
  keeps its cooldown. The new regression combines an ambiguous old confirmed
  record and a recoverable record over thirty minute-spaced tips in both scan
  orders, alongside the existing six-record/missing-AT regression.

The existing schema-55 integer field stores `-1`; no new migration is needed.
An older executable that rejects negative output indexes cannot read an
unknown-index funding record from this build. Keep a compatible recovery
executable and the current data while such records are unresolved; do not roll
back by replacing them with a pre-funding snapshot.

The acknowledged-funding path refreshes the best-effort JSON snapshot after its
state update. A failed snapshot cannot roll back the independently committed ID.
Regression coverage deliberately keeps a stale pre-funding snapshot in the
simulated-exit case to test actual startup reconciliation.

## Validation of the second review

All **170 selected Java tests passed with zero failures, errors or skips**, using
Java 11. Coverage includes the three reported paths, actual startup and HSQLDB
round trips, ARRR parsing/outpoint and wallet preparation checks, Bitcoin-family
fee recovery, and recovery HTTP APIs. Native-wallet and live-server acceptance
tests were not part of this run.

All five new regression cases also failed against the actual previous
`b7b05a4dc` testing JAR and passed against the corrected source classes. The old
JAR lost the acknowledged ID in both indexing-delay cases, starved the later
record, and closed the open seller offer; it also rejected the new unknown-index
representation. The probe reported the production class source in both runs.
The source review initially left the testing JAR unchanged. The subsequent
pre-release package is documented below; neither establishes live funded
acceptance.

### Packaged pre-release

Runtime commit `d0398d13a` was packaged from a clean detached checkout using
Java 11 and the Maven Git plugin's native-Git option for worktree metadata.
The resulting `6.1.9-d0398d1` shaded JAR completed **510 selected tests with zero
failures and nine existing witness-related assumption skips**. Class-origin
checks verified the actual JAR for the recovery implementation, HTTP approval
reader, settings and Controller. The native wallet pin remains JNI 0.3.11; no
native or Hub rebuild is required.

Artifact: `qortal-qdn-arrr-xmr-d0398d13a-testing.jar`, 95,201,382 bytes.
SHA-256:

```text
2348e22004ee206a944e97c9fa3882e4d793e5eb2abe74fe6688030c973c4ab0
```

The local stable JAR aliases and companion announcement/checksum/build-receipt
files now identify this artifact. This prepares the files for the owner's cloud
upload; it does not install the JAR on a running node. The new unknown-index
compatibility constraint above still applies.

## Validation of the first review

The final selected Maven regression run completed **427 tests: zero failures,
zero errors and nine existing skips**. This covers startup/import/export, real
HSQLDB persistence/migrations, exact outpoints, refund recovery and announcement
retry, audit scheduling, disabled APIs, QDN relay/cache/download handling,
Bitcoin-family parsing/amounts/preparation/Electrum behavior, Monero addresses and
network selection, and the existing ACCT tests. An earlier focused run passed all
128 selected checks. Native-wallet and witness coverage represented by the nine
skips was not counted as passing.

Running the new API and relay regressions against the actual previous
`09bc1a393` test JAR reproduced three failing cases: two disabled-wallet API calls
and the relay-enabled forwarding of a direct-only reply. Class-loading diagnostics
confirmed that production classes came from that JAR, not the modified source
output.

A clean detached worktree of runtime commit `3549c1caa` also packaged
successfully. All **128 critical checks passed against that shaded JAR**, with
class-loading diagnostics confirming that the modified production classes were
loaded from the JAR. Its version is `6.1.9-3549c1c`; SHA-256:

```text
2df798cb5098750dc4398cf8aecf597afb29d771075d8d8632ac053d0d9a8ab1
```

The subsequent documentation/tool-cleanup commit does not alter packaged runtime
sources. The versioned review candidate is separate from the published test JAR.

A disposable schema-55 fixture was opened and saved with that same previous JAR.
It read the trade and new JSON, and a subsequent current-code read verified that
the new funding transaction ID, output index and deletion record survived. This
establishes structural compatibility for that tested JAR/fixture; it does not
establish safe funded-trade operation with the older recovery implementation.

The publication scripts and account-page browser check use isolated fixtures.
No live wallet, funded cross-chain trade, daemon or production database was used
for these remediation checks. The existing published JAR aliases and native
publication selection were not replaced.

## Migration, recovery and final assessment

Before installing a new build, stop Core and preserve the complete configured
repository, wallet files, settings and recovery backups. Startup performs the
additive recovery upgrade automatically. There is no established requirement to
restore an older database merely to change JARs, but an older executable does not
enforce these new safeguards. Retain a compatible recovery executable and current
data for pending or funded attempts. Do not replace them with a pre-funding
snapshot during rollback.

Deletion records belong to the local repository. A deliberately replaced, empty
repository can recover the available trade backup; it cannot reconstruct later
deletions or checkpoints absent from that backup. Authenticated manual import is
explicitly allowed to replace rows and clear deletion records. Neither operation
is the ordinary restart path.

The original assessment that all ten findings were fully addressed was too broad:
the second review exposed the three paths documented above, now corrected.
Maintainer re-review of the recovery changes remains appropriate before merging. Successful
live funded ARRR completion and refund acceptance remain unverified, and these
tests do not resolve the original long-uptime QDN symptom by themselves. No new
XMR trading protocol or AT implementation is introduced here.
