# ARRR owned Sapling preparation testing

The `qdn-performance` Core exposes authenticated POST
`/crosschain/arrr/tradepreparation` for matching Hub `xmr-support` and native
JNI 0.3.11. The existing 0.3.10 QDN signature remains the default until a new
five-platform native bundle is built, verified, published and confirmed. A JAR
update alone therefore does not enable Ironwood-only trade preparation.

Hub uses Core's trade data and integer `/crosschain/arrr/tradefees` quote. It
queries preparation after the normal buy approval, keeping entropy inside Hub
and Core. When required, a separate checkbox approval displays the preparation
fee (0.0001 ARRR), the whole currently spendable Ironwood balance minus that fee,
and the owned Sapling destination. That self-payment remains in the wallet even
if the intended trade later fails, expires or is cancelled.

The native quote binds the wallet, network, source key group, eligible inputs,
owned destination and required trade amount. No caller-selected destination or
fee increase is accepted. The signed transaction is saved before broadcast;
existing wallet tables reserve inputs and retain their transaction expiry.
Retries after an unknown response or restart use the saved transaction. Status
polls do not broadcast. Hub waits up to eight minutes for confirmed, scanned,
witnessed Sapling eligibility before sending the trade response. A timeout,
decline, insufficient balance, changed account/node or unsupported library
never starts the trade. A future retry resumes the same preparation if pending.

No Core or native wallet schema migration is added. QDN cache performance,
previous Pirate recovery changes and Qortal-mainnet/Monero-stagenet support stay
on the same testing branch. Do not remove or reset existing ambiguous funding
intents to retry a trade.

## Build and delivery

1. Push native owner `crowetic/Stashi-Wallet` main and run
   `.github/workflows/qortal-jni.yml` on that exact commit. Require all tests and
   all five platform build/smoke jobs to pass. Retain the combined archive and
   provenance/checksum files.
2. Verify the bundle with the existing native packaging tools. Update Core's
   `tools/stage-pirate-unified-qdn-bundle.sh` pinned SHA-256 and workflow URL to
   that verified archive, and update the 0.3.10 labels in the publication wrapper
   to 0.3.11. Do not guess a hash or bypass the existing guard.
3. Run `./tools/prep-and-publish-arrr-wallet.sh` for that staged archive. The
   owner signs locally. Wait for confirmation and verify all published files.
   Set the new native signature only after verification, then rebuild Core.
4. Build matching Hub with `./build-appimage.sh`; require a successful Docker
   receipt in `electron/dist/desktop-build.json`. A renderer build or `cap sync`
   alone does not update an existing AppImage.
5. Build Core using `mvn -DskipTests package`, retaining version and checksum
   receipts. Do not announce the existing default 0.3.10 library as supporting
   this feature, or publish an incomplete platform bundle.

## Acceptance and rollback

Use a small owner-approved balance for live testing. Verify an Ironwood-only
wallet quotes its own Sapling address and the extra fee; decline first and
check no payment appears. Approve once, interrupt/restart Core during the pending
period, then retry and confirm the same preparation transaction ID and one fee.
After confirmation and scanning, verify contract funding, a second cross-chain
trade, redemption and an expired-contract refund against the Pirate chain.
Also check a ready Sapling or mixed wallet needs no extra approval/payment.
Offline fixtures prove construction and recovery contracts; they do not prove
live Pirate acceptance or resolution of an earlier funded refund.

Retain the previous JAR, AppImage, settings/native signature and wallet data.
Stop Core before restoring the previous JAR/native selection. Keep the current
wallet database, preparation sidecars and trade recovery records. Wait for a
pending preparation to confirm or expire and be scanned; never delete its
journal or reservations to force a second payment. No migration reversal or
reindex is needed for these changes.
