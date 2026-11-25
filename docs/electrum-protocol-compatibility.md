# Electrum protocol compatibility

Core advertises Electrum protocol versions 1.2 through 1.4. The maximum must
match the RPC names and response formats implemented by `ElectrumX`.

Advertising a future version is unsafe: protocol 1.6 changes block-header
responses, and protocol 1.7 replaces `blockchain.scripthash.*` with
`blockchain.scriptpubkey.*`. Core currently uses the script-hash methods for
wallet history, balances and unspent outputs.

On 2026-10-28, the DGB servers `electrum1.cipig.net:20059` and
`electrum2.cipig.net:20059`, and DOGE server `electrum1.cipig.net:20060`, selected
1.7.0 when Core offered its previous 1.2–2.0 range. They returned JSON-RPC
`-32601` for script-hash history and unspent-output queries. Negotiating 1.4
on the same endpoints restored those calls. Probes used a synthetic all-zero
script hash, without any wallet credentials or transactions.

The unsupported-method failover remains useful for incompatible servers, but
cannot repair negotiation when every upgraded server selects the same
unsupported protocol. Raising the advertised maximum requires implementing
and testing that protocol first.

`ElectrumXProtocolTests` exercises real loopback TCP/JSON connections against
both a 1.4 server and an upgraded server that removes script-hash calls at 1.7
and changes header responses at 1.6. It checks history, unspent outputs,
confirmed balances and block-header decoding through the production provider.

References:

- [ElectrumX 2.0 release notes](https://electrumx-spesmilo.readthedocs.io/en/latest/changelog.html)
- [Upstream protocol-dependent RPC handlers](https://github.com/spesmilo/electrumx/blob/master/src/electrumx/server/session.py)

This compatibility fix changes no Qortal database schema and requires no
reindexing. Existing connections negotiate again when the updated Core starts.
