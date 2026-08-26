package org.qortal.api.resource;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class CrossChainPirateChainResourceTests {

	@Test
	public void testRequestLogRedactsApiKeyQueryParameter() {
		assertEquals("json=true&apiKey=<redacted>",
				CrossChainPirateChainResource.redactQueryString("json=true&apiKey=test-key"));
		assertEquals("foo=bar&apikey=<redacted>&empty=",
				CrossChainPirateChainResource.redactQueryString("foo=bar&apikey=another-key&empty="));
		assertEquals("json=true", CrossChainPirateChainResource.redactQueryString("json=true"));
	}
}
