package org.qortal.crosschain;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.qortal.test.common.Common;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PirateTransactionHistoryTests extends Common {

    @Test
    public void unifiedLocalHistoryMapsAmountsAndKnownIncomingAddresses() throws ForeignBlockchainException {
        JSONArray transactions = new JSONArray()
                .put(transaction("outgoing", 1_700_000_002L, "-250000000", "10000", "sent memo"))
                .put(transaction("incoming", 1_700_000_001L, "123456789", "0", null));
        JSONArray deposits = new JSONArray()
                .put(deposit("incoming", "zs-known-recipient", "123456789", "external"))
                .put(deposit("outgoing", "zs-internal-change", "749990000", "internal"));

        List<SimpleTransaction> result = PirateTransactionHistory.parseUnified(
                transactions, deposits, "zs-current-wallet");

        assertEquals(2, result.size());
        SimpleTransaction outgoing = result.get(0);
        assertEquals("outgoing", outgoing.getTxHash());
        assertEquals(-250000000L, outgoing.getTotalAmount());
        assertEquals(10000L, outgoing.getFeeAmount());
        assertEquals("zs-current-wallet", outgoing.getInputs().get(0).getAddress());
        assertTrue(outgoing.getInputs().get(0).getAddressInWallet());
        assertEquals("[UNKNOWN]", outgoing.getOutputs().get(0).getAddress());
        assertFalse(outgoing.getOutputs().get(0).getAddressInWallet());

        SimpleTransaction incoming = result.get(1);
        assertEquals("incoming", incoming.getTxHash());
        assertEquals(123456789L, incoming.getTotalAmount());
        assertEquals(1_700_000_001_000L, incoming.getTimestamp().longValue());
        assertEquals("[PRIVATE]", incoming.getInputs().get(0).getAddress());
        assertEquals("zs-known-recipient", incoming.getOutputs().get(0).getAddress());
        assertTrue(incoming.getOutputs().get(0).getAddressInWallet());
    }

    @Test
    public void unifiedSplitSelfTransferCollapsesToOneTransaction() throws ForeignBlockchainException {
        JSONArray transactions = new JSONArray()
                .put(transaction("self-transfer", 1_700_000_003L, "-50000000", "10000", null))
                .put(transaction("self-transfer", 1_700_000_003L, "50000000", "0", null));
        JSONArray deposits = new JSONArray()
                .put(deposit("self-transfer", "zs-self", "50000000", "external"));

        List<SimpleTransaction> result = PirateTransactionHistory.parseUnified(
                transactions, deposits, "zs-current-wallet");

        assertEquals(1, result.size());
        assertEquals(0L, result.get(0).getTotalAmount());
        assertEquals(10000L, result.get(0).getFeeAmount());
        assertEquals(2, result.get(0).getInputs().size());
        assertEquals(2, result.get(0).getOutputs().size());
    }

    @Test
    public void walletHistoryCacheCopiesAndInvalidatesResults() {
        PirateWallet.TransactionHistoryCache cache = new PirateWallet.TransactionHistoryCache();
        SimpleTransaction transaction = new SimpleTransaction(
                "cached", 1L, 2L, 3L, List.of(), List.of(), null);
        List<SimpleTransaction> original = Arrays.asList(transaction);

        assertNull(cache.get());
        cache.put(original);
        original.set(0, new SimpleTransaction());

        List<SimpleTransaction> cached = cache.get();
        assertEquals(1, cached.size());
        assertEquals("cached", cached.get(0).getTxHash());
        cached.clear();
        assertEquals(1, cache.get().size());

        cache.clear();
        assertNull(cache.get());
    }

    @Test
    public void unresolvedRecipientsAreDetectedWithoutRejectingExactHistory() {
        SimpleTransaction unresolved = new SimpleTransaction(
                "unresolved", 1L, -25L, 1L, List.of(),
                List.of(new SimpleTransaction.Output("[UNKNOWN]", 25L, false)), null);
        SimpleTransaction exact = new SimpleTransaction(
                "exact", 1L, -25L, 1L, List.of(),
                List.of(new SimpleTransaction.Output("zs1-exact", 25L, false)), null);

        assertTrue(PirateTransactionHistory.hasUnresolvedRecipients(List.of(unresolved, exact)));
        assertFalse(PirateTransactionHistory.hasUnresolvedRecipients(List.of(exact)));
        assertFalse(PirateTransactionHistory.hasUnresolvedRecipients(List.of()));
    }

    @Test
    public void qortalMetadataPreservesExactOutgoingRecipients() throws ForeignBlockchainException {
        JSONArray history = new JSONArray().put(new JSONObject()
                .put("block_height", 42)
                .put("datetime", 1_700_000_000L)
                .put("txid", "exact-outgoing")
                .put("amount", -25L)
                .put("fee", 1L)
                .put("incoming_metadata", new JSONArray())
                .put("incoming_metadata_change", new JSONArray())
                .put("outgoing_metadata", new JSONArray().put(new JSONObject()
                        .put("address", "zs1-exact-recipient")
                        .put("value", 25L)
                        .put("memo", "exact memo")))
                .put("outgoing_metadata_change", new JSONArray()));

        List<SimpleTransaction> result = PirateTransactionHistory.parseQortal(
                history, "zs1-current-wallet", 10_000L);

        assertEquals(1, result.size());
        assertEquals("zs1-exact-recipient", result.get(0).getOutputs().get(0).getAddress());
        assertEquals(25L, result.get(0).getOutputs().get(0).getAmount());
        assertFalse(PirateTransactionHistory.hasUnresolvedRecipients(result));
    }

    @Test
    public void recoveredDetailsReplaceOnlyUnknownExternalRecipients() throws ForeignBlockchainException {
        JSONObject details = new JSONObject()
                .put("txid", "outgoing")
                .put("memo", JSONObject.NULL)
                .put("recipients", new JSONArray()
                        .put(new JSONObject()
                                .put("address", "zs-external")
                                .put("amount", "240000000")
                                .put("memo", "recipient memo"))
                        .put(new JSONObject()
                                .put("address", "zs-change")
                                .put("amount", "749990000")
                                .put("memo", JSONObject.NULL)));
        PirateTransactionHistory.RecoveredRecipients recovered =
                PirateTransactionHistory.parseTransactionDetails(
                        details, Set.of("zs-change"), "zs-current-wallet");
        SimpleTransaction unresolved = new SimpleTransaction(
                "outgoing", 1L, -240000000L, 10000L,
                List.of(new SimpleTransaction.Input("zs-current-wallet", 240000000L, true)),
                List.of(new SimpleTransaction.Output("[UNKNOWN]", 240000000L, false)), null);

        List<SimpleTransaction> enriched = PirateTransactionHistory.overlayRecoveredRecipients(
                List.of(unresolved), Map.of("outgoing", recovered));

        assertEquals(1, enriched.size());
        assertEquals(1, enriched.get(0).getOutputs().size());
        assertEquals("zs-external", enriched.get(0).getOutputs().get(0).getAddress());
        assertEquals(240000000L, enriched.get(0).getOutputs().get(0).getAmount());
        assertEquals("recipient memo", enriched.get(0).getMemo());
        assertFalse(PirateTransactionHistory.hasUnresolvedRecipients(enriched));
    }

    @Test
    public void internalDepositAddressesAreAvailableForChangeFiltering() {
        JSONArray deposits = new JSONArray()
                .put(deposit("outgoing", "zs-change", "10", "internal"))
                .put(deposit("incoming", "zs-external", "20", "external"));

        assertEquals(Set.of("zs-change"), PirateTransactionHistory.internalAddresses(deposits));
    }

    @Test
    public void recipientCacheClaimsOneTransactionAndOverlaysCompletedRecovery() {
        PirateChain.RecipientEnrichmentCache cache = new PirateChain.RecipientEnrichmentCache(10);
        SimpleTransaction first = unresolvedTransaction("first");
        SimpleTransaction second = unresolvedTransaction("second");

        assertEquals("first", cache.claimNext(List.of(first, second), 100L));
        assertNull(cache.claimNext(List.of(first, second), 100L));
        cache.completed("first", new PirateTransactionHistory.RecoveredRecipients(
                "first", List.of(new SimpleTransaction.Output("zs-first", 25L, false)), null));

        assertEquals("second", cache.claimNext(List.of(first, second), 100L));
        cache.failed("second", 200L);
        assertNull(cache.claimNext(List.of(first, second), 199L));
        assertEquals("second", cache.claimNext(List.of(first, second), 200L));

        List<SimpleTransaction> enriched = cache.overlay(List.of(first, second));
        assertEquals("zs-first", enriched.get(0).getOutputs().get(0).getAddress());
        assertEquals("[UNKNOWN]", enriched.get(1).getOutputs().get(0).getAddress());
    }

    private static SimpleTransaction unresolvedTransaction(String txId) {
        return new SimpleTransaction(txId, 1L, -25L, 1L,
                Collections.emptyList(),
                List.of(new SimpleTransaction.Output("[UNKNOWN]", 25L, false)), null);
    }

    @Test
    public void failedRecipientDoesNotStarveUntriedOlderTransactionsAfterCooldown() {
        PirateChain.RecipientEnrichmentCache cache = new PirateChain.RecipientEnrichmentCache(10);
        List<SimpleTransaction> transactions = List.of(unresolvedTransaction("unrecoverable-newest"),
                unresolvedTransaction("recoverable-older"));
        assertEquals("unrecoverable-newest", cache.claimNext(transactions, 100L));
        cache.failed("unrecoverable-newest", 200L);
        // A slow native lookup can exceed the cooldown before the next claim.
        assertEquals("recoverable-older", cache.claimNext(transactions, 201L));
    }

    @Test(timeout = 10000)
    public void enrichmentBatchContinuesAfterFailureAndStopsAtItsBudget() throws Exception {
        PirateChain.RecipientEnrichmentCache cache = new PirateChain.RecipientEnrichmentCache(10);
        List<SimpleTransaction> transactions = new ArrayList<>();
        for (int i = 0; i < 8; ++i) transactions.add(unresolvedTransaction("transaction-" + i));
        List<String> attempted = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch batch = new CountDownLatch(4);
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
        try {
            cache.scheduleBatch(scheduler, transactions, txId -> {
                attempted.add(txId);
                try {
                    if (txId.equals("transaction-0")) throw new IllegalStateException("unavailable");
                    cache.completed(txId, new PirateTransactionHistory.RecoveredRecipients(txId,
                            List.of(new SimpleTransaction.Output("zs-recovered-" + txId, 25L, false)), null));
                } finally {
                    batch.countDown();
                }
            }, 4, 0L, TimeUnit.MILLISECONDS);
            assertTrue("A failed first lookup must not stop recovery", batch.await(5, TimeUnit.SECONDS));
            scheduler.shutdown();
            assertTrue(scheduler.awaitTermination(2, TimeUnit.SECONDS));
            assertEquals(List.of("transaction-0", "transaction-1", "transaction-2", "transaction-3"), attempted);
            List<SimpleTransaction> enriched = cache.overlay(transactions);
            assertEquals("[UNKNOWN]", enriched.get(0).getOutputs().get(0).getAddress());
            assertEquals("zs-recovered-transaction-3", enriched.get(3).getOutputs().get(0).getAddress());
            assertEquals("transaction-4", cache.claimNext(transactions, System.currentTimeMillis()));
        } finally {
            scheduler.shutdownNow();
            assertTrue(scheduler.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    public void rejectedEnrichmentSchedulingReleasesItsClaim() {
        PirateChain.RecipientEnrichmentCache cache = new PirateChain.RecipientEnrichmentCache(10);
        List<SimpleTransaction> transactions = List.of(unresolvedTransaction("first"));
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
        scheduler.shutdown();
        cache.scheduleBatch(scheduler, transactions, txId -> {
            throw new AssertionError("Rejected work must not run");
        }, 4, 0L, TimeUnit.MILLISECONDS);
        assertEquals("first", cache.claimNext(transactions, 100L));
    }

    private static JSONObject transaction(String txid, long timestamp, String amount, String fee, String memo) {
        JSONObject transaction = new JSONObject()
                .put("txid", txid)
                .put("timestamp", timestamp)
                .put("amount", amount)
                .put("fee", fee);
        if (memo != null) {
            transaction.put("memo", memo);
        }
        return transaction;
    }

    private static JSONObject deposit(String txid, String address, String value, String scope) {
        return new JSONObject()
                .put("txid", txid)
                .put("address", address)
                .put("value", value)
                .put("address_scope", scope);
    }
}
