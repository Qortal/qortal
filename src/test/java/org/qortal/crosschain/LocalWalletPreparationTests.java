package org.qortal.crosschain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.hash.HashCode;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.Context;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionWitness;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.crypto.DeterministicKey;
import org.junit.Test;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Preparation failures remain fail-closed and produce diagnostics without wallet data. */
public class LocalWalletPreparationTests extends org.qortal.test.common.Common {
    private static final String CHAIN = "bip122:12a765e31ffd4059bada1e25190f6e98";

    @Test public void providerFailureIsLoggedWithoutWalletData() throws Exception {
        Fixture fixture = new Fixture();
        String privatePayload = fixture.xpub + " " + fixture.fixture.get("xprv") + " apiKey=secret-marker";
        var failure = new ForeignBlockchainException.NetworkException(17, privatePayload);
        when(fixture.provider.getAddressTransactions(any(byte[].class), eq(true))).thenThrow(failure);
        try (Capture logs = new Capture()) {
            try { LocalWalletSupport.spendContext(fixture.coin, fixture.xpub, CHAIN); fail(); }
            catch (ForeignBlockchainException actual) { assertSame(failure, actual); }
            String message = logs.onlyMessage();
            assertTrue(message, message.contains("coin=LTC stage=ADDRESS_HISTORY reason=ELECTRUM_NETWORK"));
            assertTrue(message, message.contains("daemonCode=17"));
            assertTrue(message, message.contains("addresses=1 outputs=0"));
            assertFalse(message.contains(fixture.xpub));
            assertFalse(message.contains(fixture.fixture.get("xprv")));
            assertFalse(message.contains("secret-marker"));
            assertNull("Never attach the original provider payload as a throwable", logs.events.get(0).getThrown());
        }
        verify(fixture.provider, never()).getUnspentOutputs(any(byte[].class), anyBoolean());
    }

    @Test public void previousOutputMismatchIsLoggedAsValidationFailure() throws Exception {
        Fixture fixture = new Fixture();
        when(fixture.provider.getUnspentOutputs(any(byte[].class), eq(false))).thenReturn(
                List.of(new UnspentOutput(fixture.funding.getTxId().getBytes(), 0, 100, 1)));
        try (Capture logs = new Capture()) {
            try { LocalWalletSupport.spendContext(fixture.coin, fixture.xpub, CHAIN); fail(); }
            catch (ForeignBlockchainException expected) { assertEquals("Previous output mismatch", expected.getMessage()); }
            String message = logs.onlyMessage();
            assertTrue(message, message.contains("stage=PREVIOUS_OUTPUT_VALIDATION reason=PREVIOUS_OUTPUT_VALUE_MISMATCH"));
            assertFalse(message.contains(fixture.funding.getTxId().toString()));
            assertFalse(message.contains(fixture.fixture.get("previous")));
        }
    }

    @Test public void providerReadStagesRemainDistinct() throws Exception {
        for (String stage : List.of("UNSPENT_OUTPUTS", "PREVIOUS_TRANSACTION", "CHAIN_HEIGHT")) {
            Fixture fixture = new Fixture();
            var failure = new ForeignBlockchainException.NetworkException("untrusted-provider-payload");
            if (stage.equals("UNSPENT_OUTPUTS"))
                when(fixture.provider.getUnspentOutputs(any(byte[].class), eq(false))).thenThrow(failure);
            else if (stage.equals("PREVIOUS_TRANSACTION"))
                when(fixture.provider.getRawTransaction(any(byte[].class))).thenThrow(failure);
            else when(fixture.provider.getCurrentHeight()).thenThrow(failure);
            try (Capture logs = new Capture()) {
                try { LocalWalletSupport.spendContext(fixture.coin, fixture.xpub, CHAIN); fail(); }
                catch (ForeignBlockchainException actual) { assertSame(failure, actual); }
                String message = logs.onlyMessage();
                assertTrue(message, message.contains("stage=" + stage + " reason=ELECTRUM_NETWORK"));
                assertFalse(message.contains("untrusted-provider-payload"));
            }
        }
    }

    @Test public void discoveryDeadlineIsIdentifiedWithoutWaitingOrReturningPartialData() throws Exception {
        Fixture fixture = new Fixture();
        AtomicLong time = new AtomicLong();
        when(fixture.provider.getAddressTransactions(any(byte[].class), eq(true))).thenAnswer(call -> {
            time.set(java.util.concurrent.TimeUnit.SECONDS.toNanos(61));
            return List.of();
        });
        try (Capture logs = new Capture()) {
            try { timedContext(fixture, time::get); fail("A timed-out scan must not return partial data"); }
            catch (ForeignBlockchainException expected) { assertEquals("Wallet discovery limit reached", expected.getMessage()); }
            String message = logs.onlyMessage();
            assertTrue(message, message.contains("reason=DISCOVERY_TIME_LIMIT"));
            assertTrue(message, message.contains("addresses=1 outputs=0"));
            assertTrue(message, message.contains("elapsedMs=61000"));
        }
        verify(fixture.provider, times(1)).getAddressTransactions(any(byte[].class), eq(true));
    }

    @Test public void successfulPreparationDoesNotEmitFailureDiagnostics() throws Exception {
        Fixture fixture = new Fixture();
        try (Capture logs = new Capture()) {
            Map<String, Object> context = LocalWalletSupport.spendContext(fixture.coin, fixture.xpub, CHAIN);
            assertEquals(1, ((List<?>) context.get("utxos")).size());
            assertTrue(logs.events.isEmpty());
        }
    }

    @Test public void witnessFundingKeepsItsTransactionIdAndCompletePreviousBytes() throws Exception {
        Fixture fixture = new Fixture();
        int originalProtocol = fixture.coin.getNetworkParameters().getDefaultSerializer().getProtocolVersion();
        byte[] raw = witnessFunding(fixture);
        when(fixture.provider.getRawTransaction(any(byte[].class))).thenReturn(raw);
        Map<String, Object> context = LocalWalletSupport.spendContext(fixture.coin, fixture.xpub, CHAIN);
        List<?> outputs = (List<?>) context.get("utxos");
        assertEquals(1, outputs.size());
        assertEquals(fixture.funding.getTxId().toString(), ((Map<?, ?>) outputs.get(0)).get("txHash"));
        assertEquals(HashCode.fromBytes(raw).toString(),
                ((Map<?, ?>) context.get("previousTransactions")).get(fixture.funding.getTxId().toString()));
        assertEquals("Raw parsing must not change network-wide protocol settings", originalProtocol,
                fixture.coin.getNetworkParameters().getDefaultSerializer().getProtocolVersion());
    }

    @Test public void witnessFundingStillRejectsAnIncorrectAdvertisedHash() throws Exception {
        Fixture fixture = new Fixture();
        when(fixture.provider.getRawTransaction(any(byte[].class))).thenReturn(witnessFunding(fixture));
        when(fixture.provider.getUnspentOutputs(any(byte[].class), eq(false))).thenReturn(
                List.of(new UnspentOutput(new byte[32], 0, 100, 1000000000)));
        try {
            LocalWalletSupport.spendContext(fixture.coin, fixture.xpub, CHAIN);
            fail("Witness parsing must never bypass the advertised transaction hash");
        } catch (ForeignBlockchainException expected) {
            assertEquals("Previous transaction hash mismatch", expected.getMessage());
        }
    }

    private byte[] witnessFunding(Fixture fixture) {
        // Synthetic witness only; no user transaction, address or wallet data.
        var serializer = fixture.coin.getNetworkParameters().getSerializer(false)
                .withProtocolVersion(NetworkParameters.ProtocolVersion.WITNESS_VERSION.getBitcoinProtocolVersion());
        Transaction transaction = serializer.makeTransaction(fixture.funding.bitcoinSerialize());
        TransactionWitness witness = new TransactionWitness(2);
        witness.setPush(0, new byte[] { 1, 2, 3 });
        witness.setPush(1, new byte[33]);
        transaction.getInput(0).setWitness(witness);
        assertTrue(transaction.hasWitnesses());
        assertEquals(fixture.funding.getTxId(), transaction.getTxId());
        return transaction.bitcoinSerialize();
    }

    @Test public void malformedProviderPayloadIsLoggedWithoutChangingTheException() throws Exception {
        Fixture fixture = new Fixture();
        var failure = new IllegalArgumentException("private-provider-payload");
        when(fixture.provider.getAddressTransactions(any(byte[].class), eq(true))).thenThrow(failure);
        try (Capture logs = new Capture()) {
            try { LocalWalletSupport.spendContext(fixture.coin, fixture.xpub, CHAIN); fail(); }
            catch (IllegalArgumentException actual) { assertSame(failure, actual); }
            String message = logs.onlyMessage();
            assertTrue(message, message.contains("stage=ADDRESS_HISTORY reason=INVALID_RESPONSE"));
            assertFalse(message.contains("private-provider-payload"));
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> timedContext(Fixture fixture, LongSupplier clock) throws Exception {
        var method = LocalWalletSupport.class.getDeclaredMethod("spendContext", Bitcoiny.class, String.class, String.class, LongSupplier.class);
        method.setAccessible(true);
        try { return (Map<String, Object>) method.invoke(null, fixture.coin, fixture.xpub, CHAIN, clock); }
        catch (InvocationTargetException e) { throw (Exception) e.getCause(); }
    }

    private static class Fixture {
        final Map<String, String> fixture;
        final BitcoinyBlockchainProvider provider = mock(BitcoinyBlockchainProvider.class);
        final Bitcoiny coin;
        final Transaction funding;
        final String xpub;

        @SuppressWarnings("unchecked")
        Fixture() throws Exception {
            fixture = new ObjectMapper().readValue(getClass().getResourceAsStream("/crosschain/local-wallet/LTC.json"), Map.class);
            var params = Litecoin.LitecoinNet.MAIN.getParams();
            Context.propagate(new Context(params));
            funding = new Transaction(params, HashCode.fromString(fixture.get("previous")).asBytes());
            xpub = DeterministicKey.deserializeB58(fixture.get("xprv"), params).serializePubB58(params);
            coin = new Bitcoiny(provider, new Context(params), "LTC", Coin.valueOf(10000)) {
                @Override public long getP2shFee(Long timestamp) { return 1000; }
                @Override public long getFeeRequired() { return 1000; }
                @Override public void setFeeRequired(long fee) { }
            };
            when(provider.getNetId()).thenReturn("Litecoin-MAIN");
            when(provider.getCurrentHeight()).thenReturn(200);
            when(provider.getAddressTransactions(any(byte[].class), eq(true))).thenAnswer(call ->
                    Arrays.equals(call.getArgument(0), funding.getOutput(0).getScriptBytes())
                            ? List.of(new TransactionHash(100, funding.getTxId().toString())) : List.of());
            when(provider.getUnspentOutputs(any(byte[].class), eq(false))).thenReturn(
                    List.of(new UnspentOutput(funding.getTxId().getBytes(), 0, 100, 1000000000)));
            when(provider.getRawTransaction(any(byte[].class))).thenReturn(funding.bitcoinSerialize());
        }
    }

    private static class Capture implements AutoCloseable {
        final Logger logger = (Logger) LogManager.getLogger(LocalWalletSupport.class);
        final Level original = logger.getLevel();
        final List<LogEvent> events = new ArrayList<>();
        final AbstractAppender appender = new AbstractAppender("local-wallet-preparation-test", null, null, false, null) {
            @Override public void append(LogEvent event) { events.add(event.toImmutable()); }
        };
        Capture() {
            appender.start();
            logger.addAppender(appender);
            logger.setLevel(Level.WARN);
        }
        String onlyMessage() {
            assertEquals("Exactly one diagnostic per failed preparation", 1, events.size());
            return events.get(0).getMessage().getFormattedMessage();
        }
        @Override public void close() {
            logger.removeAppender(appender);
            logger.setLevel(original);
            appender.stop();
        }
    }
}
