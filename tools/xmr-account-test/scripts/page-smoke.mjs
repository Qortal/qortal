import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { chromium } from 'playwright';

// Public, unfunded fixture from Hub's independently verified derivation vectors.
const wallet = {
  derivationVersion: 1, network: 'mainnet',
  address: '452yEogpX9A8dvgUKPzw3v8XnA4BeZr3AhQvApD6VMK4RiQAtNn8JqsLJAmkzt1vXQATfXV1fDKTyXVZV2tHgRgm5v3J8Zf',
  publicSpendKey: '5a0580cfdc2bd92da8124ed3e719092d05dcf2db996b31f19c1514f9e53b5793',
  publicViewKey: 'c10a3d4ada91be735d00f1512ed3e7388e574af7af94e4b64d5f62b14b5c9a2b',
};
const root = new URL('../', import.meta.url);
const server = createServer(async (request, response) => {
  const file = request.url === '/' ? 'index.html' : request.url === '/probe.js' ? 'probe.js' : null;
  if (!file) return response.writeHead(404).end();
  response.setHeader('Content-Type', file.endsWith('.js') ? 'text/javascript' : 'text/html');
  response.end(await readFile(new URL(file, root)));
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const origin = `http://127.0.0.1:${server.address().port}`;
let browser;
try {
  browser = await chromium.launch({ headless: true, executablePath: process.env.XMR_CHROMIUM_PATH || undefined });
  const context = await browser.newContext();
  await context.route('**/*', route => new URL(route.request().url()).origin === origin ? route.continue() : route.abort());
  const page = await context.newPage();
  await page.goto(origin);
  await page.locator('#request').click();
  assert.match(await page.locator('#status').textContent(), /bridge is unavailable/);
  await page.evaluate(wallet => {
    window.calls = [];
    window.qortalRequest = async request => { window.calls.push(request); return wallet; };
  }, wallet);
  await page.locator('#request').click();
  await page.waitForFunction(() => document.getElementById('status').textContent.startsWith('PASS'));
  assert.deepEqual(JSON.parse(await page.locator('#result').textContent()), wallet);
  assert.deepEqual(await page.evaluate(() => window.calls), [{ action: 'GET_USER_WALLET', coin: 'XMR' }]);
  await page.locator('#expected').fill(wallet.address);
  await page.locator('#compare').click();
  assert.match(await page.locator('#comparison').textContent(), /PASS/);
  await page.locator('#expected').fill('another-public-address');
  await page.locator('#compare').click();
  assert.match(await page.locator('#comparison').textContent(), /differ/);
  await page.evaluate(wallet => { window.qortalRequest = async () => ({ ...wallet, privateSpendKey: 'private-fixture-must-not-be-displayed' }); }, wallet);
  await page.locator('#request').click();
  await page.waitForFunction(() => document.getElementById('status').textContent.startsWith('Unexpected'));
  assert.doesNotMatch(await page.locator('body').textContent(), /private-fixture-must-not-be-displayed/);
  await page.evaluate(() => { window.qortalRequest = async () => { throw new Error('secret-error-must-not-be-displayed'); }; });
  await page.locator('#request').click();
  await page.waitForFunction(() => document.getElementById('status').textContent.startsWith('Request failed'));
  assert.doesNotMatch(await page.locator('body').textContent(), /secret-error-must-not-be-displayed/);
  assert.equal(await page.locator('#compare').isDisabled(), true);
  console.log('PASS: manual account page, public-only request/display, recovery comparison and rejected-response guards. Mock Hub bridge only; no real account, node or payment.');
} finally {
  if (browser) await browser.close();
  await new Promise(resolve => server.close(resolve));
}
