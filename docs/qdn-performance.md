# QDN cache performance testing

`qdn-performance` starts from `xmr-support` commit
`1b99eedc5e746171064722e7eb2460aa3370c21a` and retains its ARRR/XMR changes.
This branch introduces **no database migration, schema version change, persisted
cache format change or wallet format change** relative to that base. Its database
migration code and repository implementation are identical to the base.
The timestamp index and recent-query optimization are kept separately on
`performance-db-improvements`; they are not included in this testing JAR.

## Changes and limits

- Repeated chunk/metadata notifications keep one pending cache update per
  transaction signature. Distinct PUT/PATCH transactions retain arrival order.
  Normal passes process at most 128 updates, releasing their repository session
  between passes. A notification during processing remains pending for a later
  update. Shutdown retains the full pending-queue flush. This queue lives in memory;
  cache updates still write the existing primary repository tables.
- Raw-download requests poll queued builds at 200 ms intervals, with a
  60-second budget for polling/retries and support for interruption. An active
  synchronous load retains its existing I/O timeouts; the budget cannot cancel a
  query already executing. Forced rebuilds still rebuild. Existing not-found
  response handling is retained; interruption reports a repository error.

The November 17 qortal.link log and dumps demonstrate HSQLDB disk-cache contention,
including a 30.9-second recent-transaction query. They do not establish the cause
of the persistent QDN loading failure. This branch reduces redundant cache work
and request spinning; it does not include the timestamp-query optimization or
optimize the separate 72.7-second AT-state maintenance operation. The isolated
servlet-response serialization error also remains unaddressed. The inherited
full-stack thread-dump formatter remains included.

## Local verification

Use JDK 11 and explicitly enable tests (the POM defaults to skipping them):

```sh
mvn -Dmaven.gitcommitid.nativegit=true -DskipJUnitTests=false \
  -Dtest=ArbitraryDataCacheManagerTests,ArbitraryDownloadWaitTests,ArbitraryResourceSearchTests,ThreadDumpSchedulerTests,PirateChainACCTv3TradeBotTests,PirateChainWalletControllerTests,LocalWalletPreparationTests test
```

The new tests exercise concurrent notification coalescing, bounded batches,
notification retention, polling timeout, forced rebuilds, retries and interruption.
Run the existing QDN, repository and ARRR/XMR regressions before delivery. Existing
suite failures must be compared with the unchanged base rather than hidden.

## Owner-operated testing and rollback

No gateway rollout is performed by this source/build task. Test on a separate
node first, including active ARRR recovery workflows described in
[the combined branch guide](arrr-xmr-testing.md). Use a previous JAR built from
this same XMR/ARRR base for rollback; this does not establish compatibility with
arbitrary older Core releases or repositories migrated by another branch.

1. Preserve the prior JAR and checksum. On a script-managed gateway, use its
   working directory (`/home/crowetic/qortal` in the supplied log), run
   `./stop.sh --port 12391 --timeout 300`, and verify shutdown completed. Use the
   existing service/container lifecycle instead if that manages the node.
2. While stopped, back up the complete configured `repositoryPath`, settings,
   wallet/cache directories and trade recovery records together. Verify the
   testing JAR's companion checksum, then replace only `qortal.jar`. Keep the
   existing native-wallet QDN publication and cache.
3. Start through the same lifecycle (`./start.sh` for script-managed nodes).
   Check `/admin/info` for the intended build and `/admin/status` for normal
   synchronization at the configured API origin. No new schema upgrade is
   expected relative to the XMR base (version 54).
4. Load cached and uncached QDN resources, including one that previously failed.
   Compare queue behavior and collect full thread dumps during any remaining
   failure. This runtime verification is needed before calling the incident fixed.

To roll back, stop through the same lifecycle, restore the retained previous JAR,
and start with the **current** repository, wallets and recovery records. No schema
rollback, repository restore or cache deletion is needed for these changes. Do
not roll financial state back to an earlier snapshot merely to switch JARs.
