package org.qortal.api.resource;

import org.junit.Test;
import org.bitcoinj.core.Bech32;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.servlet.ServletContainer;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.Assert.*;

public class CrossChainPirateChainResourceTests {

	@Test
	public void initializationRejectsConflictingOrInvalidChoicesBeforeOpeningAWallet() throws Exception {
		CrossChainPirateChainResource resource = new CrossChainPirateChainResource();
		var request = org.mockito.Mockito.mock(javax.servlet.http.HttpServletRequest.class);
		org.mockito.Mockito.when(request.getLocale()).thenReturn(java.util.Locale.ENGLISH);
		var requestField = CrossChainPirateChainResource.class.getDeclaredField("request");
		requestField.setAccessible(true);
		requestField.set(resource, request);
		try (var security = org.mockito.Mockito.mockStatic(org.qortal.api.Security.class)) {
			for (int choice = 0; choice < 4; choice++) {
				var body = new org.qortal.api.model.crosschain.PirateChainWalletInitializationRequest();
				body.entropy58 = "unfunded-fixture";
				if (choice == 1) body.entropy58 = null;
				if (choice == 2) body.birthday = 0;
				if (choice == 3) { body.birthday = 2000000; body.useCurrentHeight = true; }
				try {
					resource.walletInitialization("fixture", choice == 0 ? null : body);
					fail("Expected invalid criteria");
				} catch (org.qortal.api.ApiException error) {
					assertEquals(org.qortal.api.ApiError.INVALID_CRITERIA.getCode(), error.error);
				}
			}
		}
	}

	@Test
	public void initializationInspectionPassesNoImplicitHeightChoiceToController() throws Exception {
		org.qortal.test.common.Common.useDefaultSettings();
		var controller = org.mockito.Mockito.mock(org.qortal.controller.PirateChainWalletController.class);
		var body = new org.qortal.api.model.crosschain.PirateChainWalletInitializationRequest();
		body.entropy58 = "unfunded-fixture";
		org.mockito.Mockito.when(controller.walletInitialization(body.entropy58, null, false))
				.thenReturn(new org.json.JSONObject().put("needsSelection", true));
		try (var security = org.mockito.Mockito.mockStatic(org.qortal.api.Security.class);
			 var pirate = org.mockito.Mockito.mockStatic(org.qortal.crosschain.PirateChain.class);
			 var wallets = org.mockito.Mockito.mockStatic(org.qortal.controller.PirateChainWalletController.class)) {
			pirate.when(org.qortal.crosschain.PirateChain::getInstance)
					.thenReturn(org.mockito.Mockito.mock(org.qortal.crosschain.PirateChain.class));
			wallets.when(org.qortal.controller.PirateChainWalletController::getInstance).thenReturn(controller);
			assertTrue(new org.json.JSONObject(new CrossChainPirateChainResource()
					.walletInitialization("fixture", body)).getBoolean("needsSelection"));
			org.mockito.Mockito.verify(controller).walletInitialization(body.entropy58, null, false);
			org.mockito.Mockito.verifyNoMoreInteractions(controller);
		}
	}

	@Test
	public void disabledWalletFeeQuoteUsesTheExistingForeignChainApiError() throws Exception {
		org.qortal.test.common.Common.useDefaultSettings();
		try (var pirate = org.mockito.Mockito.mockStatic(org.qortal.crosschain.PirateChain.class)) {
			try {
				new CrossChainPirateChainResource().getTradeFees();
				fail("Expected the disabled-wallet API error");
			} catch (org.qortal.api.ApiException error) {
				assertEquals(org.qortal.api.ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE.getCode(), error.error);
				assertTrue(error.message.contains("disabled"));
			}
		}
	}

	@Test
	public void disabledWalletPreparationUsesTheExistingForeignChainApiError() throws Exception {
		org.qortal.test.common.Common.useDefaultSettings();
		try (var pirate = org.mockito.Mockito.mockStatic(org.qortal.crosschain.PirateChain.class,
				org.mockito.Mockito.CALLS_REAL_METHODS);
			 var security = org.mockito.Mockito.mockStatic(org.qortal.api.Security.class)) {
			pirate.when(org.qortal.crosschain.PirateChain::getInstance).thenReturn(null);
			var preparation = new org.qortal.api.model.crosschain.PirateChainTradePreparationRequest();
			preparation.entropy58 = "public-unfunded-fixture";
			preparation.requiredAmount = 1;
			try {
				new CrossChainPirateChainResource().prepareTrade("fixture", preparation);
				fail("Expected the disabled-wallet API error");
			} catch (org.qortal.api.ApiException error) {
				assertEquals(org.qortal.api.ApiError.FOREIGN_BLOCKCHAIN_NETWORK_ISSUE.getCode(), error.error);
				assertTrue(error.message.contains("disabled"));
			}
		}
	}

	@Test
	public void tradeFeeQuoteSerializesAtomicAmountsWithoutAWallet() throws Exception {
		org.qortal.test.common.Common.useDefaultSettings();
		Server server = new Server(new InetSocketAddress("127.0.0.1", 0));
		ServletContextHandler handler = new ServletContextHandler();
		handler.setContextPath("/");
		handler.addServlet(new ServletHolder(new ServletContainer(new ResourceConfig(CrossChainPirateChainResource.class))), "/*");
		server.setHandler(handler);
		try {
			server.start();
			int port = ((ServerConnector) server.getConnectors()[0]).getLocalPort();
			var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/crosschain/arrr/tradefees")).GET().build(), HttpResponse.BodyHandlers.ofString());
			assertEquals(200, response.statusCode());
			var json = new org.json.JSONObject(response.body());
			assertEquals(10000, json.getLong("fundingFee")); assertEquals(10000, json.getLong("refundFee"));
			assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
		} finally { server.stop(); server.join(); }
	}

	@Test
	public void publicValidatorReturnsJsonBooleanLiteralsOverHttp() throws Exception {
		Server server = new Server(new InetSocketAddress("127.0.0.1", 0));
		ServletContextHandler handler = new ServletContextHandler();
		handler.setContextPath("/");
		handler.addServlet(new ServletHolder(new ServletContainer(
				new ResourceConfig(CrossChainPirateChainResource.class))), "/*");
		server.setHandler(handler);
		try {
			server.start();
			int port = ((ServerConnector) server.getConnectors()[0]).getLocalPort();
			String base = "http://127.0.0.1:" + port + "/crosschain/arrr/validateaddress";
			String sapling = "zs1mj80tkyjqjhc84aquetymwgrasxjdkkft0qcn5xtp2p9r42vs5ernyv4nzgufmz6xzuqvw20w9z";
			String ironwood = "pirate1lyp0g5438qkp9s5me5epku9ak9k0dxqsfyt9z894zym8qw38sps488ljyee27yw29vc2cyj8mse";
			assertValidationHttp(base + "?address=" + sapling, "true");
			assertValidationHttp(base + "?address=" + ironwood, "true");
			assertValidationHttp(base + "?address=" + ironwood.substring(0, 81) + "q", "false");
			assertValidationHttp(base, "false");
		} finally {
			server.stop();
			server.join();
		}
	}

	private static void assertValidationHttp(String url, String expected) throws Exception {
		HttpResponse<String> response = HttpClient.newHttpClient().send(
				HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
		assertEquals(200, response.statusCode());
		assertEquals(expected, response.body());
		assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
	}

	@Test
	public void publicValidatorAcceptsBothShieldedPoolsWithoutInitializingAWallet() {
		CrossChainPirateChainResource resource = new CrossChainPirateChainResource();
		String sapling = "zs1mj80tkyjqjhc84aquetymwgrasxjdkkft0qcn5xtp2p9r42vs5ernyv4nzgufmz6xzuqvw20w9z";
		String ironwood = "pirate1lyp0g5438qkp9s5me5epku9ak9k0dxqsfyt9z894zym8qw38sps488ljyee27yw29vc2cyj8mse";
		assertEquals("true", resource.validateAddress(sapling));
		assertEquals("true", resource.validateAddress(ironwood));
		assertEquals("false", resource.validateAddress(ironwood.substring(0, 81) + "q"));
		assertEquals("false", resource.validateAddress("P" + ironwood.substring(1)));
		assertEquals("false", resource.validateAddress(Bech32.encode("pirate-test", Bech32.decode(ironwood).data)));
		assertEquals("false", resource.validateAddress(null));
		assertEquals("false", resource.validateAddress(""));
	}

	@Test
	public void testRequestLogRedactsApiKeyQueryParameter() {
		assertEquals("json=true&apiKey=<redacted>",
				CrossChainPirateChainResource.redactQueryString("json=true&apiKey=test-key"));
		assertEquals("foo=bar&apikey=<redacted>&empty=",
				CrossChainPirateChainResource.redactQueryString("foo=bar&apikey=another-key&empty="));
		assertEquals("json=true", CrossChainPirateChainResource.redactQueryString("json=true"));
	}
}
