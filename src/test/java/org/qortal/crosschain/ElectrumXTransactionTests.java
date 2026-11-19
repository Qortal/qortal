package org.qortal.crosschain;

import com.google.common.hash.HashCode;
import org.bitcoinj.core.*;
import org.bitcoinj.script.Script;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Actual verbose/raw RPC parsing on loopback, using each supported coin's decoder. */
@RunWith(Parameterized.class)
public class ElectrumXTransactionTests extends org.qortal.test.common.Common {
    @Parameterized.Parameters
    public static Collection<Object[]> coins() {
        return List.of(new Object[]{"BTC", Bitcoin.BitcoinNet.MAIN.getParams()},
                new Object[]{"LTC", Litecoin.LitecoinNet.MAIN.getParams()},
                new Object[]{"DOGE", Dogecoin.DogecoinNet.MAIN.getParams()},
                new Object[]{"DGB", Digibyte.DigibyteNet.MAIN.getParams()},
                new Object[]{"RVN", Ravencoin.RavencoinNet.MAIN.getParams()});
    }

    private final String currency;
    private final NetworkParameters params;

    public ElectrumXTransactionTests(String currency, NetworkParameters params) {
        this.currency = currency;
        this.params = params;
    }

    @Test(timeout = 15000)
    public void fractionalArtifactUsesEmbeddedWireAmountsWithoutAnotherRequest() throws Exception {
        try (Fixture f = new Fixture(10001L, 7000L)) {
            f.output(0).put("value", 0.00010001000000000001);
            // Recover every output from the same wire transaction.
            f.output(1).put("value", 0.000000005);
            f.verbose.put("hex", f.raw);
            assertValues(f, 10001L, 7000L);
            assertEquals(1, f.requests.get());
            assertValues(f, 10001L, 7000L);
            assertEquals("Confirmed result should be cached", 1, f.requests.get());
        }
    }

    @Test(timeout = 15000)
    public void missingEmbeddedHexFetchesRawTransactionOnce() throws Exception {
        try (Fixture f = new Fixture(10001L, 7000L)) {
            f.output(0).put("value", 0.00010001000000000001);
            f.output(1).put("value", 0.000000005);
            assertValues(f, 10001L, 7000L);
            assertEquals(2, f.requests.get());
        }
    }

    @Test(timeout = 15000)
    public void exactIntegerZeroScientificAndDecimalRepliesNeedNoRawDecoder() throws Exception {
        try (Fixture f = new Fixture(100000000L, 0L, 1L, 123456789L)) {
            f.output(0).put("value", 1L);
            f.output(1).put("value", 0L);
            f.output(2).put("value", 1e-8);
            f.output(3).put("value", 1.23456789);
            f.raw = null;
            assertValues(f, 100000000L, 0L, 1L, 123456789L);
            assertEquals(1, f.requests.get());
        }
    }

    @Test(timeout = 15000)
    public void largeAltcoinOutputUsesWireAmountWhenDoubleLosesAtomicUnits() throws Exception {
        assumeTrue(!currency.equals("BTC")); // 75 million coins is below LTC's supply, above BTC's.
        long amount = 75000000L * 100000000L + 23L;
        try (Fixture f = new Fixture(amount)) {
            f.output(0).put("value", amount / 100000000.0);
            assertValues(f, amount);
            assertEquals(2, f.requests.get());
        }
    }

    @Test(timeout = 15000)
    public void witnessRecoveryRetainsTxidAndExactAmounts() throws Exception {
        assumeTrue(Set.of("BTC", "LTC", "DGB").contains(currency));
        try (Fixture f = new Fixture(10001L)) {
            var serializer = params.getSerializer(false).withProtocolVersion(
                    Math.max(params.getDefaultSerializer().getProtocolVersion(),
                            NetworkParameters.ProtocolVersion.WITNESS_VERSION.getBitcoinProtocolVersion()));
            Transaction witness = serializer.makeTransaction(HashCode.fromString(f.raw).asBytes());
            TransactionWitness data = new TransactionWitness(1);
            data.setPush(0, new byte[]{1, 2, 3});
            witness.getInput(0).setWitness(data);
            f.raw = HashCode.fromBytes(witness.bitcoinSerialize()).toString();
            assertEquals(f.txid, witness.getTxId().toString());
            f.output(0).put("value", 0.00010001000000000001);
            assertValues(f, 10001L);
        }
    }

    @Test(timeout = 15000)
    public void mismatchedRawIdentityScriptCountAndTrailingDataAreRejected() throws Exception {
        for (String mismatch : List.of("identity", "script", "count", "trailing", "malformed")) {
            try (Fixture f = new Fixture(10001L)) {
                f.output(0).put("value", 0.00010001000000000001);
                if (mismatch.equals("identity")) f.txid = "00".repeat(32);
                if (mismatch.equals("script")) f.output(0).getJSONObject("scriptPubKey").put("hex", "52");
                if (mismatch.equals("count")) f.verbose.getJSONArray("vout").put(new JSONObject(f.output(0).toString()));
                if (mismatch.equals("trailing")) f.raw += "00";
                if (mismatch.equals("malformed")) f.raw = "not-hex";
                assertRejected(f);
            }
        }
    }

    @Test(timeout = 15000)
    public void missingWireEvidenceDoesNotRoundOrCacheAmbiguousAmount() throws Exception {
        try (Fixture f = new Fixture(10001L)) {
            f.output(0).put("value", 0.00010001000000000001);
            String raw = f.raw;
            f.raw = null;
            assertRejected(f);
            f.raw = raw;
            assertValues(f, 10001L);
            assertEquals(4, f.requests.get());
        }
    }

    @Test(timeout = 15000)
    public void malformedNegativeAndMissingAmountsAreCheckedFailures() throws Exception {
        for (Object amount : List.of(-0.5, "0.0001", JSONObject.NULL)) {
            try (Fixture f = new Fixture(10001L)) {
                f.output(0).put("value", amount);
                assertRejected(f);
            }
        }
    }

    private void assertValues(Fixture fixture, long... values) throws Exception {
        BitcoinyTransaction transaction = fixture.provider.getTransaction(fixture.txid);
        assertEquals(values.length, transaction.outputs.size());
        for (int i = 0; i < values.length; ++i) assertEquals(values[i], transaction.outputs.get(i).value);
    }

    private void assertRejected(Fixture fixture) throws Exception {
        try {
            fixture.provider.getTransaction(fixture.txid);
            fail("Invalid or ambiguous amounts must not be rounded into wallet history");
        } catch (ForeignBlockchainException expected) { }
    }

    @SuppressWarnings("unchecked")
    private class Fixture implements AutoCloseable {
        final ServerSocket listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        final ExecutorService worker = Executors.newSingleThreadExecutor();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final AtomicInteger requests = new AtomicInteger();
        final ElectrumX provider = new ElectrumX("history-fixture", "00".repeat(32), List.of(), Map.of());
        final JSONObject verbose;
        volatile String raw;
        String txid;
        volatile Socket client;
        volatile boolean closing;

        Fixture(long... values) throws Exception {
            Context.propagate(new Context(params));
            Transaction transaction = new Transaction(params);
            transaction.addInput(Sha256Hash.of(new byte[]{1}), 0, new Script(new byte[0]));
            JSONArray outputs = new JSONArray();
            for (int i = 0; i < values.length; ++i) {
                String script = i % 2 == 0 ? "51" : "52";
                // bitcoinj's construction helper applies BTC's limit to several altcoins;
                // the wire decoder must still preserve valid larger altcoin outputs.
                transaction.addOutput(Coin.valueOf(1), new Script(HashCode.fromString(script).asBytes()))
                        .setValue(Coin.valueOf(values[i]));
                outputs.put(new JSONObject().put("value", values[i] / 100000000.0)
                        .put("scriptPubKey", new JSONObject().put("hex", script).put("address", "fixture-" + currency)));
            }
            raw = HashCode.fromBytes(transaction.bitcoinSerialize()).toString();
            txid = transaction.getTxId().toString();
            verbose = new JSONObject().put("size", raw.length() / 2).put("locktime", 0).put("time", 1700000000)
                    .put("vin", new JSONArray().put(new JSONObject().put("scriptSig", new JSONObject().put("hex", ""))
                            .put("sequence", 4294967295L).put("txid", Sha256Hash.of(new byte[]{1}).toString()).put("vout", 0)))
                    .put("vout", outputs);
            Bitcoiny coin = new Bitcoiny(provider, new Context(params), currency, Coin.valueOf(10000)) {
                @Override public long getP2shFee(Long timestamp) { return 1000; }
                @Override public long getFeeRequired() { return 1000; }
                @Override public void setFeeRequired(long fee) { }
            };
            provider.setBlockchain(coin);
            field("connectionManagementStarted").setBoolean(provider, true);
            worker.submit(() -> {
                try (Socket socket = listener.accept()) {
                    client = socket;
                    try (BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                         Writer output = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)) {
                        String line;
                        while ((line = input.readLine()) != null) {
                            JSONObject request = new JSONObject(line);
                            assertEquals("blockchain.transaction.get", request.getString("method"));
                            assertEquals(txid, request.getJSONArray("params").getString(0));
                            requests.incrementAndGet();
                            JSONObject response = new JSONObject().put("id", request.get("id")).put("jsonrpc", "2.0");
                            if (request.getJSONArray("params").getBoolean(1)) response.put("result", verbose);
                            else if (raw != null) response.put("result", raw);
                            else response.put("error", new JSONObject().put("code", -5).put("message", "fixture missing wire data"));
                            output.write(response + "\n");
                            output.flush();
                        }
                    }
                } catch (Throwable e) {
                    if (!closing) failure.compareAndSet(null, e);
                }
            });
            ElectrumX.Server server = new ElectrumX.Server("127.0.0.1", ChainableServer.ConnectionType.TCP, listener.getLocalPort());
            ElectrumServer connection = ElectrumServer.createInstance(server,
                    new InetSocketAddress("127.0.0.1", server.getPort()), 3000, new ChainableServerConnectionRecorder(10));
            ((Set<ElectrumServer>) field("connections").get(provider)).add(connection);
            ((BlockingQueue<ElectrumServer>) field("availableConnections").get(provider)).add(connection);
        }

        JSONObject output(int index) { return verbose.getJSONArray("vout").getJSONObject(index); }

        Field field(String name) throws Exception {
            Field field = ElectrumX.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        }

        @Override public void close() throws Exception {
            try {
                Method close = ElectrumX.class.getDeclaredMethod("closeAllConnections", String.class);
                close.setAccessible(true);
                close.invoke(provider, "history fixture cleanup");
                for (String name : List.of("scheduleMakeConnections", "scheduleRecoverConnections", "scheduleMonitorConnections")) {
                    ExecutorService executor = (ExecutorService) field(name).get(provider);
                    executor.shutdownNow();
                    assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
                }
            } finally {
                closing = true;
                listener.close();
                if (client != null) client.close();
                worker.shutdownNow();
                assertTrue(worker.awaitTermination(3, TimeUnit.SECONDS));
                assertNull("Loopback history server failed", failure.get());
            }
        }
    }
}
