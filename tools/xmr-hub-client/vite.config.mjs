import { copyFileSync, mkdirSync } from 'node:fs';
import { defineConfig } from 'vite';
import { nodePolyfills } from 'vite-plugin-node-polyfills';

// The pinned upstream worker embeds its WASM; publish it with the app, not a CDN.
mkdirSync('public', { recursive: true });
for (const name of ['monero.worker.js', 'monero.worker.js.LICENSE.txt']) {
  copyFileSync(`node_modules/monero-ts/dist/${name}`, `public/${name}`);
}
copyFileSync('node_modules/monero-ts/LICENSE.txt', 'public/monero-ts.LICENSE.txt');

export default defineConfig({
  base: './',
  plugins: [nodePolyfills({
    include: ['http', 'https', 'fs', 'stream', 'util', 'path', 'buffer', 'events', 'url'],
    overrides: { fs: 'memfs' },
  })],
  build: { commonjsOptions: { transformMixedEsModules: true } },
});
