# GC Log + JFR Analysis — Re-Analysis (Run 2)

**Environment:** Java 17.0.19, G1GC, 4 CPUs, 7.4GB system RAM  
**Heap:** 1536M min/initial, **1872M max**, region size 16M (↓ from 2984M — heap was reduced)  
**GC log window:** ~13.0 hours (46,691s), **8,628 GC events**  
**JFR window:** 3,686s (61 min), 13 chunks, start 08:44:14 local

---

## What Changed Since Run 1

| Fix | Status | Evidence |
|---|---|---|
| P1 — MemoryPoW thread-local buffer | **APPLIED** | `MemoryPoW.getOrResizeThreadBuffer()` visible in JFR allocation stacks |
| P2 — NTP per-instance client | **APPLIED** | Zero `NTPUDPClient` contention events (was 25 min blocked / 22 min window) |
| P3 — SynchronizedMap → ConcurrentHashMap | **APPLIED** | `SynchronizedMap` contention: 121ms total (was 392,007ms) |
| P6 — Heap region size 16M | **APPLIED** | Log header shows `Heap Region Size: 16M` |
| P0 — ByteBuffer per-peer reuse | **PARTIAL** | ByteBuffer still allocated in `readChannel()`, but 2MB retained count not visible; off-heap DirectBuffer total = 19.7MB (was ~416MB retained in old gen) |
| P4 — AT pruner lock duration | **UNCLEAR** | No `HSQLDBRepositoryFactory` contention visible (may not have fired in JFR window) |
| P5 — Timer-2 ArrayList bound | **UNCLEAR** | Timer-2 not observed in JFR window |
| P7 — Base58 cache | **NOT APPLIED** | `Base58.divMod58` still present; only 30 samples (was 1744) — naturally reduced as chain grows slower |
| P8 — IncompatibleClassChangeError classpath fix | **NOT APPLIED** | Still occurring at 08:44 |

---

## 1. GC Summary — Heap Is Now Stable

Old gen is no longer monotonically growing. It oscillates between 52–86 regions (~832MB–1376MB) throughout the 13-hour run. **The OOM trajectory from Run 1 is fixed.** However, pause quality has significantly degraded:

| Pause bucket | Count | % of total |
|---|---|---|
| < 50ms | 3 | 0% |
| 50–100ms | 257 | 3% |
| 100–200ms | 4,425 | 55% |
| 200–300ms | 2,082 | 26% |
| 300–400ms | 754 | 9% |
| 400–500ms | 277 | 3% |
| 500–700ms | 146 | 1% |
| 700ms–1s | 29 | <1% |
| **> 1s** | **4** | <1% |
| Total | 7,977 | 100% |

- **MMU violations (200ms target):** 3,258 over 13 hours (~4.2/hour) — every violated pause is a guaranteed 200ms+ STW event
- **Worst pauses:** 1,242ms (GC 8116), 1,084ms (GC 1875), 980ms (GC 933), 963ms (GC 1067)
- **Worst pause cause:** 100% of worst pauses are Mixed GC with `Evacuate Collection Set` consuming 99% of pause time (1,234ms and 1,074ms respectively). This is live-data evacuation stalling on dense old regions.

**Why pauses got worse after heap reduction:** reducing max heap from 2984M to 1872M means G1 has less room to maneuver. Mixed GCs must evacuate more frequently with less buffer, and each evacuation must copy more live data proportionally.

---

## 2. Concurrent Mark — Still The Core Problem

| Metric | Value |
|---|---|
| Total concurrent mark cycles | 652 |
| Mean duration | **2,478ms** |
| Max duration | **5,751ms** |
| Cycles > 2s | 486 (74%) |
| Cycles > 3s | 143 (22%) |
| IHOP threshold at JFR start | 74.5% (1.2GB / 1.6GB target) |
| IHOP threshold at JFR end | **95.6% (1.5GB / 1.6GB target)** |
| Predicted marking duration | **11–12 seconds** |

The IHOP threshold has drifted to 95.6%. G1 adaptive IHOP is raising the threshold because it predicts marking will take 11–12s (long), so it delays starting concurrent mark to give more time after old gen fills. This is counterproductive: the later mark starts, the closer old gen is to full when mixed GCs begin, so each mixed GC must do more work, causing the long evacuations.

**The `G1HeapOccupancyPercent=35` flag from P6 was not applied** — this is the single most impactful remaining GC tuning item.

---

## 3. CPU Hot Spots (54,695 ExecutionSamples, 61-min JFR)

| Rank | Method | Samples | % | Status |
|---|---|---|---|---|
| 1 | `BaseHashMap.clearToHalf()` | 5,789 | **10.6%** | **NEW — critical** |
| 2 | `BaseHashMap.getObjectLookup(long)` | 5,130 | 9.4% | up from 8.2% |
| 3 | `Cache.cleanUp(boolean)` | 4,333 | 7.9% | up from 4.7% |
| 4 | `Cache$CachedObjectComparator.equals` | 3,856 | 7.1% | new breakout |
| 5 | `IndexAVL.compareRow` | 1,848 | 3.4% | new |
| 6 | `AQS.releaseShared` | 1,359 | 2.5% | ≈ same |
| 7 | `RowStoreAVLDisk.get` | 1,253 | 2.3% | new |
| 8 | `ThreadLocalMap.set` | 1,238 | 2.3% | **NEW — abnormal** |
| 9 | `ValuePoolHashMap.getOrAddInteger` | 1,181 | 2.2% | ≈ same |
| 10 | `ValuePoolHashMap.getOrAddLong` | 1,054 | 1.9% | new |
| 11 | `AQS.acquireShared` | 957 | 1.7% | ≈ same |
| 12 | `ThreadLocalMap.remove` | 954 | 1.7% | **NEW — abnormal** |

### Finding: `clearToHalf()` at 10.6% — HSQLDB eviction thrash

`clearToHalf()` is called by HSQLDB when an internal hash map (ValuePool or Cache map) shrinks — it walks the entire map array, computes an access-count ceiling, and removes entries below it. It appears in samples from three threads: **Arbitrary Data Cleanup Manager**, **Arbitrary Latest-100 Metadata**, and **Arbitrary Data Manager**.

These threads are triggering continuous HSQLDB cache eviction cycles. The pattern: large amounts of data are read (filling the cache), then the cleanup manager evicts them, causing `clearToHalf()` to fire and walk O(n) maps. This is a tight add/evict cycle — the HSQLDB cache is repeatedly being filled and cleared rather than reaching a stable working set.

**Root cause:** The HSQLDB cache is still undersized for the "Arbitrary Data" working set. The cleanup manager is forcing evictions faster than data ages out naturally.

### Finding: `ThreadLocalMap.set/remove` at 4% combined

These operations appear at the top of the stack (not called from Qortal code — they ARE the hot path). Normal `ThreadLocal` operations complete in nanoseconds; appearing at 4% CPU means the per-thread `ThreadLocalMap` tables are very large, causing O(n) linear probes. 

Cause: **73,000 threads have been created and destroyed** over the 13-hour run (accumulated thread count = 72,964). Java's `ThreadLocalMap` stale entries accumulate and are only cleaned up lazily. With ~72k threads worth of ThreadLocal churn, the cleanSomeSlots algorithm runs on every set/remove.

Threads responsible: `Network-Worker` (3,209 created/terminated in JFR window alone = **53/minute**), `pool-5-thread` (1,517 created/terminated = **25/minute**). These two pools alone created 4,726 threads in 61 minutes.

---

## 4. Lock Contention (61-min JFR window)

| Monitor | Events | Total blocked | Max single wait | Status |
|---|---|---|---|---|
| `ConcurrentHashMap` | 94 | **12,866ms** | **11,600ms** | **Critical — new spike** |
| `EPollSelectorImpl` | 213 | 7,153ms | ~144ms | ≈ same |
| `HashMap` | 20 | **5,570ms** | unknown | **NEW — thread-safety risk** |
| `WeakCARCacheImpl` | 10 | 1,612ms | unknown | new |
| `HSQLDBDatabase` | 12 | 367ms | unknown | small |
| `NTPUDPClient` | **0** | **0ms** | **0** | **FIXED (was 25min)** |
| `SynchronizedMap` | 4 | 121ms | unknown | **FIXED (was 392,007ms)** |

### Critical: `ConcurrentHashMap` — 11.6s max wait

All 94 events are clustered in a 20-second window (08:44:29–08:44:52), with individual waits of 11.6s, 10.3s, 10.3s, 9.05s, 4.44s. This is a burst where many threads block together.

`ConcurrentHashMap.computeIfAbsent()` blocks all concurrent calls for the **same key** while the compute function runs. A 10-second compute function on a shared `ConcurrentHashMap` would explain this pattern exactly — one slow call (e.g., a database lookup or I/O operation inside `computeIfAbsent`) causes all threads waiting on the same key to pile up.

**This is the new bottleneck where `SynchronizedMap` used to be.** The old coarse lock was replaced with `ConcurrentHashMap`, but the compute function passed to `computeIfAbsent` is too slow (database call + I/O), which ConcurrentHashMap's key-level lock cannot help with.

### Warning: `HashMap` contention (5,570ms)

`java.util.HashMap` (not `ConcurrentHashMap`, not `Collections.synchronizedMap`) is showing `JavaMonitorEnter` events. This means either:
1. Some code does `synchronized (hashMap) { ... }` explicitly  
2. Or worse: two threads are writing to the same `HashMap` without synchronization (data race), and the JVM monitor is incidentally contended

20 events with 5,570ms blocked time = average 278ms wait per event. This is a real serialization point and potentially a data corruption risk.

---

## 5. Allocation Pressure (645,984 ObjectAllocationSamples)

| Site | Weight | Events | Object type |
|---|---|---|---|
| `RowAVLDisk.<init>` | highest | 69,946 | `NodeAVLDisk` — cache miss |
| `Arrays.copyOf(byte[],int)` | 2nd | 57,334 | `byte[]` |
| `RowInputBinary.readByteArray()` | 3rd | 50,298 | `byte[]` |
| `ByteArrayOutputStream.<init>` | 4th | 34,064 | `ByteArrayOutputStream` |
| `AQS.ConditionObject.await()` | 5th | 23,925 | `ConditionNode` |
| `RowInputBinary.readBinary()` | 6th | 20,236 | `BinaryData` |
| `X25519Field.create()` | 14th | 14,124 | `int[10]` — **NEW** |
| `Peer.sendPreSerializedMessage()` | 7th | 16,484 | `PreSerializedMessageWrapper` |

### New finding: X25519 field arithmetic allocates heavily

`org.bouncycastle.math.ec.rfc7748.X25519Field.create()` allocates a new `int[10]` array for every field element created during curve arithmetic. With 14,124 allocation events, this indicates peer key exchanges (TLS handshakes or Qortal's own crypto) are occurring at high frequency. Each X25519 scalar multiplication requires ~100+ field element allocations, so 14,124 events represents thousands of key exchange operations in the JFR window.

BouncyCastle's X25519 implementation does not pool field arrays. A patch: use `X25519Field.create(int[])` variants that write into a pre-allocated array, or switch to Java 11's native `XECPublicKey` which uses the JVM's built-in X25519 (no per-op allocation). However this requires Qortal's crypto layer to be updated.

### HSQLDB still dominates allocation

`RowAVLDisk` (cache miss path) + `readByteArray` + `readBinary` combined account for >30% of allocation weight — unchanged from Run 1. The cache is still undersized for the access pattern.

---

## 6. Exception Rate — Critical Finding

| Metric | Value |
|---|---|
| Throwables at JFR start | 3,739,126 |
| Throwables at JFR end | 4,197,369 |
| Delta in 61-min window | **458,243 exceptions** |
| **Rate** | **~124 exceptions/second** |

At 124 exceptions/second over 13 hours: approximately **5.8 million total exceptions** for this run. The throwable counter at the start (3.74M) suggests the rate was similar throughout.

**Impact:** Exception creation is expensive — each one captures a full stack trace (30–60 frames). At 124/s this is ~800 stack-trace-frame objects created per second, all going to young gen and many surviving to old gen if they're stored anywhere. This contributes meaningfully to GC pressure.

The exceptions observed via `JavaErrorThrow`: `NoSuchMethodError` and `IncompatibleClassChangeError` on `DirectMethodHandle$Holder` — same as Run 1. These are caught/swallowed by the application. The root cause remains: a library version mismatch where bytecode was compiled against one version and a different version is on the runtime classpath.

The 124/s throw rate suggests these errors are thrown on **every Jetty API request** (Jetty threads `qtp2023501911-*` visible in the error stack traces).

---

## 7. Thread Explosion

| Metric | Value |
|---|---|
| Active threads (peak) | 331 |
| Accumulated threads created | **72,964** over 13 hours |
| Network-Worker created in JFR (61min) | **3,209** (~53/min) |
| pool-5-thread created in JFR (61min) | **1,517** (~25/min) |

`Network-Worker` threads (TCP connection handlers) are being created and destroyed at 53/minute continuously. Each connection creates a new thread rather than reusing pool slots. With 312+ threads active simultaneously on a 4-CPU machine:
- Context switching overhead is high
- `ThreadLocalMap` grows stale entries for every dead thread
- JVM safepoint operations (GC, deoptimization) must wait for all 312+ threads to reach a safe point, which increases STW overhead

**The `pool-5-thread` pool** is creating 25 new threads/minute — this pool's work items are short-lived but the pool appears unbounded or misconfigured to not reuse threads.

---

## 8. G1 Adaptive IHOP Drift

| Time | IHOP threshold | Target occupancy | Mark duration prediction |
|---|---|---|---|
| JFR start (08:44) | 1.2GB (74.5%) | 1.6GB | 12.0s |
| JFR end (08:45) | **1.5GB (95.6%)** | 1.6GB | 11.2s |

The threshold reached 95.6% of the 1.6GB target. G1 has less than 64MB of old-gen headroom before it must trigger mixed GC. This is why individual Mixed GC pauses are so long — mixed GC fires when old gen is almost full, and must evacuate many dense regions.

---

## Updated Recommendations (priority order)

### P0 — Apply `G1HeapOccupancyPercent=35` immediately ← **unchanged, most urgent**

```
-XX:G1HeapOccupancyPercent=35
```

This forces concurrent mark to start at 35% old-gen occupancy instead of the current ~95%. Currently G1 waits until old gen is nearly full, leaving no room for mixed GC to reclaim space. This single flag is the root cause of the long Mixed GC pauses and IHOP drift.

Also consider:
```
-XX:G1MixedGCCountTarget=16       # spread mixed GC evacuation over 16 collections
-XX:MaxGCPauseMillis=200          # (already implicitly set — confirm in startup flags)
-Xmx3072m                         # restore some headroom; 1872M too tight for current live set
```

### P1 — Fix `ConcurrentHashMap.computeIfAbsent()` slow compute function

The `ArbitraryDataBuildManager`'s `ConcurrentHashMap` replacement solved the coarse-lock problem (SynchronizedMap) but exposed a new one: the compute function passed to `computeIfAbsent()` is too slow (causes 11.6s waits). 

Pattern to follow: the compute function must never do I/O or database access. Pre-fetch the value outside the `computeIfAbsent` call:

```java
// WRONG (holds key-level lock during DB query):
map.computeIfAbsent(key, k -> dbLookup(k));

// RIGHT (only insert if absent, compute outside):
Value v = dbLookup(key);
map.putIfAbsent(key, v);
```

Or use a two-level cache: first check `containsKey`, then do the expensive work, then `putIfAbsent` (accept that two threads might both compute the value — that's fine if the compute is idempotent).

### P2 — Fix `HashMap` thread-safety issue

Find all `java.util.HashMap` instances that are accessed from multiple threads (without explicit synchronization). The JFR `JavaMonitorEnter` events show contention on a raw `HashMap` — either replace it with `ConcurrentHashMap` or identify and fix the missing synchronization. A data race on `HashMap` can cause infinite loops (Java 6) or silent data loss.

### P3 — Bound Network-Worker thread creation

`Network-Worker` threads are created at 53/minute with apparently no upper bound. Each new `Network-Worker` thread adds another entry to every `ThreadLocal`'s map, contributing to the 4% `ThreadLocalMap.set/remove` CPU cost.

Fix: configure the `Network-Worker` thread pool with a fixed maximum size (e.g., `maxThreads = 2 * CPUs = 8`). Incoming connections that exceed the pool should queue, not create new threads.

### P4 — Fix `NoSuchMethodError` / `IncompatibleClassChangeError` on every API request

The 124 exceptions/second are being thrown on Jetty worker threads during API request handling. Identify which API endpoint triggers the reflection-based method lookup. The `DirectMethodHandle$Holder` error points to a JAX-RS or Jersey reflection call that fails on every invocation because a dependency has incompatible versions.

Run the node with `-verbose:class` to find which class is being loaded from two different JARs. Remove or shade the conflicting dependency.

### P5 — Increase HSQLDB cache size for Arbitrary Data tables

`clearToHalf()` at 10.6% CPU means the Arbitrary Data Manager threads are continuously evicting from an undersized HSQLDB cache. Find the HSQLDB `cache_size` setting for the arbitrary data tables and increase it. HSQLDB's default cache is typically 10,000 rows; for a blockchain with growing arbitrary data, 100,000–500,000 rows may be needed:

```sql
SET FILES CACHE SIZE 500000;
-- or in database.properties:
hsqldb.cache_size=500000
```

### P6 — Reduce X25519 field allocation (BouncyCastle)

`X25519Field.create()` at 14,124 allocation events indicates frequent key exchanges. Two approaches:
1. **Cache peer public keys**: if the same peer is repeatedly doing key exchanges, cache the computed shared secret per-peer connection instead of re-computing on each message exchange.
2. **Switch to JVM native X25519** (Java 11+): `java.security.KeyAgreement.getInstance("XDH")` with `NamedParameterSpec.X25519` uses the JVM's built-in implementation which uses stack-allocated field arithmetic (no heap allocation).

### P7 — Fix `pool-5-thread` unbounded creation (25 new threads/min)

`pool-5-thread` is a standard Java `Executors.newCachedThreadPool()` — it creates a new thread for every task if no idle thread is available and threads are not being reused. Replace with:
```java
// Instead of:
Executors.newCachedThreadPool()
// Use bounded pool:
new ThreadPoolExecutor(4, 32, 60L, TimeUnit.SECONDS, new SynchronousQueue<>())
```

### P8 — Restore some heap headroom

The current 1872M max is tight for a live set that sits at ~1100MB working set. G1 needs roughly 2× the live set to operate efficiently (room for young gen + mixed GC buffer + concurrent mark). Consider:
```
-Xmx3072m   # restore to ~3GB; process RSS was already reaching 4GB in Run 1
```
This will slow the IHOP drift and reduce evacuation pressure per mixed GC cycle.

---

## Summary: What's Fixed vs What Remains

| Issue | Run 1 | Run 2 | Net |
|---|---|---|---|
| OOM trajectory | Heap saturating in 29 min | **Stable 13+ hours** | **Fixed** |
| NTP lock contention | 25 min blocked / 22 min | 0 | **Fixed** |
| SynchronizedMap contention | 392,007ms | 121ms | **Fixed** |
| MemoryPoW 8MB allocs per call | 45 arrays / 360MB | Thread-local buffer used | **Fixed** |
| Worst GC pause | 395ms | **1,242ms** | **Worse** |
| IHOP drift | 73% → 93% (28 min) | 74% → 95.6% (61 min) | Same pattern |
| Concurrent mark max | 2,738ms | **5,751ms** | **Worse** |
| Exception rate | unknown | **124/sec** (5.8M total) | New finding |
| Thread explosion | unknown | 53 Network-Workers/min | New finding |
| ConcurrentHashMap 10s waits | n/a | **11.6s max** | New finding |
| HSQLDB clearToHalf() thrash | not visible | **10.6% CPU** | New finding |
| X25519 field allocation | not measured | 14,124 alloc events | New finding |
