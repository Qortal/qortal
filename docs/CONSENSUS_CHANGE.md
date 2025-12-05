### TL;DR

Yes — **enforcing invite expiration at the moment a closed-group join is finalized is a consensus change**, so you should ship it behind a **new `featureTrigger` block height** (`groupInviteExpiryHeight`, not local wall-clock time), release a new Core version, and **schedule the trigger far enough ahead that the on-chain auto-update can propagate**. Qortal already does exactly this style of rollout for other consensus-affecting changes via “featureTriggers” keyed by block height. ([GitHub][1]) Ship the first release with a far-future placeholder height (e.g., `99999999`) and flip it to a real activation height only after rollout coverage is high.

---

## Root cause → fix → how to verify (for this item)

* **Root cause:** invite `expiration` exists as data, but is not enforced in the membership-finalization logic, so an old `GROUP_INVITE` can “approve” a `JOIN_GROUP` indefinitely.
* **Fix:** after **`groupInviteExpiryHeight`**, a closed-group `JOIN_GROUP` finalizes membership **only if a matching `GROUP_INVITE` exists and is unexpired at the deterministic transaction timestamp used for finalization** (`expiry == null` means “never”, valid if `finalizingTxTimestamp <= inviteExpiry`). In the invite-first path, expired invites are ignored so the join remains a request; in the join-first path, TTL is ignored and any matching invite approves the pending request (documented exception). Use transaction timestamps for determinism, never local time.
* **Verify:** before trigger height, behavior unchanged; after trigger height in the invite-first path, a `JOIN_GROUP` that would previously auto-add a member instead becomes only a request (no auto-add). Join-first remains exempt (any invite approves a stored request), so only invite-first flows show the delta post-trigger.

---

## Why this must be a hard-fork / consensus-triggered change

Once you enforce expiry, two nodes will disagree on group membership for the same block if only one enforces it:

* **Old nodes:** “Invite exists ⇒ join succeeds (member added)”
* **Upgraded nodes:** “Invite exists but expired ⇒ join does *not* add member”

That divergence affects the validity/effect of later group-dependent transactions, so it’s consensus-critical. Hence: **feature trigger + coordinated rollout**.

---

## Recommended trigger mechanism: block height (not timestamp)

Qortal already uses **featureTriggers expressed as block heights** (e.g. `adminsReplaceFoundersHeight`, etc.), and has shipped releases where multiple triggers activate on the same height. ([GitHub][1])

**Recommendation:** implement this as a new **feature trigger height**:

* `groupInviteExpiryHeight: 99999999` (placeholder “all 9s”)
* later replace with a real height once you’re ready to schedule the fork.

Why height is the safer default:

* Deterministic during reorg and replay.
* Matches existing Qortal operational practice. ([GitHub][1])

---

## Exactly what rule should change at the trigger

### Pre-trigger (legacy behavior)

* Preserve current semantics 1:1 for replay compatibility.

### Post-trigger (new behavior)

Canonical rule: after `groupInviteExpiryHeight`, membership finalizes **only if a matching invite exists and the relevant transaction timestamp is <= inviteExpiry** (`expiry == null` = never). If the invite is expired, ignore it and let the join fall back to a pending request instead of rejecting the transaction (request path only). For join-first auto-approvals, TTL is intentionally ignored: any matching invite approves a pending request.

When a closed-group membership is about to be finalized because both sides exist (`JOIN_GROUP` + `GROUP_INVITE`, regardless of order), the approval side must check:

**Invite is valid iff (invite-first path)**

* `expiry == null` (TTL = 0 “never expires”), **OR**
* `timestamp_used_for_finalization <= inviteTx.timestamp + expiryPeriod`

Key point: **do not use local time (`System.currentTimeMillis`/NTP) in consensus paths.** Use the transaction timestamp (join or invite, depending on ordering). This matches ban-expiry semantics but accepts the ~30m forward-dating window allowed by `maxTransactionTimestampFuture`. The boundary is inclusive (`<= expiry`). Small note on dating: transaction timestamps can be forward-dated (~30m) and backdated within the transaction-expiry window (~24h), so an invite can still be consumed by a JOIN whose timestamp lies inside the TTL even if the block is mined later. Join-first auto-approvals ignore TTL entirely (any invite approves).

Residual window (intentional): sticking with transaction-timestamp semantics means a caller can use that forward/backdating window to consume an invite outside its wall-clock TTL. This is the accepted trade-off for determinism and parity with current ban handling; tighter expiry would require block-time or hybrid semantics (see docs/OTHER_ISSUES.md).

### Practical semantics (minimizes breakage)

* If invite is expired, **do not reject the `JOIN_GROUP` transaction outright**.
* Instead: treat it like a normal closed-group **join request** (i.e., it stays pending until a valid invite arrives).

This avoids turning “expired invite” into a new transaction validity rule (more disruptive), while still fixing the exploit/bug: **you can’t become a member using an expired invite**.

---

## Handling both possible orderings (important)

You described that order doesn’t matter: join can be a request first, invite can be approval later.

Post-trigger, you want *both* of these to remain true (same as pre-trigger):

1. **Invite-first (common case):**

   * `JOIN_GROUP` finalizes membership only if invite is unexpired **at joinTx.timestamp**.

2. **Join-first (request), invite-second:**

   * Auto-approves a pending join request when an invite appears, and **TTL is ignored** in this path (any matching invite approves), pre- and post-trigger. This preserves the “request + later approval” behavior even for aged invites.

If you only implement (1), you’ll fix the user-visible “invites never expire” bug in the most common flow; (2) is intentionally permissive and documented. In all cases, expired invites are ignored in invite-first (no rejection); closed-group joins fall back to pending requests.

---

## Feature-trigger rollout plan (how I’d ship it)

### 1) Add the trigger (placeholder)

* Add a new entry in the chain config featureTriggers set at **all-9s** placeholder height (e.g. `99999999`).
* Gate the new behavior behind `height >= groupInviteExpiryHeight`.

### 2) Ship the Core release + on-chain auto-update

Qortal’s model is: **auto updates exist to keep nodes synced and reduce forking risk**. ([Qortal Project][2])
Auto-updates themselves require dev/admin governance (40% approval is mentioned in the project wiki). ([Qortal Project][3])

So operationally:

* Release a Core version that *contains* the new rule but with a “far future” placeholder trigger.
* Get it distributed via auto-update.

### 3) Pick a real activation height and ship a follow-up release

Qortal has previously:

* **Set featureTrigger block heights** in releases, and even called out estimated activation dates. ([GitHub][4])
* **Pushed trigger heights back** to allow more time for auto-update preparation/propagation. ([GitHub][4])

So the pattern is already established:

* Once most of the network is on the “rule-ready” version, ship a follow-up that changes the trigger from `999...` to a concrete height.
* Set the activation height with enough margin that even slower nodes pick up the update.

---

## What “expiration details” you must lock down (so the fix is unambiguous)

These are the specific semantics I would explicitly codify (and test) before merging:

* **Units:** is invite `expiration` seconds, minutes, blocks, or milliseconds? (Treat it consistently everywhere.)
* **Sentinel value:** `expiry == null` means “never expires” (TTL = 0); enforce it explicitly.
* **Boundary:** is `expiresAt` inclusive or exclusive?

  * I’d recommend: valid if `finalizingTx.timestamp <= expiresAt` (inclusive), because it avoids “off-by-one millisecond” surprises.
* **Timestamp basis:** use **transaction timestamp** (join/invite, matching ban expiry); note the allowed ~30m forward-dating window via `maxTransactionTimestampFuture` and backdating within the tx-expiry window (~24h). Never use local clock time.
* **Both orderings:** invite-first enforces expiry; join-first auto-approvals intentionally ignore TTL (any matching invite approves a pending request).
* **Orphan/reorg safety:** logic must replay identically during sync/reorg (feature-trigger gating by height ensures that).

---

## Minimal PR description text you can reuse

> This change introduces consensus-enforced GROUP_INVITE expiration for closed-group membership finalization. After `groupInviteExpiryHeight` activates, a closed-group JOIN_GROUP finalizes membership only if a corresponding GROUP_INVITE exists and is unexpired at the deterministic on-chain transaction timestamp of finalization (with `expiry == null` meaning “never expires”); otherwise the join remains a request. Join-first auto-approvals intentionally ignore TTL (any matching invite approves a pending request). Prior to activation height, legacy behavior is preserved for chain compatibility.

If you want, next step we can walk through the exact “post-trigger rule” you prefer (strict reject vs “request-only”), but the above is the low-risk option that matches existing closed-group behavior.

## Activation plan (next release)

- Keep `groupInviteExpiryHeight` in configs: mainnet placeholder (far future), testnet/fixtures low heights.
- Ship the rule behind the placeholder height first; use on-chain auto-update.
- Once coverage is sufficient, publish a follow-up release that sets a real activation height and communicates the fork date/height to operators.

[1]: https://github.com/qortal/qortal/releases?utm_source=chatgpt.com "Releases · Qortal/qortal"
[2]: https://wiki.qortal.org/doku.php?id=how_to_update_.jar_file "how_to_update_.jar_file [Qortal Project ]"
[3]: https://wiki.qortal.org/doku.php?id=qortal_in_a_nutshell "qortal_in_a_nutshell [Qortal Project ]"
[4]: https://github.com/qortal/qortal/releases "Releases · Qortal/qortal · GitHub"
