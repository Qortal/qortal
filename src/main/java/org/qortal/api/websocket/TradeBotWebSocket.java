package org.qortal.api.websocket;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WriteCallback;
import org.eclipse.jetty.websocket.api.annotations.*;
import org.eclipse.jetty.websocket.server.JettyWebSocketServletFactory;
import org.qortal.controller.tradebot.TradeBot;
import org.qortal.crosschain.SupportedBlockchain;
import org.qortal.data.crosschain.TradeBotData;
import org.qortal.event.Event;
import org.qortal.event.EventBus;
import org.qortal.event.Listener;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.utils.Base58;

import java.io.IOException;
import java.io.StringWriter;
import java.util.*;
import java.util.stream.Collectors;

@WebSocket
@SuppressWarnings("serial")
public class TradeBotWebSocket extends ApiWebSocket implements Listener {

    /** Cache of trade-bot entry states, keyed by trade-bot entry's "trade private key" (base58) */
    private static final Map<String, Integer> PREVIOUS_STATES = new HashMap<>();

    private static final Map<Session, String> sessionBlockchain = Collections.synchronizedMap(new HashMap<>());

    /**
     * Updated for Jetty 10.
     */
    @Override
    protected void configure(JettyWebSocketServletFactory factory) {
        // Map the current instance to handle upgrades
        factory.addMapping("/", (req, res) -> this);

        try (final Repository repository = RepositoryManager.getRepository()) {
            List<TradeBotData> tradeBotEntries = repository.getCrossChainRepository().getAllTradeBotData();
            if (tradeBotEntries != null) {
                synchronized (PREVIOUS_STATES) {
                    PREVIOUS_STATES.putAll(tradeBotEntries.stream().collect(Collectors.toMap(
                            entry -> Base58.encode(entry.getTradePrivateKey()),
                            TradeBotData::getStateValue)));
                }
            }
        } catch (DataException e) {
            // No initial state cache available
        }

        EventBus.INSTANCE.addListener(this);
    }

    @Override
    public void listen(Event event) {
        if (!(event instanceof TradeBot.StateChangeEvent))
            return;

        TradeBotData tradeBotData = ((TradeBot.StateChangeEvent) event).getTradeBotData();
        String tradePrivateKey58 = Base58.encode(tradeBotData.getTradePrivateKey());

        synchronized (PREVIOUS_STATES) {
            Integer previousStateValue = PREVIOUS_STATES.get(tradePrivateKey58);
            if (previousStateValue != null && previousStateValue == tradeBotData.getStateValue())
                return;

            PREVIOUS_STATES.put(tradePrivateKey58, tradeBotData.getStateValue());
        }

        List<TradeBotData> tradeBotEntries = Collections.singletonList(tradeBotData);

        for (Session session : getSessions()) {
            String preferredBlockchain = sessionBlockchain.get(session);
            if (preferredBlockchain == null || preferredBlockchain.equals(tradeBotData.getForeignBlockchain()))
                sendEntries(session, tradeBotEntries);
        }
    }

    @OnWebSocketConnect
    @Override
    public void onWebSocketConnect(Session session) {
        super.onWebSocketConnect(session);

        Map<String, List<String>> queryParams = session.getUpgradeRequest().getParameterMap();
        final boolean excludeInitialData = queryParams.containsKey("excludeInitialData");

        List<String> foreignBlockchains = queryParams.get("foreignBlockchain");
        final String foreignBlockchain = (foreignBlockchains == null || foreignBlockchains.isEmpty()) ? null : foreignBlockchains.get(0);

        if (foreignBlockchain != null && SupportedBlockchain.fromString(foreignBlockchain) == null) {
            session.close(4003, "unknown blockchain: " + foreignBlockchain);
            return;
        }

        if (foreignBlockchain != null)
            sessionBlockchain.put(session, foreignBlockchain);

        try (final Repository repository = RepositoryManager.getRepository()) {
            List<TradeBotData> tradeBotEntries = new ArrayList<>();

            if (!excludeInitialData) {
                tradeBotEntries = repository.getCrossChainRepository().getAllTradeBotData();
                if (foreignBlockchain != null) {
                    tradeBotEntries = tradeBotEntries.stream()
                            .filter(tradeBotData -> tradeBotData.getForeignBlockchain().equals(foreignBlockchain))
                            .collect(Collectors.toList());
                }
            }

            if (!sendEntries(session, tradeBotEntries)) {
                session.close(4002, "websocket issue");
            }
        } catch (DataException e) {
            session.close(4001, "repository issue fetching trade-bot entries");
        }
    }

	@OnWebSocketClose
	@Override
	public void onWebSocketClose(Session session, int statusCode, String reason) {
		// clean up
		sessionBlockchain.remove(session);
		super.onWebSocketClose(session, statusCode, reason);
	}

	@OnWebSocketError
	public void onWebSocketError(Session session, Throwable throwable) {
		/* ignored */
	}

	@OnWebSocketMessage
	public void onWebSocketMessage(Session session, String message) {
		if (Objects.equals(message, "ping") && session.isOpen()) {
			session.getRemote().sendString("pong", WriteCallback.NOOP);
		}
	}

    private boolean sendEntries(Session session, List<TradeBotData> tradeBotEntries) {
        if (session.isOpen()) {
            try {
                StringWriter stringWriter = new StringWriter();
                marshall(stringWriter, tradeBotEntries);
                String output = stringWriter.toString();

                // Updated from sendStringByFuture to Jetty 10 async send pattern
                session.getRemote().sendString(output, WriteCallback.NOOP);
                return true;
            } catch (IOException e) {
                return false;
            }
        }
        return false;
    }
}