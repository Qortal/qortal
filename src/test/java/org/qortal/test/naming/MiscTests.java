package org.qortal.test.naming;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.api.AmountTypeAdapter;
import org.qortal.block.BlockChain;
import org.qortal.block.BlockChain.UnitFeesByTimestamp;
import org.qortal.controller.BlockMinter;
import org.qortal.data.naming.NameData;
import org.qortal.data.transaction.PaymentTransactionData;
import org.qortal.data.transaction.RegisterNameTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.data.transaction.UpdateNameTransactionData;
import org.qortal.naming.Name;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.test.common.BlockUtils;
import org.qortal.test.common.Common;
import org.qortal.test.common.TransactionUtils;
import org.qortal.test.common.transaction.TestTransaction;
import org.qortal.transaction.PaymentTransaction;
import org.qortal.transaction.RegisterNameTransaction;
import org.qortal.transaction.Transaction;
import org.qortal.transaction.Transaction.ValidationResult;
import org.qortal.utils.NTP;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.*;

public class MiscTests extends Common {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@Test
	public void testGetRecentNames() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			// Register-name
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			String name = "initial-name";
			String data = "{\"age\":30}";

			RegisterNameTransactionData transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name, data);
			transactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(transactionData.getTimestamp()));
			TransactionUtils.signAndMint(repository, transactionData, alice);

			List<String> recentNames = repository.getNameRepository().getRecentNames(0L);

			assertNotNull(recentNames);
			assertFalse(recentNames.isEmpty());
		}
	}

	// test trying to register same name twice
	@Test
	public void testDuplicateRegisterName() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			// Register-name
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			String name = "test-name";
			String data = "{\"age\":30}";

			RegisterNameTransactionData transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name, data);
			transactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(transactionData.getTimestamp()));
			TransactionUtils.signAndMint(repository, transactionData, alice);

			// duplicate
			String duplicateName = "TEST-nÁme";
			transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), duplicateName, data);
			transactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(transactionData.getTimestamp()));
			Transaction transaction = Transaction.fromData(repository, transactionData);
			transaction.sign(alice);

			ValidationResult result = transaction.importAsUnconfirmed();
			assertTrue("Transaction should be invalid", ValidationResult.OK != result);
		}
	}

	// test trying to register same name twice (with different creator)
	@Test
	public void testDuplicateRegisterNameWithDifferentCreator() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			// Register-name
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			String name = "test-name";
			String data = "{}";

			RegisterNameTransactionData transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name, data);
			transactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(transactionData.getTimestamp()));
			TransactionUtils.signAndMint(repository, transactionData, alice);

			// duplicate (this time registered by Bob)
			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");
			String duplicateName = "TEST-nÁme";
			transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(bob), duplicateName, data);
			transactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(transactionData.getTimestamp()));
			Transaction transaction = Transaction.fromData(repository, transactionData);
			transaction.sign(alice);

			ValidationResult result = transaction.importAsUnconfirmed();
			assertTrue("Transaction should be invalid", ValidationResult.OK != result);
		}
	}

	// test register then trying to update another name to existing name
	@Test
	public void testUpdateToExistingName() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			// Register-name
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			String name = "test-name";
			String data = "{\"age\":30}";

			TransactionData transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name, data);
			transactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(transactionData.getTimestamp()));
			TransactionUtils.signAndMint(repository, transactionData, alice);

			BlockUtils.mintBlocks(repository, BlockChain.getInstance().getMultipleNamesPerAccountHeight());

			// Register another name that we will later attempt to rename to first name (above)
			String otherName = "new-name";
			String otherData = "";
			transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), otherName, otherData);
			transactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(transactionData.getTimestamp()));
			TransactionUtils.signAndMint(repository, transactionData, alice);

			// we shouldn't be able to update name to existing name
			String duplicateName = "TEST-nÁme";
			transactionData = new UpdateNameTransactionData(TestTransaction.generateBase(alice), otherName, duplicateName, otherData);
			Transaction transaction = Transaction.fromData(repository, transactionData);
			transaction.sign(alice);

			ValidationResult result = transaction.importAsUnconfirmed();
			assertTrue("Transaction should be invalid", ValidationResult.OK != result);
		}
	}

	// test trying to register a name that looks like an address
	@Test
	public void testRegisterAddressAsName() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			// Register-name
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			String name = alice.getAddress();
			String data = "{\"age\":30}";

			RegisterNameTransactionData transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name, data);
			transactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(transactionData.getTimestamp()));
			Transaction transaction = Transaction.fromData(repository, transactionData);
			transaction.sign(alice);

			ValidationResult result = transaction.importAsUnconfirmed();
			assertTrue("Transaction should be invalid", ValidationResult.OK != result);
		}
	}

	// test register then trying to update to a name that looks like an address
	@Test
	public void testUpdateToAddressAsName() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			// Register-name
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			String name = "test-name";
			String data = "{\"age\":30}";

			TransactionData transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name, data);
			transactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(transactionData.getTimestamp()));
			TransactionUtils.signAndMint(repository, transactionData, alice);

			// we shouldn't be able to update name to an address
			String newName = alice.getAddress();
			String newData = "";
			transactionData = new UpdateNameTransactionData(TestTransaction.generateBase(alice), name, newName, newData);
			Transaction transaction = Transaction.fromData(repository, transactionData);
			transaction.sign(alice);

			ValidationResult result = transaction.importAsUnconfirmed();
			assertTrue("Transaction should be invalid", ValidationResult.OK != result);
		}
	}

	// test registering and then orphaning
	@Test
	public void testRegisterNameAndOrphan() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {

			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			String name = "test-name";
			String data = "{\"age\":30}";

			// Ensure the name doesn't exist
			assertNull(repository.getNameRepository().fromName(name));

			// Register the name
			RegisterNameTransactionData transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name, data);
			transactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(transactionData.getTimestamp()));
			TransactionUtils.signAndMint(repository, transactionData, alice);

			// Ensure the name exists and the data is correct
			assertEquals(data, repository.getNameRepository().fromName(name).getData());

			// Orphan the latest block
			BlockUtils.orphanBlocks(repository, 1);

			// Ensure the name doesn't exist once again
			assertNull(repository.getNameRepository().fromName(name));
		}
	}

	@Test
	public void testOrphanAndReregisterName() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {

			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			String name = "test-name";
			String data = "{\"age\":30}";

			// Ensure the name doesn't exist
			assertNull(repository.getNameRepository().fromName(name));

			// Register the name
			RegisterNameTransactionData transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name, data);
			transactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(transactionData.getTimestamp()));
			TransactionUtils.signAndMint(repository, transactionData, alice);

			// Ensure the name exists and the data is correct
			assertEquals(data, repository.getNameRepository().fromName(name).getData());

			// Orphan the latest block
			BlockUtils.orphanBlocks(repository, 1);

			// Ensure the name doesn't exist once again
			assertNull(repository.getNameRepository().fromName(name));

			// Now check there is an unconfirmed transaction
			assertEquals(1, repository.getTransactionRepository().getUnconfirmedTransactions().size());

			// Re-mint the block, including the original transaction
			BlockMinter.mintTestingBlock(repository, Common.getTestAccount(repository, "alice-reward-share"));

			// There should no longer be an unconfirmed transaction
			assertEquals(0, repository.getTransactionRepository().getUnconfirmedTransactions().size());

			// Orphan the latest block
			BlockUtils.orphanBlocks(repository, 1);

			// There should now be an unconfirmed transaction again
			assertEquals(1, repository.getTransactionRepository().getUnconfirmedTransactions().size());

			// Re-mint the block, including the original transaction
			BlockMinter.mintTestingBlock(repository, Common.getTestAccount(repository, "alice-reward-share"));

			// Ensure there are no unconfirmed transactions
			assertEquals(0, repository.getTransactionRepository().getUnconfirmedTransactions().size());
		}
	}

	// test registering and then orphaning multiple times, with a different versions of the transaction each time
	// we can sometimes end up with more than one version of a transaction, if it is signed and submitted twice
	@Test
	public void testMultipleRegisterNameAndOrphan() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {

			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			String name = "test-name";
			String data = "{\"age\":30}";

			for (int i = 1; i <= 10; i++) {

				// Ensure the name doesn't exist
				assertNull(repository.getNameRepository().fromName(name));

				// Register the name
				RegisterNameTransactionData transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name, data);
				transactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(transactionData.getTimestamp()));
				TransactionUtils.signAndMint(repository, transactionData, alice);

				// Ensure the name exists and the data is correct
				assertEquals(data, repository.getNameRepository().fromName(name).getData());

				// The number of unconfirmed transactions should equal the number of cycles minus 1 (because one is in a block)
				// If more than one made it into a block, this test would fail
				assertEquals(i-1, repository.getTransactionRepository().getUnconfirmedTransactions().size());

				// Orphan the latest block
				BlockUtils.orphanBlocks(repository, 1);

				// The number of unconfirmed transactions should equal the number of cycles
				assertEquals(i, repository.getTransactionRepository().getUnconfirmedTransactions().size());

				// Ensure the name doesn't exist once again
				assertNull(repository.getNameRepository().fromName(name));
			}
		}
	}

	@Test
	public void testSaveName() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			for (int i=0; i<10; i++) {

				String name = "test-name";
				String data = "{\"age\":30}";

				PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
				RegisterNameTransactionData transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name, data);
				transactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(transactionData.getTimestamp()));

				// Ensure the name doesn't exist
				assertNull(repository.getNameRepository().fromName(name));

				// Register the name
				Name nameObj = new Name(repository, transactionData);
				nameObj.register();

				// Ensure the name now exists
				assertNotNull(repository.getNameRepository().fromName(name));

				// Unregister the name
				nameObj.unregister();

				// Ensure the name doesn't exist again
				assertNull(repository.getNameRepository().fromName(name));

			}
		}
	}

	// test name registration fee increase
	@Test
	public void testRegisterNameFeeIncrease() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {

			BlockUtils.mintBlocks(repository, BlockChain.getInstance().getMultipleNamesPerAccountHeight());

			// Add original fee to nameRegistrationUnitFees
			UnitFeesByTimestamp originalFee = new UnitFeesByTimestamp();
			originalFee.timestamp = 0L;
			originalFee.fee = new AmountTypeAdapter().unmarshal("0.1");

			// Add a time far in the future to nameRegistrationUnitFees
			UnitFeesByTimestamp futureFeeIncrease = new UnitFeesByTimestamp();
			futureFeeIncrease.timestamp = 9999999999999L; // 20 Nov 2286
			futureFeeIncrease.fee = new AmountTypeAdapter().unmarshal("5");
			FieldUtils.writeField(BlockChain.getInstance(), "nameRegistrationUnitFees", Arrays.asList(originalFee, futureFeeIncrease), true);
			assertEquals(futureFeeIncrease.fee, BlockChain.getInstance().getNameRegistrationUnitFeeAtTimestamp(futureFeeIncrease.timestamp));

			// Validate unit fees pre and post timestamp
			assertEquals(10000000, BlockChain.getInstance().getNameRegistrationUnitFeeAtTimestamp(futureFeeIncrease.timestamp - 1)); // 0.1 QORT
			assertEquals(500000000, BlockChain.getInstance().getNameRegistrationUnitFeeAtTimestamp(futureFeeIncrease.timestamp)); // 5 QORT

			// Register-name
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			String name = "test-name";
			String data = "{\"age\":30}";

			RegisterNameTransactionData transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name, data);
			transactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(transactionData.getTimestamp()));
			assertEquals(10000000L, transactionData.getFee().longValue());
			TransactionUtils.signAndMint(repository, transactionData, alice);

			// Set nameRegistrationUnitFeeTimestamp to a time in the past
			Long now = NTP.getTime();
			UnitFeesByTimestamp pastFeeIncrease = new UnitFeesByTimestamp();
			pastFeeIncrease.timestamp = now - 1000L; // 1 second ago
			pastFeeIncrease.fee = new AmountTypeAdapter().unmarshal("3");

			// Set another increase in the future
			futureFeeIncrease = new UnitFeesByTimestamp();
			futureFeeIncrease.timestamp = now + (60 * 60 * 1000L); // 1 hour in the future
			futureFeeIncrease.fee = new AmountTypeAdapter().unmarshal("10");

			FieldUtils.writeField(BlockChain.getInstance(), "nameRegistrationUnitFees", Arrays.asList(originalFee, pastFeeIncrease, futureFeeIncrease), true);
			assertEquals(pastFeeIncrease.fee, BlockChain.getInstance().getNameRegistrationUnitFeeAtTimestamp(pastFeeIncrease.timestamp));
			assertEquals(futureFeeIncrease.fee, BlockChain.getInstance().getNameRegistrationUnitFeeAtTimestamp(futureFeeIncrease.timestamp));

			// Register a different name
			// First try with the default unit fee
			String name2 = "test-name-2";
			transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name2, data);
			assertEquals(10000000L, transactionData.getFee().longValue());
			Transaction transaction = Transaction.fromData(repository, transactionData);
			transaction.sign(alice);
			ValidationResult result = transaction.importAsUnconfirmed();
			assertTrue("Transaction should be invalid", ValidationResult.INSUFFICIENT_FEE == result);

			// Now try using correct fee (this is specified by the UI, via the /transaction/unitfee API endpoint)
			transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name2, data);
			transactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(transactionData.getTimestamp()));
			assertEquals(300000000L, transactionData.getFee().longValue());
			transaction = Transaction.fromData(repository, transactionData);
			transaction.sign(alice);
			result = transaction.importAsUnconfirmed();
			assertTrue("Transaction should be valid", ValidationResult.OK == result);
		}
	}

	// test reading the name registration fee schedule from blockchain.json / test-chain-v2.json
	@Test
	public void testRegisterNameFeeScheduleInTestchainData() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {

			final long expectedFutureFeeIncreaseTimestamp = 9999999999999L; // 20 Nov 2286, as per test-chain-v2.json
			final long expectedFutureFeeIncreaseValue = new AmountTypeAdapter().unmarshal("5");

			assertEquals(expectedFutureFeeIncreaseValue, BlockChain.getInstance().getNameRegistrationUnitFeeAtTimestamp(expectedFutureFeeIncreaseTimestamp));

			// Validate unit fees pre and post timestamp
			assertEquals(10000000, BlockChain.getInstance().getNameRegistrationUnitFeeAtTimestamp(expectedFutureFeeIncreaseTimestamp - 1)); // 0.1 QORT
			assertEquals(500000000, BlockChain.getInstance().getNameRegistrationUnitFeeAtTimestamp(expectedFutureFeeIncreaseTimestamp)); // 5 QORT

			// Register-name
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			String name = "test-name";
			String data = "{\"age\":30}";

			RegisterNameTransactionData transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name, data);
			transactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(transactionData.getTimestamp()));
			assertEquals(10000000L, transactionData.getFee().longValue());
			TransactionUtils.signAndMint(repository, transactionData, alice);
		}
	}



	// test general unit fee increase
	@Test
	public void testUnitFeeIncrease() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {

			// Add original fee to unitFees
			UnitFeesByTimestamp originalFee = new UnitFeesByTimestamp();
			originalFee.timestamp = 0L;
			originalFee.fee = new AmountTypeAdapter().unmarshal("0.1");

			// Add a time far in the future to unitFees
			UnitFeesByTimestamp futureFeeIncrease = new UnitFeesByTimestamp();
			futureFeeIncrease.timestamp = 9999999999999L; // 20 Nov 2286
			futureFeeIncrease.fee = new AmountTypeAdapter().unmarshal("1");
			FieldUtils.writeField(BlockChain.getInstance(), "unitFees", Arrays.asList(originalFee, futureFeeIncrease), true);
			assertEquals(futureFeeIncrease.fee, BlockChain.getInstance().getUnitFeeAtTimestamp(futureFeeIncrease.timestamp));

			// Validate unit fees pre and post timestamp
			assertEquals(10000000, BlockChain.getInstance().getUnitFeeAtTimestamp(futureFeeIncrease.timestamp - 1)); // 0.1 QORT
			assertEquals(100000000, BlockChain.getInstance().getUnitFeeAtTimestamp(futureFeeIncrease.timestamp)); // 1 QORT

			// Payment
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");
			PrivateKeyAccount chloe = Common.getTestAccount(repository, "chloe");

			PaymentTransactionData transactionData = new PaymentTransactionData(TestTransaction.generateBase(alice), bob.getAddress(), 100000);
			transactionData.setFee(new PaymentTransaction(null, null).getUnitFee(transactionData.getTimestamp()));
			assertEquals(10000000L, transactionData.getFee().longValue());
			TransactionUtils.signAndMint(repository, transactionData, alice);

			// Set fee increase to a time in the past
			Long now = NTP.getTime();
			UnitFeesByTimestamp pastFeeIncrease = new UnitFeesByTimestamp();
			pastFeeIncrease.timestamp = now - 1000L; // 1 second ago
			pastFeeIncrease.fee = new AmountTypeAdapter().unmarshal("3");

			// Set another increase in the future
			futureFeeIncrease = new UnitFeesByTimestamp();
			futureFeeIncrease.timestamp = now + (60 * 60 * 1000L); // 1 hour in the future
			futureFeeIncrease.fee = new AmountTypeAdapter().unmarshal("10");

			FieldUtils.writeField(BlockChain.getInstance(), "unitFees", Arrays.asList(originalFee, pastFeeIncrease, futureFeeIncrease), true);
			assertEquals(originalFee.fee, BlockChain.getInstance().getUnitFeeAtTimestamp(originalFee.timestamp));
			assertEquals(pastFeeIncrease.fee, BlockChain.getInstance().getUnitFeeAtTimestamp(pastFeeIncrease.timestamp));
			assertEquals(futureFeeIncrease.fee, BlockChain.getInstance().getUnitFeeAtTimestamp(futureFeeIncrease.timestamp));

			// Send another payment transaction
			// Fee should be determined automatically
			transactionData = new PaymentTransactionData(TestTransaction.generateBase(alice), bob.getAddress(), 50000);
			assertEquals(300000000L, transactionData.getFee().longValue());
			Transaction transaction = Transaction.fromData(repository, transactionData);
			transaction.sign(alice);
			ValidationResult result = transaction.importAsUnconfirmed();
			assertEquals("Transaction should be valid", ValidationResult.OK, result);

			// Now try fetching and setting fee manually
			transactionData = new PaymentTransactionData(TestTransaction.generateBase(alice), chloe.getAddress(), 50000);
			transactionData.setFee(new PaymentTransaction(null, null).getUnitFee(transactionData.getTimestamp()));
			assertEquals(300000000L, transactionData.getFee().longValue());
			transaction = Transaction.fromData(repository, transactionData);
			transaction.sign(alice);
			result = transaction.importAsUnconfirmed();
			assertEquals("Transaction should be valid", ValidationResult.OK, result);
		}
	}

	// test reading the fee schedule from blockchain.json / test-chain-v2.json
	@Test
	public void testFeeScheduleInTestchainData() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {

			final long expectedFutureFeeIncreaseTimestamp = 9999999999999L; // 20 Nov 2286, as per test-chain-v2.json
			final long expectedFutureFeeIncreaseValue = new AmountTypeAdapter().unmarshal("1");

			assertEquals(expectedFutureFeeIncreaseValue, BlockChain.getInstance().getUnitFeeAtTimestamp(expectedFutureFeeIncreaseTimestamp));

			// Validate unit fees pre and post timestamp
			assertEquals(10000000, BlockChain.getInstance().getUnitFeeAtTimestamp(expectedFutureFeeIncreaseTimestamp - 1)); // 0.1 QORT
			assertEquals(100000000, BlockChain.getInstance().getUnitFeeAtTimestamp(expectedFutureFeeIncreaseTimestamp)); // 1 QORT

			// Payment
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");

			PaymentTransactionData transactionData = new PaymentTransactionData(TestTransaction.generateBase(alice), bob.getAddress(), 100000);
			transactionData.setFee(new PaymentTransaction(null, null).getUnitFee(transactionData.getTimestamp()));
			assertEquals(10000000L, transactionData.getFee().longValue());
			TransactionUtils.signAndMint(repository, transactionData, alice);
		}
	}

	@Test
	public void testPrimaryNameEmpty() throws DataException {

		try (final Repository repository = RepositoryManager.getRepository()) {

			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");

			// mint passed the feature trigger block
			BlockUtils.mintBlocks(repository, BlockChain.getInstance().getMultipleNamesPerAccountHeight());

			Optional<String> primaryName = repository.getNameRepository().getPrimaryName(alice.getAddress());

			Assert.assertNotNull(primaryName);
			Assert.assertTrue(primaryName.isEmpty());
		}
	}

	@Test
	public void testPrimaryNameSingle() throws DataException {

		try (final Repository repository = RepositoryManager.getRepository()) {
			String name = "alice 1";
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");

			// register name 1
			RegisterNameTransactionData transactionData1 = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name, "{}");
			transactionData1.setFee(new RegisterNameTransaction(null, null).getUnitFee(transactionData1.getTimestamp()));
			TransactionUtils.signAndMint(repository, transactionData1, alice);

			String name1 = transactionData1.getName();

			// check name does exist
			assertTrue(repository.getNameRepository().nameExists(name1));


			// mint passed the feature trigger block
			BlockUtils.mintBlocks(repository, BlockChain.getInstance().getMultipleNamesPerAccountHeight() + 1);

			Optional<String> primaryName = repository.getNameRepository().getPrimaryName(alice.getAddress());

			Assert.assertNotNull(primaryName);
			Assert.assertTrue(primaryName.isPresent());
			Assert.assertEquals(name, primaryName.get());
		}
	}

	@Test
	public void testPrimaryNameSingleAfterFeature() throws DataException {

		try (final Repository repository = RepositoryManager.getRepository()) {
			String name = "alice 1";
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");

			// mint passed the feature trigger block
			BlockUtils.mintBlocks(repository, BlockChain.getInstance().getMultipleNamesPerAccountHeight());

			// register name 1
			RegisterNameTransactionData transactionData1 = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name, "{}");
			transactionData1.setFee(new RegisterNameTransaction(null, null).getUnitFee(transactionData1.getTimestamp()));
			TransactionUtils.signAndMint(repository, transactionData1, alice);

			String name1 = transactionData1.getName();

			// check name does exist
			assertTrue(repository.getNameRepository().nameExists(name1));


			Optional<String> primaryName = repository.getNameRepository().getPrimaryName(alice.getAddress());

			Assert.assertNotNull(primaryName);
			Assert.assertTrue(primaryName.isPresent());
			Assert.assertEquals(name, primaryName.get());

			BlockUtils.orphanLastBlock(repository);

			Optional<String> primaryNameOrpaned = repository.getNameRepository().getPrimaryName(alice.getAddress());

			Assert.assertNotNull(primaryNameOrpaned);
			Assert.assertTrue(primaryNameOrpaned.isEmpty());
		}
	}

	@Test
	public void testUpdateNameMultiple() throws DataException {

		try (final Repository repository = RepositoryManager.getRepository()) {
			String name = "alice 1";
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");

			// register name 1
			RegisterNameTransactionData transactionData1 = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name, "{}");
			transactionData1.setFee(new RegisterNameTransaction(null, null).getUnitFee(transactionData1.getTimestamp()));
			TransactionUtils.signAndMint(repository, transactionData1, alice);

			String name1 = transactionData1.getName();

			// check name does exist
			assertTrue(repository.getNameRepository().nameExists(name1));

			// register another name, second registered name should fail before the feature trigger
			final String name2 = "another name";
			RegisterNameTransactionData transactionData2 = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name2, "{}");
			Transaction.ValidationResult resultBeforeFeatureTrigger = TransactionUtils.signAndImport(repository, transactionData2, alice);

			// check that multiple names is forbidden
			assertTrue(Transaction.ValidationResult.MULTIPLE_NAMES_FORBIDDEN.equals(resultBeforeFeatureTrigger));

			// mint passed the feature trigger block
			BlockUtils.mintBlocks(repository, BlockChain.getInstance().getMultipleNamesPerAccountHeight());

			// register again, now that we are passed the feature trigger
			RegisterNameTransactionData transactionData3 = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name2, "{}");
			Transaction.ValidationResult resultAfterFeatureTrigger = TransactionUtils.signAndImport(repository, transactionData3, alice);

			// check that multiple names is ok
			assertTrue(Transaction.ValidationResult.OK.equals(resultAfterFeatureTrigger));

			// mint block, confirm transaction
			BlockUtils.mintBlock(repository);

			// check name does exist
			assertTrue(repository.getNameRepository().nameExists(name2));

			// check that there are 2 names for one account
			List<NameData> namesByOwner = repository.getNameRepository().getNamesByOwner(alice.getAddress(), 0, 0, false);

			assertEquals(2, namesByOwner.size());

			// check that the order is correct
			assertEquals(name1, namesByOwner.get(0).getName());

			String newestName = "newest-name";
			String newestReducedName = "newest-name";
			String newestData = "newest-data";
			TransactionData newestTransactionData = new UpdateNameTransactionData(TestTransaction.generateBase(alice), name2, newestName, newestData);
			TransactionUtils.signAndMint(repository, newestTransactionData, alice);

			// Check previous name no longer exists
			assertFalse(repository.getNameRepository().nameExists(name2));

			// Check newest name exists
			assertTrue(repository.getNameRepository().nameExists(newestName));

			Optional<String> alicePrimaryName1 = alice.getPrimaryName();

			assertTrue( alicePrimaryName1.isPresent() );
			assertEquals( name1, alicePrimaryName1.get() );

			// orphan and recheck
			BlockUtils.orphanLastBlock(repository);

			Optional<String> alicePrimaryName2 = alice.getPrimaryName();

			assertTrue( alicePrimaryName2.isPresent() );
			assertEquals( name1, alicePrimaryName2.get() );

			// Check newest name no longer exists
			assertFalse(repository.getNameRepository().nameExists(newestName));
			assertNull(repository.getNameRepository().fromReducedName(newestReducedName));

			// Check previous name exists again
			assertTrue(repository.getNameRepository().nameExists(name2));
		}
	}
}
