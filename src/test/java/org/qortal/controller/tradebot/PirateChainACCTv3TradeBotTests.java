package org.qortal.controller.tradebot;

import com.rust.litewalletjni.LiteWalletJni;
import org.bitcoinj.core.Bech32;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortal.api.model.crosschain.TradeBotCreateRequest;
import org.qortal.crosschain.PirateChainAddress;
import org.qortal.crosschain.PirateChainACCTv3;
import org.qortal.crosschain.PirateChainRefundState;
import org.qortal.crosschain.SupportedBlockchain;
import org.qortal.data.crosschain.TradeBotData;
import org.qortal.data.crosschain.CrossChainTradeData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.settings.Settings;
import org.qortal.test.common.Common;
import org.qortal.utils.Base58;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import org.mockito.MockedStatic;
import org.qortal.crosschain.PirateChain;
import org.qortal.crosschain.PirateChainHTLC;
import org.qortal.crosschain.UnspentOutput;
import org.qortal.repository.MessageRepository;
import org.qortal.utils.NTP;

/** Offline trade creation plus real HSQLDB and backup round trips. No native spending. */
public class PirateChainACCTv3TradeBotTests extends Common {

	private static final String SAPLING = "zs1mj80tkyjqjhc84aquetymwgrasxjdkkft0qcn5xtp2p9r42vs5ernyv4nzgufmz6xzuqvw20w9z";
	private static final String IRONWOOD = "pirate1lyp0g5438qkp9s5me5epku9ak9k0dxqsfyt9z894zym8qw38sps488ljyee27yw29vc2cyj8mse";
	private Field loadedField;
	private boolean previouslyLoaded;
	private Field exportPathField;
	private String previousExportPath;
	private Path backupDirectory;

	@Before
	public void beforeTest() throws Exception {
		Common.useDefaultSettings();
		// Creation only needs a creator reference, not a live chain or genesis replay.
		try (Repository repository = RepositoryManager.getRepository()) {
			Common.getTestAccount(repository, "alice").setLastReference(new byte[64]);
			repository.saveChanges();
		}
		backupDirectory = Files.createTempDirectory(Path.of("target"), "pirate-tradebot-backup-");
		exportPathField = Settings.class.getDeclaredField("exportPath");
		exportPathField.setAccessible(true);
		previousExportPath = Settings.getInstance().getExportPath();
		exportPathField.set(Settings.getInstance(), backupDirectory.toString());
		// The seller only needs the architecture/load gate; creation must not call JNI.
		loadedField = LiteWalletJni.class.getDeclaredField("loaded");
		loadedField.setAccessible(true);
		previouslyLoaded = loadedField.getBoolean(null);
		loadedField.setBoolean(null, true);
	}

	@After
	public void afterTest() throws Exception {
		if (loadedField != null)
			loadedField.setBoolean(null, previouslyLoaded);
		if (exportPathField != null)
			exportPathField.set(Settings.getInstance(), previousExportPath);
	}

	@Test
	public void refundCheckpointFitsExistingDatabaseAndBackupField() throws Exception {
		TradeBotData saved = createAndReload(IRONWOOD);
		byte[] checkpoint = new PirateChainRefundState(4200000, 1700000000000L, "ab".repeat(32)).encode();
		try (Repository repository = RepositoryManager.getRepository()) {
			saved.setLastTransactionSignature(checkpoint);
			repository.getCrossChainRepository().save(saved);
			repository.saveChanges();
		}
		try (Repository repository = RepositoryManager.getRepository()) {
			TradeBotData restored = repository.getCrossChainRepository().getTradeBotData(saved.getTradePrivateKey());
			assertArrayEquals(checkpoint, restored.getLastTransactionSignature());
			assertArrayEquals(checkpoint, TradeBotData.fromJson(restored.toJson()).getLastTransactionSignature());
		}
	}

	@Test
	public void fundingCapturesActualReserveAndOutputIndex() throws Exception {
		PirateChain pirate = mock(PirateChain.class);
		when(pirate.getP2shFee(any())).thenReturn(20000L);
		when(pirate.deriveP2shAddress(any())).thenReturn("funding-address");
		when(pirate.fundP2SH(anyString(), anyString(), anyLong(), anyString())).thenReturn("11".repeat(32));
		try (Repository repository = RepositoryManager.getRepository();
			 MockedStatic<PirateChain> pirates = mockStatic(PirateChain.class);
			 MockedStatic<PirateChainHTLC> htlc = mockStatic(PirateChainHTLC.class, CALLS_REAL_METHODS);
			 MockedStatic<NTP> clock = mockStatic(NTP.class)) {
			pirates.when(PirateChain::getInstance).thenReturn(pirate);
			clock.when(NTP::getTime).thenReturn(1000000L);
			htlc.when(() -> PirateChainHTLC.getFundingOutput(any(), anyString(), anyString(), anyLong()))
					.thenReturn(new UnspentOutput(new byte[32], 2, 0, 100020000L));
			Repository spy = spy(repository);
			MessageRepository messages = mock(MessageRepository.class);
			doReturn(messages).when(spy).getMessageRepository();
			when(messages.exists(any(), any(), any())).thenReturn(true); // No PoW worker is needed for this fixture.
			CrossChainTradeData data = new CrossChainTradeData();
			data.qortalAtAddress = Common.getTestAccount(repository, "bob").getAddress();
			data.creatorForeignPKH = new byte[33]; data.tradeTimeout = 60;
			data.qortAmount = 100000000L; data.expectedForeignAmount = 100000000L;
			assertEquals(AcctTradeBot.ResponseResult.OK, PirateChainACCTv3TradeBot.getInstance().startResponse(spy, null,
					PirateChainACCTv3.getInstance(), data, Base58.encode(new byte[32]), Common.getTestAccount(repository, "alice").getAddress()));
			TradeBotData saved = repository.getCrossChainRepository().getAllTradeBotData().get(0);
			assertEquals(Long.valueOf(20000L), saved.getFundingFee()); assertEquals(2, saved.getFundingOutputIndex());
			verify(pirate).fundP2SH(anyString(), anyString(), eq(100020000L), anyString());
			JSONObject backup = new JSONObject(Files.readString(backupDirectory.resolve("TradeBotStates.json")));
			assertEquals(20000L, backup.getJSONArray("data").getJSONObject(0).getLong("fundingFee"));
		}
	}

	@Test
	public void fundingMetadataSurvivesRepositoryAndBackupRoundTrips() throws Exception {
		TradeBotData saved = createAndReload(SAPLING);
		assertNull(saved.getFundingFee());
		assertEquals(0, saved.getFundingOutputIndex());
		JSONObject legacy = saved.toJson(); legacy.remove("fundingFee"); legacy.remove("fundingOutputIndex");
		TradeBotData legacyRestored = TradeBotData.fromJson(legacy);
		assertNull(legacyRestored.getFundingFee());
		assertEquals(0, legacyRestored.getFundingOutputIndex());
		saved.setFundingFee(20000L); saved.setFundingOutputIndex(2);
		try (Repository repository = RepositoryManager.getRepository()) {
			repository.getCrossChainRepository().save(saved); repository.saveChanges();
		}
		try (Repository repository = RepositoryManager.getRepository()) {
			TradeBotData restored = repository.getCrossChainRepository().getTradeBotData(saved.getTradePrivateKey());
			assertEquals(Long.valueOf(20000), restored.getFundingFee());
			assertEquals(2, restored.getFundingOutputIndex());
			assertEquals(2, repository.getCrossChainRepository().getAllTradeBotData().get(0).getFundingOutputIndex());
			TradeBotData backup = TradeBotData.fromJson(restored.toJson());
			assertEquals(Long.valueOf(20000), backup.getFundingFee()); assertEquals(2, backup.getFundingOutputIndex());
		}
	}

	@Test
	public void migrationPreservesExistingRowsWithUnknownFeeAndOutputZero() throws Exception {
		try (java.sql.Connection connection = java.sql.DriverManager.getConnection("jdbc:hsqldb:mem:arrr-migration-" + System.nanoTime(), "SA", "")) {
			try (java.sql.Statement statement = connection.createStatement()) {
				statement.execute("CREATE TABLE DatabaseInfo (version INT)"); statement.execute("INSERT INTO DatabaseInfo VALUES (53)");
				statement.execute("CREATE TABLE TradeBotStates (trade_private_key VARBINARY(32))");
				statement.execute("INSERT INTO TradeBotStates VALUES (X'01')");
				org.qortal.repository.hsqldb.HSQLDBDatabaseUpdates.updateDatabase(connection);
				try (java.sql.ResultSet row = statement.executeQuery("SELECT funding_fee, funding_output_index FROM TradeBotStates")) {
					assertTrue(row.next()); assertNull(row.getObject(1)); assertEquals(0, row.getInt(2));
				}
				assertEquals(54, org.qortal.repository.hsqldb.HSQLDBDatabaseUpdates.fetchDatabaseVersion(connection));
				statement.execute("SHUTDOWN");
			}
		}
	}

	@Test
	public void unavailableWalletIsNotReportedAsInsufficientBalance() throws Exception {
		// Exercise the actual buy funding path. The JNI load gate prevents any native spending.
		loadedField.setBoolean(null, false);
		try (Repository repository = RepositoryManager.getRepository()) {
			CrossChainTradeData trade = new CrossChainTradeData();
			trade.qortalAtAddress = Common.getTestAccount(repository, "bob").getAddress();
			trade.creatorForeignPKH = new byte[20];
			trade.tradeTimeout = 60;
			trade.qortAmount = 100_000_000L;
			trade.expectedForeignAmount = 500_000_000L;
			assertEquals(AcctTradeBot.ResponseResult.NETWORK_ISSUE,
					PirateChainACCTv3TradeBot.getInstance().startResponse(repository, null,
							PirateChainACCTv3.getInstance(), trade, Base58.encode(new byte[32]),
							Common.getTestAccount(repository, "alice").getAddress()));
			assertTrue("Failed funding must not leave an active buy", repository.getCrossChainRepository().getAllTradeBotData().isEmpty());
		}
	}

	@Test
	public void ironwoodSellOrderSurvivesDatabaseAndBackupWithoutWalletSecret() throws Exception {
		TradeBotData saved = createAndReload(IRONWOOD);
		assertEquals(IRONWOOD, new String(saved.getReceivingAccountInfo(), StandardCharsets.US_ASCII));
	}

	@Test
	public void saplingSellOrderKeepsLegacyStorageAndSettlement() throws Exception {
		TradeBotData saved = createAndReload(SAPLING);
		assertArrayEquals(Bech32.decode(SAPLING).data, saved.getReceivingAccountInfo());
	}

	@Test
	public void invalidReceivingAddressDoesNotCreateATOrTradeRecord() throws Exception {
		try (Repository repository = RepositoryManager.getRepository()) {
			TradeBotCreateRequest request = request(repository, "invalid");
			try {
				PirateChainACCTv3TradeBot.getInstance().createTrade(repository, request);
				fail("Expected invalid receiving address to be rejected");
			} catch (DataException expected) {
				assertTrue(expected.getMessage().contains("Unsupported Pirate Chain receiving address"));
			}
			assertTrue(repository.getCrossChainRepository().getAllTradeBotData().isEmpty());
		}
	}

	private TradeBotData createAndReload(String address) throws Exception {
		byte[] tradePrivateKey;
		try (Repository repository = RepositoryManager.getRepository()) {
			byte[] unsignedAT = PirateChainACCTv3TradeBot.getInstance().createTrade(repository, request(repository, address));
			assertTrue(unsignedAT.length > 0);
			List<TradeBotData> trades = repository.getCrossChainRepository().getAllTradeBotData();
			assertEquals(1, trades.size());
			tradePrivateKey = trades.get(0).getTradePrivateKey();
		}

		try (Repository repository = RepositoryManager.getRepository()) {
			TradeBotData saved = repository.getCrossChainRepository().getTradeBotData(tradePrivateKey);
			assertNotNull(saved);
			assertNull("Sellers must not persist a wallet secret", saved.getForeignKey());
			assertEquals(address, PirateChainAddress.fromReceivingAccountInfo(saved.getReceivingAccountInfo()));
			JSONObject backup = new JSONObject(Files.readString(backupDirectory.resolve("TradeBotStates.json")));
			TradeBotData restored = TradeBotData.fromJson(backup.getJSONArray("data").getJSONObject(0));
			assertEquals(address, PirateChainAddress.fromReceivingAccountInfo(restored.getReceivingAccountInfo()));
			assertNull(restored.getForeignKey());
			return saved;
		}
	}

	private TradeBotCreateRequest request(Repository repository, String address) {
		TradeBotCreateRequest request = new TradeBotCreateRequest();
		request.creatorPublicKey = Common.getTestAccount(repository, "alice").getPublicKey();
		request.foreignBlockchain = SupportedBlockchain.PIRATECHAIN;
		request.receivingAddress = address;
		request.qortAmount = 100_000_000L;
		request.fundingQortAmount = 200_000_000L;
		request.foreignAmount = 100_000_000L;
		request.tradeTimeout = 60;
		return request;
	}
}
