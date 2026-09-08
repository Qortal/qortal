package org.qortal.test.crosschain;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.servlet.ServletContainer;
import org.junit.Test;
import org.qortal.api.ApiService;
import org.qortal.api.model.crosschain.LocalWalletResponse;
import org.qortal.api.model.crosschain.LocalWalletResponseWriter;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

/** Exercise the actual Jersey writer selection, not just ObjectMapper in isolation. */
public class LocalWalletApiTests {
    private static final Map<String, Object> CONTEXT = Map.of(
            "version", 1, "sequence", 4294967295L,
            "recommendedFeePerByte", "10",
            "previousTransactions", Map.of("ab".repeat(32), "01000000"),
            "utxos", List.of(Map.of("path", List.of(0, 0), "value", "1000000000")));
    private static final Map<String, Object> EMPTY = Map.of("utxos", List.of(), "previousTransactions", Map.of());
    private static final List<Map<String, Object>> PLANS = List.of(Map.of("amount", "100001000", "lockTime", 1800000000));

    @Path("/wallet-json-test")
    public static class Resource {
        @GET @Path("/{kind}") @Produces(MediaType.APPLICATION_JSON)
        public LocalWalletResponse get(@PathParam("kind") String kind) {
            if (kind.equals("plans")) return new LocalWalletResponse(PLANS);
            return new LocalWalletResponse(kind.equals("empty") ? EMPTY : CONTEXT);
        }
    }

    @Test
    public void productionWriterRegistrationServesTheExactHubJsonContract() throws Exception {
        var factory = ApiService.class.getDeclaredMethod("createResourceConfig");
        factory.setAccessible(true);
        ResourceConfig production = (ResourceConfig) factory.invoke(null);
        assertTrue("Writer must be registered in the running API", production.isRegistered(LocalWalletResponseWriter.class));
        // Use production writer registrations, without starting blockchain services.
        ResourceConfig config = new ResourceConfig(Resource.class);
        for (Class<?> component : production.getClasses())
            if (javax.ws.rs.ext.MessageBodyWriter.class.isAssignableFrom(component)) config.register(component);
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setHost("127.0.0.1");
        connector.setPort(0);
        server.addConnector(connector);
        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        context.addServlet(new ServletHolder(new ServletContainer(config)), "/*");
        server.setHandler(context);
        try {
            server.start();
            HttpClient client = HttpClient.newHttpClient();
            ObjectMapper mapper = new ObjectMapper();
            for (var entry : Map.<String, Object>of("context", CONTEXT, "empty", EMPTY, "plans", PLANS).entrySet()) {
                URI uri = URI.create("http://127.0.0.1:" + connector.getLocalPort() + "/wallet-json-test/" + entry.getKey());
                var response = client.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(response.body(), 200, response.statusCode());
                assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
                assertEquals(mapper.valueToTree(entry.getValue()), mapper.readTree(response.body()));
            }
        } finally {
            server.stop();
        }
    }

    @Test
    public void everyNewEndpointUsesTheDedicatedResponse() throws Exception {
        for (String coin : List.of("Bitcoin", "Litecoin", "Dogecoin", "Digibyte", "Ravencoin")) {
            Class<?> resource = Class.forName("org.qortal.api.resource.CrossChain" + coin + "Resource");
            assertEquals(LocalWalletResponse.class, resource.getMethod("publicSpendContext",
                    org.qortal.api.model.crosschain.ForeignWalletRequest.class).getReturnType());
        }
        assertEquals(LocalWalletResponse.class,
                org.qortal.api.resource.CrossChainTradeBotResource.class.getMethod("prepareLocalFunding",
                        org.qortal.api.model.crosschain.LocalTradeRequest.class).getReturnType());
    }
}
