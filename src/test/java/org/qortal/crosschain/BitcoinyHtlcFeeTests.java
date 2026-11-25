package org.qortal.crosschain;

import org.bitcoinj.core.*;
import org.bitcoinj.params.*;
import org.libdohj.params.*;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.qortal.crypto.Crypto;
import org.qortal.test.common.Common;

import java.math.BigInteger;
import java.util.*;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Real signed HTLC transactions on every supported Bitcoin-family network. */
@RunWith(Parameterized.class)
public class BitcoinyHtlcFeeTests extends Common {
    private interface CheckedCall { void run() throws Exception; }
    private static void assertThrows(Class<? extends Exception> type, CheckedCall call) throws Exception {
        try { call.run(); fail("Expected " + type.getSimpleName()); }
        catch (Exception error) { if (!type.isInstance(error)) throw error; }
    }
    @Parameterized.Parameters public static Collection<Object[]> networks() {
        return Arrays.asList(new Object[][] {{"BTC", MainNetParams.get()}, {"LTC", LitecoinMainNetParams.get()},
                {"DOGE", DogecoinMainNetParams.get()}, {"DGB", DigibyteMainNetParams.get()}, {"RVN", RavencoinMainNetParams.get()}});
    }
    private final String symbol;
    private final NetworkParameters params;
    private Bitcoiny coin;
    private BitcoinyBlockchainProvider provider;
    private final ECKey buyer = ECKey.fromPrivate(BigInteger.valueOf(2));
    private final ECKey seller = ECKey.fromPrivate(BigInteger.valueOf(3));
    private final byte[] secret = new byte[32];
    private byte[] script;
    private static final long AMOUNT = 500_000_000_000L;
    private static final int LOCK = 1_795_000_000;

    public BitcoinyHtlcFeeTests(String symbol, NetworkParameters params) { this.symbol = symbol; this.params = params; }
    @Before public void setup() throws Exception {
        coin = mock(Bitcoiny.class); provider = mock(BitcoinyBlockchainProvider.class);
        when(coin.getCurrencyCode()).thenReturn(symbol); when(coin.getNetworkParameters()).thenReturn(params);
        when(coin.getBlockchainProvider()).thenReturn(provider);
        when(coin.getFeePerKb()).thenReturn(Coin.valueOf(100_000));
        when(coin.getP2shFee(any())).thenReturn(10_000L);
        when(provider.getMinimumRelayFeePerKb()).thenReturn(100_000L);
        secret[0] = 73;
        script = BitcoinyHTLC.buildScript(buyer.getPubKeyHash(), LOCK, seller.getPubKeyHash(), Crypto.hash160(secret));
    }
    private TransactionOutput funding(long reserve) {
        Transaction tx = new Transaction(params);
        tx.addInput(Sha256Hash.wrap("12".repeat(32)), 7, new Script(new byte[0]));
        tx.addOutput(Coin.valueOf(AMOUNT + reserve), ScriptBuilder.createP2SHOutputScript(Crypto.hash160(script)));
        return tx.getOutput(0);
    }
    @Test public void reserveCoversMaximumSignatureAndPreservesFullPayout() throws Exception {
        long reserve = BitcoinyHtlcFees.fundingReserve(coin, null);
        assertEquals(30_900L, reserve);
        TransactionOutput funding = funding(reserve);
        Transaction redeem = BitcoinyHtlcFees.buildRedeemTransaction(coin, Coin.valueOf(AMOUNT), seller,
                List.of(funding), script, secret, seller.getPubKeyHash());
        Transaction refund = BitcoinyHtlcFees.buildRefundTransaction(coin, Coin.valueOf(AMOUNT), buyer,
                List.of(funding), script, LOCK, buyer.getPubKeyHash());
        for (Transaction tx : List.of(redeem, refund)) {
            assertEquals(AMOUNT, tx.getOutput(0).getValue().value);
            assertTrue(reserve >= tx.bitcoinSerialize().length * 100L);
            assertEquals(funding.getOutPointFor(), tx.getInput(0).getOutpoint());
            tx.getInput(0).getScriptSig().correctlySpends(tx, 0, funding.getScriptPubKey(), Script.ALL_VERIFY_FLAGS);
        }
        assertEquals(309, BitcoinyHtlcFees.maximumSpendSize(params, funding, script, seller.getPubKey(), false));
    }
    @Test public void reportedHigherRelayMinimumAndConfiguredFloorAreBothHonored() throws Exception {
        when(provider.getMinimumRelayFeePerKb()).thenReturn(200_001L);
        assertEquals(61_801L, BitcoinyHtlcFees.fundingReserve(coin, null));
        when(coin.getP2shFee(any())).thenReturn(80_000L);
        assertEquals(80_000L, BitcoinyHtlcFees.fundingReserve(coin, null));
        assertEquals(80_000L, BitcoinyHtlcFees.requiredFee(coin, 309));
    }
    @Test public void ordinaryAndClientSignedPaymentsUseTheSameRelayFloor() throws Exception {
        when(provider.getMinimumRelayFeePerKb()).thenReturn(200_001L);
        assertEquals(200_001L, BitcoinyFees.spendRate(coin, null).value);
        assertThrows(ForeignBlockchainException.class, () -> BitcoinyFees.spendRate(coin, 100L));
        assertEquals(201_000L, BitcoinyFees.spendRate(coin, 201L).value);
        assertThrows(ForeignBlockchainException.class, () -> BitcoinyFees.spendRate(coin, Long.MAX_VALUE));
    }
    @Test(timeout=15000) @SuppressWarnings("unchecked")
    public void ordinaryAndMultiplePaymentBuildersPayTheReportedMinimum() throws Exception {
        Map<String, String> fixture = new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                getClass().getResourceAsStream("/crosschain/local-wallet/" + symbol + ".json"), Map.class);
        Transaction deposit = new Transaction(params, com.google.common.hash.HashCode.fromString(fixture.get("previous")).asBytes());
        TransactionOutput output = deposit.getOutput(0);
        String owner = output.getScriptPubKey().getToAddress(params).toString();
        when(provider.getAddressTransactions(any(byte[].class), anyBoolean())).thenAnswer(i ->
                Arrays.equals(output.getScriptBytes(), i.getArgument(0))
                        ? List.of(new TransactionHash(100, deposit.getTxId().toString())) : List.of());
        when(provider.getUnspentOutputs(anyString(), anyBoolean())).thenAnswer(i -> owner.equals(i.getArgument(0))
                ? List.of(new UnspentOutput(deposit.getTxId().getBytes(), 0, 100, output.getValue().value, output.getScriptBytes(), owner)) : List.of());
        when(provider.getRawTransaction(any(byte[].class))).thenReturn(deposit.bitcoinSerialize());
        when(provider.getMinimumRelayFeePerKb()).thenReturn(200_000L);
        when(provider.getCurrentHeight()).thenReturn(200);
        Bitcoiny walletCoin = new Bitcoiny(provider, new Context(params), symbol, Coin.valueOf(10_000)) {
            @Override public long getP2shFee(Long timestamp) { return 10_000; }
            @Override public long getFeeRequired() { return 10_000; }
            @Override public void setFeeRequired(long fee) { }
        };
        String recipient = LegacyAddress.fromPubKeyHash(params, seller.getPubKeyHash()).toString();
        for (Transaction tx : List.of(walletCoin.buildSpend(fixture.get("xprv"), recipient, 100_000_000L, null),
                walletCoin.buildSpendMultiple(fixture.get("xprv"), Map.of(recipient, 100_000_000L), null))) {
            assertEquals(1, tx.getInputs().size());
            long fee = output.getValue().value - tx.getOutputSum().value;
            assertTrue(symbol + " signed payment underpays", fee >= tx.bitcoinSerialize().length * 200L);
            tx.getInput(0).getScriptSig().correctlySpends(tx, 0, output.getScriptPubKey(), Script.ALL_VERIFY_FLAGS);
        }
        verify(provider, never()).broadcastTransaction(any(byte[].class));
    }
    @Test public void oldTenThousandReserveRequiresExplicitApproval() throws Exception {
        try {
            BitcoinyHtlcFees.buildRedeemTransaction(coin, Coin.valueOf(AMOUNT), seller,
                    List.of(funding(10_000)), script, secret, seller.getPubKeyHash());
            fail("Must not silently reduce the agreed payout");
        } catch (BitcoinyHtlcFees.ApprovalRequiredException expected) {
            assertEquals(10_000L, expected.reserve); assertEquals(30_900L, expected.requiredFee);
        }
    }
    @Test public void approvedPayoutCanCoverTheExtraFee() throws Exception {
        Transaction tx = BitcoinyHtlcFees.buildRedeemTransaction(coin, Coin.valueOf(AMOUNT - 20_900), seller,
                List.of(funding(10_000)), script, secret, seller.getPubKeyHash());
        assertEquals(AMOUNT - 20_900, tx.getOutput(0).getValue().value);
        assertTrue(30_900 >= tx.bitcoinSerialize().length * 100L);
    }
    @Test public void feeIncreaseDuringPreparationStopsBroadcastPreparation() throws Exception {
        when(provider.getMinimumRelayFeePerKb()).thenReturn(100_000L, 200_000L);
        assertThrows(ForeignBlockchainException.class, () -> BitcoinyHtlcFees.buildRedeemTransaction(coin,
                Coin.valueOf(AMOUNT), seller, List.of(funding(30_900)), script, secret, seller.getPubKeyHash()));
    }
    @Test public void unavailableRateAndOverflowFailClosed() throws Exception {
        when(provider.getMinimumRelayFeePerKb()).thenThrow(new ForeignBlockchainException("fixture unavailable"));
        assertThrows(ForeignBlockchainException.class, () -> BitcoinyHtlcFees.fundingReserve(coin, null));
        doReturn(Long.MAX_VALUE).when(provider).getMinimumRelayFeePerKb();
        assertThrows(ForeignBlockchainException.class, () -> BitcoinyHtlcFees.requiredFee(coin, 2000));
    }
    @Test public void duplicateUnrelatedAndAmbiguousDepositsAreNotCombined() throws Exception {
        TransactionOutput first = funding(40_000), other = funding(50_000);
        assertSame(first, BitcoinyHtlcFees.uniqueFunding(List.of(first, first), script, AMOUNT));
        assertThrows(ForeignBlockchainException.class, () -> BitcoinyHtlcFees.uniqueFunding(List.of(first, other), script, AMOUNT));
        Transaction unrelated = new Transaction(params);
        unrelated.addOutput(Coin.valueOf(AMOUNT), ScriptBuilder.createP2PKHOutputScript(buyer.getPubKeyHash()));
        assertThrows(ForeignBlockchainException.class, () -> BitcoinyHtlcFees.uniqueFunding(unrelated.getOutputs(), script, AMOUNT));
    }
}
