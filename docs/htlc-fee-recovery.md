# Bitcoin-family fees and explicit recovery

This follow-up applies to BTC, LTC, DOGE, DGB and RVN on `qdn-performance`.
It does not add a schema migration. The recovery schema remains version 55;
the existing funding outpoint, reserve and 64-byte checkpoint fields are reused.
ARRR continues using its native transaction builder and XMR uses Hub's wallet engine.

## Fee policy

With default wallet fees, ordinary Core sends and trade funding use at least the
configured fee rate and the connected Electrum server's reported relay minimum. An explicit wallet
fee below that reported minimum is rejected, rather than silently increased.
Hub's watch-only payment preparation receives the same fee recommendation.
The existing BTC three-argument funding method retains its 20 atomic units/byte
floor. A failed or malformed relay-policy lookup stops preparation.

For new HTLCs, the reserve covers a 309-byte canonical single-input redemption
with the maximum signature length, at the greater of those two fee rates. The
configured absolute HTLC fee remains a floor. The public `feerequired` GET APIs,
Core funding, batch funding and Hub local-signing funding preparation agree on
that reserve. Before locking QORT to a new buyer, the seller requires that reserve
and a unique qualifying deposit. Raising a current fee does not make an older
deposit disappear from settlement or refund discovery.

Every automatic Bitcoin-family HTLC redemption/refund checks its actual script,
key, secret where applicable, unique funding output, worst-case signed size and
final signed size. It preserves the agreed payment amount. If the original
reserve cannot cover the fee, it pauses and logs `HTLC fee approval required` with
the AT, trade address, reserve, required fee and authenticated preview URL.
`redeemAll` and `refundAll` continue other eligible attempts but never approve a
deduction from a trade's payout.

Electrum's `blockchain.relayfee` is a reported policy, not a guarantee of acceptance
by every node. Core negotiates protocol 1.2–1.4; see the
[ElectrumX protocol reference](https://electrumx.readthedocs.io/en/latest/protocol-methods.html#blockchain-relayfee).
Congestion, differing server policies, policy changes after preparation, and
non-fee validation failures can still reject a transaction. Do not automatically
raise fees or retry with a smaller payout after a rejection.

## Preview and approval

All calls below require the normal Core API authorization. These examples are
instructions for the wallet owner; they are not run by the release workflow.
Use the matching account's retained trade data and the AT/trade addresses in the
log. An old completed seller entry can be recovered with `redeem` as well.

1. Request an exact public preview:

   ```text
   GET /crosschain/htlc/redeem/{AT}/quote?tradeAddress={TRADE}
   GET /crosschain/htlc/refund/{AT}/quote?tradeAddress={TRADE}
   ```

2. Review the returned quote. It contains the destination, funding transaction
   and output index, operation, blockchain/network, expected amount, original
   reserve, required fee, actual fee, additional fee, net `receiveAmount`, expiry
   and `quoteId`. Monetary fields are decimal strings in atomic units; divide by
   100,000,000 for these coins. No private key, seed or redemption secret is
   returned. The quote expires after five minutes and does not sign or persist.

3. Only after accepting that exact fee and payout, submit the matching record:

   ```text
   POST /crosschain/htlc/redeem/{AT}
   POST /crosschain/htlc/refund/{AT}
   Content-Type: application/json
   X-API-KEY: <owner's API key>

   {"tradeAddress":"<from preview>","quoteId":"<from preview>","expiresAt":<from preview>}
   ```

The POST re-reads committed data while holding the trade-bot scheduler lock.
Changed fees, destination, funding output, account, operation or expiry invalidate
approval. A refund additionally needs its own wallet key, mature recorded
locktime and chain median time. A seller redemption needs verified QORT settlement
and the matching secret. Ambiguous deposits and dust payouts are rejected.
Only the approved net payout is signed. Any excess original reserve is still the
fee, matching the existing full-payout contract behavior.

An empty-body POST retains normal recovery behavior and cannot approve an extra
fee. The quote API recovers the primary A contract. Historical BTC v1's separate
1000-unit B contract is guarded by the same fee checks in its automatic path,
but is not separately approved by these A-contract endpoints. Do not use an
A-contract approval to authorize another contract. If the fee exceeds all value
in an output, this single-input recovery cannot spend it.

### HTTP content type and curl

The API documentation page at `/api-documentation/` offers `application/json`
for both per-AT approval bodies. A nonempty body with `Content-Type: */*` or
`text/plain` is rejected with HTTP 415 before recovery runs. Empty-body calls
remain optional, including callers that previously used `text/plain`.
Malformed JSON, a literal `null` approval, fractional expiry and trailing JSON
are rejected with HTTP 400; they cannot silently become normal recovery calls.

For direct curl use, set the public AT/trade addresses and enter the API key
without adding its value to shell history. These commands require `jq`:

```bash
CORE_URL=http://localhost:12391
AT_ADDRESS='<AT from the recovery log>'
TRADE_ADDRESS='<trade address from the recovery log>'
read -r -s -p 'Core API key: ' QORTAL_API_KEY
printf '\n'

curl --fail-with-body --silent --show-error --get \
  "$CORE_URL/crosschain/htlc/redeem/$AT_ADDRESS/quote" \
  -H "X-API-KEY: $QORTAL_API_KEY" -H 'Accept: application/json' \
  --data-urlencode "tradeAddress=$TRADE_ADDRESS" \
  -o qortal-redeem-quote.json
jq . qortal-redeem-quote.json
```

Review that single quote's destination, outpoint, fee, additional deduction and
net payout. Only if accepted, run the following within its five-minute expiry:

```bash
jq -e 'if type == "array" and length == 1 then .[0] |
  {tradeAddress, quoteId, expiresAt} else error("Expected exactly one quote") end' \
  qortal-redeem-quote.json > qortal-redeem-approval.json &&
curl --fail-with-body --silent --show-error \
  -X POST "$CORE_URL/crosschain/htlc/redeem/$AT_ADDRESS" \
  -H "X-API-KEY: $QORTAL_API_KEY" -H 'Accept: text/plain' \
  -H 'Content-Type: application/json' \
  --data-binary @qortal-redeem-approval.json
```

An expired or changed quote requires fetching and reviewing a new one. For an
owned buyer refund, use `refund` in both URLs and separate refund quote/approval
files. Never use a redeem quote for a refund. A `true` response acknowledges
submission; verify the resulting transaction and confirmations in Core's logs.

## Pending broadcasts and restarts

Before the first broadcast, Core commits the exact funding pair, original reserve
and approved transaction ID/fee. A success response means submission was
acknowledged; it does not mean settlement. An uncertain network response retains
that intent. Repeated API calls, batch recovery, automatic trade processing and
deletion cannot create a replacement while it is pending, including after a
database/JSON reload. Core requires the exact saved transaction spending the exact
funding output and three confirmations before clearing the hold.

If the transaction remains unknown, retain the data and investigate that saved
transaction ID and the public funding output. This release deliberately does not
expose a blind reset/replacement switch. Do not erase the checkpoint, import an
older snapshot over it, or change the funding reference to force a retry.

Stop Core and back up its complete repository, wallet files, settings and recovery
backups before updating. No blockchain reindex is needed. Retain current data and
this recovery-capable build for pending attempts during rollback. An older JAR
does not enforce the new fee approval/checkpoint safeguards.

## ARRR follow-up

Legacy completed ARRR trades can reconstruct their original funding pair from
confirmed raw history even after the output was spent. The spend's previous
transaction reference must fetch exactly the unique matching deposit bytes;
multiple deposits or conflicting evidence retain the record. Existing exact
outpoint, script and confirmation checks still decide settlement. This resolves
the missing audit reference for the reported old `BOB_DONE` entry; it is not a
new payment to that wallet.

Unified-wallet initialization now accepts its valid unscanned `birthday - 1`
checkpoint and uses the native registry's returned birthday when available.
An accepted checkpoint still requires synchronization; it does not bypass wallet
freshness or spendability checks. This fixes the initialization rejection shown
in the supplied account-switch log. It requires only the updated Core JAR, with
the existing JNI 0.3.11 publication; no new native publication or Hub build.

## Verification and remaining acceptance

The selected regression run completed 504 tests with no failures or errors and
nine existing witness skips. It includes actual signed single/multiple wallet
payments and HTLC redemptions/refunds on all five Bitcoin-family networks,
relay-policy socket responses, stale/altered approvals, uncertain broadcasts,
committed HSQLDB/JSON checkpoints, existing ARRR recovery, QDN and XMR coverage.
The existing JNI 0.3.11 was exercised with isolated loopback wallets for account
serialization, freshness, registry birthdays and constructor checkpoint bounds.
The boundary test injects the constructor's height while using the actual native
registry; it does not claim to reproduce a full mainnet historical scan.

The new resolver was also run read-only against the reported legacy ARRR
contract on `arrr3.qortal.link`. It recovered
`f2f7507b230a2ee89c2fd9da461b988202a13c3c69d931822b753d68989f6526:0`
with value 39,600,010,000 atomic units and classified its exact spend as redeemed.
No wallet access, transaction signing, broadcast or database changes were used
for that public-chain verification.

Live approval and confirmation of an underfunded DGB recovery, a new complete
ARRR cross-chain trade/refund, concurrent long historical account scans and the
original long-uptime QDN symptom remain release acceptance work.

The final clean packaged runtime is `6.1.9-7e4a364`, from commit `7e4a364f9`,
95,250,206 bytes. The same full selection also completed successfully against
that shaded JAR; class-loading records verified the production implementation
came from the packaged artifact. Its SHA-256 is:

```text
6ae325d13506c1621ad60d5392494e22698716ef8ec5a06880c27d038efb40e4
```

A second read-only run using that final JAR against `arrr.qortal.link` recovered
the same legacy funding pair and exact redeemed classification. No live
installation, service restart, funding, recovery broadcast or cloud upload was
performed. The owner publishes the checked artifact as `qortal.jar` in the
existing test share; local testing aliases point to the same checked bytes.
