# GC Log + JFR Analysis — Full Re-Analysis

**Environment:** Java 17.0.19, G1GC, 4 CPUs, 7.4GB system RAM, heap 1536M–2984M  
**GC log window:** ~28.9 minutes (1731s), 305 GC events  
**JFR window:** 1327s (22 min), 6 chunks, start 17:36:20

---

## 1. GC Summary (gc.log — 305+ collections)

| Phase | Time range | GC count | Notable |
|---|---|---|---|
| Warmup | 0–22s | GC0–GC2 | Heap 84M→23M; first concurrent mark 43ms |
| Ramp | 22–240s | GC3–GC82 | MMU violations begin; 82 Mixed GCs clear old gen; mark cycles 738–910ms |
| Mid | 240–870s | GC83–GC183 | Long concurrent mark (2224ms at GC125); heap grows to 2232M max |
| Heavy mixed | 870–1180s | GC184–GC247 | Mixed GC pauses escalate to **395ms**; concurrent mark 2395ms→2738ms |
| Terminal | 1180–1731s | GC248–GC305 | Only Young GCs; heap monotonically fills; old gen 268 regions = 2144MB; heap after GC reaches 2310/2984M |

**Key numbers:**
- **77 MMU violations** (all at 151ms/150ms target) across the entire log — not 10+
- **Worst pauses:** 395ms (GC246 mixed), 361ms (GC190 mixed), 350ms (GC94 young-normal)
- **Concurrent mark times growing:** 43ms → 738ms → 910ms → 2224ms → 2395ms → **2738ms** — each cycle longer because the live object graph keeps expanding
- **Old gen grows monotonically** after mixed GCs finish: 0 → 268 regions. Young GC adds 2–4 old regions per collection with nothing ever reclaiming them.
- **Heap near saturation at log end:** after GC305, heap = 2413M→2310M in a 2984M capacity. Only ~674MB headroom. OOM within minutes of the log ending unless a concurrent mark + mixed GC cycle fires and succeeds.
- **Container RSS:** JFR ContainerMemoryUsage shows process consuming 3.0GB→4.0GB over the JFR window — far above the 2984M heap max, meaning substantial off-heap (direct buffers, native, metaspace).

---

## 2. CPU Hot Spots (ExecutionSample, 25036 samples — all RUNNABLE)

| Rank | Method | Samples | % | Change vs last |
|---|---|---|---|---|
| 1 | `hsqldb.map.BaseHashMap.getObjectLookup` | 2060 | 8.2% | ≈ same |
| 2 | `hsqldb.lib.ObjectComparator.equals` | 1752 | 7.0% | +1.7pp ↑ |
| 3 | `qortal.utils.Base58.divMod58` | 1744 | **7.0%** | **+4pp ↑↑ — more than doubled** |
| 4 | `hsqldb.persist.Cache.cleanUp` | 1171 | 4.7% | +1.1pp ↑ |
| 5 | `hsqldb.map.BaseHashMap.remove` | 1037 | 4.1% | +1.7pp ↑ |
| 6 | `AQS.releaseShared` | 835 | 3.3% | ≈ same |
| 7 | `AQS.acquireShared` | 787 | 3.1% | ≈ same |
| 8 | `ReentrantReadWriteLock.tryAcquireShared` | 478 | 1.9% | ≈ same |
| 9 | `hsqldb.map.ValuePoolHashMap.getOrAddInteger` | 434 | 1.7% | new |

`Base58.divMod58` is now #3 at 7% — it's grown proportionally as other work stays the same, meaning the number of address encode/decode calls is increasing with chain growth. **This is the fastest-growing hot spot.**

HSQLDB `Cache.cleanUp` remaining at #4 (now 4.7%) confirms the cache is consistently undersized for the working set.

---

## 3. Lock Contention (JavaMonitorEnter, 1175 events)

| Monitor class | Events | Total blocked | Max single wait | Root cause |
|---|---|---|---|---|
| `NTPUDPClient` | 593 | **1,516,245 ms (25.3 min)** | 8,170 ms | Shared single instance across all NTP pool threads |
| `Collections$SynchronizedMap` | 347 | **392,007 ms (6.5 min)** | 6,100 ms | `ArbitraryDataBuildManager` build queue map |
| `HSQLDBRepositoryFactory` | 2 | 62,900 ms | **32,400 ms** | AT state pruner/trimmer holding factory lock |
| `EPollSelectorImpl` | 173 | 5,067 ms | 144 ms | Network selector contention |
| `ArrayList` | 30 | 3,969 ms | 847 ms | Various |

**NTPUDPClient** remains the worst single contention point. 25 cumulative minutes of thread blocking in a 22-minute recording — effectively, NTP threads spend more time blocked than running.

**New finding — `ArbitraryDataBuildManager`:** A `Collections.synchronizedMap` is used as the arbitrary data build queue. Multiple threads call `isInBuildQueue` (containsKey), `addToBuildQueue` (put), and `ArbitraryDataBuilderThread.run` (isEmpty) on it simultaneously. Individual waits reach 6 seconds. Call chains:
- `ArbitraryDataReader.isBuilding()` → `ArbitraryDataBuildManager.isInBuildQueue()` → `SynchronizedMap.containsKey()`
- `ArbitraryDataBuilderThread.run()` → `SynchronizedMap.isEmpty()` (spun in a busy-wait pattern)

**New finding — AT state pruner/trimmer:** `HSQLDBATRepository.rebuildLatestAtStates()` (pruner) and `HSQLDBATRepository.trimAtStates()` (trimmer) each hold the HSQLDB repository factory lock for 30+ seconds. During this window, all other threads needing a database connection are blocked. This is why long pauses appear in worker threads during AT processing epochs.

---

## 4. Allocation Pressure (ObjectAllocationSample, 207668 samples)

| Site | Weight % | Event count | Object type |
|---|---|---|---|
| `RowAVLDisk.<init>` | 21.6% | 21815 | `NodeAVLDisk` — disk row read, cache miss |
| `RowInputBinary.readByteArray()` | 11.7% | 13577 | `byte[]` — per-column read |
| `RowInputBinary.readBinary()` | 5.5% | 6085 | `BinaryData` |
| `Peer.sendPreSerializedMessage()` | 4.3% | 4347 | `Peer$PreSerializedMessageWrapper` |
| `StringConverter.readUTF()` | 3.6% | 4582 | `String`/`char[]` |
| `RowStoreAVLDisk.get()` | 3.5% | 4123 | Row wrappers |
| `Arrays.copyOf(byte[],int)` | 2.9% | 16264 | `byte[]` |
| `ByteArrayOutputStream.<init>` | 2.7% | 8839 | `ByteArrayOutputStream` |

HSQLDB dominates allocation at >45% of weight. The cache miss → disk read → object allocation cycle (`RowAVLDisk` + `readByteArray` + `readBinary`) is the single largest allocation source.

---

## 5. Memory Retention — Root Cause of Old-Gen Saturation

This is the most important new finding. **Three distinct sources are filling old gen and not releasing:**

### 5a. Network IO — 2MB ByteBuffer per read (~416MB retained)

`Peer.readChannel()` calls `ByteBuffer.allocate(2097161)` (2MB+) on **every invocation** of the IO loop. The `Network-IO` thread has **113 retained** 2MB buffers aged 6+ minutes. `NetworkData-IO` has **95 more**. That is **208 × 2MB = 416MB** stuck in old gen from network receive buffers alone.

Root cause: `readChannel()` allocates a fresh 2MB buffer each call instead of reusing a per-peer buffer. The per-call allocation easily escapes to old gen under G1 because it is large enough to be promoted quickly.

Allocation stack:
```
Peer.readChannel() line: 783
  → ByteBuffer.allocate(2097161)
    → HeapByteBuffer.<init>(int, int)  ← allocates 2MB on every call
```

### 5b. MemoryPoW — 8MB long arrays per verification (~360MB retained)

`MemoryPoW.verify2()` allocates `long[1048576]` (8MB) per call. Thread `pool-23-thread-1` has **26 retained** and `Thread-8` has **19 retained** — **45 × 8MB = 360MB** in old gen.

These are scratch buffers for PoW verification. If the verifier is called frequently (e.g., on every incoming block or transaction) and each call allocates a fresh 8MB array, the GC cannot collect them fast enough.

### 5c. Timer-2 growing ArrayList

`Object[47427]` in `Timer-2` thread, aged 7+ minutes, allocated via `ArrayList.add()`. This is the same unbounded-growth list identified previously — it has not been fixed. The array continues to grow each time the timer fires.

### Combined old-gen retention estimate

| Source | Objects | Size each | Total |
|---|---|---|---|
| Network-IO ByteBuffers | 208 | 2MB | ~416MB |
| MemoryPoW long arrays | 45 | 8MB | ~360MB |
| Timer-2 ArrayList | 1 (growing) | ~1.8MB at 47k entries | ~2MB |
| HSQLDB NodeAVLDisk | 12+ observed | variable | >100MB |
| **Total identifiable** | | | **~880MB** |

This 880MB of systematically unreclaimed live data explains why old gen fills to 2144MB (268 × 8M regions) and mixed GCs cannot reclaim it — it is all genuinely reachable, just never released.

---

## 6. Errors (JavaErrorThrow, 90 events)

- **85 × `NoSuchMethodError`** on `DirectMethodHandle$Holder` — same as before; bytecode manipulation library version mismatch.
- **5 × `IncompatibleClassChangeError`** — NEW. These indicate a class that was an interface at compile time is now a class at runtime (or vice versa). Suggests two different versions of the same library on the classpath. Can cause silent behavioral bugs, not just noise.

---

## 7. G1 Adaptive IHOP (Marking Trigger)

| Time | Occupancy | Threshold | Alloc rate | Mark duration |
|---|---|---|---|---|
| Start (17:36) | 568MB | 1.2GB (73%) | 12.5 MB/s | 5.88s predicted |
| End (17:58) | 2.1GB | 2.3GB (93%) | 4.0 MB/s | 5.42s predicted |

The IHOP threshold drifted from 73% to 93% of target occupancy. G1's adaptive algorithm is raising the threshold because old gen keeps growing — it is trying to delay concurrent mark starts to allow more old-gen to fill, which paradoxically makes each mixed GC cycle more expensive. Allocation rate dropped from 12.5 to 4.0 MB/s (GC is taking a larger share of CPU time).

---

## Recommendations (priority order)

### P0 — Stop the 2MB ByteBuffer leak (fixes ~416MB old-gen growth)

`Peer.readChannel()` must not allocate a new 2MB buffer per call. Fix: allocate one `ByteBuffer` per `Peer` instance (or use a pool) and reuse it across calls. The buffer should be cleared/compact()ed at the start of each read, not reallocated.

```java
// Peer.java — allocate once in constructor or as field:
private final ByteBuffer readBuffer = ByteBuffer.allocate(2097161);

// In readChannel():
readBuffer.clear();
channel.read(readBuffer);
```

### P1 — Pool MemoryPoW scratch buffers (fixes ~360MB old-gen growth)

`MemoryPoW.verify2()` should take the `long[]` scratch buffer as a parameter or use a thread-local pool. A `ThreadLocal<long[]>` initialized once per verifier thread costs nothing and eliminates the 8MB per-call allocation:

```java
private static final ThreadLocal<long[]> SCRATCH = 
    ThreadLocal.withInitial(() -> new long[1048576]);
```

### P2 — Fix NTP lock contention (removes 25min/22min of blocked time)

Give each `NTP$NTPServer` instance its own `NTPUDPClient`. The client is not expensive to create and is clearly not designed for sharing. This is unchanged from the previous recommendation but remains the most impactful threading fix.

### P3 — Replace SynchronizedMap in ArbitraryDataBuildManager

Replace `Collections.synchronizedMap(new HashMap<>())` with `ConcurrentHashMap`. The current pattern — multiple threads calling `isEmpty()`, `containsKey()`, `put()` under the same coarse lock — serializes all arbitrary data build operations. `ConcurrentHashMap` is drop-in for these operations and eliminates the 6.5 min contention total.

### P4 — Fix AT state pruner/trimmer lock duration

`HSQLDBATRepository.rebuildLatestAtStates()` and `trimAtStates()` hold the repository factory lock for 30+ seconds. These batch operations should either:
- Run on a dedicated background connection (not through the shared factory)
- Release and re-acquire the lock periodically (checkpoint approach)
- Run during off-peak windows if the blockchain allows

### P5 — Bound the Timer-2 ArrayList

Find the periodic task in `Timer-2` that calls `ArrayList.add()`. Cap its size (e.g., keep the last N entries) or replace it with a ring buffer. It has been growing for 7+ minutes with 47,427 entries and is never cleared.

### P6 — G1 tuning for current workload

```
-XX:G1HeapRegionSize=16m          # raise humongous threshold (2MB buffers won't be humongous at 16M region size)
-XX:G1MixedGCCountTarget=16       # spread mixed GC work over more collections
-XX:G1HeapOccupancyPercent=35     # start concurrent mark earlier (IHOP drifted to 93%!)
-XX:MaxGCPauseMillis=200          # match target to reality; 150ms is too tight for this heap size
-Xmx4096m                         # consider increasing max heap; process RSS already reaches 4GB
```

The most urgent flag is `-XX:G1HeapOccupancyPercent=35` — without it, G1 will keep waiting until 90%+ occupancy before starting concurrent mark, giving mixed GCs no room to work.

### P7 — Cache Base58 results

`Base58.divMod58` is at 7% CPU and growing. The growth implies encode/decode is called proportionally to chain state queries. A small LRU cache (e.g., Guava `CacheBuilder.maximumSize(10000)`) at the `Base58.encode/decode` boundary would flatten this entirely.

### P8 — Resolve IncompatibleClassChangeError

5 `IncompatibleClassChangeError` events indicate a classpath conflict (not just method signature mismatch like the NoSuchMethodErrors). Run with `-verbose:class` and look for duplicate class definitions loaded by different classloaders. This can cause subtle data corruption, not just noise.
