package org.qortal.test;

import org.junit.Before;
import org.junit.Test;
import org.qortal.account.Account;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.asset.Asset;
import org.qortal.block.Block;
import org.qortal.controller.BlockMinter;
import org.qortal.data.transaction.BaseTransactionData;
import org.qortal.data.transaction.PaymentTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.data.transaction.TransferPrivsTransactionData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.settings.Settings;
import org.qortal.test.common.*;
import org.qortal.test.common.transaction.TestTransaction;
import org.qortal.transaction.Transaction;
import org.qortal.transaction.TransferPrivsTransaction;
import org.qortal.utils.NTP;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.hamcrest.core.IsNot.not;
import static org.junit.Assert.*;
import static org.qortal.test.common.AccountUtils.fee;
import static org.qortal.transaction.Transaction.ValidationResult.ACCOUNT_NOT_TRANSFERABLE;
import static org.qortal.transaction.Transaction.ValidationResult.OK;

public class SelfSponsorshipAlgoV1Tests extends Common {

	
	@Before
	public void beforeTest() throws DataException {
		Common.useSettings("test-settings-v2-self-sponsorship-algo-v1.json");
		NTP.setFixedOffset(Settings.getInstance().getTestNtpOffset());
	}


	@Test
	public void testSingleSponsor() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			final int initialRewardShareCount = repository.getAccountRepository().getRewardShares().size();

			// Alice self share online, and will be used to mint the blocks
			PrivateKeyAccount aliceSelfShare = Common.getTestAccount(repository, "alice-reward-share");
			List<PrivateKeyAccount> onlineAccounts = new ArrayList<>();
			onlineAccounts.add(aliceSelfShare);

			PrivateKeyAccount bobAccount = Common.getTestAccount(repository, "bob");

			// Bob self sponsors 10 accounts
			List<PrivateKeyAccount> bobSponsees = AccountUtils.generateSponsorshipRewardShares(repository, bobAccount, 10);
			List<PrivateKeyAccount> bobSponseesOnlineAccounts = AccountUtils.toRewardShares(repository, bobAccount, bobSponsees);
			onlineAccounts.addAll(bobSponseesOnlineAccounts);

			// Mint blocks
			Block block = null;
			for (int i = 0; i <= 1; i++)
				block = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));

			assertEquals(11, block.getBlockData().getOnlineAccountsCount());
			assertEquals(10, repository.getAccountRepository().getRewardShares().size() - initialRewardShareCount);

			for (PrivateKeyAccount bobSponsee : bobSponsees)
				assertTrue(new Account(repository, bobSponsee.getAddress()).getLevel() == 0);

			// Mint some blocks, until accounts have leveled up
			for (int i = 0; i <= 5; i++)
				block = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));

			for (PrivateKeyAccount bobSponsee : bobSponsees)
				assertTrue(new Account(repository, bobSponsee.getAddress()).getLevel() > 0);

			// Generate self shares so the sponsees can start minting
			List<PrivateKeyAccount> bobSponseeSelfShares = AccountUtils.generateSelfShares(repository, bobSponsees);
			onlineAccounts.addAll(bobSponseeSelfShares);

			// Mint blocks
			for (int i = 0; i <= 1; i++)
				block = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));

			for (PrivateKeyAccount bobSponsee : bobSponsees)
				assertTrue(new Account(repository, bobSponsee.getAddress()).getConfirmedBalance(Asset.QORT) > 10); // 5 for transaction, 5 for fee

			// Bob then consolidates funds
			consolidateFunds(repository, bobSponsees, bobAccount);

			// Mint until block 19 (the algo runs at block 20)
			while (block.getBlockData().getHeight() < 19)
				block = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));
			assertEquals(19, (int) block.getBlockData().getHeight());

			// Ensure that bob and his sponsees have no penalties
			List<PrivateKeyAccount> bobAndSponsees = new ArrayList<>(bobSponsees);
			bobAndSponsees.add(bobAccount);
			for (PrivateKeyAccount bobSponsee : bobAndSponsees)
				assertEquals(0, (int) new Account(repository, bobSponsee.getAddress()).getBlocksMintedPenalty());

			// Mint a block, so the algo runs
			block = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));

			// Ensure that bob and his sponsees now have penalties
			for (PrivateKeyAccount bobSponsee : bobAndSponsees)
				assertEquals(-5000000, (int) new Account(repository, bobSponsee.getAddress()).getBlocksMintedPenalty());

			// Ensure that bob and his sponsees are now level 0
			for (PrivateKeyAccount bobSponsee : bobAndSponsees)
				assertEquals(0, (int) new Account(repository, bobSponsee.getAddress()).getLevel());

			// Orphan last block
			BlockUtils.orphanLastBlock(repository);

			// Ensure that bob and his sponsees are now greater than level 0
			for (PrivateKeyAccount bobSponsee : bobAndSponsees)
				assertTrue(new Account(repository, bobSponsee.getAddress()).getLevel() > 0);

			// Ensure that bob and his sponsees have no penalties again
			for (PrivateKeyAccount bobSponsee : bobAndSponsees)
				assertEquals(0, (int) new Account(repository, bobSponsee.getAddress()).getBlocksMintedPenalty());

			// Run orphan check - this can't be in afterTest() because some tests access the live db
			Common.orphanCheck();
		}
	}

	@Test
	public void testMultipleSponsors() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			final int initialRewardShareCount = repository.getAccountRepository().getRewardShares().size();

			// Alice self share online, and will be used to mint the blocks
			PrivateKeyAccount aliceSelfShare = Common.getTestAccount(repository, "alice-reward-share");
			List<PrivateKeyAccount> onlineAccounts = new ArrayList<>();
			onlineAccounts.add(aliceSelfShare);

			PrivateKeyAccount bobAccount = Common.getTestAccount(repository, "bob");
			PrivateKeyAccount chloeAccount = Common.getTestAccount(repository, "chloe");
			PrivateKeyAccount dilbertAccount = Common.getTestAccount(repository, "dilbert");

			// Bob sponsors 10 accounts
			List<PrivateKeyAccount> bobSponsees = AccountUtils.generateSponsorshipRewardShares(repository, bobAccount, 10);
			List<PrivateKeyAccount> bobSponseesOnlineAccounts = AccountUtils.toRewardShares(repository, bobAccount, bobSponsees);
			onlineAccounts.addAll(bobSponseesOnlineAccounts);

			// Chloe sponsors 10 accounts
			List<PrivateKeyAccount> chloeSponsees = AccountUtils.generateSponsorshipRewardShares(repository, chloeAccount, 10);
			List<PrivateKeyAccount> chloeSponseesOnlineAccounts = AccountUtils.toRewardShares(repository, chloeAccount, chloeSponsees);
			onlineAccounts.addAll(chloeSponseesOnlineAccounts);

			// Dilbert sponsors 5 accounts
			List<PrivateKeyAccount> dilbertSponsees = AccountUtils.generateSponsorshipRewardShares(repository, dilbertAccount, 5);
			List<PrivateKeyAccount> dilbertSponseesOnlineAccounts = AccountUtils.toRewardShares(repository, dilbertAccount, dilbertSponsees);
			onlineAccounts.addAll(dilbertSponseesOnlineAccounts);

			// Mint blocks
			Block block = null;
			for (int i = 0; i <= 1; i++)
				block = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));

			assertEquals(26, block.getBlockData().getOnlineAccountsCount());
			assertEquals(25, repository.getAccountRepository().getRewardShares().size() - initialRewardShareCount);

			for (PrivateKeyAccount bobSponsee : bobSponsees)
				assertTrue(new Account(repository, bobSponsee.getAddress()).getLevel() == 0);

			// Mint some blocks, until accounts have leveled up
			for (int i = 0; i <= 5; i++)
				block = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));

			for (PrivateKeyAccount bobSponsee : bobSponsees)
				assertTrue(new Account(repository, bobSponsee.getAddress()).getLevel() > 0);

			// Generate self shares so the sponsees can start minting
			List<PrivateKeyAccount> bobSponseeSelfShares = AccountUtils.generateSelfShares(repository, bobSponsees);
			onlineAccounts.addAll(bobSponseeSelfShares);

			// Mint blocks
			for (int i = 0; i <= 1; i++)
				block = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));

			for (PrivateKeyAccount bobSponsee : bobSponsees)
				assertTrue(new Account(repository, bobSponsee.getAddress()).getConfirmedBalance(Asset.QORT) > 10); // 5 for transaction, 5 for fee

			// Bob then consolidates funds
			consolidateFunds(repository, bobSponsees, bobAccount);

			// Mint until block 19 (the algo runs at block 20)
			while (block.getBlockData().getHeight() < 19)
				block = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));
			assertEquals(19, (int) block.getBlockData().getHeight());

			// Ensure that bob and his sponsees have no penalties
			List<PrivateKeyAccount> bobAndSponsees = new ArrayList<>(bobSponsees);
			bobAndSponsees.add(bobAccount);
			for (PrivateKeyAccount bobSponsee : bobAndSponsees)
				assertEquals(0, (int) new Account(repository, bobSponsee.getAddress()).getBlocksMintedPenalty());

			assertEquals(6, (int)dilbertAccount.getLevel());

			// Mint a block, so the algo runs
			block = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));

			// Ensure that bob and his sponsees now have penalties
			for (PrivateKeyAccount bobSponsee : bobAndSponsees)
				assertEquals(-5000000, (int) new Account(repository, bobSponsee.getAddress()).getBlocksMintedPenalty());

			// Ensure that chloe and her sponsees have no penalties
			List<PrivateKeyAccount> chloeAndSponsees = new ArrayList<>(chloeSponsees);
			chloeAndSponsees.add(chloeAccount);
			for (PrivateKeyAccount chloeSponsee : chloeAndSponsees)
				assertEquals(0, (int) new Account(repository, chloeSponsee.getAddress()).getBlocksMintedPenalty());

			// Ensure that dilbert and his sponsees have no penalties
			List<PrivateKeyAccount> dilbertAndSponsees = new ArrayList<>(dilbertSponsees);
			dilbertAndSponsees.add(dilbertAccount);
			for (PrivateKeyAccount dilbertSponsee : dilbertAndSponsees)
				assertEquals(0, (int) new Account(repository, dilbertSponsee.getAddress()).getBlocksMintedPenalty());

			// Ensure that bob and his sponsees are now level 0
			for (PrivateKeyAccount bobSponsee : bobAndSponsees)
				assertEquals(0, (int) new Account(repository, bobSponsee.getAddress()).getLevel());

			// Orphan last block
			BlockUtils.orphanLastBlock(repository);

			// Ensure that bob and his sponsees are now greater than level 0
			for (PrivateKeyAccount bobSponsee : bobAndSponsees)
				assertTrue(new Account(repository, bobSponsee.getAddress()).getLevel() > 0);

			// Ensure that bob and his sponsees have no penalties again
			for (PrivateKeyAccount bobSponsee : bobAndSponsees)
				assertEquals(0, (int) new Account(repository, bobSponsee.getAddress()).getBlocksMintedPenalty());

			// Ensure that chloe and her sponsees still have no penalties
			for (PrivateKeyAccount chloeSponsee : chloeAndSponsees)
				assertEquals(0, (int) new Account(repository, chloeSponsee.getAddress()).getBlocksMintedPenalty());

			// Ensure that dilbert and his sponsees still have no penalties
			for (PrivateKeyAccount dilbertSponsee : dilbertAndSponsees)
				assertEquals(0, (int) new Account(repository, dilbertSponsee.getAddress()).getBlocksMintedPenalty());

			// Run orphan check - this can't be in afterTest() because some tests access the live db
			Common.orphanCheck();
		}
	}

	@Test
	public void testMintBlockWithSignerPenalty() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			final int initialRewardShareCount = repository.getAccountRepository().getRewardShares().size();

			List<PrivateKeyAccount> onlineAccountsAliceSigner = new ArrayList<>();
			List<PrivateKeyAccount> onlineAccountsBobSigner = new ArrayList<>();

			// Alice self share online, and will be used to mint (some of) the blocks
			PrivateKeyAccount aliceSelfShare = Common.getTestAccount(repository, "alice-reward-share");
			onlineAccountsAliceSigner.add(aliceSelfShare);

			// Bob self share online, and will be used to mint (some of) the blocks
			PrivateKeyAccount bobSelfShare = Common.getTestAccount(repository, "bob-reward-share");
			onlineAccountsBobSigner.add(bobSelfShare);

			// Include Alice and Bob's online accounts in each other's arrays
			onlineAccountsAliceSigner.add(bobSelfShare);
			onlineAccountsBobSigner.add(aliceSelfShare);

			PrivateKeyAccount bobAccount = Common.getTestAccount(repository, "bob");
			PrivateKeyAccount chloeAccount = Common.getTestAccount(repository, "chloe");
			PrivateKeyAccount dilbertAccount = Common.getTestAccount(repository, "dilbert");

			// Bob sponsors 10 accounts
			List<PrivateKeyAccount> bobSponsees = AccountUtils.generateSponsorshipRewardShares(repository, bobAccount, 10);
			List<PrivateKeyAccount> bobSponseesOnlineAccounts = AccountUtils.toRewardShares(repository, bobAccount, bobSponsees);
			onlineAccountsAliceSigner.addAll(bobSponseesOnlineAccounts);
			onlineAccountsBobSigner.addAll(bobSponseesOnlineAccounts);

			// Chloe sponsors 10 accounts
			List<PrivateKeyAccount> chloeSponsees = AccountUtils.generateSponsorshipRewardShares(repository, chloeAccount, 10);
			List<PrivateKeyAccount> chloeSponseesOnlineAccounts = AccountUtils.toRewardShares(repository, chloeAccount, chloeSponsees);
			onlineAccountsAliceSigner.addAll(chloeSponseesOnlineAccounts);
			onlineAccountsBobSigner.addAll(chloeSponseesOnlineAccounts);

			// Dilbert sponsors 5 accounts
			List<PrivateKeyAccount> dilbertSponsees = AccountUtils.generateSponsorshipRewardShares(repository, dilbertAccount, 5);
			List<PrivateKeyAccount> dilbertSponseesOnlineAccounts = AccountUtils.toRewardShares(repository, dilbertAccount, dilbertSponsees);
			onlineAccountsAliceSigner.addAll(dilbertSponseesOnlineAccounts);
			onlineAccountsBobSigner.addAll(dilbertSponseesOnlineAccounts);

			// Mint blocks (Bob is the signer)
			Block block = null;
			for (int i = 0; i <= 1; i++)
				block = BlockMinter.mintTestingBlock(repository, onlineAccountsBobSigner.toArray(new PrivateKeyAccount[0]));

			// Get reward share transaction count
			assertEquals(25, repository.getAccountRepository().getRewardShares().size() - initialRewardShareCount);

			for (PrivateKeyAccount bobSponsee : bobSponsees)
				assertTrue(new Account(repository, bobSponsee.getAddress()).getLevel() == 0);

			// Mint some blocks, until accounts have leveled up (Bob is the signer)
			for (int i = 0; i <= 5; i++)
				block = BlockMinter.mintTestingBlock(repository, onlineAccountsBobSigner.toArray(new PrivateKeyAccount[0]));

			for (PrivateKeyAccount bobSponsee : bobSponsees)
				assertTrue(new Account(repository, bobSponsee.getAddress()).getLevel() > 0);

			// Generate self shares so the sponsees can start minting
			List<PrivateKeyAccount> bobSponseeSelfShares = AccountUtils.generateSelfShares(repository, bobSponsees);
			onlineAccountsAliceSigner.addAll(bobSponseeSelfShares);
			onlineAccountsBobSigner.addAll(bobSponseeSelfShares);

			// Mint blocks (Bob is the signer)
			for (int i = 0; i <= 1; i++)
				block = BlockMinter.mintTestingBlock(repository, onlineAccountsBobSigner.toArray(new PrivateKeyAccount[0]));

			for (PrivateKeyAccount bobSponsee : bobSponsees)
				assertTrue(new Account(repository, bobSponsee.getAddress()).getConfirmedBalance(Asset.QORT) > 10); // 5 for transaction, 5 for fee

			// Bob then consolidates funds
			consolidateFunds(repository, bobSponsees, bobAccount);

			// Mint until block 19 (the algo runs at block 20) (Bob is the signer)
			while (block.getBlockData().getHeight() < 19)
				block = BlockMinter.mintTestingBlock(repository, onlineAccountsBobSigner.toArray(new PrivateKeyAccount[0]));
			assertEquals(19, (int) block.getBlockData().getHeight());

			// Ensure that bob and his sponsees have no penalties
			List<PrivateKeyAccount> bobAndSponsees = new ArrayList<>(bobSponsees);
			bobAndSponsees.add(bobAccount);
			for (PrivateKeyAccount bobSponsee : bobAndSponsees)
				assertEquals(0, (int) new Account(repository, bobSponsee.getAddress()).getBlocksMintedPenalty());

			// Mint a block, so the algo runs (Bob is the signer)
			// Block should be valid, because new account levels don't take effect until next block's validation
			block = BlockMinter.mintTestingBlock(repository, onlineAccountsBobSigner.toArray(new PrivateKeyAccount[0]));

			// Ensure that bob and his sponsees now have penalties
			for (PrivateKeyAccount bobSponsee : bobAndSponsees)
				assertEquals(-5000000, (int) new Account(repository, bobSponsee.getAddress()).getBlocksMintedPenalty());

			// Ensure that bob and his sponsees are now level 0
			for (PrivateKeyAccount bobSponsee : bobAndSponsees)
				assertEquals(0, (int) new Account(repository, bobSponsee.getAddress()).getLevel());

			// Mint a block, but Bob is now an invalid signer because he is level 0
			block = BlockMinter.mintTestingBlockUnvalidated(repository, onlineAccountsBobSigner.toArray(new PrivateKeyAccount[0]));
			// Block should be null as it's unable to be minted
			assertNull(block);

			// Mint the same block with Alice as the signer, and this time it should be valid
			block = BlockMinter.mintTestingBlock(repository, onlineAccountsAliceSigner.toArray(new PrivateKeyAccount[0]));
			// Block should NOT be null
			assertNotNull(block);

			// Run orphan check - this can't be in afterTest() because some tests access the live db
			Common.orphanCheck();
		}
	}

	@Test
	public void testMintBlockWithFounderSignerPenalty() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			final int initialRewardShareCount = repository.getAccountRepository().getRewardShares().size();

			List<PrivateKeyAccount> onlineAccountsAliceSigner = new ArrayList<>();
			List<PrivateKeyAccount> onlineAccountsBobSigner = new ArrayList<>();

			// Alice self share online, and will be used to mint (some of) the blocks
			PrivateKeyAccount aliceSelfShare = Common.getTestAccount(repository, "alice-reward-share");
			onlineAccountsAliceSigner.add(aliceSelfShare);

			// Bob self share online, and will be used to mint (some of) the blocks
			PrivateKeyAccount bobSelfShare = Common.getTestAccount(repository, "bob-reward-share");
			onlineAccountsBobSigner.add(bobSelfShare);

			// Include Alice and Bob's online accounts in each other's arrays
			onlineAccountsAliceSigner.add(bobSelfShare);
			onlineAccountsBobSigner.add(aliceSelfShare);

			PrivateKeyAccount aliceAccount = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount bobAccount = Common.getTestAccount(repository, "bob");

			// Alice sponsors 10 accounts
			List<PrivateKeyAccount> aliceSponsees = AccountUtils.generateSponsorshipRewardShares(repository, aliceAccount, 10);
			List<PrivateKeyAccount> aliceSponseesOnlineAccounts = AccountUtils.toRewardShares(repository, aliceAccount, aliceSponsees);
			onlineAccountsAliceSigner.addAll(aliceSponseesOnlineAccounts);
			onlineAccountsBobSigner.addAll(aliceSponseesOnlineAccounts);

			// Bob sponsors 9 accounts
			List<PrivateKeyAccount> bobSponsees = AccountUtils.generateSponsorshipRewardShares(repository, bobAccount, 9);
			List<PrivateKeyAccount> bobSponseesOnlineAccounts = AccountUtils.toRewardShares(repository, bobAccount, bobSponsees);
			onlineAccountsAliceSigner.addAll(bobSponseesOnlineAccounts);
			onlineAccountsBobSigner.addAll(bobSponseesOnlineAccounts);

			// Mint blocks (Bob is the signer)
			Block block = null;
			for (int i = 0; i <= 1; i++)
				block = BlockMinter.mintTestingBlock(repository, onlineAccountsAliceSigner.toArray(new PrivateKeyAccount[0]));

			// Get reward share transaction count
			assertEquals(19, repository.getAccountRepository().getRewardShares().size() - initialRewardShareCount);

			for (PrivateKeyAccount aliceSponsee : aliceSponsees)
				assertTrue(new Account(repository, aliceSponsee.getAddress()).getLevel() == 0);

			// Mint some blocks, until accounts have leveled up (Alice is the signer)
			for (int i = 0; i <= 5; i++)
				block = BlockMinter.mintTestingBlock(repository, onlineAccountsAliceSigner.toArray(new PrivateKeyAccount[0]));

			for (PrivateKeyAccount aliceSponsee : aliceSponsees)
				assertTrue(new Account(repository, aliceSponsee.getAddress()).getLevel() > 0);

			// Generate self shares so the sponsees can start minting
			List<PrivateKeyAccount> aliceSponseeSelfShares = AccountUtils.generateSelfShares(repository, aliceSponsees);
			onlineAccountsAliceSigner.addAll(aliceSponseeSelfShares);
			onlineAccountsBobSigner.addAll(aliceSponseeSelfShares);

			// Mint blocks (Bob is the signer)
			for (int i = 0; i <= 1; i++)
				block = BlockMinter.mintTestingBlock(repository, onlineAccountsAliceSigner.toArray(new PrivateKeyAccount[0]));

			for (PrivateKeyAccount aliceSponsee : aliceSponsees)
				assertTrue(new Account(repository, aliceSponsee.getAddress()).getConfirmedBalance(Asset.QORT) > 10); // 5 for transaction, 5 for fee

			// Alice then consolidates funds
			consolidateFunds(repository, aliceSponsees, aliceAccount);

			// Mint until block 19 (the algo runs at block 20) (Bob is the signer)
			while (block.getBlockData().getHeight() < 19)
				block = BlockMinter.mintTestingBlock(repository, onlineAccountsAliceSigner.toArray(new PrivateKeyAccount[0]));
			assertEquals(19, (int) block.getBlockData().getHeight());

			// Ensure that alice and her sponsees have no penalties
			List<PrivateKeyAccount> aliceAndSponsees = new ArrayList<>(aliceSponsees);
			aliceAndSponsees.add(aliceAccount);
			for (PrivateKeyAccount aliceSponsee : aliceAndSponsees)
				assertEquals(0, (int) new Account(repository, aliceSponsee.getAddress()).getBlocksMintedPenalty());

			// Mint a block, so the algo runs (Alice is the signer)
			// Block should be valid, because new account levels don't take effect until next block's validation
			block = BlockMinter.mintTestingBlock(repository, onlineAccountsAliceSigner.toArray(new PrivateKeyAccount[0]));

			// Ensure that alice and her sponsees now have penalties
			for (PrivateKeyAccount aliceSponsee : aliceAndSponsees)
				assertEquals(-5000000, (int) new Account(repository, aliceSponsee.getAddress()).getBlocksMintedPenalty());

			// Ensure that alice and her sponsees are now level 0
			for (PrivateKeyAccount aliceSponsee : aliceAndSponsees)
				assertEquals(0, (int) new Account(repository, aliceSponsee.getAddress()).getLevel());

			// Mint a block, but Alice is now an invalid signer because she has lost founder minting abilities
			block = BlockMinter.mintTestingBlockUnvalidated(repository, onlineAccountsAliceSigner.toArray(new PrivateKeyAccount[0]));
			// Block should be null as it's unable to be minted
			assertNull(block);

			// Mint the same block with Bob as the signer, and this time it should be valid
			block = BlockMinter.mintTestingBlock(repository, onlineAccountsBobSigner.toArray(new PrivateKeyAccount[0]));
			// Block should NOT be null
			assertNotNull(block);

			// Run orphan check - this can't be in afterTest() because some tests access the live db
			Common.orphanCheck();
		}
	}

	@Test
	public void testOnlineAccountsWithPenalties() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			final int initialRewardShareCount = repository.getAccountRepository().getRewardShares().size();

			// Alice self share online, and will be used to mint the blocks
			PrivateKeyAccount aliceSelfShare = Common.getTestAccount(repository, "alice-reward-share");
			List<PrivateKeyAccount> onlineAccounts = new ArrayList<>();
			onlineAccounts.add(aliceSelfShare);

			// Bob self share online
			PrivateKeyAccount bobSelfShare = Common.getTestAccount(repository, "bob-reward-share");
			onlineAccounts.add(bobSelfShare);

			PrivateKeyAccount bobAccount = Common.getTestAccount(repository, "bob");
			PrivateKeyAccount chloeAccount = Common.getTestAccount(repository, "chloe");
			PrivateKeyAccount dilbertAccount = Common.getTestAccount(repository, "dilbert");

			// Bob sponsors 10 accounts
			List<PrivateKeyAccount> bobSponsees = AccountUtils.generateSponsorshipRewardShares(repository, bobAccount, 10);
			List<PrivateKeyAccount> bobSponseesOnlineAccounts = AccountUtils.toRewardShares(repository, bobAccount, bobSponsees);
			onlineAccounts.addAll(bobSponseesOnlineAccounts);

			// Chloe sponsors 10 accounts
			List<PrivateKeyAccount> chloeSponsees = AccountUtils.generateSponsorshipRewardShares(repository, chloeAccount, 10);
			List<PrivateKeyAccount> chloeSponseesOnlineAccounts = AccountUtils.toRewardShares(repository, chloeAccount, chloeSponsees);
			onlineAccounts.addAll(chloeSponseesOnlineAccounts);

			// Dilbert sponsors 5 accounts
			List<PrivateKeyAccount> dilbertSponsees = AccountUtils.generateSponsorshipRewardShares(repository, dilbertAccount, 5);
			List<PrivateKeyAccount> dilbertSponseesOnlineAccounts = AccountUtils.toRewardShares(repository, dilbertAccount, dilbertSponsees);
			onlineAccounts.addAll(dilbertSponseesOnlineAccounts);

			// Mint blocks
			Block block = null;
			for (int i = 0; i <= 1; i++)
				block = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));

			assertEquals(27, block.getBlockData().getOnlineAccountsCount());
			assertEquals(25, repository.getAccountRepository().getRewardShares().size() - initialRewardShareCount);

			for (PrivateKeyAccount bobSponsee : bobSponsees)
				assertTrue(new Account(repository, bobSponsee.getAddress()).getLevel() == 0);

			// Mint some blocks, until accounts have leveled up
			for (int i = 0; i <= 5; i++)
				block = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));

			for (PrivateKeyAccount bobSponsee : bobSponsees)
				assertTrue(new Account(repository, bobSponsee.getAddress()).getLevel() > 0);

			// Generate self shares so the sponsees can start minting
			List<PrivateKeyAccount> bobSponseeSelfShares = AccountUtils.generateSelfShares(repository, bobSponsees);
			onlineAccounts.addAll(bobSponseeSelfShares);

			// Mint blocks
			for (int i = 0; i <= 1; i++)
				block = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));

			for (PrivateKeyAccount bobSponsee : bobSponsees)
				assertTrue(new Account(repository, bobSponsee.getAddress()).getConfirmedBalance(Asset.QORT) > 10); // 5 for transaction, 5 for fee

			// Bob then consolidates funds
			consolidateFunds(repository, bobSponsees, bobAccount);

			// Mint until block 19 (the algo runs at block 20)
			while (block.getBlockData().getHeight() < 19)
				block = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));
			assertEquals(19, (int) block.getBlockData().getHeight());

			// Ensure that bob and his sponsees have no penalties
			List<PrivateKeyAccount> bobAndSponsees = new ArrayList<>(bobSponsees);
			bobAndSponsees.add(bobAccount);
			for (PrivateKeyAccount bobSponsee : bobAndSponsees)
				assertEquals(0, (int) new Account(repository, bobSponsee.getAddress()).getBlocksMintedPenalty());

			// Ensure that bob and his sponsees are present in block's online accounts
			assertTrue(areAllAccountsPresentInBlock(bobAndSponsees, block));

			// Ensure that chloe's sponsees are present in block's online accounts
			assertTrue(areAllAccountsPresentInBlock(chloeSponsees, block));

			// Ensure that dilbert's sponsees are present in block's online accounts
			assertTrue(areAllAccountsPresentInBlock(dilbertSponsees, block));

			// Mint a block, so the algo runs
			block = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));

			// Ensure that bob and his sponsees now have penalties
			for (PrivateKeyAccount bobSponsee : bobAndSponsees)
				assertEquals(-5000000, (int) new Account(repository, bobSponsee.getAddress()).getBlocksMintedPenalty());

			// Ensure that bob and his sponsees are still present in block's online accounts
			assertTrue(areAllAccountsPresentInBlock(bobAndSponsees, block));

			// Mint another few blocks
			while (block.getBlockData().getHeight() < 24)
				block = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));

			assertEquals(24, (int)block.getBlockData().getHeight());

			// Ensure that bob and his sponsees are NOT present in block's online accounts (due to penalties)
			assertFalse(areAllAccountsPresentInBlock(bobAndSponsees, block));

			// Ensure that chloe's sponsees are still present in block's online accounts
			assertTrue(areAllAccountsPresentInBlock(chloeSponsees, block));

			// Ensure that dilbert's sponsees are still present in block's online accounts
			assertTrue(areAllAccountsPresentInBlock(dilbertSponsees, block));

			// Run orphan check - this can't be in afterTest() because some tests access the live db
			Common.orphanCheck();
		}
	}

	@Test
	public void testFounderOnlineAccountsWithPenalties() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			final int initialRewardShareCount = repository.getAccountRepository().getRewardShares().size();

			// Bob self share online, and will be used to mint the blocks
			PrivateKeyAccount bobSelfShare = Common.getTestAccount(repository, "bob-reward-share");
			List<PrivateKeyAccount> onlineAccounts = new ArrayList<>();
			onlineAccounts.add(bobSelfShare);

			// Alice self share online
			PrivateKeyAccount aliceSelfShare = Common.getTestAccount(repository, "alice-reward-share");
			onlineAccounts.add(aliceSelfShare);

			PrivateKeyAccount aliceAccount = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount bobAccount = Common.getTestAccount(repository, "bob");

			// Alice sponsors 10 accounts
			List<PrivateKeyAccount> aliceSponsees = AccountUtils.generateSponsorshipRewardShares(repository, aliceAccount, 10);
			List<PrivateKeyAccount> aliceSponseesOnlineAccounts = AccountUtils.toRewardShares(repository, aliceAccount, aliceSponsees);
			onlineAccounts.addAll(aliceSponseesOnlineAccounts);
			onlineAccounts.addAll(aliceSponseesOnlineAccounts);

			// Bob sponsors 9 accounts
			List<PrivateKeyAccount> bobSponsees = AccountUtils.generateSponsorshipRewardShares(repository, bobAccount, 9);
			List<PrivateKeyAccount> bobSponseesOnlineAccounts = AccountUtils.toRewardShares(repository, bobAccount, bobSponsees);
			onlineAccounts.addAll(bobSponseesOnlineAccounts);
			onlineAccounts.addAll(bobSponseesOnlineAccounts);

			// Mint blocks (Bob is the signer)
			Block block = null;
			for (int i = 0; i <= 1; i++)
				block = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));

			// Get reward share transaction count
			assertEquals(19, repository.getAccountRepository().getRewardShares().size() - initialRewardShareCount);

			for (PrivateKeyAccount aliceSponsee : aliceSponsees)
				assertTrue(new Account(repository, aliceSponsee.getAddress()).getLevel() == 0);

			// Mint some blocks, until accounts have leveled up (Alice is the signer)
			for (int i = 0; i <= 5; i++)
				block = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));

			for (PrivateKeyAccount aliceSponsee : aliceSponsees)
				assertTrue(new Account(repository, aliceSponsee.getAddress()).getLevel() > 0);

			// Generate self shares so the sponsees can start minting
			List<PrivateKeyAccount> aliceSponseeSelfShares = AccountUtils.generateSelfShares(repository, aliceSponsees);
			onlineAccounts.addAll(aliceSponseeSelfShares);

			// Mint blocks (Bob is the signer)
			for (int i = 0; i <= 1; i++)
				block = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));

			for (PrivateKeyAccount aliceSponsee : aliceSponsees)
				assertTrue(new Account(repository, aliceSponsee.getAddress()).getConfirmedBalance(Asset.QORT) > 10); // 5 for transaction, 5 for fee

			// Alice then consolidates funds
			consolidateFunds(repository, aliceSponsees, aliceAccount);

			// Mint until block 19 (the algo runs at block 20) (Bob is the signer)
			while (block.getBlockData().getHeight() < 19)
				block = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));
			assertEquals(19, (int) block.getBlockData().getHeight());

			// Ensure that alice and her sponsees have no penalties
			List<PrivateKeyAccount> aliceAndSponsees = new ArrayList<>(aliceSponsees);
			aliceAndSponsees.add(aliceAccount);
			for (PrivateKeyAccount aliceSponsee : aliceAndSponsees)
				assertEquals(0, (int) new Account(repository, aliceSponsee.getAddress()).getBlocksMintedPenalty());

			// Mint a block, so the algo runs (Alice is the signer)
			// Block should be valid, because new account levels don't take effect until next block's validation
			block = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));

			// Ensure that alice and her sponsees now have penalties
			for (PrivateKeyAccount aliceSponsee : aliceAndSponsees)
				assertEquals(-5000000, (int) new Account(repository, aliceSponsee.getAddress()).getBlocksMintedPenalty());

			// Ensure that alice and her sponsees are now level 0
			for (PrivateKeyAccount aliceSponsee : aliceAndSponsees)
				assertEquals(0, (int) new Account(repository, aliceSponsee.getAddress()).getLevel());

			// Ensure that alice and her sponsees don't have penalties
			List<PrivateKeyAccount> bobAndSponsees = new ArrayList<>(bobSponsees);
			bobAndSponsees.add(bobAccount);
			for (PrivateKeyAccount bobSponsee : bobAndSponsees)
				assertEquals(0, (int) new Account(repository, bobSponsee.getAddress()).getBlocksMintedPenalty());

			// Ensure that bob and his sponsees are still present in block's online accounts
			assertTrue(areAllAccountsPresentInBlock(bobAndSponsees, block));

			// Ensure that alice and her sponsees are still present in block's online accounts
			assertTrue(areAllAccountsPresentInBlock(aliceAndSponsees, block));

			// Mint another few blocks
			while (block.getBlockData().getHeight() < 24)
				block = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));

			assertEquals(24, (int)block.getBlockData().getHeight());

			// Ensure that alice and her sponsees are NOT present in block's online accounts (due to penalties)
			assertFalse(areAllAccountsPresentInBlock(aliceAndSponsees, block));

			// Ensure that bob and his sponsees are still present in block's online accounts
			assertTrue(areAllAccountsPresentInBlock(bobAndSponsees, block));

			// Run orphan check - this can't be in afterTest() because some tests access the live db
			Common.orphanCheck();
		}
	}

	@Test
	public void testPenaltyAccountCreateRewardShare() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			final int initialRewardShareCount = repository.getAccountRepository().getRewardShares().size();

			// Alice self share online, and will be used to mint the blocks
			PrivateKeyAccount aliceSelfShare = Common.getTestAccount(repository, "alice-reward-share");
			List<PrivateKeyAccount> onlineAccounts = new ArrayList<>();
			onlineAccounts.add(aliceSelfShare);

			PrivateKeyAccount bobAccount = Common.getTestAccount(repository, "bob");
			PrivateKeyAccount chloeAccount = Common.getTestAccount(repository, "chloe");

			// Bob sponsors 10 accounts
			List<PrivateKeyAccount> bobSponsees = AccountUtils.generateSponsorshipRewardShares(repository, bobAccount, 10);
			List<PrivateKeyAccount> bobSponseesOnlineAccounts = AccountUtils.toRewardShares(repository, bobAccount, bobSponsees);
			onlineAccounts.addAll(bobSponseesOnlineAccounts);

			// Chloe sponsors 10 accounts
			List<PrivateKeyAccount> chloeSponsees = AccountUtils.generateSponsorshipRewardShares(repository, chloeAccount, 10);
			List<PrivateKeyAccount> chloeSponseesOnlineAccounts = AccountUtils.toRewardShares(repository, chloeAccount, chloeSponsees);
			onlineAccounts.addAll(chloeSponseesOnlineAccounts);

			// Mint blocks
			Block block = null;
			for (int i = 0; i <= 1; i++)
				block = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));

			assertEquals(21, block.getBlockData().getOnlineAccountsCount());
			assertEquals(20, repository.getAccountRepository().getRewardShares().size() - initialRewardShareCount);

			// Mint some blocks, until accounts have leveled up
			for (int i = 0; i <= 5; i++)
				block = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));

			// Generate self shares so the sponsees can start minting
			List<PrivateKeyAccount> bobSponseeSelfShares = AccountUtils.generateSelfShares(repository, bobSponsees);
			onlineAccounts.addAll(bobSponseeSelfShares);

			// Mint blocks
			for (int i = 0; i <= 1; i++)
				block = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));

			// Bob then consolidates funds
			consolidateFunds(repository, bobSponsees, bobAccount);

			// Mint until block 19 (the algo runs at block 20)
			while (block.getBlockData().getHeight() < 19)
				block = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));
			assertEquals(19, (int) block.getBlockData().getHeight());

			// Bob creates a valid reward share transaction
			assertEquals(Transaction.ValidationResult.OK, AccountUtils.createRandomRewardShare(repository, bobAccount));

			// Mint a block, so the algo runs
			block = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));

			// Bob can no longer create a reward share transaction
			assertEquals(Transaction.ValidationResult.ACCOUNT_CANNOT_REWARD_SHARE, AccountUtils.createRandomRewardShare(repository, bobAccount));

			// ... but Chloe still can
			assertEquals(Transaction.ValidationResult.OK, AccountUtils.createRandomRewardShare(repository, chloeAccount));

			// Orphan last block
			BlockUtils.orphanLastBlock(repository);

			// Bob creates another valid reward share transaction
			assertEquals(Transaction.ValidationResult.OK, AccountUtils.createRandomRewardShare(repository, bobAccount));

			// Run orphan check - this can't be in afterTest() because some tests access the live db
			Common.orphanCheck();
		}
	}

	@Test
	public void testPenaltyFounderCreateRewardShare() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			final int initialRewardShareCount = repository.getAccountRepository().getRewardShares().size();

			// Bob self share online, and will be used to mint the blocks
			PrivateKeyAccount bobSelfShare = Common.getTestAccount(repository, "bob-reward-share");
			List<PrivateKeyAccount> onlineAccounts = new ArrayList<>();
			onlineAccounts.add(bobSelfShare);

			PrivateKeyAccount aliceAccount = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount bobAccount = Common.getTestAccount(repository, "bob");

			// Alice sponsors 10 accounts
			List<PrivateKeyAccount> aliceSponsees = AccountUtils.generateSponsorshipRewardShares(repository, aliceAccount, 10);
			List<PrivateKeyAccount> aliceSponseesOnlineAccounts = AccountUtils.toRewardShares(repository, aliceAccount, aliceSponsees);
			onlineAccounts.addAll(aliceSponseesOnlineAccounts);

			// Bob sponsors 10 accounts
			List<PrivateKeyAccount> bobSponsees = AccountUtils.generateSponsorshipRewardShares(repository, bobAccount, 10);
			List<PrivateKeyAccount> bobSponseesOnlineAccounts = AccountUtils.toRewardShares(repository, bobAccount, bobSponsees);
			onlineAccounts.addAll(bobSponseesOnlineAccounts);

			// Mint blocks
			Block block = null;
			for (int i = 0; i <= 1; i++)
				block = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));

			assertEquals(21, block.getBlockData().getOnlineAccountsCount());
			assertEquals(20, repository.getAccountRepository().getRewardShares().size() - initialRewardShareCount);

			// Mint some blocks, until accounts have leveled up
			for (int i = 0; i <= 5; i++)
				block = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));

			// Generate self shares so the sponsees can start minting
			List<PrivateKeyAccount> aliceSponseeSelfShares = AccountUtils.generateSelfShares(repository, aliceSponsees);
			onlineAccounts.addAll(aliceSponseeSelfShares);

			// Mint blocks
			for (int i = 0; i <= 1; i++)
				block = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));

			// Alice then consolidates funds
			consolidateFunds(repository, aliceSponsees, aliceAccount);

			// Mint until block 19 (the algo runs at block 20)
			while (block.getBlockData().getHeight() < 19)
				block = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));
			assertEquals(19, (int) block.getBlockData().getHeight());

			// Alice creates a valid reward share transaction
			assertEquals(Transaction.ValidationResult.OK, AccountUtils.createRandomRewardShare(repository, aliceAccount));

			// Mint a block, so the algo runs
			block = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));

			// Ensure that alice now has a penalty
			assertEquals(-5000000, (int) new Account(repository, aliceAccount.getAddress()).getBlocksMintedPenalty());

			// Ensure that alice and her sponsees are now level 0
			assertEquals(0, (int) new Account(repository, aliceAccount.getAddress()).getLevel());

			// Alice can no longer create a reward share transaction
			// Used to Chehck for ACCOUNT_CANNOT_REWARD_SHARE / Updated post canMint() function changes
			// canMint returns False because reward penalty  != 0 (account.java:240)
			// Causes trip in RewardShareTransaction.java:126, Returns NOT_MINTING_ACCOUNT
			assertThat(Transaction.ValidationResult.OK, not(AccountUtils.createRandomRewardShare(repository, aliceAccount)));

			// ... but Bob still can
			assertEquals(Transaction.ValidationResult.OK, AccountUtils.createRandomRewardShare(repository, bobAccount));

			// Orphan last block
			BlockUtils.orphanLastBlock(repository);

			// Alice creates another valid reward share transaction
			assertEquals(Transaction.ValidationResult.OK, AccountUtils.createRandomRewardShare(repository, aliceAccount));

			// Run orphan check - this can't be in afterTest() because some tests access the live db
			Common.orphanCheck();
		}
	}

	/**
	 * This is a test to prove that Dilbert levels up from 6 to 7 in the same block that the self
	 * sponsorship algo runs. It is here to give some confidence in the following testPenaltyAccountLevelUp()
	 * test, in which we will test what happens if a penalty is applied or removed in the same block
	 * that an account would otherwise have leveled up. It also gives some confidence that the algo
	 * doesn't affect the levels of unflagged accounts.
	 *
	 * @throws DataException
	 */
	@Test
	public void testNonPenaltyAccountLevelUp() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {

			// Alice self share online, and will be used to mint the blocks
			PrivateKeyAccount aliceSelfShare = Common.getTestAccount(repository, "alice-reward-share");
			List<PrivateKeyAccount> onlineAccounts = new ArrayList<>();
			onlineAccounts.add(aliceSelfShare);

			PrivateKeyAccount dilbertAccount = Common.getTestAccount(repository, "dilbert");

			// Dilbert sponsors 10 accounts
			List<PrivateKeyAccount> dilbertSponsees = AccountUtils.generateSponsorshipRewardShares(repository, dilbertAccount, 10);
			List<PrivateKeyAccount> dilbertSponseesOnlineAccounts = AccountUtils.toRewardShares(repository, dilbertAccount, dilbertSponsees);
			onlineAccounts.addAll(dilbertSponseesOnlineAccounts);

			// Mint blocks
			Block block = null;
			for (int i = 0; i <= 1; i++)
				block = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));

			// Mint some blocks, until accounts have leveled up
			for (int i = 0; i <= 5; i++)
				block = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));

			// Mint blocks
			for (int i = 0; i <= 1; i++)
				block = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));

			// Mint until block 19 (the algo runs at block 20)
			while (block.getBlockData().getHeight() < 19)
				block = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));
			assertEquals(19, (int) block.getBlockData().getHeight());

			// Make sure Dilbert hasn't leveled up yet
			assertEquals(6, (int)dilbertAccount.getLevel());

			// Mint a block, so the algo runs
			block = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));

			// Make sure Dilbert has leveled up
			assertEquals(7, (int)dilbertAccount.getLevel());

			// Orphan last block
			BlockUtils.orphanLastBlock(repository);

			// Make sure Dilbert has returned to level 6
			assertEquals(6, (int)dilbertAccount.getLevel());

			// Run orphan check - this can't be in afterTest() because some tests access the live db
			Common.orphanCheck();
		}
	}

	@Test
	public void testPenaltyAccountLevelUp() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {

			// Alice self share online, and will be used to mint the blocks
			PrivateKeyAccount aliceSelfShare = Common.getTestAccount(repository, "alice-reward-share");
			List<PrivateKeyAccount> onlineAccounts = new ArrayList<>();
			onlineAccounts.add(aliceSelfShare);

			PrivateKeyAccount dilbertAccount = Common.getTestAccount(repository, "dilbert");

			// Dilbert sponsors 10 accounts
			List<PrivateKeyAccount> dilbertSponsees = AccountUtils.generateSponsorshipRewardShares(repository, dilbertAccount, 10);
			List<PrivateKeyAccount> dilbertSponseesOnlineAccounts = AccountUtils.toRewardShares(repository, dilbertAccount, dilbertSponsees);
			onlineAccounts.addAll(dilbertSponseesOnlineAccounts);

			// Mint blocks
			Block block = null;
			for (int i = 0; i <= 1; i++)
				block = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));

			// Mint some blocks, until accounts have leveled up
			for (int i = 0; i <= 5; i++)
				block = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));

			// Generate self shares so the sponsees can start minting
			List<PrivateKeyAccount> dilbertSponseeSelfShares = AccountUtils.generateSelfShares(repository, dilbertSponsees);
			onlineAccounts.addAll(dilbertSponseeSelfShares);

			// Mint blocks
			for (int i = 0; i <= 1; i++)
				block = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));

			// Dilbert then consolidates funds
			consolidateFunds(repository, dilbertSponsees, dilbertAccount);

			// Mint until block 19 (the algo runs at block 20)
			while (block.getBlockData().getHeight() < 19)
				block = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));
			assertEquals(19, (int) block.getBlockData().getHeight());

			// Make sure Dilbert hasn't leveled up yet
			assertEquals(6, (int)dilbertAccount.getLevel());

			// Mint a block, so the algo runs
			block = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));

			// Make sure Dilbert is now level 0 instead of 7 (due to penalty)
			assertEquals(0, (int)dilbertAccount.getLevel());

			// Orphan last block
			BlockUtils.orphanLastBlock(repository);

			// Make sure Dilbert has returned to level 6
			assertEquals(6, (int)dilbertAccount.getLevel());

			// Run orphan check - this can't be in afterTest() because some tests access the live db
			Common.orphanCheck();
		}
	}

	@Test
	public void testDuplicateSponsors() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			final int initialRewardShareCount = repository.getAccountRepository().getRewardShares().size();

			// Alice self share online, and will be used to mint the blocks
			PrivateKeyAccount aliceSelfShare = Common.getTestAccount(repository, "alice-reward-share");
			List<PrivateKeyAccount> onlineAccounts = new ArrayList<>();
			onlineAccounts.add(aliceSelfShare);

			PrivateKeyAccount bobAccount = Common.getTestAccount(repository, "bob");
			PrivateKeyAccount chloeAccount = Common.getTestAccount(repository, "chloe");
			PrivateKeyAccount dilbertAccount = Common.getTestAccount(repository, "dilbert");

			// Bob sponsors 10 accounts
			List<PrivateKeyAccount> bobSponsees = AccountUtils.generateSponsorshipRewardShares(repository, bobAccount, 10);
			List<PrivateKeyAccount> bobSponseesOnlineAccounts = AccountUtils.toRewardShares(repository, bobAccount, bobSponsees);
			onlineAccounts.addAll(bobSponseesOnlineAccounts);

			// Chloe sponsors THE SAME 10 accounts
			for (PrivateKeyAccount bobSponsee : bobSponsees) {
				// Create reward-share
				TransactionData transactionData = AccountUtils.createRewardShare(repository, chloeAccount, bobSponsee, 0, fee);
				TransactionUtils.signAndImportValid(repository, transactionData, chloeAccount);
			}
			List<PrivateKeyAccount> chloeSponsees = new ArrayList<>(bobSponsees);
			List<PrivateKeyAccount> chloeSponseesOnlineAccounts = AccountUtils.toRewardShares(repository, chloeAccount, chloeSponsees);
			onlineAccounts.addAll(chloeSponseesOnlineAccounts);

			// Dilbert sponsors 5 accounts
			List<PrivateKeyAccount> dilbertSponsees = AccountUtils.generateSponsorshipRewardShares(repository, dilbertAccount, 5);
			List<PrivateKeyAccount> dilbertSponseesOnlineAccounts = AccountUtils.toRewardShares(repository, dilbertAccount, dilbertSponsees);
			onlineAccounts.addAll(dilbertSponseesOnlineAccounts);

			// Mint blocks
			Block block = null;
			for (int i = 0; i <= 1; i++)
				block = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));

			assertEquals(26, block.getBlockData().getOnlineAccountsCount());
			assertEquals(25, repository.getAccountRepository().getRewardShares().size() - initialRewardShareCount);

			for (PrivateKeyAccount bobSponsee : bobSponsees)
				assertTrue(new Account(repository, bobSponsee.getAddress()).getLevel() == 0);

			// Mint some blocks, until accounts have leveled up
			for (int i = 0; i <= 5; i++)
				block = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));

			for (PrivateKeyAccount bobSponsee : bobSponsees)
				assertTrue(new Account(repository, bobSponsee.getAddress()).getLevel() > 0);

			// Generate self shares so the sponsees can start minting
			List<PrivateKeyAccount> bobSponseeSelfShares = AccountUtils.generateSelfShares(repository, bobSponsees);
			onlineAccounts.addAll(bobSponseeSelfShares);

			// Mint blocks
			for (int i = 0; i <= 1; i++)
				block = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));

			for (PrivateKeyAccount bobSponsee : bobSponsees)
				assertTrue(new Account(repository, bobSponsee.getAddress()).getConfirmedBalance(Asset.QORT) > 10); // 5 for transaction, 5 for fee

			// Bob then consolidates funds
			consolidateFunds(repository, bobSponsees, bobAccount);

			// Mint until block 19 (the algo runs at block 20)
			while (block.getBlockData().getHeight() < 19)
				block = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));
			assertEquals(19, (int) block.getBlockData().getHeight());

			// Ensure that bob and his sponsees have no penalties
			List<PrivateKeyAccount> bobAndSponsees = new ArrayList<>(bobSponsees);
			bobAndSponsees.add(bobAccount);
			for (PrivateKeyAccount bobSponsee : bobAndSponsees)
				assertEquals(0, (int) new Account(repository, bobSponsee.getAddress()).getBlocksMintedPenalty());

			assertEquals(6, (int)dilbertAccount.getLevel());

			// Mint a block, so the algo runs
			block = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));

			// Ensure that bob and his sponsees now have penalties
			for (PrivateKeyAccount bobSponsee : bobAndSponsees)
				assertEquals(-5000000, (int) new Account(repository, bobSponsee.getAddress()).getBlocksMintedPenalty());

			// Ensure that chloe and her sponsees also have penalties, as they relate to the same network of accounts
			List<PrivateKeyAccount> chloeAndSponsees = new ArrayList<>(chloeSponsees);
			chloeAndSponsees.add(chloeAccount);
			for (PrivateKeyAccount chloeSponsee : chloeAndSponsees)
				assertEquals(-5000000, (int) new Account(repository, chloeSponsee.getAddress()).getBlocksMintedPenalty());

			// Ensure that dilbert and his sponsees have no penalties
			List<PrivateKeyAccount> dilbertAndSponsees = new ArrayList<>(dilbertSponsees);
			dilbertAndSponsees.add(dilbertAccount);
			for (PrivateKeyAccount dilbertSponsee : dilbertAndSponsees)
				assertEquals(0, (int) new Account(repository, dilbertSponsee.getAddress()).getBlocksMintedPenalty());

			// Ensure that bob and his sponsees are now level 0
			for (PrivateKeyAccount bobSponsee : bobAndSponsees)
				assertEquals(0, (int) new Account(repository, bobSponsee.getAddress()).getLevel());

			// Orphan last block
			BlockUtils.orphanLastBlock(repository);

			// Ensure that bob and his sponsees are now greater than level 0
			for (PrivateKeyAccount bobSponsee : bobAndSponsees)
				assertTrue(new Account(repository, bobSponsee.getAddress()).getLevel() > 0);

			// Ensure that bob and his sponsees have no penalties again
			for (PrivateKeyAccount bobSponsee : bobAndSponsees)
				assertEquals(0, (int) new Account(repository, bobSponsee.getAddress()).getBlocksMintedPenalty());

			// Ensure that chloe and her sponsees still have no penalties again
			for (PrivateKeyAccount chloeSponsee : chloeAndSponsees)
				assertEquals(0, (int) new Account(repository, chloeSponsee.getAddress()).getBlocksMintedPenalty());

			// Ensure that dilbert and his sponsees still have no penalties
			for (PrivateKeyAccount dilbertSponsee : dilbertAndSponsees)
				assertEquals(0, (int) new Account(repository, dilbertSponsee.getAddress()).getBlocksMintedPenalty());

			// Run orphan check - this can't be in afterTest() because some tests access the live db
			Common.orphanCheck();
		}
	}

	@Test
	public void testTransferPrivsBeforeAlgoBlock() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {

			// Alice self share online, and will be used to mint the blocks
			PrivateKeyAccount aliceSelfShare = Common.getTestAccount(repository, "alice-reward-share");
			List<PrivateKeyAccount> onlineAccounts = new ArrayList<>();
			onlineAccounts.add(aliceSelfShare);

			PrivateKeyAccount bobAccount = Common.getTestAccount(repository, "bob");

			// Bob sponsors 10 accounts
			List<PrivateKeyAccount> bobSponsees = AccountUtils.generateSponsorshipRewardShares(repository, bobAccount, 10);
			List<PrivateKeyAccount> bobSponseesOnlineAccounts = AccountUtils.toRewardShares(repository, bobAccount, bobSponsees);
			onlineAccounts.addAll(bobSponseesOnlineAccounts);

			// Mint blocks
			Block block = null;
			for (int i = 0; i <= 1; i++)
				block = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));

			for (PrivateKeyAccount bobSponsee : bobSponsees)
				assertTrue(new Account(repository, bobSponsee.getAddress()).getLevel() == 0);

			// Mint some blocks, until accounts have leveled up
			for (int i = 0; i <= 5; i++)
				block = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));

			for (PrivateKeyAccount bobSponsee : bobSponsees)
				assertTrue(new Account(repository, bobSponsee.getAddress()).getLevel() > 0);

			// Generate self shares so the sponsees can start minting
			List<PrivateKeyAccount> bobSponseeSelfShares = AccountUtils.generateSelfShares(repository, bobSponsees);
			onlineAccounts.addAll(bobSponseeSelfShares);

			// Mint blocks
			for (int i = 0; i <= 1; i++)
				block = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));

			for (PrivateKeyAccount bobSponsee : bobSponsees)
				assertTrue(new Account(repository, bobSponsee.getAddress()).getConfirmedBalance(Asset.QORT) > 10); // 5 for transaction, 5 for fee

			// Bob then consolidates funds
			consolidateFunds(repository, bobSponsees, bobAccount);

			// Mint until block 18 (the algo runs at block 20)
			while (block.getBlockData().getHeight() < 18)
				block = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));
			assertEquals(18, (int) block.getBlockData().getHeight());

			// Bob then issues a TRANSFER_PRIVS
			PrivateKeyAccount recipientAccount = randomTransferPrivs(repository, bobAccount);

			// Ensure recipient has no level (actually, no account record) at this point (pre-confirmation)
			assertNull(recipientAccount.getLevel());

			// Mint another block, so that the TRANSFER_PRIVS confirms
			block = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));

			// Now ensure that the TRANSFER_PRIVS recipient has inherited Bob's level, and Bob is at level 0
			assertTrue(recipientAccount.getLevel() > 0);
			assertEquals(0, (int)bobAccount.getLevel());
			assertEquals(0, (int) new Account(repository, recipientAccount.getAddress()).getBlocksMintedPenalty());

			// Ensure that bob and his sponsees have no penalties
			List<PrivateKeyAccount> bobAndSponsees = new ArrayList<>(bobSponsees);
			bobAndSponsees.add(bobAccount);
			for (PrivateKeyAccount bobSponsee : bobAndSponsees)
				assertEquals(0, (int) new Account(repository, bobSponsee.getAddress()).getBlocksMintedPenalty());

			// Ensure that bob's sponsees are greater than level 0
			// Bob's account won't be, as he has transferred privs
			for (PrivateKeyAccount bobSponsee : bobSponsees)
				assertTrue(new Account(repository, bobSponsee.getAddress()).getLevel() > 0);

			// Mint a block, so the algo runs
			block = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));

			// Ensure that bob and his sponsees now have penalties
			for (PrivateKeyAccount bobSponsee : bobAndSponsees)
				assertEquals(-5000000, (int) new Account(repository, bobSponsee.getAddress()).getBlocksMintedPenalty());

			// Ensure that bob and his sponsees are now level 0
			for (PrivateKeyAccount bobSponsee : bobAndSponsees)
				assertEquals(0, (int) new Account(repository, bobSponsee.getAddress()).getLevel());

			// Ensure recipient account has penalty too
			assertEquals(-5000000, (int) new Account(repository, recipientAccount.getAddress()).getBlocksMintedPenalty());
			assertEquals(0, (int) new Account(repository, recipientAccount.getAddress()).getLevel());

			// TODO: check both recipients' sponsees

			// Orphan last block
			BlockUtils.orphanLastBlock(repository);

			// Ensure that Bob's sponsees are now greater than level 0
			for (PrivateKeyAccount bobSponsee : bobSponsees)
				assertTrue(new Account(repository, bobSponsee.getAddress()).getLevel() > 0);

			// Ensure that bob and his sponsees have no penalties again
			for (PrivateKeyAccount bobSponsee : bobAndSponsees)
				assertEquals(0, (int) new Account(repository, bobSponsee.getAddress()).getBlocksMintedPenalty());

			// Ensure recipient account has no penalty again and has a level greater than 0
			assertEquals(0, (int) new Account(repository, recipientAccount.getAddress()).getBlocksMintedPenalty());
			assertTrue(new Account(repository, recipientAccount.getAddress()).getLevel() > 0);

			// Run orphan check - this can't be in afterTest() because some tests access the live db
			Common.orphanCheck();
		}
	}

	@Test
	public void testTransferPrivsInAlgoBlock() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {

			// Alice self share online, and will be used to mint the blocks
			PrivateKeyAccount aliceSelfShare = Common.getTestAccount(repository, "alice-reward-share");
			List<PrivateKeyAccount> onlineAccounts = new ArrayList<>();
			onlineAccounts.add(aliceSelfShare);

			PrivateKeyAccount bobAccount = Common.getTestAccount(repository, "bob");

			// Bob sponsors 10 accounts
			List<PrivateKeyAccount> bobSponsees = AccountUtils.generateSponsorshipRewardShares(repository, bobAccount, 10);
			List<PrivateKeyAccount> bobSponseesOnlineAccounts = AccountUtils.toRewardShares(repository, bobAccount, bobSponsees);
			onlineAccounts.addAll(bobSponseesOnlineAccounts);

			// Mint blocks
			Block block = null;
			for (int i = 0; i <= 1; i++)
				block = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));

			for (PrivateKeyAccount bobSponsee : bobSponsees)
				assertTrue(new Account(repository, bobSponsee.getAddress()).getLevel() == 0);

			// Mint some blocks, until accounts have leveled up
			for (int i = 0; i <= 5; i++)
				block = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));

			for (PrivateKeyAccount bobSponsee : bobSponsees)
				assertTrue(new Account(repository, bobSponsee.getAddress()).getLevel() > 0);

			// Generate self shares so the sponsees can start minting
			List<PrivateKeyAccount> bobSponseeSelfShares = AccountUtils.generateSelfShares(repository, bobSponsees);
			onlineAccounts.addAll(bobSponseeSelfShares);

			// Mint blocks
			for (int i = 0; i <= 1; i++)
				block = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));

			for (PrivateKeyAccount bobSponsee : bobSponsees)
				assertTrue(new Account(repository, bobSponsee.getAddress()).getConfirmedBalance(Asset.QORT) > 10); // 5 for transaction, 5 for fee

			// Bob then consolidates funds
			consolidateFunds(repository, bobSponsees, bobAccount);

			// Mint until block 19 (the algo runs at block 20)
			while (block.getBlockData().getHeight() < 19)
				block = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));
			assertEquals(19, (int) block.getBlockData().getHeight());

			// Ensure that bob and his sponsees have no penalties
			List<PrivateKeyAccount> bobAndSponsees = new ArrayList<>(bobSponsees);
			bobAndSponsees.add(bobAccount);
			for (PrivateKeyAccount bobSponsee : bobAndSponsees)
				assertEquals(0, (int) new Account(repository, bobSponsee.getAddress()).getBlocksMintedPenalty());

			// Bob then issues a TRANSFER_PRIVS
			PrivateKeyAccount recipientAccount = randomTransferPrivs(repository, bobAccount);

			// Ensure recipient has no level (actually, no account record) at this point (pre-confirmation)
			assertNull(recipientAccount.getLevel());

			// Mint a block, so the algo runs
			block = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));

			// Now ensure that the TRANSFER_PRIVS recipient has inherited Bob's level, and Bob is at level 0
			assertTrue(recipientAccount.getLevel() > 0);
			assertEquals(0, (int)bobAccount.getLevel());

			// Ensure that bob and his sponsees now have penalties
			for (PrivateKeyAccount bobSponsee : bobAndSponsees)
				assertEquals(-5000000, (int) new Account(repository, bobSponsee.getAddress()).getBlocksMintedPenalty());

			// Ensure that bob and his sponsees are now level 0
			for (PrivateKeyAccount bobSponsee : bobAndSponsees)
				assertEquals(0, (int) new Account(repository, bobSponsee.getAddress()).getLevel());

			// Orphan last block
			BlockUtils.orphanLastBlock(repository);

			// Ensure recipient has no level again
			assertNull(recipientAccount.getLevel());

			// Ensure that bob and his sponsees are now greater than level 0
			for (PrivateKeyAccount bobSponsee : bobAndSponsees)
				assertTrue(new Account(repository, bobSponsee.getAddress()).getLevel() > 0);

			// Ensure that bob and his sponsees have no penalties again
			for (PrivateKeyAccount bobSponsee : bobAndSponsees)
				assertEquals(0, (int) new Account(repository, bobSponsee.getAddress()).getBlocksMintedPenalty());

			// Run orphan check - this can't be in afterTest() because some tests access the live db
			Common.orphanCheck();
		}
	}

	@Test
	public void testTransferPrivsAfterAlgoBlock() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {

			// Alice self share online, and will be used to mint the blocks
			PrivateKeyAccount aliceSelfShare = Common.getTestAccount(repository, "alice-reward-share");
			List<PrivateKeyAccount> onlineAccounts = new ArrayList<>();
			onlineAccounts.add(aliceSelfShare);

			PrivateKeyAccount bobAccount = Common.getTestAccount(repository, "bob");

			// Bob sponsors 10 accounts
			List<PrivateKeyAccount> bobSponsees = AccountUtils.generateSponsorshipRewardShares(repository, bobAccount, 10);
			List<PrivateKeyAccount> bobSponseesOnlineAccounts = AccountUtils.toRewardShares(repository, bobAccount, bobSponsees);
			onlineAccounts.addAll(bobSponseesOnlineAccounts);

			// Mint blocks
			Block block = null;
			for (int i = 0; i <= 1; i++)
				block = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));

			for (PrivateKeyAccount bobSponsee : bobSponsees)
				assertTrue(new Account(repository, bobSponsee.getAddress()).getLevel() == 0);

			// Mint some blocks, until accounts have leveled up
			for (int i = 0; i <= 5; i++)
				block = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));

			for (PrivateKeyAccount bobSponsee : bobSponsees)
				assertTrue(new Account(repository, bobSponsee.getAddress()).getLevel() > 0);

			// Generate self shares so the sponsees can start minting
			List<PrivateKeyAccount> bobSponseeSelfShares = AccountUtils.generateSelfShares(repository, bobSponsees);
			onlineAccounts.addAll(bobSponseeSelfShares);

			// Mint blocks
			for (int i = 0; i <= 1; i++)
				block = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));

			for (PrivateKeyAccount bobSponsee : bobSponsees)
				assertTrue(new Account(repository, bobSponsee.getAddress()).getConfirmedBalance(Asset.QORT) > 10); // 5 for transaction, 5 for fee

			// Bob then consolidates funds
			consolidateFunds(repository, bobSponsees, bobAccount);

			// Mint until block 19 (the algo runs at block 20)
			while (block.getBlockData().getHeight() < 19)
				block = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));
			assertEquals(19, (int) block.getBlockData().getHeight());

			// Ensure that bob and his sponsees have no penalties
			List<PrivateKeyAccount> bobAndSponsees = new ArrayList<>(bobSponsees);
			bobAndSponsees.add(bobAccount);
			for (PrivateKeyAccount bobSponsee : bobAndSponsees)
				assertEquals(0, (int) new Account(repository, bobSponsee.getAddress()).getBlocksMintedPenalty());

			// Mint a block, so the algo runs
			block = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));

			// Bob then issues a TRANSFER_PRIVS, which should be invalid
			Transaction transferPrivsTransaction = randomTransferPrivsTransaction(repository, bobAccount);
			assertEquals(ACCOUNT_NOT_TRANSFERABLE, transferPrivsTransaction.isValid());

			// Orphan last 2 blocks
			BlockUtils.orphanLastBlock(repository);
			BlockUtils.orphanLastBlock(repository);

			// TRANSFER_PRIVS should now be valid
			transferPrivsTransaction = randomTransferPrivsTransaction(repository, bobAccount);
			assertEquals(OK, transferPrivsTransaction.isValid());

			// Run orphan check - this can't be in afterTest() because some tests access the live db
			Common.orphanCheck();
		}
	}

	@Test
	public void testDoubleTransferPrivs() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {

			// Alice self share online, and will be used to mint the blocks
			PrivateKeyAccount aliceSelfShare = Common.getTestAccount(repository, "alice-reward-share");
			List<PrivateKeyAccount> onlineAccounts = new ArrayList<>();
			onlineAccounts.add(aliceSelfShare);

			PrivateKeyAccount bobAccount = Common.getTestAccount(repository, "bob");

			// Bob sponsors 10 accounts
			List<PrivateKeyAccount> bobSponsees = AccountUtils.generateSponsorshipRewardShares(repository, bobAccount, 10);
			List<PrivateKeyAccount> bobSponseesOnlineAccounts = AccountUtils.toRewardShares(repository, bobAccount, bobSponsees);
			onlineAccounts.addAll(bobSponseesOnlineAccounts);

			// Mint blocks
			Block block = null;
			for (int i = 0; i <= 1; i++)
				block = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));

			for (PrivateKeyAccount bobSponsee : bobSponsees)
				assertTrue(new Account(repository, bobSponsee.getAddress()).getLevel() == 0);

			// Mint some blocks, until accounts have leveled up
			for (int i = 0; i <= 5; i++)
				block = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));

			for (PrivateKeyAccount bobSponsee : bobSponsees)
				assertTrue(new Account(repository, bobSponsee.getAddress()).getLevel() > 0);

			// Generate self shares so the sponsees can start minting
			List<PrivateKeyAccount> bobSponseeSelfShares = AccountUtils.generateSelfShares(repository, bobSponsees);
			onlineAccounts.addAll(bobSponseeSelfShares);

			// Mint blocks
			for (int i = 0; i <= 1; i++)
				block = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));

			for (PrivateKeyAccount bobSponsee : bobSponsees)
				assertTrue(new Account(repository, bobSponsee.getAddress()).getConfirmedBalance(Asset.QORT) > 10); // 5 for transaction, 5 for fee

			// Bob then consolidates funds
			consolidateFunds(repository, bobSponsees, bobAccount);

			// Mint until block 17 (the algo runs at block 20)
			while (block.getBlockData().getHeight() < 17)
				block = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));
			assertEquals(17, (int) block.getBlockData().getHeight());

			// Bob then issues a TRANSFER_PRIVS
			PrivateKeyAccount recipientAccount1 = randomTransferPrivs(repository, bobAccount);

			// Ensure recipient has no level (actually, no account record) at this point (pre-confirmation)
			assertNull(recipientAccount1.getLevel());

			// Bob and also sends some QORT to cover future transaction fees
			// This mints another block, and the TRANSFER_PRIVS confirms
			AccountUtils.pay(repository, bobAccount, recipientAccount1.getAddress(), 123456789L);

			// Now ensure that the TRANSFER_PRIVS recipient has inherited Bob's level, and Bob is at level 0
			assertTrue(recipientAccount1.getLevel() > 0);
			assertEquals(0, (int)bobAccount.getLevel());
			assertEquals(0, (int) new Account(repository, recipientAccount1.getAddress()).getBlocksMintedPenalty());

			// The recipient account then issues a TRANSFER_PRIVS of their own
			PrivateKeyAccount recipientAccount2 = randomTransferPrivs(repository, recipientAccount1);

			// Ensure recipientAccount2 has no level at this point (pre-confirmation)
			assertNull(recipientAccount2.getLevel());

			// Mint another block, so that the TRANSFER_PRIVS confirms
			block = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));

			// Now ensure that the TRANSFER_PRIVS recipient2 has inherited Bob's level, and recipient1 is at level 0
			assertTrue(recipientAccount2.getLevel() > 0);
			assertEquals(0, (int)recipientAccount1.getLevel());
			assertEquals(0, (int) new Account(repository, recipientAccount2.getAddress()).getBlocksMintedPenalty());

			// Ensure that bob and his sponsees have no penalties
			List<PrivateKeyAccount> bobAndSponsees = new ArrayList<>(bobSponsees);
			bobAndSponsees.add(bobAccount);
			for (PrivateKeyAccount bobSponsee : bobAndSponsees)
				assertEquals(0, (int) new Account(repository, bobSponsee.getAddress()).getBlocksMintedPenalty());

			// Ensure that bob's sponsees are greater than level 0
			// Bob's account won't be, as he has transferred privs
			for (PrivateKeyAccount bobSponsee : bobSponsees)
				assertTrue(new Account(repository, bobSponsee.getAddress()).getLevel() > 0);

			// Mint a block, so the algo runs
			block = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));

			// Ensure that bob and his sponsees now have penalties
			for (PrivateKeyAccount bobSponsee : bobAndSponsees)
				assertEquals(-5000000, (int) new Account(repository, bobSponsee.getAddress()).getBlocksMintedPenalty());

			// Ensure that bob and his sponsees are now level 0
			for (PrivateKeyAccount bobSponsee : bobAndSponsees)
				assertEquals(0, (int) new Account(repository, bobSponsee.getAddress()).getLevel());

			// Ensure recipientAccount2 has penalty too
			assertEquals(-5000000, (int) new Account(repository, recipientAccount2.getAddress()).getBlocksMintedPenalty());
			assertEquals(0, (int) new Account(repository, recipientAccount2.getAddress()).getLevel());

			// Ensure recipientAccount1 has penalty too
			assertEquals(-5000000, (int) new Account(repository, recipientAccount1.getAddress()).getBlocksMintedPenalty());
			assertEquals(0, (int) new Account(repository, recipientAccount1.getAddress()).getLevel());

			// TODO: check recipient's sponsees

			// Orphan last block
			BlockUtils.orphanLastBlock(repository);

			// Ensure that Bob's sponsees are now greater than level 0
			for (PrivateKeyAccount bobSponsee : bobSponsees)
				assertTrue(new Account(repository, bobSponsee.getAddress()).getLevel() > 0);

			// Ensure that bob and his sponsees have no penalties again
			for (PrivateKeyAccount bobSponsee : bobAndSponsees)
				assertEquals(0, (int) new Account(repository, bobSponsee.getAddress()).getBlocksMintedPenalty());

			// Ensure recipientAccount1 has no penalty again and is level 0
			assertEquals(0, (int) new Account(repository, recipientAccount1.getAddress()).getBlocksMintedPenalty());
			assertEquals(0, (int) new Account(repository, recipientAccount1.getAddress()).getLevel());

			// Ensure recipientAccount2 has no penalty again and has a level greater than 0
			assertEquals(0, (int) new Account(repository, recipientAccount2.getAddress()).getBlocksMintedPenalty());
			assertTrue(new Account(repository, recipientAccount2.getAddress()).getLevel() > 0);

			// Run orphan check - this can't be in afterTest() because some tests access the live db
			Common.orphanCheck();
		}
	}



	private static PrivateKeyAccount randomTransferPrivs(Repository repository, PrivateKeyAccount senderAccount) throws DataException {
		// Generate random recipient account
		byte[] randomPrivateKey = new byte[32];
		new Random().nextBytes(randomPrivateKey);
		PrivateKeyAccount recipientAccount = new PrivateKeyAccount(repository, randomPrivateKey);

		BaseTransactionData baseTransactionData = new BaseTransactionData(NTP.getTime(), 0, senderAccount.getLastReference(), senderAccount.getPublicKey(), fee, null);
		TransactionData transactionData = new TransferPrivsTransactionData(baseTransactionData, recipientAccount.getAddress());

		TransactionUtils.signAndImportValid(repository, transactionData, senderAccount);

		return recipientAccount;
	}

	private static TransferPrivsTransaction randomTransferPrivsTransaction(Repository repository, PrivateKeyAccount senderAccount) throws DataException {
		// Generate random recipient account
		byte[] randomPrivateKey = new byte[32];
		new Random().nextBytes(randomPrivateKey);
		PrivateKeyAccount recipientAccount = new PrivateKeyAccount(repository, randomPrivateKey);

		BaseTransactionData baseTransactionData = new BaseTransactionData(NTP.getTime(), 0, senderAccount.getLastReference(), senderAccount.getPublicKey(), fee, null);
		TransactionData transactionData = new TransferPrivsTransactionData(baseTransactionData, recipientAccount.getAddress());

		return new TransferPrivsTransaction(repository, transactionData);
	}

	private boolean areAllAccountsPresentInBlock(List<PrivateKeyAccount> accounts, Block block) throws DataException {
		for (PrivateKeyAccount bobSponsee : accounts) {
			boolean foundOnlineAccountInBlock = false;
			for (Block.ExpandedAccount expandedAccount : block.getExpandedAccounts()) {
				if (expandedAccount.getRecipientAccount().getAddress().equals(bobSponsee.getAddress())) {
					foundOnlineAccountInBlock = true;
					break;
				}
			}
			if (!foundOnlineAccountInBlock) {
				return false;
			}
		}
		return true;
	}

	private static void consolidateFunds(Repository repository, List<PrivateKeyAccount> sponsees, PrivateKeyAccount sponsor) throws DataException {
		for (PrivateKeyAccount sponsee : sponsees) {
			for (int i = 0; i < 5; i++) {
				// Generate new payments from sponsee to sponsor
				TransactionData paymentData = new PaymentTransactionData(TestTransaction.generateBase(sponsee), sponsor.getAddress(), 1);
				TransactionUtils.signAndImportValid(repository, paymentData, sponsee); // updates paymentData's signature
			}
		}
	}

}