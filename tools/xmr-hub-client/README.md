# Qortal Hub XMR engine probe

Development-only feasibility test for `monero-ts` **0.11.22**. All fixture keys/passwords are public and must never receive funds. There is no key-import UI, public daemon connection, scan, payment or integration with an actual Hub account.

The product architecture puts all cryptography in **trusted Qortal Hub**. Q-Apps request permissioned operations and receive public results. This fixture intentionally runs the engine in isolated browser surfaces to measure compatibility; it is not a proposed Q-App wallet vault. See [the decision and Hub integration plan](../../docs/monero-hub-client.md).

For manual account/backup testing with the matching Hub branch, use the read-only page in `../xmr-account-test` and follow [the combined ARRR/XMR test guide](../../docs/arrr-xmr-testing.md). `npm run test:account-page` checks that page with a mock bridge; it does not replace the real Hub acceptance steps.

Pinned SDK asset SHA-256 values measured on 2026-11-17 (the npm tarball integrity is in the lockfile):

| Asset | Bytes | SHA-256 |
| --- | ---: | --- |
| `dist/monero.worker.js` | 3,669,221 | `a118abf560ecbba372fc41a0c41cef468feb4c5e3f732598d1316c4c06a78b84` |
| `dist/monero.js` | 2,642,942 | `258a065899deec0172fec24de9d3e65a9fa2a10499185b72112f52dcf5bc8433` |

## Run

Use Node 20.19+ (or a newer Vite-supported LTS). The lockfile pins the SDK, browser polyfills and test tools. From this directory:

```sh
npm ci --ignore-scripts
npm test
npm run build
npx playwright install chromium
npm run test:browser
```

An existing compatible Chromium can be used instead of installing one:

```sh
XMR_CHROMIUM_PATH=/absolute/path/to/chrome npm run test:browser
```

The browser runner starts ephemeral loopback HTTP servers and closes them after completion. All external network requests are blocked during the test. It writes public pass/fail evidence to ignored `results/browser-smoke.json`. `public/`, `dist/` and `node_modules/` are generated and ignored. No Core process is needed. The HTML buttons require the runner-injected fixture URLs; building the page alone does not configure a daemon.

## Coverage

Five Node tests check explicit mainnet/stagenet mode selection, unknown-mode rejection, daemon network mismatch and nested worker-asset resolution. Four browser cases cover top-level and cross-origin sandboxed iframe surfaces, each with Qortal test mode -> Monero stagenet and normal mode -> Monero mainnet.

Each browser case creates a synthetic wallet and signs/verifies a fixed message, persists encrypted wallet/cache bytes through IndexedDB, restarts the worker, rejects an incorrect password and reopens with the correct password. It also recreates the same address/view key from the public spend-key fixture without cache blobs, creates a view-only wallet, checks fixture daemon network and reads native wallet daemon height. Only public booleans, sizes, network and timings are returned. These are engine recovery tests, **not tests of the Qortal backup format**.

The fixture serves a small allowlist of read-only daemon methods with CORS from a separate origin. It rejects wallet-secret field names and unexpected requests. Two additional browser checks reject an unknown Qortal mode and a daemon on the wrong network. The runner asserts no external requests or page errors. A restrictive CSP permits local worker scripts and WASM compilation; neither `SharedArrayBuffer` nor cross-origin isolation is available during these tests. This does not establish compatibility with Hub's actual CSP, Electron webviews or mobile runtime.

## Implementation findings

- `LibraryUtils.setWorkerDistPath()` must point beneath the application's trusted asset root. The pinned worker embeds its WASM; never fetch wallet code from a CDN at runtime.
- Full-wallet reopening needs a functioning browser filesystem shim even when encrypted bytes are supplied directly. The probe explicitly maps `fs` to `memfs`.
- The full native wallet discards configured daemon URI path prefixes. The fixture tests native wallet transport against an origin-root daemon, not just the JavaScript daemon wrapper. A prefixed Core relay needs an explicit transport adapter.
- `wallet.getData()` returns encrypted keys/cache blobs; their successful reopen does not establish a production vault policy. Account locking, cache keys and wipe semantics remain Hub work.
- Vite reports externalized `child_process`/`crypto` imports from SDK paths and a large bundle. The exercised worker paths pass, but unused Node process APIs are not available in browsers. Test the actual Hub bundle and scan/send paths before enabling this engine in a product.
- The copied SDK worker includes upstream notices. Review all underlying native/WASM and shim licenses before packaging a product release. The SDK package declares MIT; that does not replace the embedded components' notices.

To stop or remove the experiment, close the runner/browser and delete only generated files or revert this focused commit. No services, Core configuration or production wallets are changed.
