package org.qortal.crosschain;

import org.json.JSONObject;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Exercise real socket replies without remote servers, wallet keys or timing sleeps. */
public class ElectrumXResponseTests {

	@Test(timeout = 15000)
	public void successfulSlowReplyIsReturnedOnceBeforeRetiringConnection() throws Exception {
		try (Fixture fixture = new Fixture("valid", true)) {
			assertEquals(123L, fixture.provider.getConfirmedBalance(new byte[] { 0x51 }));
			assertEquals("A completed query must not be repeated", 1, fixture.slow.requests.get());
			assertEquals(0, fixture.healthy.requests.get());
			assertFalse(fixture.connections.contains(fixture.slowConnection));
			assertEquals(1, fixture.available.size());
			assertEquals(456L, fixture.provider.getConfirmedBalance(new byte[] { 0x51 }));
			assertEquals(1, fixture.healthy.requests.get());
			assertEquals(0, fixture.inFlight.get());
		}
	}

	@Test(timeout = 15000)
	public void missingSlowReplyTriesAvailableAlternative() throws Exception {
		try (Fixture fixture = new Fixture("missing", true)) {
			assertEquals(456L, fixture.provider.getConfirmedBalance(new byte[] { 0x51 }));
			assertEquals(1, fixture.slow.requests.get());
			assertEquals(1, fixture.healthy.requests.get());
			assertFalse(fixture.connections.contains(fixture.slowConnection));
			assertEquals(1, fixture.available.size());
			assertEquals(0, fixture.inFlight.get());
		}
	}

	@Test(timeout = 15000)
	public void successfulSlowReplyKeepsOnlyAvailableConnection() throws Exception {
		try (Fixture fixture = new Fixture("valid", false)) {
			assertEquals(123L, fixture.provider.getConfirmedBalance(new byte[] { 0x51 }));
			assertTrue(fixture.connections.contains(fixture.slowConnection));
			assertEquals(1, fixture.available.size());
			assertEquals(0, fixture.inFlight.get());
		}
	}

	@Test(timeout = 15000)
	public void definitiveDaemonErrorIsNotRetriedOnAnotherServer() throws Exception {
		try (Fixture fixture = new Fixture("error", true)) {
			try {
				fixture.provider.getConfirmedBalance(new byte[] { 0x51 });
				fail("A definitive daemon error must reach the caller");
			} catch (ForeignBlockchainException.NetworkException expected) {
				assertEquals(Integer.valueOf(-5), expected.getDaemonErrorCode());
			}
			assertEquals(1, fixture.slow.requests.get());
			assertEquals(0, fixture.healthy.requests.get());
			assertTrue(fixture.connections.contains(fixture.slowConnection));
			assertEquals(2, fixture.available.size());
			assertEquals(0, fixture.inFlight.get());
		}
	}

	@SuppressWarnings("unchecked")
	private static class Fixture implements AutoCloseable {
		final ReplyServer slow;
		final ReplyServer healthy;
		final ElectrumX provider;
		final ElectrumServer slowConnection;
		final Set<ElectrumServer> connections;
		final BlockingQueue<ElectrumServer> available;
		final AtomicInteger inFlight;

		Fixture(String slowReply, boolean alternative) throws Exception {
			slow = new ReplyServer(slowReply, 123L);
			healthy = new ReplyServer("valid", 456L);
			provider = new ElectrumX("response-fixture", "00".repeat(32), List.of(), Map.of());
			// Seed only loopback connections; never start discovery or maintenance threads.
			Field started = field("connectionManagementStarted");
			started.setBoolean(provider, true);
			connections = (Set<ElectrumServer>) field("connections").get(provider);
			available = (BlockingQueue<ElectrumServer>) field("availableConnections").get(provider);
			inFlight = (AtomicInteger) field("inFlightRpcCount").get(provider);
			ElectrumX.Server slowServer = configuration(slow);
			for (int i = 0; i < 6; i++) slowServer.addResponseTime(6000L);
			slowConnection = connect(slowServer);
			if (alternative) connect(configuration(healthy));
		}

		private ElectrumX.Server configuration(ReplyServer reply) {
			return new ElectrumX.Server("127.0.0.1", ChainableServer.ConnectionType.TCP, reply.listener.getLocalPort());
		}

		private ElectrumServer connect(ElectrumX.Server server) throws Exception {
			ElectrumServer connection = ElectrumServer.createInstance(server,
					new InetSocketAddress("127.0.0.1", server.getPort()), 3000,
					new ChainableServerConnectionRecorder(10));
			connections.add(connection);
			available.add(connection);
			return connection;
		}

		private Field field(String name) throws Exception {
			Field field = ElectrumX.class.getDeclaredField(name);
			field.setAccessible(true);
			return field;
		}

		@Override
		public void close() throws Exception {
			try {
				Method close = ElectrumX.class.getDeclaredMethod("closeAllConnections", String.class);
				close.setAccessible(true);
				close.invoke(provider, "fixture cleanup");
				for (String name : List.of("scheduleMakeConnections", "scheduleRecoverConnections", "scheduleMonitorConnections")) {
					ExecutorService executor = (ExecutorService) field(name).get(provider);
					executor.shutdownNow();
					assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
				}
			} finally {
				try { slow.close(); } finally { healthy.close(); }
			}
		}
	}

	private static class ReplyServer implements AutoCloseable {
		final ServerSocket listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
		final AtomicInteger requests = new AtomicInteger();
		final AtomicReference<Throwable> failure = new AtomicReference<>();
		final ExecutorService worker = Executors.newSingleThreadExecutor();
		volatile Socket client;
		volatile boolean closing;

		ReplyServer(String reply, long balance) throws Exception {
			worker.submit(() -> {
				try (Socket socket = listener.accept()) {
					client = socket;
					try (BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
						 Writer output = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)) {
						String line;
						while ((line = input.readLine()) != null) {
							JSONObject request = new JSONObject(line);
							assertEquals("blockchain.scripthash.get_balance", request.getString("method"));
							requests.incrementAndGet();
							JSONObject response = new JSONObject().put("id", request.get("id")).put("jsonrpc", "2.0");
							if (reply.equals("valid"))
								response.put("result", new JSONObject().put("confirmed", balance).put("unconfirmed", 0));
							else if (reply.equals("error"))
								response.put("error", new JSONObject().put("code", 1)
										.put("message", "daemon error: DaemonError({'code': -5, 'message': 'fixture rejection'})"));
							output.write(response + "\n");
							output.flush();
						}
					}
				} catch (Throwable e) {
					if (!closing) failure.compareAndSet(null, e);
				}
			});
		}

		@Override
		public void close() throws Exception {
			closing = true;
			listener.close();
			if (client != null) client.close();
			worker.shutdownNow();
			assertTrue(worker.awaitTermination(3, TimeUnit.SECONDS));
			assertNull("Loopback reply server failed", failure.get());
		}
	}
}
