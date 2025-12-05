# Invite Expiration – Implementation Considerations

- **Deterministic time source:** In consensus paths use the transaction timestamp (matches ban semantics); never use `NTP.getTime()`/local clock. Accept the ~30-minute forward-dating window (`maxTransactionTimestampFuture`) and the ~24h backdating window (transaction expiry); switching to block time would change behavior and need a feature-triggered rollout.
- **Feature trigger wiring:** Add `groupInviteExpiryHeight` to `BlockChain.FeatureTrigger` and every chain config (mainnet/test/regtest/fixtures); missing entries fail startup validation.
- **“No expiry” sentinel:** TTL=0 is stored as `expiry = null`; keep this in code/tests/docs.
- **Orderings:** Invite-first enforces expiry post-trigger; join-first auto-approval intentionally ignores TTL (any matching invite approves a pending request). Document this split.
- **API filtering scope:** Filter expired invites unconditionally using chain-tip block timestamp with inclusive boundary (`expiry >= chainTipTimestamp`); treat `expiry == null` as never; skip filtering if there is no chain tip. Limits/offsets apply before filtering, so pages can be short. Document divergence: API uses chain-tip time while consensus uses transaction timestamps (with forward/backdating), so a backdated JOIN might still succeed even if the API hides the invite. This pre-trigger, chain-tip filtering is intentional as a soft mitigation to avoid surfacing invites that “should” be expired.
- **Expired invite handling/retention:** Leave expired invites stored; validation ignores them, API filters them; no pruning so cancel flows continue to work and replay/orphan behavior stays deterministic.
- **Testing matrix:** Cover invite-first valid/expired, join-first valid/“expired by wall clock” (still auto-approves because TTL is ignored), TTL=0, backdated JOIN success (documents tx-timestamp window), API filtering behavior (chain-tip basis, skip when no tip), and orphan/reorg safety via deterministic checks. Use `groupInviteExpiryHeight` with `BlockUtils.mintBlocks` for pre/post behavior.
- **Current code gaps:** `Group.join(...)` lacks expiry checks/trigger gating (invite-first); API not filtering by tip; join-first TTL handling must be explicit (ignored) and consistently documented (no trigger gating).

## Companion docs addressed

- docs/CONSENSUS_CHANGE.md: deterministic time source, `expiry = null` sentinel, feature trigger wiring, both orderings (invite-first enforced, join-first documented).
- docs/IMPLEMENTATION.md: trigger wiring, consensus-time basis, API filtering (tip-based, skip when no tip), invite-first enforcement, join-first TTL ignored, tests aligned.
- docs/INVITE_EXPIRATION.md: tx-timestamp basis with forward/backdating windows, invite-first enforcement, join-first TTL ignored, API divergence documented.

## Feature trigger wiring decisions

- Trigger name: `groupInviteExpiryHeight` (lowerCamel with `Height`, matching `groupMemberCheckHeight` style).
- Mainnet config (`src/main/resources/blockchain.json`): add the key with a far-future placeholder (e.g., `99999999`).
- Testnet (`testnet/testchain.json`): add the key with a low height (e.g., `0` or `1`) so it is active immediately.
- Test fixtures (`src/test/resources/test-chain-*.json`): add the key to every `featureTriggers` map with a low height (e.g., `0`) to avoid validation failures and keep tests exercising expiry logic.
- Code: add the enum entry and a getter in `BlockChain`, and use the getter in invite-expiry code instead of raw map access.

## Testing plan

- Consensus-path tests (extend `src/test/java/org/qortal/test/group/MiscTests.java`):  
  - Invite-first, valid before expiry → member added post-trigger.  
  - Invite-first, expired → join becomes request, no membership; invite stored but ignored.  
  - Join-first, invite later valid → auto-add member.  
  - Join-first, invite later expired by wall clock → still auto-adds because TTL is ignored; request should not persist.  
  - TTL=0 non-expiring invite → still works.  
  - (Optional) Expired invite with backdated JOIN (tx timestamp ≤ expiry) still succeeds to document tx-timestamp window.  
  - Use `BlockChain.getGroupInviteExpiryHeight()` with `BlockUtils.mintBlocks(...)` to flip pre/post behavior; set test configs’ `groupInviteExpiryHeight` low (0/1).
- API filtering tests (new class, e.g., `GroupApiInviteTests`):  
  - Invite with short TTL expires; chain-tip timestamp > expiry ⇒ `/groups/invites/...` omits it.  
  - TTL=0 and unexpired invites remain visible.  
  - Expiry exactly at chain-tip timestamp remains visible (inclusive boundary).  
  - Use chain-tip block timestamp as “now”; skip filtering when no tip; no feature trigger gating.
