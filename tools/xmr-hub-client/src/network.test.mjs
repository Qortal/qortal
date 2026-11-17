import assert from 'node:assert/strict';
import test from 'node:test';
import { assertDaemonNetwork, networkForNodeInfo, workerUrl } from './network.mjs';

test('Qortal testnet selects Monero stagenet, normal mode selects mainnet', () => {
  assert.equal(networkForNodeInfo({ isTestNet: true }), 'stagenet');
  assert.equal(networkForNodeInfo({ isTestNet: false }), 'mainnet');
});

test('missing or malformed mode never silently selects a production network', () => {
  for (const value of [null, {}, { isTestNet: 'false' }, { isTestNet: 0 }, { isTestNet: undefined }]) {
    assert.throws(() => networkForNodeInfo(value));
  }
});

test('a provider cannot change the network chosen from the host mode', () => {
  assertDaemonNetwork('stagenet', { nettype: 'stagenet' });
  assertDaemonNetwork('mainnet', { nettype: 'mainnet' });
  for (const expected of ['stagenet', 'mainnet']) {
    for (const info of [null, {}, { nettype: 'testnet' }, { nettype: expected === 'mainnet' ? 'stagenet' : 'mainnet' }]) {
      assert.throws(() => assertDaemonNetwork(expected, info));
    }
  }
  assert.throws(() => assertDaemonNetwork('testnet', { nettype: 'testnet' }));
});

test('worker assets resolve below a nested QDN resource root', () => {
  assert.equal(workerUrl('/render/APP/xmr-client/', 'https://node.example/render/APP/xmr-client/wallet/history'),
    'https://node.example/render/APP/xmr-client/monero.worker.js');
  assert.equal(workerUrl(undefined, 'https://node.example/render/APP/xmr-client/index.html'),
    'https://node.example/render/APP/xmr-client/monero.worker.js');
});

test('worker roots reject cross-origin URLs, file paths and ambiguous directories', () => {
  for (const root of ['https://cdn.example/', 'file:///tmp/', '/app', '/app/?secret=1', '/app/#fragment']) {
    assert.throws(() => workerUrl(root, 'https://node.example/render/APP/xmr-client/index.html'));
  }
});
