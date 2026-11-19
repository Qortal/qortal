# Adding a Bitcoin-compatible chain

The implementation is an incremental wallet framework. Registration is not
authorization to open a market. Bitcoin and Litecoin use `BitcoinClone` while
retaining their public classes and selected-network singletons. New definitions
reuse `Bitcoiny` and `ElectrumX`; they do not copy a wallet implementation.

## Working example

The complete executable definition is
[`src/test/resources/crosschain/bitcoinclone/regtest.json`](../../src/test/resources/crosschain/bitcoinclone/regtest.json).
It uses Bitcoin's actual regtest genesis header/hash, test address/key prefixes,
and a loopback Electrum endpoint. `FIX`/`fixturecoin` identify an isolated test
fixture, not another public cryptocurrency. The tests construct, sign, verify
and serialize transactions with synthetic UTXOs; they never spend cryptocurrency.

Programmatic registration of this fixture requires no new backend class:

```java
List<BitcoinCloneDefinition> definitions = BitcoinCloneLoader.load(
    Path.of("src/test/resources/crosschain/bitcoinclone/regtest.json"));
BitcoinCloneRegistry registry = BitcoinCloneRegistry.builder()
    .register(definitions.get(0)).build();
BitcoinClone fixture = registry.backend("fixturecoin", BitcoinCloneDefinition.Network.REGTEST);
// fixture.getUnusedReceiveAddress(xpub), getWalletTransactions(xpub),
// getWalletBalance(xpub), buildSpend(xprv, recipient, atomicAmount, feePerByte),
// broadcastTransaction(transaction) all reuse Bitcoiny.
// Only use synthetic funds and a controlled regtest backend in this example.
```

For a controlled development node, put the definition in a local file and add
`"bitcoinCloneDefinitions": "bitcoin-clones.json"` to that node's settings.
Relative paths resolve against the settings user directory. The file is opt-in,
validated before node services start, and never hot reloaded. Invalid definitions
stop startup rather than silently leaving a partially active registry. Merely
registering chains or listing metadata opens no connections.

Do not replace the running production node or change its settings to try the
fixture. Use a separate development node/data directory. Definitions only take
effect on the next explicitly authorized node start.

## JSON contract, schema version 1

The root object contains exactly `schemaVersion: 1` and `chains: [...]`.
Each chain object has every field below; unknown fields and duplicate JSON keys
are rejected. There are at most 32 definitions, 64 servers per definition, eight
levels of nesting, and 1 MiB of input. Numeric parameters are JSON integers.

| Fields | Meaning and restrictions |
| --- | --- |
| `id`, `name`, `symbol` | Lowercase stable internal ID, display name, uppercase symbol; existing coin IDs/symbols are reserved |
| `network` | Exactly `MAINNET`, `TESTNET` or `REGTEST`; network selection is always explicit |
| `enabled` | Boolean operator opt-in; disabled definitions remain discoverable but have no available backend |
| `protocol` | `BITCOIN_WIRE_SHA256D`; external definitions cannot select an arbitrary adapter class |
| `derivation` | `QORTAL_BIP32_MASTER_P2PKH`; imported master key, relative receive/change chains; no BIP44 path switch |
| `bip44CoinType` | Nonnegative informational coin type; does not change key derivation |
| `genesisHash`, `genesisHeader` | Lowercase 64-character hash and 160-character 80-byte header; hash must match SHA256d, previous hash must be zero, basic target/PoW and nonempty merkle root checked |
| `p2pkhPrefix`, `p2shPrefix`, `wifPrefix` | Distinct one-byte Base58 version numbers, 0–255 |
| `xpubVersion`, `xprvVersion` | Distinct nonzero four-byte versions as eight lowercase hex characters |
| `bech32Hrp` | Lowercase HRP or explicit null; supports compatible recipient addresses, not a switch to witness wallet derivation |
| `networkMagic`, `p2pPort` | Concrete unsigned 32-bit magic and valid port; metadata for the network parameter adapter, not a P2P client |
| `coinbaseMaturity`, `maxMoneyAtomic` | Positive maturity and atomic-unit monetary bound |
| `feePerKb` | Fixed fee in atomic units per 1000 bytes, 1000–100000000; no live fee estimation claim |
| `minimumRelayFeePerKb`, `dustThreshold` | Profile currently requires bitcoinj standard values 1000 and 546 |
| `maxTransactionBytes`, `transactionVersion` | Profile requires 100000 and 1 for constructed ordinary spends |
| `transactionFormat` | `BITCOIN_LEGACY_WITH_WITNESS_PARSING`; inherited witness-aware previous transaction decoding, P2PKH signing |
| `signatureHash` | `BITCOIN_ALL`; no fork-id or custom sighash |
| `headerMode` | `ELECTRUM_TRUSTED_80`; genesis/header format checking does not independently verify a chain |
| `capabilities` | Array of recognized capabilities below, no duplicates |
| `electrumServers` | Array of `{ "host": "127.0.0.1", "transport": "TCP", "port": 50001 }` objects |

Use the complete fixture rather than copying a partial table into production.
A different public chain requires independently verified values and evidence
that its protocol matches the selected profile. Prefix matching alone is
insufficient. Same ID on different networks must preserve symbol, name,
protocol, coin type and derivation. Duplicate IDs per network and conflicting
symbols/genesis identities are rejected.

## Capability gates

`ADDRESS` is required. `BALANCE` and `HISTORY` describe inherited wallet reads.
`SIGN` requires balance/history and is permitted from configuration only for
isolated regtest. `BROADCAST` additionally requires `SIGN`. Configured mainnet
and testnet definitions are read-only. `HTLC`, `ACCT` and `TRADEBOT` cannot be
enabled by JSON. The example has regtest sign/broadcast capabilities so tests
exercise real transaction construction, rather than just metadata.

Configured regtest servers must be exactly `127.0.0.1` or `::1`. Mainnet/testnet
servers require SSL and literal public IP addresses. Names, URL schemes,
credentials, loopback/private/link-local destinations and peer expansion are
rejected. The fixed allowlist is checked before both probes and connections;
peer discovery is disabled. This deliberately restricts new configurations;
existing built-in server lists, DNS and connection behavior remain unchanged.
The existing SSL client does not perform normal CA hostname authentication.

## Read-only API

All new routes require the existing API authorization check and preserve API
exposure restrictions. For each selected chain, supply `?network=REGTEST`
(or `MAINNET`/`TESTNET`); omission is an error.

| Route | Result |
| --- | --- |
| `GET /crosschain/coins` | Registered definitions and effective enable state; currently BTC/LTC plus configured chains |
| `GET /crosschain/coins/{coin}` | One chain/network definition's public metadata and capabilities |
| `GET /crosschain/coins/{coin}/fees` | Current fixed fee policy in atomic units, serialized as decimal strings |
| `GET /crosschain/coins/{coin}/height` | Server-asserted height with explicit trust label |
| `POST /crosschain/coins/{coin}/walletaddress` | `{ "xpub58": "..." }` → first relative `0/0` P2PKH address; does not claim the address is unused |
| `GET /crosschain/coins/{coin}/address/{address}/balance` | Confirmed atomic balance with explicit server-trust label |

The address route rejects extended private keys and other fields from the
reused `ForeignWalletRequest` model. No generic send/broadcast, wallet import,
private-key, configuration-write or trading route is introduced. Existing
coin-specific endpoints remain available with their existing behavior.

## Specialized adapters and advanced support

Add a reviewed Java `Protocol` entry and explicit parameter/backend factory
when serialization, address encoding, sighash, header format or fees differ.
Reuse `NetworkParameters`, serializer strategies and `Bitcoiny` overrides rather
than coin-name branches. Register reviewed built-ins only from trusted startup
code; no JSON-to-class loading exists. Add deterministic vectors for every
changed signing or consensus assumption before permitting send operations.

`BitcoinCloneHtlc` gates the existing legacy P2SH HASH160/CLTV script builder.
Bitcoin/Litecoin keep their existing ACCT/TradeBot integration. For another
chain, separately verify redeem/refund scripts, version/sequence/locktime,
confirmations, fees, AT code hashes, bot state recovery and repository mapping.
Only then add a stable `SupportedBlockchain`/ACCT integration. The registry never
invents enum names, rewrites AT bytecode or opens a market from configuration.
