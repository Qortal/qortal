package org.qortal.test.arbitrary;

import org.junit.Before;
import org.junit.Test;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.controller.arbitrary.ArbitraryDataHostMonitor;
import org.qortal.data.transaction.PaymentTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.test.common.Common;
import org.qortal.test.common.TransactionUtils;
import org.qortal.test.common.transaction.TestTransaction;
import org.qortal.utils.Base58;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ArbitraryDataHostMonitorTests extends Common {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@Test
	public void testFetchTransactionsInBatches() throws Exception {
		List<byte[]> signatures = new ArrayList<>();

		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");

			// Seven transactions: more than one batch of three, and not a multiple of it
			for (int i = 0; i < 7; i++) {
				PaymentTransactionData paymentData = new PaymentTransactionData(TestTransaction.generateBase(alice), bob.getAddress(), 1L);
				TransactionUtils.signAndMint(repository, paymentData, alice);
				signatures.add(paymentData.getSignature());
			}
		}

		Set<String> expected = signatures.stream().map(Base58::encode).collect(Collectors.toSet());
		assertEquals(7, expected.size());

		// Unknown signatures mixed in must simply be skipped
		Random random = new Random();
		byte[] unknown1 = new byte[64];
		byte[] unknown2 = new byte[64];
		random.nextBytes(unknown1);
		random.nextBytes(unknown2);

		List<byte[]> lookup = new ArrayList<>(signatures);
		lookup.add(2, unknown1);
		lookup.add(unknown2);

		ArbitraryDataHostMonitor monitor = ArbitraryDataHostMonitor.getInstance();

		for (int batchSize : new int[] { 1, 3, 7, 100 }) {
			List<TransactionData> found = monitor.fetchTransactionsInBatches(lookup, batchSize);
			Set<String> foundSignatures = found.stream().map(data -> Base58.encode(data.getSignature())).collect(Collectors.toSet());

			assertEquals("batch size " + batchSize, expected.size(), found.size());
			assertEquals("batch size " + batchSize, expected, foundSignatures);
		}

		assertTrue(monitor.fetchTransactionsInBatches(Collections.emptyList(), 3).isEmpty());
	}

}
