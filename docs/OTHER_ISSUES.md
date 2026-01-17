# Other Issues (Non-invite-expiry)

## Transaction-timestamp spoofing can bypass near-expiry bans

- **What happens:** GROUP_JOIN and GROUP_INVITE validation checks whether an offender is banned by calling `banExists(groupId, address, txTimestamp)` (`src/main/java/org/qortal/transaction/JoinGroupTransaction.java`, `GroupInviteTransaction.java`). The repository considers a ban active if `expires_when > txTimestamp` (`src/main/java/org/qortal/repository/hsqldb/HSQLDBGroupRepository.java:790`).
- **Spoof window:** Transaction timestamps are user-supplied and only constrained to be (a) not too far in the future (`now + maxTransactionTimestampFuture`, default 30 minutes; `Settings.java:108`) and (b) before the containing block timestamp (`Block.java:1335-1340`, `BlockMinter.java:468-476`). A user can forward-date their JOIN/INVITE by up to ~30 minutes. They can also backdate within the transaction-expiry window (~24h). If a ban expires inside those windows, the tx timestamp can be set just after ban expiry so validation treats the ban as expired even though the block is mined earlier.
- **Scope of backdating:** Backdating is capped by `transactionExpiryPeriod` (24h in `src/main/resources/blockchain.json`), so the spoof window cannot extend beyond that expiry horizon.
- **Impact on users:** Offenders can rejoin early by timestamp spoofing—up to ~30 minutes via forward-dating, or within the tx-expiry window via backdating—defeating the tail end of a ban’s lifetime.
- **Underlying cause:** Validation uses the transaction’s timestamp (user-controlled) instead of a deterministic on-chain time (e.g., containing block timestamp) when evaluating ban expiry.
- **Potential fixes:**  
  - **Block timestamp check (consensus):** Switch ban expiry checks to use the containing block’s timestamp (or other deterministic on-chain time) instead of `txTimestamp`; gate via a new feature trigger. Closes the spoof window; requires rollout.  
  - **Reduce forward-dating (non-consensus):** Tighten `maxTransactionTimestampFuture` (e.g., from ~30m down) to shrink the window while keeping tx-timestamp semantics. Partial mitigation; no fork.  
  - **Hybrid check (consensus):** Keep tx timestamp but also require ban expiry at block time (e.g., treat ban as active if `blockTimestamp < banExpiry`); gate via trigger. More complex than a full switch.  
  - **Block-count TTL (consensus + format change):** Introduce a block-count-based TTL for new bans/invites (deterministic, no clocks); would need new tx version/field and dual semantics because legacy data is time-based. Requires trigger and coordinated rollout.
- **Consensus impact:** Any change to consensus-time basis (block timestamp, hybrid, or block-count TTL) is a consensus change and needs a feature trigger and rollout. Tightening `maxTransactionTimestampFuture` alone is non-consensus.

## Stale group invites/bans accumulate in DB

- **What happens:** Expired group invites (and expired bans) are not auto-pruned; they remain in `GroupInvites`/`GroupBans` tables. They are deleted only when explicitly consumed (join, cancel, ban lift) or manually removed. Expiry checks simply ignore stale rows (`banExists(..., txTimestamp)`, invite expiry validation).
- **Impact on users:** DB grows with stale rows; API clients might see older entries unless filtered. Cancel transactions for invites/bans still succeed because the row exists, which can be surprising if UI hides them.
- **Underlying cause:** No background cleanup or opportunistic delete-on-expiry; current logic favors idempotence and replay safety by leaving rows intact until acted upon.
- **Risks of changing DB behavior:** Auto-deleting on expiry could break cancel flows (row no longer exists), change orphan/reorg replay expectations, and add write paths in validation. Background pruning must be carefully non-consensus or triggered consistently to avoid divergence across nodes.
- **Why not included now:** For the invite-expiry fix we’re leaving expired entries stored but ignored (matching existing ban behavior) to avoid new DB mutation paths, reduce risk to orphaning/replay, and keep cancel transactions functional. Cleanup can be considered separately as a non-consensus maintenance task with proper safeguards.
