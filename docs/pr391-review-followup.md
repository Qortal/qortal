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

The acknowledged-funding path also refreshes the best-effort JSON snapshot after
committing the funding pair. A failed snapshot cannot roll back committed recovery
state. Regression coverage still deliberately uses a stale pre-funding snapshot
to test restart safety.

## Validation

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

All ten supplied findings are addressed in this follow-up. No remaining blocker
from that list was found in the final diff and selected regressions. Maintainer
re-review of the recovery changes remains appropriate before merging. Successful
live funded ARRR completion and refund acceptance remain unverified, and these
tests do not resolve the original long-uptime QDN symptom by themselves. No new
XMR trading protocol or AT implementation is introduced here.
