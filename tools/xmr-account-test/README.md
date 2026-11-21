# Public XMR account and backup check

This optional developer page requests only `GET_USER_WALLET` for XMR through
the actual Hub bridge. It displays public account fields and compares a restored
address with one supplied by the tester. It contains no wallet engine, private
keys, signing, balance scanning or broadcast code. Core and Hub do not package it.

Follow the [manual fresh-profile backup check](../../docs/arrr-xmr-testing.md#manual-account-and-backup-test)
using the matching Hub testing build. Serve the page through Hub developer mode,
not an ordinary browser tab, for actual permission/account recovery acceptance.

## Automated page check

From this directory, with Node 20.19+:

```bash
npm ci --ignore-scripts
npx playwright install chromium
npm test
```

Alternatively set `XMR_CHROMIUM_PATH` to an installed compatible Chromium when
running `npm test`. The runner starts a temporary loopback server, blocks external
requests and uses an unfunded public fixture with a **mock Hub bridge**. It checks
the request shape, strict public-only response display, recovery comparison,
denial and error redaction. It does not exercise an actual Hub account, wallet,
node or payment; the manual check establishes those integration results.

This test was retained when the obsolete `tools/xmr-hub-client` browser wallet
experiment was removed. There is no Monero SDK, Vite or filesystem shim dependency.
