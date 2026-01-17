# Commits Log

This document tracks each commit made for the invite-expiration work. For every TODO sub-item we complete, add a new entry before committing:

- Use the sub-item title as the commit message.
- List the files changed.
- Describe what the changes do.
- Explain why the changes are needed.

Template for entries:

```
## <commit message / TODO sub-item title>
- Files: <comma-separated paths>
- What: <concise description of the changes>
- Why: <reasoning/intent>
```

## Add feature trigger enum entry
- Files: src/main/java/org/qortal/block/BlockChain.java
- What: Added `groupInviteExpiryHeight` to the `FeatureTrigger` enum and exposed a getter to read it from chain configs.
- Why: This prepares the blockchain config/validation to recognize the new invite-expiration trigger and makes it accessible to invite-handling code.

## Expose feature trigger getter
- Files: src/main/java/org/qortal/block/BlockChain.java
- What: Documented the `getGroupInviteExpiryHeight` convenience method with the intended activation scope (invite-first expiry enforcement).
- Why: Clarifies the purpose of the getter so future invite-expiry logic and reviews have an explicit reference to its role.

## Wire mainnet config placeholder
- Files: src/main/resources/blockchain.json
- What: Added a placeholder `groupInviteExpiryHeight` entry (set to 99999999) to the mainnet featureTriggers map.
- Why: Ensures mainnet config recognizes the new invite-expiry trigger while keeping activation far in the future until a rollout height is chosen.

## Wire testnet config
- Files: testnet/testchain.json
- What: Added `groupInviteExpiryHeight` with immediate activation (height 0) to the testnet featureTriggers map.
- Why: Ensures testnet nodes recognize and activate invite-expiry logic right away for testing and validation.

## Wire test fixtures
- Files: src/test/resources/test-chain-v2.json, src/test/resources/test-chain-v2-block-timestamps.json, src/test/resources/test-chain-v2-disable-reference.json, src/test/resources/test-chain-v2-founder-rewards.json, src/test/resources/test-chain-v2-leftover-reward.json, src/test/resources/test-chain-v2-minting.json, src/test/resources/test-chain-v2-penalty-fix.json, src/test/resources/test-chain-v2-qora-holder-extremes.json, src/test/resources/test-chain-v2-qora-holder-reduction.json, src/test/resources/test-chain-v2-qora-holder.json, src/test/resources/test-chain-v2-reward-levels.json, src/test/resources/test-chain-v2-reward-scaling.json, src/test/resources/test-chain-v2-reward-shares.json, src/test/resources/test-chain-v2-self-sponsorship-algo-v1.json, src/test/resources/test-chain-v2-self-sponsorship-algo-v2.json, src/test/resources/test-chain-v2-self-sponsorship-algo-v3.json
- What: Added `groupInviteExpiryHeight` with low activation (0) across all test-chain fixture configs.
- Why: Keeps all test fixtures aligned with startup validation and allows tests to exercise invite-expiry logic immediately.

## (No commit) Sanity-check trigger coverage
- Files: (inspection only; no file changes)
- What: Verified all chain configs with featureTriggers already include `groupInviteExpiryHeight`; no additional files needed updates.
- Why: Ensures startup validation won’t fail on overlooked configs before proceeding to code changes.

## Gate invite-first expiry by trigger
- Files: src/main/java/org/qortal/group/Group.java
- What: Introduced trigger-gated invite expiry handling in `Group.join(...)`, using next block height to decide when to apply invite expiry logic.
- Why: Ensures invite expiry enforcement is only evaluated after the `groupInviteExpiryHeight` activation point.

## Use join tx timestamp for expiry check
- Files: src/main/java/org/qortal/group/Group.java
- What: Added join-transaction-timestamp-based expiry evaluation, respecting TTL=0 as never expiring and an inclusive `<= expiry` boundary.
- Why: Ensures invite validity checks rely on deterministic transaction timestamps instead of local time before applying invite consumption.

## Treat expired invite as absent in join
- Files: src/main/java/org/qortal/group/Group.java
- What: When the invite is expired post-trigger, `Group.join` now treats it as missing, falling back to a join request for closed groups without deleting the stale invite.
- Why: Prevents expired invites from granting membership while preserving the stored invite for deterministic replay/orphan handling.

## Preserve pre-trigger join behavior
- Files: src/main/java/org/qortal/group/Group.java
- What: Clarified the trigger gate in `Group.join` to note legacy behavior remains unchanged before activation.
- Why: Documents that expiry enforcement is strictly post-trigger, reassuring reviewers about pre-trigger compatibility.

## Honor TTL=0 and inclusive boundary
- Files: src/main/java/org/qortal/group/Group.java
- What: Confirmed invite expiry logic treats `expiry == null` as non-expiring and uses an inclusive `timestamp <= expiry` boundary with an explanatory comment.
- Why: Ensures invite validation semantics are explicit and deterministic, matching the intended TTL sentinel and boundary behavior.

## Document join-first time basis
- Files: src/main/java/org/qortal/group/Group.java
- What: Added a code comment clarifying that TTL/expiry is intentionally ignored when an invite approves a pending join request (join-first path), pre- and post-trigger.
- Why: Documents the deliberate legacy-preserving behavior so reviewers know this path remains TTL-agnostic.

## Auto-approve pending request
- Files: src/main/java/org/qortal/group/Group.java
- What: Clarified in code that a matching invite auto-approves and consumes a pending join request in the join-first path, maintaining legacy behavior.
- Why: Documents the intended behavior for reviewers and affirms no trigger/TTL gating is applied when approving stored requests.

## Honor TTL=0 sentinel (join-first)
- Files: src/main/java/org/qortal/group/Group.java
- What: Noted that TTL=0 invites remain valid in the join-first auto-approval path.
- Why: Documents that the non-expiring sentinel applies consistently even when approving stored join requests.

## Filter invites-by-invitee API
- Files: src/main/java/org/qortal/api/resource/GroupsResource.java
- What: Added chain-tip-based filtering for `/groups/invites/{address}` invites, treating `expiry == null` as non-expiring, using inclusive `expiry >= tip`, and skipping filtering when no chain tip is present; introduced a helper to reuse for other invite listings.
- Why: Hides expired invites from the API without relying on local time and prepares for consistent invite filtering across endpoints.

## Filter invites-by-group API
- Files: src/main/java/org/qortal/api/resource/GroupsResource.java
- What: Applied the same chain-tip-based invite filtering to `/groups/invites/group/{groupid}` via the shared helper, respecting `expiry == null`, inclusive boundary, and no-tip passthrough.
- Why: Ensures group-level invite listings also hide expired entries without depending on local time.

## Document unconditional filtering
- Files: src/main/java/org/qortal/api/resource/GroupsResource.java
- What: Updated swagger summaries for invite endpoints to explicitly state chain-tip-based filtering (inclusive boundary, `expiry == null` sentinel) and left filtering unconditional (no feature trigger) with no local-clock fallback when tip is missing.
- Why: Makes the filtering behavior clear to API consumers and documents the divergence from consensus time basis.

## Test invite-first expiry enforcement
- Files: src/test/java/org/qortal/test/group/MiscTests.java
- What: Added invite-first tests covering valid-before-expiry membership and expired-invite fallback to join request (invite retained), with helper builders for timestamped joins and TTL invites.
- Why: Verifies post-trigger invite expiry enforcement matches intended behavior in invite-first flow.

## Test join-first behavior
- Files: src/test/java/org/qortal/test/group/MiscTests.java
- What: Added join-first tests confirming invites auto-approve pending requests regardless of TTL/age (including TTL=0) and that backdated requests still auto-add when an invite arrives later.
- Why: Documents and validates the TTL-agnostic join-first path and back/forward-dated request approval behavior.

## Test backdated/forward-dated join window
- Files: src/test/java/org/qortal/test/group/MiscTests.java
- What: Added invite-first test showing a backdated join within the invite’s expiry window still adds a member (documenting tx-timestamp dating behavior).
- Why: Captures the expected behavior of the transaction-timestamp window for invite consumption.

## Test API invite filtering
- Files: src/test/java/org/qortal/test/group/MiscTests.java
- What: Added an API-level test verifying chain-tip-based filtering hides expired invites while retaining non-expiring ones for both invitee and group endpoints.
- Why: Confirms the filtering helper and endpoints omit expired invites without local clock use.

## Test pre/post trigger activation
- Files: src/test/java/org/qortal/test/group/MiscTests.java
- What: Added coverage showing expired invites auto-add pre-trigger but fall back to join requests post-trigger, using a temporary feature trigger override via reflection.
- Why: Validates trigger-gated behavior for invite expiry enforcement across activation boundaries.


## Update docs with final semantics
- Files: docs/INVITE_EXPIRATION.md, docs/IMPLEMENTATION.md
- What: Documented finalized invite-expiry semantics (trigger, invite-first enforcement with join timestamp, join-first TTL-agnostic behavior, transaction-timestamp dating windows, and API chain-tip filtering divergence).
- Why: Keeps design docs aligned with implemented behavior and test coverage.

## Document activation plan
- Files: docs/CONSENSUS_CHANGE.md
- What: Added activation plan guidance (placeholder trigger on mainnet, low heights on testnet/fixtures, follow-up release to set real height after coverage).
- Why: Clarifies rollout steps for the consensus change.

# Added Tests (details)

## testInviteFirstValidBeforeExpiryAddsMember
- What: Invite-first flow where join timestamp is before invite expiry adds the member and consumes the invite; no join request persists.
- How: Mint invite with short TTL, join using a timestamp before expiry, assert membership and invite/request cleanup.
- Why: Verifies the success path of invite-first expiry enforcement.
- Output:
  - [testInviteFirstValidBeforeExpiryAddsMember] START
  - Join timestamp 1764917672854 before expiry 1764917674354
  - Membership? true
  - Invite should be consumed -> null
  - Join request should be absent -> null
  - PASS

## testInviteFirstExpiredCreatesRequest
- What: Invite-first flow with expired invite results in a stored join request; invite remains stored.
- How: Mint invite with 1s TTL, join with timestamp past expiry, assert no membership, join request present, invite retained.
- Why: Confirms expired invites are treated as absent and fall back to request handling.
- Output:
  - [testInviteFirstExpiredCreatesRequest] START
  - Join timestamp 1764917674379 after expiry 1764917674378
  - Membership? false
  - Join request stored? true
  - Expired invite retained? true
  - PASS

## testInviteFirstBackdatedJoinWithinExpiry
- What: Backdated join inside the expiry window still adds the member even if block time is later.
- How: Mint invite with TTL, join using a backdated timestamp inside TTL, assert membership.
- Why: Documents the transaction-timestamp window behavior for invite consumption.
- Output:
  - [testInviteFirstBackdatedJoinWithinExpiry] START
  - Join timestamp 1764917675587 relative to expiry 1764917676087
  - Membership? true
  - PASS

## testJoinFirstInviteLaterAutoAddsIgnoringTtl
- What: Join-first pending request is auto-approved by a later invite regardless of TTL/age.
- How: Create request, mint invite with short TTL, assert membership and request/invite consumed.
- Why: Validates documented TTL-agnostic behavior for join-first path.
- Output:
  - [testJoinFirstInviteLaterAutoAddsIgnoringTtl] START
  - Stored join request? true
  - Membership after invite? true
  - PASS

## testJoinFirstInviteLaterWithBackdatedJoinStillAdds
- What: Backdated join request is approved when a later invite arrives.
- How: Create backdated request, mint short-TTL invite later, assert membership and request consumed.
- Why: Confirms forward/backdating of join requests doesn’t block auto-approval.
- Output:
  - [testJoinFirstInviteLaterWithBackdatedJoinStillAdds] START
  - Stored join request? true
  - Membership after invite? true
  - PASS

## testJoinFirstInviteLaterTtlZero
- What: Non-expiring invite (TTL=0) still approves a stored join request.
- How: Create request, mint TTL=0 invite, assert membership and cleanup.
- Why: Ensures TTL=0 sentinel applies in join-first auto-approval.
- Output:
  - [testJoinFirstInviteLaterTtlZero] START
  - Stored join request? true
  - Membership after TTL=0 invite? true
  - PASS

## testApiFiltersExpiredInvites
- What: API endpoints omit expired invites and return non-expiring ones using chain-tip time.
- How: Mint an expired invite and a TTL=0 invite, call both invite endpoints, assert expired invite hidden and TTL=0 visible.
- Why: Confirms chain-tip-based filtering behavior exposed via API.
- Output:
  - [testApiFiltersExpiredInvites] START
  - Minting expired invite at 1764917670601 for bob
  - Minting TTL=0 invite for chloe
  - Group invites returned: 1
  - Invites for Chloe: 1
  - Invites for Bob (expired should be filtered): 0
  - PASS

## testPrePostTriggerActivation
- What: Expired invites auto-add pre-trigger but become requests post-trigger.
- How: Use reflection to raise the trigger (pre) to allow expired invite membership, then restore trigger and assert expired invite becomes a request post-trigger.
- Why: Validates trigger-gated activation of invite expiry enforcement.
- Output:
  - [testPrePostTriggerActivation] START
  - Pre-trigger join timestamp 1764917676864 relative to expiry 1764917675864
  - Pre-trigger membership? true
  - Post-trigger join timestamp 1764917676945 relative to expiry 1764917675945
  - Post-trigger membership? false
  - Post-trigger request stored and invite retained
  - PASS

## testInviteFilteringByChainTip
- What: API invite filtering hides expired invites and retains TTL=0/unexpired invites using chain-tip timestamp.
- How: Mint an expired invite, a non-expiring invite, and an unexpired invite; call invitee and group endpoints; assert expired invite filtered out and others returned.
- Why: Verifies chain-tip-based filtering behavior exposed via API.
- Output:
  - TEST START: testInviteFilteringByChainTip - expired invites filtered, TTL=0/unexpired retained.
  - TEST PASS: testInviteFilteringByChainTip - expected bobInvites size=1, actual=1; expected groupInvites size=2, actual=2

## testInviteFilteringSkippedWhenNoChainTip
- What: API invite filtering is skipped when no chain tip is available, so expired invites are returned.
- How: Mint an expired invite, swap repository factory to return null chain tip, call invitee endpoint, and assert expired invite is present.
- Why: Confirms documented no-tip fallback for API filtering.
- Output:
  - TEST START: testInviteFilteringSkippedWhenNoChainTip - filtering is skipped without a chain tip.
  - TEST PASS: testInviteFilteringSkippedWhenNoChainTip - expired invite present=true

# Test Logging Notes
- Added descriptive stdout logging (`log(testName, message)`) to new invite-expiry tests to show start, key state, and outcomes when run via CLI.
