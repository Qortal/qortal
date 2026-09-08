# Public-wallet discovery and client-signed funding

The new authenticated wallet endpoints for BTC/LTC/DOGE/DGB/RVN are:

- `POST /crosschain/<coin>/wallet/public/spend-context`, with `xpub58` and
  `expectedChainId`. Returns versioned mainnet spend data with decimal-string
  atomic amounts, confirmed P2PKH inputs, receive/change derivation paths and
  previous transactions. Root private keys are rejected. Discovery fails if it
  exceeds its key/output/response limits; it never silently truncates a wallet.
- `POST /crosschain/<coin>/send/broadcast`, with `expectedChainId` and
  `rawTransactionHex`. Parses the transaction and requires ElectrumX to return
  its expected ID. No wallet private key is needed.

`POST /crosschain/tradebot/respond/local` accepts `addresses` (up to 20 ATs),
`xpub58`, `receivingAddress` (Qortal), and `expectedChainId`. ACCTv3 only.
It durably stores trade-specific keys and the wallet XPUB in state
`ALICE_WAITING_FOR_FUNDING` (80), then returns the HTLC destinations, amounts,
redeem scripts and deadlines. A client must validate these and sign funding
locally. Core detects confirmed funding and resumes the existing offer-message,
AT-lock, redeem and refund flow. Repeated preparation of a pending trade is
idempotent. Changed/expired offers resume the existing state checks, including
recovery when an offer message was sent just before a crash.

This keeps the **wallet extended private key** out of new requests. Core still
holds **per-trade private keys and secrets** so it can complete/refund trades
while the client is closed. Existing trade data and legacy APIs are unchanged.
Do not downgrade Core while it manages trades in the new state.

Run the offline regression suite explicitly:

    mvn -DskipJUnitTests=false -Dtest=LocalWalletTests,LocalWalletApiTests test

The default Maven configuration skips JUnit. Tests independently verify
Hub-generated signatures for all five chains using bitcoinj, reject modified
payments/private-key discovery/wrong chains, and exercise watch-only trade
preparation and restart transitions. The fixtures are synthetic, publicly known
test keys and must never hold funds. Tests do not connect to foreign networks.
Full funded send, trade-completion and refund acceptance remains a separate
controlled integration test with an updated Hub.

Hub now builds and validates a BIP 174 PSBT locally using `@scure/btc-signer`.
Core's public API is unchanged: it supplies discovery data and receives only
finalized signed transaction bytes. Core does not need to implement PSBT parsing.
The offline fixtures cover the replacement signing engine as well as the public API.

Discovery queries history (including mempool) for the full receive/change gap.
It queries unspent outputs only for addresses with history and reads the chain
height at most once per response. Each new request rechecks history and outputs;
there is no cache of spendable balances across approvals.
