# ARRR contract discovery across Pirate address indexes

Musiqo's November 20, 2026 `qortal-b109da5.log` records a funding acknowledgement
at 06:54:01 for transaction
`9e20ff92f96a4609bfeabe559593445d04febfba46ad429862682c019a8f0087`.
The Qortal AT `AbGrfbZ7b2CnvAdCFmnw5Nhw7bD2QCNDUp` remains OFFERING. At
08:53:34 the buyer reaches its refund deadline, then repeatedly reports
`HTLC_UNFUNDED`. There is no Sapling funding rejection for this attempt.

Read-only checks of `arrr3.qortal.link` and `arrr.qortal.link` independently found:

- The funding transaction confirmed at Pirate block **4,220,443**.
- Output **0** contains **500,010,000 atomic units / 5.0001 ARRR**, including the
  existing 0.0001 ARRR redeem/refund reserve.
- The actual output script is
  `a9148c99562ea007b0d503c1285a84794159c08eb49e87`.
- History and UTXO queries for the canonical P2SH address
  `bRYgz6zd6rSvP9SrDv5Yf49eewJT4A9edU` return nothing.
- The public-key-hash index alias `RN6cNPYRMLs1wJxgcRkaXvLyvKrsGKbXWa`
  returns the exact funding transaction and its still-unspent contract output.

This is an address-index classification defect on the serving Pirate nodes.
It makes the confirmed contract invisible to the earlier Core lookup, blocking
both the seller's funding detection and the buyer's refund discovery. It is
separate from the previously fixed Ironwood-only funding rejection. The output
is funded; an empty canonical address response does not establish its absence.

## Core compatibility handling

For a checksum-valid Pirate mainnet P2SH address, the lightwallet provider reads
both its normal index bucket and its same-hash public-key-hash index alias.
These are lookup keys only. The payment address, contract script, destination,
amount, fee, signatures and consensus rules do not change.

Both buckets are always queried, even when the first returns funding. After a
server upgrade or index repair, funding can be in one bucket and its later
spend in the other. History is merged and duplicate transactions are removed.
Only outputs matching the contract script or inputs revealing its matching
redeem-script hash are retained. An incomplete lookup or inconsistent duplicate
height fails rather than reporting an incomplete history as unspent funding.

UTXO lookups request both buckets together. Same-hash P2PKH outputs are excluded.
Candidate contract UTXOs must also match the raw funding transaction's output
index, value and script. Duplicate outpoints are counted once; conflicting
replies fail. Ordinary addresses retain their single lookup.

This correction applies to every ARRR buyer/seller state and manual
refundAll/redeemAll path using the shared lightwallet provider. It requires an
updated Core on the buyer and seller. The existing JNI **0.3.11** publication
remains selected; no new native publication, Hub/Q-Wallets/Q-Trade build, Core
schema migration, wallet migration or reindex is required by this correction.
An operator repairing the underlying Pirate-node index should follow that
node's own repair procedure; this Core change does not modify the serving node.

## Verification and recovery

`PirateAddressIndexTests` serves the public transaction bytes through loopback
RPCs. Coverage includes the observed wrong bucket, a corrected index, duplicate
and mixed buckets, refund confirmation depth, unrelated same-hash P2PKH outputs,
raw-output mismatches, conflicting replies and an unavailable second lookup.
The observed case fails against the previous packaged `6.1.9-b109da5` JAR.

Public read-only acceptance checks call the updated provider on the same two
servers and require it to return the original output and `FUNDED` status. These
checks do not initialize a wallet, sign or broadcast, and do not establish that
a live refund or a complete cross-chain trade has succeeded.

For the affected buyer, update Core and retain its existing wallet and trade
recovery records. Allow ordinary recovery to inspect the original attempt;
do not start a replacement attempt or delete recovery data to force funding.
The old attempt is already expired, so its expected next operation is refund
recovery rather than completing the old trade. A subsequent trade requires the
updated seller as well. Keep the previous JAR for code rollback and preserve
current wallet/recovery data throughout; no migration reversal is needed.
