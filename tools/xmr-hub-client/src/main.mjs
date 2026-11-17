import * as monero from 'monero-ts';
import { networkForNodeInfo, workerUrl } from './network.mjs';

const PUBLIC_TEST_SPEND_KEY = '01' + '00'.repeat(31);
const PUBLIC_TEST_PASSWORD = 'unfunded-compatibility-probe-only';
const MESSAGE = 'Qortal Hub XMR engine compatibility probe';
let busy = false;

function check(condition, message) {
  if (!condition) throw new Error(message);
}

async function cacheRoundTrip(network, keysData, cacheData) {
  const name = `qortal-xmr-client-spike-${network}`;
  const open = () => new Promise((resolve, reject) => {
    const request = indexedDB.open(name, 1);
    request.onupgradeneeded = () => request.result.createObjectStore('probe');
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
  let db;
  try {
    db = await open();
    await new Promise((resolve, reject) => {
      const transaction = db.transaction('probe', 'readwrite');
      transaction.objectStore('probe').put({ network, keysData: Uint8Array.from(keysData), cacheData: Uint8Array.from(cacheData) }, 'wallet');
      transaction.oncomplete = resolve;
      transaction.onabort = () => reject(transaction.error);
      transaction.onerror = () => reject(transaction.error);
    });
    db.close();
    db = await open();
    const stored = await new Promise((resolve, reject) => {
      const request = db.transaction('probe').objectStore('probe').get('wallet');
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
    });
    check(stored.network === network, 'Stored wallet network mismatch');
    check(stored.keysData.length === keysData.length && stored.cacheData.length === cacheData.length, 'Stored wallet data length mismatch');
    return stored;
  } finally {
    if (db) db.close();
    await new Promise((resolve, reject) => {
      const request = indexedDB.deleteDatabase(name);
      request.onsuccess = resolve;
      request.onerror = () => reject(request.error);
      request.onblocked = () => reject(new Error('Synthetic cache cleanup blocked'));
    });
  }
}

/** Synthetic engine proof only. Real keys belong in Hub, never in a Q-App. */
async function runSmokeForNodeInfo(nodeInfo, fixtureDaemon) {
  if (busy) throw new Error('Probe already running');
  busy = true;
  let wallet;
  let restored;
  let viewWallet;
  try {
    const network = networkForNodeInfo(nodeInfo);
    fixtureDaemon ||= document.querySelector(`meta[name="xmr-probe-${network}-fixture"]`)?.content;
    const fixture = new URL(fixtureDaemon);
    check(fixture.hostname === '127.0.0.1' && fixture.pathname === '/' && !fixture.search && !fixture.hash && !fixture.username && !fixture.password,
      'This probe requires its loopback daemon fixture at the origin root');
    const networkType = network === 'stagenet' ? monero.MoneroNetworkType.STAGENET : monero.MoneroNetworkType.MAINNET;
    const worker = workerUrl(window._qdnBase, location.href);
    monero.LibraryUtils.setWorkerDistPath(worker);
    await monero.LibraryUtils.setLogLevel(0);

    const started = performance.now();
    wallet = await monero.createWalletFull({
      networkType, password: PUBLIC_TEST_PASSWORD, privateSpendKey: PUBLIC_TEST_SPEND_KEY,
      restoreHeight: 0, proxyToWorker: true,
    });
    check(await wallet.getNetworkType() === networkType, 'Wallet network mismatch');
    check(await wallet.getBalance() === 0n, 'Unexpected synthetic wallet balance');
    const address = await wallet.getPrimaryAddress();
    check(address.length === 95, 'Unexpected address format');
    const signature = await wallet.signMessage(MESSAGE);
    const verification = await wallet.verifyMessage(MESSAGE, address, signature);
    check(verification.getIsGood(), 'Local message signature did not verify');

    const daemon = await monero.connectToDaemonRpc({ server: fixtureDaemon, proxyToWorker: true });
    check((await daemon.getInfo()).getNetworkType() === networkType, 'Fixture daemon network mismatch');
    await wallet.setDaemonConnection(fixtureDaemon, false);
    check(await wallet.getDaemonHeight() === 12345, 'Native wallet daemon transport failed');
    await wallet.setDaemonConnection();

    const exported = await wallet.getData();
    const { keysData, cacheData } = await cacheRoundTrip(network, exported[0], exported[1]);
    const privateViewKey = await wallet.getPrivateViewKey();
    await wallet.close();
    wallet = undefined;
    await monero.LibraryUtils.terminateWorker();
    let wrongPasswordRejected = false;
    try {
      const invalid = await monero.openWalletFull({ networkType, password: 'incorrect', keysData, cacheData, proxyToWorker: true });
      await invalid.close();
    } catch {
      wrongPasswordRejected = true;
    }
    check(wrongPasswordRejected, 'Encrypted wallet accepted the wrong password');

    restored = await monero.openWalletFull({ networkType, password: PUBLIC_TEST_PASSWORD, keysData, cacheData, proxyToWorker: true });
    check(await restored.getPrimaryAddress() === address, 'Encrypted wallet restore changed its address');
    check(await restored.getNetworkType() === networkType, 'Restored wallet network mismatch');
    await restored.close();
    restored = undefined;
    await monero.LibraryUtils.terminateWorker();

    // Recreate without the saved wallet blobs. The production Hub derivation
    // must supply the same scalar from the Qortal backup; this public fixture
    // proves only that the selected engine supports deterministic recovery.
    restored = await monero.createWalletFull({
      networkType, password: PUBLIC_TEST_PASSWORD, privateSpendKey: PUBLIC_TEST_SPEND_KEY,
      restoreHeight: 0, proxyToWorker: true,
    });
    check(await restored.getPrimaryAddress() === address, 'Key recovery changed its address');
    check(await restored.getPrivateViewKey() === privateViewKey, 'Key recovery changed its view key');

    viewWallet = await monero.createWalletFull({
      networkType, password: PUBLIC_TEST_PASSWORD, primaryAddress: address,
      privateViewKey, restoreHeight: 0, proxyToWorker: true,
    });
    check(await viewWallet.isViewOnly(), 'View wallet unexpectedly has spending capability');

    return {
      library: monero.MoneroUtils.getVersion(), network,
      walletCreated: true, localMessageSignatureVerified: true,
      encryptedWalletReopened: true, wrongPasswordRejected, viewOnlyWalletCreated: true,
      indexedDbRoundTrip: true, restoredAfterWorkerRestart: true,
      deterministicKeyRecovery: true,
      nativeDaemonHeightRead: true, fixtureDaemonNetworkVerified: true,
      crossOriginIsolated: globalThis.crossOriginIsolated,
      sharedArrayBufferAvailable: typeof SharedArrayBuffer !== 'undefined',
      wasmMemoryBytes: await monero.LibraryUtils.getWasmMemoryUsed(),
      elapsedMs: Math.round(performance.now() - started),
    };
  } finally {
    try {
      for (const instance of [viewWallet, restored, wallet]) {
        if (instance) await instance.close();
      }
    } finally {
      try {
        await monero.LibraryUtils.terminateWorker();
      } finally {
        busy = false;
      }
    }
  }
}

window.runXmrClientSmoke = runSmokeForNodeInfo;
for (const [id, isTestNet] of [['stagenet', true], ['mainnet', false]]) {
  document.getElementById(id).addEventListener('click', async () => {
    const output = document.getElementById('result');
    try {
      output.textContent = JSON.stringify(await runSmokeForNodeInfo({ isTestNet }), null, 2);
    } catch (error) {
      output.textContent = `Probe failed: ${error.message}`;
    }
  });
}
