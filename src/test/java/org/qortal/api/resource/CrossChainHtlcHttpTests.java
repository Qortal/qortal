package org.qortal.api.resource;

import io.swagger.v3.jaxrs2.Reader;
import io.swagger.v3.oas.models.OpenAPI;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.servlet.ServletContainer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortal.api.model.crosschain.HtlcRecoveryApproval;
import org.qortal.api.ApiService;

import javax.ws.rs.Path;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Set;

import static org.junit.Assert.*;

/** Exercise the real Jersey entity readers and Swagger scanner, without accessing a wallet. */
public class CrossChainHtlcHttpTests {
    private Server server;
    private String base;
    private final HttpClient client = HttpClient.newHttpClient();
    private final CapturingResource resource = new CapturingResource();

    @Before public void startServer() throws Exception {
        org.qortal.test.common.Common.useDefaultSettings();
        server = new Server(new InetSocketAddress("127.0.0.1", 0));
        ServletContextHandler handler = new ServletContextHandler();
        handler.setContextPath("/");
        var factory = ApiService.class.getDeclaredMethod("createResourceConfig");
        factory.setAccessible(true);
        ResourceConfig production = (ResourceConfig) factory.invoke(null);
        ResourceConfig config = new ResourceConfig().register(resource);
        for (Class<?> component : production.getClasses())
            if (javax.ws.rs.ext.MessageBodyReader.class.isAssignableFrom(component)) config.register(component);
        for (Object component : production.getInstances())
            if (component instanceof io.swagger.v3.jaxrs2.integration.resources.OpenApiResource) config.register(component);
        config.register(ApiDefinition.class).register(AnnotationPostProcessor.class);
        handler.addServlet(new ServletHolder(new ServletContainer(config)), "/*");
        ServletHolder docs = new ServletHolder("static-swagger-ui", DefaultServlet.class);
        docs.setInitParameter("resourceBase", ApiService.class.getClassLoader().getResource("resources/swagger-ui/").toString());
        docs.setInitParameter("pathInfoOnly", "true");
        handler.addServlet(docs, "/api-documentation/*");
        server.setHandler(handler);
        server.start();
        base = "http://127.0.0.1:" + ((ServerConnector) server.getConnectors()[0]).getLocalPort() + "/crosschain/htlc/";
    }

    @Test public void documentationPageLoadsItsServedJsonRequestSchema() throws Exception {
        var docs = get("api-documentation/");
        assertEquals(200, docs.statusCode());
        assertTrue(docs.body().contains("swagger-initializer.js"));
        var initializer = get("api-documentation/swagger-initializer.js");
        assertEquals(200, initializer.statusCode());
        assertTrue(initializer.body().contains("/openapi.json"));
        var spec = get("openapi.json");
        assertEquals(spec.body(), 200, spec.statusCode());
        var json = new org.json.JSONObject(spec.body());
        for (String operation : new String[]{"redeem", "refund"}) {
            var body = json.getJSONObject("paths").getJSONObject("/crosschain/htlc/" + operation + "/{ataddress}")
                    .getJSONObject("post").getJSONObject("requestBody");
            assertFalse(body.optBoolean("required", false));
            assertEquals(Set.of("application/json"), body.getJSONObject("content").keySet());
        }
    }

    private HttpResponse<String> get(String path) throws Exception {
        URI uri = URI.create(base).resolve("/" + path);
        return client.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @After public void stopServer() throws Exception {
        if (server != null) { server.stop(); server.join(); }
    }

    @Test public void swaggerOffersOnlyOptionalJsonApprovalForBothOperations() {
        OpenAPI api = new Reader(new OpenAPI()).read(CrossChainHtlcResource.class);
        for (String operation : new String[]{"redeem", "refund"}) {
            var body = api.getPaths().get("/crosschain/htlc/" + operation + "/{ataddress}").getPost().getRequestBody();
            assertNotNull(body);
            assertFalse(Boolean.TRUE.equals(body.getRequired()));
            assertEquals(Set.of("application/json"), body.getContent().keySet());
            assertTrue(body.getContent().get("application/json").getSchema().get$ref().endsWith("/HtlcRecoveryApproval"));
        }
    }

    @Test public void jsonApprovalBindsAllFieldsOnBothOperations() throws Exception {
        String json = "{\"tradeAddress\":\"synthetic-trade\",\"quoteId\":\"synthetic-quote\",\"expiresAt\":1795226976362}";
        for (String operation : new String[]{"redeem", "refund"}) {
            for (String type : new String[]{"application/json", "application/json; charset=UTF-8"}) {
                var response = post(operation, type, json);
                assertEquals(operation + " / " + type + ": " + response.body(), 200, response.statusCode());
                assertEquals("false", response.body());
                assertEquals(operation, resource.operation);
                assertEquals("synthetic-key", resource.apiKey);
                assertEquals("synthetic-at", resource.atAddress);
                assertNotNull(resource.approval);
                assertEquals("synthetic-trade", resource.approval.tradeAddress);
                assertEquals("synthetic-quote", resource.approval.quoteId);
                assertEquals(1795226976362L, resource.approval.expiresAt);
            }
        }
    }

    @Test public void legacyEmptyPostsRemainUnapprovedOnBothOperations() throws Exception {
        for (String operation : new String[]{"redeem", "refund"}) {
            for (String type : new String[]{null, "application/json", "text/plain", "*/*"}) {
                int calls = resource.calls;
                var response = post(operation, type, "");
                assertEquals(operation + " / " + type + ": " + response.body(), 200, response.statusCode());
                assertEquals(calls + 1, resource.calls);
                assertNull(resource.approval);
            }
        }
    }

    @Test public void unsupportedOrMalformedBodiesNeverReachRecovery() throws Exception {
        for (String operation : new String[]{"redeem", "refund"}) {
            int calls = resource.calls;
            assertEquals(415, post(operation, "*/*", "{}").statusCode());
            assertEquals(415, post(operation, "text/plain", "{}").statusCode());
            assertEquals(400, post(operation, "application/json", "{broken").statusCode());
            assertEquals(400, post(operation, "application/json", "null").statusCode());
            assertEquals(400, post(operation, "application/json", "{} {}").statusCode());
            assertEquals(400, post(operation, "application/json", "{\"expiresAt\":1.5}").statusCode());
            assertEquals(400, post(operation, "application/json", "{\"expiresAt\":null}").statusCode());
            assertEquals(calls, resource.calls);
        }
    }

    private HttpResponse<String> post(String operation, String contentType, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + operation + "/synthetic-at"))
                .header("Accept", "text/plain").header("X-API-KEY", "synthetic-key");
        if (contentType != null) request.header("Content-Type", contentType);
        return client.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Inherits production route/parameter annotations; replaces signing logic with capture only. */
    @Path("/crosschain/htlc")
    public static class CapturingResource extends CrossChainHtlcResource {
        volatile HtlcRecoveryApproval approval;
        volatile String operation, apiKey, atAddress;
        volatile int calls;

        @Override public boolean redeemHtlc(String apiKey, String atAddress, HtlcRecoveryApproval approval) {
            return capture("redeem", apiKey, atAddress, approval);
        }
        @Override public boolean refundHtlc(String apiKey, String atAddress, HtlcRecoveryApproval approval) {
            return capture("refund", apiKey, atAddress, approval);
        }
        private boolean capture(String operation, String apiKey, String atAddress, HtlcRecoveryApproval approval) {
            this.operation = operation; this.apiKey = apiKey; this.atAddress = atAddress; this.approval = approval;
            calls++;
            return false;
        }
    }
}
