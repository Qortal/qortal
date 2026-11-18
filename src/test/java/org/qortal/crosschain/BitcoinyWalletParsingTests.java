package org.qortal.crosschain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.hash.HashCode;
import org.bitcoinj.core.*;
import org.bitcoinj.crypto.DeterministicKey;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.*;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.Mockito.*;

/** Synthetic Hub fixtures: exercise each coin's actual parameters without remote wallets. */
@RunWith(Parameterized.class)
public class BitcoinyWalletParsingTests extends org.qortal.test.common.Common {
    @Parameterized.Parameters
    public static Collection<Object[]> coins() {
        return List.of(new Object[] {"BTC", "000000000019d6689c085ae165831e93", Bitcoin.BitcoinNet.MAIN.getParams()},
                new Object[] {"LTC", "12a765e31ffd4059bada1e25190f6e98", Litecoin.LitecoinNet.MAIN.getParams()},
                new Object[] {"DOGE", "1a91e3dace36e2be3bf030a65679fe82", Dogecoin.DogecoinNet.MAIN.getParams()},
                new Object[] {"DGB", "7497ea1b465eb39f1c8f507bc877078f", Digibyte.DigibyteNet.MAIN.getParams()},
                new Object[] {"RVN", "0000006b444bc2f2ffe627be9d9e7e7a", Ravencoin.RavencoinNet.MAIN.getParams()});
    }

    private final String currency;
    private final String chain;
    private final NetworkParameters params;

    public BitcoinyWalletParsingTests(String currency, String genesis, NetworkParameters params) {
        this.currency = currency;
        this.chain = "bip122:" + genesis;
        this.params = params;
    }

    @Test public void legacyPreparationRetainsCoinPolicyAndCompletePreviousTransaction() throws Exception {
        Fixture f = new Fixture();
        Map<String, Object> context = f.prepare();
        assertContext(f, context, f.raw);
        assertEquals(currency, context.get("currencyCode"));
        assertEquals(chain, context.get("chainId"));
        assertEquals(Long.toString(LocalWalletSupport.minimumP2pkhOutput(currency)), context.get("minimumNonDustOutput"));
        assertEquals("LEGACY", context.get("transactionFormat"));
        verify(f.provider, times(1)).getUnspentOutputs(any(byte[].class), eq(false));
    }

    @Test public void legacyOutputLookupPreservesValueAndScript() throws Exception {
        Fixture f = new Fixture();
        assertOutputs(f, f.coin.getOutputs(f.funding.getTxId().getBytes()));
    }

    @Test public void witnessPreparationUsesTxidAndFullWitnessBytes() throws Exception {
        requireWitnessChain();
        Fixture f = new Fixture();
        byte[] raw = f.withWitness();
        int originalProtocol = params.getDefaultSerializer().getProtocolVersion();
        assertContext(f, f.prepare(), raw);
        assertEquals(originalProtocol, params.getDefaultSerializer().getProtocolVersion());
    }

    @Test public void witnessOutputLookupPreservesValueScriptAndNetworkDefaults() throws Exception {
        requireWitnessChain();
        Fixture f = new Fixture();
        f.withWitness();
        int originalProtocol = params.getDefaultSerializer().getProtocolVersion();
        assertOutputs(f, f.coin.getOutputs(f.funding.getTxId().getBytes()));
        assertEquals(originalProtocol, params.getDefaultSerializer().getProtocolVersion());
    }

    @Test public void previousTransactionWithTrailingBytesIsRejected() throws Exception {
        Fixture f = new Fixture();
        byte[] extra = Arrays.copyOf(f.raw, f.raw.length + 1);
        extra[extra.length - 1] = 1;
        when(f.provider.getRawTransaction(any(byte[].class))).thenReturn(extra);
        try { f.prepare(); fail("Trailing data must not enter a signing context"); }
        catch (ForeignBlockchainException expected) { }
    }

    @Test public void outputLookupRejectsDifferentTransaction() throws Exception {
        Fixture f = new Fixture();
        try { f.coin.getOutputs(new byte[32]); fail("Provider bytes must match the requested transaction ID"); }
        catch (ForeignBlockchainException expected) { }
    }

    @Test public void preparationRejectsWrongHashValueScriptAndIndex() throws Exception {
        for (String mismatch : List.of("hash", "value", "script", "index")) {
            Fixture f = new Fixture();
            Transaction funding = new Transaction(params, f.raw);
            if (mismatch.equals("script")) {
                funding.clearOutputs();
                funding.addOutput(Coin.valueOf(1000000000), new org.bitcoinj.script.Script(new byte[] {0x51}));
                when(f.provider.getRawTransaction(any(byte[].class))).thenReturn(funding.bitcoinSerialize());
            }
            when(f.provider.getUnspentOutputs(any(byte[].class), eq(false))).thenReturn(List.of(new UnspentOutput(
                    mismatch.equals("hash") ? new byte[32] : funding.getTxId().getBytes(),
                    mismatch.equals("index") ? 100 : 0, 100,
                    mismatch.equals("value") ? 1 : funding.getOutput(0).getValue().value)));
            try { f.prepare(); fail("Accepted previous output mismatch: " + mismatch); }
            catch (ForeignBlockchainException expected) { }
        }
    }

    @Test public void broadcastForwardsExactHubSignedBytesAndExpectedId() throws Exception {
        Fixture f = new Fixture();
        assertEquals(f.data.get("txId"), LocalWalletSupport.broadcast(f.coin, f.data.get("raw"), chain));
        verify(f.provider).broadcastTransaction(aryEq(HashCode.fromString(f.data.get("raw")).asBytes()), eq(f.data.get("txId")));
    }

    @Test public void legacyHtlcSecretExtractionRemainsCompatible() throws Exception {
        checkHtlcSecret(false);
    }

    @Test public void htlcSecretExtractionAcceptsAnUnrelatedWitnessInput() throws Exception {
        requireWitnessChain();
        checkHtlcSecret(true);
    }

    private void checkHtlcSecret(boolean withWitness) throws Exception {
        Fixture f = new Fixture();
        byte[] secret = new byte[32];
        Arrays.fill(secret, (byte) (currency.hashCode() + (withWitness ? 1 : 0)));
        byte[] redeemScript = BitcoinyHTLC.buildScript(new byte[20], 1800000000,
                new byte[20], org.qortal.crypto.Crypto.hash160(secret));
        String address = LegacyAddress.fromScriptHash(params, org.qortal.crypto.Crypto.hash160(redeemScript)).toString();
        org.bitcoinj.script.Script scriptSig = new org.bitcoinj.script.ScriptBuilder()
                .data(secret).data(new byte[71]).data(new byte[33]).data(redeemScript).build();
        Transaction redeem = new Transaction(params);
        redeem.addInput(Sha256Hash.of(new byte[] {1}), 0, scriptSig);
        redeem.addOutput(Coin.valueOf(100000), f.funding.getOutput(0).getScriptPubKey());
        if (withWitness) {
            redeem.addInput(Sha256Hash.of(new byte[] {2}), 0, new org.bitcoinj.script.Script(new byte[0]));
            var serializer = params.getSerializer(false).withProtocolVersion(
                    NetworkParameters.ProtocolVersion.WITNESS_VERSION.getBitcoinProtocolVersion());
            redeem = serializer.makeTransaction(redeem.bitcoinSerialize());
            TransactionWitness witness = new TransactionWitness(2);
            witness.setPush(0, new byte[71]);
            witness.setPush(1, new byte[33]);
            redeem.getInput(1).setWitness(witness);
        }
        when(f.provider.getAddressTransactions(any(byte[].class), eq(false))).thenReturn(
                List.of(new TransactionHash(100, redeem.getTxId().toString())));
        when(f.provider.getRawTransaction(any(byte[].class))).thenReturn(redeem.bitcoinSerialize());
        assertArrayEquals(secret, BitcoinyHTLC.findHtlcSecret(f.coin, address));
    }

    private void requireWitnessChain() {
        // DOGE and RVN keep their existing legacy chain formats.
        assumeTrue(Set.of("BTC", "LTC", "DGB").contains(currency));
    }

    private void assertContext(Fixture f, Map<String, Object> context, byte[] raw) {
        List<?> outputs = (List<?>) context.get("utxos");
        assertEquals(1, outputs.size());
        Map<?, ?> output = (Map<?, ?>) outputs.get(0);
        assertEquals(f.funding.getTxId().toString(), output.get("txHash"));
        assertEquals("1000000000", output.get("value"));
        assertEquals(HashCode.fromBytes(f.funding.getOutput(0).getScriptBytes()).toString(), output.get("scriptPubKeyHex"));
        assertEquals(HashCode.fromBytes(raw).toString(), ((Map<?, ?>) context.get("previousTransactions")).get(output.get("txHash")));
    }

    private void assertOutputs(Fixture f, List<TransactionOutput> outputs) {
        assertEquals(f.funding.getOutputs().size(), outputs.size());
        for (int i = 0; i < outputs.size(); i++) {
            assertEquals(f.funding.getOutput(i).getValue(), outputs.get(i).getValue());
            assertArrayEquals(f.funding.getOutput(i).getScriptBytes(), outputs.get(i).getScriptBytes());
        }
    }

    private class Fixture {
        final BitcoinyBlockchainProvider provider = mock(BitcoinyBlockchainProvider.class);
        final Map<String, String> data;
        final byte[] raw;
        final Transaction funding;
        final Bitcoiny coin;
        final String xpub;

        @SuppressWarnings("unchecked")
        Fixture() throws Exception {
            data = new ObjectMapper().readValue(getClass().getResourceAsStream("/crosschain/local-wallet/" + currency + ".json"), Map.class);
            Context.propagate(new Context(params));
            raw = HashCode.fromString(data.get("previous")).asBytes();
            funding = new Transaction(params, raw);
            xpub = DeterministicKey.deserializeB58(data.get("xprv"), params).serializePubB58(params);
            coin = new Bitcoiny(provider, new Context(params), currency, Coin.valueOf(10000)) {
                @Override public long getP2shFee(Long timestamp) { return 1000; }
                @Override public long getFeeRequired() { return 1000; }
                @Override public void setFeeRequired(long fee) { }
            };
            when(provider.getNetId()).thenReturn(currency + "-MAIN");
            when(provider.getCurrentHeight()).thenReturn(200);
            when(provider.getAddressTransactions(any(byte[].class), eq(true))).thenAnswer(call ->
                    Arrays.equals(call.getArgument(0), funding.getOutput(0).getScriptBytes())
                            ? List.of(new TransactionHash(100, funding.getTxId().toString())) : List.of());
            when(provider.getUnspentOutputs(any(byte[].class), eq(false))).thenReturn(
                    List.of(new UnspentOutput(funding.getTxId().getBytes(), 0, 100, funding.getOutput(0).getValue().value)));
            when(provider.getRawTransaction(any(byte[].class))).thenReturn(raw);
        }

        Map<String, Object> prepare() throws ForeignBlockchainException {
            return LocalWalletSupport.spendContext(coin, xpub, chain);
        }

        byte[] withWitness() throws Exception {
            var serializer = params.getSerializer(false).withProtocolVersion(
                    Math.max(params.getDefaultSerializer().getProtocolVersion(),
                            NetworkParameters.ProtocolVersion.WITNESS_VERSION.getBitcoinProtocolVersion()));
            Transaction tx = serializer.makeTransaction(raw);
            TransactionWitness witness = new TransactionWitness(2);
            witness.setPush(0, new byte[] {1, 2, 3});
            witness.setPush(1, new byte[33]);
            tx.getInput(0).setWitness(witness);
            assertFalse(tx.getTxId().equals(tx.getWTxId()));
            assertEquals(funding.getTxId(), tx.getTxId());
            byte[] bytes = tx.bitcoinSerialize();
            when(provider.getRawTransaction(any(byte[].class))).thenReturn(bytes);
            return bytes;
        }
    }
}
