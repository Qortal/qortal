package org.qortal.api.websocket;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WriteCallback;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketError;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.server.JettyWebSocketServletFactory;
import org.qortal.data.arbitrary.DataMonitorInfo;
import org.qortal.event.DataMonitorEvent;
import org.qortal.event.Event;
import org.qortal.event.EventBus;
import org.qortal.event.Listener;

import java.io.IOException;
import java.io.StringWriter;

@WebSocket
@SuppressWarnings("serial")
public class DataMonitorSocket extends ApiWebSocket implements Listener {

	private static final Logger LOGGER = LogManager.getLogger(DataMonitorSocket.class);

	/**
	 * Updated for Jetty 10 using JettyWebSocketServletFactory.
	 */
	@Override
	protected void configure(JettyWebSocketServletFactory factory) {
		LOGGER.info("configure");

		// Register this instance to handle websocket upgrades on the servlet path
		factory.addMapping("/", (req, res) -> this);

		EventBus.INSTANCE.addListener(this);
	}

	@Override
	public void listen(Event event) {
		if (!(event instanceof DataMonitorEvent))
			return;

		DataMonitorEvent dataMonitorEvent = (DataMonitorEvent) event;

		for (Session session : getSessions())
			sendDataEventSummary(session, buildInfo(dataMonitorEvent));
	}

	private DataMonitorInfo buildInfo(DataMonitorEvent dataMonitorEvent) {
		return new DataMonitorInfo(
			dataMonitorEvent.getTimestamp(),
			dataMonitorEvent.getIdentifier(),
			dataMonitorEvent.getName(),
			dataMonitorEvent.getService(),
			dataMonitorEvent.getDescription(),
			dataMonitorEvent.getTransactionTimestamp(),
			dataMonitorEvent.getLatestPutTimestamp()
		);
	}

	@OnWebSocketConnect
	@Override
	public void onWebSocketConnect(Session session) {
		super.onWebSocketConnect(session);
	}

	@OnWebSocketClose
	@Override
	public void onWebSocketClose(Session session, int statusCode, String reason) {
		super.onWebSocketClose(session, statusCode, reason);
	}

	@OnWebSocketError
	public void onWebSocketError(Session session, Throwable throwable) {
		/* We ignore errors to silence log spam */
	}

	@OnWebSocketMessage
	public void onWebSocketMessage(Session session, String message) {
		if (java.util.Objects.equals(message, "ping") && session.isOpen()) {
			session.getRemote().sendString("pong", WriteCallback.NOOP);
			return;
		}
		LOGGER.info("onWebSocketMessage: message = " + message);
	}

	private void sendDataEventSummary(Session session, DataMonitorInfo dataMonitorInfo) {
		StringWriter stringWriter = new StringWriter();

		try {
			marshall(stringWriter, dataMonitorInfo);

			// Jetty 10 uses sendString with a WriteCallback for asynchronous delivery
			if (session.isOpen()) {
				session.getRemote().sendString(stringWriter.toString(), WriteCallback.NOOP);
			}
		} catch (IOException e) {
			// No output this time. WebSocketException is no longer explicitly required here.
		}
	}
}