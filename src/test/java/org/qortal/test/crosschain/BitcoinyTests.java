package org.qortal.test.crosschain;

import org.bitcoinj.core.InsufficientMoneyException;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortal.crosschain.AddressInfo;
import org.qortal.crosschain.Bitcoiny;
import org.qortal.crosschain.BitcoinyHTLC;
import org.qortal.crosschain.ForeignBlockchainException;
import org.qortal.crosschain.RepairWalletPreview;
import org.qortal.repository.DataException;
import org.qortal.test.common.Common;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

public abstract class BitcoinyTests extends Common {

	protected Bitcoiny bitcoiny;

	protected abstract String getCoinName();

	protected abstract String getCoinSymbol();

	protected abstract Bitcoiny getCoin();

	protected abstract void resetCoinForTesting();

	protected abstract String getDeterministicKey58();

	protected abstract String getDeterministicPublicKey58();

	protected abstract String getRecipient();

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings(); // TestNet3
		bitcoiny = getCoin();
	}

	@After
	public void afterTest() {
		resetCoinForTesting();
		bitcoiny = null;
	}

	@Test
	public void testGetMedianBlockTime() throws ForeignBlockchainException {
		System.out.println(String.format("Starting " + getCoinSymbol() + " instance..."));
		System.out.println(String.format(getCoinSymbol() + " instance started"));

		long before = System.currentTimeMillis();
		System.out.println(String.format(getCoinName() + " median blocktime: %d", bitcoiny.getMedianBlockTime()));
		long afterFirst = System.currentTimeMillis();

		System.out.println(String.format(getCoinName() + " median blocktime: %d", bitcoiny.getMedianBlockTime()));
		long afterSecond = System.currentTimeMillis();

		long firstPeriod = afterFirst - before;
		long secondPeriod = afterSecond - afterFirst;

		System.out.println(String.format("1st call: %d ms, 2nd call: %d ms", firstPeriod, secondPeriod));

		makeGetMedianBlockTimeAssertions(firstPeriod, secondPeriod);
	}

	public void makeGetMedianBlockTimeAssertions(long firstPeriod, long secondPeriod) {
		assertTrue("2nd call should be quicker than 1st", secondPeriod < firstPeriod);
		assertTrue("2nd call should take less than 5 seconds", secondPeriod < 5000L);
	}

	@Test
	public void testFindHtlcSecret() throws ForeignBlockchainException {
		// This actually exists on TEST3 but can take a while to fetch
		String p2shAddress = "2N8WCg52ULCtDSMjkgVTm5mtPdCsUptkHWE";

		byte[] expectedSecret = "This string is exactly 32 bytes!".getBytes();
		byte[] secret = BitcoinyHTLC.findHtlcSecret(bitcoiny, p2shAddress);

		assertNotNull(secret);
		assertTrue("secret incorrect", Arrays.equals(expectedSecret, secret));
	}

	@Test
	public void testBuildSpend() {
		String xprv58 = getDeterministicKey58();

		String recipient = getRecipient();
		long amount = 1000L;

		Transaction transaction = bitcoiny.buildSpend(xprv58, recipient, amount);
		assertNotNull(transaction);

		// Check spent key caching doesn't affect outcome

		transaction = bitcoiny.buildSpend(xprv58, recipient, amount);
		assertNotNull(transaction);
	}
	@Test
	public void testRepair() throws ForeignBlockchainException, InsufficientMoneyException {
		String xprv58 = getDeterministicKey58();

		String transaction = bitcoiny.repairOldWallet(xprv58);

		assertNotNull(transaction);
		Sha256Hash.wrap(transaction);
	}

	@Test
	public void testRepairPreview() throws ForeignBlockchainException {
		String xpub58 = getDeterministicPublicKey58();

		RepairWalletPreview preview = bitcoiny.previewRepairOldWallet(xpub58);

		assertNotNull(preview);
		assertTrue(preview.getOldBalance() >= 0L);
		assertTrue(preview.getCurrentBalance() >= 0L);
		assertTrue(preview.getMissingBalance() >= 0L);
		assertTrue(preview.getEstimatedFee() >= 0L);
		assertTrue(preview.getDustThreshold() >= 0L);
		assertTrue(preview.getAddressCountOld() > 0);
		assertTrue(preview.getAddressCountCurrent() > 0);
		assertTrue(preview.getMissingUtxoCount() >= 0);
	}

	@Test
	public void testGetWalletBalance() throws ForeignBlockchainException {
		String xprv58 = getDeterministicKey58();

		Long balance = bitcoiny.getWalletBalance(xprv58);

		assertNotNull(balance);

		System.out.println(bitcoiny.format(balance));

		// Check spent key caching doesn't affect outcome

		Long repeatBalance = bitcoiny.getWalletBalance(xprv58);

		assertNotNull(repeatBalance);

		System.out.println(bitcoiny.format(repeatBalance));

		assertEquals(balance, repeatBalance);
	}

	@Test
	public void testGetUnusedReceiveAddress() throws ForeignBlockchainException {
		String xprv58 = getDeterministicKey58();

		String address = bitcoiny.getUnusedReceiveAddress(xprv58);

		assertNotNull(address);

		System.out.println(address);
	}

	@Test
	public void testGenerateRootKeyForTesting() {

		String rootKey = BitcoinyTestsUtils.generateBip32RootKey( this.bitcoiny.getNetworkParameters() );

		System.out.println(String.format(getCoinName() + " generated BIP32 Root Key: " + rootKey));

	}

	@Test
	public void testGetWalletAddresses() throws ForeignBlockchainException {

		String xprv58 = getDeterministicKey58();

		Set<String> addresses = this.bitcoiny.getWalletAddresses(xprv58);

		System.out.println( "root key = " + xprv58 );
		System.out.println( "keys ...");
		addresses.stream().forEach(System.out::println);
	}

	@Test
	public void testWalletAddressInfos() throws ForeignBlockchainException {

		String key58 = getDeterministicPublicKey58();

		List<AddressInfo> addressInfos = this.bitcoiny.getWalletAddressInfos(key58);

		System.out.println("address count = " + addressInfos.size() );
		System.out.println( "address infos ..." );
		addressInfos.forEach( System.out::println );
	}

	@Test
	public void testWalletSpendingCandidateAddresses() throws ForeignBlockchainException {

		String xpub58 = getDeterministicPublicKey58();

		List<String> candidateAddresses = this.bitcoiny.getSpendingCandidateAddresses(xpub58);

		System.out.println("candidate address count = " + candidateAddresses.size() );
		System.out.println( "candidate addresses ..." );
		candidateAddresses.forEach( System.out::println );
	}
}
