# JFR Analysis — snap-140558.jfr

**Host:** masked info
**JVM:** OpenJDK 17.0.19, G1GC, max heap 3.64 GB
**Recording:** 2026-05-31 11:59:35 UTC, duration **384 seconds**, 4 chunks, 22 MB

---

## 1. CPU Load During Recording

| Metric | Observed range |
|---|---|
| Machine total CPU | 55 – 96% |
| JVM user CPU | 20 – 50% |
| JVM system CPU | 5 – 15% |
| System load avg (at snapshot) | **6.11 on 4 cores** (152% overloaded) |

The machine is CPU-saturated throughout the recording. JVM user CPU is consistently high and system CPU (kernel/IO) adds another significant fraction.

---

## 2. GC — The Primary Problem

### 2.1 Overview

| Metric | Value |
|---|---|
| Total GC events | **485** in 384 s (~1.26 GC/sec) |
| Young GC events | 248 |
| Old (concurrent) GC events | 237 |
| Total stop-the-world pause time | **30,514 ms** (8% of runtime) |
| Average pause | **62.9 ms** |
| **Maximum pause** | **5,930 ms** (5.93 seconds) |

8% of application time is spent paused for GC. The 5.93 s max pause is catastrophic for a peer-to-peer node — it drops connections and misses block propagation windows.

### 2.2 GC Cause Breakdown

| Cause | Count | % |
|---|---|---|
| **G1 Humongous Allocation** | **474** | **97.7%** |
| G1 Preventive Collection | 9 | 1.9% |
| G1 Evacuation Pause | 2 | 0.4% |

**97.7% of all GC events are triggered by humongous object allocations.** This is the smoking gun.

### 2.3 What Is a Humongous Allocation?

G1GC divides the heap into equal-sized regions. Any single allocation ≥ 50% of the region size is a "humongous object" and is handled specially:

- It is allocated directly in Old Gen (bypasses Eden entirely).
- G1 triggers a Young GC immediately to reclaim Old Gen space when humongous objects accumulate.
- Humongous objects fragment the heap and are expensive to free.

For a 3.64 GB max heap, G1 uses **2 MB regions** → humongous threshold = **1 MB**.

---

## 3. Root Cause: `Peer.readChannel()` Allocates 2 MB Buffers

### 3.1 The allocation

`src/main/java/org/qortal/network/Peer.java`, line 783:

```java
if (this.byteBuffer == null) {
    this.byteBuffer = ByteBuffer.allocate(Network.getInstance().getMaxMessageSize());
}
```

`getMaxMessageSize()` is computed once in `Network` as:

```java
maxMessageSize = 4 + 1 + 4 + BlockChain.getInstance().getMaxBlockSize();
```

`maxBlockSize` is set to **2,097,152** bytes in `blockchain.json`:

```json
"maxBlockSize": 2097152
```

So every `ByteBuffer.allocate` call here allocates **2,097,161 bytes ≈ 2.000009 MB** — just above the 2 MB region boundary, and **double** the 1 MB humongous threshold.

### 3.2 Why this triggers constant GCs

- The buffer starts as `null` (line 720) and is freed (set to `null`) each time the buffer is empty after a complete message is consumed (line 847).
- Every peer connection that reads even a single byte triggers a fresh 2 MB humongous allocation.
- With 242+ live threads and active peer networking, this allocation fires continuously.
- The JFR `ObjectAllocationSample` records **15,985 samples** of `byte[]` originating from `Peer.readChannel()` line 783, with a sampled weight of **2.0 MB each** — the single largest allocation source in the entire recording (40.7% of all allocation samples are `byte[]`).

### 3.3 Impact chain

```
Peer connects / sends data
  → byteBuffer == null → allocate 2,097,161 bytes
    → humongous object → lands in Old Gen
      → Old Gen fills → G1 Young GC fires (cause: "G1 Humongous Allocation")
        → STW pause 63 ms avg, up to 5,930 ms max
          → application stalls, connections time out
```

---

## 4. CPU Hotspots

### 4.1 Java execution samples (8,366 total)

| Thread | Samples | % |
|---|---|---|
| `Arbitrary Data` group (all threads) | ~4,172 | **49.9%** |
| `Synchronizer` | 995 | 11.9% |
| `pool-26-thread-1` | 562 | 6.7% |
| `AT States trimmer` / `pool-*` | 486 | 5.8% |
| `BlockMinter` | 412 | 4.9% |
| `pool-16-thread-1` | 382 | 4.6% |
| `Foreign Fee Manager-1` | 277 | 3.3% |

### 4.2 Native method samples (17,519 total)

| Thread | Samples | % | Note |
|---|---|---|---|
| `RMI TCP Connection` threads | 2,956 | 16.9% | JMX/diagnostic traffic |
| `HttpClient-1-SelectorManager` | 1,478 | 8.4% | |
| `qtp406511188-272/273/274` | ~4,431 | 25.3% | Jetty HTTP threads (SSL) |
| `grpc-nio-worker-ELG-1-2/3/4` | ~4,431 | 25.3% | gRPC NIO workers |
| `Network-IO` | 1,320 | 7.5% | |
| `NetworkData-IO` | 1,247 | 7.1% | |
| `Arbitrary Data` threads | 570 | 3.3% | |

The Jetty + gRPC NIO workers dominate native samples, indicating heavy network I/O in kernel space (epoll/select syscalls).

### 4.3 Arbitrary Data subsystem — what it is actually doing

The execution samples for all Arbitrary Data threads point to the same HSQLDB call chain:

```
QuerySpecification.buildResult()
  → RangeVariable$RangeIteratorMain.next()
    → IndexAVL$IndexRowIterator.getNextRow()
      → IndexAVL.next()
        → NodeAVLDisk.getLeft/getRight/getParent()
          → RowStoreAVLDisk.get()
            → DataFileCache.getFromFile()   ← disk read under WriteLock
              → DataFileCache.get()
```

This is a **full AVL index scan on a disk-backed HSQLDB table**, repeated continuously by multiple threads (`Arbitrary Data Manager`, `Arbitrary Index Cache Timer Task`, `Arbitrary Data Cleanup Manager`, `Arbitrary Data Cache Manager`). The `ReentrantReadWriteLock$WriteLock.lock()` on `DataFileCache` causes cross-thread contention on every disk read.

Additionally, `Cache.cleanUp()` / `Cache.preparePut()` appear in samples, meaning the HSQLDB row cache is under constant eviction pressure — it cannot hold the working set in memory.

### 4.4 Foreign Fee Manager — EdDSA in a hot path

`Foreign Fee Manager-1` (277 Java execution samples + 12 native samples) is burning CPU on:

```
Crypto.verify()
  → Ed25519.implVerify()
    → Ed25519.scalarMultStraus128Var()
      → Wnaf.getSignedVar()
```

Ed25519 signature verification is expensive on aarch64 without hardware acceleration. If `processForeignFeesImportQueue()` is verifying signatures in a tight scheduler loop, it should batch or throttle.

---

## 5. Object Allocation Profile

### 5.1 Allocation sample type breakdown (85,503 total samples)

| Type | Samples | % | Primary source |
|---|---|---|---|
| `byte[]` | **34,837** | **40.7%** | `Peer.readChannel()` (2 MB buffers) + HSQLDB binary reads |
| `NodeAVLDisk` | 6,278 | 7.3% | HSQLDB index node reads |
| `Peer$PreSerializedMessageWrapper` | 5,013 | 5.9% | Outbound peer message serialization |
| `AQS$ConditionNode` | 4,505 | 5.3% | Lock condition queue churn |
| `Object[]` | 3,974 | 4.6% | General |
| `int[]` | 3,554 | 4.2% | HSQLDB / general |
| `RowAVLDisk` | 1,890 | 2.2% | HSQLDB row reads |
| `BinaryData` | 1,687 | 2.0% | HSQLDB binary column data |
| `Long` | 1,510 | 1.8% | Autoboxing |

### 5.2 Promotion to Old Gen (PromoteObjectInNewPLAB)

- **66,987** total promote events; **60,064 (89.7%) tenured = true**.
- Nearly 90% of promoted objects survive to Old Gen, confirming the working set is too large for Young Gen to absorb.
- `plabSize = 1.0 MB` appears 4,989 times — large 1 MB PLAB chunks being allocated in Old Gen for `byte[]`, `BinaryData`, and `NodeAVLDisk` promotions.

### 5.3 Old Object Sample (741 events — long-lived objects)

All 741 OldObjectSample events record `object = [` (arrays), consistent with the 2 MB `byte[]` buffers accumulating in Old Gen between concurrent GC cycles.

---

## 6. Memory State

| Pool | Used | Committed | Max |
|---|---|---|---|
| G1 Eden Space | 64 MB | 360 MB | dynamic |
| G1 Old Gen | 1.19 GB | 1.54 GB | 3.64 GB |
| G1 Survivor Space | 44 MB | 44 MB | dynamic |
| Metaspace | 76 MB | — | unlimited |
| CodeHeap (all) | 28.8 MB | — | — |

- **Old Gen grew from 897 MB → 1.19 GB** (+300 MB) in ~3 minutes of the live observation window.
- G1 Eden has 360 MB committed but only 64 MB used — G1 is not utilising Eden efficiently because most large allocations skip it entirely (humongous path).
- Host free physical memory: **74.7 MB of 7.28 GB**. JVM committed virtual memory (7.52 GB) exceeds total RAM — the OS is paging.

---

## 7. Thread State Snapshot

| State | Count |
|---|---|
| RUNNABLE | 20 |
| WAITING | 124 |
| TIMED_WAITING | 98 |
| Live total | 242 (peak 270, 860 started) |

- No deadlocks.
- No blocked threads.
- The high WAITING + TIMED_WAITING count with non-trivial CPU burn (seen in the JMX sample) is explained by threads that loop through work, block briefly on a lock or park, and immediately resume — appearing "waiting" in a point-in-time snapshot but consuming real CPU over time.

---

## 8. Summary of Findings

| # | Finding | Severity |
|---|---|---|
| 1 | `Peer.readChannel()` allocates a 2 MB `ByteBuffer` per message cycle; humongous threshold is 1 MB → 97.7% of GCs triggered by this | **Critical** |
| 2 | Max GC pause of **5.93 seconds**; 8% of runtime in STW pauses | **Critical** |
| 3 | Old Gen growing ~100 MB/min; Full GC imminent if growth continues | **High** |
| 4 | Physical RAM exhausted (74 MB free); JVM committed memory exceeds RAM → OS paging | **High** |
| 5 | Arbitrary Data subsystem runs continuous full AVL index scans on disk-backed HSQLDB | **High** |
| 6 | HSQLDB `DataFileCache` under constant `WriteLock` contention across 4+ threads | **Medium** |
| 7 | `Foreign Fee Manager` verifies Ed25519 signatures in a tight scheduler loop | **Medium** |
| 8 | 89.7% of Young Gen promotions go directly to Old Gen — Young Gen is not filtering effectively | **Medium** |

---

## 9. Recommended Fixes

### Fix 1 — Eliminate humongous `ByteBuffer` allocations (Critical)

**Option A (JVM flag — immediate mitigation, no code change):**
Add `-XX:G1HeapRegionSize=4m` to the JVM launch flags. This doubles the region size, moving the humongous threshold from 1 MB to 2 MB. The 2,097,161-byte buffers drop below the threshold and allocate normally in Eden.

**Option B (code fix — correct solution):**
Do not set `byteBuffer = null` after each message. Keep the buffer allocated for the lifetime of the connection and `clear()` / `compact()` it instead. This means one 2 MB allocation per peer connection (not per message), and since it stays in Old Gen only once, it stops triggering repeated GC cycles.

**Option C (deeper fix):**
Allocate a small initial buffer and grow it only when a large message is detected from the header length. Most messages are small; only block messages need 2 MB.

### Fix 2 — Reduce HSQLDB full-scan frequency (High)

The Arbitrary Data threads run full `IndexAVL` scans on every tick. Determine whether the queries driving `Arbitrary Data Manager`, `Arbitrary Index Cache Timer Task`, and `Arbitrary Data Cleanup Manager` have appropriate indexes, and whether their scheduling interval can be increased.

### Fix 3 — Throttle Ed25519 verification in Foreign Fee Manager (Medium)

Batch signature verification or add a rate limit / back-pressure to `processForeignFeesImportQueue()` so it does not monopolize CPU on each scheduler tick.
