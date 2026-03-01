package org.qortal.api;

import io.swagger.v3.jaxrs2.integration.resources.OpenApiResource;
import io.swagger.v3.oas.integration.SwaggerConfiguration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jetty.alpn.server.ALPNServerConnectionFactory;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http2.HTTP2Cipher;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.rewrite.handler.RedirectPatternRule;
import org.eclipse.jetty.rewrite.handler.RewriteHandler;
import org.eclipse.jetty.server.*;
import org.eclipse.jetty.server.handler.ErrorHandler;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.server.handler.InetAccessHandler;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.servlets.CrossOriginFilter;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.websocket.server.config.JettyWebSocketServletContainerInitializer;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.servlet.ServletContainer;
import org.qortal.api.resource.AnnotationPostProcessor;
import org.qortal.api.resource.ApiDefinition;
import org.qortal.api.websocket.*;
import org.qortal.network.Network;
import org.qortal.settings.Settings;
import org.qortal.utils.SslUtils;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.servlet.http.HttpServletRequest;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Security;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.stream.Collectors;

public class ApiService {

	private static final Logger LOGGER = LogManager.getLogger(ApiService.class);
	private static ApiService instance;
	private static final Set<String> API_RESOURCE_PACKAGES = Set.of(
			"org.qortal.api.resource",
			"org.qortal.api.restricted.resource"
	);
	private static final Set<String> OPENAPI_RESOURCE_CLASS_NAMES = discoverOpenApiResourceClassNames();

	/** Ensures stop/start/restart are atomic and no concurrent lifecycle changes. */
	private final Object lifecycleLock = new Object();

	private Server server;
	private ApiKey apiKey;

	/**
	 * Creates a fresh ResourceConfig for each server lifecycle or inspection.
	 * Jersey locks (immutabilizes) a ResourceConfig when used by a ServletContainer,
	 * so we must not reuse the same instance across restarts.
	 */
	private static ResourceConfig createResourceConfig() {
		ResourceConfig config = new ResourceConfig();
		config.packages(API_RESOURCE_PACKAGES.toArray(String[]::new));
		config.register(org.glassfish.jersey.media.multipart.MultiPartFeature.class);
		config.register(org.qortal.api.model.ConnectedPeerJacksonWriter.class, 10000);
		config.register(createOpenApiResource());
		config.register(ApiDefinition.class);
		config.register(AnnotationPostProcessor.class);
		return config;
	}

	/**
	 * Configure Swagger to scan only known JAX-RS resource classes from this application.
	 * Without this, swagger-jaxrs2 2.2.x falls back to a broad ClassGraph scan on every
	 * /openapi.json request, which can spawn huge numbers of threads under concurrent refreshes.
	 */
	private static OpenApiResource createOpenApiResource() {
		SwaggerConfiguration swaggerConfig = new SwaggerConfiguration()
				.resourcePackages(new LinkedHashSet<>(API_RESOURCE_PACKAGES))
				.resourceClasses(new LinkedHashSet<>(OPENAPI_RESOURCE_CLASS_NAMES));

		return (OpenApiResource) new OpenApiResource().openApiConfiguration(swaggerConfig);
	}

	private static Set<String> discoverOpenApiResourceClassNames() {
		ResourceConfig discoveryConfig = new ResourceConfig();
		discoveryConfig.packages(API_RESOURCE_PACKAGES.toArray(String[]::new));

		return discoveryConfig.getClasses().stream()
				.filter(clazz -> clazz.isAnnotationPresent(javax.ws.rs.Path.class))
				.map(Class::getName)
				.collect(Collectors.toCollection(LinkedHashSet::new));
	}

	/** Add BouncyCastle providers once per JVM; repeated adds would clutter the provider list. */
	private static void ensureProvidersAdded() {
		if (Security.getProvider("BC") == null) {
			Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
		}
		if (Security.getProvider("BCJSSE") == null) {
			Security.addProvider(new org.bouncycastle.jsse.provider.BouncyCastleJsseProvider());
		}
	}

	public static final String API_VERSION_HEADER = "X-API-VERSION";

	private ApiService() {
	}

	public static ApiService getInstance() {
		if (instance == null)
			instance = new ApiService();

		return instance;
	}

	/** Returns resource classes; uses a fresh config so no long-lived reference to a locked config is retained. */
	public Iterable<Class<?>> getResources() {
		return List.copyOf(createResourceConfig().getClasses());
	}

	public void setApiKey(ApiKey apiKey) {
		this.apiKey = apiKey;
	}

	public ApiKey getApiKey() {
		return this.apiKey;
	}


	public void start() {
		synchronized (lifecycleLock) {
			startInternal();
		}
	}

	private void startInternal() {
		//System.setProperty("javax.net.debug", "ssl,handshake");
		try {
			ensureProvidersAdded();

			String keystorePathname = Settings.getInstance().getSslKeystorePathname();
			String keystorePassword = Settings.getInstance().getSslKeystorePassword();

			if (keystorePathname != null && keystorePassword != null) {
				if (!Files.isReadable(Path.of(keystorePathname))) {
					SslUtils.generateSsl();
				} else {
					// Validate keystore is loadable (not corrupt/truncated). Use default PKCS12 provider
					// so validation matches Jetty's load and works on Oracle Java SE (BC can fail JCE auth).
					try {
						KeyStore keyStore = KeyStore.getInstance("PKCS12");
						try (InputStream in = Files.newInputStream(Path.of(keystorePathname))) {
							keyStore.load(in, keystorePassword.toCharArray());
						}
					} catch (Exception e) {
						LOGGER.warn("Keystore invalid or corrupt ({}), regenerating: {}", keystorePathname, e.getMessage());
						try {
							Files.delete(Path.of(keystorePathname));
						} catch (Exception e2) {
							LOGGER.warn("Could not delete corrupt keystore: {}", e2.getMessage());
						}
						SslUtils.generateSsl();
					}
				}

				// 2. SSL Context Factory
				SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
				sslContextFactory.setKeyStorePath(keystorePathname);
				sslContextFactory.setKeyStorePassword(keystorePassword);
				sslContextFactory.setKeyStoreType("PKCS12");
				// Do not set KeyStore provider: use default (SunJSSE) so keystore load works on Oracle
				// Java SE where repackaged BC fails "JCE cannot authenticate the provider BC" during load.

				// Use SunJSSE for ALPN support
				sslContextFactory.setProvider("SunJSSE");
				sslContextFactory.setProtocol("TLS");

				// Disable Hostname Verification (Fixes "localhost" ALPN issues)
				sslContextFactory.setEndpointIdentificationAlgorithm(null);

				// We explicitly tell the server: "You MUST use a cipher that allows HTTP/2"
				sslContextFactory.setIncludeCipherSuites("TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256");
				// HTTP/2 strict cipher compliance
				sslContextFactory.setCipherComparator(HTTP2Cipher.COMPARATOR);
				sslContextFactory.setUseCipherSuitesOrder(true);

				this.server = new Server();

				// 3. HTTP Configuration
				HttpConfiguration httpConfig = new HttpConfiguration();
				httpConfig.setSecureScheme("https");
				httpConfig.setSecurePort(Settings.getInstance().getApiPort());

				// HTTPS Config (adds Strict Transport Security, etc.)
				HttpConfiguration httpsConfig = new HttpConfiguration(httpConfig);
				httpsConfig.addCustomizer(new SecureRequestCustomizer());

				// 4. Connection Factories
				// HTTP/1.1 (The Workhorse)
				HttpConnectionFactory http1 = new HttpConnectionFactory(httpsConfig);

				// HTTP/2 (The Goal)
				HTTP2ServerConnectionFactory h2 = new HTTP2ServerConnectionFactory(httpsConfig);

				// ALPN (The Negotiator: "h2" or "http/1.1"?)
				ALPNServerConnectionFactory alpn = new ALPNServerConnectionFactory();
				alpn.setDefaultProtocol(http1.getProtocol());

				// SSL (The Encryptor)
				// It hands off to ALPN once decryption is done
				SslConnectionFactory ssl = new SslConnectionFactory(sslContextFactory, alpn.getProtocol());

				// Peeks at the first bytes. - If TLS -> sends to 'ssl';  Plain -> sends to 'http1'.
				OptionalSslConnectionFactory optionalSsl = new OptionalSslConnectionFactory(ssl, http1.getProtocol());

				// 5. The Unified Connector
				// Order: Detector -> SSL -> ALPN -> H2 -> HTTP1
				ServerConnector sslConnector = new ServerConnector(this.server,
						optionalSsl, ssl, alpn, h2, http1);

				sslConnector.setPort(Settings.getInstance().getApiPort());
				sslConnector.setHost(Network.getInstance().getBindAddress());

				this.server.addConnector(sslConnector);

			} else {
				// Non-SSL Mode
				InetAddress bindAddr = InetAddress.getByName(Network.getInstance().getBindAddress());
				InetSocketAddress endpoint = new InetSocketAddress(bindAddr, Settings.getInstance().getApiPort());
				this.server = new Server(endpoint);
			}

			// Error handler
			ErrorHandler errorHandler = new ApiErrorHandler();
			this.server.setErrorHandler(errorHandler);

			// Request logging
			if (Settings.getInstance().isApiLoggingEnabled()) {
				RequestLogWriter logWriter = new RequestLogWriter("API-requests.log");
				logWriter.setAppend(true);
				logWriter.setTimeZone("UTC");
				RequestLog requestLog = new CustomRequestLog(logWriter, CustomRequestLog.EXTENDED_NCSA_FORMAT);
				this.server.setRequestLog(requestLog);
			}

			// IP address based access control
			InetAccessHandler accessHandler = new InetAccessHandler();
			for (String pattern : Settings.getInstance().getApiWhitelist()) {
				accessHandler.include(pattern);
			}
			this.server.setHandler(accessHandler);

			// URL rewriting
			RewriteHandler rewriteHandler = new RewriteHandler();
			accessHandler.setHandler(rewriteHandler);

			// Context
			ServletContextHandler context = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
			context.setContextPath("/");
			// Allow multipart/form-data up to 10 MB (e.g. /chunk uploads); Jetty 10 default is 200 KB
			context.setMaxFormContentSize(10 * 1024 * 1024);
			rewriteHandler.setHandler(context);

			// Cross-origin resource sharing
			FilterHolder corsFilterHolder = new FilterHolder(CrossOriginFilter.class);
			corsFilterHolder.setInitParameter(CrossOriginFilter.ALLOWED_ORIGINS_PARAM, "*");
			corsFilterHolder.setInitParameter(CrossOriginFilter.ALLOWED_METHODS_PARAM, "GET, POST, DELETE");
			corsFilterHolder.setInitParameter(CrossOriginFilter.CHAIN_PREFLIGHT_PARAM, "false");
			context.addFilter(corsFilterHolder, "/*", null);

			// API servlet - fresh config per server lifecycle (Jersey locks config on use)
			ServletContainer container = new ServletContainer(createResourceConfig());
			ServletHolder apiServlet = new ServletHolder(container);
			apiServlet.setInitOrder(1);
			context.addServlet(apiServlet, "/*");

			if (Settings.getInstance().isApiDocumentationEnabled()) {
				// Swagger-UI static content
				ClassLoader loader = this.getClass().getClassLoader();
				ServletHolder swaggerUIServlet = new ServletHolder("static-swagger-ui", DefaultServlet.class);
				swaggerUIServlet.setInitParameter("resourceBase", loader.getResource("resources/swagger-ui/").toString());
				swaggerUIServlet.setInitParameter("dirAllowed", "true");
				swaggerUIServlet.setInitParameter("pathInfoOnly", "true");
				context.addServlet(swaggerUIServlet, "/api-documentation/*");

				rewriteHandler.addRule(new RedirectPatternRule("", "/api-documentation/")); // redirect empty path to API docs
				rewriteHandler.addRule(new RedirectPatternRule("/api-documentation", "/api-documentation/")); // redirect to add trailing slash if missing
			} else {
				// Simple pages that explains that API documentation is disabled
				ClassLoader loader = this.getClass().getClassLoader();
				ServletHolder swaggerUIServlet = new ServletHolder("api-docs-disabled", DefaultServlet.class);
				swaggerUIServlet.setInitParameter("resourceBase", loader.getResource("api-docs-disabled/").toString());
				swaggerUIServlet.setInitParameter("dirAllowed", "true");
				swaggerUIServlet.setInitParameter("pathInfoOnly", "true");
				context.addServlet(swaggerUIServlet, "/api-documentation/*");

				rewriteHandler.addRule(new RedirectPatternRule("", "/api-documentation/")); // redirect empty path to API docs
				rewriteHandler.addRule(new RedirectPatternRule("/api-documentation", "/api-documentation/")); // redirect to add trailing slash if missing
			}

			JettyWebSocketServletContainerInitializer.configure(context, null);
			context.addServlet(AdminStatusWebSocket.class, "/websockets/admin/status");
			context.addServlet(BlocksWebSocket.class, "/websockets/blocks");
			context.addServlet(DataMonitorSocket.class, "/websockets/datamonitor");
			context.addServlet(ActiveChatsWebSocket.class, "/websockets/chat/active/*");
			context.addServlet(ChatMessagesWebSocket.class, "/websockets/chat/messages");
			context.addServlet(UnsignedFeesSocket.class, "/websockets/crosschain/unsignedfees");
			context.addServlet(TradeOffersWebSocket.class, "/websockets/crosschain/tradeoffers");
			context.addServlet(TradeBotWebSocket.class, "/websockets/crosschain/tradebot");
			context.addServlet(TradePresenceWebSocket.class, "/websockets/crosschain/tradepresence");

			// Deprecated
			context.addServlet(PresenceWebSocket.class, "/websockets/presence");

			// Start server
			this.server.start();
		} catch (Exception e) {
			// Failed to start
			System.err.println("Failed to start API");
			e.printStackTrace();
			throw new RuntimeException("Failed to start API", e);

		}
	}

	public void stop() {
		synchronized (lifecycleLock) {
			stopInternal();
		}
	}

	/**
	 * Stop the server and wait for it to fully release the port using Jetty's lifecycle listener.
	 * Must be called with lifecycleLock held.
	 */
	private void stopInternal() {
		if (this.server == null) {
			return;
		}
		Server s = this.server;
		this.server = null;
		try {
			CountDownLatch latch = new CountDownLatch(1);
			s.addEventListener(new LifeCycle.Listener() {
				@Override
				public void lifeCycleStopped(LifeCycle event) {
					latch.countDown();
				}
			});
			s.stop();
			if (!latch.await(15, TimeUnit.SECONDS)) {
				LOGGER.warn("API server did not stop within 15 seconds");
			}
		} catch (Exception e) {
			LOGGER.error("Failed to stop API server: {}", e.getMessage(), e);
		}
	}

	/**
	 * Restart the API service to reload SSL certificates or other configuration.
	 * If the new server fails to start, attempts to bring the old server back up so the API is not permanently down.
	 */
	public void restart() throws Exception {
		synchronized (lifecycleLock) {
			Server oldServer = this.server;
			try {
				stopInternal();
				startInternal();
			} catch (Exception e) {
				LOGGER.error("Restart failed, attempting recovery", e);
				if (oldServer != null && oldServer.isStopped()) {
					try {
						oldServer.start();
						this.server = oldServer;
						LOGGER.info("Recovery successful: previous API server restored");
					} catch (Exception ex) {
						LOGGER.error("Recovery failed", ex);
						throw new RuntimeException("Restart and recovery failed", e);
					}
				} else {
					throw new RuntimeException("Restart failed", e);
				}
			}
		}
	}

	public static int getApiVersion(HttpServletRequest request) {
		// Get API version
		String apiVersionString = request.getHeader(API_VERSION_HEADER);
		if (apiVersionString == null) {
			// Try query string - this is needed to avoid a CORS preflight. See: https://stackoverflow.com/a/43881141
			apiVersionString = request.getParameter("apiVersion");
		}

		int apiVersion = 1;
		if (apiVersionString != null) {
			apiVersion = Integer.parseInt(apiVersionString);
		}
		return apiVersion;
	}

}
