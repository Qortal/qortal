package org.qortal.crosschain;

import org.bitcoinj.core.*;
import org.bitcoinj.crypto.DeterministicKey;
import org.bitcoinj.crypto.HDKeyDerivation;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;
import org.junit.Test;
import org.qortal.crypto.Crypto;
import org.qortal.test.common.Common;

import java.lang.reflect.Constructor;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.Assert.*;
import static org.qortal.crosschain.BitcoinCloneDefinitionTests.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class BitcoinCloneWalletTests extends Common {
    @Test public void existingBitcoinAndLitecoinWalletsKeepKnownAddressesAcrossAllNetworks() throws Exception {
        for (Bitcoin.BitcoinNet network : Bitcoin.BitcoinNet.values()) checkAddress(Bitcoin.definitionFor(network),
                network == Bitcoin.BitcoinNet.MAIN ? "12CL4K2eVqj7hQTix7dM7CVHCkpP17Pry3" : "mgiHMN7dJsANUWwLfgbiw7hc4kR5xMjPhw");
        for (Litecoin.LitecoinNet network : Litecoin.LitecoinNet.values()) checkAddress(Litecoin.definitionFor(network),
                network == Litecoin.LitecoinNet.MAIN ? "LLRHKXLUaVyAxD9t8FcePDZ3QyBf7E4gu5" : "mgiHMN7dJsANUWwLfgbiw7hc4kR5xMjPhw");
    }

    private void checkAddress(BitcoinCloneDefinition definition, String expected) throws Exception {
        Fixture fixture = new Fixture(definition);
        // Independently derived from BIP32 vector-one seed 000102...0f at m/0/0.
        assertEquals(expected, fixture.coin.getUnusedReceiveAddress(fixture.xpub));
        assertEquals(expected, fixture.coin.getUnusedReceiveAddress(fixture.xprv));
        assertSame(definition.parameters(), fixture.coin.getNetworkParameters());
        assertEquals(definition.feePerKb, fixture.coin.getFeePerKb().value);
        assertEquals(definition.minimumOrderAmount, fixture.coin.getMinimumOrderAmount());
        assertEquals(fixture.baseline.getSpendingCandidateAddresses(fixture.xpub), fixture.coin.getSpendingCandidateAddresses(fixture.xpub));
        verify(fixture.provider).setBlockchain(fixture.coin);
    }

    @Test public void migratedCoinsAndConfigOnlyFixtureBuildAndSignRealTransactions() throws Exception {
        List<BitcoinCloneDefinition> definitions = new ArrayList<>();
        for (Bitcoin.BitcoinNet network : Bitcoin.BitcoinNet.values()) definitions.add(Bitcoin.definitionFor(network));
        for (Litecoin.LitecoinNet network : Litecoin.LitecoinNet.values()) definitions.add(Litecoin.definitionFor(network));
        definitions.add(BitcoinCloneDefinitionTests.load(BitcoinCloneDefinitionTests.fixture()));
        for (BitcoinCloneDefinition definition : definitions) {
            Fixture fixture = new Fixture(definition);
            Transaction actual = fixture.coin.buildSpend(fixture.xprv, fixture.recipient, 500000, null);
            Transaction baseline = fixture.baseline.buildSpend(fixture.xprv, fixture.recipient, 500000, null);
            assertNotNull(definition.key(), actual);
            actual.verify();
            assertEquals(1, actual.getVersion());
            assertEquals(0, actual.getLockTime());
            assertEquals(baseline.getInput(0).getSequenceNumber(), actual.getInput(0).getSequenceNumber());
            assertEquals(baseline.getFee(), actual.getFee());
            assertEquals(outputs(baseline), outputs(actual));
            actual.getInput(0).getScriptSig().correctlySpends(actual, 0,
                    fixture.funding.getOutput(0).getScriptPubKey(), Script.ALL_VERIFY_FLAGS);
            assertArrayEquals(actual.bitcoinSerialize(), fixture.coin.deserializeTransaction(actual.bitcoinSerialize()).bitcoinSerialize());
            fixture.coin.broadcastTransaction(actual);
            verify(fixture.provider).broadcastTransaction(actual.bitcoinSerialize());
            assertEquals(1000000L, fixture.coin.getWalletBalance(fixture.xpub).longValue());
            assertEquals(1, fixture.coin.getUnspentOutputs(fixture.ownedAddress, false).size());
        }
    }

    @Test public void configuredReadOnlyChainCannotSignOrBroadcastOrBuildHtlc() throws Exception {
        var root = BitcoinCloneDefinitionTests.fixture();
        ((com.fasterxml.jackson.databind.node.ObjectNode) root.get("chains").get(0)).putArray("capabilities").add("ADDRESS").add("BALANCE");
        Fixture fixture = new Fixture(BitcoinCloneDefinitionTests.load(root));
        assertThrows(IllegalStateException.class, () -> fixture.coin.buildSpend(fixture.xprv, fixture.recipient, 1000));
        assertThrows(IllegalStateException.class, () -> fixture.coin.buildSpendMultiple(fixture.xprv, Map.of(fixture.recipient, 1000L), null));
        assertThrows(IllegalStateException.class, () -> fixture.coin.broadcastTransaction(fixture.funding));
        assertThrows(IllegalStateException.class, () -> BitcoinCloneHtlc.buildScript(fixture.coin.getDefinition(), new byte[20], 1800000000, new byte[20], new byte[20]));
        verify(fixture.provider, never()).broadcastTransaction(any(byte[].class));
    }

    @Test public void existingHtlcScriptsAndRefundRedeemSigningRemainValid() throws Exception {
        for (BitcoinCloneDefinition definition : List.of(Bitcoin.definitionFor(Bitcoin.BitcoinNet.MAIN), Litecoin.definitionFor(Litecoin.LitecoinNet.MAIN))) {
            Fixture fixture = new Fixture(definition);
            ECKey refund = ECKey.fromPrivate(java.math.BigInteger.valueOf(3)), redeem = ECKey.fromPrivate(java.math.BigInteger.valueOf(4));
            byte[] secret = new byte[32]; Arrays.fill(secret, (byte) 42);
            int lockTime = 1800000000;
            byte[] script = BitcoinCloneHtlc.buildScript(definition, refund.getPubKeyHash(), lockTime, redeem.getPubKeyHash(), Crypto.hash160(secret));
            assertArrayEquals(BitcoinyHTLC.buildScript(refund.getPubKeyHash(), lockTime, redeem.getPubKeyHash(), Crypto.hash160(secret)), script);
            Transaction funding = new Transaction(definition.parameters());
            funding.addOutput(Coin.valueOf(100000), ScriptBuilder.createP2SHOutputScript(Crypto.hash160(script)));
            Transaction refundTx = BitcoinyHTLC.buildRefundTransaction(definition.parameters(), Coin.valueOf(99000), refund,
                    funding.getOutputs(), script, lockTime, refund.getPubKeyHash());
            Transaction redeemTx = BitcoinyHTLC.buildRedeemTransaction(definition.parameters(), Coin.valueOf(99000), redeem,
                    funding.getOutputs(), script, secret, redeem.getPubKeyHash());
            assertEquals(lockTime, refundTx.getLockTime());
            assertEquals(BitcoinyHTLC.LOCKTIME_NO_RBF_SEQUENCE, refundTx.getInput(0).getSequenceNumber());
            assertEquals(BitcoinyHTLC.NO_LOCKTIME_NO_RBF_SEQUENCE, redeemTx.getInput(0).getSequenceNumber());
            refundTx.getInput(0).getScriptSig().correctlySpends(refundTx, 0, funding.getOutput(0).getScriptPubKey(), Script.ALL_VERIFY_FLAGS);
            redeemTx.getInput(0).getScriptSig().correctlySpends(redeemTx, 0, funding.getOutput(0).getScriptPubKey(), Script.ALL_VERIFY_FLAGS);
        }
    }

    @Test public void bitcoinDefaultSpendFeeAndLitecoinAddressConversionArePreserved() throws Exception {
        Fixture bitcoin = new Fixture(Bitcoin.definitionFor(Bitcoin.BitcoinNet.MAIN));
        assertEquals(bitcoin.coin.buildSpend(bitcoin.xprv, bitcoin.recipient, 500000, 20L).getFee(),
                bitcoin.coin.buildSpend(bitcoin.xprv, bitcoin.recipient, 500000).getFee());
        Fixture litecoin = new Fixture(Litecoin.definitionFor(Litecoin.LitecoinNet.MAIN));
        Litecoin ltc = (Litecoin) litecoin.coin;
        String current = LegacyAddress.fromScriptHash(Litecoin.MAIN_NET_PARAMS_P2SH_OVERRIDE, new byte[20]).toString();
        assertTrue(ltc.isCurrentP2ShAddress(current));
        assertEquals(LegacyAddress.fromScriptHash(ltc.getNetworkParameters(), new byte[20]).toString(), ltc.convertCurrentP2ShAddress(current));
    }

    private static Map<String, Long> outputs(Transaction tx) {
        return tx.getOutputs().stream().collect(Collectors.toMap(o -> Utils.HEX.encode(o.getScriptBytes()), o -> o.getValue().value));
    }

    private static class Fixture {
        final BitcoinyBlockchainProvider provider = mock(BitcoinyBlockchainProvider.class);
        final BitcoinClone coin;
        final Bitcoiny baseline;
        final Transaction funding;
        final String xpub, xprv, ownedAddress, recipient;

        Fixture(BitcoinCloneDefinition definition) throws Exception {
            NetworkParameters params = definition.parameters();
            Context context = new Context(params); Context.propagate(context);
            if (definition.isBuiltIn()) {
                Class<? extends BitcoinClone> type = definition.id.equals("bitcoin") ? Bitcoin.class : Litecoin.class;
                Object network = definition.id.equals("bitcoin") ? Bitcoin.BitcoinNet.valueOf(oldNetwork(definition.network))
                        : Litecoin.LitecoinNet.valueOf(oldNetwork(definition.network));
                Constructor<? extends BitcoinClone> constructor = type.getDeclaredConstructor(
                        definition.id.equals("bitcoin") ? Bitcoin.BitcoinNet.class : Litecoin.LitecoinNet.class,
                        BitcoinyBlockchainProvider.class, Context.class, String.class);
                constructor.setAccessible(true);
                coin = constructor.newInstance(network, provider, context, definition.symbol);
            } else coin = new BitcoinClone(definition, provider, context);
            baseline = new Bitcoiny(provider, context, definition.symbol, Coin.valueOf(definition.feePerKb)) {
                @Override public long getP2shFee(Long timestamp) { return definition.p2shFee; }
                @Override public long getFeeRequired() { return definition.p2shFee; }
                @Override public void setFeeRequired(long fee) { }
            };
            DeterministicKey master = HDKeyDerivation.createMasterPrivateKey(Utils.HEX.decode("000102030405060708090a0b0c0d0e0f"));
            xpub = master.serializePubB58(params); xprv = master.serializePrivB58(params);
            ownedAddress = baseline.walletFromDeterministicKey58(xpub).currentReceiveAddress().toString();
            recipient = LegacyAddress.fromKey(params, ECKey.fromPrivate(java.math.BigInteger.TEN)).toString();
            funding = new Transaction(params);
            funding.addInput(Sha256Hash.of(new byte[]{1}), 0, new Script(new byte[0]));
            funding.addOutput(Coin.valueOf(1000000), org.bitcoinj.core.Address.fromString(params, ownedAddress));
            UnspentOutput utxo = new UnspentOutput(funding.getTxId().getBytes(), 0, 100, 1000000,
                    funding.getOutput(0).getScriptBytes(), ownedAddress);
            when(provider.getCurrentHeight()).thenReturn(200);
            when(provider.getAddressTransactions(any(byte[].class), anyBoolean())).thenReturn(List.of());
            when(provider.getUnspentOutputs(anyString(), anyBoolean())).thenAnswer(call -> ownedAddress.equals(call.getArgument(0)) ? List.of(utxo) : List.of());
            when(provider.getUnspentOutputs(any(byte[].class), anyBoolean())).thenAnswer(call -> Arrays.equals(funding.getOutput(0).getScriptBytes(), call.getArgument(0)) ? List.of(utxo) : List.of());
            when(provider.getRawTransaction(any(byte[].class))).thenReturn(funding.bitcoinSerialize());
        }

        static String oldNetwork(BitcoinCloneDefinition.Network network) {
            return network == BitcoinCloneDefinition.Network.MAINNET ? "MAIN" : network == BitcoinCloneDefinition.Network.TESTNET ? "TEST3" : "REGTEST";
        }
    }
}
