import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { mkdir, readFile, stat, writeFile } from 'node:fs/promises';
import { extname, resolve, sep } from 'node:path';
import { chromium } from 'playwright';

const dist = resolve('dist');
const appBase = '/render/APP/xmr-client-spike/';
const requests = [];
const blockedRequests = [];
const browserErrors = [];
const cspBase = "default-src 'self'; script-src 'self' 'wasm-unsafe-eval'; worker-src 'self'; object-src 'none'; base-uri 'none'";
const types = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.txt': 'text/plain' };

const app = createServer(async (request, response) => {
  const url = new URL(request.url, 'http://127.0.0.1');
  requests.push({ method: request.method, path: url.pathname });
  response.setHeader('Content-Security-Policy', csp);
  response.setHeader('Cache-Control', 'no-store');
  if (request.method !== 'GET' || !url.pathname.startsWith(appBase)) {
    response.writeHead(404).end();
    return;
  }
  const path = resolve(dist, decodeURIComponent(url.pathname.slice(appBase.length)) || 'index.html');
  if (!path.startsWith(dist + sep)) {
    response.writeHead(404).end();
    return;
  }
  try {
    if (!(await stat(path)).isFile()) throw new Error('Not a file');
    response.setHeader('Content-Type', types[extname(path)] || 'application/octet-stream');
    let data = await readFile(path);
    if (extname(path) === '.html') {
      data = Buffer.from(data.toString().replace('</head>',
        `<meta name="xmr-probe-mainnet-fixture" content="${mainnetOrigin}/"><meta name="xmr-probe-stagenet-fixture" content="${stagenetOrigin}/"></head>`));
    }
    response.end(data);
  } catch {
    response.writeHead(404).end();
  }
});

function daemonFixture(network) {
  return createServer(async (request, response) => {
    const url = new URL(request.url, 'http://127.0.0.1');
    const entry = { server: network, method: request.method, path: url.pathname };
    requests.push(entry);
    response.setHeader('Access-Control-Allow-Origin', appOrigin);
    response.setHeader('Vary', 'Origin');
    if (request.method === 'OPTIONS') {
      response.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
      response.setHeader('Access-Control-Allow-Headers', 'Content-Type');
      response.writeHead(204).end();
      return;
    }
    if (!['/json_rpc', '/get_height'].includes(url.pathname) || !['GET', 'POST'].includes(request.method)) {
      response.writeHead(403).end();
      return;
    }
    let bytes = 0;
    const chunks = [];
    for await (const chunk of request) {
      bytes += chunk.length;
      if (bytes > 4096) { response.writeHead(413).end(); return; }
      chunks.push(chunk);
    }
    const body = Buffer.concat(chunks).toString();
    assert.doesNotMatch(body, /view_key|spend_key|mnemonic|seed|password/i, 'Wallet secret field reached the daemon fixture');
    const payload = body ? JSON.parse(body) : {};
    entry.rpcMethod = payload.method || 'get_height';
    let result;
    if (url.pathname === '/get_height') result = { status: 'OK', height: 12345, untrusted: false };
    else if (payload.method === 'get_info') result = { status: 'OK', height: 12345, target_height: 12345, nettype: network, synchronized: true, restricted: true, offline: false };
    else if (payload.method === 'get_version') result = { status: 'OK', version: 196620, release: true };
    else if (payload.method === 'get_block_count') result = { status: 'OK', count: 12345 };
    else { response.writeHead(403).end(); return; }
    response.setHeader('Content-Type', 'application/json');
    response.end(JSON.stringify(url.pathname === '/json_rpc' ? { jsonrpc: '2.0', id: payload.id, result } : result));
  });
}

const mainnet = daemonFixture('mainnet');
const stagenet = daemonFixture('stagenet');

const parent = createServer((_request, response) => {
  response.setHeader('Content-Type', 'text/html');
  response.setHeader('Content-Security-Policy', `default-src 'none'; frame-src ${appOrigin}`);
  response.end(`<!doctype html><title>Cross-origin Q-App frame fixture</title><iframe sandbox="allow-scripts allow-same-origin" src="${appOrigin}${appBase}index.html"></iframe>`);
});

async function listen(server) {
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', resolve);
  });
  return `http://127.0.0.1:${server.address().port}`;
}

const appOrigin = await listen(app);
const parentOrigin = await listen(parent);
const mainnetOrigin = await listen(mainnet);
const stagenetOrigin = await listen(stagenet);
const csp = `${cspBase}; connect-src 'self' ${mainnetOrigin} ${stagenetOrigin}`;
let browser;
try {
  browser = await chromium.launch({ headless: true, executablePath: process.env.XMR_CHROMIUM_PATH || undefined });
  const context = await browser.newContext();
  await context.route('**/*', async route => {
    const url = new URL(route.request().url());
    if ((url.origin === appOrigin && url.pathname.startsWith(appBase)) || [parentOrigin, mainnetOrigin, stagenetOrigin].includes(url.origin)) {
      await route.continue();
    } else {
      blockedRequests.push(url.origin + url.pathname);
      await route.abort();
    }
  });
  const page = await context.newPage();
  page.on('pageerror', error => browserErrors.push(error.message));
  const cases = [];
  for (const embedded of [false, true]) {
    await page.goto(embedded ? parentOrigin : appOrigin + appBase + 'index.html');
    const frame = embedded
      ? await (await page.waitForSelector('iframe')).contentFrame()
      : page.mainFrame();
    assert.deepEqual(browserErrors, [], 'Page failed before the probe initialized');
    await frame.waitForFunction(() => typeof window.runXmrClientSmoke === 'function');
    for (const isTestNet of [true, false]) {
      const result = await frame.evaluate(isTestNet => Promise.race([
        window.runXmrClientSmoke({ isTestNet }),
        new Promise((_, reject) => setTimeout(() => reject(new Error('Wallet proof exceeded 30 seconds')), 30000)),
      ]), isTestNet);
      assert.equal(result.network, isTestNet ? 'stagenet' : 'mainnet');
      assert.equal(result.library, '0.11.22');
      assert.equal(result.crossOriginIsolated, false);
      assert.equal(result.sharedArrayBufferAvailable, false);
      for (const field of ['walletCreated', 'localMessageSignatureVerified', 'encryptedWalletReopened', 'wrongPasswordRejected', 'viewOnlyWalletCreated', 'indexedDbRoundTrip', 'restoredAfterWorkerRestart', 'deterministicKeyRecovery', 'nativeDaemonHeightRead', 'fixtureDaemonNetworkVerified']) {
        assert.equal(result[field], true, field);
      }
      assert.ok(result.wasmMemoryBytes > 0);
      cases.push({ surface: embedded ? 'cross-origin-sandboxed-iframe' : 'top-level', ...result });
    }
  }
  const frame = await (await page.waitForSelector('iframe')).contentFrame();
  await assert.rejects(frame.evaluate(() => window.runXmrClientSmoke({})), /Qortal node mode is unavailable/);
  await assert.rejects(frame.evaluate(() => window.runXmrClientSmoke(
    { isTestNet: true }, document.querySelector('meta[name="xmr-probe-mainnet-fixture"]').content,
  )), /Fixture daemon network mismatch/);
  const negativeCases = { unknownQortalModeRejected: true, mismatchedDaemonRejected: true };
  assert.deepEqual(blockedRequests, [], 'The engine attempted an unexpected network request');
  assert.deepEqual(browserErrors, [], 'Unexpected browser error');
  assert.ok(requests.some(request => request.path === appBase + 'monero.worker.js'));
  assert.ok(requests.every(request => ['GET', 'OPTIONS'].includes(request.method) || (request.method === 'POST' && ['get_info', 'get_height', 'get_version', 'get_block_count'].includes(request.rpcMethod))));
  const result = {
    status: 'passed', chromium: browser.version(), csp, cases, negativeCases,
    requests, blockedRequests, browserErrors,
    scope: 'Synthetic browser/iframe proof with loopback fixture daemon. No blockchain synchronization, payment signing/broadcast, actual Hub integration or production vault tested.',
  };
  await mkdir('results', { recursive: true });
  await writeFile('results/browser-smoke.json', JSON.stringify(result, null, 2) + '\n');
  console.log(JSON.stringify({ status: result.status, cases, negativeCases, requests: requests.length, blockedRequests }, null, 2));
} catch (error) {
  console.error(JSON.stringify({ browserErrors, blockedRequests, requests }, null, 2));
  throw error;
} finally {
  if (browser) await browser.close();
  await Promise.all([app, parent, mainnet, stagenet].map(server => new Promise(resolve => server.close(resolve))));
}
