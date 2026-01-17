# Implementing Invite Expiration in Qortal Groups

Canonical rule (post-trigger, consensus path): after `groupInviteExpiryHeight`, a closed-group join only finalizes membership if a matching invite exists and the deterministic transaction timestamp used for finalization is **<= inviteExpiry** (`expiry == null` = never). If expired, ignore the invite and fall back to the join-request path; do not reject the transaction.

## Feature Trigger: Invite Expiration Enforcement

Gate the rule behind a blockchain feature trigger. Add a new enum entry to `BlockChain.FeatureTrigger` and a getter (e.g. `getGroupInviteExpiryHeight()`), and add the key to all chain configs (mainnet/testnet and every test fixture). Use a far-future placeholder on mainnet and low activation heights for tests. On startup, `BlockChain` validates that every enum entry exists in `featureTriggers`[1].

## Enforcing Expiration on Group Join

The core logic lives in Group.join(...) (file: org/qortal/group/Group.java). Currently, when a user attempts to join a closed group, the code checks for an existing invite but does not verify if it’s expired. Specifically, it retrieves any pending GroupInviteData for the joiner and, if none is found and the group is closed, converts the join to a pending request; otherwise, it uses the invite[3][4]:

```
GroupInviteData groupInviteData = this.getInvite(joiner.getAddress());

if (groupInviteData == null && !groupData.isOpen()) {
    // Closed group with no invite: create join request
    this.addJoinRequest(joiner.getAddress(), joinTxData.getSignature());
    joinTxData.setInviteReference(null);
    return;
}

// If invite exists, use it (no expiration check yet)
if (groupInviteData != null) {
    joinTxData.setInviteReference(groupInviteData.getReference());
    this.deleteInvite(joiner.getAddress());  // consume invite
} else {
    joinTxData.setInviteReference(null);
}

// Add new member to group
this.addMember(joiner.getAddress(), joinTxData);
...
```

We need to insert an expiration check after retrieving groupInviteData but before deciding how to proceed. The invite’s expiry timestamp is stored when the invite is created[5] (it’s set to inviteTimestamp + timeToLive*1000 if TTL > 0). Our plan (matching the canonical rule):

1. Fetch the next block height (tip height + 1) and compare to the trigger height via `BlockChain.getGroupInviteExpiryHeight()`.
2. If active (`nextHeight >= triggerHeight`) and the invite has an expiry (`expiry != null`), compare the **join transaction timestamp** (deterministic, matches ban semantics) against the expiry.
3. If `joinTxTimestamp > expiry`, treat the invite as expired (inclusive boundary: `<= expiry` is valid). Set `groupInviteData = null` to force the “no invite” path; leave the expired invite stored and do not delete it.
4. Pre-trigger: preserve legacy behavior unchanged.

In code, it will look something like:

```
GroupInviteData groupInviteData = this.getInvite(joiner.getAddress());

// If feature trigger active, check for expired invite
long triggerHeight = BlockChain.getInstance().getGroupInviteExpiryHeight();
int nextBlockHeight = this.repository.getBlockRepository().getBlockchainHeight() + 1;
long now = joinGroupTransactionData.getTimestamp(); // transaction timestamp, deterministic, matches ban semantics
if (nextBlockHeight >= triggerHeight
        && groupInviteData != null
        && groupInviteData.getExpiry() != null
        && now > groupInviteData.getExpiry()) {
    // Invite has expired – ignore it (leave stored)
    groupInviteData = null;
}

if (groupInviteData == null && !groupData.isOpen()) {
    // No valid invite for a closed group: create join request
    this.addJoinRequest(joiner.getAddress(), joinTxData.getSignature());
    joinTxData.setInviteReference(null);
    return;
}
if (groupInviteData != null) {
    // Valid invite present
    joinTxData.setInviteReference(groupInviteData.getReference());
    this.deleteInvite(joiner.getAddress());
} else {
    joinTxData.setInviteReference(null);
}
this.addMember(joiner.getAddress(), joinTxData);
```

This ensures that after the trigger height, an invite past its TTL will no longer grant immediate membership. Instead, the join will fall back to a join request (the expired invite remains in the database; we ignore it, consistent with ban handling). Before the trigger height, behavior remains unchanged for backward compatibility.

Note: Use the transaction timestamp (join/invite) for expiry checks to avoid local-clock drift and to match ban expiry semantics. Do not use `NTP.getTime()` in consensus paths. Transaction timestamps can be forward-dated (~30m) and backdated within the transaction-expiry window (~24h), so an invite can still be consumed by a JOIN whose timestamp lies inside the TTL even if the containing block is later; that’s expected under tx-timestamp semantics.

### Join-first auto-approval (invite arrives after join request)

For pending join requests, TTL does **not** gate the approval (pre- and post-trigger). Any matching invite auto-approves the stored request, even if the invite’s TTL would be expired by wall-clock time. Notes:

- Time basis: ignore TTL when approving a pending request; the invite’s transaction timestamp is used only for deterministic bookkeeping, not for expiry checks.
- Flow: only auto-approve if a pending join request exists; consume that request when the invite confirms.
- Trigger: no feature-trigger gating in this path; behavior is unchanged before/after activation.
- TTL=0 sentinel: still means “never expires” (consistent with invite-first path).

### API filtering (client-facing, non-consensus)

Invite-list endpoints filter expired invites using the current chain-tip block timestamp with an inclusive boundary (`expiry >= tip`), treating `expiry == null` as never expiring, and skipping filtering if there is no chain tip (to avoid local clock). Filtering is unconditional (no feature trigger) and may hide invites that could still be consumed via back/forward-dated JOINs—this divergence is intentional as a UX safety layer.

Residual window (intentional): because we keep transaction-timestamp semantics, the forward/backdating window (~30m / ~24h) remains usable to consume an invite outside its wall-clock TTL. Closing that would need a block-time or hybrid basis (see docs/OTHER_ISSUES.md); out of scope for this fix.

## API: Ignoring Expired Invites in Results

Update the API endpoints that list pending invites so that expired invites are filtered out. The relevant endpoints are in GroupsResource.java:

- GET /groups/invites/{address} – returns invites where the given address is the invitee[6][7].
- GET /groups/invites/group/{groupid} – returns invites for a given group[8][9].

If the node has no chain tip yet (`getLastBlock() == null`, e.g., very early startup), skip filtering and return invites unfiltered to avoid using local time.

Filter expired invites unconditionally (no feature trigger), using a deterministic basis such as the current chain tip’s block timestamp. Treat `expiry == null` (TTL=0) as never expires. For example:

```
List<GroupInviteData> invites = repository.getGroupRepository().getInvitesByInvitee(invitee);
BlockData chainTip = repository.getBlockRepository().getLastBlock();
if (chainTip == null)
    return invites; // no tip yet; do not use local clock
long chainTipTimestamp = chainTip.getTimestamp();
return invites.stream()
    .filter(inv -> inv.getExpiry() == null || inv.getExpiry() >= chainTipTimestamp) // inclusive boundary
    .collect(Collectors.toList());
```

Apply the same filtering for getInvitesByGroupId. This hides expired invites from clients even before consensus enforcement and may hide invites that could still be used via back/forward-dated JOINs; this pre-trigger, chain-tip-based filtering is intentional as a soft mitigation/UX safety layer and should be documented as a divergence from consensus time basis (tx timestamp).

Pagination note: limits/offsets are applied before filtering, so a page can return fewer items than the requested limit once expired invites are dropped, and filtered-out entries still count toward the offset (older invites can be skipped over). Sorting/reverse order is preserved because filtering happens after the repository query. This behavior is expected under the pre-trigger, client-facing filtering.

## Unit Tests for Invite Expiration

Extend the test suite (e.g., org.qortal.test.group.MiscTests) to cover:

- Invite-first, valid before expiry → member added post-trigger.
- Invite-first, expired → join becomes request, no membership; invite stored but ignored.
- Join-first, invite later valid → auto-add member.
- Join-first, invite later expired (by wall clock) still auto-adds because TTL is ignored for pending requests (documented behavior).
- TTL=0 non-expiring invite → still works.
- (Optional) Expired invite with backdated JOIN (tx timestamp ≤ expiry) still succeeds to document tx-timestamp window.

Use `BlockChain.getGroupInviteExpiryHeight()` with `BlockUtils.mintBlocks(...)` to flip pre/post behavior; set test configs’ `groupInviteExpiryHeight` low (0/1). For API filtering, add tests (e.g., new GroupApiInviteTests) that use chain-tip block timestamp as “now,” skip filtering when there is no tip, and assert expired invites are omitted while TTL=0/unexpired remain. Document the divergence: API uses chain-tip time; consensus uses transaction timestamps and accepts the forward-dating window.

[1] BlockChain.java

https://github.com/QORT/qortal/blob/d81729d9f7cfef3060e15cbaf8563e89b8e72776/src/main/java/org/qortal/block/BlockChain.java

[2] blockchain.json

https://github.com/QORT/qortal/blob/d81729d9f7cfef3060e15cbaf8563e89b8e72776/src/main/resources/blockchain.json

[3] [4] [5] Group.java

https://github.com/QORT/qortal/blob/d81729d9f7cfef3060e15cbaf8563e89b8e72776/src/main/java/org/qortal/group/Group.java

[6] [7] [8] [9] [14] GroupsResource.java

https://github.com/QORT/qortal/blob/d81729d9f7cfef3060e15cbaf8563e89b8e72776/src/main/java/org/qortal/api/resource/GroupsResource.java

[10] GroupInviteData.java

https://github.com/QORT/qortal/blob/d81729d9f7cfef3060e15cbaf8563e89b8e72776/src/main/java/org/qortal/data/group/GroupInviteData.java

[11] [12] [13] MiscTests.java

https://github.com/QORT/qortal/blob/d81729d9f7cfef3060e15cbaf8563e89b8e72776/src/test/java/org/qortal/test/group/MiscTests.java

[15] GroupInviteTransaction.java

https://github.com/QORT/qortal/blob/d81729d9f7cfef3060e15cbaf8563e89b8e72776/src/main/java/org/qortal/transaction/GroupInviteTransaction.java
