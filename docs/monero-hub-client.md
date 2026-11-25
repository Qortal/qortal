# XMR client: Hub ownership and unified recovery

Decision date: 2026-11-17. Status: owner-approved custody/network requirements; selected engine feasibility candidate. Hub's account/backup foundation is now implemented on its separate `xmr-support` branch (`a3731b9c`, `cd894052`); this Core branch does not contain the Hub sources. The matching desktop testing build now implements stagenet scanning and approved local sends. See [the combined build and manual XMR test guide](arrr-xmr-testing.md).

## Responsibilities

The owner requires all wallet cryptography to run locally inside Qortal Hub. XMR key creation belongs in the same account lifecycle as the existing coin wallets. One Qortal backup must recover them all.

```text
Q-Wallets / another Q-App
  | approved wallet requests; public results
  v
Trusted Qortal Hub account service and Monero worker
  | public chain queries / locally signed transaction bytes
  v
Monero daemon (direct transport or bounded Qortal Core connector)
```

Hub owns root-seed access, XMR spend/view keys, scanning, subaddresses, transaction preparation, confirmation, signing, encrypted cache storage and account-lock cleanup. Load the engine from Hub's own pinned assets. A Q-App must not supply executable wallet code, daemon URLs, worker URLs or a private-key destination. Never copy an unlocked account into a QDN iframe/webview.

The Q-App displays receive addresses, sync state, balances, history and send requests. Hub authenticates the requesting app, obtains wallet permissions and displays its own transaction confirmation before signing. Root spend/view keys, mnemonic, seed, wallet blobs and raw signing internals do not cross the app bridge. The daemon receives no private view key: scanning remains local. A light-wallet server that requires that key does not meet this boundary.

Core stays a source of Qortal mode and approved daemon connection information, with a restricted transport adapter only if needed. It does not create wallets or perform wallet cryptography. The Monero daemon still needs a full or pruned chain database. Public daemon hosts/domains are not selected and no server rollout is part of this increment.

## Existing Hub backup contract

Reviewed canonical source: `/home/crowetic/git-repos/gitea/crowetic/Qortal-Hub`.

| Hub source | Existing behavior and XMR consequence |
| --- | --- |
| `src/utils/generateWallet/phrase-wallet.ts` | `PhraseWallet` derives the Qortal address seed by wallet version and address nonce, then builds the current coin wallets. XMR must join the creation and restoration lifecycle without changing any existing result. |
| `src/utils/generateWallet/storeWallet.ts` | `generateSaveWalletData` encrypts `_byteSeed` and returns `address0`, `encryptedSeed`, `salt`, `iv`, `version`, `mac`, and `kdfThreads`. The backup represents the shared root, rather than a list of independent coin keys. |
| `src/background/background.ts` | `decryptWallet` decrypts that seed, constructs `PhraseWallet` and explicitly extracts the existing coin keys. XMR's trusted handoff and cleanup must be added here as well as to creation. |
| `src/qortal/get.ts` | `getUserWallet` is the permissioned Q-App entry point. XMR responses must be an explicit public projection, never a serialized account object. |
| `src/components/Auth/{BackupWalletModal,DownloadWallet}.tsx`, `src/App.tsx` | Backup/export and phrase-restore paths also rebuild `PhraseWallet`. Test all of them against the same XMR derivation contract. |

Consequently, creating a fresh unrelated random XMR key would break the backup promise. A fixed deterministic derivation from the recoverable account seed can make older Qortal backups recover the new XMR wallet too. This is an integration requirement, not a claim that old backups currently contain an implemented XMR wallet.

The first Hub increment must:

1. Define and freeze `xmrDerivationVersion = 1`: input bytes, domain label, account/address index, Qortal wallet version, network separation, hash/KDF, scalar reduction, byte order and zero-scalar handling. Use maintained cryptographic primitives and the reference wallet for independent vectors. Do not reinterpret an Ed25519 signing key, Bitcoin HD key or mnemonic as a Monero spend key. Do not bump the Qortal wallet version to add one coin.
2. Derive/create the same XMR wallet on new-account creation, phrase login and encrypted-backup recovery. Route secret material only to trusted Hub code. Review the Hub key-storage guard and logout/account-switch cleanup before adding any secret field.
3. Preserve root-seed backup compatibility. Any additive derivation metadata needs an explicit legacy default and round-trip tests; the algorithm must remain reproducible from old backups. Future derivation versions must preserve recovery of funded older wallets.
4. Keep cache/restore-height data separate from spending authority. If the cache or optional birthday is missing, scan from a safe height rather than silently starting at today's tip. Define recovery of allocated subaddress indexes beyond normal lookahead.
5. Prove recovery on a fresh profile using only the ordinary encrypted Qortal backup. Compare XMR address and private-key results internally, while reporting only pass/fail and public test fixtures. Verify BTC/LTC/DOGE/DGB/RVN/ARRR/QORT outputs are unchanged for existing wallet versions and nonces. Only then expose a receive address.

The historical browser experiment used a public scalar fixture to demonstrate
deterministic engine reconstruction. Hub's account derivation and encrypted-backup
KDF have their own integration tests; the retained public page does not replace them.

## Integrated engine and removed browser experiment

Hub pins `monero-ts` 0.11.22 in its own `electron/package.json` and starts its
trusted `electron/src/monero-wallet.worker` through `MoneroWalletService`.
The worker scans and signs locally, encrypts its cache/pending-payment journal,
and returns explicitly selected public results. Root spend/view keys do not go
to Core, Q-Apps or the daemon. Network traffic also includes daemon queries for
sync, blockchain data, fees and transaction status, with observable IP/timing.

The obsolete `tools/xmr-hub-client` standalone browser/WASM experiment has been
removed. Its SDK, filesystem shims, Vite bundle and historical `isTestNet`-only
network selector are not product requirements. Keep Hub's own worker and SDK.
The public backup-check page and its relocated mock-bridge runner remain in
[`tools/xmr-account-test`](../tools/xmr-account-test/README.md). They are optional
and contain no wallet engine. Use Hub's account/backup/permission tests and
[real-worker local-chain verification](https://github.com/crowetic/Qortal-Hub/blob/xmr-support/docs/monero-stagenet-wallet.md#repeatable-native-verification)
for current integration checks. Independent review and platform acceptance remain
release work.

## Network and transport contract

| Qortal `/admin/info` / `GET_NODE_INFO` result | Monero network |
| --- | --- |
| Explicit valid `moneroNetwork` hint with boolean `isTestNet` | The explicit `mainnet` or `stagenet` value |
| No hint, `isTestNet: false` | `mainnet` |
| No hint, `isTestNet: true` | `stagenet` |
| Invalid hint or missing/non-boolean mode | Unavailable; fail closed |

This supports Qortal mainnet with Monero stagenet. The `moneroNetwork` value is case-insensitive; `/admin/info` reports lowercase. The current functional desktop engine allows stagenet only. Monero's separate `testnet` is not used for Qortal test mode. Hub checks daemon `get_info.nettype` against the selected network before scanning or preparing a payment and distinguishes the local wallet's scan state from daemon synchronization. Cache, permissions and wallet operations must be scoped to account, derivation version and network. Cancel active operations on account/node-mode changes; never reuse a previous mode's balance or prepared transaction.

Two transport details were found in the pinned engine:

- The default worker URL points at the origin root. Resolve it beneath the trusted Hub asset directory. The fixture tests a nested `/render/APP/.../` path to catch accidental root resolution; real Hub must not take its worker path from a Q-App.
- The native full wallet drops the path prefix of a configured daemon URI and requests origin-root endpoints such as `/json_rpc`. The JavaScript daemon wrapper preserves a prefix, so testing that wrapper alone gives incomplete evidence. The fixture verifies the native wallet against a separate CORS-enabled daemon origin. A Core URL such as `/crosschain/xmr/...` needs a tested worker transport adapter; configuring that URL alone is insufficient.

Prefer Hub-owned networking to an open server proxy. Direct browser transports require suitable HTTPS/CORS; Electron/mobile adapters must preserve endpoint policy and cancellation. A Core relay, if required, needs a fixed daemon pool per network and a small allowlist of required JSON and binary daemon routes, bounded payloads/deadlines and exact integer handling. Do not accept arbitrary user-supplied upstream URLs. Full scan binary endpoints, broadcast error semantics, node switching and mainnet-scale performance are not established by the height fixture.

## Next deliverables

| Order | Component | Completion evidence |
| --- | --- | --- |
| 1 | Hub derivation and unified-backup integration | Implemented on Hub `xmr-support`; independent/Monero-engine vectors and real encrypted-backup tests pass. Run the fresh-profile manual acceptance in the combined test guide. |
| 2 | Trusted Hub worker and vault lifecycle | Pinned local assets; account lock/switch/restart tests; safe cache encryption/storage; no secrets in app IPC or logs |
| 3 | Read-only Q-App wallet interface | Permission checks; public address/balance/history models with atomic amounts as strings; explicit unavailable/syncing states |
| 4 | Daemon pool and scan transport | Approved mainnet/stagenet targets; network/version checks; real scan and restart/reorg evidence; actual Hub platform tests |
| 5 | Hub-confirmed local sends | Independent amount/destination/fee review; durable intent; ambiguous-broadcast recovery; authorized unfunded then funded acceptance |

Use the task's `xmr-support` isolation when starting Hub implementation after inspecting its current history and local work. This Core increment changes no Hub runtime files and requires no restart or rollout. Removing the historical probe does not alter the integrated wallet. Direct QORT/XMR trades remain separately gated by the protocol work in the roadmap.
