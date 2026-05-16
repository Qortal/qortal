package org.qortal.test.common;

import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.asset.Asset;
import org.qortal.block.BlockChain;
import org.qortal.data.account.AccountBalanceData;
import org.qortal.data.asset.AssetData;
import org.qortal.data.group.GroupData;
import org.qortal.repository.AccountRepository.BalanceOrdering;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryFactory;
import org.qortal.repository.RepositoryManager;
import org.qortal.repository.hsqldb.HSQLDBRepositoryFactory;
import org.qortal.settings.Settings;
import org.qortal.utils.NTP;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.security.Security;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

public class Common {

	static {
		// This must go before any calls to LogManager/Logger
		System.setProperty("java.util.logging.manager", "org.apache.logging.log4j.jul.LogManager");

		Security.insertProviderAt(new BouncyCastleProvider(), 0);
		Security.insertProviderAt(new BouncyCastleJsseProvider(), 1);
	}

	private static final Logger LOGGER = LogManager.getLogger(Common.class);

	public static final String testConnectionUrlMemory = "jdbc:hsqldb:mem:testdb";
	public static final String testConnectionUrlDisk = "jdbc:hsqldb:file:%s/blockchain;create=true";

	// For debugging, use testConnectionUrlDisk instead of memory, to write DB to disk for examination.
	// This can be achieved using `Common.useSettingsAndDb(Common.testSettingsFilename, false);`
	// where `false` specifies to use a repository on disk rather than one in memory.
	// Make sure to also comment out `Common.deleteTestRepository();` in closeRepository() below, so that
	// the files remain after the test finishes.


	public static final String testSettingsFilename = "test-settings-v2.json";
	public static boolean shouldRetainRepositoryAfterTest = false;

	static {
		// Load/check settings, which potentially sets up blockchain config, etc.
		URL testSettingsUrl = Common.class.getClassLoader().getResource(testSettingsFilename);
		assertNotNull("Test settings JSON file not found", testSettingsUrl);
		Settings.fileInstance(testSettingsUrl.getPath());
	}

	private static List<AssetData> initialAssets;
	private static List<GroupData> initialGroups;
	private static List<AccountBalanceData> initialBalances;

	private static Map<String, TestAccount> testAccountsByName = new HashMap<>();
	static {
		testAccountsByName.put("alice", new TestAccount(null, "alice", "A9MNsATgQgruBUjxy2rjWY36Yf19uRioKZbiLFT2P7c6", false));
		testAccountsByName.put("bob", new TestAccount(null, "bob", "AdTd9SUEYSdTW8mgK3Gu72K97bCHGdUwi2VvLNjUohot", false));
		testAccountsByName.put("chloe", new TestAccount(null, "chloe", "HqVngdE1AmEyDpfwTZqUdFHB13o4bCmpoTNAKEqki66K", false));
		testAccountsByName.put("dilbert", new TestAccount(null, "dilbert", "Gakhh6Ln4vtBFM88nE9JmDaLBDtUBg51aVFpWfSkyVw5", false));

		// Alice reward-share with herself. Private key is reward-share private key, derived from Alice's private and public keys.
		testAccountsByName.put("alice-reward-share", new TestAccount(null, "alice-reward-share", "1CeDCg9TSdBwJNGVTGG7pCKsvsyyoEcaVXYvDT1Xb9f", true));
		// Bob self-share
		testAccountsByName.put("bob-reward-share", new TestAccount(null, "bob-reward-share", "975G6DJX2bhkq2dawxxDbNe5DcT33LbGto5tRueKVRDx", true));
		// Chloe self-share
		testAccountsByName.put("chloe-reward-share", new TestAccount(null, "chloe-reward-share", "2paayAXTbGmdLtJ7tNxY93bhPnWZwNYwk15KA37Sw5yS", true));
		// Dilbert self-share
		testAccountsByName.put("dilbert-reward-share", new TestAccount(null, "dilbert-reward-share", "C3DqD3K9bZDqxwLBroXc2NgL2SRJrif1mcAW7zNMUg9", true));
	}

	public static TestAccount getTestAccount(Repository repository, String name) {
		return new TestAccount(repository, testAccountsByName.get(name));
	}

	public static TestAccount getRandomTestAccount(Repository repository, Boolean includeRewardShare) {
		List<TestAccount> testAccounts = new ArrayList<>(testAccountsByName.values());

		if (includeRewardShare != null)
			testAccounts.removeIf(account -> account.isRewardShare != includeRewardShare);

		Random random = new Random();
		int index = random.nextInt(testAccounts.size());

		return testAccounts.get(index);
	}

	public static List<TestAccount> getTestAccounts(Repository repository) {
		return testAccountsByName.values().stream().map(account -> new TestAccount(repository, account)).collect(Collectors.toList());
	}

	public static PrivateKeyAccount generateRandomSeedAccount(Repository repository) {
		byte[] seed = new byte[32];
		new SecureRandom().nextBytes(seed);
		return new PrivateKeyAccount(repository, seed);
	}

	public static void useSettingsAndDb(String settingsFilename, boolean dbInMemory) throws DataException {
		if (RepositoryManager.getRepositoryFactory() != null) {
			closeRepository();
		}

		// Load/check settings, which potentially sets up blockchain config, etc.
		LOGGER.debug(String.format("Using setting file: %s", settingsFilename));
		URL testSettingsUrl = Common.class.getClassLoader().getResource(settingsFilename);
		assertNotNull("Test settings JSON file not found", testSettingsUrl);
		Settings.fileInstance(testSettingsUrl.getPath());

		setRepository(dbInMemory);

		resetBlockchain();
	}

	public static void useSettings(String settingsFilename) throws DataException {
		Common.useSettingsAndDb(settingsFilename, true);
		setShouldRetainRepositoryAfterTest(false);
	}

	public static void useDefaultSettings() throws DataException {
		useSettings(testSettingsFilename);
		NTP.setFixedOffset(Settings.getInstance().getTestNtpOffset());
	}

	public static void resetBlockchain() throws DataException {
		BlockChain.validate();

		try (final Repository repository = RepositoryManager.getRepository()) {
			// Build snapshot of initial state in case we want to compare with post-test orphaning
			initialAssets = repository.getAssetRepository().getAllAssets();
			LOGGER.debug("resetBlockchain - initialAssets size: {}", initialAssets.size());
			for (AssetData asset : initialAssets) {
				LOGGER.debug("resetBlockchain - initial asset: {} - {}", asset.getAssetId(), asset.getName());
			}
			initialGroups = repository.getGroupRepository().getAllGroups();
			initialBalances = repository.getAccountRepository().getAssetBalances(Collections.emptyList(), Collections.emptyList(), BalanceOrdering.ASSET_ACCOUNT, false, null, null, null);

			// Check that each test account can fetch their last reference
			for (TestAccount testAccount : getTestAccounts(repository))
				if (!testAccount.isRewardShare)
					assertNotNull(String.format("Test account %s / %s should have last reference", testAccount.accountName, testAccount.getAddress()), testAccount.getLastReference());
		}
	}

	/** Orphan back to genesis block and compare initial snapshot. */
	public static void orphanCheck() throws DataException {
		// Skip orphanCheck if shouldRetainRepositoryAfterTest is true
		if (shouldRetainRepositoryAfterTest) {
			LOGGER.debug("Skipping orphanCheck as shouldRetainRepositoryAfterTest is true");
			return;
		}

		LOGGER.debug("Orphaning back to genesis block");

		try (final Repository repository = RepositoryManager.getRepository()) {
			// Debug: Check if QORT asset exists before orphaning
			try {
				AssetData qortAsset = repository.getAssetRepository().fromAssetId(Asset.QORT);
				LOGGER.debug("orphanCheck - QORT asset exists before orphaning: {}", qortAsset != null);
			} catch (DataException e) {
				LOGGER.debug("orphanCheck - QORT asset does not exist before orphaning");
			}
			
			// Orphan back to genesis block
			while (repository.getBlockRepository().getBlockchainHeight() > 1) {
				BlockUtils.orphanLastBlock(repository);
			}
			
			// Debug: Check if QORT asset exists after orphaning
			try {
				AssetData qortAsset = repository.getAssetRepository().fromAssetId(Asset.QORT);
				LOGGER.debug("orphanCheck - QORT asset exists after orphaning: {}", qortAsset != null);
			} catch (DataException e) {
				LOGGER.debug("orphanCheck - QORT asset does not exist after orphaning");
			}

			List<AssetData> remainingAssets = repository.getAssetRepository().getAllAssets();
			LOGGER.debug("orphanCheck - remainingAssets size: {}", remainingAssets.size());
			for (AssetData asset : remainingAssets) {
				LOGGER.debug("orphanCheck - remaining asset: {} - {}", asset.getAssetId(), asset.getName());
			}
			checkOrphanedLists("asset", initialAssets, remainingAssets, AssetData::getAssetId, AssetData::getAssetId);

			List<GroupData> remainingGroups = repository.getGroupRepository().getAllGroups();
			checkOrphanedLists("group", initialGroups, remainingGroups, GroupData::getGroupId, GroupData::getGroupId);

			List<AccountBalanceData> remainingBalances = repository.getAccountRepository().getAssetBalances(Collections.emptyList(), Collections.emptyList(), BalanceOrdering.ASSET_ACCOUNT, false, null, null, null);
			checkOrphanedLists("account balance", initialBalances, remainingBalances, entry -> entry.getAddress() + " [" + entry.getAssetName() + "]", entry -> entry.getBalance());

			assertEquals("remainingBalances is different size", initialBalances.size(), remainingBalances.size());
			// Actually compare balances
			for (int i = 0; i < initialBalances.size(); ++i) {
				AccountBalanceData initialBalance = initialBalances.get(i);
				AccountBalanceData remainingBalance = remainingBalances.get(i);

				assertEquals("Remaining balance's address differs", initialBalance.getAddress(), remainingBalance.getAddress());
				assertEquals(initialBalance.getAddress() + " remaining balance's asset differs", initialBalance.getAssetId(), remainingBalance.getAssetId());

				assertEquals(initialBalance.getAddress() + " remaining balance differs", initialBalance.getBalance(), remainingBalance.getBalance());
			}
		}
	}

	private static <T> void checkOrphanedLists(String typeName, List<T> initial, List<T> remaining, Function<T, ? extends Object> keyExtractor, Function<T, ? extends Object> valueExtractor) {
		Predicate<T> isInitial = entry -> initial.stream().anyMatch(initialEntry -> keyExtractor.apply(initialEntry).equals(keyExtractor.apply(entry)));
		Predicate<T> isRemaining = entry -> remaining.stream().anyMatch(remainingEntry -> keyExtractor.apply(remainingEntry).equals(keyExtractor.apply(entry)));

		// Check all initial entries remain
		for (T initialEntry : initial)
			assertTrue(String.format("Genesis %s %s missing", typeName, keyExtractor.apply(initialEntry)), isRemaining.test(initialEntry));

		// Remove initial entries from remaining to see there are any leftover
		List<T> remainingClone = new ArrayList<T>(remaining);
		remainingClone.removeIf(isInitial);

		for (T remainingEntry : remainingClone)
			LOGGER.info(String.format("Non-genesis remaining entry: %s = %s", keyExtractor.apply(remainingEntry), valueExtractor.apply(remainingEntry)));

		assertTrue(String.format("Non-genesis %s remains", typeName), remainingClone.isEmpty());
	}

	public static void setRepository(boolean inMemory) throws DataException {
		String connectionUrlDisk = String.format(testConnectionUrlDisk, Settings.getInstance().getRepositoryPath());
		String connectionUrl = inMemory ? testConnectionUrlMemory : connectionUrlDisk;
		RepositoryFactory repositoryFactory = new HSQLDBRepositoryFactory(connectionUrl);
		RepositoryManager.setRepositoryFactory(repositoryFactory);
	}

	public static void setShouldRetainRepositoryAfterTest(boolean shouldRetain) {
		shouldRetainRepositoryAfterTest = shouldRetain;
	}

	public static void deleteTestRepository() throws DataException {
		if (shouldRetainRepositoryAfterTest) {
			// Don't delete if we've requested to keep the db intact
			return;
		}

		// Delete repository directory if exists
		Path repositoryPath = Paths.get(Settings.getInstance().getRepositoryPath());
		try {
			FileUtils.deleteDirectory(repositoryPath.toFile());
		} catch (IOException e) {
			throw new DataException(String.format("Unable to delete test repository: %s", e.getMessage()));
		}
	}

	@BeforeClass
	public static void setRepositoryInMemory() throws DataException {
		Common.deleteTestRepository();
		Common.setRepository(true);
	}

	@AfterClass
	public static void closeRepository() throws DataException {
		RepositoryManager.closeRepositoryFactory();
		Common.deleteTestRepository(); // Comment out this line in you need to inspect the database after running a test
	}

	// Test assertions

	public static void assertEmptyBlockchain(Repository repository) throws DataException {
		assertEquals("Blockchain should be empty for this test", 0, repository.getBlockRepository().getBlockchainHeight());
	}

	public static void assertEqualBigDecimals(String message, BigDecimal expected, BigDecimal actual) {
		assertTrue(String.format("%s: expected %s, actual %s", message, expected.toPlainString(), actual.toPlainString()),
				actual.compareTo(expected) == 0);
	}

}
