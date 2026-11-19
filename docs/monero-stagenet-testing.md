# Qortal mainnet / Monero stagenet wallet testing

Add `"moneroNetwork": "STAGENET"` to the test node's existing settings JSON
alongside its other fields. Keep Qortal `isTestNet` false (or absent). This
changes only the public wallet-network hint in `/admin/info`; it does not
change Qortal consensus, balances, ports or the selected Qortal chain. Stop and
restart the owner-operated test Core through its normal lifecycle after retaining
the prior JAR/settings and a consistent backup. No live configuration is changed
by this source task.

An explicit Monero network overrides the default pairing with Qortal `isTestNet`.
Both `STAGENET` and `stagenet` are accepted; names are case-insensitive and
surrounding whitespace is ignored. An invalid explicit value stops Core startup
with a settings error.
Earlier builds, including `6.1.9-e2f1eae`, require uppercase `STAGENET`: lowercase
values can silently select mainnet. Restart Core after changing the setting;
reopening Hub alone does not reload it. Before connecting the XMR wallet, verify
that `/admin/info` reports `isTestNet: false` and `moneroNetwork: "stagenet"`.

The matching XMR Hub honors `moneroNetwork` and shows a stagenet wallet even on
Qortal mainnet. Old Hubs ignore the hint and must be replaced for this test.
The Q-App cannot supply a different network. Private keys, scans and signing
remain in local Hub; this Core JAR is not an XMR wallet RPC server.

Remove `moneroNetwork` to restore the default pairing: Qortal mainnet uses Monero
mainnet, and Qortal testnet uses Monero stagenet. Mainnet and stagenet addresses
and scan caches are distinct; the existing Qortal backup recovers both keys.
The first functional Hub testing build permits spending only on stagenet.

Stagenet remote daemon: `https://xmr.nodely.dev:38089` (restricted RPC, public
operator [documentation](https://nodely.io/docs/monero)). Its `get_info` was
verified as stagenet and synchronized during implementation. Availability is
external. All remote daemons remain untrusted for wallet operations.
Get worthless test coins manually from the
[Nodely stagenet faucet](https://faucet-stagenet.xmr.nodely.dev/).
[Operator documentation](https://nodely.io/docs/monero/faucet/) describes claim
limits and payout timing. No faucet claim or public transaction is performed by
the build. Keep mainnet funds out of this first wallet acceptance round.

A new Monero trading AT is separate work. Do not add a placeholder hash to
`DeployAtTransaction.APPROVED_CODE_HASHES`, disable its activation guard, or
advertise QORT/XMR cross-chain trades merely because this wallet-network selector
is present.
