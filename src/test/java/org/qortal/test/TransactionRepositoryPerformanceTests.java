package org.qortal.test;

import org.junit.Before;
import org.junit.Test;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.data.transaction.TransactionData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.test.common.AccountUtils;
import org.qortal.test.common.BlockUtils;
import org.qortal.test.common.Common;
import org.qortal.transaction.Transaction.TransactionType;

import java.util.List;

import static org.junit.Assert.*;

public class TransactionRepositoryPerformanceTests extends Common {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@Test
	public void testTransactionCacheAndBatchLoading() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");

			// Mint block and perform transactions
			BlockUtils.mintBlock(repository);
			AccountUtils.pay(repository, alice, bob.getAddress(), 100L);
			AccountUtils.pay(repository, bob, alice.getAddress(), 50L);

			List<byte[]> signatures = repository.getTransactionRepository().getSignaturesMatchingCriteria(TransactionType.PAYMENT, null, null, null);
			assertTrue("Should have payment signatures", signatures.size() >= 2);

			byte[] sig1 = signatures.get(0);

			// First lookup - populates cache
			TransactionData fetched1 = repository.getTransactionRepository().fromSignature(sig1);
			assertNotNull("Fetched transaction 1 should not be null", fetched1);
			assertArrayEquals("Signatures should match", sig1, fetched1.getSignature());

			// Second lookup - cache hit
			TransactionData cached1 = repository.getTransactionRepository().fromSignature(sig1);
			assertSame("Cached transaction should be identical instance from LRU cache", fetched1, cached1);

			// Test batch lookup via fromSignatures
			List<TransactionData> batchFetched = repository.getTransactionRepository().fromSignatures(signatures);
			assertEquals(signatures.size(), batchFetched.size());
		}
	}
}
