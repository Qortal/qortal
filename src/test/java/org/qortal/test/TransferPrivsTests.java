package org.qortal.test;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.qortal.account.Account;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.account.PublicKeyAccount;
import org.qortal.block.BlockChain;
import org.qortal.controller.BlockMinter;
import org.qortal.data.account.AccountData;
import org.qortal.data.transaction.BaseTransactionData;
import org.qortal.data.transaction.PaymentTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.data.transaction.TransferPrivsTransactionData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.test.common.*;
import org.qortal.test.common.transaction.TestTransaction;
import org.qortal.transform.Transformer;
import org.qortal.utils.Amounts;

import java.util.List;
import java.util.Random;

import static org.junit.Assert.*;

@Ignore(value = "Doesn't work, to be fixed later")
public class TransferPrivsTests extends Common {

	private static List<Integer> cumulativeBlocksByLevel;


	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();

		cumulativeBlocksByLevel = BlockChain.getInstance().getCumulativeBlocksByLevel();
	}

	@After
	public void afterTest() throws DataException {
		Common.orphanCheck();
	}

	@Test
	public void testNewAccountsTransferPrivs() throws DataException {
		Random random = new Random();

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");

			PrivateKeyAccount mintingAccount = Common.getTestAccount(repository, "alice-reward-share");

			byte[] randomPrivateKey = new byte[Transformer.PRIVATE_KEY_LENGTH];
			random.nextBytes(randomPrivateKey);
			PrivateKeyAccount randomAccount = new PrivateKeyAccount(repository, randomPrivateKey);

			// Alice sends random account an amount less than fee
			TransactionData transactionData = new PaymentTransactionData(TestTransaction.generateBase(alice), randomAccount.getAddress(), 1L);
			TransactionUtils.signAndMint(repository, transactionData, alice);

			byte[] recipientPublicKey = new byte[Transformer.PUBLIC_KEY_LENGTH];
			random.nextBytes(recipientPublicKey);
			PublicKeyAccount recipientAccount = new PublicKeyAccount(repository, recipientPublicKey);

			combineAccounts(repository, randomAccount, recipientAccount, mintingAccount);
		}
	}

	@Test
	public void testAliceIntoNewAccountTransferPrivs() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			assertTrue(alice.canMint(false));

			PrivateKeyAccount aliceMintingAccount = Common.getTestAccount(repository, "alice-reward-share");

			byte[] randomPublicKey = new byte[Transformer.PUBLIC_KEY_LENGTH];
			Random random = new Random();
			random.nextBytes(randomPublicKey);

			Account randomAccount = new PublicKeyAccount(repository, randomPublicKey);

			combineAccounts(repository, alice, randomAccount, aliceMintingAccount);

			assertFalse(alice.canMint(false));
			assertTrue(randomAccount.canMint(false));
		}
	}

	@Test
	public void testAliceIntoDilbertTransferPrivs() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount dilbert = Common.getTestAccount(repository, "dilbert");

			assertTrue(alice.canMint(false));
			assertTrue(dilbert.canMint(false));

			// Dilbert has level, Alice does not so we need Alice to mint enough blocks to bump Dilbert's level post-combine
			final int expectedPostCombineLevel = dilbert.getLevel() + 1;
			PrivateKeyAccount aliceMintingAccount = Common.getTestAccount(repository, "alice-reward-share");
			mintToSurpassLevelPostCombine(repository, aliceMintingAccount, dilbert);

			// Grab pre-combine versions of Alice and Dilbert data
			AccountData preCombineAliceData = repository.getAccountRepository().getAccount(alice.getAddress());
			AccountData preCombineDilbertData = repository.getAccountRepository().getAccount(dilbert.getAddress());

			// Combine Alice into Dilbert
			combineAccounts(repository, alice, dilbert, aliceMintingAccount);

			// Grab post-combine data
			AccountData postCombineAliceData = repository.getAccountRepository().getAccount(alice.getAddress());
			AccountData postCombineDilbertData = repository.getAccountRepository().getAccount(dilbert.getAddress());

			// Post-combine sender checks
			checkSenderPostTransfer(postCombineAliceData);
			assertFalse(alice.canMint(false));

			// Post-combine recipient checks
			checkRecipientPostTransfer(preCombineAliceData, preCombineDilbertData, postCombineDilbertData, expectedPostCombineLevel);
			assertTrue(dilbert.canMint(false));

			// Orphan previous block
			BlockUtils.orphanLastBlock(repository);

			// Sender checks
			AccountData orphanedAliceData = repository.getAccountRepository().getAccount(alice.getAddress());
			checkAccountDataRestored("sender", preCombineAliceData, orphanedAliceData);
			assertTrue(alice.canMint(false));

			// Recipient checks
			AccountData orphanedDilbertData = repository.getAccountRepository().getAccount(dilbert.getAddress());
			checkAccountDataRestored("recipient", preCombineDilbertData, orphanedDilbertData);
			assertTrue(dilbert.canMint(false));
		}
	}

	@Test
	public void testDilbertIntoAliceTransferPrivs() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount dilbert = Common.getTestAccount(repository, "dilbert");

			assertTrue(dilbert.canMint(false));
			assertTrue(alice.canMint(false));

			// Dilbert has level, Alice does not so we need Alice to mint enough blocks to surpass Dilbert's level post-combine
			final int expectedPostCombineLevel = dilbert.getLevel() + 1;
			PrivateKeyAccount mintingAccount = Common.getTestAccount(repository, "alice-reward-share");
			mintToSurpassLevelPostCombine(repository, mintingAccount, dilbert);

			// Grab pre-combine versions of Alice and Dilbert data
			AccountData preCombineAliceData = repository.getAccountRepository().getAccount(alice.getAddress());
			AccountData preCombineDilbertData = repository.getAccountRepository().getAccount(dilbert.getAddress());

			// Combine Dilbert into Alice
			combineAccounts(repository, dilbert, alice, mintingAccount);

			// Grab post-combine data
			AccountData postCombineAliceData = repository.getAccountRepository().getAccount(alice.getAddress());
			AccountData postCombineDilbertData = repository.getAccountRepository().getAccount(dilbert.getAddress());

			// Post-combine sender checks
			checkSenderPostTransfer(postCombineDilbertData);
			assertFalse(dilbert.canMint(false));

			// Post-combine recipient checks
			checkRecipientPostTransfer(preCombineDilbertData, preCombineAliceData, postCombineAliceData, expectedPostCombineLevel);
			assertTrue(alice.canMint(false));

			// Orphan previous block
			BlockUtils.orphanLastBlock(repository);

			// Sender checks
			AccountData orphanedDilbertData = repository.getAccountRepository().getAccount(dilbert.getAddress());
			checkAccountDataRestored("sender", preCombineDilbertData, orphanedDilbertData);
			assertTrue(dilbert.canMint(false));

			// Recipient checks
			AccountData orphanedAliceData = repository.getAccountRepository().getAccount(alice.getAddress());
			checkAccountDataRestored("recipient", preCombineAliceData, orphanedAliceData);
			assertTrue(alice.canMint(false));
		}
	}

	@Test
	public void testMultipleIntoChloeTransferPrivs() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			// Alice needs to mint block containing REWARD_SHARE BEFORE Alice loses minting privs
			byte[] aliceChloeRewardSharePrivateKey = AccountUtils.rewardShare(repository, "alice", "chloe", 0); // Block minted by Alice
			PrivateKeyAccount aliceChloeRewardShareAccount = new PrivateKeyAccount(repository, aliceChloeRewardSharePrivateKey);

			// Alice needs to mint block containing REWARD_SHARE BEFORE Alice loses minting privs
			byte[] dilbertRewardSharePrivateKey = AccountUtils.rewardShare(repository, "dilbert", "dilbert", 0); // Block minted by Alice
			PrivateKeyAccount dilbertRewardShareAccount = new PrivateKeyAccount(repository, dilbertRewardSharePrivateKey);

			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount chloe = Common.getTestAccount(repository, "chloe");
			TestAccount dilbert = Common.getTestAccount(repository, "dilbert");

			assertTrue(dilbert.canMint(false));
			assertFalse(chloe.canMint(false));

			// COMBINE DILBERT INTO CHLOE

			// Alice-Chloe reward share needs to mint enough blocks to surpass Dilbert's level post-combine
			final int expectedPost1stCombineLevel = dilbert.getLevel() + 1;
			mintToSurpassLevelPostCombine(repository, aliceChloeRewardShareAccount, dilbert);

			// Grab pre-combine versions of Dilbert and Chloe data
			AccountData pre1stCombineDilbertData = repository.getAccountRepository().getAccount(dilbert.getAddress());
			AccountData pre1stCombineChloeData = repository.getAccountRepository().getAccount(chloe.getAddress());
			final int pre1stCombineBlockHeight = repository.getBlockRepository().getBlockchainHeight();

			// Combine Dilbert into Chloe
			combineAccounts(repository, dilbert, chloe, dilbertRewardShareAccount);

			// Grab post-combine data
			AccountData post1stCombineDilbertData = repository.getAccountRepository().getAccount(dilbert.getAddress());
			AccountData post1stCombineChloeData = repository.getAccountRepository().getAccount(chloe.getAddress());

			// Post-combine sender checks
			checkSenderPostTransfer(post1stCombineDilbertData);
			assertFalse(dilbert.canMint(false));

			// Post-combine recipient checks
			checkRecipientPostTransfer(pre1stCombineDilbertData, pre1stCombineChloeData, post1stCombineChloeData, expectedPost1stCombineLevel);
			assertTrue(chloe.canMint(false));

			// COMBINE ALICE INTO CHLOE

			assertTrue(alice.canMint(false));
			assertTrue(chloe.canMint(false));

			// Alice needs to mint enough blocks to surpass Chloe's level post-combine
			final int expectedPost2ndCombineLevel = chloe.getLevel() + 1;
			PrivateKeyAccount aliceMintingAccount = Common.getTestAccount(repository, "alice-reward-share");
			mintToSurpassLevelPostCombine(repository, aliceMintingAccount, chloe);

			// Grab pre-combine versions of Alice and Chloe data
			AccountData pre2ndCombineAliceData = repository.getAccountRepository().getAccount(alice.getAddress());
			AccountData pre2ndCombineChloeData = repository.getAccountRepository().getAccount(chloe.getAddress());

			// Combine Alice into Chloe
			combineAccounts(repository, alice, chloe, aliceMintingAccount);

			// Grab post-combine data
			AccountData post2ndCombineAliceData = repository.getAccountRepository().getAccount(alice.getAddress());
			AccountData post2ndCombineChloeData = repository.getAccountRepository().getAccount(chloe.getAddress());

			// Post-combine sender checks
			checkSenderPostTransfer(post2ndCombineAliceData);
			assertFalse(alice.canMint(false));

			// Post-combine recipient checks
			checkRecipientPostTransfer(pre2ndCombineAliceData, pre2ndCombineChloeData, post2ndCombineChloeData, expectedPost2ndCombineLevel);
			assertTrue(chloe.canMint(false));

			// Orphan 2nd combine
			BlockUtils.orphanLastBlock(repository);

			// Sender checks
			AccountData orphanedAliceData = repository.getAccountRepository().getAccount(alice.getAddress());
			checkAccountDataRestored("sender", pre2ndCombineAliceData, orphanedAliceData);
			assertTrue(alice.canMint(false));

			// Recipient checks
			AccountData orphanedChloeData = repository.getAccountRepository().getAccount(chloe.getAddress());
			checkAccountDataRestored("recipient", pre2ndCombineChloeData, orphanedChloeData);
			assertTrue(chloe.canMint(false));

			// Orphan 1st combine
			BlockUtils.orphanToBlock(repository, pre1stCombineBlockHeight);

			// Sender checks
			AccountData orphanedDilbertData = repository.getAccountRepository().getAccount(dilbert.getAddress());
			checkAccountDataRestored("sender", pre1stCombineDilbertData, orphanedDilbertData);
			assertTrue(dilbert.canMint(false));

			// Recipient checks
			orphanedChloeData = repository.getAccountRepository().getAccount(chloe.getAddress());
			checkAccountDataRestored("recipient", pre1stCombineChloeData, orphanedChloeData);

			// Chloe canMint() would return true here due to Alice-Chloe reward-share minting at top of method, so undo that minting by orphaning back to block 1
			BlockUtils.orphanToBlock(repository, 1);
			assertFalse(chloe.canMint(false));
		}
	}

	/** Mint enough blocks, using <tt>mintingAccount</tt> so that minting account(s) will surpass <tt>targetAccount</tt>'s level post-combine. */
	private void mintToSurpassLevelPostCombine(Repository repository, PrivateKeyAccount mintingAccount, Account targetAccount) throws DataException {
		AccountData preMintAccountData = repository.getAccountRepository().getAccount(targetAccount.getAddress());
		final int minterBlocksNeeded = cumulativeBlocksByLevel.get(preMintAccountData.getLevel() + 1) - preMintAccountData.getBlocksMinted() - preMintAccountData.getBlocksMintedAdjustment();

		// Mint enough blocks to bump testAccount level
		for (int bc = 0; bc < minterBlocksNeeded; ++bc)
			BlockMinter.mintTestingBlock(repository, mintingAccount);
	}

	/** Combine sender's level, flags and block counts into recipient using TRANSFER_PRIVS transaction. */
	private void combineAccounts(Repository repository, PrivateKeyAccount senderAccount, Account recipientAccount, PrivateKeyAccount mintingAccount) throws DataException {
		byte[] reference = senderAccount.getLastReference();
		long timestamp = repository.getTransactionRepository().fromSignature(reference).getTimestamp() + 1;
		int txGroupId = 0;
		long fee = 1L * Amounts.MULTIPLIER;

		BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, txGroupId, reference, senderAccount.getPublicKey(), fee, null);
		TransactionData transactionData = new TransferPrivsTransactionData(baseTransactionData, recipientAccount.getAddress());

		TransactionUtils.signAndImportValid(repository, transactionData, senderAccount);
		BlockMinter.mintTestingBlock(repository, mintingAccount);
	}

	private void checkSenderPostTransfer(AccountData senderAccountData) {
		// Confirm sender has zeroed flags
		assertEquals("sender's flags should be zeroed", 0, (int) senderAccountData.getFlags());

		// Confirm sender has zeroed level
		assertEquals("sender's level should be zeroed", 0, (int) senderAccountData.getLevel());

		// Confirm sender has zeroed minted block count
		assertEquals("sender's minted block count should be zeroed", 0, (int) senderAccountData.getBlocksMinted());

		// Confirm sender has zeroed minted block adjustment
		assertEquals("sender's minted block adjustment should be zeroed", 0, (int) senderAccountData.getBlocksMintedAdjustment());
	}

	private void checkRecipientPostTransfer(AccountData preCombineSenderData, AccountData preCombineRecipientData, AccountData postCombineRecipientData, int expectedPostCombineLevel) {
		// Confirm recipient has bumped level
		assertEquals("recipient's level incorrect", expectedPostCombineLevel, postCombineRecipientData.getLevel());

		// Confirm recipient has gained sender's flags
		assertEquals("recipient's flags should be changed", preCombineSenderData.getFlags() | preCombineRecipientData.getFlags(), (int) postCombineRecipientData.getFlags());

		// Confirm recipient has increased minted block count
		assertEquals("recipient minted block count incorrect", preCombineRecipientData.getBlocksMinted() + preCombineSenderData.getBlocksMinted() + 1, postCombineRecipientData.getBlocksMinted());

		// Confirm recipient has increased minted block adjustment
		assertEquals("recipient minted block adjustment incorrect", preCombineRecipientData.getBlocksMintedAdjustment() + preCombineSenderData.getBlocksMintedAdjustment(), postCombineRecipientData.getBlocksMintedAdjustment());
	}

	private void checkAccountDataRestored(String accountName, AccountData expectedAccountData, AccountData actualAccountData) {
		// Confirm flags have been restored
		assertEquals(accountName + "'s flags weren't restored", expectedAccountData.getFlags(), actualAccountData.getFlags());

		// Confirm minted blocks count
		assertEquals(accountName + "'s minted block count wasn't restored", expectedAccountData.getBlocksMinted(), actualAccountData.getBlocksMinted());

		// Confirm minted block adjustment
		assertEquals(accountName + "'s minted block adjustment wasn't restored", expectedAccountData.getBlocksMintedAdjustment(), actualAccountData.getBlocksMintedAdjustment());

		// Confirm level has been restored
		assertEquals(accountName + "'s level wasn't restored", expectedAccountData.getLevel(), actualAccountData.getLevel());
	}

}
