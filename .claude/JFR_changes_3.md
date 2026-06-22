# JFR / GC Analysis (Run 2) — Changes Applied

Reference analysis: `/home/nicola/Downloads/_QORTA/analysis.md` (see also `.claude/JFR_analysis_3.md`)

This is the third round of fixes. The reference analysis confirmed the OOM
trajectory from Run 1 is fixed (heap stable 13+ hours) but surfaced new
contention, CPU, exception-rate and thread-churn findings. Priorities below use
the analysis.md numbering (P0–P8).

---

## P0 — G1HeapOccupancyPercent=35 (NOT changed here)

Already present in the node startup script — intentionally skipped in code.

---

## P1 — ArbitraryDataBuilderThread.java: DB status check held a monitor lock

**File:** `src/main/java/org/qortal/controller/arbitrary/ArbitraryDataBuilderThread.java`
**Commit:** `dfb7f551`

**Problem:** All 5 builder threads contend on
`synchronized (buildManager.arbitraryDataBuildQueue)`. Inside that block the old
code called `ArbitraryTransactionUtils.getStatus(...)`, which opens a DB
connection, runs a query, writes a status row and commits — potentially 10+
seconds under load. While one thread held the monitor doing that DB work, the
other 4 builder threads were all blocked. This is what the JFR caught as
`ConcurrentHashMap` / `JavaMonitorEnter` contention with waits up to ~11.6s
(the analysis attributed it to "computeIfAbsent slow compute", but the real
mechanism was the DB call under the queue monitor).

**Fix:** `prepareForBuild()` (which sets `buildStartTimestamp`, marking the item
in-progress so other threads' `isQueued()` filter skips it) now runs *inside*
the lock to reserve the item. The DB `getStatus()` call was moved *outside* the
synchronized block. The 5 builder threads can now overlap their DB calls instead
of serializing through the queue monitor.

---

## P4 — pom.xml: Jackson library stack split across incompatible versions

**File:** `pom.xml`
**Commit:** `e36bdd3f`

**Problem:** The Jackson ecosystem was split across two versions on the runtime
classpath:

| Version | Artifacts | Source |
|---|---|---|
| **2.18.2** | jackson-jaxrs-json-provider, jackson-jaxrs-base, jackson-module-jaxb-annotations, jackson-dataformat-yaml, jackson-datatype-jsr310 | transitive via `swagger-core:2.2.30` |
| **2.15.2** | jackson-databind, jackson-core, jackson-annotations | explicitly pinned in pom.xml |

The `jackson-jaxrs-json-provider:2.18.2` is compiled against
`jackson-databind:2.18.2` internals but ran against `2.15.2`, throwing
`NoSuchMethodError` / `IncompatibleClassChangeError` on the `DirectMethodHandle$Holder`
path for every JAX-RS request that serialized a JSON response — the ~124
exceptions/second (≈5.8M total/run) seen on the Jetty worker threads.

**Fix:** Imported the official Jackson BOM in a new `<dependencyManagement>`
section:

```xml
<dependency>
  <groupId>com.fasterxml.jackson</groupId>
  <artifactId>jackson-bom</artifactId>
  <version>${jackson.version}</version>   <!-- 2.18.2 -->
  <type>pom</type>
  <scope>import</scope>
</dependency>
```

Added a `jackson.version=2.18.2` property and removed the now-redundant
hardcoded `2.15.2` from the direct `jackson-databind` dependency. `mvn
dependency:tree` confirms all 8 Jackson artifacts now resolve to **2.18.2**; the
project compiles cleanly against the bumped databind. The 2.15.2 pin came from
the "JSON import/export support" commit and was an arbitrary version choice (the
"latest stable" comment was wrong — swagger already required 2.18.2), so
aligning upward carries no known regression and picks up later security fixes.

---

## P5 — HSQLDBDatabaseUpdates.java: cache evicting on row-count, not bytes

**File:** `src/main/java/org/qortal/repository/hsqldb/HSQLDBDatabaseUpdates.java`
**Commit:** `7170f539`

**Problem:** `BaseHashMap.clearToHalf()` was the #1 CPU hotspot at 10.6%, driven
by HSQLDB `Cache.cleanUp()`. Verified against the HSQLDB 2.7.4 source: the row
cache has **two independent limits** and evicts when *either* is hit
(`Cache.preparePut()` → `exceedsCount || exceedsSize`):

- `hsqldb.cache_size` (`SET FILES CACHE SIZE`) — default **10,000 KB**
- `hsqldb.cache_rows` (`SET FILES CACHE ROWS`) — default **50,000 rows**

A prior commit (`7a9a80a7`) ran `SET FILES CACHE SIZE 200000`, raising only the
**byte** budget to ~200 MB — `CACHE SIZE` is in kilobytes, not rows (the commit
comment conflated the two). The **row-count** limit stayed at its default 50,000.
Because the JFR was captured on this branch *after* that commit and still showed
`clearToHalf` thrashing, the working set is row-count-bound: it hits 50,000 rows
long before 200 MB, so the byte bump never took effect.

**Fix:** Added migration `case 53:` running `SET FILES CACHE ROWS 200000`, so the
cache can grow into the 200 MB byte budget already configured in case 52.
Verified in the `Cache` constructor that the backing arrays are pre-allocated to
the CACHE ROWS value, so 200,000 (matching the prior commit's stated intent and
the analysis's 100k–500k recommendation) was chosen rather than an arbitrarily
huge value — ~5–8 MB of index arrays, with memory still hard-bounded by the
unchanged 200 MB byte cap (heap-safe). The misleading comment on case 52 was also
corrected. A new `case 53:` (rather than editing case 52) ensures already-upgraded
nodes pick up the change on the next version bump.

---

## P3 — Network.java: Network-Worker thread churn (~53 created/min)

**File:** `src/main/java/org/qortal/network/Network.java`
**Status:** applied, **not yet committed**

**Problem:** `Network-Worker` threads were created and destroyed at ~53/min,
contributing to ~4% CPU in `ThreadLocalMap.set/remove` (stale-entry cleanup
runs on every set/remove when threads churn) plus safepoint overhead. The churn
is intrinsic to the `ExecuteProduceConsume` (EPC) algorithm: it spawns a logical
worker whenever every active thread becomes a consumer, and exits workers when
there's an idle-producer excess. The backing `ThreadPoolExecutor` was
`core=2, max=512, keepalive=5s, SynchronousQueue`, so almost every EPC spawn
created a brand-new physical `Thread` that then died within 5s — and physical
thread create/destroy is what churns `ThreadLocalMap`.

**Fix:** Keep physical threads warm for reuse:
- `core` **2 → 10** (`NETWORK_EPC_CORE_THREADS`) — a pinned warm baseline (core
  threads never time out) absorbs steady-state load with zero churn.
- `keepalive` **5s → 60s** (`NETWORK_EPC_KEEPALIVE`) — burst-overflow threads
  (above core) linger long enough to be reused instead of respawned.

**Deliberately NOT changed:** `max=512` and the `SynchronousQueue`. The analysis
suggested capping max at ~8 with a queue, but that is dangerous for this EPC: it
breaks the required immediate producer→consumer handoff and would cause
`RejectedExecutionException` storms / network stalls under burst. The sibling
`NetworkData-Worker` pool was verified to already be churn-free (`core=10` with an
unbounded `LinkedBlockingQueue` never grows past 10 threads) and was left alone.

---

## P2 — TradeOffersWebSocket.java: DB queries under a java.util.HashMap monitor

**File:** `src/main/java/org/qortal/api/websocket/TradeOffersWebSocket.java`
**Status:** applied, **not yet committed**

**Problem:** The analysis flagged `JavaMonitorEnter` contention on a raw
`java.util.HashMap` (20 events, 5,570ms, ~278ms average wait). The `JavaMonitorEnter`
class is the key clue: a data race *without* synchronization produces no monitor
events, so this had to be an explicit `synchronized (someHashMap)`. After ruling out
the wrapper-typed (`Collections$SynchronizedMap`) and non-map lock objects, and the
plain-HashMap monitors that are only ever touched single-threaded (`AccountRefCache`,
used solely from single-threaded `Block.process()`/`orphan()`) or hold no I/O
(`ChatNotifier.listenersBySession`, `TradeBotWebSocket.PREVIOUS_STATES`), the culprit
was `TradeOffersWebSocket.cachedInfoByBlockchain` (`new HashMap<>()`).

Its `listen()` method (a `NewChainTipEvent` handler, fired every block) held
`synchronized (cachedInfoByBlockchain)` across a loop of
`repository.getATRepository().getMatchingFinalATStates(...)` + `produceSummaries(...)`
**DB queries** — once per supported blockchain, per ACCT. Meanwhile
`onWebSocketConnect()` also locks `cachedInfoByBlockchain` to read initial data, so
every client connecting during a block's AT-state scan blocked for the full scan
duration. (Not a corruption risk — it *is* synchronized — but the monitor was held
far too long.)

**Fix:** Moved all repository/DB work in `listen()` *outside* the
`synchronized (cachedInfoByBlockchain)` block. The DB queries build the
`crossChainOfferSummaries` list with no lock held; the monitor is then taken only for
the fast in-memory cache mutation (diff against `previousAtModes`, update
`currentSummaries`/`historicSummaries`, prune >24h entries). The lock's real job —
guarding the cache structure between the `listen()` writer and the
`onWebSocketConnect()` reader — is preserved; the slow DB scan no longer blocks
connecting clients. Behavior (including the "skip if unchanged" `continue` and
historic pruning) is unchanged.

---

## P7 — NTP.java: unbounded, unnamed NTP poll pool (~25 threads/min)

**File:** `src/main/java/org/qortal/utils/NTP.java`
**Status:** applied, **not yet committed**

**Problem:** The anonymous `pool-5-thread` churner doing short-lived work was
NTP's `serverExecutor = Executors.newCachedThreadPool()`. It submits one short
task per NTP server each poll cycle; because per-server poll intervals can exceed
the cached pool's 60s keepalive, its threads kept dying between polls and being
recreated. Anonymous Executors pools also surface as the unhelpful
`pool-N-thread-M` name in profiles.

**Fix:** Replaced with a fixed pool sized to the server count, using a named
daemon factory:

```java
serverExecutor = Executors.newFixedThreadPool(
        Math.max(1, ntpServers.size()),
        new DaemonThreadFactory("NTP-poll", Thread.NORM_PRIORITY));
```

Reuses warm threads each cycle (no churn), bounds concurrency to the number of
servers, and names threads for future profiling. (`pool-5` couldn't be confirmed
as NTP without a runtime thread dump, but it's the strongest-matching candidate
and bounding/naming this unbounded anonymous pool is correct regardless — the
next profile will name it explicitly.)

---

## P6 — Redundant X25519 shared-secret computation in the handshake

**Files:** `src/main/java/org/qortal/crypto/Crypto.java`,
`src/main/java/org/qortal/network/Network.java`,
`src/main/java/org/qortal/network/NetworkData.java`,
`src/main/java/org/qortal/network/Peer.java`,
`src/main/java/org/qortal/network/Handshake.java`
**Status:** applied, **not yet committed**

**Context — this is the lowest-impact JFR item.** `X25519Field.create()` (`new int[10]`)
showed 14,124 allocation events but ranked only **14th** by allocation *weight*: ≈0.8 MB
over the 61-min window of tiny, short-lived young-gen arrays that never reach old gen. It
is **not a GC driver.** The real cost is the CPU of the X25519 scalar multiplication, and
two redundancies in the handshake path. The analysis's suggested options were rejected:
switching to JVM-native `KeyAgreement("XDH")` would risk the handshake's byte-for-byte
compatibility (Qortal uses a custom Ed25519→X25519 birational map + SHA-512 clamping), and
pooling `int[10]` arrays would require forking BouncyCastle — both poor trade-offs for
~0.8 MB/h. Only the two zero-wire-change wins (A + B) were applied. (P3's reduction in
handshake churn already lowers this proportionally.)

**Fix A — derive our X25519 private params once.** The node's Ed25519 key is fixed for
the JVM lifetime, yet `getSharedSecret` re-ran `toX25519PrivateKey` (a SHA-512 digest) and
`new X25519PrivateKeyParameters` on every call. Added `Crypto.toX25519PrivateKeyParams(...)`
plus a `getSharedSecret(X25519PrivateKeyParameters, byte[])` overload; `Network` and
`NetworkData` now cache `ourX25519PrivateKeyParams` once at construction and pass it in. The
existing `getSharedSecret(byte[], byte[])` is retained (delegates to the overload) for other
callers (e.g. `PrivateKeyAccount`).

**Fix B — compute the per-handshake secret once.** The shared secret was computed twice per
handshake — in `RESPONSE.action()` (sending our RESPONSE) and in the RESPONSE message handler
(validating the peer's). Both use the same `(our fixed key, peer's public key)` → identical
result. Added a `volatile byte[] handshakeSharedSecret` field + accessors on `Peer`, and a
`Handshake.getOrComputeSharedSecret(peer, peersPublicKey)` helper that computes once, caches
on the peer, and reuses. The concurrent compute is benign (deterministic result; worst case
matches the old double-compute). No wire bytes change — the secret is identical, just computed
once and reused.

---

## Remaining (not yet applied)

| ID | Description | Notes |
|----|-------------|-------|
| P8 | Restore heap headroom (`-Xmx`) | GC tuning — startup-script territory like P0; may already be set. |
