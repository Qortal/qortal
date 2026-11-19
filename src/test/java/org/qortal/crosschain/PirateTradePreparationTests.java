package org.qortal.crosschain;

import org.junit.Test;
import org.qortal.api.model.crosschain.PirateChainTradePreparationRequest;
import static org.junit.Assert.*;

public class PirateTradePreparationTests {
    @Test
    public void nativeQuoteUsesAtomicAmountAndOwnedSourceWithoutEntropyOrApproval() {
        PirateChainTradePreparationRequest request = new PirateChainTradePreparationRequest();
        request.entropy58 = "must-not-enter-native-arguments";
        request.requiredAmount = 123456789;
        var args = PirateChain.tradePreparationArguments(request, "zs1owned");
        assertEquals(123456789L, args.getLong("required_amount"));
        assertEquals("zs1owned", args.getString("input"));
        assertEquals(2, args.length());
    }
    @Test
    public void approvalForwardsOnlyTheExactQuoteAndMaximumFee() {
        PirateChainTradePreparationRequest request = new PirateChainTradePreparationRequest();
        request.requiredAmount = 123456789;
        request.approvedQuote = "a".repeat(64);
        request.maxFee = 10000L;
        var args = PirateChain.tradePreparationArguments(request, "zs1owned");
        assertEquals(request.approvedQuote, args.getString("approved_quote"));
        assertEquals(10000L, args.getLong("max_fee"));
        assertEquals(4, args.length());
    }
    @Test
    public void unsupportedOrInvalidNativeRepliesCannotBecomeReady() throws Exception {
        for (String response : new String[]{"{}", "invalid", "{\"result\":\"success\"}", "{\"status\":\"unknown\"}", "{\"status\":\"ready\",\"error\":\"unsupported command\"}"}) {
            try { PirateChain.parseTradePreparationResponse(response); fail("Accepted invalid native reply"); }
            catch (ForeignBlockchainException expected) { }
        }
        for (String status : new String[]{"ready", "needsPreparation", "pending", "insufficient"})
            assertEquals(status, PirateChain.parseTradePreparationResponse("{\"status\":\""+status+"\"}").getString("status"));
    }
}
