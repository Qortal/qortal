# Combined ARRR / XMR test build

## What is included

The Core `xmr-support` branch merges owner `pirate-changes` at `7dfdc5cbb0c4484c4cb40a970fcfe982ffa689bb`. There is no `arrr-changes` branch in this checkout or the owner's remote. The refund/redemption recovery and diagnostic work was already an ancestor; the merge adds the newer QDN-native packaging correction and shared Bitcoin-family witness-aware transaction reader.

The resulting `target/qortal-arrr-xmr-testing.jar` includes those ARRR/Core changes and the XMR address/network/amount foundations. **It does not implement an XMR wallet service in Core.** Wallet key creation/recovery and the public account request require the separate owner [Qortal Hub `xmr-support` branch](https://github.com/crowetic/Qortal-Hub/tree/xmr-support), containing `a3731b9c` and `cd894052`.

| Can test now | Not implemented yet |
| --- | --- |
| Existing ARRR wallet/trade recovery, using the selected QDN JNI publication | New XMR chain scanning, balances and history |
| Hub XMR account keys and ordinary Qortal backup recovery | XMR payments, transaction broadcast and QORT/XMR swaps |
| Mainnet versus stagenet selection and permissioned public XMR addresses | An XMR wallet tile or complete wallet flow in Q-Wallets |
| Offline XMR primitives, local WASM feasibility and wallet compatibility regressions | A public Monero daemon fleet or Core XMR RPC connector |

Do not send XMR to test the unfinished scan/send features. Use a new unfunded Qortal account for the account/recovery checks below. The shipped synthetic fixtures are public and must never receive funds.

## Core artifact and installation

The JAR's companion `.build.json` and `.sha256` files identify its exact source commit, tests and checksum. Check the receipt rather than identifying it only by the `6.1.9` base version. ARRR native libraries are deliberately absent from the JAR: Core retrieves the selected immutable publication from QDN and loads its platform library from disk. The smaller JAR is expected. Keep the existing ARRR QDN signature and wallet cache; do not repack native libraries into it.

For an owner-operated test node:

1. Stop that Core through its existing lifecycle. Preserve the current `qortal.jar`, settings, complete configured repository and wallet directories, and trade recovery backups together. Do not copy a running database as a backup.
2. Verify `sha256sum -c qortal-arrr-xmr-testing.jar.sha256` from the downloaded artifact directory. Replace only the test node's `qortal.jar` with this verified JAR.
3. Restart through the normal lifecycle. Confirm `/admin/info` and the startup log report the build version recorded in `.build.json`. Confirm the expected QDN native signature if exercising ARRR; retain the diagnostic/recovery checkpoints.
4. If another node is running, use a separate working directory, repository/data/wallet directories, and API/P2P/QDN ports. Select that local Core in Hub. A testnet Core must use separate data from a mainnet Core; do not toggle a production database between networks.

No node is installed or restarted by this source/build task. See [ARRR testing and recovery](pirate-chain-wallet-library-testing.md) for the native publication, restore-birthday and pending-refund rules. For a code rollback, stop the test node and restore its retained JAR while preserving current repository/wallet/recovery records. Do not erase an ambiguous funding/refund intent or independently replace its data with an older snapshot.

## Run the matching Hub

An existing main/release Hub does not have the new XMR account implementation. Use the owner's Hub source branch, whose latest account-foundation tip is `cd894052`. For a browser-based account test, native desktop packaging is not needed:

```sh
cd /path/to/Qortal-Hub
git switch xmr-support
npm ci
npm run dev -- --host 127.0.0.1
```

Use Hub's supported Node toolchain (CI uses Node 22; Node 20.19.2 also passed these tests). Open the local URL printed by Vite, select the local test Core and create a new unfunded account. If using a previously unlocked test session, log out and unlock again so Hub reconstructs its XMR record. For desktop builds, follow Hub's normal renderer build, Capacitor sync and platform packaging instructions; this Core JAR does not update Hub.

## Manual account and backup test

A small read-only Q-App is included so the test does not require choosing a frame in developer tools. From this Core source checkout:

```sh
python3 -m http.server 8173 --bind 127.0.0.1 --directory tools/xmr-account-test
```

In the updated Hub, with a **local Core** selected, open **Apps → Development Mode** and add a developer app with domain `127.0.0.1` and port `8173`. Core's development proxy injects the normal Q-App bridge. Do not open the page directly in a separate tab: that has no Hub request bridge. No QDN publication or fee is needed for this developer-mode test.

1. Click **Request public XMR account**, approve Hub's wallet permission, and leave the remember/always-allow box unchecked if you want to test denial afterward.
2. With a normal Core (`/admin/info` has `isTestNet: false`), expect `network: "mainnet"`, `derivationVersion: 1`, and a 95-character address. The response contains exactly five public fields: address, network, derivation version, public spend key and public view key. No private key or seed is requested or displayed.
3. Record the public address and the Qortal account address. Download the ordinary Qortal backup through Hub's account backup flow. There is no separate XMR backup file.
4. In a fresh browser profile, open the same updated Hub and import/unlock **only that Qortal backup**. Select the same Core/network, reopen the developer app, request the account and paste the earlier public address into the comparison field. Expect **PASS: the recovered public address matches**, with the original Qortal address too.
5. Create/select a different unfunded Qortal account and request again. Its XMR address must differ. Switch back to the restored account and confirm its original address returns.
6. Select a local, separately configured Qortal testnet Core whose `/admin/info` has `isTestNet: true`. Request again: expect `network: "stagenet"` and a different address from mainnet. Restore that account on the same test mode and confirm the stagenet address is stable. Monero's separate `testnet` is not used, and an app-supplied network cannot override Hub's Core mode.
7. Decline a wallet permission in a fresh app session/profile: the page must show a failed/declined request and no new wallet data. Missing/invalid Core mode, account/Core switching during approval, or an old key record must fail rather than return a wallet for another account/network.

The page makes only `GET_USER_WALLET` with `coin: "XMR"`. It never imports a key, requests a balance, signs or broadcasts. Balance/history/wallet-info/send requests deliberately return the existing unsupported-operation error in this build. The page's automated test uses a mock Hub bridge; the manual steps above are the actual Hub acceptance check, not something established by the page test alone.

To stop the helper, press Ctrl-C in its terminal and close the developer app. Its public display/comparison has no separate key store or recovery state.

## Automated verification

Core, with JDK 11 and cached Maven dependencies:

```sh
JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64 mvn -o -B \
  -Dmaven.gitcommitid.nativegit=true -DskipJUnitTests=false \
  -Dtest=MoneroAddressTests,MoneroAmountsTests,BitcoinyWalletParsingTests,LocalWalletPreparationTests,PirateChainRefundTests,PirateChainACCTv3TradeBotTests,PirateChainWalletControllerTests,PirateRecoveryRpcTests \
  test
```

Omit `-o` if dependencies must be downloaded. Use an isolated worktree for packaging so IDE-generated classes and old native staging cannot contaminate the artifact. No native/live/funded opt-in property is required for these checks.

Hub, on its `xmr-support` branch:

```sh
npx vitest run src/lib/monero src/utils/generateWallet/monero-backup.test.ts \
  src/qortal/monero-wallet.test.ts src/qortal/__tests__/moneroWallet.test.ts \
  src/utils/chromeStorage.foreign-wallet.test.ts
```

Those tests exercise the real backup KDF/bcrypt/AES/HMAC while adapting only the test Worker boundary. [Hub's account documentation](https://github.com/crowetic/Qortal-Hub/blob/xmr-support/docs/monero-accounts.md) includes the independent Python and full Monero WASM interoperability checks.

The optional browser engine/page probes run from `tools/xmr-hub-client`:

```sh
npm ci --ignore-scripts
npm test
npm run build
npx playwright install chromium
npm run test:browser
npm run test:account-page
```

Set `XMR_CHROMIUM_PATH` to an existing compatible Chromium if using one instead of Playwright's installed browser. These probes use public synthetic wallets, loopback fixtures and/or a mock bridge. They do not scan a real chain or test a funded send.
