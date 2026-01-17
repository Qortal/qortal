# Investigating Qortal’s Group Invite Expiration Issue

## Overview of Qortal Group Invites and Joins

Group Types: In Qortal, on-chain groups can be Open (public) or Closed (private). Membership in open groups is permissionless – anyone can join by broadcasting a JOIN_GROUP transaction. Closed groups, however, require an invitation and approval process:

- Open Groups: A user joins by sending a JOIN_GROUP transaction. Once that transaction confirms, the user is immediately added as a group member.
- Closed Groups: Joining is a two-step process:
- A group admin sends a GROUP_INVITE transaction targeting the invitee’s address. This acts as an invitation (or an approval if a join request already exists).
- The invitee accepts by sending their own JOIN_GROUP transaction for that group. This join will only succeed (result in membership) if a corresponding invite exists (or if the join came first and is later “approved” by an invite).

The order of these two transactions doesn’t strictly matter. If the JOIN_GROUP comes before the invite (user requests to join before being invited), it is stored as a pending join request in the group’s data. When the admin later issues a GROUP_INVITE, the code detects the pending request and immediately adds the user to the group (consuming both the invite and the stored request). Conversely, if the GROUP_INVITE comes first for a closed group, it sits as a pending invite until the user eventually sends a matching JOIN_GROUP transaction to accept it[1][2]. In that case, the join transaction will find the pending invite and the user will be added to the group upon the join’s confirmation[3].

Private Group Messaging: A closed group’s messages can technically be seen by anyone on-chain, but they are usually encrypted with a group key (derived from members’ public keys) to preserve privacy. Only group members possess the decryption key. Non-members cannot send messages to the group (attempts would be rejected), but they could see ciphertext of messages (hence the need for encryption for privacy).

Cancelling Invites: The Qortal core does support a manual invite cancellation via a CANCEL_GROUP_INVITE transaction type (which can be used by an admin to revoke an outstanding invite)[4][5]. However, absent manual cancellation, an issued invite remains in effect indefinitely unless the intended expiration mechanism works. Currently, users are working around unwanted late acceptances by removing the member (kicking them) after they join, which is not ideal.

## The Intended “Expiration” Mechanism

When creating a GROUP_INVITE transaction, the inviter specifies a Time-To-Live (TTL) for the invite, in seconds. This is meant to define how long the invite remains valid. The core sets an expires_when timestamp by adding TTL to the invite’s creation time:

- In the code, GroupInviteTransactionData includes a field timeToLive (TTL in seconds)[6]. When processing the invite, the core calculates the expiration time as invite_timestamp + TTL * 1000 (milliseconds) and stores it in the GroupInvites repository table[7]. If TTL is 0, they treat it as “no expiration” (the expiry is stored as NULL)[7].

So, for example, an invite with timeToLive = 3600 seconds will have an expiry timestamp ~1 hour after the invite’s creation time. The invite and its expiry are saved in the node’s database (GroupInvites table) via the repository layer[7].

Expectation: If the current time passes that expires_when timestamp before the invite is accepted, the invite should be considered expired/invalid. In practice, that would mean if the invitee tries to join after the expiry time, the join should not be auto-approved – effectively the invite should no longer count, and the join might instead be treated as a new request or be rejected.

## What’s Happening in Practice (Bug)

Invites Never Expire: In the current Qortal Core implementation, once an invite is issued, it can be accepted at any future time, even long after the supposed expiration. The TTL field and expires_when timestamp are being recorded but not actually enforced.

Code Analysis: The code responsible for handling group joins and invites does not check the expiration at all when deciding whether a join can be approved by an invite:

- When a user’s JOIN_GROUP transaction is processed for a closed group, the core simply checks if there is a pending invite for that user in the group via getInvite(groupId, userAddress). If an invite is found, the code proceeds to accept the join[3]; if no invite is found and the group is closed, the join is stored as a pending request[8]. Crucially, there is no check comparing current time to the invite’s expiry. The presence of an invite in the DB is enough to cause an auto-join, regardless of its age.
- The GroupInviteData object does carry the expiry timestamp[9][10], and the invite is stored in the repository with that expires_when field. However, retrieval functions do not filter out or consider expiration. For example, GroupRepository.getInvite(groupId, invitee) simply selects the invite row by group and invitee, returning it if present[11]. It doesn’t check whether expires_when is in the past. Similarly, listing all pending invites for a group or invitee returns all entries unfiltered[12][13]. So an expired invite remains in the “pending invites” list returned by the API, and more importantly, remains available to satisfy a join.
- The invite creation logic only validates that TTL is non-negative (it allows zero or positive values)[14], but beyond setting the expiry timestamp, there is no further logic making use of that timestamp. There is no scheduled task purging old invites, nor any validation in the JOIN_GROUP processing to reject or ignore an invite that has passed its expiry.

Given this, any GROUP_INVITE entry persists indefinitely until one of three events: the invite is accepted (join happens), the invite is manually canceled by an admin (CANCEL_GROUP_INVITE tx), or the group is deleted entirely. If none of those occur, the invite sits in the DB forever, effectively never expiring on its own.

Example Scenario: Suppose an admin invites Alice to a closed group with a TTL of 1 day. Internally, the invite’s expires_when is set to (invite_timestamp + 86,400,000 ms). If Alice waits a week and then sends a JOIN_GROUP, the core will still find the invite entry and treat it as valid, adding Alice to the group – even though a week is well past the intended 1-day validity. This matches the observed behavior that “invites never expire”.

## Root Cause of the Issue

The root cause is simply that the expiration feature was never fully implemented in the invite acceptance logic. The TTL and expiry are recorded, but no code checks or acts on the expiry timestamp when it should:

- No expiration check on join: The group.join(...) method (called when processing a JOIN_GROUP tx) should ideally ignore or reject an invite if current_time > invite.expiry. Currently it does not – it unconditionally uses the invite if present[8][3]. There is no conditional like “if invite is expired, consider it not found.”
- No automatic cleanup: There is no background process or trigger to remove expired invites from the GroupInvites table. The invite remains until explicitly removed (by acceptance or cancellation). The repository’s deleteInvite() is only called during normal invite consumption (on join) or when processing a CANCEL_GROUP_INVITE[15][16]. If an invite quietly expires, it stays in the table and continues to be returned as a pending invite.

Essentially, the expiration is only a timestamp field with no logic attached. This appears to be an oversight in the implementation. The design clearly intended invites to have a limited lifetime (hence the TTL field), but the enforcement mechanism is missing or incomplete. The result is that “expired” invites are treated no differently than active invites by the core.

## Confirming the Findings in Code

To illustrate the above, let’s look at the relevant code snippets:

- Setting the expiry when creating an invite: In the Group.invite(...) method, the code calculates the expiry as shown below. If timeToLive is non-zero, it adds that many seconds to the invite’s timestamp (note: timestamps are in milliseconds since epoch) and saves the invite with this expires_when value. Zero TTL yields expiry = null (meaning no expiration)[7]:
```
// In Group.java, handling a new invite
int timeToLive = groupInviteTransactionData.getTimeToLive();
Long expiry = null;
if (timeToLive != 0)
    expiry = groupInviteTransactionData.getTimestamp() + timeToLive * 1000;

GroupInviteData groupInviteData = new GroupInviteData(groupId, inviterAddress, invitee, expiry, txSignature);
groupRepository.save(groupInviteData);
```

This confirms TTL is stored correctly (in milliseconds). For example, a TTL of 300 seconds will produce an expires_when roughly 5 minutes after the invite’s timestamp.

- No check at join time: When a JOIN_GROUP transaction is processed, the code simply does:
```
GroupInviteData inviteData = groupRepository.getInvite(groupId, joinerAddress);
if (inviteData == null && !groupData.isOpen()) {
    // No invite found for a closed group – treat this join as a pending request
    this.addJoinRequest(joinerAddress, joinTxSignature);
    // ... (set joinTx inviteReference to null, etc.)
    return;
}
if (inviteData != null) {
    // Invite found – proceed to use it
    joinTxData.setInviteReference(inviteData.getReference());
    groupRepository.deleteInvite(groupId, joinerAddress); // consume the invite
}
// Then addMember to group unconditionally...
this.addMember(joinerAddress, joinGroupTransactionData);
```

In the actual Group.join() implementation, you can see that if an invite exists, they immediately delete the invite and add the member[3][17]. There is no conditional check on the invite’s expiry here – meaning even if the current time is well beyond inviteData.expiry, the code treats inviteData != null as sufficient for validity.

- Invite retrieval doesn’t filter expiry: The repository call getInvite(groupId, invitee) simply fetches the row if it exists[11]. It does convert the SQL NULL to a null Long for expiry (lines not shown here), but it doesn’t ignore expired ones. In other words, even if expires_when is in the past, getInvite will still return a GroupInviteData object (with an expiry value set). Nothing in the service layer subsequently discards it.
For reference, the SQL used is:

```
SELECT inviter, expires_when, reference 
FROM GroupInvites 
WHERE group_id = ? AND invitee = ?;
```

[11]

If expires_when is past, that fact is not used – there’s no “…AND expires_when > currentTime” in the query or any post-query logic to drop it.

These code findings confirm why, in practice, “group invites never expire”. The expiration timestamp is effectively inert.

## Implications and Current Workaround

Because of this bug, an invitee can join a closed group long after the invite was meant to lapse. This can be problematic if an admin intended the invite to be short-lived (for security or policy reasons). Admins currently have to monitor and manually remove any late joiners (by kicking them) if they no longer want that person in the group. As you pointed out, this is not how the feature should work – users shouldn’t have to manually enforce expiration.

There is a manual cancellation transaction (CANCEL_GROUP_INVITE) that an admin can issue before the invite is accepted[5]. It will remove the pending invite from the repository, effectively rescinding it. However, this is a manual step and not tied to the TTL at all. It’s useful if an admin changes their mind or made a mistake, but it doesn’t solve the expired invite problem unless the admin preemptively cancels after the TTL passes. Relying on that is not practical.

To answer your question: Yes, the core currently lacks any automatic expiry enforcement. The TTL is supposed to mean “this invite is only good for X time,” but due to the missing checks, any invite can be accepted at any time until explicitly canceled or consumed. This is clearly unintended behavior given the design.

## Proposed Solution and Recommendations

Post-trigger canonical rule (consensus path): after `groupInviteExpiryHeight`, closed-group membership finalizes only if a matching invite exists and the deterministic transaction timestamp used for finalization is **<= inviteExpiry** (`expiry == null` = never). If expired, ignore the invite and fall back to a join request; do not reject the transaction. Use transaction timestamps, never local time. Transaction timestamps can be forward-dated (~30m) and backdated within the transaction-expiry window (~24h), so a JOIN whose timestamp lies inside the TTL can still consume an invite even if the block is mined later; that’s expected with tx-timestamp semantics.

To fix this issue, the Qortal Core needs to incorporate the invite expiration logic where appropriate. Based on our analysis, the following changes are recommended:

- Enforce expiration on join: Modify the join processing logic to check the invite’s expiry before using it. If an invite exists but its expiry time has passed (relative to the join transaction’s timestamp), the code should treat it as if no valid invite exists. In practical terms, for a closed group, that would mean:
- The joining user’s JOIN_GROUP transaction would no longer find a valid invite, so it would be handled as a join request instead of an immediate join. The expired invite stays stored but is ignored (no delete).
- As a result, the user is not added to the group automatically. The on-chain effect is that their join transaction sits as a pending request, awaiting approval.
- The group admin, seeing the join request, can then decide to issue a fresh GROUP_INVITE if they still want to let the user in.

Implementing this would involve a small addition, gated by a feature trigger such as `groupInviteExpiryHeight` and using the next block height comparison (`nextHeight >= groupInviteExpiryHeight`):

```
// Group.join(...) uses the group-scoped helper that fetches by invitee
GroupInviteData inviteData = this.getInvite(user);
if (inviteData != null && inviteData.getExpiry() != null) {
    int nextHeight = repository.getBlockRepository().getBlockchainHeight() + 1;
    long now = joinTx.getTimestamp(); // deterministic, matches ban semantics
    if (nextHeight >= BlockChain.getInstance().getGroupInviteExpiryHeight()
            && now > inviteData.getExpiry()) {
        // Invite is expired – ignore it (leave stored)
        inviteData = null;
    }
}
if (inviteData == null && !groupData.isOpen()) {
    // handle as join request...
}
if (inviteData != null) {
    // proceed with invite as before...
}
```

This ensures expired invites can no longer approve joins once the trigger height is reached in the invite-first path, using transaction timestamps (not local clock) for determinism. Note the ~30m forward-dating and ~24h backdating (transaction expiry) windows.

- Join-first auto-approval: when an invite arrives after a stored join request, TTL is **not** enforced; any matching invite approves the pending request. Use the invite’s transaction timestamp only for deterministic bookkeeping, not for expiry checks. This preserves the “request + later approval” flow regardless of invite age and is unchanged by the feature trigger.
- No pruning: leave expired invites stored; enforce via expiry checks and API filtering only.
- API filtering: list endpoints should filter expired invites using the chain-tip block timestamp (inclusive boundary: `expiry >= chainTipTimestamp`), treat `expiry == null` as never, and skip filtering if there is no chain tip yet (`getLastBlock() == null`) to avoid local-time drift. Filtering is unconditional (no feature trigger) and diverges from consensus time basis.
- Timestamp basis and dating windows: consensus paths use transaction timestamps and accept the ~30-minute forward-dating window allowed by `maxTransactionTimestampFuture` and backdating within the transaction-expiry window (~24h); API filtering uses chain-tip block time. A backdated JOIN could still consume an invite the API hides—this divergence is expected and should be documented.
- Testing the changes: After implementing, thorough testing is needed:
- Issue an invite with a short TTL (e.g., 1 minute), wait for it to expire (by minting blocks so joinTx.timestamp > expiry), then attempt to join. The expected result is the join becomes a pending request (and the user is not added until a new invite is sent).
- Conversely, test joining before expiry to ensure it still works normally.
- Test TTL = 0 (no expiry) to ensure those invites continue to work indefinitely (they should, by design).
- Ensure that manual cancellation (CANCEL_GROUP_INVITE) still works (cancelling an invite should trump everything regardless of TTL).
- Join-first flow: verify that an aged/“expired by wall clock” invite still auto-approves a pending request, since TTL is ignored in that path pre- and post-trigger (documented behavior).

With these fixes, the system will align with the intended behavior: invites will only be valid for the specified time window in the invite-first flow. The documented join-first exception still applies (any matching invite approves a pending request even if the TTL would have expired by wall clock), so expiry enforcement is one-sided by design. After the window, invite-first joins fall back to requests and cannot auto-join unless re-approved. This removes the burden on users to “manually fix” the situation by kicking unwanted late-joiners.

## Conclusion

The investigation confirms that the invite expiration issue is caused by a missing enforcement in the code. The Qortal Core defines an expiry for group invites but never uses it when it actually matters, i.e., during join validation. The solution is to introduce checks in the invite-first join path (and API filtering) to honor the expiration timestamp, while explicitly documenting that join-first auto-approvals ignore TTL. By making these changes, group admins can rely on invites expiring for the invite-first flow and understand the documented exception for pending requests.

## Activation status and final semantics

- Trigger wiring: the behavior is gated by `groupInviteExpiryHeight` with a placeholder height of `99999999` on mainnet, immediate activation on testnet (height `0/1`), and immediate activation in all test fixtures; a future release will set a real mainnet activation height.
- Semantics (invite-first): after activation, invite expiry is enforced using the join transaction timestamp (inclusive boundary, `expiry == null` = never); expired invites are treated as absent and fall back to join requests; pre-trigger behavior is unchanged.
- Semantics (join-first): TTL is intentionally ignored for pending-request approvals both pre- and post-trigger; any matching invite approves the request, and `expiry == null` still means non-expiring.
- Time basis and windows: transaction timestamps (not local clock) drive expiry checks; the allowed forward/backdating windows remain, so a backdated join inside the TTL can still succeed even if the block is later.
- API filtering: the invite-list endpoints filter using the chain-tip timestamp (inclusive, `expiry == null` = never) and skip filtering if there is no chain tip, so clients see only unexpired/non-expiring invites by default.
- Activation plan: keep the placeholder trigger on mainnet until a coordinated release sets a concrete height and accompanying release notes/changelog entry.

References:

- Qortal Core source code showing invite TTL stored but not enforced[7][3].
- Qortal Core source code showing join processing does not consider invite expiration[8][11].

[1] [2] [3] [7] [8] [15] [17] Group.java

https://github.com/QORT/qortal/blob/d81729d9f7cfef3060e15cbaf8563e89b8e72776/src/main/java/org/qortal/group/Group.java

[4] [5] GroupsResource.java

https://github.com/QORT/qortal/blob/d81729d9f7cfef3060e15cbaf8563e89b8e72776/src/main/java/org/qortal/api/resource/GroupsResource.java

[6] GroupInviteTransactionData.java

https://github.com/QORT/qortal/blob/d81729d9f7cfef3060e15cbaf8563e89b8e72776/src/main/java/org/qortal/data/transaction/GroupInviteTransactionData.java

[9] [10] GroupInviteData.java

https://github.com/QORT/qortal/blob/d81729d9f7cfef3060e15cbaf8563e89b8e72776/src/main/java/org/qortal/data/group/GroupInviteData.java

[11] [12] [13] [16] HSQLDBGroupRepository.java

https://github.com/QORT/qortal/blob/d81729d9f7cfef3060e15cbaf8563e89b8e72776/src/main/java/org/qortal/repository/hsqldb/HSQLDBGroupRepository.java

[14] GroupInviteTransaction.java

https://github.com/QORT/qortal/blob/d81729d9f7cfef3060e15cbaf8563e89b8e72776/src/main/java/org/qortal/transaction/GroupInviteTransaction.java

[18] NTP.java

https://github.com/QORT/qortal/blob/d81729d9f7cfef3060e15cbaf8563e89b8e72776/src/main/java/org/qortal/utils/NTP.java
