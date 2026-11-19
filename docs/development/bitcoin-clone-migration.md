# Bitcoin-clone migration and verification

## Delivered increment

| Milestone | Bitcoin / Litecoin | Configuration-only regtest fixture | Other configured public chains |
| --- | --- | --- | --- |
| Validated definition / registry | Yes | Yes | Yes, matching supported profile |
| Existing BIP32/P2PKH address derivation | Preserved | Tested | Available |
| Balance/UTXO/history backend | Existing `Bitcoiny` retained | Controlled UTXOs/balance tested | Server-trusted reads only |
| Ordinary transaction construction/signing | Preserved, tested on all three networks | Signed transactions and scripts verified | Disabled |
| Broadcast | Existing behavior preserved | Provider delegation tested, no public network submission | Disabled |
| HTLC script + redeem/refund | Existing scripts retained and verified | Disabled | Disabled |
| ACCT and automated TradeBot | Existing enum/contracts/states retained | Disabled | Disabled |

`Bitcoin` and `Litecoin` now extend the common `BitcoinClone` backend, which
extends `Bitcoiny`. Their network enums, singleton factories, exact bitcoinj /
altcoinj parameter objects, fees, minimum orders, Bitcoin default spend fee and
Litecoin P2SH conversion remain. `SupportedBlockchain` and persisted TradeBot
data are unchanged. No database migration, wallet re-derivation, QDN behavior,
Qortal consensus rule or Pirate/Monero integration is changed by this feature.

Dogecoin, DigiByte and Ravencoin retain their existing concrete adapters. The
shared wallet parsing/broadcast regression suite still exercises all five.
Their fee rules, AuxPoW/header differences and serializers need separate evidence
before moving their construction into this registry. Pirate's shielded/native
wallet remains separate.

## Deterministic verification

Run from the source checkout using Java 11. Detached worktrees need native Git
because this repository's JGit build plugin does not resolve their object store.

```sh
JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64 mvn \
  -Dmaven.gitcommitid.nativegit=true -DskipJUnitTests=false \
  -Dtest=BitcoinCloneDefinitionTests,BitcoinCloneWalletTests,BitcoinCloneNetworkTests,CrossChainCoinsResourceTests,BitcoinyWalletParsingTests,ElectrumXProtocolTests,ElectrumXResponseTests,ElectrumXRpcErrorTests,CrossChainHtlcCompatibilityTests \
  test
```

New tests cover strict types/required fields, unsupported adapters, bounded JSON,
identity conflicts, capability escalation, immutable snapshots, per-registry
backends, disabled chains, known BIP32 vectors, signing and fee compatibility,
raw transaction round trips, balance/UTXO resolution, refund/redeem script
verification, API auth/public-key-only input, real loopback handshakes,
wrong-network/malformed features, and fixed destination enforcement. Existing
offline protocol/response tests cover RPC negotiation and failover.

The seed `000102030405060708090a0b0c0d0e0f`, derived at `m/0/0`, yields:

| Network | First P2PKH address |
| --- | --- |
| Bitcoin mainnet | `12CL4K2eVqj7hQTix7dM7CVHCkpP17Pry3` |
| Litecoin mainnet | `LLRHKXLUaVyAxD9t8FcePDZ3QyBf7E4gu5` |
| Bitcoin/Litecoin testnet and regtest | `mgiHMN7dJsANUWwLfgbiw7hc4kR5xMjPhw` |

These constants were independently calculated using BIP32 HMAC-SHA512,
secp256k1 compressed public keys, HASH160 and Base58Check, then checked through
the actual migrated classes. All keys/UTXOs in these tests are synthetic.

The older `BitcoinTests`, `LitecoinTests`, `DogecoinTests`, `DigibyteTests`,
`RavencoinTests` and `HtlcTests` contain real external testnet queries and
spends; do not treat them as deterministic unit tests or run them against funded
wallets. Use the controlled regressions above. Existing repository-backed ACCT
tests can be selected separately for persisted state/AT lifecycle regression.

### Verification record — 2026-11-19

Source `22d847e9ff5912fb4361c2ac7e1dfa92189eeae0` was integrated on
`qdn-performance` after `b109da5f6a3263e081d0ec20d1d22ca185cadb06`, preserving
the concurrent exact-amount and ARRR JNI publication changes. Java 11 Maven
`package` passed across 19 selected suites: 202 tests, 193 executed successfully,
nine expected skips (eight witness cases and one Bitcoin supply-limit fixture),
zero failures/errors. This included the
five ACCT v3 lifecycle suites, all five coin wallet fixtures, API/security tests,
Electrum protocol/failover tests, and the concurrent amount/history regressions.

All 18 new feature tests also passed with the actual shaded JAR first on the
classpath. Its Git metadata matched the source commit; 21 relevant packaged
classes matched the compiled classes byte for byte. The build used an isolated
worktree and did not replace existing release artifact aliases or deploy a node.

## Remaining work and limits

This increment does not claim arbitrary fork compatibility or a new production
currency. Independent header/PoW/inclusion validation, nonstandard sighashes,
asset/timestamped/AuxPoW formats, witness wallet derivation, dynamic fee
estimation, new production send endpoints and new ACCT markets remain separate
milestones. The inherited whole-wallet balance/history paths retain existing
server-trust and error-handling behavior; the new balance API deliberately uses
the direct address query that propagates provider errors. A later audit can
strengthen wallet-wide partial-error reporting without silently changing existing
coin API behavior here.

The registry's `supportedBlockchain` lookup bridges only reviewed built-ins to
their existing enum. This is not a new generic TradeBot state machine. The HTLC
gate and existing script reuse are the delivered advanced-capability foundation.
No new trade state names, database fields or markets are introduced.

## Review, rollout and recovery

Compile/package in a development checkout with the existing Maven build; run the
offline tests above before considering a release. No live service or test-JAR
alias is updated by this implementation. The initial source base is recorded in
the architecture audit; concurrent release fixes are preserved during branch
integration.

Readiness: existing BTC/LTC routes, selected networks, known addresses, fee
setters and in-progress trade recovery must still pass the selected regressions.
With no new setting, only reviewed BTC/LTC registrations appear. With an opt-in
file on a controlled development node, inspect authenticated `/crosschain/coins`,
check the explicit network/genesis, derive a public address and test a controlled
loopback balance query. Do not submit public trades or real-fund transfers.

Deployment is an owner-authorized release step using existing node lifecycle
procedures. For rollback, remove the opt-in definition setting and restore the
previous verified Core JAR through that procedure. Keep the node database,
wallet files and all existing trade recovery journals. There is no schema or
wallet migration to reverse. A source-only review requires no restart.
