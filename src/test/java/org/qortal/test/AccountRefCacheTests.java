package org.qortal.test;

import org.junit.Before;
import org.junit.Test;
import org.qortal.account.Account;
import org.qortal.account.AccountRefCache;
import org.qortal.account.PublicKeyAccount;
import org.qortal.data.transaction.PaymentTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.test.common.BlockUtils;
import org.qortal.test.common.Common;
import org.qortal.test.common.TestAccount;
import org.qortal.test.common.TransactionUtils;
import org.qortal.test.common.transaction.TestTransaction;

import java.util.Arrays;
import java.util.Random;

import static org.junit.Assert.*;

public class AccountRefCacheTests extends Common {

	private static final Random RANDOM = new Random();

	@Before
	public void before() throws DataException {
		Common.useDefaultSettings();
	}

	// Test no cache in play (existing account)
	@Test
	public void testNoCacheExistingAccount() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount account = Common.getTestAccount(repository, "alice");

			// fetch 1st ref
			byte[] lastRef1 = account.getLastReference();

			// generate 2nd ref and call Account.setLastReference
			byte[] lastRef2 = new byte[32];
			RANDOM.nextBytes(lastRef2);
			account.setLastReference(lastRef2);

			// fetch 3rd ref
			byte[] lastRef3 = account.getLastReference();

			// 3rd ref should match 2nd ref
			assertTrue("getLastReference() should return latest value", Arrays.equals(lastRef2, lastRef3));

			// 3rd ref should not match 1st ref
			assertFalse("setLastReference() failed?", Arrays.equals(lastRef1, lastRef3));
		}
	}

	// Test no cache in play (new account)
	@Test
	public void testNoCacheNewAccount() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			Account account = createRandomAccount(repository);

			// fetch 1st ref
			byte[] lastRef1 = account.getLastReference();
			assertNull("new account's initial lastReference should be null", lastRef1);

			// generate 2nd ref and call Account.setLastReference
			byte[] lastRef2 = new byte[32];
			RANDOM.nextBytes(lastRef2);
			account.setLastReference(lastRef2);

			// fetch 3rd ref
			byte[] lastRef3 = account.getLastReference();

			// 3rd ref should match 2nd ref
			assertTrue("getLastReference() should return latest value", Arrays.equals(lastRef2, lastRef3));

			// 3rd ref should not match 1st ref
			assertFalse("setLastReference() failed?", Arrays.equals(lastRef1, lastRef3));
		}
	}

	// Test cache in play (existing account, no commit)
	@Test
	public void testWithCacheExistingAccountNoCommit() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount account = Common.getTestAccount(repository, "alice");

			// fetch 1st ref
			byte[] lastRef1 = account.getLastReference();

			// begin caching
			try (final AccountRefCache accountRefCache = new AccountRefCache(repository)) {
				// fetch 2nd ref
				byte[] lastRef2 = account.getLastReference();

				// 2nd ref should match 1st ref
				assertTrue("getLastReference() should return pre-cache value", Arrays.equals(lastRef1, lastRef2));

				// generate 3rd ref and call Account.setLastReference
				byte[] lastRef3 = new byte[32];
				RANDOM.nextBytes(lastRef3);
				account.setLastReference(lastRef3);

				// fetch 4th ref
				byte[] lastRef4 = account.getLastReference();

				// 4th ref should match 1st ref
				assertTrue("getLastReference() should return pre-cache value", Arrays.equals(lastRef1, lastRef4));
			}
			// cache discarded

			// fetch 5th ref
			byte[] lastRef5 = account.getLastReference();

			// 5th ref should match 1st ref
			assertTrue("getLastReference() should return pre-cache value", Arrays.equals(lastRef1, lastRef5));
		}
	}

	// Test cache in play (existing account, with commit)
	@Test
	public void testWithCacheExistingAccountWithCommit() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount account = Common.getTestAccount(repository, "alice");

			// fetch 1st ref
			byte[] lastRef1 = account.getLastReference();

			// begin caching
			byte[] committedRef;
			try (final AccountRefCache accountRefCache = new AccountRefCache(repository)) {
				// fetch 2nd ref
				byte[] lastRef2 = account.getLastReference();

				// 2nd ref should match 1st ref
				assertTrue("getLastReference() should return pre-cache value", Arrays.equals(lastRef1, lastRef2));

				// generate 3rd ref and call Account.setLastReference
				byte[] lastRef3 = new byte[32];
				RANDOM.nextBytes(lastRef3);
				account.setLastReference(lastRef3);
				committedRef = lastRef3;

				// fetch 4th ref
				byte[] lastRef4 = account.getLastReference();

				// 4th ref should match 1st ref
				assertTrue("getLastReference() should return pre-cache value", Arrays.equals(lastRef1, lastRef4));

				// Commit cache
				accountRefCache.commit();
			}

			// fetch 5th ref
			byte[] lastRef5 = account.getLastReference();

			// 5th ref should match committed ref
			assertTrue("getLastReference() should return pre-cache value", Arrays.equals(committedRef, lastRef5));
		}
	}

	// Test cache in play (new account, no commit)
	@Test
	public void testWithCacheNewAccountNoCommit() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			Account account = createRandomAccount(repository);

			// fetch 1st ref
			byte[] lastRef1 = account.getLastReference();
			assertNull("new account's initial lastReference should be null", lastRef1);

			// begin caching
			try (final AccountRefCache accountRefCache = new AccountRefCache(repository)) {
				// fetch 2nd ref
				byte[] lastRef2 = account.getLastReference();

				// 2nd ref should match 1st ref
				assertTrue("getLastReference() should return pre-cache value", Arrays.equals(lastRef1, lastRef2));

				// generate 3rd ref and call Account.setLastReference
				byte[] lastRef3 = new byte[32];
				RANDOM.nextBytes(lastRef3);
				account.setLastReference(lastRef3);

				// fetch 4th ref
				byte[] lastRef4 = account.getLastReference();

				// 4th ref should match 1st ref
				assertTrue("getLastReference() should return pre-cache value", Arrays.equals(lastRef1, lastRef4));
			}
			// cache discarded

			// fetch 5th ref
			byte[] lastRef5 = account.getLastReference();

			// 5th ref should match 1st ref
			assertTrue("getLastReference() should return pre-cache value", Arrays.equals(lastRef1, lastRef5));
		}
	}

	// Test cache in play (new account, with commit)
	@Test
	public void testWithCacheNewAccountWithCommit() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			Account account = createRandomAccount(repository);

			// fetch 1st ref
			byte[] lastRef1 = account.getLastReference();
			assertNull("new account's initial lastReference should be null", lastRef1);

			// begin caching
			byte[] committedRef;
			try (final AccountRefCache accountRefCache = new AccountRefCache(repository)) {
				// fetch 2nd ref
				byte[] lastRef2 = account.getLastReference();

				// 2nd ref should match 1st ref
				assertTrue("getLastReference() should return pre-cache value", Arrays.equals(lastRef1, lastRef2));

				// generate 3rd ref and call Account.setLastReference
				byte[] lastRef3 = new byte[32];
				RANDOM.nextBytes(lastRef3);
				account.setLastReference(lastRef3);
				committedRef = lastRef3;

				// fetch 4th ref
				byte[] lastRef4 = account.getLastReference();

				// 4th ref should match 1st ref
				assertTrue("getLastReference() should return pre-cache value", Arrays.equals(lastRef1, lastRef4));

				// Commit cache
				accountRefCache.commit();
			}

			// fetch 5th ref
			byte[] lastRef5 = account.getLastReference();

			// 5th ref should match committed ref
			assertTrue("getLastReference() should return pre-cache value", Arrays.equals(committedRef, lastRef5));
		}
	}

	// Test Block support
	@Test
	public void testBlockSupport() throws DataException {
		final long amount = 123_45670000L;

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			Account newbie = createRandomAccount(repository);

			// fetch 1st ref
			byte[] lastRef1 = alice.getLastReference();

			// generate new payment from Alice to new account
			TransactionData paymentData1 = new PaymentTransactionData(TestTransaction.generateBase(alice), newbie.getAddress(), amount);
			TransactionUtils.signAndImportValid(repository, paymentData1, alice); // updates paymentData1's signature

			// generate another payment from Alice to new account
			TransactionData paymentData2 = new PaymentTransactionData(TestTransaction.generateBase(alice), newbie.getAddress(), amount);
			TransactionUtils.signAndImportValid(repository, paymentData2, alice); // updates paymentData2's signature

			// mint block containing payments (uses cache)
			BlockUtils.mintBlock(repository);

			// confirm new account's ref is last payment's sig
			byte[] newAccountRef = newbie.getLastReference();
			assertTrue("new account's lastReference should match last payment's sig", Arrays.equals(paymentData2.getSignature(), newAccountRef));

			// confirm Alice's ref is last payment's sig
			byte[] lastRef2 = alice.getLastReference();
			assertTrue("Alice's lastReference should match last payment's sig", Arrays.equals(paymentData2.getSignature(), lastRef2));

			// orphan block
			BlockUtils.orphanLastBlock(repository);

			// confirm new account's ref reverted back to null
			newAccountRef = newbie.getLastReference();
			assertNull("new account's lastReference should have reverted back to null", newAccountRef);

			// confirm Alice's ref matches 1st ref
			byte[] lastRef3 = alice.getLastReference();
			assertTrue("Alice's lastReference should match initial lastReference", Arrays.equals(lastRef1, lastRef3));
		}
	}

	private static Account createRandomAccount(Repository repository) {
		byte[] randomPublicKey = new byte[32];
		RANDOM.nextBytes(randomPublicKey);
		return new PublicKeyAccount(repository, randomPublicKey);
	}

}
