# QORT / XMR trades: protocol and Core integration work

Status: research and implementation plan, 2026-11-18. The wallet release supports
Monero stagenet scanning/payments. It does not implement swaps. No new trading AT,
approved hash, consensus opcode or trade-bot entry is enabled by this release.

## Findings from the current Core

A new Monero trade protocol should have its own versioned AT and code hash. The
existing Bitcoin-family/Pirate ACCT implementations assume a compatible foreign
redemption mechanism. Registering XMR beside them does not supply that mechanism.

* `transaction/DeployAtTransaction.java` checks creation size (4096 bytes), code
  size (1024 bytes), serialized state size (1024 bytes), and after
  `BlockChain.getAtValidateHeight()` compares Base58(SHA256(code)) with
  `APPROVED_CODE_HASHES`. An unapproved new AT is rejected as
  `INVALID_CREATION_BYTES`.
* `crosschain/SupportedBlockchain.java` separately maps coin, ACCT name and exact
  code hash to implementations. `controller/tradebot/TradeBot.java` resolves those
  implementations and their persistent state handlers. A whitelist entry alone
  neither indexes nor executes an XMR trading workflow.
* The pinned CIYAM AT engine (`com.github.iceburst:AT:1b731d1`) supplies hashes,
  message handling, balances and payouts. Inspection of `FunctionCode` and
  `at/QortalFunctionCode.java` found no Edwards point/scalar verification or
  adaptor-signature primitive. Qortal transaction signature verification outside
  the AT VM is not an AT-accessible Monero verification primitive.

Adding an accepted AT hash changes consensus acceptance: older nodes can reject
blocks containing it. New VM behavior has the same coordination requirement.
An owner testing JAR cannot unilaterally activate a new protocol on Qortal mainnet.
Use an isolated Qortal test chain for the AT prototype, then arrange reviewed,
height-gated network activation and compatible node upgrades.

## Why the existing HTLC cannot simply be copied

Monero lacks Bitcoin's script-based HTLC redemption path. The published
[Bitcoin/Monero protocol paper](https://arxiv.org/abs/2101.12332) uses adaptor
signatures; the [h4sh3d reference protocol](https://github.com/h4sh3d/xmr-btc-atomic-swap)
describes verifiably encrypted signatures and cross-curve proofs. Those are
primary references for the security argument, not drop-in Qortal implementations.
Qortal uses a different transaction/AT model and signature scheme from Bitcoin.

An AT that accepts a hash preimage does not prove that the preimage is the spend
share needed for the actual Monero deposit. A daemon's assertion that payment was
sent is not an atomicity proof. An ordinary `SEND_COIN` approval also does not
establish swap intent, refunds, counterparties or completion conditions.

## Protocol choices to investigate before choosing bytecode

| Candidate | Required work | Main unresolved gate |
| --- | --- | --- |
| Adapt an established adaptor-signature construction to a Qortal escrow/payout transaction | Exact Qortal signing algebra, adaptor extraction, canonical scalars/points, pre-signed recovery transactions, transcript proofs | A full proof that either party can recover after every abort; Bitcoin ECDSA code cannot be reused unchanged |
| A purpose-built AT and joint Monero spend-key construction | Bind spend shares to the funded output and claim/refund paths; possibly bounded Edwards verification in the VM | Independent refund liveness: a public secret/point check alone does not create a unilateral Monero refund |

The [Athanor ETH/XMR implementation](https://github.com/athanorlabs/atomic-swap)
is a contract-based design reference, but its repository was archived on
2026-09-01. Do not adopt it as an actively maintained security dependency. Any
contract design must be re-derived for Qortal's AT timing, messages, reorgs and
cost/size limits. Prefer a narrowly scoped, reviewed primitive over embedding
large cryptographic proofs into every AT state.

These alternatives are research directions, not a claim that either is already
safe. Freeze one protocol transcript and recovery proof before implementing an
AT whitelist entry. In particular, reject a naive design in which a maker can
redeem QORT without a confirmed XMR deposit, or a failed counterparty must reveal
a secret voluntarily before the other party can recover locked XMR.

## Work packages and acceptance gates

1. **Protocol specification.** Assign QORT seller/XMR seller roles; define both
   network IDs, amounts (8-decimal QORT / 12-decimal XMR), per-swap keys, joint
   address/view-key ownership, authenticated transcript, bounded messages,
   confirmations, fee changes and timeout units. Enumerate every abort boundary,
   including one-sided funding, readiness withheld, claim/refund races, deep
   reorg, malicious subgroup points, duplicate messages and a restarted client.
   Produce a written atomicity/refund-liveness argument and independent review.
2. **Cryptographic prototype outside consensus.** Use disposable keys and local
   chains. Pin reviewed implementation dependencies and independent vectors;
   validate extraction and refund transactions under adversarial transcripts.
   Account wallet keys must never double as shared swap secrets. Ordinary Qortal
   account backups recover the wallet; any additional per-swap secret/transcript
   and pending recovery state require an explicit encrypted, recoverable design.
3. **AT/VM prototype.** Specify `MoneroACCTv1` only after step 1. Compile bytecode
   deterministically, assert the exact hash and size limits in tests. If needed,
   define a bounded, deterministic VM verification function with canonical input
   validation, subgroup checks, metered cost, invalid-input behavior and an
   activation height; test every boundary and rollback/orphan path. Do not run
   daemon RPC, wallet scanning or spend-key handling inside consensus execution.
4. **Core plumbing.** Register the frozen ACCT/code hash in `SupportedBlockchain`,
   add public trade data/API and presence handling, persistence/migrations and
   recovery semantics. Keep private signing in Hub. Review the existing Core
   trade-bot ownership assumptions before adding a Hub-owned XMR state machine.
   Add the exact approved deployment hash only with coordinated activation.
5. **Hub/Q-App execution.** Separate swap approvals from ordinary payments; bind
   every approval to account, both networks, counterparty, exact amounts, AT hash,
   escrow address, fee limits and timeout schedule. Persist encrypted transcripts
   before releasing signatures; resume after crash, support monitoring/refunds,
   and never let a Q-App receive either account private key or swap spend share.
6. **Testing and rollout.** Both trade directions, every abort/refund, unknown
   broadcast outcome, concurrent requests, malformed proofs, stale chain tips,
   reorgs and backup recovery must pass on isolated chains. Then use worthless
   test networks with independent review and a coordinated upgrade before
   considering real-value trading. Wallet-only Qortal-mainnet/stagenet testing
   does not authorize placing a real QORT swap offer.

Deliverables for the next implementation milestone are the protocol transcript,
security/recovery argument, independent vectors and local two-party prototype.
Only after those gates should we allocate the final AT code hash and activate it.
