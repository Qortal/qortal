# ARRR buy preparation and first-use recovery, 2026-11-23

This follow-up belongs to `qdn-performance`, based on the `6.1.9-d0398d1`
testing release. The separate `optimize-data-nov-2026` experiment is unchanged.

## Findings and limits

The supplied Core log identifies `qortal-6.1.9-d0398d1`, build timestamp
20261121184805, and the confirmed JNI 0.3.11 publication beginning `2wr3xfLy`.
At 22:46:34 on November 22, `/crosschain/arrr/tradepreparation` reports
`Wallet busy. Please try again later.` A sync-status initialization times out at
22:46:35; background recipient enrichment completes at 22:46:39. The screenshots
show Hub's exact 30000 ms `CREATE_TRADE_BUY_ORDER` timeout and an additional local
history row with zero net amount and a 0.0001 ARRR fee.

Source review and regressions identify three defects:

1. Hub applies the ordinary 30-second read deadline to an ARRR buy. Preparation
   has a separate approval, shielded proof construction and an eight-minute
   confirmation/scan wait. Timing out the message bridge does not cancel that
   work and prevents the Q-App receiving its eventual result.
2. Core's optional background recipient enrichment calls the generic native
   transaction-details API while owning the serialized wallet lane. Its memo
   and recipient network calls have long retry budgets. Preparation/status
   requests can consequently wait behind optional display work and fail busy.
3. Core treats absent local files as proof of a new account and starts at the
   current lightwallet height. Importing a previously used account onto another
   node is indistinguishable and silently skips its previous deposits.

An Ironwood-to-owned-Sapling preparation intentionally moves the whole currently
spendable Ironwood balance, less its separately approved fee, to the same account's
Sapling address. That is not the 2.5 ARRR contract funding in the reported offer.
After scanning both sides, a self-transfer has zero net value excluding its fee.
Native local transaction intents can appear before another node sees a confirmed
transaction. A missing remote row alone does not establish whether it was
broadcast, rejected, expired or confirmed. The supplied log contains no full
preparation transaction ID or native broadcast result, so none is claimed here.

Hub creates the trade response only after preparation is ready. A preparation
failure can therefore leave no new TradeBot state or funding log. Do not clear
pending preparation journals, wallet reservations or trade recovery data to
force a retry. This change performs no funded send or live wallet repair.

## Resulting behavior

Core now defaults an uninitialized account to its configured historical birthday.
The public null-seed lookup wallet may still start at the tip. Existing registry
birthdays and legacy migrations are preserved. An explicit per-wallet choice
overrides `arrrNewWalletBirthday` for a clean namespace only.

Authenticated POST `/crosschain/arrr/walletinitialization` accepts:

- `entropy58` alone: inspect, without creating or opening a wallet;
- `entropy58` and a positive integer `birthday`: select a recovery start;
- `entropy58` and `useCurrentHeight: true`: explicitly select the current block.

The two selection fields are mutually exclusive. Responses include
`existingWallet`, `needsSelection`, `birthday` and `defaultBirthday`. The first
selection wins. Existing Unified registries and legacy wallets are never replaced
or reset by this endpoint. The wallet controller serializes selection with actual
initialization. The small `scan-start-height` file in that wallet's Unified
namespace stores only the selected height; unreadable/invalid choices fail closed.

Matching Hub asks on first use of each wallet/node, with historical recovery as
the default. A checkbox permits current-height initialization only after the owner
confirms the wallet has never received funds. Concurrent wallet reads share the
prompt. Approval is bound to the account and selected node. The default prompt
uses Core's configured historical start; advanced callers can select a different
positive birthday through the authenticated API before initialization.

Hub keeps the ARRR buy bridge request attached for up to 30 minutes, covering
approval, proof construction and the bounded preparation wait. Initial quote and
pending-status reads retry recognized temporary wallet-busy/sync errors. An
approved monetary request is never automatically repeated by this retry logic.
Preparation polling remains bounded; errors never proceed to contract funding.

Core uses JNI 0.3.11's existing `qortal_list_transactions_partial` for background
recipient recovery. One five-second network recovery budget replaces the long
generic details calls. Only complete metadata for the exact requested transaction
can replace unknown recipients; incomplete evidence stays unresolved. Main-wallet
amounts are not rewritten from recipient estimates. Preparation request logs now
identify quote/status versus approval without logging entropy or destinations.

## Rollout and recovery

Install the newly versioned Core test JAR and the matching Hub desktop test build.
Neither a Core update alone nor a Hub update alone supplies the whole fix.
Q-Trade and Q-Wallets use their existing request interfaces. No new native build,
QDN publication, Core schema migration, consensus activation or reindex is needed;
the Core database remains schema 55.

Test with an unfunded account first: decline setup; choose historical recovery;
then use a different never-used account to select current height. On another
node, importing an account must prompt again because its files are node-local.
Only explicitly new accounts should use current height. Verify the same derived
address and a complete scan before trusting a restored balance.

**An already initialized wallet with an incorrectly late birthday is not repaired
automatically.** Changing a first-use setting does not rewind an existing native
database. Preserve its current wallet files, preparation journals and trade state;
determine a suitable earlier birthday and use a reviewed native rescan/recovery
procedure. Do not delete its namespace or copy an actively open database. A
confirmed transaction ID/native diagnostic is needed to resolve the reported
pending preparation before any monetary retry.

For acceptance, separately approve a small preparation, confirm its transaction
on Pirate, let the wallet scan and become spend-ready, then verify exactly one
contract funding and the resulting TradeBot state. Test restart and account/node
changes while pending. These funded checks require the owner's explicit test
authorization; offline passes do not establish live acceptance or resolve prior
funded refunds.

To recover from a bad executable, stop the test Core/Hub normally and return to
the retained compatible binaries while preserving current repository/wallet data.
An older Core retains its unsafe first-use default, so avoid creating fresh
restored namespaces with it. Never roll wallet data back after a broadcast.
