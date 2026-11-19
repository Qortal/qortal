# Generic Bitcoin-clone framework

## Audit and compatibility boundary

Implementation base: `qdn-performance` at
`347df1a791b8a5b2a635f85fd80c9c325789d3c9`. The owner's current instruction
to continue this branch supersedes the branch names in the original brief.

| Component | Existing responsibility | Decision |
| --- | --- | --- |
| `Bitcoiny` | bitcoinj wallets, imported BIP32 keys, P2PKH address discovery, UTXOs, balances/history, coin selection, signing, raw transaction decoding, broadcast | Reuse; do not implement another wallet |
| `Bitcoin`, `Litecoin` | Network enum, Electrum servers, fee state, minimum order, singleton, LTC P2SH conversion | Retain public classes/enums; share construction through `BitcoinClone` |
| `Dogecoin`, `Digibyte`, `Ravencoin` | Existing altcoinj parameters and fee/network policies | Retain pending individual migration evidence |
| `ElectrumX`, `ElectrumServer` | Protocol 1.4 negotiation, connection pools, failover, history/UTXO/header/transaction RPC, broadcast | Reuse client; add optional fixed-destination policy for configured chains |
| `BitcoinyBlockchainProvider` | Provider abstraction | Reuse; tests inject controlled providers |
| bitcoinj 0.16.3 / altcoinj `d7cf6ac` | Wallet, serialization, signing, network classes | Keep dependency versions and existing parameter objects |
| `BitcoinyHTLC` | HASH160 secret, CHECKLOCKTIMEVERIFY, legacy P2SH redeem/refund, version 2 transactions, SIGHASH_ALL | Reuse behind explicit compatibility gate |
| ACCT v1/v3 classes | AT bytecode and foreign-chain-specific fee/confirmation semantics | Retain hashes, contracts and dispatch |
| `SupportedBlockchain` | Stable names, ACCT names/code hashes and factories | Keep enum unchanged; registry maps migrated identities back to it |
| `TradeBot` / concrete bots | State machines, trade keys, confirmation handling, refund/redeem recovery | Keep state names, repository data and recovery unchanged |
| `CrossChainRepository`, HSQLDB mapping | Serialized in-progress trades | No schema or data migration |
| `CrossChain*Resource`, `LocalWalletSupport` | Legacy wallet/trade APIs, public signing context, authenticated broadcast | Preserve endpoints; add authenticated read-only discovery/address operations |
| `BitcoinyTBD`, `NetTBD`, `DeterminedNetworkParams` | Earlier dynamic prototype: mutable symbol maps, no validation, incomplete genesis, assumed scrypt/difficulty rules | Leave legacy entry points intact; do not use as validated registration |
| `Settings`, `Controller` | JSON node configuration and startup | Opt-in local definition file, validated once before services start |
| `PirateChain` / native wallet | Shielded pools, preparation journals, native transaction semantics | Separate implementation, outside this UTXO adapter |

### Wallet invariants

Existing wallets import a BIP32 master extended key into bitcoinj and use its
P2PKH receive/change chains. A BIP44 coin type is descriptive metadata, **not**
permission to change this derivation to `m/44'/coin'/0'`. Existing networks,
prefixes, extended key versions, Litecoin's legacy/internal P2SH prefix and
its explicit current-prefix conversion remain authoritative. Bitcoin's default
three-argument spend retains its 20 atomic-units/byte override; explicit null
fee uses its existing per-kB policy. Other concrete coin fee setters remain.

### Trust and protocol limits

Electrum data is server asserted. Qortal compares the advertised genesis hash,
checks raw transaction hashes/encoding and previous output metadata in relevant
wallet paths; this is not independent proof of chain inclusion, accumulated
work, confirmations or balances. Header retrieval is not a complete SPV verifier.
The current TLS client uses `TrustlessSSLSocketFactory` (no normal CA identity
assurance). TCP and TLS preserve these existing semantics.

The new configurable profile deliberately describes standard Bitcoin wire
transactions and SHA256d 80-byte headers with server-trusted chain state. It
does not claim support for AuxPoW, fork-id sighashes, timestamped transactions,
assets, multi-byte Base58 versions, shielded transactions or arbitrary header
consensus. Litecoin keeps the existing altcoinj adapter. Unknown profiles fail
closed. A JSON claim of HTLC support cannot authorize ACCT or a TradeBot.

## Design

An immutable definition identifies **one network**. An immutable registry indexes
`id + network`; mainnet/testnet/regtest can coexist without runtime replacement.
Definitions are validated before backend construction. Registry backend caches
are private, lazy and per registry. Built-in factories return existing selected
network singletons, respecting the existing wallet enable setting.

Configuration cannot replace any existing coin's ID or symbol. An operator
explicitly selects a local JSON file at startup; no network imports, class names,
reflection, scripts or dynamic plugins are interpreted. Recognized Java profiles
provide parameters. A custom Bitcoin wire profile supplies concrete address/key,
genesis, fee and transaction parameters. Production custom chains start read-only;
sign/broadcast is restricted to isolated loopback regtest definitions. Advanced
capabilities require a reviewed Java integration and deterministic tests.

Configured Electrum connections use a fixed literal-IP allowlist, with DNS and
peer discovery disabled. This prevents server-supplied peers and DNS rebinding
from expanding destinations. Existing built-in clients retain their behavior.

See [adding-bitcoin-clone.md](adding-bitcoin-clone.md) for the concrete schema
and [bitcoin-clone-migration.md](bitcoin-clone-migration.md) for rollout and tests.
