package org.qortal.test.naming;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.api.resource.TransactionsResource;
import org.qortal.block.BlockChain;
import org.qortal.data.naming.NameData;
import org.qortal.data.transaction.BuyNameTransactionData;
import org.qortal.data.transaction.CancelSellNameTransactionData;
import org.qortal.data.transaction.RegisterNameTransactionData;
import org.qortal.data.transaction.SellNameTransactionData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.test.common.BlockUtils;
import org.qortal.test.common.Common;
import org.qortal.test.common.TransactionUtils;
import org.qortal.test.common.transaction.TestTransaction;
import org.qortal.transaction.RegisterNameTransaction;
import org.qortal.transaction.Transaction;
import org.qortal.utils.Amounts;

import java.util.List;
import java.util.Optional;
import java.util.Random;

import static org.junit.Assert.*;

public class BuySellTests extends Common {

	protected static final Random random = new Random();

	private Repository repository;
	private PrivateKeyAccount alice;
	private PrivateKeyAccount bob;

	private String name;
	private Long price;

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();

		repository = RepositoryManager.getRepository();
		alice = Common.getTestAccount(repository, "alice");
		bob = Common.getTestAccount(repository, "bob");

		name = "test name" + " " + random.nextInt(1_000_000);
		price = (random.nextInt(1000) + 1) * Amounts.MULTIPLIER;
	}

	@After
	public void afterTest() throws DataException {
		name = null;
		price = null;

		alice = null;
		bob = null;

		repository = null;

		Common.orphanCheck();
	}

	@Test
	public void testRegisterName() throws DataException {
		// Register-name
		RegisterNameTransactionData transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name, "{}");
		transactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(transactionData.getTimestamp()));
		TransactionUtils.signAndMint(repository, transactionData, alice);

		String name = transactionData.getName();

		// Check name does exist
		assertTrue(repository.getNameRepository().nameExists(name));

		// Orphan register-name
		BlockUtils.orphanLastBlock(repository);

		// Check name no longer exists
		assertFalse(repository.getNameRepository().nameExists(name));

		// Re-process register-name
		BlockUtils.mintBlock(repository);

		// Check name does exist
		assertTrue(repository.getNameRepository().nameExists(name));
	}

	@Test
	public void testRegisterNameMultiple() throws DataException {
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

		assertEquals(2, namesByOwner.size() );

		// check that the order is correct
		assertEquals(name1, namesByOwner.get(0).getName());

		SellNameTransactionData sellPrimaryNameData = new SellNameTransactionData(TestTransaction.generateBase(alice), name, price);
		Transaction.ValidationResult sellPrimaryNameResult = TransactionUtils.signAndImport(repository, sellPrimaryNameData, alice);

		// check that selling primary name is not supported while owning multiple addresses
		assertTrue(Transaction.ValidationResult.NOT_SUPPORTED.equals(sellPrimaryNameResult));
	}

	@Test
	public void testSellName() throws DataException {
		// mint passed the feature trigger block
		BlockUtils.mintBlocks(repository, BlockChain.getInstance().getMultipleNamesPerAccountHeight());

		// Register-name
		testRegisterName();

		// assert primary name for alice
		Optional<String> alicePrimaryName1 = alice.getPrimaryName();
		assertTrue(alicePrimaryName1.isPresent());
		assertTrue(alicePrimaryName1.get().equals(name));

		// Sell-name
		SellNameTransactionData transactionData = new SellNameTransactionData(TestTransaction.generateBase(alice), name, price);
		TransactionUtils.signAndMint(repository, transactionData, alice);

		// assert primary name for alice
		Optional<String> alicePrimaryName2 = alice.getPrimaryName();
		assertTrue(alicePrimaryName2.isPresent());
		assertTrue(alicePrimaryName2.get().equals(name));

		NameData nameData;

		// Check name is for sale
		nameData = repository.getNameRepository().fromName(name);
		assertTrue(nameData.isForSale());
		assertEquals("price incorrect", price, nameData.getSalePrice());

		// assert alice cannot register another name while primary name is for sale
		final String name2 = "another name";
		RegisterNameTransactionData registerSecondNameData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name2, "{}");
		Transaction.ValidationResult registrationResult = TransactionUtils.signAndImport(repository, registerSecondNameData, alice);

		// check that registering is not supported while primary name is for sale
		assertTrue(Transaction.ValidationResult.NOT_SUPPORTED.equals(registrationResult));

		// Orphan sell-name
		BlockUtils.orphanLastBlock(repository);

		// Check name no longer for sale
		nameData = repository.getNameRepository().fromName(name);
		assertFalse(nameData.isForSale());
		// Not concerned about price

		// Re-process sell-name
		BlockUtils.mintBlock(repository);

		// Check name is for sale
		nameData = repository.getNameRepository().fromName(name);
		assertTrue(nameData.isForSale());
		assertEquals("price incorrect", price, nameData.getSalePrice());

		// Orphan sell-name and register-name
		BlockUtils.orphanBlocks(repository, 2);

		// assert primary name for alice
		Optional<String> alicePrimaryName3 = alice.getPrimaryName();
		assertTrue(alicePrimaryName3.isEmpty());

		// Check name no longer exists
		assertFalse(repository.getNameRepository().nameExists(name));
		nameData = repository.getNameRepository().fromName(name);
		assertNull(nameData);

		// Re-process register-name and sell-name
		BlockUtils.mintBlock(repository);
		// Unconfirmed sell-name transaction not included in previous block
		// as it isn't valid until name exists thanks to register-name transaction.
		BlockUtils.mintBlock(repository);

		// Check name does exist
		assertTrue(repository.getNameRepository().nameExists(name));

		// Check name is for sale
		nameData = repository.getNameRepository().fromName(name);
		assertTrue(nameData.isForSale());
		assertEquals("price incorrect", price, nameData.getSalePrice());
	}

	@Test
	public void testCancelSellName() throws DataException {
		// Register-name and sell-name
		testSellName();

		// Cancel Sell-name
		CancelSellNameTransactionData transactionData = new CancelSellNameTransactionData(TestTransaction.generateBase(alice), name);
		TransactionUtils.signAndMint(repository, transactionData, alice);

		NameData nameData;

		// Check name is no longer for sale
		nameData = repository.getNameRepository().fromName(name);
		assertFalse(nameData.isForSale());
		// Not concerned about price

		// Orphan cancel sell-name
		BlockUtils.orphanLastBlock(repository);

		// Check name is for sale
		nameData = repository.getNameRepository().fromName(name);
		assertTrue(nameData.isForSale());
		assertEquals("price incorrect", price, nameData.getSalePrice());
	}

	@Test
	public void testCancelSellNameAndRelist() throws DataException {
		// Register-name and sell-name
		testSellName();

		// Cancel Sell-name
		CancelSellNameTransactionData transactionData = new CancelSellNameTransactionData(TestTransaction.generateBase(alice), name);
		TransactionUtils.signAndMint(repository, transactionData, alice);

		NameData nameData;

		// Check name is no longer for sale
		nameData = repository.getNameRepository().fromName(name);
		assertFalse(nameData.isForSale());
		assertNull(nameData.getSalePrice());

		// Re-sell-name
		Long newPrice = random.nextInt(1000) * Amounts.MULTIPLIER;
		SellNameTransactionData sellNameTransactionData = new SellNameTransactionData(TestTransaction.generateBase(alice), name, newPrice);
		TransactionUtils.signAndMint(repository, sellNameTransactionData, alice);

		// Check name is for sale
		nameData = repository.getNameRepository().fromName(name);
		assertTrue(nameData.isForSale());
		assertEquals("price incorrect", newPrice, nameData.getSalePrice());

		// Orphan sell-name
		BlockUtils.orphanLastBlock(repository);

		// Check name no longer for sale
		nameData = repository.getNameRepository().fromName(name);
		assertFalse(nameData.isForSale());
		assertNull(nameData.getSalePrice());

		// Orphan cancel-sell-name
		BlockUtils.orphanLastBlock(repository);

		// Check name is for sale (at original price)
		nameData = repository.getNameRepository().fromName(name);
		assertTrue(nameData.isForSale());
		assertEquals("price incorrect", price, nameData.getSalePrice());

		// Orphan sell-name and register-name
		BlockUtils.orphanBlocks(repository, 2);
	}

	@Test
	public void testBuyName() throws DataException {
		// move passed primary initiation
		BlockUtils.mintBlocks(repository, BlockChain.getInstance().getMultipleNamesPerAccountHeight());

		// Register-name and sell-name
		testSellName();

		String seller = alice.getAddress();

		// assert alice has the name as primary
		Optional<String> alicePrimaryName1 = alice.getPrimaryName();
		assertTrue(alicePrimaryName1.isPresent());
		assertEquals(name, alicePrimaryName1.get());

		// assert bob does not have a primary name
		Optional<String> bobPrimaryName1 = bob.getPrimaryName();
		assertTrue(bobPrimaryName1.isEmpty());

		// Buy-name
		BuyNameTransactionData transactionData = new BuyNameTransactionData(TestTransaction.generateBase(bob), name, price, seller);
		TransactionUtils.signAndMint(repository, transactionData, bob);

		// assert alice does not have a primary name anymore
		Optional<String> alicePrimaryName2 = alice.getPrimaryName();
		assertTrue(alicePrimaryName2.isEmpty());

		// assert bob does have the name as primary
		Optional<String> bobPrimaryName2 = bob.getPrimaryName();
		assertTrue(bobPrimaryName2.isPresent());
		assertEquals(name, bobPrimaryName2.get());

		NameData nameData;

		// Check name is sold
		nameData = repository.getNameRepository().fromName(name);
		assertFalse(nameData.isForSale());
		// Not concerned about price

		// Orphan buy-name
		BlockUtils.orphanLastBlock(repository);

		// assert alice has the name as primary
		Optional<String> alicePrimaryNameOrphaned = alice.getPrimaryName();
		assertTrue(alicePrimaryNameOrphaned.isPresent());
		assertEquals(name, alicePrimaryNameOrphaned.get());

		// assert bob does not have a primary name
		Optional<String> bobPrimaryNameOrphaned = bob.getPrimaryName();
		assertTrue(bobPrimaryNameOrphaned.isEmpty());

		// Check name is for sale (not sold)
		nameData = repository.getNameRepository().fromName(name);
		assertTrue(nameData.isForSale());
		assertEquals("price incorrect", price, nameData.getSalePrice());

		// Re-process buy-name
		BlockUtils.mintBlock(repository);

		// Check name is sold
		nameData = repository.getNameRepository().fromName(name);
		assertFalse(nameData.isForSale());
		// Not concerned about price
		assertEquals(bob.getAddress(), nameData.getOwner());

		// Orphan buy-name and sell-name
		BlockUtils.orphanBlocks(repository, 2);

		// Check name no longer for sale
		nameData = repository.getNameRepository().fromName(name);
		assertFalse(nameData.isForSale());
		// Not concerned about price
		assertEquals(alice.getAddress(), nameData.getOwner());

		// Re-process sell-name and buy-name
		BlockUtils.mintBlock(repository);
		// Unconfirmed buy-name transaction not included in previous block
		// as it isn't valid until name is for sale thanks to sell-name transaction.
		BlockUtils.mintBlock(repository);

		// Check name is sold
		nameData = repository.getNameRepository().fromName(name);
		assertFalse(nameData.isForSale());
		// Not concerned about price
		assertEquals(bob.getAddress(), nameData.getOwner());

		assertEquals(alice.getPrimaryName(), alice.determinePrimaryName(TransactionsResource.ConfirmationStatus.CONFIRMED));
		assertEquals(bob.getPrimaryName(), bob.determinePrimaryName(TransactionsResource.ConfirmationStatus.CONFIRMED));
	}

	@Test
	public void testSellBuySellName() throws DataException {
		// Register-name, sell-name, buy-name
		testBuyName();

		// Sell-name
		Long newPrice = random.nextInt(1000) * Amounts.MULTIPLIER;
		SellNameTransactionData transactionData = new SellNameTransactionData(TestTransaction.generateBase(bob), name, newPrice);
		TransactionUtils.signAndMint(repository, transactionData, bob);

		NameData nameData;

		// Check name is for sale
		nameData = repository.getNameRepository().fromName(name);
		assertTrue(nameData.isForSale());
		assertEquals("price incorrect", newPrice, nameData.getSalePrice());

		// Orphan sell-name
		BlockUtils.orphanLastBlock(repository);

		// Check name no longer for sale
		nameData = repository.getNameRepository().fromName(name);
		assertFalse(nameData.isForSale());
		// Not concerned about price

		// Re-process sell-name
		BlockUtils.mintBlock(repository);

		// Check name is for sale
		nameData = repository.getNameRepository().fromName(name);
		assertTrue(nameData.isForSale());
		assertEquals("price incorrect", newPrice, nameData.getSalePrice());

		// Orphan sell-name and buy-name
		BlockUtils.orphanBlocks(repository, 2);

		// Check name is for sale
		nameData = repository.getNameRepository().fromName(name);
		assertTrue(nameData.isForSale());
		// Note: original sale price
		assertEquals("price incorrect", price, nameData.getSalePrice());
		assertEquals(alice.getAddress(), nameData.getOwner());

		// Re-process buy-name and sell-name
		BlockUtils.mintBlock(repository);
		// Unconfirmed sell-name transaction not included in previous block
		// as it isn't valid until name owned by bob thanks to buy-name transaction.
		BlockUtils.mintBlock(repository);

		// Check name does exist
		assertTrue(repository.getNameRepository().nameExists(name));

		// Check name is for sale
		nameData = repository.getNameRepository().fromName(name);
		assertTrue(nameData.isForSale());
		assertEquals("price incorrect", newPrice, nameData.getSalePrice());
		assertEquals(bob.getAddress(), nameData.getOwner());

		assertEquals(alice.getPrimaryName(), alice.determinePrimaryName(TransactionsResource.ConfirmationStatus.CONFIRMED));
		assertEquals(bob.getPrimaryName(), bob.determinePrimaryName(TransactionsResource.ConfirmationStatus.CONFIRMED));
	}

	@Test
	public void testBuyInvalidationDuringPrimaryNameSale() throws DataException {
		// mint passed the feature trigger block
		BlockUtils.mintBlocks(repository, BlockChain.getInstance().getMultipleNamesPerAccountHeight());

		// Register-name
		testRegisterName();

		// assert primary name for alice
		Optional<String> alicePrimaryName1 = alice.getPrimaryName();
		assertTrue(alicePrimaryName1.isPresent());
		assertTrue(alicePrimaryName1.get().equals(name));

		// Sell-name
		SellNameTransactionData transactionData = new SellNameTransactionData(TestTransaction.generateBase(alice), name, price);
		TransactionUtils.signAndMint(repository, transactionData, alice);

		// assert primary name for alice
		Optional<String> alicePrimaryName2 = alice.getPrimaryName();
		assertTrue(alicePrimaryName2.isPresent());
		assertTrue(alicePrimaryName2.get().equals(name));

		NameData nameData;

		// Check name is for sale
		nameData = repository.getNameRepository().fromName(name);
		assertTrue(nameData.isForSale());
		assertEquals("price incorrect", price, nameData.getSalePrice());

		// assert alice cannot register another name while primary name is for sale
		final String name2 = "another name";
		RegisterNameTransactionData registerSecondNameData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), name2, "{}");
		Transaction.ValidationResult registrationResult = TransactionUtils.signAndImport(repository, registerSecondNameData, alice);

		// check that registering is not supported while primary name is for sale
		assertTrue(Transaction.ValidationResult.NOT_SUPPORTED.equals(registrationResult));

		String bobName = "bob";
		RegisterNameTransactionData bobRegisterData = new RegisterNameTransactionData(TestTransaction.generateBase(bob), bobName, "{}");
		transactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(bobRegisterData.getTimestamp()));
		TransactionUtils.signAndMint(repository, bobRegisterData, bob);

		Optional<String> bobPrimaryName = bob.getPrimaryName();

		assertTrue(bobPrimaryName.isPresent());
		assertEquals(bobName, bobPrimaryName.get());

		SellNameTransactionData bobSellData = new SellNameTransactionData(TestTransaction.generateBase(bob), bobName, price);
		TransactionUtils.signAndMint(repository, bobSellData, bob);

		BuyNameTransactionData aliceBuyData = new BuyNameTransactionData(TestTransaction.generateBase(alice), bobName, price, bob.getAddress());
		Transaction.ValidationResult aliceBuyResult = TransactionUtils.signAndImport(repository, aliceBuyData, alice);

		// check that buying is not supported while primary name is for sale
		assertTrue(Transaction.ValidationResult.NOT_SUPPORTED.equals(aliceBuyResult));
	}
}
