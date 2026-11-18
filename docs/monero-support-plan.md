# Monero (XMR) support plan

Date: 2026-11-17. Scope: Qortal Core foundations, device-local wallets owned by Qortal Hub with Q-Wallets as their interface, and eventual direct QORT/XMR trading in Q-Trade. This is an implementation roadmap, not an announcement of usable XMR wallets or trades.

## Current result and decisions

The first increment adds offline Java primitives in `org.qortal.crosschain.monero`:

- `MoneroNetwork`: mainnet, testnet and stagenet address prefixes, with no default network.
- `MoneroAddress`: standard, integrated and subaddress decoding; block-Base58 overflow checks; Keccak-256 checksum; explicit network and payload-length checks; canonical, non-identity prime-order public keys using the existing Bouncy Castle dependency; immutable decoded keys and payment IDs.
- `MoneroAmounts`: exact 12-decimal conversion and unsigned 64-bit wire amounts using `BigInteger`, without floating point, rounding or narrowing to signed `long`.
- Offline tests using published addresses, frozen independently generated fixtures for all nine network/type combinations, malformed addresses and amount boundaries.

These classes have no runtime callers yet. No RPC settings, wallet processes, endpoints, database migrations, supported-chain registration, ATs or native bundles are added. Address acceptance is deliberately stricter than a checksum-only decoder: unusual encodings with noncanonical, identity or non-prime-order keys are rejected. Differential tests against the selected wallet implementation are required before this becomes a sending gate. Validation does not prove ownership, receipt or spendability.

Wallet work can proceed while the direct-swap protocol is researched. XMR must enter `SupportedBlockchain` only when there is a real, tested ACCT implementation; that registry currently couples a chain to its AT code hashes and latest ACCT. Wallet availability needs its own capability model.

The owner has since fixed the client boundary: **all cryptography runs locally in Qortal Hub**, including XMR account-key creation alongside the other coins. The same Qortal account backup must recover XMR. Q-Apps request approved operations and display public results. Qortal testnet maps to **Monero stagenet**; normal/default mode maps to **Monero mainnet**. See [the Hub client decision and integration work](monero-hub-client.md). A synthetic `monero-ts` worker feasibility probe is now available in [`tools/xmr-hub-client`](../tools/xmr-hub-client/README.md); it is not a product wallet or a Q-App key store.

## Repository findings

| Existing component | Consequence for XMR |
| --- | --- |
| `crosschain/SupportedBlockchain.java`, `controller/tradebot/TradeBot.java` | Registration enables ACCT discovery and trade-bot dispatch. A placeholder XMR entry would advertise functionality that does not exist. |
| `crosschain/ForeignBlockchain.java` | Small existing interface includes wallet-key validation and a `long` minimum amount. It does not describe scanning, private view keys, signed key images or wallet capabilities. |
| `crosschain/Bitcoiny.java`, `ElectrumX.java`, Bitcoin-family HTLC builders | Their transparent UTXO/script model cannot implement a Monero wallet or lock transaction. |
| `crosschain/PirateChain.java` and its ACCT/bot | Native wallet lifecycle and recovery offer implementation lessons. ARRR's P2SH trading protocol and native binary are not Monero support. |
| `api/AmountTypeAdapter.java`, `TradeBotCreateRequest.java` | Foreign amounts currently use an 8-decimal adapter. Reusing it for XMR loses the intended unit semantics; it must not be globally changed for existing chains. |
| `data/crosschain/TradeBotData.java` | Foreign amounts are signed `long`; its storage and backup format need explicit compatibility handling. |
| `at/QortalATAPI.java`, `QortalFunctionCode.java`, pinned CIYAM AT library | Existing contracts support hash/secret/message workflows. The inspected extension functions and library function list do not provide a ready-made adaptor-signature or discrete-log proof verifier. A protocol feasibility experiment must establish what can run within today's AT machine. |
| `crypto/Crypto.java` | Qortal transaction signing uses Ed25519. Sharing a curve family with Monero does not establish an atomic-swap construction, compatible scalar derivation or safe nonce handling. |
| `docs/local-wallet-signing.md` | Preserve the direction of device-local wallet signing. Existing Core trade-specific secrets require a separate explicit design for XMR; never send a root spend key to hosted Core. |

The initial inspected source base was `44b602f54f416cbb17e8ba272dcf8fcb7c00ef14`, on `pirate-changes`, tracking the owner's `origin/pirate-changes`. Existing ARRR diagnostics belong to another task; that work independently advanced the checkout to `c3b867dd4` during this increment. At the owner's request, XMR development now lives on `xmr-support`, branched from that exact current tip in the existing `crowetic/qortal` fork. The foundation is tested in an isolated detached worktree of the same base plus the new XMR source files.

## Architecture and custody

Use three separate responsibilities:

1. **Chain access** reads public daemon status, headers, transactions and fee information. It verifies network, sync state and supported protocol versions and treats a remote daemon as an untrusted data source. A daemon does not provide an address-indexed wallet balance.
2. **Hub-local wallet service** derives the XMR key during account creation/restoration, scans outputs, manages account/subaddress indexes and restore height, tracks signed key images and available funds, prepares transactions, and signs locally. Private view keys disclose wallet information and remain private even when they cannot spend. Private spend/view keys, seeds and full wallet files stay inside trusted Hub code on the user's device; Q-Apps receive no root keys.
3. **Swap engine** coordinates only a reviewed protocol, authenticates counterparty messages, persists recovery material before side effects and monitors both chains. It requires separate protocol and recovery capabilities, not just a wallet capable of sending XMR.

The selected feasibility candidate is `monero-ts` 0.11.22, running its local `wallet2` WASM engine in a trusted Hub worker. Bundle the pinned assets with Hub. Account derivation and Q-App permissions still need implementation and recovery tests before this becomes a usable wallet. Keep an explicitly configured, authenticated loopback `monero-wallet-rpc` as an interoperability reference for development tests, not the product's default wallet service. Do not expose wallet RPC through hosted Core. The official [wallet-process reference](https://docs.getmonero.org/interacting/monero-wallet-rpc-reference/) describes the daemon/wallet separation.

Before product packaging, record an architecture decision comparing:

| Candidate | Work to resolve |
| --- | --- |
| Local reference wallet RPC | Authentication, process ownership, per-wallet serialization, restart/recovery, wallet-file protection, binary verification, packaged desktop lifecycle and IPC boundaries. Lowest-cost interoperability prototype. |
| Native `wallet2` integration | Maintained bindings, ABI stability, reproducible builds, licenses, crash isolation and every supported OS/architecture. Do not reuse or modify the ARRR bundle for this. |
| Browser/WASM wallet library | `monero-ts` 0.11.22 selected for the Hub feasibility increment; synthetic browser/worker tests pass. Actual Hub integration, scanning, payment interoperability, independent review and supported platforms remain release gates. |

Pin the exact backend release, source commit, binary hashes, schema and network in acceptance evidence. The official [release page](https://github.com/monero-project/monero/releases/tag/v0.18.5.4) identifies v0.18.5.4 as the current release at this review. Development `master` is not a promise of activated network behavior; recheck compatibility at implementation and release time.

The owner requires deterministic XMR recovery from the existing Qortal account backup. Hub currently encrypts the root seed and stores the Qortal wallet version, then rebuilds coin wallets through `PhraseWallet`. Add XMR to that account lifecycle with a fixed, versioned, domain-separated derivation; do not generate an unrelated random wallet or change existing coin derivation. Freeze the exact algorithm and recovery vectors before receiving funds. Scan-cache files are replaceable state, not the only copy of spending authority. Present scan height, daemon height, last refresh, locked balance, unlocked balance and incomplete spent-status information separately.

## Amounts, APIs and persistence

The [pinned protocol configuration](https://github.com/monero-project/monero/blob/v0.18.5.4/src/cryptonote_config.h) defines 12 decimal places and the network prefixes. One piconero is `0.000000000001 XMR`. Individual wire amounts occupy `0..18446744073709551615`; this is a serialization bound, not a claimed supply cap or minimum trade size.

Proposed new wallet/swap DTOs carry `currency: "XMR"`, explicit network, `decimals: 12`, and atomic amounts as decimal **strings**. Convert to the reference daemon's required JSON integer tokens at the transport boundary with an exact parser/writer. Do not route integers through JavaScript `Number`, Java `double`, Qortal's `Amounts` helpers or the existing 8-decimal adapter. RPC versions can differ in string-to-integer acceptance, so do not assume a string-valued wire field is accepted.

Keep QORT's amount semantics and all existing chain contracts unchanged. For initial trading, either explicitly cap XMR values at `Long.MAX_VALUE` piconero with checked conversion into existing storage, or introduce versioned wider storage. Record that decision before wiring `TradeBotData`. Never use `longValue()` to truncate a wider amount. Atomic balances may be zero; payments and trades must separately require a positive amount, sufficient unlocked funds, a fee reserve and policy limits.

Design typed capabilities such as `receive`, `scan`, `prepare`, `sign`, `broadcast` and `directSwap`, each with availability/reason and network. Distinguish unavailable/not configured, syncing, stale data, authentication failure, unsupported version and invalid address. An unavailable wallet is not a zero balance. Version new API models rather than broadening the legacy trade request implicitly. Keep account-specific wallet access authenticated and scoped; hosted Core receives no wallet scan secrets.

Persist a versioned wallet/swap journal with network, protocol ID, role, public identifiers, expected amounts, fee policy, scan checkpoints and block hashes, recovery deadlines, intent ID, transaction/checkpoint IDs, and encrypted sensitive recovery material. Specify ownership, file permissions, encryption/unlock lifecycle and backup schema. Serialize operations per wallet and swap. Write intent before signing or broadcasting; distinguish prepared, signed, submitted, observed, confirmed, unlocked and swept. A timeout after submission is ambiguous: inspect the known transaction and wallet before considering any retry. Never construct a second payment merely because an RPC response was lost.

## Direct QORT/XMR swap feasibility gate

The [BTC/Monero protocol paper](https://arxiv.org/abs/2101.12332) uses adaptor signatures on the Bitcoin side. It is research input, not a Qortal implementation. The original [COMIT repository](https://github.com/comit-network/xmr-btc-swap) is archived as of this review; evaluate maintenance, licensing and protocol suitability before adopting any successor or component.

Do not copy the existing AT secret message and treat it as a Monero refund mechanism. In a shared-key design, revealing a spend-key share can give both parties spending power unless the full protocol prevents a race. A two-party multisig wallet alone also supplies no unilateral timeout refund. A BTC intermediary route would be a separate two-swap product with extra fees and independent failure states, not direct QORT/XMR atomicity.

The feasibility deliverable must contain:

1. Both roles, funding order, all signed messages and transactions, exact cryptographic statements, domain-separated transcript hashes, nonce rules, scalar encodings, key validation and secret-extraction steps. Establish whether same-curve proofs are sufficient or other proofs are needed.
2. The exact Qortal transaction/AT witness that releases the Monero claim ability, who can observe it and when, and why a sender cannot obtain XMR after receiving a QORT refund. Bind both networks, amounts, destinations, counterparty keys, protocol version and swap identity.
3. A unilateral recovery argument for each point at which either party has committed funds: cancellation, timeout, refund, sweep, counterparty disappearance, delayed messages, fee spikes and simultaneous spends. Do not assume Monero implements a programmable refund branch.
4. Reorg and mempool analysis on both chains. A secret visible in an unconfirmed or later-orphaned Qortal message remains revealed. Explain how premature disclosure and redemption/refund races cannot cause a loss. Include execution fees and AT exhaustion.
5. An inventory of necessary AT operations and resource costs. Demonstrate the construction on the current VM, or document the exact consensus change, activation and backward compatibility required. Do not add an opcode or alter deployed ACCT code hashes in a wallet feature patch.
6. A runnable adversarial model and test transcripts, then independent cryptographic review. If no safe construction passes, record that result and continue wallet support without exposing direct trade creation.

Only after this gate passes should a versioned Monero ACCT and trade bot be designed. Give the new AT its own code hash and parsing rules; preserve every existing ACCT. Define an authenticated, replay-resistant counterparty transport with bounded payloads, resumable sessions and encrypted private data. Secrets do not belong in public Qortal messages except for the reviewed final witness. Neither QORT nor XMR funding starts until the other party's setup and all required refund/recovery material have been verified and saved.

## Milestones and work queue

These are ordered deliverables, not calendar promises. Wallet and protocol research can progress independently. The protocol gate controls the direct-trading schedule.

| ID | Deliverable and component | Depends on | Acceptance gate | State |
| --- | --- | --- | --- | --- |
| XMR-001 | Evidence, architecture and roadmap (Core docs) | Repository review | Explicit custody, precision, direct-swap and release gates | Complete for this increment |
| XMR-002 | Amount/network/address primitives (Core) | XMR-001 | Offline independent vectors, overflow and network rejection | Complete: 18 tests pass |
| XMR-003 | Backend, key derivation and backup ADR (Core/Hub) | XMR-001 | A second device restores the same development wallet; documented Hub/Q-App/daemon boundaries | Hub `xmr-support` implements keys/backup/public addresses; independent and Monero-engine vectors pass; clean-profile manual acceptance described in [test guide](arrr-xmr-testing.md) |
| XMR-004 | Bounded read-only daemon/wallet adapters (Core/Hub) | XMR-002, 003 | Typed errors, exact uint64 parsing, network/version/sync checks; local wallet ownership; fixture and local-process tests | Fixture transport proven; product adapter planned |
| XMR-005 | Receive, scan, balances and history (Hub/Q-Wallets) | XMR-004 | Account/subaddress allocation, restore-height recovery, key-image spent status, reorg reconciliation; no false zero balance | Planned |
| XMR-006 | Prepare, local signing and broadcast (Hub/local wallet) | XMR-005 | Independent destination/amount/change/fee/network confirmation; restart-safe intent; ambiguous submission recovery; verified wallet receipt | Planned |
| XMR-007 | Direct-swap specification and feasibility experiment (Core/AT research) | XMR-001 | All six protocol requirements above, executable adversarial model and independent review | Research required |
| XMR-008 | Prototype both swap roles with durable recovery (local engine) | XMR-006, 007 | Development networks only; every funded state can complete or recover; no secret/nonces reused | Gated |
| XMR-009 | Versioned AT, bot, repository/backup migrations and capabilities (Core) | XMR-008 | Deterministic AT tests, migration rollback policy, recovery across restart/reorg; legacy ACCT regressions pass | Gated |
| XMR-010 | Offers, quotes, approval and recovery UI (Hub/Q-Trade) | XMR-009 | Exact pricing/precision, fee reserves, cancellation states, offline counterparty and user export/recovery walkthroughs | Gated |
| XMR-011 | Review, reproducible packaging and soak tests (all components) | Wallet or trade candidate | Supported platform matrix; pinned artifacts; fault-injection results; independent security findings resolved | Gated per release |
| XMR-012 | Opt-in release and staged rollout (owner) | XMR-011 | Owner-authorized controlled acceptance, readiness checks and recovery runbook | Not authorized by this development task |

The account/backup/public-address foundation is now on Hub's separate `xmr-support` branch. The next coding increment is its trusted local Monero worker lifecycle and read-only daemon scanning transport. Run the existing backup through the [manual fresh-profile test](arrr-xmr-testing.md) while developing that path. Core should provide network/node metadata and, only if transport requires it, a bounded daemon connector. The native WASM wallet drops URI path prefixes, so a generic Core URL prefix is not a working relay without an explicit adapter. Keep wallet support separate from ACCT registration.

## Verification matrix

**This increment:** Java 11 compilation and offline JUnit address/amount tests. The fixtures cover published standard/integrated addresses; all network/type combinations; embedded payment IDs; full/final block overflow; every single-character mutation of the nine positive fixtures; wrong length, alphabet, whitespace and network; checksum-valid invalid/low-order keys; defensive copies; one piconero; exact uint64 maximum; precision, syntax, signed-long overflow and uint64 overflow. Frozen synthetic fixtures use independent Python encoding and PyCryptodome 3.23.0 Keccak, not the production decoder. They are public examples, never funded wallets.

Verified on OpenJDK 11: **18 tests, zero failures/errors/skips**, with all production and test sources compiling in an isolated checkout of the base plus these new files. Reproduce with:

```sh
JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64 mvn -o -B \
  -Dmaven.gitcommitid.nativegit=true -DskipJUnitTests=false \
  -Dtest=MoneroAddressTests,MoneroAmountsTests test
```

Use the installed JDK 11 path on the target machine; omit `-o` when dependencies are not cached. Native Git metadata collection avoids the existing JGit plugin's failure to resolve this linked worktree's commit. No Maven configuration was changed. This is source/test verification, not a release JAR, reference-wallet differential test, security audit, live wallet or funded swap acceptance.

**Adapter stage:** local mock HTTP servers and a pinned reference process cover JSON-RPC IDs/envelopes/errors; HTTP/auth failures; bounded response size, connect/read/total deadlines and cancellation; mismatched networks; stale, busy and unsynced nodes; unsupported protocol versions; oversized and malformed integers; failed wallet refresh; reconnect/restart; concurrent wallet switching and cancellation. Never automatically retry a state-changing RPC. Bound scanning and distinguish incomplete results from complete history. Test DNS/redirect/endpoint policy and proxy behavior rather than forwarding user-supplied URLs from hosted Core.

**Wallet stage:** compare scans and fees against the reference wallet. Test standard, integrated and subaddress recipients, key-image import/export, incoming/outgoing/failed/pending transfers, locked outputs, insufficient unlocked balance, repeated subaddress allocation, restore from backup, cold signing with independently described recipients/change/fees, lost broadcast replies and multiple-device conflicts. The official [wallet RPC specification](https://docs.getmonero.org/rpc-library/wallet-rpc/) provides relevant methods; implement only the selected subset and verify version-specific schemas.

**Swap stage:** cover both roles, each transition and timeout, crashes before/after every persisted intent and side effect, corrupt/incomplete recovery records, duplicate/reordered/replayed messages, invalid proofs and points, wrong network/amount/destination, concurrent attempts, AT fee exhaustion, wallet rescan, dropped/late transactions, both-chain reorgs, public witness leakage, claim/refund races and counterparty disappearance. Demonstrate actual receipt and unilateral recovery, not just RPC acknowledgement. Run Bitcoin-family and ARRR trade/HTLC/repository regressions before registration.

**Platforms:** development loopback tests first, then reproducible wallet builds on supported Linux x86_64/arm64, Windows and macOS targets. Track unsupported platforms through capabilities. Separate test configuration and development wallets from production. Funded acceptance and any production change require their own owner-authorized rollout.

## Release, operations and recovery

This increment requires **no service restart, native installation, wallet import or deployment**. There is no running XMR path to enable. Existing ARRR/native artifacts must remain untouched. Source rollback consists of removing only the new Monero files and this document; do not discard unrelated changes.

For future releases, publish a compatibility matrix for Core, Hub, Q-Wallets, Q-Trade and the exact Monero backend. Add separate capability flags for wallet and trading. Defaults stay disabled until their respective gates pass. Before an authorized rollout, verify network/sync/version, local signer availability, unlocked funds and reserves, recovery storage, backups, supported AT version and protocol compatibility. Build from reviewed source, retain prior binaries/checksums and reproduce offline tests from the release artifacts.

Install through the affected repository's established release mechanism and actual target configuration; do not copy a development wallet to a server. Exercise receive/scan/restore first, then a controlled send only when authorized, then successful swaps and unilateral recovery in both roles. Capture confirmations and destination wallet receipt, and restart during pending states. Report public operation IDs and reason codes without seeds, view keys, wallet RPC credentials, recovery keys or raw private payloads.

On failure, stop new payment/trade creation while preserving monitoring, wallet files and journals. A code downgrade may not understand a newer active swap or wallet format: retain a compatible recovery executable and test that path before release. Never erase a post-submission intent, replace current wallet data with an old backup, or delete keys for funded swaps as part of rollback. Schema-changing releases need a specific tested downgrade/recovery procedure before deployment.

## Protocol references

- [Monero block-Base58](https://docs.getmonero.org/cryptography/base58/), [standard addresses](https://docs.getmonero.org/public-address/standard-address/), [integrated addresses](https://docs.getmonero.org/public-address/integrated-address/) and [subaddresses](https://docs.getmonero.org/public-address/subaddress/).
- [Pinned address/amount constants](https://github.com/monero-project/monero/blob/v0.18.5.4/src/cryptonote_config.h) and [Base58 boundary test vectors](https://github.com/monero-project/monero/blob/v0.18.5.4/tests/unit_tests/base58.cpp).
- [Daemon RPC](https://docs.getmonero.org/rpc-library/monerod-rpc/), [wallet RPC](https://docs.getmonero.org/rpc-library/wallet-rpc/) and [wallet service lifecycle](https://docs.getmonero.org/interacting/monero-wallet-rpc-reference/).
- [Atomic Swaps between Bitcoin and Monero](https://arxiv.org/abs/2101.12332), [Monero's original swap announcement](https://www.getmonero.org/2021/08/20/atomic-swaps.html) and [original COMIT implementation](https://github.com/comit-network/xmr-btc-swap). These establish precedent for BTC/XMR, not proof of QORT/XMR feasibility.
