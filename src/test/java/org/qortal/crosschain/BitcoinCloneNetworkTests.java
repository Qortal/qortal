package org.qortal.crosschain;

import org.bitcoinj.core.Context;
import org.json.JSONObject;
import org.junit.Test;
import org.qortal.test.common.Common;

import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Actual loopback handshakes through the reused ElectrumX implementation. */
public class BitcoinCloneNetworkTests extends Common {
    @Test(timeout = 15000) public void rejectsWrongNetworkAndMalformedFeaturesThenUsesHealthyServer() throws Exception {
        BitcoinCloneDefinition definition = BitcoinCloneDefinitionTests.load(BitcoinCloneDefinitionTests.fixture());
        try (Server wrong = new Server("11".repeat(32), false);
             Server malformed = new Server(definition.genesisHash, true);
             Server healthy = new Server(definition.genesisHash, false)) {
            Set<Integer> ports = Set.of(wrong.port(), malformed.port(), healthy.port());
            ElectrumX provider = new ElectrumX(definition.key(), definition.genesisHash, List.of(), Map.of(),
                    server -> server.getHostName().equals("127.0.0.1") && ports.contains(server.getPort()));
            new BitcoinClone(definition, provider, new Context(definition.parameters()));
            field("connectionManagementStarted").setBoolean(provider, true); // Never run background discovery in tests.
            try {
                assertFalse(connect(provider, wrong.port()).isSuccess());
                assertTrue("Rejected connection must close", wrong.closed.await(2, TimeUnit.SECONDS));
                assertFalse(connect(provider, malformed.port()).isSuccess());
                assertTrue(malformed.closed.await(2, TimeUnit.SECONDS));
                assertEquals(0, provider.getConnectedServerCount());
                assertTrue(connect(provider, healthy.port()).isSuccess());
                assertEquals(1, provider.getConnectedServerCount());
                assertEquals(42L, provider.getConfirmedBalance(new byte[]{0x51}));
            } finally { cleanup(provider); }
        }
    }

    @Test(timeout = 5000) public void destinationPolicyBlocksConnectionsProbesAndPeerDiscovery() throws Exception {
        BitcoinCloneDefinition definition = BitcoinCloneDefinitionTests.load(BitcoinCloneDefinitionTests.fixture());
        BitcoinClone backend = BitcoinClone.create(definition);
        ElectrumX provider = (ElectrumX) backend.getBlockchainProvider();
        try {
            ChainableServer forbidden = new ElectrumX.Server("169.254.169.254", ChainableServer.ConnectionType.TCP, 80);
            Method connect = method("makeConnection", ChainableServer.class, String.class);
            Optional<?> result = (Optional<?>) connect.invoke(provider, forbidden, "fixture");
            assertFalse(((ChainableServerConnection) result.get()).isSuccess());
            assertFalse(((ChainableServerConnection) result.get()).isOpen());
            method("probeServer", ChainableServer.class).invoke(provider, forbidden);
            assertEquals(Set.of(), method("serverPeersSubscribe").invoke(provider));
            assertEquals(0, provider.getConnectedServerCount());
            assertFalse(field("connectionManagementStarted").getBoolean(provider));
        } finally { cleanup(provider); }
    }

    @SuppressWarnings("unchecked")
    private static ChainableServerConnection connect(ElectrumX provider, int port) throws Exception {
        return ((Optional<ChainableServerConnection>) method("makeConnection", ChainableServer.class, String.class)
                .invoke(provider, new ElectrumX.Server("127.0.0.1", ChainableServer.ConnectionType.TCP, port), "fixture")).get();
    }

    private static Field field(String name) throws Exception {
        Field field = ElectrumX.class.getDeclaredField(name); field.setAccessible(true); return field;
    }

    private static Method method(String name, Class<?>... types) throws Exception {
        Method method = ElectrumX.class.getDeclaredMethod(name, types); method.setAccessible(true); return method;
    }

    private static void cleanup(ElectrumX provider) throws Exception {
        method("closeAllConnections", String.class).invoke(provider, "fixture cleanup");
        for (String name : List.of("scheduleMakeConnections", "scheduleRecoverConnections", "scheduleMonitorConnections")) {
            ExecutorService pool = (ExecutorService) field(name).get(provider);
            pool.shutdownNow(); assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    private static class Server implements AutoCloseable {
        final ServerSocket listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        final ExecutorService worker = Executors.newSingleThreadExecutor();
        final CountDownLatch closed = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        volatile Socket client;
        volatile boolean closing;

        Server(String genesis, boolean malformed) throws Exception {
            worker.submit(() -> {
                try (Socket socket = listener.accept()) {
                    client = socket;
                    try (BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                         Writer output = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)) {
                        String line;
                        while ((line = input.readLine()) != null) {
                            JSONObject request = new JSONObject(line);
                            JSONObject reply = new JSONObject().put("id", request.get("id")).put("jsonrpc", "2.0");
                            switch (request.getString("method")) {
                                case "server.version": reply.put("result", new org.json.JSONArray().put("fixture").put("1.4")); break;
                                case "server.features": reply.put("result", malformed ? "bad features" : new JSONObject()
                                        .put("protocol_min", "1.4").put("genesis_hash", genesis)); break;
                                case "blockchain.scripthash.get_balance": reply.put("result", new JSONObject().put("confirmed", 42).put("unconfirmed", 0)); break;
                                default: throw new AssertionError("Unexpected RPC: " + request.getString("method"));
                            }
                            output.write(reply + "\n"); output.flush();
                        }
                    }
                } catch (Throwable e) { if (!closing) failure.set(e); }
                finally { closed.countDown(); }
            });
        }

        int port() { return listener.getLocalPort(); }
        @Override public void close() throws Exception {
            closing = true; listener.close(); if (client != null) client.close();
            worker.shutdownNow(); assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS));
            assertNull(failure.get());
        }
    }
}
