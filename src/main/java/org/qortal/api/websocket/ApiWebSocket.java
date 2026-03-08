package org.qortal.api.websocket;

import org.eclipse.jetty.http.pathmap.UriTemplatePathSpec;
import org.eclipse.jetty.websocket.api.Session;
//import org.eclipse.jetty.websocket.server.JettyServletUpgradeRequest;
import org.eclipse.jetty.websocket.server.JettyServerUpgradeRequest;
import org.eclipse.jetty.websocket.server.JettyWebSocketServlet;
import org.eclipse.jetty.websocket.server.JettyWebSocketServletFactory;
import org.eclipse.persistence.jaxb.JAXBContextFactory;
import org.eclipse.persistence.jaxb.MarshallerProperties;
import org.qortal.api.ApiError;
import org.qortal.api.ApiErrorRoot;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.*;

@SuppressWarnings("serial")
public abstract class ApiWebSocket extends JettyWebSocketServlet {

	private static final Map<Class<? extends ApiWebSocket>, List<Session>> SESSIONS_BY_CLASS = new HashMap<>();

	/**
	 * Jetty 10 requires an implementation of configure.
	 * Subclasses should override this or call super if using custom mappings.
	 */
	@Override
	protected void configure(JettyWebSocketServletFactory factory) {
		// Default implementation can be empty or used to register the class
		// Subclasses like ActiveChatsWebSocket will override this to register themselves.
		factory.addMapping("/", (req, res) -> this);

	}

	protected static String getPathInfo(Session session) {
		if (session.getUpgradeRequest() instanceof JettyServerUpgradeRequest) {
			JettyServerUpgradeRequest upgradeRequest = (JettyServerUpgradeRequest) session.getUpgradeRequest();
			return upgradeRequest.getHttpServletRequest().getPathInfo();
		}

		// Fallback if the request is not a standard Jetty upgrade request
		return session.getUpgradeRequest().getRequestURI().getPath();
	}
	protected static Map<String, String> getPathParams(Session session, String pathSpec) {
		UriTemplatePathSpec uriTemplatePathSpec = new UriTemplatePathSpec(pathSpec);
		return uriTemplatePathSpec.getPathParams(getPathInfo(session));
	}

	protected static void sendError(Session session, ApiError apiError) {
		ApiErrorRoot apiErrorRoot = new ApiErrorRoot();
		apiErrorRoot.setApiError(apiError);

		StringWriter stringWriter = new StringWriter();
		try {
			marshall(stringWriter, apiErrorRoot);
			session.getRemote().sendString(stringWriter.toString(), null); // Jetty 10 sendString now requires a Callback or is blocking
		} catch (IOException e) {
			// Remote end probably closed
		}
	}

	protected static void marshall(Writer writer, Object object) throws IOException {
		Marshaller marshaller = createMarshaller(object.getClass());

		try {
			marshaller.marshal(object, writer);
		} catch (JAXBException e) {
			throw new IOException("Unable to create marshall object for websocket", e);
		}
	}

	protected static void marshall(Writer writer, Collection<?> collection) throws IOException {
		// If collection is empty then we're returning "[]" anyway
		if (collection.isEmpty()) {
			writer.append("[]");
			return;
		}

		// Grab an entry from collection so we can determine type
		Object entry = collection.iterator().next();
		Marshaller marshaller = createMarshaller(entry.getClass());

		try {
			marshaller.marshal(collection, writer);
		} catch (JAXBException e) {
			throw new IOException("Unable to create marshall object for websocket", e);
		}
	}

	private static Marshaller createMarshaller(Class<?> objectClass) {
		try {
			// Create JAXB context aware of object's class
			JAXBContext jc = JAXBContextFactory.createContext(new Class[] { objectClass }, null);
			Marshaller marshaller = jc.createMarshaller();
			marshaller.setProperty(MarshallerProperties.MEDIA_TYPE, "application/json");
			marshaller.setProperty(MarshallerProperties.JSON_INCLUDE_ROOT, false);
			return marshaller;
		} catch (JAXBException e) {
			throw new RuntimeException("Unable to create websocket marshaller", e);
		}
	}

	public void onWebSocketConnect(Session session) {
		synchronized (SESSIONS_BY_CLASS) {
			SESSIONS_BY_CLASS.computeIfAbsent(this.getClass(), clazz -> new ArrayList<>()).add(session);
		}
	}

	public void onWebSocketClose(Session session, int statusCode, String reason) {
		synchronized (SESSIONS_BY_CLASS) {
			List<Session> sessions = SESSIONS_BY_CLASS.get(this.getClass());
			if (sessions != null)
				sessions.remove(session);
		}
	}

	protected List<Session> getSessions() {
		synchronized (SESSIONS_BY_CLASS) {
			return new ArrayList<>(SESSIONS_BY_CLASS.getOrDefault(this.getClass(), Collections.emptyList()));
		}
	}
}