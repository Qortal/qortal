# JFR / GC Analysis — Changes Applied

Reference analysis: `/home/nicola/Downloads/_QORTA/analysis.md`

---

## P0 — Peer.java: per-connection ByteBuffer allocation (already fixed)

**Commit:** `b0895f02 Set allocation per peer connection`

`Peer.readChannel()` was allocating a fresh 2MB `ByteBuffer` on every call, leaving 208 buffers (~416MB) stuck in old gen. Fixed before the current analysis session by lazily allocating once per `Peer` instance and reusing it.

---

## P1 — MemoryPoW.java: ThreadLocal scratch buffer

**File:** `src/main/java/org/qortal/crypto/MemoryPoW.java`

**Problem:** `verify2()` and `compute2()` allocated a fresh `long[1048576]` (8MB) on every call. With 45 retained arrays observed, ~360MB was stuck in old gen.

**Fix:** Added `ThreadLocal<long[]> THREAD_WORK_BUFFER` with a `getOrResizeThreadBuffer()` helper. Both `compute2` and `verify2` now reuse the per-thread buffer instead of allocating per-call. Loop bounds changed from `workBuffer.length` to `longBufferLength` to ensure correctness when the ThreadLocal buffer is larger than needed (e.g. an 8MB buffer reused for a 1MB verification call).

---

## P2 — NTP.java: per-server NTPUDPClient

**File:** `src/main/java/org/qortal/utils/NTP.java`

**Problem:** All `NTPServer` instances shared a single `NTPUDPClient`, with every `doPoll()` call requiring `synchronized (client)` before calling `client.getTime()`. This caused 25 cumulative minutes of thread blocking in a 22-minute JFR window — NTP threads spent more time blocked than running.

**Fix:** Moved `NTPUDPClient` from `NTP` (shared) into `NTPServer` (one per server instance). Each server creates its own client in its constructor and closes it via `close()`. Removed the `synchronized (client)` block entirely — no synchronization needed since each client is used by exactly one thread. `NTP.shutdownInternals()` now iterates over servers to close their clients. `doPoll()` signature drops the `client` parameter.

---

## P3 — ArbitraryDataBuildManager.java: ConcurrentHashMap

**File:** `src/main/java/org/qortal/controller/arbitrary/ArbitraryDataBuildManager.java`

**Problem:** `arbitraryDataBuildQueue` and `arbitraryDataFailedBuilds` were `Collections.synchronizedMap(new HashMap<>())`. The same coarse monitor was used for all operations (`containsKey`, `put`, `remove`, `isEmpty`, `removeIf`) AND for the compound find-and-claim block in `ArbitraryDataBuilderThread`, causing 6.5 cumulative minutes of contention with individual waits up to 6 seconds.

**Fix:** Replaced both maps with `ConcurrentHashMap`. Individual operations are now lock-free. The `synchronized (buildManager.arbitraryDataBuildQueue)` block in `ArbitraryDataBuilderThread` is intentionally retained — it still serializes the compound find-and-claim (stream → `isQueued()` filter → `prepareForBuild()`) across the 5 builder threads, which must remain atomic since `prepareForBuild()` is not itself atomic.

---

## P4 — RepositoryManager.java / HSQLDBRepository.java: dedicated lock objects

**Files:**
- `src/main/java/org/qortal/repository/RepositoryManager.java`
- `src/main/java/org/qortal/repository/hsqldb/HSQLDBRepository.java`

**Problem:** Both `trimHeightsLock` and `latestATStatesLock` in `HSQLDBRepository` were set to `RepositoryManager.getRepositoryFactory()` — the same singleton object. `rebuildLatestAtStates()` and `trimAtStates()` each held this lock for 30+ seconds (running expensive `DELETE + INSERT INTO ... SELECT` and `DELETE FROM ATStatesData WHERE NOT EXISTS` queries). During those windows, every caller of `trimHeightsLock` (`setAtTrimHeight`, `setBlockTrimHeight`, etc.) was also blocked, even though those operations are unrelated to AT state rebuilding.

**Fix:** Added two dedicated `public static final Object` singletons to `RepositoryManager`:
- `LATEST_AT_STATES_LOCK` — for `LatestATStates` table mutations
- `TRIM_HEIGHTS_LOCK` — for `DatabaseInfo` height columns

`HSQLDBRepository` now references these instead of the factory. Operations within the same concern still serialize correctly. The factory is no longer a contention hotspot.

---

## P5 — HSQLDBCacheUtils.java: bound balancesByHeight map

**File:** `src/main/java/org/qortal/repository/hsqldb/HSQLDBCacheUtils.java`

**Problem:** The balance recorder timer (Timer-2) fires every 20 minutes and stores a full snapshot of all ~47k network accounts into `balancesByHeight` (a `ConcurrentHashMap<Integer, List<AccountBalanceData>>`). The eviction call (`removeRecordingsBelowHeight`) was only called inside `produceBalanceDynamics`, which only runs during reward-distribution events. When `isRewardRecordingOnly=true` (the default), the map grew without bound between distributions — each entry holding a 47k-element ArrayList (~3.7MB each) that was never cleared. The JFR showed a `Object[47427]` backing array aged 7+ minutes still retained in old gen.

**Fix:** Added an unconditional `removeRecordingsBelowHeight(currentHeight - rollbackAllowance, balancesByHeight)` call in the timer task body so the map is evicted on every fire regardless of whether dynamics are produced. With default `rollbackAllowance=100` blocks at ~1 block/min and a 20-minute recording frequency, the map is now capped at ~5 entries at steady state. The existing call inside `produceBalanceDynamics` is retained as a second-pass cleanup after dynamics computation.

---

## P7 — Base58.java: LRU encode/decode cache

**File:** `src/main/java/org/qortal/utils/Base58.java`

**Problem:** `Base58.divMod58` was the #3 CPU hotspot at 7% and the fastest-growing hot spot, rising proportionally with chain state queries. Every `encode(byte[])` call on a known public key or address re-ran the full iterative division algorithm.

**Fix:** Added two Guava `Cache` instances (max 10,000 entries each, thread-safe via segment locking):

- `ENCODE_CACHE<String, String>` — keyed by `new String(bytes, ISO_8859_1)`, which gives content-based equality for `byte[]` without a custom wrapper. Result cached on miss; returned directly on hit (String is immutable).
- `DECODE_CACHE<String, byte[]>` — keyed by the input String. On hit, returns `cached.clone()` to prevent callers from mutating the cached value. On miss, stores the computed result and returns a clone.

With ~47k distinct addresses in the network and 10k cache slots, repeated lookups of active addresses eliminate `divMod58`/`divMod256` entirely for those keys.

---

## Remaining (not yet applied)

| ID | Description |
|----|-------------|
| P6 | G1 tuning flags (`G1HeapOccupancyPercent=35`, `G1HeapRegionSize=16m`, etc.) |
| P8 | Resolve `IncompatibleClassChangeError` classpath conflict |
