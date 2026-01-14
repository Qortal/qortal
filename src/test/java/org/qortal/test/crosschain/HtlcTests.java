package org.qortal.test.crosschain;

import com.google.common.primitives.Longs;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.qortal.crosschain.Bitcoin;
import org.qortal.crosschain.BitcoinyHTLC;
import org.qortal.crosschain.ForeignBlockchainException;
import org.qortal.crosschain.Litecoin;
import org.qortal.crypto.Crypto;
import org.qortal.repository.DataException;
import org.qortal.test.common.Common;

import static org.junit.Assert.*;

public class HtlcTests extends Common {

	private Bitcoin bitcoin;
	private Litecoin litecoin;

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings(); // TestNet3
		bitcoin = Bitcoin.getInstance();
		litecoin = Litecoin.getInstance();
	}

	@After
	public void afterTest() {
		Bitcoin.resetForTesting();
		litecoin = null;
	}

	@Test
	public void testFindHtlcSecret() throws ForeignBlockchainException {
		// This actually exists on TEST3 but can take a while to fetch
		String p2shAddress = "2N8WCg52ULCtDSMjkgVTm5mtPdCsUptkHWE";

		byte[] expectedSecret = "This string is exactly 32 bytes!".getBytes();
		byte[] secret = BitcoinyHTLC.findHtlcSecret(bitcoin, p2shAddress);

		assertNotNull(secret);
		assertArrayEquals("secret incorrect", expectedSecret, secret);
	}

	@Test
	@Ignore(value = "Doesn't work, to be fixed later")
	public void testHtlcSecretCaching() throws ForeignBlockchainException {
		String p2shAddress = "2N8WCg52ULCtDSMjkgVTm5mtPdCsUptkHWE";
		byte[] expectedSecret = "This string is exactly 32 bytes!".getBytes();

		do {
			// We need to perform fresh setup for 1st test
			Bitcoin.resetForTesting();
			litecoin = Litecoin.getInstance();

			long now = System.currentTimeMillis();
			long timestampBoundary = now / 30_000L;

			byte[] secret1 = BitcoinyHTLC.findHtlcSecret(litecoin, p2shAddress);
			long executionPeriod1 = System.currentTimeMillis() - now;

			assertNotNull(secret1);
			assertArrayEquals("secret1 incorrect", expectedSecret, secret1);

			assertTrue("1st execution period should not be instant!", executionPeriod1 > 10);

			byte[] secret2 = BitcoinyHTLC.findHtlcSecret(litecoin, p2shAddress);
			long executionPeriod2 = System.currentTimeMillis() - now - executionPeriod1;

			assertNotNull(secret2);
			assertArrayEquals("secret2 incorrect", expectedSecret, secret2);

			// Test is only valid if we've called within same timestampBoundary
			if (System.currentTimeMillis() / 30_000L != timestampBoundary)
				continue;

			assertArrayEquals(secret1, secret2);

			assertTrue("2nd execution period should be effectively instant!", executionPeriod2 < 10);
		} while (false);
	}

	@Test
	public void testDetermineHtlcStatus() throws ForeignBlockchainException {
		// This actually exists on TEST3 but can take a while to fetch
		String p2shAddress = "2N8WCg52ULCtDSMjkgVTm5mtPdCsUptkHWE";

		BitcoinyHTLC.Status htlcStatus = BitcoinyHTLC.determineHtlcStatus(litecoin.getBlockchainProvider(), p2shAddress, 1L);
		assertNotNull(htlcStatus);

		System.out.println(String.format("HTLC %s status: %s", p2shAddress, htlcStatus.name()));
	}

	@Test
	public void testHtlcStatusCaching() throws ForeignBlockchainException {
		do {
			// We need to perform fresh setup for 1st test
			Bitcoin.resetForTesting();
			litecoin = Litecoin.getInstance();

			long now = System.currentTimeMillis();
			long timestampBoundary = now / 30_000L;

			// Won't ever exist
			String p2shAddress = litecoin.deriveP2shAddress(Crypto.hash160(Longs.toByteArray(now)));

			BitcoinyHTLC.Status htlcStatus1 = BitcoinyHTLC.determineHtlcStatus(litecoin.getBlockchainProvider(), p2shAddress, 1L);
			long executionPeriod1 = System.currentTimeMillis() - now;

			assertNotNull(htlcStatus1);
			assertTrue("1st execution period should not be instant!", executionPeriod1 > 10);

			BitcoinyHTLC.Status htlcStatus2 = BitcoinyHTLC.determineHtlcStatus(litecoin.getBlockchainProvider(), p2shAddress, 1L);
			long executionPeriod2 = System.currentTimeMillis() - now - executionPeriod1;

			assertNotNull(htlcStatus2);
			assertEquals(htlcStatus1, htlcStatus2);

			// Test is only valid if we've called within same timestampBoundary
			if (System.currentTimeMillis() / 30_000L != timestampBoundary)
				continue;

			assertTrue("2nd execution period should be effectively instant!", executionPeriod2 < 10);
		} while (false);
	}

}
