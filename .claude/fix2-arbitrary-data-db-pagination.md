# Fix 2 — Arbitrary Data DB-level Pagination

## Problem

JFR profiling showed the Arbitrary Data subsystem accounting for ~50% of all Java CPU samples. All four hot threads (`Arbitrary Data Manager`, `Arbitrary Index Cache Timer Task`, `Arbitrary Data Cleanup Manager`, `Arbitrary Data Cache Manager`) shared the same HSQLDB stack trace bottoming out in full AVL index scans:

```
QuerySpecification.buildResult()
  → IndexAVL$IndexRowIterator.getNextRow()
    → NodeAVLDisk.getLeft/getRight/getParent()
      → DataFileCache.getFromFile()   ← disk read under WriteLock
```

### Root cause: load-all-then-paginate-in-memory

Every manager followed the same anti-pattern:

1. Load **all rows** from `ArbitraryTransactions` into a Java `List` (no `LIMIT`)
2. Paginate through the list in memory with `.stream().skip(offset).limit(100)`
3. When the list is exhausted, reload everything from DB and start over

This caused the entire `ArbitraryTransactions` table to be scanned repeatedly:

| Manager | Frequency | Query | LIMIT |
|---|---|---|---|
| `ArbitraryDataManager.fetchAllMetadata()` | every 5 min | `getArbitraryTransactionSignaturesLite()` | none |
| `ArbitraryDataManager.fetchAndProcessTransactions()` | every 1 min | `getArbitraryTransactionSignaturesLite()` | none |
| `ArbitraryDataCleanupManager` | cycle ~30 min | `getLatestArbitraryTransactions()` | none |
| `ArbitraryIndexUtils.fillCache()` | timer (configurable) | `searchArbitraryResources(JSON, "idx-")` | none |

`getLatestArbitraryTransactions()` selects 22 columns including raw `data` payloads via a JOIN — particularly expensive.

---

## Fix

Pushed pagination down to the database with `LIMIT … OFFSET …`.

### Files changed

**`ArbitraryRepository.java`**
Added two new interface methods:
```java
List<ArbitraryTransactionData> getLatestArbitraryTransactions(Integer limit, Integer offset);
List<ArbitraryTransactionDataHashWrapper> getArbitraryTransactionSignaturesLite(Integer limit, Integer offset);
```
Existing no-arg and single-arg overloads are preserved (they delegate with `offset = null`) so no other callers are affected.

**`HSQLDBArbitraryRepository.java`**
Both methods now append `OFFSET ?` to the SQL when offset is non-null. Old overloads delegate to the new ones.

**`ArbitraryDataCleanupManager`**
- Removed the upfront `getLatestArbitraryTransactions()` call that loaded the entire table into heap at startup.
- Each loop tick (every 30 s) now calls `getLatestArbitraryTransactions(100, offset)` directly.
- When the page is empty the offset resets to 0, the first page is re-fetched, and `processedTransactions` is cleared — same cycle semantics, no full-table scan.

**`ArbitraryDataManager.fetchAndProcessTransactions`**
- `name == null` path (the hot `processAll()` branch): replaced upfront full load with `getArbitraryTransactionSignaturesLite(100, offset)` per iteration.
- `name != null` path: result is bounded by name (small), in-memory pagination kept unchanged.

**`ArbitraryDataManager.fetchAllMetadata`**
- Removed upfront `getArbitraryTransactionSignaturesLite()` full load.
- Each iteration opens a repository, fetches one page, deduplicates against `processedTransactions`, then closes the repository.

**`ArbitraryIndexUtils.fillCache`**
- Added `INDEX_RESOURCE_LIMIT = 5000` to the `searchArbitraryResources` call (previously `null` = unlimited).
- A `WARN` log is emitted if the limit is hit so the operator knows the index cache may be incomplete.

---

## Effect

Before: each manager cycle performed a full sequential scan of the entire `ArbitraryTransactions` table, loading all rows into the JVM heap, causing continuous `DataFileCache.getFromFile()` disk reads under `WriteLock`.

After: each 30-second tick fetches exactly 100 rows at the DB level. The table is never fully materialized in memory. HSQLDB can satisfy each page request by seeking directly to the offset position in the sorted index rather than scanning from the beginning.
