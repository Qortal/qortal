package org.qortal.crosschain;

import org.bitcoinj.core.Coin;
import org.bitcoinj.core.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.qortal.test.common.Common;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Actual socket/JSON negotiation against servers that change RPCs by protocol. */
public class ElectrumXProtocolTests extends Common {

	@Test(timeout = 15000)
	public void upgradedServerRetainsWalletRpcAndHeaderCompatibility() throws Exception {
		checkWalletQueries("1.7");
	}

	@Test(timeout = 15000)
	public void existingProtocol14ServerRemainsCompatible() throws Exception {
		checkWalletQueries("1.4");
	}

	private void checkWalletQueries(String serverMaximum) throws Exception {
		try (ProtocolServer fixture = new ProtocolServer(serverMaximum)) {
			ElectrumX provider = new ElectrumX("protocol-fixture", ProtocolServer.GENESIS,
					List.of(new ElectrumX.Server("127.0.0.1", ChainableServer.ConnectionType.TCP,
							fixture.listener.getLocalPort())), Map.of());
			provider.setBlockchain(new Bitcoiny(provider, new Context(Digibyte.DigibyteNet.MAIN.getParams()),
					"DGB", Coin.valueOf(10000)) {
				@Override public long getP2shFee(Long timestamp) { return 10000; }
				@Override public long getFeeRequired() { return 10000; }
				@Override public void setFeeRequired(long fee) { }
			});
			try {
				byte[] script = new byte[] { 0x51 }; // Synthetic OP_TRUE script, no wallet data.
				List<TransactionHash> history = provider.getAddressTransactions(script, false);
				assertEquals(1, history.size());
				assertEquals(ProtocolServer.TX_ID, history.get(0).txHash);
				assertEquals(123, history.get(0).height);
				List<UnspentOutput> outputs = provider.getUnspentOutputs(script, false);
				assertEquals(1, outputs.size());
				assertEquals(123456789L, outputs.get(0).value);
				assertEquals(123456789L, provider.getConfirmedBalance(script));
				assertEquals(80, provider.getRawBlockHeaders(123, 1).get(0).length);
				assertFalse(fixture.negotiatedVersions.isEmpty());
				for (String version : fixture.negotiatedVersions)
					assertEquals("1.4", version);
			} finally {
				// Production maintenance runs in the background; stop only this fixture's pools.
				for (String name : List.of("scheduleMakeConnections", "scheduleRecoverConnections", "scheduleMonitorConnections")) {
					Field field = ElectrumX.class.getDeclaredField(name);
					field.setAccessible(true);
					ExecutorService executor = (ExecutorService) field.get(provider);
					executor.shutdownNow();
					assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
				}
				Method close = ElectrumX.class.getDeclaredMethod("closeAllConnections", String.class);
				close.setAccessible(true);
				close.invoke(provider, "fixture cleanup");
			}
		}
	}

	private static class ProtocolServer implements AutoCloseable {
		private static final String GENESIS = "00".repeat(32);
		private static final String TX_ID = "11".repeat(32);
		private final ServerSocket listener = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"));
		private final ExecutorService workers = Executors.newCachedThreadPool();
		private final Set<Socket> clients = ConcurrentHashMap.newKeySet();
		private final List<String> negotiatedVersions = new CopyOnWriteArrayList<>();
		private final AtomicReference<Throwable> failure = new AtomicReference<>();
		private volatile boolean closing;

		private ProtocolServer(String serverMaximum) throws IOException {
			workers.submit(() -> {
				try {
					while (!closing) {
						Socket socket = listener.accept();
						clients.add(socket);
						workers.submit(() -> serve(socket, serverMaximum));
					}
				} catch (IOException e) {
					if (!closing) failure.compareAndSet(null, e);
				}
			});
		}

		private void serve(Socket socket, String serverMaximum) {
			try (Socket client = socket;
				 BufferedReader input = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
				 Writer output = new OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8)) {
				double protocol = 1.4;
				String line;
				while ((line = input.readLine()) != null) {
					JSONObject request = new JSONObject(line);
					String method = request.getString("method");
					JSONObject response = new JSONObject().put("id", request.get("id")).put("jsonrpc", "2.0");
					if (method.equals("server.version")) {
						JSONArray range = request.getJSONArray("params").getJSONArray(1);
						protocol = Math.min(Double.parseDouble(serverMaximum), Double.parseDouble(range.getString(1)));
						String negotiated = Double.toString(protocol);
						negotiatedVersions.add(negotiated);
						response.put("result", new JSONArray().put("ElectrumX fixture").put(negotiated));
					} else if (method.equals("server.features")) {
						response.put("result", new JSONObject().put("protocol_min", "1.4")
								.put("protocol_max", serverMaximum).put("genesis_hash", GENESIS));
					} else if (method.startsWith("blockchain.scripthash.") && protocol >= 1.7) {
						response.put("error", new JSONObject().put("code", -32601).put("message", "unknown method \"" + method + "\""));
					} else if (method.equals("blockchain.scripthash.get_history")) {
						response.put("result", new JSONArray().put(new JSONObject().put("height", 123).put("tx_hash", TX_ID)));
					} else if (method.equals("blockchain.scripthash.listunspent")) {
						response.put("result", new JSONArray().put(new JSONObject().put("height", 123)
								.put("tx_hash", TX_ID).put("tx_pos", 0).put("value", 123456789L)));
					} else if (method.equals("blockchain.scripthash.get_balance")) {
						response.put("result", new JSONObject().put("confirmed", 123456789L).put("unconfirmed", 0));
					} else if (method.equals("blockchain.block.headers")) {
						JSONObject result = new JSONObject().put("count", 1).put("max", 2016);
						if (protocol >= 1.6) result.put("headers", new JSONArray().put("00".repeat(80)));
						else result.put("hex", "00".repeat(80));
						response.put("result", result);
					} else if (method.equals("server.peers.subscribe")) {
						response.put("result", new JSONArray());
					} else {
						throw new AssertionError("Unexpected fixture RPC: " + method);
					}
					output.write(response + "\n");
					output.flush();
				}
			} catch (Throwable e) {
				if (!closing) failure.compareAndSet(null, e);
			} finally {
				clients.remove(socket);
			}
		}

		@Override
		public void close() throws Exception {
			closing = true;
			listener.close();
			for (Socket client : clients) client.close();
			workers.shutdownNow();
			assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS));
			assertNull("Loopback Electrum server failed", failure.get());
		}
	}
}
