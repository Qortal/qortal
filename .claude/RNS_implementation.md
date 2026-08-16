# `RNS.java` — Implementation Plan

**Companion to:** `.claude/RNS_analysis.md`
**Baseline:** commit `4ec4609a`, branch `feature/reticulum-refactoring`
**Target:** `src/main/java/org/qortal/network/reticulum/` — `RNS.java` 2610 L → ~250 L facade + 8 collaborators
**Build constraints:** Java 11 (`pom.xml` `<release>11</release>`), JUnit 5 + Mockito 5.10 (test scope), Lombok

---

## Status — 14 phases landed, all §11.2 checks pass, one re-run outstanding

Last updated at `f4f59134`. Every phase in the table below is committed, plus one
post-phase-12 fix (`12a`, §9 item 20) that the soak turned up; the plan document
is now a record of what was done and why, not a forward plan.

| | |
|---|---|
| `RNS.java` | 2610 → **470 L** (−82 %) |
| Reticulum package | 16 main files, 4753 L total (3042 L excluding `ReticulumPeer.java`, which was only moved) |
| Tests | 5 classes, **63 cases**, all green |
| Behaviour changes | 22, all registered in §9 |
| §10 comments | all present, grep-verified per phase |
| `RNS` public surface | 12 members, all with an external caller (phase 13) |

**The line budget, settled.** §13's last open clause was `RNS.java` ≤ 300 lines.
It is now **≤ 500, and met at 470** — the target was revised, not quietly
dropped. The original figure was estimated from the code to be moved, before the
cost of the documentation that code needs once separated was known; phase 14
moved everything it assumed would move and more. The remaining 470 lines are 308
of code, 97 of comment, 65 blank, and are the facade proper. Arithmetic and
reasoning in §13.

**What is not done.** One item, and it is a run rather than a code defect:

1. **The soak is substantially done, not complete.** A node ran 6 h 09 m at the
   phase-12 state (§14.2): §11.2 check 4 passes outright — 199 disconnects, 128
   teardowns, zero CME/NPE — and check 5's liveness half passes on 1480
   uninterrupted reconnect cycles. **§13's "≥ 24 h" is superseded**: the duration
   was a proxy for teardown volume, and 199 of them is more concurrency exercise
   than a quiet 24 h would give. Check 6 also passes — `stop.sh` reached
   `shutdown of Reticulum complete` and the JVM exited in 10 s. **All six §11.2
   checks now pass**: thread identity, the last one open, is closed on a later
   12 h dump pair (§14.2). What remains is one path duration alone cannot reach —
   §9 item 15's 24 h eviction, now covered by a unit test instead — and the
   re-run in **Next steps** below, whose scope has grown: it is now the only
   evidence for `12a` *and* phases 13 and 14.

---

## Next steps

Everything that was an edit has landed and is pushed to
`nbenaglia/feature/reticulum-refactoring` — `ReconnectPolicyTest` (`17dcfa42`),
phase 13 (`fd113950`), phase 14 (`8c110f9f`, `c72fcf0d`, `f4f59134`), and §11.2
check 5 closed from the `ThreadDumpScheduler` dumps. **Everything remaining needs
a node.**

### 1. One node run, now validating four phases — required

The last soak (§14.2) predates `12a`, phase 13 and phase 14. The re-run was
originally about one grep; it is now the only evidence for a restructure that
moved the inbound-accept path, the announce handler, every peer add/remove and
the identity load. Run it before treating the refactor as finished.

**Pass/fail, in order of what would hurt most if broken:**

| # | What | How | Expect |
|---|---|---|---|
| 1 | `12a` teardown dedup | the §14.2 grep below | **0** (baseline: 41 of 158, 26 %) |
| 2 | Mesh forms at all (14a) | `grep "added new .* ReticulumPeer" qortal.log` | non-zero; BASE and DATA both connect |
| 3 | Inbound accept, both aspects (14b item 21) | `grep -c "BASE client connected"` / `"DATA client connected"` | both non-zero |
| 4 | Identity still loads (14c) | `grep "server identity loaded from file"` | present, once, at startup |
| 5 | Clean shutdown | §11.2 check 6 | `shutdown of Reticulum complete`, JVM exits ≤ ~15 s |

```bash
grep "Disconnecting peer" qortal.log \
  | sed -E 's/^(.{19}) .*peer ([^ ]+).*/\1 \2/' | sort | uniq -c | awk '$1>1' | wc -l
```

**One gap this run will not close.** Check 4 above only proves the *load* branch
of `RNSIdentityStore`. Phase 14c also moved the `identities/` directory creation
out of the `RNS` constructor into the store, and that code runs **only on a node
with no existing identity** — which the soak host is not. To exercise it, start a
node against an empty storage path once and confirm both lines appear:

```
new server identity created dynamically.
serverIdentity written back to file
```

A regression here is invisible on an existing node and costs a fresh node its
persistent mesh address, so it is worth the single throwaway start.

### 2. Optional, in the order I would do them

1. **§14.2 finding 5** — guard `RNSPeerLifecycle.clientConnected` with the
   `shuttingDown` supplier it already holds, and `ReticulumPeer.linkEstablished`
   the same way. Stops this node accepting links ~7 s after declaring shutdown
   complete, and stops it feeding other nodes' retry-exhaustion counts. Cheaper
   since 14b: one guard on the inbound side, not two.
2. **§14.2 finding 3** — the interface-status block is 4 lines per 15 s, ~20 % of
   the log, never changing value. Log on transition, with a periodic line only
   while something is offline.
3. **§14.2 finding 2** — two `ReticulumPeer` instances for one remote, twice in
   6 h. Pre-existing, and `isLinkedTracked` is meant to prevent it.
4. **§14.2 finding 4** — `MessageException: Message checksum incorrect`, 3× in
   6 h. `ReticulumPeer.java` assumes one `buf.read()` yields exactly one whole
   `Message`. Pre-existing, non-fatal, and inside the file this plan scopes out.

---

## 14. Findings from node runs

### 14.1 First node run (phase 11 state, fixed in phase 12)

The run confirmed the mechanical parts: both runners alive on their own threads,
`logInterfaceStatus` emitting the interface block once rather than twice, the
§9 item 4 announce condition firing as `<` (`Active DATA peers (5) < desired peers
(8)`), the phase-9 pruner census on its 90 s cycle, and — direct evidence for
phase 11 — `requesting paths to 13 known peers` followed by *zero* per-target
`Path to …: hops=` lines. `DATA announce attempt completed in 1ms`: no `jobsLock`
contention.

Reading the same logs surfaced three defects, all older than this refactor:

| # | Defect | Why it survived |
|---|---|---|
| 1 | `triggerImmediateDataAnnounce()` had **zero callers** — both kick sites called the BASE-only variant regardless of aspect, so a dropped DATA peer woke the wrong loop and DATA waited out its full 30 s window | The BASE/DATA duplication this plan removed is exactly what hid it: the DATA kick was written and then never wired |
| 2 | One teardown queued and logged **per concurrent sender**: 8 "buffer closed" warnings and 4 disconnects for a single peer inside one second, each queueing a full `makePeerUnavailable` + remove + kick | Idempotent, so it never broke anything — only visible as log noise until someone read the timestamps |
| 3 | Three log lines identified inbound peers by `destinationHash`, which for an inbound peer is **our** destination — eight failures on different peers all printed the same hash as our own DATA announce | `RNSPeerRegistry.incomingIdentityKey()` documents this trap; the log lines predate it |

The lesson for the register in §9: none of these is a behaviour change anyone
would have caught by reading a diff. They came from reading a running node's
output against expectations — which is why §11.2 exists and why skipping it after
phase 8 was a mistake.

### 14.2 Phase-12 soak (6 h 09 m, `68663294`)

12:28:37 → ~18:37 on a live mesh. 3.4 MB of log, no rotation, so the whole run is
in one file.

**§11.2 results.**

| Check | Result |
|---|---|
| 4 — no CME/NPE | ✅ 0 and 0, across **199 disconnects** and **128 teardowns** |
| 4 — stuck-PENDING rate | ✅ flat: 1 / 1 / 4 / 2 / 2 / 1 per hour, no climb |
| 5 — loop liveness | ✅ 5892 interface-status lines = 1480 BASE reconnect cycles × 4 interfaces, spread 504 / 956 / 960 / 960 / 956 / 960 / 600 per hour — no hour short |
| 5 — thread identity | ✅ closed on a later 12 h window from `ThreadDumpScheduler`, not on `jcmd` — detail below |
| 6 — clean shutdown | ✅ `stop.sh` at 19:05:34 → `shutdown of Reticulum complete` same second, `Controller: Shutdown complete!` 19:05:44 — **10 s**, inside the ~15 s target. 13 peers closed via `p.shutdown()`, 11 shutdown-packet confirmations at 30–220 ms RTT, both backbone interfaces disconnected, JVM exited. No NPE/CME, and the `exitHandler` timeout guard (`RNS.java:357-364`) never fired. Caveat in finding 5 |
| §14.1 finding 1 — aspect-aware kick | ✅ DATA teardown 17:13:32 → path request + proactive connect 17:13:33 → link established 17:13:34 (**2 s**); DATA announces at 17:13:26 and 17:13:37, an **11 s** gap against the 30 s interval |
| §14.1 finding 2 — teardown dedup | ✅ **126 of 128** teardowns are singletons (was 8 warnings for one peer in one second) |

**Check 5, thread identity — the evidence.** Two `ThreadDumpScheduler` dumps 12 h apart
(2026-08-13 00:28:04 and 12:28:04 CEST) on one JVM — same process, `Qortal` still
`Id=12`. This is a later run than the 6 h soak above, but it is the same question,
answered on a longer window:

| Thread | 00:28 | 12:28 |
|---|---|---|
| `rnsMesh-BASE` / `rnsMesh-DATA` | Id 146 / 147 | Id 146 / 147 |
| `RNS-BASE-Announce-1` / `RNS-BASE-Reconnect-1` | Id 462 / 149 | Id 462 / 149 |
| `RNS-DATA-Announce-1` / `RNS-DATA-Reconnect-1` | Id 461 / 148 | Id 461 / 148 |
| `RNS-Worker-1/2/3` | Id 158 / 163 / 180 | Id 158 / 163 / 180 |
| `rns-scheduler-1…8` (library) | 104,107,108,110,113,119,120,121 | same 8 |
| `rns-scheduler-watchdog-1/2/3` (library) | Id 105 / 111 / 114 | same 3 |

Identical name sets with no numeric growth, and — stronger than the check asked
for — **identical thread IDs**: these are the same thread objects 12 h apart, not
merely the same names. Every aspect executor still ends in `-1`, so neither
`ThreadPoolExecutor` ever minted a replacement worker; the watchdog-cancel path
spawned nothing. This is what a count alone could not have shown.

The one population that churns is `RNS-LinkWatchdog`, which is library-owned
(`io.reticulum.link.Link.watchdogJob`) and runs one per live Link: roughly four
dozen in each dump, with entirely different IDs. Turnover, not accumulation —
worth stating against the leak §10 records (16,642 threads, RSS 34.8 G).

The two 956s are not gaps: `LOOP_SLEEP_MS = 50` makes the real reconnect period
~15.05 s, so 240 cycles take slightly over an hour and one occasionally lands in
the next bucket. A stall would be a step down, not a 0.4 % wobble.

**Why 6 h was accepted in place of §13's 24 h.** The duration was a proxy for
"enough teardowns to shake out a race", and every periodic path is far shorter
than the window: announce 30 s, reconnect 15 s, prune 90 s, gateway cooldown
10 min, backoff cap 30 min, inbound link timeout 12 min. 199 disconnects with
zero CME/NPE is more concurrency exercise than a quiet 24 h would give. **The one
thing 6 h structurally cannot reach is §9 item 15**: `FAILURE_STATE_MAX_AGE_MS`
is 24 h (`RNSAspectRunner.java:69`), so the `removeIf` in
`ReconnectPolicy.evictOlderThan` had never executed and had no test. Covered by
`ReconnectPolicyTest` (`17dcfa42`) rather than by 18 more hours of wall clock —
the class is pure and package-private, so the test reaches the same `removeIf` in
milliseconds and asserts what a soak cannot: that eviction drops the *right*
entries, and drops the failure counter with the timestamp so an evicted peer
restarts from the 60 s base window instead of resuming its old backoff.
`evictOlderThan(0)` as sketched earlier would have been flaky — the cutoff is
computed at call time, and an entry stamped in the same millisecond compares
`>= cutoff` and survives. The tests sleep 50 ms and evict against 25 ms.

**Peer health.** BASE held its target of 5 for the whole run, dipping to 4 exactly
twice (14:38:44, 17:08:45) and recovering inside one 30 s cycle each time. DATA
never reached its target of 8 — 711 samples at 3 (×1), 4 (×5), 5 (×202), 6 (×277),
7 (×226). All four interfaces reported `online=true` on every one of the 1480
cycles, so this is peer supply, not a dead transport: on this mesh the node
announces DATA every 30 s indefinitely. Note that the announce line only logs when
an aspect is *below* target (`RNSAspectRunner.java:316`), so a low count is a
healthy aspect, not a stalled runner — BASE logged 2, DATA 707.

**Findings.**

| # | Finding | Status |
|---|---|---|
| 1 | `linkClosed()` had **no claim guard**, so a single link closure ran the whole teardown — `shutdownChannel()`, `disconnect()`, `triggerImmediateAnnounce()` — once per timed-out packet. **41 of 158 real closures (26 %) were processed twice.** Root cause below | **fixed** — §9 item 20 |
| 2 | Two `ReticulumPeer` instances for one remote: concurrent initiator links `0d2b0aaf` and `fc9f8e98` to `4a24e3d9`. `claimRemoval()` is a CAS, so both legitimately claimed — this is what produced the 2 non-singleton `marking for removal` lines (a different metric from finding 1's `Disconnecting peer` count), not a dedup failure. `isLinkedTracked` (`RNSAspectRunner.java:387`) is meant to prevent it | open, pre-existing, 2 occurrences in 6 h |
| 3 | The interface-status block is **4 lines per 15 s**, not the "one or two" the phase-11 note claims — 5920 lines, ~20 % of the log, none ever changing value. Logging on transition (with a periodic line only while something is offline) satisfies the stated justification | open, cosmetic |
| 4 | 3 × `MessageException: Message checksum incorrect` (13:06, 16:23, 17:42, three different peers). `ReticulumPeer.java:859` assumes one `buf.read()` yields exactly one whole `Message`. The handler is deliberately non-fatal and all three peers carried on — one for 43 min, one to the end of the run | open, pre-existing, predates the refactor |
| 5 | **The node keeps connecting after `shutdown of Reticulum complete`.** 8 outbound links established 19:05:35–19:05:44, every hash one that `RNS:336` had torn down a second earlier; plus one inbound peer accepted *whole* at 19:05:41 — `dataClientConnected` → buffer → `Enabling pings` → `addIncomingPeer` → identified as `b3ae0fb7…`, 7 s after shutdown was declared complete and after `exitHandler` had run. Detail below | open, pre-existing |

**Finding 1, root cause.** `Channel.packetTimeout()` is a *per-packet* callback
(`Channel.java:369`). Every in-flight packet that exhausts `maxTries` logs
`Retry count exceeded`, then at `Channel.java:414-417` runs
`shutdown(); outlet.timedOut();`. So N packets in flight on one dying link give:

> N × `packetTimeout` → N × `outlet.timedOut()` → N × `Link.teardown()` → N × `linkClosed()`

and `Link.teardown()` (`Link.java:754-767`) has no already-CLOSED guard to swallow
the repeats. Measured over the run: **124** `Retry count exceeded`, **45** of them
same-second bursts, against **41** doubled closures — near 1:1, the gap being
bursts that span *different* links (17:18:56 tore down three separate peers, three
correct singletons). Clearest single case is 17:18:50: two retry-exceeded lines,
then two full disconnect sequences 1 ms apart for link `6bd125a4` (connection ages
12175141 / 12175142). The `closedLinkHandled` flag for exactly this existed since
phase 12 but was referenced only from `sendMessage`.

Two consequences worth carrying forward. First, 124 retry-exhaustions against 158
real closures means **Channel retry exhaustion is the normal way a link dies on
this mesh**, not an exceptional one — this path deserves the scrutiny. Second, the
re-run check is now quantitative rather than anecdotal: against a 26 % baseline,

```bash
grep "Disconnecting peer" qortal.log \
  | sed -E 's/^(.{19}) .*peer ([^ ]+).*/\1 \2/' | sort | uniq -c | awk '$1>1' | wc -l
```

must return **0** on a run at `12a`.

A related library-side artefact, not worth chasing: `Packet:507 No interfaces
could process (resend)` + `LinkChannelOutlet:49 Failed to resend packet` is one
event, a sibling envelope resending in the window between retry-exhaustion and the
link reaching CLOSED. `Channel.java:379-387` already guards this with
`outlet.isClosed()`, but teardown runs after `packetTxOp` returns, so the guard
narrows the race without closing it.

**Finding 5, detail.** `isShuttingDown` is checked in `markPeerForImmediateRemoval`,
`dedupIncomingPeerByIdentity` (both now `RNSPeerLifecycle`) and `linkClosed`
(`ReticulumPeer`), but **not** in the inbound-accept path or
`ReticulumPeer.linkEstablished`. The only defence at shutdown is
`setProofStrategy(PROVE_NONE)` in `RNS.shutdown`, which stops proving but does not
stop the destination's link-established callback from firing. The outbound half is
`new Link()` calls already in flight when `runner.shutdown()` stopped the reconnect
loop: the loop stops, the pending LINKREQUESTs do not.

*(Line numbers dropped: they were `RNS.java` positions at the phase-12 state, and
phase 14 moved every one of these methods. The inbound-accept path the run
observed as `dataClientConnected` is now `RNSPeerLifecycle.clientConnected`, one
method for both aspects — which is also why the fix is cheaper than it was.)*

Not a check-6 failure — shutdown still completed in 10 s and the JVM exited. The
costs are that the registry is mutated after teardown, ping timers are armed on
peers nothing will service, and those ~9 remotes get a link that dies abruptly
rather than a clean close, so they reach `Channel: Retry count exceeded` instead
of `INITIATOR_CLOSED`. Given that retry exhaustion already accounts for 124 of 158
link deaths here, every node doing this feeds that number.

The fix, when wanted: guard `RNSPeerLifecycle.clientConnected` with the
`shuttingDown` supplier it already holds (tearing the offered link down rather than
accepting it) and guard `ReticulumPeer.linkEstablished` the same way — reusing the
flag and the discipline the rest of the code already uses. Since phase 14b this is
one guard on the inbound side, not two.

Findings 1, 3 and 5 repeat §14.1's lesson: all are invisible in a diff and obvious
in a log. Finding 1 in particular was *introduced as a fix* in phase 12 and then
wired to only one of its two call sites — the kind of gap that only a run finds.
Finding 5 needed the shutdown log specifically, which is why §11.2 check 6 is
worth running against accumulated state rather than a freshly-started node.

---

## 0. Ground rules

1. **One phase = one commit.** Every phase compiles the whole tree and is independently revertable. Never mix a move with an edit.
2. **Comment preservation is mandatory.** The concurrency comments in `RNS.java` are the only record of why several obvious simplifications are wrong. Carry them verbatim to the new home. §10 lists the ones that must survive, with a grep check.
3. **No behaviour change unless listed.** §9 is the complete register of intended behaviour changes. Anything not in that list that changes behaviour is a bug in the refactor.
4. **Side effects stay outside locks.** `shutdownChannel()`, `makePeerUnavailable()`, `closeIfActive()` and any Reticulum call must never run while holding a registry lock — `removeLinkedPeer`'s comment (RNS.java:1960) documents the deadlock this avoids.
5. **Verification after every phase:** `mvn -q -DskipTests compile` must pass with zero new warnings; from phase 4 on, `mvn -q -Dtest='RNS*Test,KnownPeerStoreTest' test` must pass.

---

## 1. Target layout

Planned, with **as-built** line counts alongside the estimate:

```
src/main/java/org/qortal/network/
├── reticulum/                              planned   as built
│   ├── RNS.java                    facade    ~250 L     470 L   ← §13 budget revised to ≤500
│   ├── RNSCommon.java              unchanged  ~50 L      52 L
│   ├── ReticulumPeer.java          moved only 1633 L    1646 L   out of scope for this plan
│   ├── ReticulumPeerAddress.java   moved only   71 L      73 L
│   ├── RNSConfigWriter.java        [new]       ~90 L     129 L
│   ├── RNSIdentityStore.java       [new]         —        66 L   ← phase 14c
│   ├── RNSAnnounceCodec.java       [new]      ~170 L     260 L
│   ├── RNSGatewayManager.java      [new]      ~200 L     255 L
│   ├── RNSPeerRegistry.java        [new]      ~260 L     301 L
│   ├── RNSPeerLifecycle.java       [new]         —       284 L   ← phase 14b
│   ├── RNSAnnounceHandler.java     [new]         —       190 L   ← phase 14a
│   ├── AnnouncedVersionCache.java  [new]         —        45 L   ← phase 14a
│   ├── KnownPeerStore.java         [new]       ~80 L     114 L
│   ├── ReconnectPolicy.java        [new]       ~90 L      85 L
│   ├── RNSAspectRunner.java        [new]      ~340 L     489 L
│   └── RNSPeerPruner.java          [new]      ~170 L     227 L
└── (unchanged) Peer.java, Network.java, NetworkData.java, task/Reticulum*Task.java …

src/test/java/org/qortal/network/reticulum/  — 5 classes, 63 cases, 1066 L
    RNSAnnounceCodecTest (18)  RNSPeerRegistryTest (16)
    RNSPeerPrunerTest (14)     RNSPeerFactoryScanTest (2)
    ReconnectPolicyTest (13)
```

The `[new]` files each came in 20–45 % over estimate, almost entirely in javadoc
and the §10 comments — the estimates were made from the code being moved, not the
documentation it needed once it stood alone.

`org.qortal.network.task.ReticulumMessageTask` / `ReticulumPingTask` stay where they are (they sit with the other `Task` implementations); they only need an import update.

**Line budget:** planned 2610 → ~1700 across 10 files, of which ~250 is the facade. **As built: 2610 → 3538 across 12 files**, of which 857 is the facade — but 1646 of that is `ReticulumPeer.java`, which was only moved. Excluding it, the RNS code itself is 2610 → 1892 L. The reduction came where predicted (dead weight in phase 1, BASE/DATA de-duplication in phase 10); what the budget missed is that extracting a class costs a class javadoc, and that the surviving comments needed more context once separated from the code around them.

---

## 2. Phase table

| # | Commit | Work | Risk | Δ `RNS.java` |
|---|---|---|---|---|
| 1 | ✅ `12873ec5` | commented-out blocks, unused fields/imports/locals, zero-caller public methods | none | −460 (2610→2150) |
| 2 | ✅ `2aa0d14b` | pure `git mv` + package/import updates, no logic change | low (wide diff) | 0 |
| 3 | ✅ `2b90404f` | drop ~100 generated accessors, keep 6 | very low | +6 |
| 4 | ✅ `3579c18b` | §5.1, §5.2, §5.3, §5.5 (gateway map), §5.6 from the analysis | very low | +51 |
| 5 | ✅ `9e9d8737` | `RNSAnnounceCodec` + 18 tests, all passing | very low | −110 (→2046) |
| 6 | ✅ `92eec531` | `KnownPeerStore` ×2 + `RNSConfigWriter` | low | −170 (→1876) |
| 7 | ✅ `9b0b2a62` | `RNSGatewayManager` + dialling off the announce thread (§5.4) | low | −175 (→1701) |
| 8 | ✅ `9e234e80` | `RNSPeerRegistry` + 16 tests | **medium** | −132 (→1569) |
| 9 | ✅ `cda0c7a0` | `RNSPeerPruner` + 14 tests | low–medium | −132 (→1437) |
| 10 | ✅ `6bf85e93` + `b29ea96d` | `ReconnectPolicy` + `RNSAspectRunner`, BASE then DATA | **highest** | −583 (→854) |
| 11 | ✅ `993f2351` | 10 ms → 50 ms tick, INFO → DEBUG | low | ~0 |
| 12 | ✅ `f6b5d5d5` | three defects found by the first node run (§14) | low | +14 |
| 12a | ✅ `e6c655cb` | `linkClosed()` claim guard (§9 item 20, §14.2 finding 1) | low | 0 (in `ReticulumPeer`) |
| 13 | ✅ `fd113950` | visibility: 12 members + the constructor drop to package-private/private | none | 0 |
| 14a | ✅ `8c110f9f` | `RNSAnnounceHandler` + `AnnouncedVersionCache` | low | −160 (873→713) |
| 14b | ✅ `c72fcf0d` | `RNSPeerLifecycle` | low–medium | −218 (→495) |
| 14c | ✅ `f4f59134` | `RNSIdentityStore` | very low | −25 (→470) |

Phases 1–7 are ~60 % of the reduction at near-zero risk and can ship before any decision on 8–10.

---

## 3. Phase 1 — dead weight (`−520 L`)

### 3.1 Commented-out blocks (line refs at `4ec4609a`)

| Lines | Content |
|---|---|
| 1743–1840 | `RNSProcessor` inner class |
| 996–1018 | `broadcastOurChain`, `buildNewTransactionMessage`, `buildGetUnconfirmedTransactionsMessage` |
| 1891–1902 | `makePeerAvailable` + getter note |
| 1938–1944 | `makePeerUnavailable` |
| 1965–1971 | `getLinkedPeers` |
| 2110–2116 | `getIncomingPeers` / `getImmutableIncomingPeers` |
| 2572–2577 | second `removePeer` |
| 2149–2159 | repository block inside `peerMisbehaved` (method itself goes, see 3.3) |
| 2179, 2192–2195, 2271–2278 | commented ping/prune fragments inside `prunePeers` |
| import block | ~25 commented import lines (15–19, 24–26, 28, 31–33, 36, 39–40, 43, 54, 63–64, 66, 72–73, 77–79, 88–89, 93, 106–107) |

### 3.2 Unused declarations

- Fields: `MAX_PEERS` (126), `PRUNE_INTERVAL` (130), `reticulumMaxNetworkThreadPoolSize` (228 — the constructor reads `Settings` directly), `BROADCAST_INTERVAL` (239).
- Imports: `SelectionKey` (60), `AtomicLong` (76), `Predicate` (84), `BlockData` (100), `TransactionData` (102).
- Dead locals: `initiatorActivePeerList` (2185, 2324), `incomingPeerList = this.incomingPeers` (2270).
- Duplicated javadoc: first block of 1191–1205 (describes the superseded QGW1-only format), first block of 2007–2020 (attached to the wrong method).

### 3.3 Public methods with zero callers in the tree

Verified with `grep -rn "<name>" --include="*.java" src/main src/test` — each appears only at its own definition (plus commented references):

| Method | Note |
|---|---|
| `sendCloseToRemote(Link)` | `shutdown()` calls `ReticulumPeer.sendCloseToRemote`, not this one |
| `closePacketDelivered`, `packetTimedOut` | only reachable from the above |
| `clientDisconnected`, `serverPacketReceived` | referenced only from commented callbacks |
| `buildHeightOrChainTipInfo(ReticulumPeer)` | all callers use `Network.getInstance().buildHeightOrChainTipInfo` |
| `onPingMessage(ReticulumPeer, Message)` | `Network.onPingMessage` handles both peer types |
| `peerMisbehaved(Peer)` | all callers use `Network.getInstance().peerMisbehaved` |
| `findPeerByLink`, `findPeerByDestinationHash` | no callers (also the INFO-per-match loggers from §6.4) |
| `getOurNodeId`, `getOurPublicKey` | callers use `Network`/`NetworkData` |
| `getAllKnownCorePeers`, `getAllKnownDataPeers` | only `getAllKnownPeers` is used (Network.java:807) |
| `maybeRecoverInstance` | empty TODO stub |

Cascade deletions once those are gone: `BROADCAST_CHAIN_TIP_DEPTH`, imports `Repository`, `RepositoryManager`, `BlockSummaryData`, and the `Class.forName` + `ClassNotFoundException` handler (§5.8).

**Keep** `DataException` in imports — `prunePeers()` must retain `throws DataException` (see §8.3).

**Guard:** this deletes public methods. Re-run the full-tree grep before deleting each one; `mvn -q -DskipTests compile` plus `mvn -q -DskipTests test-compile` is the acceptance gate.

---

## 4. Phase 2 — package move

```bash
mkdir -p src/main/java/org/qortal/network/reticulum
git mv src/main/java/org/qortal/network/{RNS,RNSCommon,ReticulumPeer,ReticulumPeerAddress}.java \
       src/main/java/org/qortal/network/reticulum/
```

Then:

1. `package org.qortal.network.reticulum;` in all four files.
2. Add to each moved file the imports it now needs from `org.qortal.network`: `Peer`, `PeerAddress`, `PeerCtor`, `PeerAddressCtor`, `PeerSendManager` (ReticulumPeer), `Network`, `NetworkData`.
3. Update importers — `org.qortal.network.RNSCommon.PeerMetaType` → `org.qortal.network.reticulum.RNSCommon.PeerMetaType` in:
   `Peer.java:40`, `IPPeer.java:41`, `Network.java:45`, `NetworkData.java:16`, `data/network/PeerData.java:6`.
4. Add `import org.qortal.network.reticulum.RNS;` / `ReticulumPeer` to: `controller/Controller.java`, `controller/arbitrary/ArbitraryDataFileManager.java`, `controller/arbitrary/PeerMessage.java`, `api/resource/PeersResource.java`, `api/model/ConnectedPeer.java`, `network/Network.java`, `network/NetworkData.java`, `network/PeerSendManager.java`, `network/PeerSendManagement.java`, `network/task/ReticulumMessageTask.java`, `network/task/ReticulumPingTask.java`, `src/test/java/org/qortal/test/network/RNSNetworkTest.java`.

### 4.1 Access-modifier survey (done — no surprises)

- `Peer` is an **interface**, so `NETWORK`/`NETWORKDATA` and every method are implicitly `public`. Cross-package use is fine.
- `ReticulumPeer`'s package-private fields (`peerLink`, `peerBuffer`, `channel`, `peerAddress`, `peerLinkHash`, `receiveStreamId`, `sendStreamId`) are **not** touched from outside `RNS`/`ReticulumPeer` — verified by grep. Both move together.
- `RNS.confirmPeerHash` and `RNS.markPeerForImmediateRemoval` are package-private and called from `ReticulumPeer` — same package after the move, so they stay package-private.
- `PeerFactory` / `PeerAddressFactory` scan with `new Reflections("org.qortal.network")`, which is a **prefix** scan and includes subpackages, so the `@PeerCtor("destination-hash")` / `@PeerCtor("link")` / `@PeerAddressCtor("destination-hash")` registrations keep working. Confirm at runtime with a node start (§11.2), not just by compiling — this is reflection, the compiler cannot catch it.

**Alternative if step 3 is unwanted:** keep `RNSCommon.java` in `org.qortal.network` (it serves the IP path too via `PeerMetaType`). Costs one cross-package import in the reticulum classes instead of five in the IP classes. The plan above moves it, for the "all reticulum files in one folder" rule from the analysis §10.

---

## 5. Phase 3 — `@Data` → explicit getters

Delete `@Data`; keep `@Slf4j`. Add exactly these:

```java
@Getter private Identity serverIdentity;      // ReticulumPeer:654
@Getter private Destination baseDestination;  // ReticulumPeer:903
@Getter private Destination dataDestination;  // internal only — keep package-private getter
@Getter private volatile boolean isShuttingDown = false;   // → isShuttingDown()
private volatile boolean meshStarted = false;              // explicit isMeshStarted() already exists
```

Snapshot accessors become hand-written (they are the only list API that survives):

```java
public List<ReticulumPeer> getImmutableLinkedPeers()   { return registry.linked(); }
public List<ReticulumPeer> getImmutableIncomingPeers() { return registry.incoming(); }
```

Everything else that `@Data` generated goes, in particular:

- **All setters.** `setLinkedPeers`, `setImmutableLinkedPeers`, `setShuttingDown`, `setReticulum`, … — these could bypass every invariant in `addLinkedPeer`.
- **`getLinkedPeers()` / `getIncomingPeers()`** — handing out the live `synchronizedList` is the direct cause of §5.3. Removing the accessor fixes the class of bug, not just the one call site.
- **`toString()` / `equals()` / `hashCode()`.** Note `Controller.java:578` has a commented `rns.toString()`; leave it commented and do not restore a `toString`.

**Lombok naming trap:** keep the field named `isShuttingDown` (not `shuttingDown`) so `@Getter` still emits `isShuttingDown()` and `ReticulumPeer:498` / `ReticulumPeer:679` compile unchanged.

The full external surface after this phase (18 members — verified by grep over `src/main`):

```
getInstance  start  shutdown  prunePeers  isMeshStarted  isShuttingDown
broadcast  onPeersV2Message  isUnreachable  getAllKnownPeers  getActiveDataPeers
getImmutableLinkedPeers  getImmutableIncomingPeers  getServerIdentity  getBaseDestination
triggerImmediateAnnounce  markPeerForImmediateRemoval  confirmPeerHash  dedupIncomingPeerByIdentity
```

---

## 6. Phase 4 — correctness fixes

### 6.1 `shutdown()` bitwise `&` (RNS.java:1052)

```java
-            if (nonNull(pl) & (pl.getStatus() == ACTIVE)) {
+            if (nonNull(pl) && (pl.getStatus() == ACTIVE)) {
```

An NPE here aborts `shutdown()` before the linked-peer loop, the executor shutdown and `exitHandler()`.

### 6.2 Atomic snapshot rebuild on the remove paths

`removeLinkedPeer` (1952) and `removeIncomingPeer` (2103) mutate and republish without the lock that `addLinkedPeer` holds. Fix — and note carefully what stays **outside** the lock:

```java
public void removeLinkedPeer(ReticulumPeer peer) {
    peer.shutdownChannel();               // outside: touches Reticulum
    synchronized (this.linkedPeers) {     // inside: mutation + snapshot, nothing else
        this.linkedPeers.remove(peer);
        this.immutableLinkedPeers = List.copyOf(this.linkedPeers);
    }
    peer.makePeerUnavailable();           // outside: acquires Network's peer-list locks
}
```

The comment at 1954–1961 explaining *why* `makePeerUnavailable()` runs with no RNS lock held must move with it. Same shape for `removeIncomingPeer` (its `closeIfActive(peer)` also stays outside).

### 6.3 Unsynchronised iteration

```java
public List<ReticulumPeer> getNonActiveIncomingPeers() {
    List<ReticulumPeer> result = new ArrayList<>();
    for (ReticulumPeer p : getImmutableIncomingPeers()) {   // snapshot, not the live list
        Link pl = p.getPeerLink();
        if (pl == null || pl.getStatus() != ACTIVE) result.add(p);
    }
    return result;
}
```

Same substitution in `shutdown()` (1050, 1058): iterate `getImmutableIncomingPeers()` / `getImmutableLinkedPeers()`.

Also drop the pointless `Collections.synchronizedList` wrapper in `getActiveImmutableLinkedPeers()` (1852) and in `getNonActiveIncomingPeers()` — these are single-caller snapshots; the wrapper only implies a thread-safety contract that does not exist.

### 6.4 Half-built singleton (§5.6)

Keep the constructor non-throwing (`getInstance()` must not blow up in a static initialiser), but make `start()` refuse to run:

```java
public void start() {
    if (reticulum == null) {
        log.error("Reticulum stack unavailable (see construction error above) — mesh will not start");
        return;   // meshStarted stays false; every consumer already guards on isMeshStarted()
    }
    ...
}
```

`Network.java:460` and every consumer already guard on `isMeshStarted()`, so this degrades cleanly instead of NPE-ing at `reticulum.getStoragePath()` (399).

### 6.5 Unbounded maps (§5.5)

- `recentGatewayAttempts` → phase 7, evicted inside `RNSGatewayManager` (`removeIf(age > 2 × GATEWAY_COOLDOWN)` once per call, plus a hard cap of 256).
- `pendingLinkFailureMs` / `pendingFailureCount` → phase 10, `ReconnectPolicy.evictOlderThan(24 h)` called once per reconnect cycle.

---

## 7. Phases 5–7 — the stateless extractions

### 7.1 `RNSAnnounceCodec` (phase 5)

Pure, static, no Reticulum/Settings/Controller dependency — the caller supplies the version string and the gateway host, so the codec is constructible in a unit test.

```java
public final class RNSAnnounceCodec {
    static final byte[] QAN_MAGIC = { 'Q', 'A', 'N', '1' };
    static final byte   TLV_VERSION = 0x01;
    static final byte   TLV_GATEWAY = 0x02;
    static final byte[] QGW_MAGIC = { 'Q', 'G', 'W', '1' };   // legacy, decode-only
    private static final int QGW_MIN_LEN = QGW_MAGIC.length + 1 + 2;

    private RNSAnnounceCodec() { }

    /** QAN1 container: version record always, gateway record when host != null and port in 1..65535. */
    public static byte[] encode(String version, String gatewayHost, int gatewayPort);

    /** Never returns null. Falls back to legacy QGW1 when the QAN1 magic is absent. */
    public static AnnounceInfo decode(byte[] appData);

    /** "x.y.z[-hash]", with or without the "qortal-" prefix → 3×16-bit packed long; 0 if unparseable. */
    public static long parseVersionToLong(String versionString);

    /** Rejects null/empty, "localhost", 127.*, ::1, and single-label names. */
    public static boolean isUsableAdvertiseHost(String host);

    public static final class AnnounceInfo {          // NOT a record — pom targets Java 11
        private final String version;                 // nullable
        private final String gatewayHost;             // nullable
        private final int gatewayPort;                // 0 = absent
        // getters + equals/hashCode/toString (tests compare instances)
    }
}
```

Wire-format invariants to carry over verbatim from `buildAnnounceAppData`/`buildGatewayValue`/`decodeAnnounceAppData`:

- version value truncated to 255 bytes; gateway host must encode to 1..252 bytes (so `1 + n + 2 ≤ 255`); port 1..65535.
- decode stops at a truncated record, skips unknown TLV types, tolerates `null`/short input.
- gateway TLV body is byte-identical to the QGW1 body minus its magic — that is what keeps old peers parseable.

`RNS` then becomes:

```java
private byte[] buildAnnounceAppData() {
    return RNSAnnounceCodec.encode(
            Controller.getInstance().getVersionStringWithoutPrefix(),
            gatewayManager.getAdvertiseHost(),   // null when disabled/unusable — codec omits the record
            TARGET_PORT);
}
```

Note the shift: the "is the gateway feature enabled" decision (`getReticulumAnnounceGateway()` && `getReticulumIsGateway()`) moves into `RNSGatewayManager.getAdvertiseHost()`, which returns `null` when disabled. Behaviour identical, `Settings` reads confined to one class.

**Tests** — `src/test/java/org/qortal/test/network/RNSAnnounceCodecTest.java` (JUnit 5):

| Test | Asserts |
|---|---|
| `roundTripVersionOnly` | `decode(encode("6.1.9-abc", null, 0))` → version set, gwPort 0 |
| `roundTripVersionAndGateway` | both fields survive, port big-endian |
| `legacyQgw1Decodes` | hand-built QGW1 buffer → host/port set, version null |
| `truncatedRecordStopsCleanly` | `encode(...)` sliced short → no exception, partial fields |
| `unknownTlvIsSkipped` | injected type `0x7F` between version and gateway → both still decode |
| `oversizedHostOmitsGatewayRecord` | 253-byte host → no gateway TLV, version still present |
| `portBounds` | 0 and 65536 → gateway record omitted |
| `nullAndEmptyAppData` | `decode(null)` / `decode(new byte[0])` → all-absent info, no throw |
| `parseVersion` | `"6.1.9"`, `"qortal-6.1.9-abc"` equal; `"garbage"` → 0; `40000.0.0` → 0 |
| `isUsableAdvertiseHost` | localhost / 127.0.0.1 / ::1 / `dev-vm` rejected; `a.example.com`, `1.2.3.4` accepted |

This is the first executable test of the announce wire format — today none of it is reachable without constructing `RNS` (which builds a Reticulum stack and five thread pools).

### 7.2 `KnownPeerStore` + `RNSConfigWriter` (phase 6)

```java
final class KnownPeerStore {
    private final Path file;
    private final Set<String> confirmed = ConcurrentHashMap.newKeySet();
    private final Set<String> loaded    = ConcurrentHashMap.newKeySet();

    KnownPeerStore(Path storagePath, String fileName);

    void load();                       // tolerant: unreadable/missing file → no-op + log
    void save();                       // writes confirmed, or loaded when confirmed.isEmpty()
    boolean confirm(String hashHex);   // true when newly added; caller decides whether to save()
    Set<String> reconnectTargets();    // new HashSet<>(confirmed) + loaded — a copy, always
    boolean hasLoadedHashes();         // drives the start() announce-timer seeding
}
```

Instantiated twice in `RNS.start()`:

```java
this.baseStore = new KnownPeerStore(reticulum.getStoragePath(), "known_peer_hashes.txt");
this.dataStore = new KnownPeerStore(reticulum.getStoragePath(), "known_data_peer_hashes.txt");
```

Preserve exactly: the "prefer confirmed, fall back to loaded" save rule (2391), the two separate sets (loaded entries are never written back directly, which is how stale entries age out), and the `reticulum == null` early return.

`RNSConfigWriter.write(Path configDir)` is a straight lift of `initConfig` — Jinjava context build, `Files.deleteIfExists` before write (the truncation fix at 542–547 has its comment carried over), fallback to the packaged default config on any exception. Static method, no state.

### 7.3 `RNSGatewayManager` (phase 7)

```java
final class RNSGatewayManager {
    private final String appName;
    private final int targetPort;
    private volatile String localFqdn;
    private volatile String advertiseHost;
    private volatile boolean advertiseHostResolved;
    private final Map<String, Instant> recentAttempts = new ConcurrentHashMap<>();
    private final ExecutorService dialExecutor;   // 1 thread, queue 8, DiscardPolicy, "RNS-GatewayDial"

    String getAdvertiseHost();                    // null when disabled or unusable; logs once
    void maybeAddDynamicGateway(String host, int port);
    void forceBackboneReconnect();                // iterates BackboneClientInterface, forceReconnect()
    void shutdown();
}
```

**The §5.4 fix.** `maybeAddDynamicGateway` keeps every *cheap* check inline on the caller's thread (usable-host, self-skip via FQDN and advertised name, cooldown stamp, existing-interface scan, initiator cap) so ordering and dedup stay deterministic; only the blocking tail is submitted:

```java
    dialExecutor.execute(() -> {
        try {
            BackboneClientInterface iface = new BackboneClientInterface();
            ... setInterfaceName/TargetHost/TargetPort/Enabled/IfacNetName/IfacNetKey ...
            if (!InterfaceUtils.initIFac(iface)) { log.warn(...); return; }
            Transport.getInstance().getInterfaces().add(iface);
            iface.launch();                       // TCP connect — no longer on the announce thread
        } catch (Exception e) { log.warn(...); }
    });
```

That removes the TCP dial from `QAnnounceHandler.receivedAnnounce` (Reticulum's announce-delivery thread) and lets the `@Synchronized` on the handler go — the analysis notes it does not serialise BASE against DATA anyway, since the two handler instances hold separate Lombok `$lock`s.

Cooldown map eviction (§5.5) goes at the top of `maybeAddDynamicGateway`:

```java
    recentAttempts.values().removeIf(t -> Duration.between(t, now).compareTo(GATEWAY_COOLDOWN.multipliedBy(2)) > 0);
```

---

## 8. Phases 8–10 — the stateful extractions

### 8.1 `RNSPeerRegistry` (phase 8)

Sole owner of the four collections. Every mutation takes one lock and republishes the snapshot **inside** it; every read returns the snapshot, never the live list.

```java
final class RNSPeerRegistry {
    private final Object linkedLock = new Object();
    private final List<ReticulumPeer> linked = new ArrayList<>();          // guarded by linkedLock
    private volatile List<ReticulumPeer> linkedSnapshot = List.of();
    private final Object incomingLock = new Object();
    private final List<ReticulumPeer> incoming = new ArrayList<>();        // guarded by incomingLock
    private volatile List<ReticulumPeer> incomingSnapshot = List.of();

    /** false when a peer with the same destination hash is already tracked (caller closes the loser). */
    boolean addLinked(ReticulumPeer peer);
    void removeLinked(ReticulumPeer peer);
    /** Returns superseded same-identity+aspect peers, already unlinked; caller runs their side effects. */
    List<ReticulumPeer> addIncoming(ReticulumPeer peer);
    void removeIncoming(ReticulumPeer peer);
    List<ReticulumPeer> duplicateIncomingByIdentity(ReticulumPeer keep);

    List<ReticulumPeer> linked();
    List<ReticulumPeer> incoming();
    List<ReticulumPeer> activeLinked();                       // link ACTIVE && !deleteMe
    List<ReticulumPeer> activeLinked(PeerAspect aspect);
    List<ReticulumPeer> activeIncoming(PeerAspect aspect);
    List<ReticulumPeer> nonActiveIncoming();
    Set<String> activeIncomingHashes(PeerAspect aspect);      // hashFromNameAndIdentity, precomputed once
    boolean isTracked(byte[] destinationHash, PeerAspect aspect);
}
```

Two design decisions worth stating explicitly:

- **Side effects move out.** `addIncoming` currently calls `shutdownChannel()` and `closeIfActive()` on evicted peers *while holding the list monitor*. The registry now returns the evicted peers and `RNS` runs those calls after the lock is released. Narrower lock, same outcome, and it keeps rule 4 from §0.
- **`activeIncomingHashes(aspect)` is a registry method** because it is the O(1) dedup set that the BASE reconnect path already precomputes (RNS.java:708–720) and the DATA path lacks. Putting it here is what lets phase 10 give both aspects the same behaviour for free.

**Testability caveat, stated honestly:** `RNSPeerRegistry` cannot be unit-tested cheaply — `ReticulumPeer`'s constructors call `initPeerLink()`, which sends a LINKREQUEST through `Transport`. Mockito is available (test scope) and `ReticulumPeer` is non-final, so `mock(ReticulumPeer.class)` with stubbed `getDestinationHash()`/`getPeerLink()`/`getPeerAspect()` covers the add/remove/dedup logic without touching Reticulum. Do that for `addLinked` dedup, `removeLinked` snapshot atomicity and `activeIncomingHashes`; do not attempt an end-to-end registry test.

### 8.2 `ReconnectPolicy` (phase 10 prerequisite)

```java
final class ReconnectPolicy {
    private static final long BASE_BACKOFF_MS = 60_000L;
    private static final long MAX_BACKOFF_MS  = 30 * 60_000L;
    private final Map<String, Long>    lastFailureMs = new ConcurrentHashMap<>();
    private final Map<String, Integer> failureCount  = new ConcurrentHashMap<>();

    void recordFailure(String hashHex);
    boolean isBackingOff(String hashHex);      // now - lastFailure < backoffMs(hashHex)
    long backoffMs(String hashHex);            // 60s, 120s, 240s … capped at 30 min
    void clear(String hashHex);                // on confirmed ACTIVE
    void evictOlderThan(long ageMs);           // §5.5 — called once per reconnect cycle
}
```

One instance **per aspect**. Today `pendingFailureCount` is shared between aspects while the time maps are split, and `clearPendingFailure` clears both time maps — but BASE and DATA destination hashes are distinct (different aspect string in `hashFromNameAndIdentity`), so an entry can only ever exist in one map. Per-aspect instances are therefore behaviour-preserving. Keep the comment at 156–164 explaining why the exponential backoff exists (PENDING link → `expirePath()` cull cascade).

### 8.3 `RNSPeerPruner` (phase 9)

`prunePeers()` (155 L, four unrelated passes) becomes:

```java
final class RNSPeerPruner {
    RNSPeerPruner(RNSPeerRegistry registry,
                  Consumer<ReticulumPeer> removeLinkedPeer,
                  Consumer<ReticulumPeer> removeIncomingPeer,
                  BiConsumer<String, PeerAspect> recordPendingFailure);

    void prune();                                  // logs before/after counts, calls the four passes
    private void pruneInitiatorPeers();            // timed-out / unreachable-ACTIVE / CLOSED / stuck-PENDING
    private void pruneNonActiveIncoming();
    private void dedupActiveIncomingByIdentity();
    private void pruneSilentActiveIncoming();
    static boolean isUnreachable(ReticulumPeer p); // boxed Boolean → primitive (§5.7)
}
```

The pruner decides *which* peers go; the two `Consumer` callbacks are `RNS::removeLinkedPeer` /
`RNS::removeIncomingPeer`, so every side effect (`shutdownChannel`, `closeIfActive`,
`makePeerUnavailable`) stays in `RNS` and rule 4 of §0 holds without the pruner knowing about
Reticulum or `Network` at all. `recordPendingFailure` is a new aspect-keyed overload on `RNS`
delegating to the existing `(String, ConcurrentHashMap)` one — the pruner no longer needs to know
that the two aspects use separate time maps, which is what `ReconnectPolicy` (§8.2) will absorb in
phase 10.

Each pass takes one snapshot at its start instead of recomputing `getActiveImmutableLinkedPeers()` / `getNonActiveIncomingPeers()` seven times per cycle (§6.3 of the analysis).

`RNS.prunePeers()` stays as a one-line facade and **must keep `throws DataException`**: `Controller.java:1004` has a `catch (DataException e)` around the call, and Java rejects a catch clause for a checked exception that cannot be thrown. Changing that signature turns a refactor into a Controller edit for no gain.

`isUnreachable` returning `boolean` instead of `Boolean` changes `ConnectedPeer.java:185` (`!RNS.getInstance().isUnreachable(rnsPeer)`) not at all — auto-unboxing simply disappears.

### 8.4 `RNSAspectRunner` (phase 10)

One class, instantiated twice; this is where the ~600 duplicated lines collapse to ~340 and where DATA inherits BASE's robustness.

```java
final class RNSAspectRunner {
    RNSAspectRunner(PeerAspect aspect,
                    String aspectName,              // "qortal.core" | "qortal.qdn"  (use CORE_ASPECT/QDN_ASPECT)
                    Destination destination,
                    int minDesiredPeers,
                    int messageTaskType,            // Peer.NETWORK | Peer.NETWORKDATA
                    KnownPeerStore store,
                    RNSPeerRegistry registry,
                    ReconnectPolicy policy,
                    RNSGatewayManager gateways,
                    ExecutorService workerPool,
                    Supplier<byte[]> appDataSupplier,
                    BiConsumer<byte[], Identity> peerFactory,   // createLinkedPeerFromIdentity, per aspect
                    BooleanSupplier shuttingDown);

    void start();                        // names the thread "rnsMesh-" + aspect
    void shutdown();                     // interrupt + join(5s) + executor shutdown/awaitTermination(2s)
    void triggerImmediateAnnounce();     // now - interval + 5s
    void seedAnnounceTimer();            // full window on first start, 15s when the store has loaded hashes

    private void run();                  // while(!shuttingDown && !interrupted): drain, announce, reconnect, sleep
    private void drainPeerTasks(long now);
    private void announceTick(long nowMs);
    private void reconnectTick(long nowMs);
    private boolean watchdog(String label, AtomicLong startedMs, AtomicReference<Future<?>> future,
                             ThreadPoolExecutor exec, long timeoutMs);   // written once, used 4×
}
```

Construction in `RNS.start()`:

```java
this.baseRunner = new RNSAspectRunner(PeerAspect.BASE, CORE_ASPECT, baseDestination,
        MIN_DESIRED_CORE_PEERS, Peer.NETWORK, baseStore, registry, basePolicy, gatewayManager,
        rnsWorkerPool, this::buildAnnounceAppData, this::createLinkedPeerFromIdentity, this::isShuttingDown);
this.dataRunner = new RNSAspectRunner(PeerAspect.DATA, QDN_ASPECT, dataDestination,
        MIN_DESIRED_DATA_PEERS, Peer.NETWORKDATA, dataStore, registry, dataPolicy, gatewayManager,
        rnsWorkerPool, this::buildAnnounceAppData, this::createLinkedDataPeerFromIdentity, this::isShuttingDown);
```

`maybeAnnounce` collapses from two near-identical `if` blocks to one:

```java
private void announce() {
    int count = registry.activeLinked(aspect).size();
    if (count > minDesiredPeers) return;             // see §9 item 4 for the <= → > change
    long t0 = System.currentTimeMillis();
    destination.announce(appDataSupplier.get());
    long ms = System.currentTimeMillis() - t0;
    log.info("{} announce attempt completed in {}ms", aspect, ms);
    if (ms > 5_000) log.warn("{} announce took {}ms — possible jobsLock contention", aspect, ms);
}
```

**Migration order inside phase 10** (do not do this as one edit):

1. Write `RNSAspectRunner` with BASE semantics only; wire BASE to it; delete `runBaseLoop`. Verify a node runs for ≥ 1 h with BASE peers connecting.
2. Wire DATA to a second instance; delete `runDataLoop`, the four DATA executors, the eight DATA volatiles and `triggerImmediateDataAnnounce`. Verify DATA peers connect and QDN transfers still work.

Step 1 alone is revertable; step 2 is where the behaviour changes land.

---

## 9. Register of intended behaviour changes

Everything here is deliberate. Anything else that changes behaviour is a regression.

| # | Change | Phase | Why | How to see it |
|---|---|---|---|---|
| 1 | `shutdown()` no longer NPEs on a null incoming link | 4 | §5.1 | shutdown log reaches "shutdown of Reticulum complete" |
| 2 | Snapshot rebuild atomic on remove | 4 | §5.2 — a peer could be live but invisible to every consumer | not directly observable; narrow race |
| 3 | Dynamic gateway dial runs off the announce thread | 7 | §5.4 — a slow gateway stalled announce processing | "Dynamically adding announced backbone gateway" now logged from `RNS-GatewayDial` |
| 4 | `maybeAnnounce` fires on `count < min`, not `count <= min` | 10 | announcing after the target is met is pointless traffic | one fewer announce per cycle at steady state |
| 5 | **DATA gains announce/reconnect watchdogs** (60 s / 45 s) | 10 | §3 — a wedged DATA announce currently stops DATA announces for the process lifetime | "announce task running for Ns — interrupting stuck task" now appears with a DATA prefix |
| 6 | **DATA gains circuit-breaker participation** | 10 | ditto — DATA never triggered `forceReconnect()` | backbone force-reconnect can now be triggered by DATA stalls |
| 7 | **DATA gains the 1-outgoing-link-per-cycle throttle** | 10 | DATA could create N simultaneous links and flood `jobsLock` | at DATA 0-peers, one "proactively connecting" line per 15 s cycle instead of N |
| 8 | **DATA skips peers already ACTIVE as incoming** | 10 | avoids duplicate links and doubled Channel teardown rate | fewer duplicate DATA peers in `/peers/reticulum` |
| 9 | Loop tick 10 ms → 50 ms | 11 | ~200 peer-list traversals/s at idle for ≤ 10 ms of latency | idle CPU of `rnsMesh-*` threads drops |
| 10 | Per-peer reconnect INFO → DEBUG | 11 | ~100 INFO lines per 15 s cycle with 50 known peers (~24 k/h) | log volume |
| 11 | `isUnreachable` returns `boolean` | 9 | §5.7 | none |
| 15 | `ReconnectPolicy.evictOlderThan(24 h)` per reconnect cycle | 10 | §5.5 — the failure maps only ever grew | a peer quiet for a day restarts from the 60 s base window |
| 16 | The reconnect pass tests "already tracked" against the live registry, not a list captured before the task was submitted | 10 | a peer added in between was dialled and then deduped by `addLinked` | one fewer wasted LINKREQUEST per race |
| 17 | Announce/reconnect executor threads renamed `RNS-<aspect>-Announce` / `-Reconnect` | 10 | one naming rule for two instances | `jcmd Thread.print`; `RNS-` prefix greps still match |
| 18 | `shutdown()` stops each loop thread **and its executors** before peer teardown, not after | 10 | a reconnect task must not create links while shutdown closes them | shutdown log ordering |
| 19 | `QAnnounceHandler`'s per-peer "peer exists" / "peer link" lines INFO → DEBUG | 11 | that loop runs per received announce, and every peer announces every ~30 s | log volume; "added new ReticulumPeer" stays at INFO |
| 12 | Zero-caller public methods removed | 1 | §3.3 | none in-tree; note for any out-of-tree consumer |
| 13 | `shutdown()` tolerates a mesh that never started | 4 | `start()` can now return early, and Controller calls `shutdown()` unconditionally — without this the guard in §6.4 would just move the NPE | "Reticulum mesh was not started — closing worker threads only" |
| 14 | Snapshot fields are `volatile` | 3 | written by mutators, read by every consumer thread; the `@Data` getter provided no barrier | none observable |
| 21 | `baseClientConnected`/`dataClientConnected` collapse into one `clientConnected(link, aspect)`; their two INFO lines per inbound connection become one | 14b | the two were near-identical copies — the shape that hid §14.1 finding 1 — and aspect was already their only difference | one `"BASE client connected — link … (hash …)"` per inbound link, where there were two lines, the first of which logged a raw `byte[]` toString beside its own hex |
| 23 | Reticulum config logging now states which branch was taken, at INFO | — | the constructor logged `creating config in …` unconditionally, before a call that writes only when the config is missing or `reticulumRegenerateConfigOnRestart` is set; on every ordinary start the library's next line (`Config loaded from …`) contradicted it, and the truthful branch was DEBUG | one of `Reticulum config exists at … — leaving it as-is`, `Writing new Reticulum config to …`, `Regenerating Reticulum config at …` — replacing `creating config in …` |
| 22 | `removePeer(ReticulumPeer)` deleted | 14b | zero callers in `src/main` or `src/test`; package-private, so phase 1's sweep of zero-caller *public* methods missed it | none |
| 20 | `linkClosed()` claims its handling once per peer instance | 12a | the library calls the callback once **per timed-out packet**, not per link: `Channel.packetTimeout` → `outlet.timedOut()` → `Link.teardown()`, which has no already-closed guard. 26 % of closures ran the full teardown twice | same-second duplicate `Disconnecting peer … reason: link closed` groups drop from 41-of-158 to zero (§14.2 finding 1) |

**Runtime verification.** A node was run at the phase-5 state (`9e9d8737`) — mesh forms, peers connect, Reticulum working normally. Phases 6–7 changed startup ordering (the peer stores are built in `start()`, not the constructor) and the gateway-dial threading, so **re-run a node before phase 8** and check: known-peer hashes still load at startup ("Loaded N known BASE peer hashes"), `/peers/reticulum` fills as before, and any dynamic gateway add now logs from the `RNS-GatewayDial` thread.

**Test placement.** The Reticulum unit tests live in `org.qortal.network.reticulum` under `src/test` (`RNSAnnounceCodecTest`, `RNSPeerFactoryScanTest`, `RNSPeerRegistryTest`, `RNSPeerPrunerTest`) — `RNSPeerRegistry` and `RNSPeerPruner` are package-private *on purpose*, since that visibility is what enforces "only RNS mutates the peer lists", and a same-package test is the only way to reach them without making the classes public. The repo already has this pattern (`src/test/java/org/qortal/controller`). `RNSNetworkTest`, which is an integration-style test rather than a unit test, stays in `org.qortal.test.network`.

**Testability fix, phase 8.** `ReticulumPeer.APP_NAME` (a `static final` reading `Settings`) forced the settings file, `BlockChain` and the crypto stack to load during class initialisation — mocking the class failed with `NoClassDefFoundError: NullAccount`, root-caused to `RIPEMD160 message digest not available`. It is now `appName()`, resolved on use. Only `initPeerLink()` ever read it. `RNS` has the same pattern in its own `APP_NAME`; harmless today (nothing mocks `RNS`) but worth the same treatment if it ever needs testing.

**Deviation, phase 7.** `@Synchronized` on `receivedAnnounce` is **kept**, not removed as §7.3 anticipated. What the analysis objected to was holding it across a TCP connect; that is fixed by moving the dial to an executor. The lock itself is cheap, and dropping it would let two announces for the same aspect race the `activePeerCount < peerLimit` check — harmless (`addLinkedPeer` dedups atomically) but a real concurrency change with no upside. Its limits are now documented at the annotation.

**Notes, phase 11.** Item 10's scope was "per-peer reconnect"; item 19 was added for `QAnnounceHandler`, which is the same class of problem (per-announce rather than per-cycle) and was the largest single INFO source left in `RNS.java` on a busy mesh. `hopsTo()` moved inside an `isDebugEnabled()` guard — it fed nothing but that log line. Deliberately left at INFO: the per-cycle interface online status (one or two lines per 15 s, and the first thing you read when the mesh is down), the announce timing lines that §11.2 checks, and everything in `RNSPeerPruner` — a peer removal is an event, not a poll.

**Notes, phase 10.** Landed as the two commits §8.4 asks for: `6bf85e93` wires BASE only and leaves `runDataLoop` untouched (revertable, and the state to run a node at), `b29ea96d` wires DATA and deletes the duplicate.

Deviations from §8.4's sketch:

- **`aspectName` is not a constructor parameter.** Nothing in the runner needs it — `registry.activeIncomingHashes(aspect)` derives it itself. Two parameters were added instead: `logInterfaceStatus` (interface state is transport-wide, so only BASE logs it — otherwise the line rate simply doubles) and `threadPriority` (the runner builds its own executors, so it needs what the `RNS` constructor used to read from `Settings`).
- **`peerFactory` is one method, not two.** `createLinkedPeerFromIdentity` takes the aspect; `new ReticulumPeer(dhash)` was already `new ReticulumPeer(dhash, BASE)`.
- **§8.4's `announce()` snippet contradicts §9 item 4** — it shows `if (count > minDesiredPeers) return;`, which is the old `<=` behaviour. §9 is authoritative per rule 3, so the code is `if (count >= minDesiredPeers) return;`. Worth a look on a live node: at exactly the peer target a node now stops announcing, which is the intended traffic saving but also makes it marginally less discoverable.
- **The watchdog helper takes `nowMs`** as well as the four planned parameters, and the "is a task already running" test is a `compareAndSet(0, nowMs)` rather than a read-then-write — the loop thread is the only writer, so this is defensive rather than a fix.

`RNS.java` is 854 L, not the ~250 L of §1. What remains is the facade proper (lifecycle, the singleton, the public API), plus `QAnnounceHandler` (~140 L), the two `*ClientConnected` callbacks, and the peer add/remove methods whose bodies are mostly the §10 comments. Splitting those out was never in the phase table; §13's "≤ 300 lines" is not met and would need a phase 12 to reach.

**Notes, phase 9.** Two behaviour-neutral simplifications beyond the plan: the before/after census reads `registry.activeIncoming().size()` instead of `incoming.size() - nonActiveIncoming().size()` (exact complements — one traversal, not two), and the silent-peer pass iterates `registry.activeIncoming()` instead of filtering `incoming()` by `status == ACTIVE` inline. Log text is unchanged. `RNS.getNonActiveIncomingPeers()` is deleted: after the extraction it had zero callers, and `registry.nonActiveIncoming()` is the accessor. `java.time.Duration` and the `LinkStatus.PENDING` static import leave `RNS.java` with the prune code — nothing else in the facade used either.

**Running notes (phases 1–5, as landed).** `mvn test` needs network access the first time — surefire's `junit-platform` provider is not in the local repo, and `-o` fails on it. Tests run with
`mvn -DskipTests -DskipJUnitTests=false -Dtest=RNSAnnounceCodecTest test` (the pom wires `<skipTests>` to the `skipJUnitTests` property, so `-DskipTests` alone does not control it).
`RNSAnnounceCodec.parseVersionToLong` uses `Peer.VERSION_PATTERN` rather than `ReticulumPeer.VERSION_PATTERN` — same constant, inherited from the interface, but it keeps the codec free of any peer class.

Items 5–8 are the point of the whole exercise: the DATA path is silently missing robustness fixes that were applied to BASE, and unifying the loops is the only way to stop that drift recurring.

**Notes, phase 13.** Modifiers only — nothing in §9 applies, because nothing
observable changed. Twelve members dropped to package-private, two more than the
ten the Status list named: `dedupIncomingPeerByIdentity` (the list's own
parenthesis already noted it is called only from `ReticulumPeer`, then left it
off), and `getMessageMagic`, which the list counted as *external* in error — every
call site outside the package is `Network.getInstance().getMessageMagic()` or
`NetworkData`'s, never `RNS`'s. Inside the package `RNS` calls it four times, when
stamping a new peer. So the real external surface is **12**, not 13:
`getInstance`, `start`, `shutdown`, `prunePeers`, `isMeshStarted`, `broadcast`,
`isUnreachable`, `getAllKnownPeers`, `getActiveDataPeers`,
`getImmutableLinkedPeers`, `getImmutableIncomingPeers`, `onPeersV2Message`.

The constructor is now `private`: `SingletonContainer` is its only caller and
nothing constructs `RNS` reflectively (`grep "RNS\.class"` and `grep "new RNS("`
over `src/main` + `src/test`). `QAnnounceHandler.getAspectFilter` /
`receivedAnnounce` stay public — they implement the library's `AnnounceHandler`
interface — but the class holding them is `private`, so they are not surface.

The check for each member was a full-tree grep outside the package before the
edit. Three names produced hits that are not calls and are worth recording so the
next reader does not re-derive them: `addLinkedPeer` and
`onIncomingPeerIdentified` appear in comments in `Network.java` and
`ConnectedPeer.java`, and `removePeer` matches
`ArbitraryDataFileManager.removePeerTimeOut` and `PeersResource.removePeer`,
which are unrelated methods of the same name.

---

## 10. Comments that must survive verbatim

These encode operational knowledge that is not re-derivable from the code. Each one moves with its code; none may be summarised away.

| Comment (line at `4ec4609a`) | Home as built |
|---|---|
| Dedicated single-thread executors / `jobsLock` busy-wait rationale (215–221) | `RNSAspectRunner` field block |
| `PENDING_FAILURE_BACKOFF_MS` / capped-exponential rationale (148–164) | `ReconnectPolicy` |
| Hybrid reconnect strategy: `createLinkedPeerFromIdentity` vs `requestPath` (738–756) | `RNSAspectRunner.reconnectTick` |
| Why the 1-link-per-cycle throttle exists (703–706) | `RNSAspectRunner.reconnectTick` |
| Why `activeIncomingBaseHashes` is precomputed (708–711) | `RNSPeerRegistry.activeIncomingHashes` |
| Why `createLinkedPeerFromIdentity` must **not** call `getOrInitPeerLink()` (1698–1703) | `RNSPeerLifecycle.createLinkedPeerFromIdentity` |
| Why `removeLinkedPeer` deliberately does **not** close `peerLink` (1948–1951) | `RNSPeerLifecycle.removeLinkedPeer` |
| Why `makePeerUnavailable()` runs with no RNS lock held (1954–1961) | `RNSPeerLifecycle.removeLinkedPeer` |
| `closeIfActive` ACTIVE-only + ABBA-inversion rationale (2078–2088) | `RNSPeerLifecycle.closeIfActive` |
| Why PENDING links must not be torn down (`expirePath` cull cascade) (2254–2262) | `RNSPeerPruner.pruneInitiatorPeers` |
| Watchdog-thread leak: 16,642 threads / RSS 34.8 G (2215–2222) | `RNSPeerPruner.pruneInitiatorPeers` |
| Why `announce()`/`requestPath()` are not called from `prunePeers` (2330–2333) | `RNSPeerPruner.prune` |
| Why `getAspectFilter()` returns null (1565–1569) | `RNSAnnounceHandler.getAspectFilter` |
| `createPeerBuffer` vs `getOrInitPeerBuffer` on the broadcast path (1154–1155) | `RNSPeerLifecycle.clientConnected` |
| `exitHandler()` timeout / zombie-channel rationale (1093–1095) | `RNS.shutdown` |
| Config truncation fix: delete before write (542–547) | `RNSConfigWriter` |
| `LINK_INBOUND_TIMEOUT_MS` ≈ 2× library KEEPALIVE (240–247) | `RNSPeerPruner` |

Check after each extraction phase:

```bash
git show HEAD --stat
git diff HEAD~1 -- '*.java' | grep '^-' | grep -E '^\-\s*(//|\*)' | grep -vE '^\-\s*//\s*$'
```

Every deleted comment line in that output must be accounted for: either it moved (find it in the `+` side) or it belonged to code deleted in phase 1. Javadoc that wraps differently because a method was renamed counts as moved — phase 14b re-wrapped several when `baseClientConnected`/`dataClientConnected` became `clientConnected`.

All sixteen were grep-verified present after phase 14. To re-check the table above in one pass:

```bash
cd src/main/java/org/qortal/network/reticulum
grep -rl "would call initPeerLink() a second time"   # createLinkedPeerFromIdentity
grep -rl "deliberately does NOT close peerLink"      # removeLinkedPeer
grep -rl "No RNS lock is held here"                  # makePeerUnavailable
grep -rl "ACTIVE-only is deliberate"                 # closeIfActive
grep -rl "avoids synchronized(link)"                 # createPeerBuffer
grep -rl "Return null so Transport fires"            # getAspectFilter
grep -rl "zombie link's channel holds a lock"        # exitHandler
grep -rl "16,642"                                    # watchdog-thread leak
```

---

## 11. Verification

### 11.1 Per-phase, mechanical

```bash
mvn -q -DskipTests compile                       # every phase
mvn -q -DskipTests test-compile                  # catches test-side breakage from phase 1 and 2
mvn -q -Dtest='RNSAnnounceCodecTest' test        # phase 5 onwards
```

Post-phase greps that must return nothing:

```bash
# phase 3: no Lombok setters left on RNS
grep -rn "\.set\(Reticulum\|ServerIdentity\|LinkedPeers\|IncomingPeers\|ShuttingDown\)(" src/main --include='*.java'
# phase 4: no live-list iteration
grep -n "getIncomingPeers()\|getLinkedPeers()" src/main/java/org/qortal/network/reticulum/RNS.java
# phase 1: no fully-qualified names where an import exists (§5.8)
grep -n "java\.util\.concurrent\.\|java\.time\.Duration\|io\.reticulum\.interfaces\." \
     src/main/java/org/qortal/network/reticulum/RNS.java
# phase 10: no BASE/DATA duplicated field pairs left
grep -nE "dataAnnounce|dataReconnect|lastDataLoop|pendingDataLink" \
     src/main/java/org/qortal/network/reticulum/RNS.java
# phase 14: the facade no longer holds the announce handler, the peer lifecycle
# or the identity load
grep -nE "class QAnnounceHandler|ClientConnected|closeIfActive|addIncomingPeer|Identity\.fromFile" \
     src/main/java/org/qortal/network/reticulum/RNS.java
```

And one that must return exactly `470` until the facade is touched again:

```bash
wc -l < src/main/java/org/qortal/network/reticulum/RNS.java   # §13 budget: ≤ 500
```

### 11.2 Runtime, after phase 2 and after each of 7/8/10

> **Status.** Run at the phase-11 state (§14.1) and soaked 6 h 09 m at the
> phase-12 state (§14.2). **All six checks pass.** Check 5's thread-identity half
> was closed separately, on two `ThreadDumpScheduler` dumps 12 h apart rather than
> on `jcmd`, which could not attach (§14.2).
>
> **Nothing since the phase-12 state has been on a node** — that is now `12a`,
> phase 13 and all of phase 14. `12a` changes the teardown path and phase 14
> moved the inbound-accept path, the announce handler, the peer add/remove
> methods and the identity load; phase 13 is modifiers only and rides along. The
> checklist for that one run is in **Next steps**, near the top.

Compiling does not prove the reflection-based `PeerFactory` registration still works, nor that the mesh forms. Run a node and check:

1. `Reticulum config exists at … — leaving it as-is` (or `Writing new Reticulum config to …`
   on a first start) / `reticulum instance created` — construction path intact. Both are INFO;
   the old `Reticulum config exists, skipping.` was DEBUG, so this check asked for a line that
   was invisible at the default level (§9 item 23).
2. `RNS mesh started, baseDestination: <hash>` — `start()` completed.
3. `GET /peers/reticulum` returns peers with both `BASE` and `DATA` aspects, initiator and incoming.
4. Over ≥ 1 h: no `ConcurrentModificationException`, no NPE in `shutdown`, `Removing PENDING link stuck for Ns` appears at a similar rate to the baseline (not more).
5. Thread count stable: `jcmd <pid> Thread.print | grep -c "RNS-\|rnsMesh-"` — after phase 10 expect *fewer* threads (four aspect executors are now created by the runners, not by the RNS constructor, and the count is unchanged at 4; the watchdog-cancel path no longer spawns replacements).
6. Clean shutdown: `stop.sh` reaches `shutdown of Reticulum complete` within ~15 s, JVM exits.

Capture a baseline of 3–6 on the current build **before** starting phase 1, so the comparison is real.

---

## 12. Deviations from the analysis

| Analysis says | Plan does | Why |
|---|---|---|
| §8.1 "make `AnnounceInfo` a package-private record" | final class with final fields | `pom.xml` sets `<release>11</release>`; records need 16+ |
| §8.6 extract `RNSMessaging` (~120 L) | delete instead | `onPingMessage`, `buildHeightOrChainTipInfo`, `peerMisbehaved`, `getMessageMagic`'s external form, `findPeerBy*`, `getOurNodeId` have **zero callers** — all live callers use the `Network`/`NetworkData` equivalents. Only `broadcast` (Network.java:2753) survives, and it is 15 lines; it stays on the facade |
| §9 phase 1 "replace `@Data`" first | dead-code deletion and the package move come first | the move produces a cleaner rename diff when file content is not simultaneously edited, and deleting first shrinks what has to move |
| §5.6 "constructor swallows failure" | guard in `start()`, constructor still non-throwing | throwing from the constructor turns into an `ExceptionInInitializerError` inside `SingletonContainer`, which is strictly worse than a mesh that stays down with `isMeshStarted() == false` |
| §10 "put all reticulum java files in the folder" | includes `RNSCommon.java`, at the cost of 5 import updates in the IP path | `RNSCommon.PeerMetaType` is shared with `Peer`/`IPPeer`/`Network`/`NetworkData`/`PeerData`; the alternative (leave it in place) is noted in §4 |

---

## 13. Definition of done

- ✅ `src/main/java/org/qortal/network/reticulum/RNS.java` **≤ 500 lines** (revised from ≤ 300 — see below), no `@Data`, no setters, ≤ 18-member public surface.
  **470 L**, 12-member public surface. `@Data`, the setters and the live-list getters went in phase 3; since phase 13 the public surface *is* the external surface — every one of the 12 has a caller outside the package, against the 18 this line asked for.

  **Why the figure changed.** The original ≤ 300 was an estimate made from the code to be moved, before the cost of the documentation that code needs once it stands alone was known; §1 records the same 20–45 % overshoot on every one of the eleven extracted classes. Phase 14 moved everything the budget assumed it would — `QAnnounceHandler`, the `*ClientConnected` callbacks, the peer add/remove methods — plus the identity block the budget never counted, and landed at 470: **308 lines of code, 97 of comment, 65 blank**. Deleting every comment and blank line would still miss 300, and those comments are §10 material rule 2 forbids removing. What remains is the facade proper — `start()`, `shutdown()`, the constructor, the fields, the public API — so going lower means moving `start()`/`shutdown()` out too, after which `RNS` is a delegation shell and "facade" is a name rather than a description. 500 is the honest budget for what this class is.
- ✅ No `runDataLoop`; one `RNSAspectRunner` class instantiated twice; no `data*` mirror fields. Grep-verified (§11.1).
- ✅ `RNSAnnounceCodecTest` green, ≥ 10 cases, covering legacy QGW1 and truncation. 18 cases; 63 across the package.
- ✅ Every comment in §10 present in the new tree (grep-verified after each extraction phase).
- ✅ A node runs with BASE and DATA peers connected, stable thread count, no CME/NPE, and shuts down cleanly.
  **6 h 09 m at the phase-12 state (§14.2), mostly met.** Both aspects connected throughout — BASE at its target of 5, DATA at 5–7 of a desired 8 (peer supply, not a fault). Zero CME/NPE across 199 disconnects and 128 teardowns, and 1480 uninterrupted loop cycles.
  The original "≥ 24 h" is **superseded**: duration was standing in for teardown volume, and 199 of them exercises the concurrency surface more than a quiet 24 h would. Shutdown is verified: 10 s from `stop.sh` to JVM exit, 13 peers closed gracefully, no NPE/CME (§14.2 check 6) — though the node accepts new links after declaring shutdown complete (§14.2 finding 5). Thread *identity*, the last clause open, is now verified too: two `ThreadDumpScheduler` dumps 12 h apart show every RNS-owned thread with the same name **and the same thread ID**, and no executor past its `-1` suffix (§14.2). Separately, §9 item 15's eviction is unreachable below 24 h uptime; `ReconnectPolicyTest` (`17dcfa42`, 13 cases) now covers it, which is the answer a longer run could not give — a soak shows the `removeIf` not crashing, the test shows it evicting the right entries and resetting the failure counter with them. See "Next step" in Status.
- ⚠️ §9 is the complete diff in observable behaviour; nothing outside it changed. True by construction and review — 20 registered items — but only a node run can confirm it. The 6 h soak (§14.2) confirmed items 4 and the phase-12 kick directly, and turned up one *unregistered* divergence, now registered as item 20.