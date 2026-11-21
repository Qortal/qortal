package org.qortal.crosschain;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ElectrumXRpcErrorTests {

	@Test
	public void testMethodNotFoundCodeIsUnsupported() {
		assertTrue(ElectrumX.isUnsupportedRpcMethodError(-32601L, "unrelated message"));
	}

	@Test
	public void testKnownUnsupportedMethodMessages() {
		assertTrue(ElectrumX.isUnsupportedRpcMethodError(null, "unknown method \"blockchain.scripthash.get_history\""));
		assertTrue(ElectrumX.isUnsupportedRpcMethodError(null, "Method not found"));
	}

	@Test
	public void testOtherRpcErrorsRemainTerminal() {
		assertFalse(ElectrumX.isUnsupportedRpcMethodError(-5L, "No such mempool transaction"));
		assertFalse(ElectrumX.isUnsupportedRpcMethodError(null, null));
	}
}
