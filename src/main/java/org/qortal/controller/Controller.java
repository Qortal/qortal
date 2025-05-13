package org.qortal.controller;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider;
import org.qortal.account.Account;
import org.qortal.api.ApiService;
import org.qortal.api.DomainMapService;
import org.qortal.api.GatewayService;
import org.qortal.api.resource.TransactionsResource;
import org.qortal.block.Block;
import org.qortal.block.BlockChain;
import org.qortal.block.BlockChain.BlockTimingByHeight;
import org.qortal.controller.arbitrary.*;
import org.qortal.controller.hsqldb.HSQLDBBalanceRecorder;
import org.qortal.controller.hsqldb.HSQLDBDataCacheManager;
import org.qortal.controller.repository.NamesDatabaseIntegrityCheck;
import org.qortal.controller.repository.PruneManager;
import org.qortal.controller.tradebot.TradeBot;
import org.qortal.controller.tradebot.RNSTradeBot;
import org.qortal.data.account.AccountBalanceData;
import org.qortal.data.account.AccountData;
import org.qortal.data.block.BlockData;
import org.qortal.data.block.BlockSummaryData;
import org.qortal.data.naming.NameData;
import org.qortal.data.network.PeerData;
import org.qortal.data.transaction.ArbitraryTransactionData;
import org.qortal.data.transaction.ChatTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.event.Event;
import org.qortal.event.EventBus;
import org.qortal.globalization.Translator;
import org.qortal.gui.Gui;
import org.qortal.gui.SysTray;
import org.qortal.network.Network;
import org.qortal.network.RNSNetwork;
import org.qortal.network.RNSPeer;
import org.qortal.network.Peer;
import org.qortal.network.PeerAddress;
import org.qortal.network.message.*;
import org.qortal.repository.*;
import org.qortal.repository.hsqldb.HSQLDBRepositoryFactory;
import org.qortal.settings.Settings;
import org.qortal.transaction.Transaction;
import org.qortal.transaction.Transaction.TransactionType;
import org.qortal.transform.TransformationException;
import org.qortal.utils.*;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import java.awt.TrayIcon.MessageType;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.security.Security;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class Controller extends Thread {

	public static HSQLDBRepositoryFactory REPOSITORY_FACTORY;

	static {
		// This must go before any calls to LogManager/Logger
		System.setProperty("log4j2.formatMsgNoLookups", "true");
		System.setProperty("java.util.logging.manager", "org.apache.logging.log4j.jul.LogManager");
	}

	/** Controller start-up time (ms) taken using <tt>System.currentTimeMillis()</tt>. */
	public static final long startTime = System.currentTimeMillis();
	public static final String VERSION_PREFIX = "qortal-";

	private static final Logger LOGGER = LogManager.getLogger(Controller.class);
	public static final long MISBEHAVIOUR_COOLOFF = 10 * 60 * 1000L; // ms
	private static final int MAX_BLOCKCHAIN_TIP_AGE = 5; // blocks
	private static final Object shutdownLock = new Object();
	private static final String repositoryUrlTemplate = "jdbc:hsqldb:file:%s" + File.separator + "blockchain;create=true;hsqldb.full_log_replay=true";
	private static final long NTP_PRE_SYNC_CHECK_PERIOD = 5 * 1000L; // ms
	private static final long NTP_POST_SYNC_CHECK_PERIOD = 5 * 60 * 1000L; // ms
	private static final long DELETE_EXPIRED_INTERVAL = 5 * 60 * 1000L; // ms

	private static volatile boolean isStopping = false;
	private static BlockMinter blockMinter = null;
	private static volatile boolean requestSysTrayUpdate = true;
	private static Controller instance;

	private final String buildVersion;
	private final long buildTimestamp; // seconds
	private final String[] savedArgs;

	private ExecutorService callbackExecutor = Executors.newFixedThreadPool(4);
	private volatile boolean notifyGroupMembershipChange = false;

	/** Latest blocks on our chain. Note: tail/last is the latest block. */
	private final Deque<BlockData> latestBlocks = new LinkedList<>();

	/** Cache of BlockMessages, indexed by block signature */
	@SuppressWarnings("serial")
	private final LinkedHashMap<ByteArray, CachedBlockMessage> blockMessageCache = new LinkedHashMap<>() {
		@Override
		protected boolean removeEldestEntry(Map.Entry<ByteArray, CachedBlockMessage> eldest) {
			return this.size() > Settings.getInstance().getBlockCacheSize();
		}
	};

	private long repositoryBackupTimestamp = startTime; // ms
	private long repositoryMaintenanceTimestamp = startTime; // ms
	private long repositoryCheckpointTimestamp = startTime; // ms
	private long prunePeersTimestamp = startTime; // ms
	private long ntpCheckTimestamp = startTime; // ms
	private long pruneRNSPeersTimestamp = startTime; // ms
	private long deleteExpiredTimestamp = startTime + DELETE_EXPIRED_INTERVAL; // ms

	/** Whether we can mint new blocks, as reported by BlockMinter. */
	private volatile boolean isMintingPossible = false;

	/** Lock for only allowing one blockchain-modifying codepath at a time. e.g. synchronization or newly minted block. */
	private final ReentrantLock blockchainLock = new ReentrantLock();

	// Stats
	@XmlAccessorType(XmlAccessType.FIELD)
	public static class StatsSnapshot {
		public static class GetBlockMessageStats {
			public AtomicLong requests = new AtomicLong();
			public AtomicLong cacheHits = new AtomicLong();
			public AtomicLong unknownBlocks = new AtomicLong();
			public AtomicLong cacheFills = new AtomicLong();

			public GetBlockMessageStats() {
			}
		}
		public GetBlockMessageStats getBlockMessageStats = new GetBlockMessageStats();

		public static class GetBlockSummariesStats {
			public AtomicLong requests = new AtomicLong();
			public AtomicLong cacheHits = new AtomicLong();
			public AtomicLong fullyFromCache = new AtomicLong();

			public GetBlockSummariesStats() {
			}
		}
		public GetBlockSummariesStats getBlockSummariesStats = new GetBlockSummariesStats();

		public static class GetBlockSignaturesV2Stats {
			public AtomicLong requests = new AtomicLong();
			public AtomicLong cacheHits = new AtomicLong();
			public AtomicLong fullyFromCache = new AtomicLong();

			public GetBlockSignaturesV2Stats() {
			}
		}
		public GetBlockSignaturesV2Stats getBlockSignaturesV2Stats = new GetBlockSignaturesV2Stats();

		public static class GetArbitraryDataFileMessageStats {
			public AtomicLong requests = new AtomicLong();
			public AtomicLong unknownFiles = new AtomicLong();

			public GetArbitraryDataFileMessageStats() {
			}
		}
		public GetArbitraryDataFileMessageStats getArbitraryDataFileMessageStats = new GetArbitraryDataFileMessageStats();

		public static class GetArbitraryDataFileListMessageStats {
			public AtomicLong requests = new AtomicLong();
			public AtomicLong unknownFiles = new AtomicLong();

			public GetArbitraryDataFileListMessageStats() {
			}
		}
		public GetArbitraryDataFileListMessageStats getArbitraryDataFileListMessageStats = new GetArbitraryDataFileListMessageStats();

		public static class GetArbitraryMetadataMessageStats {
			public AtomicLong requests = new AtomicLong();
			public AtomicLong unknownFiles = new AtomicLong();

			public GetArbitraryMetadataMessageStats() {
			}
		}
		public GetArbitraryMetadataMessageStats getArbitraryMetadataMessageStats = new GetArbitraryMetadataMessageStats();

		public static class GetAccountMessageStats {
			public AtomicLong requests = new AtomicLong();
			public AtomicLong cacheHits = new AtomicLong();
			public AtomicLong unknownAccounts = new AtomicLong();

			public GetAccountMessageStats() {
			}
		}
		public GetAccountMessageStats getAccountMessageStats = new GetAccountMessageStats();

		public static class GetAccountBalanceMessageStats {
			public AtomicLong requests = new AtomicLong();
			public AtomicLong unknownAccounts = new AtomicLong();

			public GetAccountBalanceMessageStats() {
			}
		}
		public GetAccountBalanceMessageStats getAccountBalanceMessageStats = new GetAccountBalanceMessageStats();

		public static class GetAccountTransactionsMessageStats {
			public AtomicLong requests = new AtomicLong();
			public AtomicLong unknownAccounts = new AtomicLong();

			public GetAccountTransactionsMessageStats() {
			}
		}
		public GetAccountTransactionsMessageStats getAccountTransactionsMessageStats = new GetAccountTransactionsMessageStats();

		public static class GetAccountNamesMessageStats {
			public AtomicLong requests = new AtomicLong();
			public AtomicLong unknownAccounts = new AtomicLong();

			public GetAccountNamesMessageStats() {
			}
		}
		public GetAccountNamesMessageStats getAccountNamesMessageStats = new GetAccountNamesMessageStats();

		public static class GetNameMessageStats {
			public AtomicLong requests = new AtomicLong();
			public AtomicLong unknownAccounts = new AtomicLong();

			public GetNameMessageStats() {
			}
		}
		public GetNameMessageStats getNameMessageStats = new GetNameMessageStats();

		public AtomicLong latestBlocksCacheRefills = new AtomicLong();

		public StatsSnapshot() {
		}
	}
	public final StatsSnapshot stats = new StatsSnapshot();

	// Constructors

	private Controller(String[] args) {
		Properties properties = new Properties();
		try (InputStream in = this.getClass().getResourceAsStream("/build.properties")) {
			properties.load(in);
		} catch (IOException e) {
			throw new RuntimeException("Can't read build.properties resource", e);
		}

		// Determine build timestamp
		String buildTimestampProperty = properties.getProperty("build.timestamp");
		if (buildTimestampProperty == null) {
			throw new RuntimeException("Can't read build.timestamp from build.properties resource");
		}
		if (buildTimestampProperty.startsWith("$")) {
			// Maven vars haven't been replaced - this was most likely built using an IDE, not via mvn package
			this.buildTimestamp = System.currentTimeMillis();
			buildTimestampProperty = "unknown";
		} else {
			this.buildTimestamp = LocalDateTime.parse(buildTimestampProperty, DateTimeFormatter.ofPattern("yyyyMMddHHmmss")).toEpochSecond(ZoneOffset.UTC);
		}
		LOGGER.info(String.format("Build timestamp: %s", buildTimestampProperty));

		// Determine build version
		String buildVersionProperty = properties.getProperty("build.version");
		if (buildVersionProperty == null) {
			throw new RuntimeException("Can't read build.version from build.properties resource");
		}
		if (buildVersionProperty.contains("${git.commit.id.abbrev}")) {
			// Maven vars haven't been replaced - this was most likely built using an IDE, not via mvn package
			buildVersionProperty = buildVersionProperty.replace("${git.commit.id.abbrev}", "debug");
		}
		this.buildVersion = VERSION_PREFIX + buildVersionProperty;
		LOGGER.info(String.format("Build version: %s", this.buildVersion));

		this.savedArgs = args;
	}

	private static synchronized Controller newInstance(String[] args) {
		instance = new Controller(args);
		return instance;
	}

	public static synchronized Controller getInstance() {
		if (instance == null)
			instance = new Controller(null);

		return instance;
	}

	// Getters / setters

	public static String getRepositoryUrl() {
		return String.format(repositoryUrlTemplate, Settings.getInstance().getRepositoryPath());
	}

	public long getBuildTimestamp() {
		return this.buildTimestamp;
	}

	public String getVersionString() {
		return this.buildVersion;
	}

	public String getVersionStringWithoutPrefix() {
		return this.buildVersion.replaceFirst(VERSION_PREFIX, "");
	}

	/** Returns current blockchain height, or 0 if it's not available. */
	public int getChainHeight() {
		synchronized (this.latestBlocks) {
			BlockData blockData = this.latestBlocks.peekLast();
			if (blockData == null)
				return 0;

			return blockData.getHeight();
		}
	}

	public static long uptime() {
		return System.currentTimeMillis() - Controller.startTime;
	}

	/** Returns highest block, or null if it's not available. */
	public BlockData getChainTip() {
		synchronized (this.latestBlocks) {
			return this.latestBlocks.peekLast();
		}
	}

	public void refillLatestBlocksCache() throws DataException {
		// Set initial chain height/tip
		try (final Repository repository = RepositoryManager.getRepository()) {
			BlockData blockData = repository.getBlockRepository().getLastBlock();
			int blockCacheSize = Settings.getInstance().getBlockCacheSize();

			synchronized (this.latestBlocks) {
				this.latestBlocks.clear();

				for (int i = 0; i < blockCacheSize && blockData != null; ++i) {
					this.latestBlocks.addFirst(blockData);
					blockData = repository.getBlockRepository().fromHeight(blockData.getHeight() - 1);
				}
			}
		}
	}

	public ReentrantLock getBlockchainLock() {
		return this.blockchainLock;
	}

	/* package */ String[] getSavedArgs() {
		return this.savedArgs;
	}

	public static boolean isStopping() {
		return isStopping;
	}

	// For API use
	public boolean isMintingPossible() {
		return this.isMintingPossible;
	}

	// Entry point

	public static void main(String[] args) {
		LoggingUtils.fixLegacyLog4j2Properties();

		LOGGER.info("Starting up...");

		// Potential GUI startup with splash screen, etc.
		Gui.getInstance();

		Security.insertProviderAt(new BouncyCastleProvider(), 0);
		Security.insertProviderAt(new BouncyCastleJsseProvider(), 1);

		// Load/check settings, which potentially sets up blockchain config, etc.
		try {
			if (args.length > 0)
				Settings.fileInstance(args[0]);
			else
				Settings.getInstance();
		} catch (Throwable t) {
			Gui.getInstance().fatalError("Settings file", t.getMessage());
			return; // Not System.exit() so that GUI can display error
		}

		Controller.newInstance(args);

		LOGGER.info("Starting NTP");
		Long ntpOffset = Settings.getInstance().getTestNtpOffset();
		if (ntpOffset != null)
			NTP.setFixedOffset(ntpOffset);
		else
			NTP.start(Settings.getInstance().getNtpServers());

		LOGGER.info("Starting repository");
		try {
			REPOSITORY_FACTORY = new HSQLDBRepositoryFactory(getRepositoryUrl());
			RepositoryManager.setRepositoryFactory(REPOSITORY_FACTORY);
			RepositoryManager.setRequestedCheckpoint(Boolean.TRUE);

			try (final Repository repository = RepositoryManager.getRepository()) {
				// RepositoryManager.rebuildTransactionSequences(repository);
				ArbitraryDataCacheManager.getInstance().buildArbitraryResourcesCache(repository, false);
			}

			if( Settings.getInstance().isDbCacheEnabled() ) {
				LOGGER.info("Db Cache Starting ...");
				HSQLDBDataCacheManager hsqldbDataCacheManager = new HSQLDBDataCacheManager();
				hsqldbDataCacheManager.start();
			}
			else {
				LOGGER.info("Db Cache Disabled");
			}

			LOGGER.info("Arbitrary Indexing Starting ...");
			ArbitraryIndexUtils.startCaching(
				Settings.getInstance().getArbitraryIndexingPriority(),
				Settings.getInstance().getArbitraryIndexingFrequency()
			);

			if( Settings.getInstance().isBalanceRecorderEnabled() ) {
				Optional<HSQLDBBalanceRecorder> recorder = HSQLDBBalanceRecorder.getInstance();

				if( recorder.isPresent() ) {
					LOGGER.info("Balance Recorder Starting ...");
					recorder.get().start();
				}
				else {
					LOGGER.info("Balance Recorder won't start.");
				}
			}
			else {
				LOGGER.info("Balance Recorder Disabled");
			}
		} catch (DataException e) {
			// If exception has no cause or message then repository is in use by some other process.
			if (e.getCause() == null && e.getMessage() == null) {
				LOGGER.info("Repository in use by another process?");
				Gui.getInstance().fatalError("Repository issue", "Repository in use by another process?");
			} else {
				LOGGER.error("Unable to start repository", e);
				Gui.getInstance().fatalError("Repository issue", e);
			}

			return; // Not System.exit() so that GUI can display error
		}

		// If we have a non-lite node, we need to perform some startup actions
		if (!Settings.getInstance().isLite()) {

			// Rebuild Names table and check database integrity (if enabled)
			NamesDatabaseIntegrityCheck namesDatabaseIntegrityCheck = new NamesDatabaseIntegrityCheck();
			namesDatabaseIntegrityCheck.rebuildAllNames();
			if (Settings.getInstance().isNamesIntegrityCheckEnabled()) {
				namesDatabaseIntegrityCheck.runIntegrityCheck();
			}

			LOGGER.info("Validating blockchain");
			try {
				BlockChain.validate();

				Controller.getInstance().refillLatestBlocksCache();
				LOGGER.info(String.format("Our chain height at start-up: %d", Controller.getInstance().getChainHeight()));
			} catch (DataException e) {
				LOGGER.error("Couldn't validate blockchain", e);
				Gui.getInstance().fatalError("Blockchain validation issue", e);
				return; // Not System.exit() so that GUI can display error
			}
		}

		try (Repository repository = RepositoryManager.getRepository()) {
			if (RepositoryManager.needsTransactionSequenceRebuild(repository)) {
				// Don't allow the node to start if transaction sequences haven't been built yet
				// This is needed to handle a case when bootstrapping
				LOGGER.error("Database upgrade needed. Please restart the core to complete the upgrade process.");
				Gui.getInstance().fatalError("Database upgrade needed", "Please restart the core to complete the upgrade process.");
				return;
			}
			if (ArbitraryDataCacheManager.getInstance().needsArbitraryResourcesCacheRebuild(repository)) {
				// Don't allow the node to start if arbitrary resources cache hasn't been built yet
				// This is needed to handle a case when bootstrapping
				LOGGER.error("Database upgrade needed. Please restart the core to complete the upgrade process.");
				Gui.getInstance().fatalError("Database upgrade needed", "Please restart the core to complete the upgrade process.");
				return;
			}
		} catch (DataException e) {
			LOGGER.error("Error checking transaction sequences in repository", e);
			return;
		}

		// Import current trade bot states and minting accounts if they exist
		Controller.importRepositoryData();

		// Add the initial peers to the repository if we don't have any
		Controller.installInitialPeers();

		LOGGER.info("Starting controller");
		Controller.getInstance().start();

		LOGGER.info(String.format("Starting networking on port %d", Settings.getInstance().getListenPort()));
		try {
			Network network = Network.getInstance();
			network.start();
		} catch (IOException | DataException e) {
			LOGGER.error("Unable to start networking", e);
			Controller.getInstance().shutdown();
			Gui.getInstance().fatalError("Networking failure", e);
			return; // Not System.exit() so that GUI can display error
		}

		LOGGER.info("Starting Reticulum");
		try {
			RNSNetwork rns = RNSNetwork.getInstance();
			rns.start();
			LOGGER.debug("Reticulum instance: {}", rns.toString());
		} catch (IOException | DataException e) {
			LOGGER.error("Unable to start Reticulum", e);
		}

		Runtime.getRuntime().addShutdownHook(new Thread() {
			@Override
			public void run() {
				Thread.currentThread().setName("Shutdown hook");
				Controller.getInstance().shutdown();
			}
		});

		LOGGER.info("Starting synchronizer");
		Synchronizer.getInstance().start();

		LOGGER.info("Starting synchronizer over Reticulum");
		RNSSynchronizer.getInstance().start();

		LOGGER.info("Starting block minter");
		blockMinter = new BlockMinter();
		blockMinter.start();

		LOGGER.info("Starting trade-bot");
		TradeBot.getInstance();

		// Arbitrary data controllers
		LOGGER.info("Starting arbitrary-transaction controllers");
		ArbitraryDataManager.getInstance().start();
		ArbitraryDataFileManager.getInstance().start();
		ArbitraryDataCacheManager.getInstance().start();
		ArbitraryDataBuildManager.getInstance().start();
		ArbitraryDataCleanupManager.getInstance().start();
		ArbitraryDataStorageManager.getInstance().start();
		ArbitraryDataRenderManager.getInstance().start();

		// start rebuild arbitrary resource cache timer task
		if( Settings.getInstance().isRebuildArbitraryResourceCacheTaskEnabled() ) {
			new Timer().schedule(
				new RebuildArbitraryResourceCacheTask(),
				Settings.getInstance().getRebuildArbitraryResourceCacheTaskDelay() * RebuildArbitraryResourceCacheTask.MILLIS_IN_MINUTE,
				Settings.getInstance().getRebuildArbitraryResourceCacheTaskPeriod() * RebuildArbitraryResourceCacheTask.MILLIS_IN_HOUR
			);
		}


		LOGGER.info("Starting online accounts manager");
		OnlineAccountsManager.getInstance().start();

		LOGGER.info("Starting transaction importer");
		TransactionImporter.getInstance().start();

		// Auto-update service?
		if (Settings.getInstance().isAutoUpdateEnabled()) {
			LOGGER.info("Starting auto-update");
			AutoUpdate.getInstance().start();
		}

		LOGGER.info("Starting wallets");
		PirateChainWalletController.getInstance().start();

		LOGGER.info(String.format("Starting API on port %d", Settings.getInstance().getApiPort()));
		try {
			ApiService apiService = ApiService.getInstance();
			apiService.start();
		} catch (Exception e) {
			LOGGER.error("Unable to start API", e);
			Controller.getInstance().shutdown();
			Gui.getInstance().fatalError("API failure", e);
			return; // Not System.exit() so that GUI can display error
		}

		if (Settings.getInstance().isGatewayEnabled()) {
			LOGGER.info(String.format("Starting gateway service on port %d", Settings.getInstance().getGatewayPort()));
			try {
				GatewayService gatewayService = GatewayService.getInstance();
				gatewayService.start();
			} catch (Exception e) {
				LOGGER.error("Unable to start gateway service", e);
				Controller.getInstance().shutdown();
				Gui.getInstance().fatalError("Gateway service failure", e);
				return; // Not System.exit() so that GUI can display error
			}
		}

		if (Settings.getInstance().isDomainMapEnabled()) {
			LOGGER.info(String.format("Starting domain map service on port %d", Settings.getInstance().getDomainMapPort()));
			try {
				DomainMapService domainMapService = DomainMapService.getInstance();
				domainMapService.start();
			} catch (Exception e) {
				LOGGER.error("Unable to start domain map service", e);
				Controller.getInstance().shutdown();
				Gui.getInstance().fatalError("Domain map service failure", e);
				return; // Not System.exit() so that GUI can display error
			}
		}

		// If GUI is enabled, we're no longer starting up but actually running now
		Gui.getInstance().notifyRunning();

		if (Settings.getInstance().isAutoRestartEnabled()) {
			// Check every 10 minutes if we have enough connected peers
			Timer checkConnectedPeers = new Timer();

			checkConnectedPeers.schedule(new TimerTask() {
				@Override
				public void run() {
					// Get the connected peers
					int myConnectedPeers = Network.getInstance().getImmutableHandshakedPeers().size();
					LOGGER.debug("Node have {} connected peers", myConnectedPeers);
					if (myConnectedPeers == 0) {
						// Restart node if we have 0 peers
						LOGGER.info("Node have no connected peers, restarting node");
						try {
							RestartNode.attemptToRestart();
						} catch (Exception e) {
							LOGGER.error("Unable to restart the node", e);
						}
					}
				}
			}, 10*60*1000, 10*60*1000);
		}

		// Check every 10 minutes to see if the block minter is running
		Timer checkBlockMinter = new Timer();

		checkBlockMinter.schedule(new TimerTask() {
			@Override
			public void run() {
				if (blockMinter.isAlive()) {
					LOGGER.debug("Block minter is running? {}", blockMinter.isAlive());
				} else if (!blockMinter.isAlive()) {
					LOGGER.debug("Block minter is running? {}", blockMinter.isAlive());
					blockMinter.shutdown();

					try {
						// Wait 10 seconds before restart
						TimeUnit.SECONDS.sleep(10);

						// Start new block minter thread
						LOGGER.info("Restarting block minter");
						blockMinter.start();
					} catch (InterruptedException e) {
						// Couldn't start new block minter thread
						LOGGER.info("Starting block minter failed {}", e);
						throw new RuntimeException(e);
					}
				}
			}
		}, 10*60*1000, 10*60*1000);

		// Check if we need sync from genesis and start syncing
		Timer syncFromGenesis = new Timer();
		syncFromGenesis.schedule(new TimerTask() {
			@Override
			public void run() {
				LOGGER.debug("Start sync from genesis check.");
				boolean canBootstrap = Settings.getInstance().getBootstrap();
				boolean needsArchiveRebuild = false;
				int checkHeight = 0;

				try (final Repository repository = RepositoryManager.getRepository()){
					needsArchiveRebuild = (repository.getBlockArchiveRepository().fromHeight(2) == null);
					checkHeight = repository.getBlockRepository().getBlockchainHeight();
				} catch (DataException e) {
					throw new RuntimeException(e);
				}

				if (canBootstrap || !needsArchiveRebuild || checkHeight > 3) {
					LOGGER.debug("Bootstrapping is enabled or we have more than 2 blocks, cancel sync from genesis check.");
					syncFromGenesis.cancel();
					return;
				}

				if (needsArchiveRebuild && !canBootstrap) {
					LOGGER.info("Start syncing from genesis!");
					List<Peer> seeds = new ArrayList<>(Network.getInstance().getImmutableHandshakedPeers());

					// Check if have a qualified peer to sync
					if (seeds.isEmpty()) {
						LOGGER.info("No connected peers, will try again later.");
						return;
					}

					int index = new SecureRandom().nextInt(seeds.size());
					String syncNode = String.valueOf(seeds.get(index));
					PeerAddress peerAddress = PeerAddress.fromString(syncNode);
					InetSocketAddress resolvedAddress = null;

					try {
						resolvedAddress = peerAddress.toSocketAddress();
					} catch (UnknownHostException e) {
						throw new RuntimeException(e);
					}

					InetSocketAddress finalResolvedAddress = resolvedAddress;
					Peer targetPeer = seeds.stream().filter(peer -> peer.getResolvedAddress().equals(finalResolvedAddress)).findFirst().orElse(null);
					Synchronizer.SynchronizationResult syncResult;

					try {
						do {
							try {
								syncResult = Synchronizer.getInstance().actuallySynchronize(targetPeer, true);
							} catch (InterruptedException e) {
								throw new RuntimeException(e);
							}
						}
						while (syncResult == Synchronizer.SynchronizationResult.OK);
					} finally {
						// We are syncing now, so can cancel the check
						syncFromGenesis.cancel();
					}
				}
			}
		}, 3*60*1000, 3*60*1000);
		//Timer syncFromGenesisRNS = new Timer();
		//syncFromGenesisRNS.schedule(new TimerTask() {
		//	@Override
		//	public void run() {
		//		LOGGER.debug("Start sync from genesis check (RNS).");
		//		boolean canBootstrap = Settings.getInstance().getBootstrap();
		//		boolean needsArchiveRebuildRNS = false;
		//		int checkHeightRNS = 0;
		//
		//		try (final Repository repository = RepositoryManager.getRepository()){
		//			needsArchiveRebuildRNS = (repository.getBlockArchiveRepository().fromHeight(2) == null);
		//			checkHeightRNS = repository.getBlockRepository().getBlockchainHeight();
		//		} catch (DataException e) {
		//			throw new RuntimeException(e);
		//		}
		//
		//		if (canBootstrap || !needsArchiveRebuildRNS || checkHeightRNS > 3) {
		//			LOGGER.debug("Bootstrapping is enabled or we have more than 2 blocks, cancel sync from genesis check.");
		//			syncFromGenesisRNS.cancel();
		//			return;
		//		}
		//
		//		if (needsArchiveRebuildRNS && !canBootstrap) {
		//			LOGGER.info("Start syncing from genesis (RNS)!");
		//			List<RNSPeer> seeds = new ArrayList<>(RNSNetwork.getInstance().getActiveImmutableLinkedPeers());
		//
		//			// Check if have a qualified peer to sync
		//			if (seeds.isEmpty()) {
		//				LOGGER.info("No connected RNSPeer(s), will try again later.");
		//				return;
		//			}
		//
		//			int index = new SecureRandom().nextInt(seeds.size());
		//			RNSPeer syncPeer = seeds.get(index);
		//			var syncPeerLinkAsString = syncPeer.getPeerLink().toString();
		//			//String syncNode = String.valueOf(seeds.get(index));
		//			//PeerAddress peerAddress = PeerAddress.fromString(syncNode);
		//			//InetSocketAddress resolvedAddress = null;
		//			//
		//			//try {
		//			//	resolvedAddress = peerAddress.toSocketAddress();
		//			//} catch (UnknownHostException e) {
		//			//	throw new RuntimeException(e);
		//			//}
		//			//
		//			//InetSocketAddress finalResolvedAddress = resolvedAddress;
		//			//Peer targetPeer = seeds.stream().filter(peer -> peer.getResolvedAddress().equals(finalResolvedAddress)).findFirst().orElse(null);
		//			//RNSPeer targetPeerRNS = seeds.stream().findFirst().orElse(null);
		//			RNSPeer targetPeerRNS = seeds.stream().filter(peer -> peer.getPeerLink().toString().equals(syncPeerLinkAsString)).findFirst().orElse(null);
		//			RNSSynchronizer.SynchronizationResult syncResultRNS;
		//
		//			try {
		//				do {
		//					try {
		//						syncResultRNS = RNSSynchronizer.getInstance().actuallySynchronize(targetPeerRNS, true);
		//					} catch (InterruptedException e) {
		//						throw new RuntimeException(e);
		//					}
		//				}
		//				while (syncResultRNS == RNSSynchronizer.SynchronizationResult.OK);
		//			} finally {
		//				// We are syncing now, so can cancel the check
		//				syncFromGenesisRNS.cancel();
		//			}
		//		}
		//	}
		//}, 3*60*1000, 3*60*1000);
	}

	/** Called by AdvancedInstaller's launch EXE in single-instance mode, when an instance is already running. */
	public static void secondaryMain(String[] args) {
		// Return as we don't want to run more than one instance
	}

	// Main thread

	@Override
	public void run() {
		Thread.currentThread().setName("Qortal");

		final long repositoryBackupInterval = Settings.getInstance().getRepositoryBackupInterval();
		final long repositoryCheckpointInterval = Settings.getInstance().getRepositoryCheckpointInterval();
		long repositoryMaintenanceInterval = getRandomRepositoryMaintenanceInterval();
		final long prunePeersInterval = 5 * 60 * 1000L; // Every 5 minutes
		//final long pruneRNSPeersInterval = 5 * 60 * 1000L; // Every 5 minutes
		final long pruneRNSPeersInterval = 1 * 60 * 1000L; // Every 1 minute (during development)

		// Start executor service for trimming or pruning
		PruneManager.getInstance().start();

		try {
			while (!isStopping) {
				// Maybe update SysTray
				if (requestSysTrayUpdate) {
					requestSysTrayUpdate = false;
					updateSysTray();
				}

				Thread.sleep(1000);

				final long now = System.currentTimeMillis();

				// Check NTP status
				if (now >= ntpCheckTimestamp) {
					Long ntpTime = NTP.getTime();

					if (ntpTime != null) {
						if (ntpTime != now)
							// Only log if non-zero offset
							LOGGER.info(String.format("Adjusting system time by NTP offset: %dms", ntpTime - now));

						ntpCheckTimestamp = now + NTP_POST_SYNC_CHECK_PERIOD;
						requestSysTrayUpdate = true;
					} else {
						LOGGER.info(String.format("No NTP offset yet"));
						ntpCheckTimestamp = now + NTP_PRE_SYNC_CHECK_PERIOD;
						// We can't do much without a valid NTP time
						continue;
					}
				}

				// Clean up arbitrary data request cache
				ArbitraryDataManager.getInstance().cleanupRequestCache(now);
				// Clean up arbitrary data queues and lists
				ArbitraryDataBuildManager.getInstance().cleanupQueues(now);

				// Time to 'checkpoint' uncommitted repository writes?
				if (now >= repositoryCheckpointTimestamp + repositoryCheckpointInterval) {
					repositoryCheckpointTimestamp = now + repositoryCheckpointInterval;

					RepositoryManager.setRequestedCheckpoint(Boolean.TRUE);
				}

				// Give repository a chance to backup (if enabled)
				if (repositoryBackupInterval > 0 && now >= repositoryBackupTimestamp + repositoryBackupInterval) {
					repositoryBackupTimestamp = now + repositoryBackupInterval;

					if (Settings.getInstance().getShowBackupNotification())
						SysTray.getInstance().showMessage(Translator.INSTANCE.translate("SysTray", "DB_BACKUP"),
								Translator.INSTANCE.translate("SysTray", "CREATING_BACKUP_OF_DB_FILES"),
								MessageType.INFO);

					try {
						// Timeout if the database isn't ready for backing up after 60 seconds
						long timeout = 60 * 1000L;
						RepositoryManager.backup(true, "backup", timeout);

					} catch (TimeoutException e) {
						LOGGER.info("Attempt to backup repository failed due to timeout: {}", e.getMessage());
					}
				}

				// Give repository a chance to perform maintenance (if enabled)
				if (repositoryMaintenanceInterval > 0 && now >= repositoryMaintenanceTimestamp + repositoryMaintenanceInterval) {
					repositoryMaintenanceTimestamp = now + repositoryMaintenanceInterval;

					if (Settings.getInstance().getShowMaintenanceNotification())
						SysTray.getInstance().showMessage(Translator.INSTANCE.translate("SysTray", "DB_MAINTENANCE"),
								Translator.INSTANCE.translate("SysTray", "PERFORMING_DB_MAINTENANCE"),
								MessageType.INFO);

					LOGGER.info("Starting scheduled repository maintenance. This can take a while...");
					int attempts = 0;
					while (attempts <= 5) {
						try (final Repository repository = RepositoryManager.getRepository()) {
							attempts++;

							// Timeout if the database isn't ready for maintenance after 60 seconds
							long timeout = 60 * 1000L;
							repository.performPeriodicMaintenance(timeout);

							LOGGER.info("Scheduled repository maintenance completed");
							break;
						} catch (DataException | TimeoutException e) {
							LOGGER.info("Scheduled repository maintenance failed. Retrying up to 5 times...", e);
						}
					}

					// Get a new random interval
					repositoryMaintenanceInterval = getRandomRepositoryMaintenanceInterval();
				}

				// Prune stuck/slow/old peers
				if (now >= prunePeersTimestamp + prunePeersInterval) {
					prunePeersTimestamp = now + prunePeersInterval;

					try {
						LOGGER.debug("Pruning peers...");
						Network.getInstance().prunePeers();
					} catch (DataException e) {
						LOGGER.warn(String.format("Repository issue when trying to prune peers: %s", e.getMessage()));
					}
				}

				// Q: Do we need global pruning?
				if (now >= pruneRNSPeersTimestamp + pruneRNSPeersInterval) {
					pruneRNSPeersTimestamp = now + pruneRNSPeersInterval;

					try {
						LOGGER.debug("Pruning Reticulum peers...");
						RNSNetwork.getInstance().prunePeers();
					} catch (DataException e) {
						LOGGER.warn(String.format("Repository issue when trying to prune Reticulum peers: %s", e.getMessage()));
					}
				}

				// Delete expired transactions
				if (now >= deleteExpiredTimestamp) {
					deleteExpiredTimestamp = now + DELETE_EXPIRED_INTERVAL;
					deleteExpiredTransactions();
				}
			}
		} catch (InterruptedException e) {
			// Clear interrupted flag so we can shutdown trim threads
			Thread.interrupted();
			// Fall-through to exit
		} finally {
			PruneManager.getInstance().stop();
		}
	}

	/**
	 * Import current trade bot states and minting accounts.
	 * This is needed because the user may have bootstrapped, or there could be a database inconsistency
	 * if the core crashed when computing the nonce during the start of the trade process.
	 */
	private static void importRepositoryData() {
		try (final Repository repository = RepositoryManager.getRepository()) {

			String exportPath = Settings.getInstance().getExportPath();
			try {
				Path importPath = Paths.get(exportPath, "TradeBotStates.json");
				repository.importDataFromFile(importPath.toString());
			} catch (FileNotFoundException e) {
				// Do nothing, as the files will only exist in certain cases
			}

			try {
				Path importPath = Paths.get(exportPath, "MintingAccounts.json");
				repository.importDataFromFile(importPath.toString());
			} catch (FileNotFoundException e) {
				// Do nothing, as the files will only exist in certain cases
			}
			repository.saveChanges();
		}
		catch (DataException | IOException e) {
			LOGGER.info("Unable to import data into repository: {}", e.getMessage());
		}
	}

	private static void installInitialPeers() {
		try (final Repository repository = RepositoryManager.getRepository()) {
			if (repository.getNetworkRepository().getAllPeers().isEmpty()) {
				Network.installInitialPeers(repository);
			}

		} catch (DataException e) {
			// Fail silently as this is an optional step
		}
	}

	public static final Predicate<Peer> hasMisbehaved = peer -> {
		final Long lastMisbehaved = peer.getPeerData().getLastMisbehaved();
		return lastMisbehaved != null && lastMisbehaved > NTP.getTime() - MISBEHAVIOUR_COOLOFF;
	};

	public static final Predicate<Peer> hasNoRecentBlock = peer -> {
		final Long minLatestBlockTimestamp = getMinimumLatestBlockTimestamp();
		final BlockSummaryData peerChainTipData = peer.getChainTipData();
		return peerChainTipData == null || peerChainTipData.getTimestamp() == null || peerChainTipData.getTimestamp() < minLatestBlockTimestamp;
	};

	public static final Predicate<RNSPeer> hasNoRecentBlock2 = peer -> {
		final Long minLatestBlockTimestamp = getMinimumLatestBlockTimestamp();
		final BlockSummaryData peerChainTipData = peer.getChainTipData();
		return peerChainTipData == null || peerChainTipData.getTimestamp() == null || peerChainTipData.getTimestamp() < minLatestBlockTimestamp;
	};

	public static final Predicate<Peer> hasNoOrSameBlock = peer -> {
		final BlockData latestBlockData = getInstance().getChainTip();
		final BlockSummaryData peerChainTipData = peer.getChainTipData();
		return peerChainTipData == null || peerChainTipData.getSignature() == null || Arrays.equals(latestBlockData.getSignature(), peerChainTipData.getSignature());
	};

	public static final Predicate<RNSPeer> hasNoOrSameBlock2 = peer -> {
		final BlockData latestBlockData = getInstance().getChainTip();
		final BlockSummaryData peerChainTipData = peer.getChainTipData();
		return peerChainTipData == null || peerChainTipData.getSignature() == null || Arrays.equals(latestBlockData.getSignature(), peerChainTipData.getSignature());
	};

	public static final Predicate<Peer> hasOnlyGenesisBlock = peer -> {
		final BlockSummaryData peerChainTipData = peer.getChainTipData();
		return peerChainTipData == null || peerChainTipData.getHeight() == 1;
	};

	public static final Predicate<RNSPeer> hasOnlyGenesisBlock2 = peer -> {
		final BlockSummaryData peerChainTipData = peer.getChainTipData();
		return peerChainTipData == null || peerChainTipData.getHeight() == 1;
	};


	public static final Predicate<Peer> hasInferiorChainTip = peer -> {
		final BlockSummaryData peerChainTipData = peer.getChainTipData();
		final List<ByteArray> inferiorChainTips = Synchronizer.getInstance().inferiorChainSignatures;
		return peerChainTipData == null || peerChainTipData.getSignature() == null || inferiorChainTips.contains(ByteArray.wrap(peerChainTipData.getSignature()));
	};

	public static final Predicate<RNSPeer> hasInferiorChainTip2 = peer -> {
		final BlockSummaryData peerChainTipData = peer.getChainTipData();
		final List<ByteArray> inferiorChainTips = Synchronizer.getInstance().inferiorChainSignatures;
		return peerChainTipData == null || peerChainTipData.getSignature() == null || inferiorChainTips.contains(ByteArray.wrap(peerChainTipData.getSignature()));
	};

	public static final Predicate<Peer> hasOldVersion = peer -> {
		final String minPeerVersion = Settings.getInstance().getMinPeerVersion();
		return !peer.isAtLeastVersion(minPeerVersion);
	};

	public static final Predicate<Peer> hasInvalidSigner = peer -> {
		final BlockSummaryData peerChainTipData = peer.getChainTipData();
		if (peerChainTipData == null)
			return true;

		try (Repository repository = RepositoryManager.getRepository()) {
			return Account.getRewardShareEffectiveMintingLevel(repository, peerChainTipData.getMinterPublicKey()) == 0;
		} catch (DataException e) {
			return true;
		}
	};

	public static final Predicate<RNSPeer> hasInvalidSigner2 = peer -> {
		final BlockSummaryData peerChainTipData = peer.getChainTipData();
		if (peerChainTipData == null)
			return true;

		try (Repository repository = RepositoryManager.getRepository()) {
			return Account.getRewardShareEffectiveMintingLevel(repository, peerChainTipData.getMinterPublicKey()) == 0;
		} catch (DataException e) {
			return true;
		}
	};

	public static final Predicate<Peer> wasRecentlyTooDivergent = peer -> {
		Long now = NTP.getTime();
		Long peerLastTooDivergentTime = peer.getLastTooDivergentTime();
		if (now == null || peerLastTooDivergentTime == null)
			return false;

		// Exclude any peers that were TOO_DIVERGENT in the last 5 mins
		return (now - peerLastTooDivergentTime < 5 * 60 * 1000L);
	};

	private long getRandomRepositoryMaintenanceInterval() {
		final long minInterval = Settings.getInstance().getRepositoryMaintenanceMinInterval();
		final long maxInterval = Settings.getInstance().getRepositoryMaintenanceMaxInterval();
		if (maxInterval == 0) {
			return 0;
		}
		return (new Random().nextLong() % (maxInterval - minInterval)) + minInterval;
	}

	/**
	 * Export current trade bot states and minting accounts.
	 */
	public void exportRepositoryData() {
		try (final Repository repository = RepositoryManager.getRepository()) {
			repository.exportNodeLocalData();

		} catch (DataException e) {
			// Fail silently as this is an optional step
		}
	}


	public static class StatusChangeEvent implements Event {
		public StatusChangeEvent() {
		}
	}

	public void updateSysTray() {
		if (NTP.getTime() == null) {
			SysTray.getInstance().setToolTipText(Translator.INSTANCE.translate("SysTray", "SYNCHRONIZING_CLOCK"));
			SysTray.getInstance().setTrayIcon(1);
			return;
		}

		final int numberOfPeers = Network.getInstance().getImmutableHandshakedPeers().size();

		final int height = getChainHeight();

		String connectionsText = Translator.INSTANCE.translate("SysTray", numberOfPeers != 1 ? "CONNECTIONS" : "CONNECTION");
		String heightText = Translator.INSTANCE.translate("SysTray", "BLOCK_HEIGHT");

		String actionText;

		// Use a more tolerant latest block timestamp in the isUpToDate() calls below to reduce misleading statuses.
		// Any block in the last 2 hours is considered "up to date" for the purposes of displaying statuses.
		// This also aligns with the time interval required for continued online account submission.
		final Long minLatestBlockTimestamp = NTP.getTime() - (2 * 60 * 60 * 1000L);

		// Only show sync percent if it's less than 100, to avoid confusion
		final Integer syncPercent = Synchronizer.getInstance().getSyncPercent();
		final boolean isSyncing = (syncPercent != null && syncPercent < 100);

		synchronized (Synchronizer.getInstance().syncLock) {
			if (Settings.getInstance().isLite()) {
				actionText = Translator.INSTANCE.translate("SysTray", "LITE_NODE");
				SysTray.getInstance().setTrayIcon(4);
			}
			else if (numberOfPeers < Settings.getInstance().getMinBlockchainPeers()) {
				actionText = Translator.INSTANCE.translate("SysTray", "CONNECTING");
				SysTray.getInstance().setTrayIcon(3);
			}
			else if (!this.isUpToDate(minLatestBlockTimestamp) && isSyncing) {
				actionText = String.format("%s - %d%%", Translator.INSTANCE.translate("SysTray", "SYNCHRONIZING_BLOCKCHAIN"), Synchronizer.getInstance().getSyncPercent());
				SysTray.getInstance().setTrayIcon(3);
			}
			else if (!this.isUpToDate(minLatestBlockTimestamp)) {
				actionText = String.format("%s", Translator.INSTANCE.translate("SysTray", "SYNCHRONIZING_BLOCKCHAIN"));
				SysTray.getInstance().setTrayIcon(3);
			}
			else if (OnlineAccountsManager.getInstance().hasActiveOnlineAccountSignatures()) {
				actionText = Translator.INSTANCE.translate("SysTray", "MINTING_ENABLED");
				SysTray.getInstance().setTrayIcon(2);
			}
			else {
				actionText = Translator.INSTANCE.translate("SysTray", "MINTING_DISABLED");
				SysTray.getInstance().setTrayIcon(4);
			}
		}

		String tooltip = String.format("%s - %d %s", actionText, numberOfPeers, connectionsText);
		if (!Settings.getInstance().isLite()) {
			tooltip = tooltip.concat(String.format(" - %s %d", heightText, height));

			final Integer blocksRemaining = Synchronizer.getInstance().getBlocksRemaining();
			if (blocksRemaining != null && blocksRemaining > 0) {
				String blocksRemainingText = Translator.INSTANCE.translate("SysTray", "BLOCKS_REMAINING");
				tooltip = tooltip.concat(String.format(" - %d %s", blocksRemaining, blocksRemainingText));
			}
		}
		tooltip = tooltip.concat(String.format("\n%s: %s", Translator.INSTANCE.translate("SysTray", "BUILD_VERSION"), this.buildVersion));
		SysTray.getInstance().setToolTipText(tooltip);

		this.callbackExecutor.execute(() -> {
			EventBus.INSTANCE.notify(new StatusChangeEvent());
		});
	}

	public void deleteExpiredTransactions() {
		final Long now = NTP.getTime();
		if (now == null)
			return;

		// This isn't critical so don't block for repository instance.
		try (final Repository repository = RepositoryManager.tryRepository()) {
			if (repository == null)
				return;

			List<TransactionData> transactions = repository.getTransactionRepository().getUnconfirmedTransactions();

			int deletedCount = 0;
			for (TransactionData transactionData : transactions) {
				Transaction transaction = Transaction.fromData(repository, transactionData);

				if (now >= transaction.getDeadline()) {
					LOGGER.debug(() -> String.format("Deleting expired, unconfirmed transaction %s", Base58.encode(transactionData.getSignature())));
					repository.getTransactionRepository().delete(transactionData);
					this.onExpiredTransaction(transactionData);
					deletedCount++;
				}
			}
			if (deletedCount > 0) {
				LOGGER.info(String.format("Deleted %d expired, unconfirmed transaction%s", deletedCount, (deletedCount == 1 ? "" : "s")));
			}

			repository.saveChanges();
		} catch (DataException e) {
			if (RepositoryManager.isDeadlockRelated(e))
				LOGGER.info("Couldn't delete some expired, unconfirmed transactions this round");
			else
				LOGGER.error("Repository issue while deleting expired unconfirmed transactions", e);
		}
	}


	// Shutdown

	public void shutdown() {
		synchronized (shutdownLock) {
			if (!isStopping) {
				isStopping = true;

				LOGGER.info("Shutting down synchronizer");
				Synchronizer.getInstance().shutdown();

				LOGGER.info("Shutting down API");
				ApiService.getInstance().stop();

				LOGGER.info("Shutting down wallets");
				PirateChainWalletController.getInstance().shutdown();

				if (Settings.getInstance().isAutoUpdateEnabled()) {
					LOGGER.info("Shutting down auto-update");
					AutoUpdate.getInstance().shutdown();
				}

				// Arbitrary data controllers
				LOGGER.info("Shutting down arbitrary-transaction controllers");
				ArbitraryDataManager.getInstance().shutdown();
				ArbitraryDataFileManager.getInstance().shutdown();
				ArbitraryDataCacheManager.getInstance().shutdown();
				ArbitraryDataBuildManager.getInstance().shutdown();
				ArbitraryDataCleanupManager.getInstance().shutdown();
				ArbitraryDataStorageManager.getInstance().shutdown();
				ArbitraryDataRenderManager.getInstance().shutdown();

				LOGGER.info("Shutting down online accounts manager");
				OnlineAccountsManager.getInstance().shutdown();

				LOGGER.info("Shutting down transaction importer");
				TransactionImporter.getInstance().shutdown();

				if (blockMinter != null) {
					LOGGER.info("Shutting down block minter");
					blockMinter.shutdown();
					try {
						blockMinter.join();
					} catch (InterruptedException e) {
						// We were interrupted while waiting for thread to join
					}
				}

				// Export local data
				LOGGER.info("Backing up local data");
				this.exportRepositoryData();

				LOGGER.info("Shutting down networking");
				Network.getInstance().shutdown();

				LOGGER.info("Shutting down Reticulum");
				RNSNetwork.getInstance().shutdown();

				LOGGER.info("Shutting down controller");
				this.interrupt();
				try {
					this.join();
				} catch (InterruptedException e) {
					// We were interrupted while waiting for thread to join
				}

				// Make sure we're the only thread modifying the blockchain when shutting down the repository
				ReentrantLock blockchainLock = Controller.getInstance().getBlockchainLock();
				try {
					if (!blockchainLock.tryLock(5, TimeUnit.SECONDS)) {
						LOGGER.debug("Couldn't acquire blockchain lock even after waiting 5 seconds");
						// Proceed anyway, as we have to shut down
					}
				} catch (InterruptedException e) {
					LOGGER.info("Interrupted when waiting for blockchain lock");
				}

				try {
					LOGGER.info("Shutting down repository");
					RepositoryManager.closeRepositoryFactory();
				} catch (DataException e) {
					LOGGER.error("Error occurred while shutting down repository", e);
				}

				// Release the lock if we acquired it
				if (blockchainLock.isHeldByCurrentThread()) {
					blockchainLock.unlock();
				}

				LOGGER.info("Shutting down NTP");
				NTP.shutdownNow();

				LOGGER.info("Shutdown complete!");
			}
		}
	}

	public void shutdownAndExit() {
		this.shutdown();
		System.exit(0);
	}

	// Callbacks

	public void onGroupMembershipChange(int groupId) {
		/*
		 * We've likely been called in the middle of block processing,
		 * so set a flag for now as other repository sessions won't 'see'
		 * the group membership change until a call to repository.saveChanges().
		 * 
		 * Eventually, onNewBlock() will be executed and queue a callback task.
		 * This callback task will check the flag and notify websocket listeners, etc.
		 * and those listeners will be post-saveChanges() and hence see the new
		 * group membership state.
		 */
		this.notifyGroupMembershipChange = true;
	}

	// Callbacks for/from network

	public void doNetworkBroadcast() {
		if (Settings.getInstance().isLite()) {
			// Lite nodes have nothing to broadcast
			return;
		}

		Network network = Network.getInstance();

		// Send (if outbound) / Request peer lists
		network.broadcast(peer -> peer.isOutbound() ? network.buildPeersMessage(peer) : new GetPeersMessage());

		// Send our current height
		network.broadcastOurChain();

		// Request unconfirmed transaction signatures, but only if we're up-to-date.
		// If we're NOT up-to-date then priority is synchronizing first
		if (isUpToDate())
			network.broadcast(network::buildGetUnconfirmedTransactionsMessage);
	}

	public void doRNSNetworkBroadcast() {
		if (Settings.getInstance().isLite()) {
			// Lite nodes have nothing to broadcast
			return;
		}
		RNSNetwork network = RNSNetwork.getInstance();

		// Send our current height
		network.broadcastOurChain();

		// Request unconfirmed transaction signatures, but only if we're up-to-date.
		// if we're not up-to-dat then priority is synchronizing first
		if (isUpToDateRNS()) {
			network.broadcast(network::buildGetUnconfirmedTransactionsMessage);
		}

	}

	public void doRNSPrunePeers() {
		RNSNetwork network = RNSNetwork.getInstance();

        try {
            LOGGER.debug("Pruning peers...");
            network.prunePeers();
        } catch (DataException e) {
            LOGGER.warn(String.format("Repository issue when trying to prune peers: %s", e.getMessage()));
        }
	}

	public void onMintingPossibleChange(boolean isMintingPossible) {
		this.isMintingPossible = isMintingPossible;
		requestSysTrayUpdate = true;
	}

	public static class NewBlockEvent implements Event {
		private final BlockData blockData;

		public NewBlockEvent(BlockData blockData) {
			this.blockData = blockData;
		}

		public BlockData getBlockData() {
			return this.blockData;
		}
	}

	/**
	 * Callback for when we've received a new block.
	 * <p>
	 * See <b>WARNING</b> for {@link EventBus#notify(Event)}
	 * to prevent deadlocks.
	 */
	public void onNewBlock(BlockData latestBlockData) {
		// Protective copy
		BlockData blockDataCopy = new BlockData(latestBlockData);
		int blockCacheSize = Settings.getInstance().getBlockCacheSize();

		synchronized (this.latestBlocks) {
			BlockData cachedChainTip = this.latestBlocks.peekLast();

			if (cachedChainTip != null && Arrays.equals(cachedChainTip.getSignature(), blockDataCopy.getReference())) {
				// Chain tip is parent for new latest block, so we can safely add new latest block
				this.latestBlocks.addLast(latestBlockData);

				// Trim if necessary
				if (this.latestBlocks.size() >= blockCacheSize)
					this.latestBlocks.pollFirst();
			} else {
				if (cachedChainTip != null)
					// Chain tip didn't match - potentially abnormal behaviour?
					LOGGER.debug(() -> String.format("Cached chain tip %.8s not parent for new latest block %.8s (reference %.8s)",
							Base58.encode(cachedChainTip.getSignature()),
							Base58.encode(blockDataCopy.getSignature()),
							Base58.encode(blockDataCopy.getReference())));

				// Defensively rebuild cache
				try {
					this.stats.latestBlocksCacheRefills.incrementAndGet();

					this.refillLatestBlocksCache();
				} catch (DataException e) {
					LOGGER.warn(() -> "Couldn't refill latest blocks cache?", e);
				}
			}
		}

		this.onNewOrOrphanedBlock(blockDataCopy, NewBlockEvent::new);
	}

	public static class OrphanedBlockEvent implements Event {
		private final BlockData blockData;

		public OrphanedBlockEvent(BlockData blockData) {
			this.blockData = blockData;
		}

		public BlockData getBlockData() {
			return this.blockData;
		}
	}

	/**
	 * Callback for when we've orphaned a block.
	 * <p>
	 * See <b>WARNING</b> for {@link EventBus#notify(Event)}
	 * to prevent deadlocks.
	 */
	public void onOrphanedBlock(BlockData latestBlockData) {
		// Protective copy
		BlockData blockDataCopy = new BlockData(latestBlockData);

		synchronized (this.latestBlocks) {
			BlockData cachedChainTip = this.latestBlocks.pollLast();
			boolean refillNeeded = false;

			if (cachedChainTip != null && Arrays.equals(cachedChainTip.getReference(), blockDataCopy.getSignature())) {
				// Chain tip was parent for new latest block that has been orphaned, so we're good

				// However, if we've emptied the cache then we will need to refill it
				refillNeeded = this.latestBlocks.isEmpty();
			} else {
				if (cachedChainTip != null)
					// Chain tip didn't match - potentially abnormal behaviour?
					LOGGER.debug(() -> String.format("Cached chain tip %.8s (reference %.8s) was not parent for new latest block %.8s",
							Base58.encode(cachedChainTip.getSignature()),
							Base58.encode(cachedChainTip.getReference()),
							Base58.encode(blockDataCopy.getSignature())));

				// Defensively rebuild cache
				refillNeeded = true;
			}

			if (refillNeeded)
				try {
					this.stats.latestBlocksCacheRefills.incrementAndGet();

					this.refillLatestBlocksCache();
				} catch (DataException e) {
					LOGGER.warn(() -> "Couldn't refill latest blocks cache?", e);
				}
		}

		this.onNewOrOrphanedBlock(blockDataCopy, OrphanedBlockEvent::new);
	}

	private void onNewOrOrphanedBlock(BlockData blockDataCopy, Function<BlockData, Event> eventConstructor) {
		requestSysTrayUpdate = true;

		// Notify listeners, trade-bot, etc.
		EventBus.INSTANCE.notify(eventConstructor.apply(blockDataCopy));

		if (this.notifyGroupMembershipChange) {
			this.notifyGroupMembershipChange = false;
			ChatNotifier.getInstance().onGroupMembershipChange();
		}
	}

	public static class NewTransactionEvent implements Event {
		private final TransactionData transactionData;

		public NewTransactionEvent(TransactionData transactionData) {
			this.transactionData = transactionData;
		}

		public TransactionData getTransactionData() {
			return this.transactionData;
		}
	}

	/**
	 * Callback for when we've received a new transaction via API or peer.
	 * <p>
	 * @implSpec performs actions in a new thread
	 */
	public void onNewTransaction(TransactionData transactionData) {
		this.callbackExecutor.execute(() -> {
			// Notify all peers
			Message newTransactionSignatureMessage = new TransactionSignaturesMessage(Arrays.asList(transactionData.getSignature()));
			Network.getInstance().broadcast(broadcastPeer -> newTransactionSignatureMessage);

			// Notify listeners
			EventBus.INSTANCE.notify(new NewTransactionEvent(transactionData));

			// If this is a CHAT transaction, there may be extra listeners to notify
			if (transactionData.getType() == TransactionType.CHAT)
				ChatNotifier.getInstance().onNewChatTransaction((ChatTransactionData) transactionData);
		});
	}

	/**
	 * Callback for when we've deleted an expired, unconfirmed transaction.
	 * <p>
	 * @implSpec performs actions in a new thread
	 */
	public void onExpiredTransaction(TransactionData transactionData) {
		this.callbackExecutor.execute(() -> {

			// If this is an ARBITRARY transaction, we may need to update the cache
			if (transactionData.getType() == TransactionType.ARBITRARY) {
				ArbitraryDataManager.getInstance().onExpiredArbitraryTransaction((ArbitraryTransactionData)transactionData);
			}
		});
	}

	public void onPeerHandshakeCompleted(Peer peer) {
		// Only send if outbound
		if (peer.isOutbound()) {
			// Request peer's unconfirmed transactions
			Message message = new GetUnconfirmedTransactionsMessage();
			if (!peer.sendMessage(message)) {
				peer.disconnect("failed to send request for unconfirmed transactions");
				return;
			}
		}

		requestSysTrayUpdate = true;
	}

	public void onPeerDisconnect(Peer peer) {
		requestSysTrayUpdate = true;
	}

	public void onNetworkMessage(Peer peer, Message message) {
		LOGGER.trace(() -> String.format("Processing %s message from %s", message.getType().name(), peer));

		// Ordered by message type value
		switch (message.getType()) {
			case GET_BLOCK:
				onNetworkGetBlockMessage(peer, message);
				break;

			case GET_BLOCK_SUMMARIES:
				onNetworkGetBlockSummariesMessage(peer, message);
				break;

			case GET_SIGNATURES_V2:
				onNetworkGetSignaturesV2Message(peer, message);
				break;

			case HEIGHT_V2:
				onNetworkHeightV2Message(peer, message);
				break;

			case BLOCK_SUMMARIES_V2:
				onNetworkBlockSummariesV2Message(peer, message);
				break;

			case GET_TRANSACTION:
				TransactionImporter.getInstance().onNetworkGetTransactionMessage(peer, message);
				break;

			case TRANSACTION:
				TransactionImporter.getInstance().onNetworkTransactionMessage(peer, message);
				break;

			case GET_UNCONFIRMED_TRANSACTIONS:
				TransactionImporter.getInstance().onNetworkGetUnconfirmedTransactionsMessage(peer, message);
				break;

			case TRANSACTION_SIGNATURES:
				TransactionImporter.getInstance().onNetworkTransactionSignaturesMessage(peer, message);
				break;

			case GET_ONLINE_ACCOUNTS_V3:
				OnlineAccountsManager.getInstance().onNetworkGetOnlineAccountsV3Message(peer, message);
				break;

			case ONLINE_ACCOUNTS_V3:
				OnlineAccountsManager.getInstance().onNetworkOnlineAccountsV3Message(peer, message);
				break;

			case GET_ARBITRARY_DATA:
				// Not currently supported
				break;

			case ARBITRARY_DATA_FILE_LIST:
				ArbitraryDataFileListManager.getInstance().onNetworkArbitraryDataFileListMessage(peer, message);
				break;

			case GET_ARBITRARY_DATA_FILE:
				ArbitraryDataFileManager.getInstance().onNetworkGetArbitraryDataFileMessage(peer, message);
				break;

			case GET_ARBITRARY_DATA_FILE_LIST:
				ArbitraryDataFileListManager.getInstance().onNetworkGetArbitraryDataFileListMessage(peer, message);
				break;

			case ARBITRARY_SIGNATURES:
				// Not currently supported
				break;

			case GET_ARBITRARY_METADATA:
				ArbitraryMetadataManager.getInstance().onNetworkGetArbitraryMetadataMessage(peer, message);
				break;

			case ARBITRARY_METADATA:
				ArbitraryMetadataManager.getInstance().onNetworkArbitraryMetadataMessage(peer, message);
				break;

			case GET_TRADE_PRESENCES:
				TradeBot.getInstance().onGetTradePresencesMessage(peer, message);
				break;

			case TRADE_PRESENCES:
				TradeBot.getInstance().onTradePresencesMessage(peer, message);
				break;

			case GET_ACCOUNT:
				onNetworkGetAccountMessage(peer, message);
				break;

			case GET_ACCOUNT_BALANCE:
				onNetworkGetAccountBalanceMessage(peer, message);
				break;

			case GET_ACCOUNT_TRANSACTIONS:
				onNetworkGetAccountTransactionsMessage(peer, message);
				break;

			case GET_ACCOUNT_NAMES:
				onNetworkGetAccountNamesMessage(peer, message);
				break;

			case GET_NAME:
				onNetworkGetNameMessage(peer, message);
				break;

			default:
				LOGGER.debug(() -> String.format("Unhandled %s message [ID %d] from peer %s", message.getType().name(), message.getId(), peer));
				break;
		}
	}

	private void onNetworkGetBlockMessage(Peer peer, Message message) {
		GetBlockMessage getBlockMessage = (GetBlockMessage) message;
		byte[] signature = getBlockMessage.getSignature();
		this.stats.getBlockMessageStats.requests.incrementAndGet();

		ByteArray signatureAsByteArray = ByteArray.wrap(signature);

		CachedBlockMessage cachedBlockMessage = this.blockMessageCache.get(signatureAsByteArray);
		int blockCacheSize = Settings.getInstance().getBlockCacheSize();

		// Check cached latest block message
		if (cachedBlockMessage != null) {
			this.stats.getBlockMessageStats.cacheHits.incrementAndGet();

			// We need to duplicate it to prevent multiple threads setting ID on the same message
			CachedBlockMessage clonedBlockMessage = Message.cloneWithNewId(cachedBlockMessage, message.getId());

			if (!peer.sendMessage(clonedBlockMessage))
				peer.disconnect("failed to send block");

			return;
		}

		try (final Repository repository = RepositoryManager.getRepository()) {
			BlockData blockData = repository.getBlockRepository().fromSignature(signature);

			if (blockData != null) {
				if (PruneManager.getInstance().isBlockPruned(blockData.getHeight())) {
					// If this is a pruned block, we likely only have partial data, so best not to sent it
					blockData = null;
				}
			}

			// If we have no block data, we should check the archive in case it's there
			if (blockData == null) {
				if (Settings.getInstance().isArchiveEnabled()) {
					Triple<byte[], Integer, Integer> serializedBlock = BlockArchiveReader.getInstance().fetchSerializedBlockBytesForSignature(signature, true, repository);
					if (serializedBlock != null) {
						byte[] bytes = serializedBlock.getA();
						Integer serializationVersion = serializedBlock.getB();

						Message blockMessage;
						switch (serializationVersion) {
							case 1:
								blockMessage = new CachedBlockMessage(bytes);
								break;

							case 2:
								blockMessage = new CachedBlockV2Message(bytes);
								break;

							default:
								return;
						}
						blockMessage.setId(message.getId());

						// This call also causes the other needed data to be pulled in from repository
						if (!peer.sendMessage(blockMessage)) {
							peer.disconnect("failed to send block");
							// Don't fall-through to caching because failure to send might be from failure to build message
							return;
						}

						// Sent successfully from archive, so nothing more to do
						return;
					}
				}
			}

			if (blockData == null) {
				// We don't have this block
				this.stats.getBlockMessageStats.unknownBlocks.getAndIncrement();

				// Send valid, yet unexpected message type in response, so peer's synchronizer doesn't have to wait for timeout
				LOGGER.debug(() -> String.format("Sending 'block unknown' response to peer %s for GET_BLOCK request for unknown block %s", peer, Base58.encode(signature)));

				// Send generic 'unknown' message as it's very short
				Message blockUnknownMessage = peer.getPeersVersion() >= GenericUnknownMessage.MINIMUM_PEER_VERSION
						? new GenericUnknownMessage()
						: new BlockSummariesMessage(Collections.emptyList());
				blockUnknownMessage.setId(message.getId());
				if (!peer.sendMessage(blockUnknownMessage))
					peer.disconnect("failed to send block-unknown response");
				return;
			}

			Block block = new Block(repository, blockData);

			// V2 support
			if (peer.getPeersVersion() >= BlockV2Message.MIN_PEER_VERSION) {
				Message blockMessage = new BlockV2Message(block);
				blockMessage.setId(message.getId());
				if (!peer.sendMessage(blockMessage)) {
					peer.disconnect("failed to send block");
					// Don't fall-through to caching because failure to send might be from failure to build message
					return;
				}
				return;
			}

			CachedBlockMessage blockMessage = new CachedBlockMessage(block);
			blockMessage.setId(message.getId());

			if (!peer.sendMessage(blockMessage)) {
				peer.disconnect("failed to send block");
				// Don't fall-through to caching because failure to send might be from failure to build message
				return;
			}

			// If request is for a recent block, cache it
			if (getChainHeight() - blockData.getHeight() <= blockCacheSize) {
				this.stats.getBlockMessageStats.cacheFills.incrementAndGet();

				this.blockMessageCache.put(ByteArray.wrap(blockData.getSignature()), blockMessage);
			}
		} catch (DataException e) {
			LOGGER.error(String.format("Repository issue while sending block %s to peer %s", Base58.encode(signature), peer), e);
		} catch (TransformationException e) {
			LOGGER.error(String.format("Serialization issue while sending block %s to peer %s", Base58.encode(signature), peer), e);
		}
	}

	private void onNetworkGetBlockSummariesMessage(Peer peer, Message message) {
		GetBlockSummariesMessage getBlockSummariesMessage = (GetBlockSummariesMessage) message;
		final byte[] parentSignature = getBlockSummariesMessage.getParentSignature();
		this.stats.getBlockSummariesStats.requests.incrementAndGet();

		// If peer's parent signature matches our latest block signature
		// then we have no blocks after that and can short-circuit with an empty response
		BlockData chainTip = getChainTip();
		if (chainTip != null && Arrays.equals(parentSignature, chainTip.getSignature())) {
			Message blockSummariesMessage = peer.getPeersVersion() >= BlockSummariesV2Message.MINIMUM_PEER_VERSION
					? new BlockSummariesV2Message(Collections.emptyList())
					: new BlockSummariesMessage(Collections.emptyList());

			blockSummariesMessage.setId(message.getId());

			if (!peer.sendMessage(blockSummariesMessage))
				peer.disconnect("failed to send block summaries");

			return;
		}

		List<BlockSummaryData> blockSummaries = new ArrayList<>();

		// Attempt to serve from our cache of latest blocks
		synchronized (this.latestBlocks) {
			blockSummaries = this.latestBlocks.stream()
					.dropWhile(cachedBlockData -> !Arrays.equals(cachedBlockData.getReference(), parentSignature))
					.map(BlockSummaryData::new)
					.collect(Collectors.toList());
		}

		if (blockSummaries.isEmpty()) {
			try (final Repository repository = RepositoryManager.getRepository()) {
				int numberRequested = Math.min(Network.MAX_BLOCK_SUMMARIES_PER_REPLY, getBlockSummariesMessage.getNumberRequested());

				BlockData blockData = repository.getBlockRepository().fromReference(parentSignature);
				if (blockData == null) {
					// Try the archive
					blockData = repository.getBlockArchiveRepository().fromReference(parentSignature);
				}

				if (blockData != null) {
					if (PruneManager.getInstance().isBlockPruned(blockData.getHeight())) {
						// If this request contains a pruned block, we likely only have partial data, so best not to sent anything
						// We always prune from the oldest first, so it's fine to just check the first block requested
						blockData = null;
					}
				}

				while (blockData != null && blockSummaries.size() < numberRequested) {
					BlockSummaryData blockSummary = new BlockSummaryData(blockData);
					blockSummaries.add(blockSummary);

					byte[] previousSignature = blockData.getSignature();
					blockData = repository.getBlockRepository().fromReference(previousSignature);
					if (blockData == null) {
						// Try the archive
						blockData = repository.getBlockArchiveRepository().fromReference(previousSignature);
					}
				}
			} catch (DataException e) {
				LOGGER.error(String.format("Repository issue while sending block summaries after %s to peer %s", Base58.encode(parentSignature), peer), e);
			}
		} else {
			this.stats.getBlockSummariesStats.cacheHits.incrementAndGet();

			if (blockSummaries.size() >= getBlockSummariesMessage.getNumberRequested())
				this.stats.getBlockSummariesStats.fullyFromCache.incrementAndGet();
		}

		Message blockSummariesMessage = peer.getPeersVersion() >= BlockSummariesV2Message.MINIMUM_PEER_VERSION
				? new BlockSummariesV2Message(blockSummaries)
				: new BlockSummariesMessage(blockSummaries);
		blockSummariesMessage.setId(message.getId());
		if (!peer.sendMessage(blockSummariesMessage))
			peer.disconnect("failed to send block summaries");
	}

	private void onNetworkGetSignaturesV2Message(Peer peer, Message message) {
		GetSignaturesV2Message getSignaturesMessage = (GetSignaturesV2Message) message;
		final byte[] parentSignature = getSignaturesMessage.getParentSignature();
		this.stats.getBlockSignaturesV2Stats.requests.incrementAndGet();

		// If peer's parent signature matches our latest block signature
		// then we can short-circuit with an empty response
		BlockData chainTip = getChainTip();
		if (chainTip != null && Arrays.equals(parentSignature, chainTip.getSignature())) {
			Message signaturesMessage = new SignaturesMessage(Collections.emptyList());
			signaturesMessage.setId(message.getId());
			if (!peer.sendMessage(signaturesMessage))
				peer.disconnect("failed to send signatures (v2)");

			return;
		}

		List<byte[]> signatures = new ArrayList<>();

		// Attempt to serve from our cache of latest blocks
		synchronized (this.latestBlocks) {
			signatures = this.latestBlocks.stream()
					.dropWhile(cachedBlockData -> !Arrays.equals(cachedBlockData.getReference(), parentSignature))
					.map(BlockData::getSignature)
					.collect(Collectors.toList());
		}

		if (signatures.isEmpty()) {
			try (final Repository repository = RepositoryManager.getRepository()) {
				int numberRequested = getSignaturesMessage.getNumberRequested();
				BlockData blockData = repository.getBlockRepository().fromReference(parentSignature);
				if (blockData == null) {
					// Try the archive
					blockData = repository.getBlockArchiveRepository().fromReference(parentSignature);
				}

				while (blockData != null && signatures.size() < numberRequested) {
					signatures.add(blockData.getSignature());

					byte[] previousSignature = blockData.getSignature();
					blockData = repository.getBlockRepository().fromReference(previousSignature);
					if (blockData == null) {
						// Try the archive
						blockData = repository.getBlockArchiveRepository().fromReference(previousSignature);
					}
				}
			} catch (DataException e) {
				LOGGER.error(String.format("Repository issue while sending V2 signatures after %s to peer %s", Base58.encode(parentSignature), peer), e);
			}
		} else {
			this.stats.getBlockSignaturesV2Stats.cacheHits.incrementAndGet();

			if (signatures.size() >= getSignaturesMessage.getNumberRequested())
				this.stats.getBlockSignaturesV2Stats.fullyFromCache.incrementAndGet();
		}

		Message signaturesMessage = new SignaturesMessage(signatures);
		signaturesMessage.setId(message.getId());
		if (!peer.sendMessage(signaturesMessage))
			peer.disconnect("failed to send signatures (v2)");
	}

	private void onNetworkHeightV2Message(Peer peer, Message message) {
		HeightV2Message heightV2Message = (HeightV2Message) message;

		if (!Settings.getInstance().isLite()) {
			// If peer is inbound and we've not updated their height
			// then this is probably their initial HEIGHT_V2 message
			// so they need a corresponding HEIGHT_V2 message from us
			if (!peer.isOutbound() && peer.getChainTipData() == null) {
				Message responseMessage = Network.getInstance().buildHeightOrChainTipInfo(peer);

				if (responseMessage == null || !peer.sendMessage(responseMessage)) {
					peer.disconnect("failed to send our chain tip info");
					return;
				}
			}
		}

		// Update peer chain tip data
		BlockSummaryData newChainTipData = new BlockSummaryData(heightV2Message.getHeight(), heightV2Message.getSignature(), heightV2Message.getMinterPublicKey(), heightV2Message.getTimestamp());
		peer.setChainTipData(newChainTipData);

		// Potentially synchronize
		Synchronizer.getInstance().requestSync();
	}

	private void onNetworkBlockSummariesV2Message(Peer peer, Message message) {
		BlockSummariesV2Message blockSummariesV2Message = (BlockSummariesV2Message) message;

		if (!Settings.getInstance().isLite()) {
			// If peer is inbound and we've not updated their height
			// then this is probably their initial BLOCK_SUMMARIES_V2 message
			// so they need a corresponding BLOCK_SUMMARIES_V2 message from us
			if (!peer.isOutbound() && peer.getChainTipData() == null) {
				Message responseMessage = Network.getInstance().buildHeightOrChainTipInfo(peer);

				if (responseMessage == null || !peer.sendMessage(responseMessage)) {
					peer.disconnect("failed to send our chain tip info");
					return;
				}
			}
		}

		if (message.hasId()) {
			/*
			 * Experimental proof-of-concept: discard messages with ID
			 * These are 'late' reply messages received after timeout has expired,
			 * having been passed upwards from Peer to Network to Controller.
			 * Hence, these are NOT simple "here's my chain tip" broadcasts from other peers.
			 */
			LOGGER.debug("Discarding late {} message with ID {} from {}", message.getType().name(), message.getId(), peer);
			return;
		}

		// Update peer chain tip data
		peer.setChainTipSummaries(blockSummariesV2Message.getBlockSummaries());

		// Potentially synchronize
		Synchronizer.getInstance().requestSync();
	}

	private void onNetworkGetAccountMessage(Peer peer, Message message) {
		GetAccountMessage getAccountMessage = (GetAccountMessage) message;
		String address = getAccountMessage.getAddress();
		this.stats.getAccountMessageStats.requests.incrementAndGet();

		try (final Repository repository = RepositoryManager.getRepository()) {
			AccountData accountData = repository.getAccountRepository().getAccount(address);

			if (accountData == null) {
				// We don't have this account
				this.stats.getAccountMessageStats.unknownAccounts.getAndIncrement();

				// Send valid, yet unexpected message type in response, so peer doesn't have to wait for timeout
				LOGGER.debug(() -> String.format("Sending 'account unknown' response to peer %s for GET_ACCOUNT request for unknown account %s", peer, address));

				// Send generic 'unknown' message as it's very short
				Message accountUnknownMessage = new GenericUnknownMessage();
				accountUnknownMessage.setId(message.getId());
				if (!peer.sendMessage(accountUnknownMessage))
					peer.disconnect("failed to send account-unknown response");
				return;
			}

			AccountMessage accountMessage = new AccountMessage(accountData);
			accountMessage.setId(message.getId());

			if (!peer.sendMessage(accountMessage)) {
				peer.disconnect("failed to send account");
			}

		} catch (DataException e) {
			LOGGER.error(String.format("Repository issue while send account %s to peer %s", address, peer), e);
		}
	}

	private void onNetworkGetAccountBalanceMessage(Peer peer, Message message) {
		GetAccountBalanceMessage getAccountBalanceMessage = (GetAccountBalanceMessage) message;
		String address = getAccountBalanceMessage.getAddress();
		long assetId = getAccountBalanceMessage.getAssetId();
		this.stats.getAccountBalanceMessageStats.requests.incrementAndGet();

		try (final Repository repository = RepositoryManager.getRepository()) {
			AccountBalanceData accountBalanceData = repository.getAccountRepository().getBalance(address, assetId);

			if (accountBalanceData == null) {
				// We don't have this account
				this.stats.getAccountBalanceMessageStats.unknownAccounts.getAndIncrement();

				// Send valid, yet unexpected message type in response, so peer doesn't have to wait for timeout
				LOGGER.debug(() -> String.format("Sending 'account unknown' response to peer %s for GET_ACCOUNT_BALANCE request for unknown account %s and asset ID %d", peer, address, assetId));

				// Send generic 'unknown' message as it's very short
				Message accountUnknownMessage = new GenericUnknownMessage();
				accountUnknownMessage.setId(message.getId());
				if (!peer.sendMessage(accountUnknownMessage))
					peer.disconnect("failed to send account-unknown response");
				return;
			}

			AccountBalanceMessage accountMessage = new AccountBalanceMessage(accountBalanceData);
			accountMessage.setId(message.getId());

			if (!peer.sendMessage(accountMessage)) {
				peer.disconnect("failed to send account balance");
			}

		} catch (DataException e) {
			LOGGER.error(String.format("Repository issue while send balance for account %s and asset ID %d to peer %s", address, assetId, peer), e);
		}
	}

	private void onNetworkGetAccountTransactionsMessage(Peer peer, Message message) {
		GetAccountTransactionsMessage getAccountTransactionsMessage = (GetAccountTransactionsMessage) message;
		String address = getAccountTransactionsMessage.getAddress();
		int limit = Math.min(getAccountTransactionsMessage.getLimit(), 100);
		int offset = getAccountTransactionsMessage.getOffset();
		this.stats.getAccountTransactionsMessageStats.requests.incrementAndGet();

		try (final Repository repository = RepositoryManager.getRepository()) {
			List<byte[]> signatures = repository.getTransactionRepository().getSignaturesMatchingCriteria(null, null, null,
					null, null, null, address, TransactionsResource.ConfirmationStatus.CONFIRMED, limit, offset, false);

			// Expand signatures to transactions
			List<TransactionData> transactions = new ArrayList<>(signatures.size());
			for (byte[] signature : signatures) {
				transactions.add(repository.getTransactionRepository().fromSignature(signature));
			}

			if (transactions == null) {
				// We don't have this account
				this.stats.getAccountTransactionsMessageStats.unknownAccounts.getAndIncrement();

				// Send valid, yet unexpected message type in response, so peer doesn't have to wait for timeout
				LOGGER.debug(() -> String.format("Sending 'account unknown' response to peer %s for GET_ACCOUNT_TRANSACTIONS request for unknown account %s", peer, address));

				// Send generic 'unknown' message as it's very short
				Message accountUnknownMessage = new GenericUnknownMessage();
				accountUnknownMessage.setId(message.getId());
				if (!peer.sendMessage(accountUnknownMessage))
					peer.disconnect("failed to send account-unknown response");
				return;
			}

			TransactionsMessage transactionsMessage = new TransactionsMessage(transactions);
			transactionsMessage.setId(message.getId());

			if (!peer.sendMessage(transactionsMessage)) {
				peer.disconnect("failed to send account transactions");
			}

		} catch (DataException e) {
			LOGGER.error(String.format("Repository issue while sending transactions for account %s %d to peer %s", address, peer), e);
		} catch (MessageException e) {
			LOGGER.error(String.format("Message serialization issue while sending transactions for account %s %d to peer %s", address, peer), e);
		}
	}

	private void onNetworkGetAccountNamesMessage(Peer peer, Message message) {
		GetAccountNamesMessage getAccountNamesMessage = (GetAccountNamesMessage) message;
		String address = getAccountNamesMessage.getAddress();
		this.stats.getAccountNamesMessageStats.requests.incrementAndGet();

		try (final Repository repository = RepositoryManager.getRepository()) {
			List<NameData> namesDataList = repository.getNameRepository().getNamesByOwner(address);

			if (namesDataList == null) {
				// We don't have this account
				this.stats.getAccountNamesMessageStats.unknownAccounts.getAndIncrement();

				// Send valid, yet unexpected message type in response, so peer doesn't have to wait for timeout
				LOGGER.debug(() -> String.format("Sending 'account unknown' response to peer %s for GET_ACCOUNT_NAMES request for unknown account %s", peer, address));

				// Send generic 'unknown' message as it's very short
				Message accountUnknownMessage = new GenericUnknownMessage();
				accountUnknownMessage.setId(message.getId());
				if (!peer.sendMessage(accountUnknownMessage))
					peer.disconnect("failed to send account-unknown response");
				return;
			}

			NamesMessage namesMessage = new NamesMessage(namesDataList);
			namesMessage.setId(message.getId());

			if (!peer.sendMessage(namesMessage)) {
				peer.disconnect("failed to send account names");
			}

		} catch (DataException e) {
			LOGGER.error(String.format("Repository issue while send names for account %s to peer %s", address, peer), e);
		}
	}

	private void onNetworkGetNameMessage(Peer peer, Message message) {
		GetNameMessage getNameMessage = (GetNameMessage) message;
		String name = getNameMessage.getName();
		this.stats.getNameMessageStats.requests.incrementAndGet();

		try (final Repository repository = RepositoryManager.getRepository()) {
			NameData nameData = repository.getNameRepository().fromName(name);

			if (nameData == null) {
				// We don't have this account
				this.stats.getNameMessageStats.unknownAccounts.getAndIncrement();

				// Send valid, yet unexpected message type in response, so peer doesn't have to wait for timeout
				LOGGER.debug(() -> String.format("Sending 'name unknown' response to peer %s for GET_NAME request for unknown name %s", peer, name));

				// Send generic 'unknown' message as it's very short
				Message nameUnknownMessage = new GenericUnknownMessage();
				nameUnknownMessage.setId(message.getId());
				if (!peer.sendMessage(nameUnknownMessage))
					peer.disconnect("failed to send name-unknown response");
				return;
			}

			NamesMessage namesMessage = new NamesMessage(Arrays.asList(nameData));
			namesMessage.setId(message.getId());

			if (!peer.sendMessage(namesMessage)) {
				peer.disconnect("failed to send name data");
			}

		} catch (DataException e) {
			LOGGER.error(String.format("Repository issue while send name %s to peer %s", name, peer), e);
		}
	}


	// Utilities

	/** Returns a list of peers that are not misbehaving, and have a recent block. */
	public List<Peer> getRecentBehavingPeers() {
		final Long minLatestBlockTimestamp = getMinimumLatestBlockTimestamp();
		if (minLatestBlockTimestamp == null)
			return null;

		// Needs a mutable copy of the unmodifiableList
		List<Peer> peers = new ArrayList<>(Network.getInstance().getImmutableHandshakedPeers());

		// Filter out unsuitable peers
		Iterator<Peer> iterator = peers.iterator();
		while (iterator.hasNext()) {
			final Peer peer = iterator.next();

			final PeerData peerData = peer.getPeerData();
			if (peerData == null) {
				iterator.remove();
				continue;
			}

			// Disregard peers that have "misbehaved" recently
			if (hasMisbehaved.test(peer)) {
				iterator.remove();
				continue;
			}

			BlockSummaryData peerChainTipData = peer.getChainTipData();
			if (peerChainTipData == null) {
				iterator.remove();
				continue;
			}

			// Disregard peers that don't have a recent block
			if (peerChainTipData.getTimestamp() == null || peerChainTipData.getTimestamp() < minLatestBlockTimestamp) {
				iterator.remove();
            }
		}

		return peers;
	}

	/**
	 * Returns whether we think our node has up-to-date blockchain based on our info about other peers.
	 * @param minLatestBlockTimestamp - the minimum block timestamp to be considered recent
	 * @return boolean - whether our node's blockchain is up to date or not
	 */
	public boolean isUpToDate(Long minLatestBlockTimestamp) {
		if (Settings.getInstance().isLite()) {
			// Lite nodes are always "up to date"
			return true;
		}

		// Do we even have a vaguely recent block?
		if (minLatestBlockTimestamp == null)
			return false;

		final BlockData latestBlockData = getChainTip();
		if (latestBlockData == null || latestBlockData.getTimestamp() < minLatestBlockTimestamp)
			return false;

		if (Settings.getInstance().isSingleNodeTestnet())
			// Single node testnets won't have peers, so we can assume up to date from this point
			return true;

		// Needs a mutable copy of the unmodifiableList
		List<Peer> peers = new ArrayList<>(Network.getInstance().getImmutableHandshakedPeers());
		if (peers == null)
			return false;

		// Disregard peers that have "misbehaved" recently
		peers.removeIf(hasMisbehaved);

		// Disregard peers that don't have a recent block
		peers.removeIf(hasNoRecentBlock);

		// Check we have enough peers to potentially synchronize/mint
		if (peers.size() < Settings.getInstance().getMinBlockchainPeers())
			return false;

		// If we don't have any peers left then can't synchronize, therefore consider ourself not up to date
		return !peers.isEmpty();
	}

	/**
	 * Returns whether we think our node has up-to-date blockchain based on our info about other peers.
	 * Uses the default minLatestBlockTimestamp value.
	 * @return boolean - whether our node's blockchain is up to date or not
	 */
	public boolean isUpToDate() {
		final Long minLatestBlockTimestamp = getMinimumLatestBlockTimestamp();
		return this.isUpToDate(minLatestBlockTimestamp);
	}

	/** Returns minimum block timestamp for block to be considered 'recent', or <tt>null</tt> if NTP not synced. */
	public static Long getMinimumLatestBlockTimestamp() {
		Long now = NTP.getTime();
		if (now == null)
			return null;

		int height = getInstance().getChainHeight();
		if (height == 0)
			return null;

		long offset = 0;
		for (int ai = 0; height >= 1 && ai < MAX_BLOCKCHAIN_TIP_AGE; ++ai, --height) {
			BlockTimingByHeight blockTiming = BlockChain.getInstance().getBlockTimingByHeight(height);
			offset += blockTiming.target + blockTiming.deviation;
		}

		return now - offset;
	}

	public StatsSnapshot getStatsSnapshot() {
		return this.stats;
	}

	public void onRNSNetworkMessage(RNSPeer peer, Message message) {
		LOGGER.trace(() -> String.format("Processing %s message from %s", message.getType().name(), peer));

		// Ordered by message type value
		switch (message.getType()) {
			case GET_BLOCK:
				onRNSNetworkGetBlockMessage(peer, message);
				break;
			
			case GET_BLOCK_SUMMARIES:
				onRNSNetworkGetBlockSummariesMessage(peer, message);
				break;
			
			case GET_SIGNATURES_V2:
				onRNSNetworkGetSignaturesV2Message(peer, message);
				break;
			
			case HEIGHT_V2:
				onRNSNetworkHeightV2Message(peer, message);
				break;
			
			case BLOCK_SUMMARIES_V2:
				onRNSNetworkBlockSummariesV2Message(peer, message);
				break;
			
			case GET_TRANSACTION:
				RNSTransactionImporter.getInstance().onNetworkGetTransactionMessage(peer, message);
				break;
			
			case TRANSACTION:
				RNSTransactionImporter.getInstance().onNetworkTransactionMessage(peer, message);
				break;
			
			case GET_UNCONFIRMED_TRANSACTIONS:
				RNSTransactionImporter.getInstance().onNetworkGetUnconfirmedTransactionsMessage(peer, message);
				break;
			
			case TRANSACTION_SIGNATURES:
				RNSTransactionImporter.getInstance().onNetworkTransactionSignaturesMessage(peer, message);
				break;
			
			//case GET_ONLINE_ACCOUNTS_V3:
			//	OnlineAccountsManager.getInstance().onNetworkGetOnlineAccountsV3Message(peer, message);
			//	break;
			//
			//case ONLINE_ACCOUNTS_V3:
			//	OnlineAccountsManager.getInstance().onNetworkOnlineAccountsV3Message(peer, message);
			//	break;
			
			//// TODO: Compiles but much of the Manager details need to be rethought for Reticulum
			//case GET_ARBITRARY_DATA:
			//	// Not currently supported
			//	break;
			////
			//case ARBITRARY_DATA_FILE_LIST:
			//	RNSArbitraryDataFileListManager.getInstance().onNetworkArbitraryDataFileListMessage(peer, message);
			//	break;
			//
			//case GET_ARBITRARY_DATA_FILE:
			//	RNSArbitraryDataFileManager.getInstance().onNetworkGetArbitraryDataFileMessage(peer, message);
			//	break;
			//
			//case GET_ARBITRARY_DATA_FILE_LIST:
			//	RNSArbitraryDataFileListManager.getInstance().onNetworkGetArbitraryDataFileListMessage(peer, message);
			//	break;
			//
			case ARBITRARY_SIGNATURES:
				// Not currently supported
				break;
			
			case GET_ARBITRARY_METADATA:
				RNSArbitraryMetadataManager.getInstance().onNetworkGetArbitraryMetadataMessage(peer, message);
				break;
			
			case ARBITRARY_METADATA:
				RNSArbitraryMetadataManager.getInstance().onNetworkArbitraryMetadataMessage(peer, message);
				break;
			
			case GET_TRADE_PRESENCES:
				RNSTradeBot.getInstance().onGetTradePresencesMessage(peer, message);
				break;
			
			case TRADE_PRESENCES:
				RNSTradeBot.getInstance().onTradePresencesMessage(peer, message);
				break;
			
			case GET_ACCOUNT:
				onRNSNetworkGetAccountMessage(peer, message);
				break;
			
			case GET_ACCOUNT_BALANCE:
				onRNSNetworkGetAccountBalanceMessage(peer, message);
				break;
			
			case GET_ACCOUNT_TRANSACTIONS:
				onRNSNetworkGetAccountTransactionsMessage(peer, message);
				break;
			
			case GET_ACCOUNT_NAMES:
				onRNSNetworkGetAccountNamesMessage(peer, message);
				break;
			
			case GET_NAME:
				onRNSNetworkGetNameMessage(peer, message);
				break;

			default:
				LOGGER.debug(() -> String.format("Unhandled %s message [ID %d] from peer %s", message.getType().name(), message.getId(), peer));
				break;
		}
	}

	private void onRNSNetworkGetBlockMessage(RNSPeer peer, Message message) {
		GetBlockMessage getBlockMessage = (GetBlockMessage) message;
		byte[] signature = getBlockMessage.getSignature();
		this.stats.getBlockMessageStats.requests.incrementAndGet();

		ByteArray signatureAsByteArray = ByteArray.wrap(signature);

		CachedBlockMessage cachedBlockMessage = this.blockMessageCache.get(signatureAsByteArray);
		int blockCacheSize = Settings.getInstance().getBlockCacheSize();

		// Check cached latest block message
		if (cachedBlockMessage != null) {
			this.stats.getBlockMessageStats.cacheHits.incrementAndGet();

			// We need to duplicate it to prevent multiple threads setting ID on the same message
			CachedBlockMessage clonedBlockMessage = Message.cloneWithNewId(cachedBlockMessage, message.getId());

			//if (!peer.sendMessage(clonedBlockMessage))
			//	peer.disconnect("failed to send block");
			peer.sendMessage(clonedBlockMessage);

			return;
		}

		try (final Repository repository = RepositoryManager.getRepository()) {
			BlockData blockData = repository.getBlockRepository().fromSignature(signature);

			if (blockData != null) {
				if (PruneManager.getInstance().isBlockPruned(blockData.getHeight())) {
					// If this is a pruned block, we likely only have partial data, so best not to sent it
					blockData = null;
				}
			}

			// If we have no block data, we should check the archive in case it's there
			if (blockData == null) {
				if (Settings.getInstance().isArchiveEnabled()) {
					Triple<byte[], Integer, Integer> serializedBlock = BlockArchiveReader.getInstance().fetchSerializedBlockBytesForSignature(signature, true, repository);
					if (serializedBlock != null) {
						byte[] bytes = serializedBlock.getA();
						Integer serializationVersion = serializedBlock.getB();

						Message blockMessage;
						switch (serializationVersion) {
							case 1:
								blockMessage = new CachedBlockMessage(bytes);
								break;

							case 2:
								blockMessage = new CachedBlockV2Message(bytes);
								break;

							default:
								return;
						}
						blockMessage.setId(message.getId());

						// This call also causes the other needed data to be pulled in from repository
						//if (!peer.sendMessage(blockMessage)) {
						//	peer.disconnect("failed to send block");
						//	// Don't fall-through to caching because failure to send might be from failure to build message
						//	return;
						//}
						peer.sendMessage(blockMessage);

						// Sent successfully from archive, so nothing more to do
						return;
					}
				}
			}

			if (blockData == null) {
				// We don't have this block
				this.stats.getBlockMessageStats.unknownBlocks.getAndIncrement();

				// Send valid, yet unexpected message type in response, so peer's synchronizer doesn't have to wait for timeout
				LOGGER.debug(() -> String.format("Sending 'block unknown' response to peer %s for GET_BLOCK request for unknown block %s", peer, Base58.encode(signature)));

				// Send generic 'unknown' message as it's very short
				//Message blockUnknownMessage = peer.getPeersVersion() >= GenericUnknownMessage.MINIMUM_PEER_VERSION
				//		? new GenericUnknownMessage()
				//		: new BlockSummariesMessage(Collections.emptyList());
				Message blockUnknownMessage = new GenericUnknownMessage();
				blockUnknownMessage.setId(message.getId());
				//if (!peer.sendMessage(blockUnknownMessage))
				//	peer.disconnect("failed to send block-unknown response");
				peer.sendMessage(blockUnknownMessage);
				return;
			}

			Block block = new Block(repository, blockData);

			//// V2 support
			//if (peer.getPeersVersion() >= BlockV2Message.MIN_PEER_VERSION) {
			//	Message blockMessage = new BlockV2Message(block);
			//	blockMessage.setId(message.getId());
			//	if (!peer.sendMessage(blockMessage)) {
			//		peer.disconnect("failed to send block");
			//		// Don't fall-through to caching because failure to send might be from failure to build message
			//		return;
			//	}
			//	return;
			//}

			CachedBlockMessage blockMessage = new CachedBlockMessage(block);
			blockMessage.setId(message.getId());

			//if (!peer.sendMessage(blockMessage)) {
			//	peer.disconnect("failed to send block");
			//	// Don't fall-through to caching because failure to send might be from failure to build message
			//	return;
			//}
			peer.sendMessage(blockMessage);

			// If request is for a recent block, cache it
			if (getChainHeight() - blockData.getHeight() <= blockCacheSize) {
				this.stats.getBlockMessageStats.cacheFills.incrementAndGet();

				this.blockMessageCache.put(ByteArray.wrap(blockData.getSignature()), blockMessage);
			}
		} catch (DataException e) {
			LOGGER.error(String.format("Repository issue while sending block %s to peer %s", Base58.encode(signature), peer), e);
		} catch (TransformationException e) {
			LOGGER.error(String.format("Serialization issue while sending block %s to peer %s", Base58.encode(signature), peer), e);
		}
	}

	private void onRNSNetworkGetBlockSummariesMessage(RNSPeer peer, Message message) {
		GetBlockSummariesMessage getBlockSummariesMessage = (GetBlockSummariesMessage) message;
		final byte[] parentSignature = getBlockSummariesMessage.getParentSignature();
		this.stats.getBlockSummariesStats.requests.incrementAndGet();

		// If peer's parent signature matches our latest block signature
		// then we have no blocks after that and can short-circuit with an empty response
		BlockData chainTip = getChainTip();
		if (chainTip != null && Arrays.equals(parentSignature, chainTip.getSignature())) {
			//Message blockSummariesMessage = peer.getPeersVersion() >= BlockSummariesV2Message.MINIMUM_PEER_VERSION
			//		? new BlockSummariesV2Message(Collections.emptyList())
			//		: new BlockSummariesMessage(Collections.emptyList());
			Message blockSummariesMessage = new BlockSummariesV2Message(Collections.emptyList());

			blockSummariesMessage.setId(message.getId());

			//if (!peer.sendMessage(blockSummariesMessage))
			//	peer.disconnect("failed to send block summaries");
			peer.sendMessage(blockSummariesMessage);

			return;
		}

		List<BlockSummaryData> blockSummaries = new ArrayList<>();

		// Attempt to serve from our cache of latest blocks
		synchronized (this.latestBlocks) {
			blockSummaries = this.latestBlocks.stream()
					.dropWhile(cachedBlockData -> !Arrays.equals(cachedBlockData.getReference(), parentSignature))
					.map(BlockSummaryData::new)
					.collect(Collectors.toList());
		}

		if (blockSummaries.isEmpty()) {
			try (final Repository repository = RepositoryManager.getRepository()) {
				int numberRequested = Math.min(Network.MAX_BLOCK_SUMMARIES_PER_REPLY, getBlockSummariesMessage.getNumberRequested());

				BlockData blockData = repository.getBlockRepository().fromReference(parentSignature);
				if (blockData == null) {
					// Try the archive
					blockData = repository.getBlockArchiveRepository().fromReference(parentSignature);
				}

				if (blockData != null) {
					if (PruneManager.getInstance().isBlockPruned(blockData.getHeight())) {
						// If this request contains a pruned block, we likely only have partial data, so best not to sent anything
						// We always prune from the oldest first, so it's fine to just check the first block requested
						blockData = null;
					}
				}

				while (blockData != null && blockSummaries.size() < numberRequested) {
					BlockSummaryData blockSummary = new BlockSummaryData(blockData);
					blockSummaries.add(blockSummary);

					byte[] previousSignature = blockData.getSignature();
					blockData = repository.getBlockRepository().fromReference(previousSignature);
					if (blockData == null) {
						// Try the archive
						blockData = repository.getBlockArchiveRepository().fromReference(previousSignature);
					}
				}
			} catch (DataException e) {
				LOGGER.error(String.format("Repository issue while sending block summaries after %s to peer %s", Base58.encode(parentSignature), peer), e);
			}
		} else {
			this.stats.getBlockSummariesStats.cacheHits.incrementAndGet();

			if (blockSummaries.size() >= getBlockSummariesMessage.getNumberRequested())
				this.stats.getBlockSummariesStats.fullyFromCache.incrementAndGet();
		}

		//Message blockSummariesMessage = peer.getPeersVersion() >= BlockSummariesV2Message.MINIMUM_PEER_VERSION
		//		? new BlockSummariesV2Message(blockSummaries)
		//		: new BlockSummariesMessage(blockSummaries);
		Message blockSummariesMessage = new BlockSummariesV2Message(blockSummaries);
		blockSummariesMessage.setId(message.getId());
		//if (!peer.sendMessage(blockSummariesMessage))
		//	peer.disconnect("failed to send block summaries");
		peer.sendMessage(blockSummariesMessage);
	}

	private void onRNSNetworkGetSignaturesV2Message(RNSPeer peer, Message message) {
		GetSignaturesV2Message getSignaturesMessage = (GetSignaturesV2Message) message;
		final byte[] parentSignature = getSignaturesMessage.getParentSignature();
		this.stats.getBlockSignaturesV2Stats.requests.incrementAndGet();

		// If peer's parent signature matches our latest block signature
		// then we can short-circuit with an empty response
		BlockData chainTip = getChainTip();
		if (chainTip != null && Arrays.equals(parentSignature, chainTip.getSignature())) {
			Message signaturesMessage = new SignaturesMessage(Collections.emptyList());
			signaturesMessage.setId(message.getId());
			//if (!peer.sendMessage(signaturesMessage))
			//	peer.disconnect("failed to send signatures (v2)");
			peer.sendMessage(signaturesMessage);

			return;
		}

		List<byte[]> signatures = new ArrayList<>();

		// Attempt to serve from our cache of latest blocks
		synchronized (this.latestBlocks) {
			signatures = this.latestBlocks.stream()
					.dropWhile(cachedBlockData -> !Arrays.equals(cachedBlockData.getReference(), parentSignature))
					.map(BlockData::getSignature)
					.collect(Collectors.toList());
		}

		if (signatures.isEmpty()) {
			try (final Repository repository = RepositoryManager.getRepository()) {
				int numberRequested = getSignaturesMessage.getNumberRequested();
				BlockData blockData = repository.getBlockRepository().fromReference(parentSignature);
				if (blockData == null) {
					// Try the archive
					blockData = repository.getBlockArchiveRepository().fromReference(parentSignature);
				}

				while (blockData != null && signatures.size() < numberRequested) {
					signatures.add(blockData.getSignature());

					byte[] previousSignature = blockData.getSignature();
					blockData = repository.getBlockRepository().fromReference(previousSignature);
					if (blockData == null) {
						// Try the archive
						blockData = repository.getBlockArchiveRepository().fromReference(previousSignature);
					}
				}
			} catch (DataException e) {
				LOGGER.error(String.format("Repository issue while sending V2 signatures after %s to peer %s", Base58.encode(parentSignature), peer), e);
			}
		} else {
			this.stats.getBlockSignaturesV2Stats.cacheHits.incrementAndGet();

			if (signatures.size() >= getSignaturesMessage.getNumberRequested())
				this.stats.getBlockSignaturesV2Stats.fullyFromCache.incrementAndGet();
		}

		Message signaturesMessage = new SignaturesMessage(signatures);
		signaturesMessage.setId(message.getId());
		//if (!peer.sendMessage(signaturesMessage))
		//	peer.disconnect("failed to send signatures (v2)");
		peer.sendMessage(signaturesMessage);
	}

	private void onRNSNetworkHeightV2Message(RNSPeer peer, Message message) {
		HeightV2Message heightV2Message = (HeightV2Message) message;

		if (!Settings.getInstance().isLite()) {
			// If peer is inbound and we've not updated their height
			// then this is probably their initial HEIGHT_V2 message
			// so they need a corresponding HEIGHT_V2 message from us
			if (!peer.getIsInitiator() && peer.getChainTipData() == null) {
				Message responseMessage = RNSNetwork.getInstance().buildHeightOrChainTipInfo(peer);
				peer.sendMessage(responseMessage);
			}
		}

		// Update peer chain tip data
		BlockSummaryData newChainTipData = new BlockSummaryData(heightV2Message.getHeight(), heightV2Message.getSignature(), heightV2Message.getMinterPublicKey(), heightV2Message.getTimestamp());
		peer.setChainTipData(newChainTipData);

		// Potentially synchronize
		Synchronizer.getInstance().requestSync();
	}

	private void onRNSNetworkBlockSummariesV2Message(RNSPeer peer, Message message) {
		BlockSummariesV2Message blockSummariesV2Message = (BlockSummariesV2Message) message;

		if (!Settings.getInstance().isLite()) {
			//// If peer is inbound and we've not updated their height
			//// then this is probably their initial BLOCK_SUMMARIES_V2 message
			//// so they need a corresponding BLOCK_SUMMARIES_V2 message from us
			if (!peer.getIsInitiator() && peer.getChainTipData() == null) {
				Message responseMessage = RNSNetwork.getInstance().buildHeightOrChainTipInfo(peer);
				peer.sendMessage(responseMessage);
			}
		}

		if (message.hasId()) {
			/*
			 * Experimental proof-of-concept: discard messages with ID
			 * These are 'late' reply messages received after timeout has expired,
			 * having been passed upwards from Peer to Network to Controller.
			 * Hence, these are NOT simple "here's my chain tip" broadcasts from other peers.
			 */
			LOGGER.debug("Discarding late {} message with ID {} from {}", message.getType().name(), message.getId(), peer);
			return;
		}

		// Update peer chain tip data
		peer.setChainTipSummaries(blockSummariesV2Message.getBlockSummaries());

		// Potentially synchronize
		Synchronizer.getInstance().requestSync();
	}

	// ************

	private void onRNSNetworkGetAccountMessage(RNSPeer peer, Message message) {
		GetAccountMessage getAccountMessage = (GetAccountMessage) message;
		String address = getAccountMessage.getAddress();
		this.stats.getAccountMessageStats.requests.incrementAndGet();

		try (final Repository repository = RepositoryManager.getRepository()) {
			AccountData accountData = repository.getAccountRepository().getAccount(address);

			if (accountData == null) {
				// We don't have this account
				this.stats.getAccountMessageStats.unknownAccounts.getAndIncrement();

				// Send valid, yet unexpected message type in response, so peer doesn't have to wait for timeout
				LOGGER.debug(() -> String.format("Sending 'account unknown' response to peer %s for GET_ACCOUNT request for unknown account %s", peer, address));

				// Send generic 'unknown' message as it's very short
				Message accountUnknownMessage = new GenericUnknownMessage();
				accountUnknownMessage.setId(message.getId());
				peer.sendMessage(accountUnknownMessage);
				return;
			}

			AccountMessage accountMessage = new AccountMessage(accountData);
			accountMessage.setId(message.getId());

			// handle in timeout callback instead
			//if (!peer.sendMessage(accountMessage)) {
			//	peer.disconnect("failed to send account");
			//}
			peer.sendMessage(accountMessage);

		} catch (DataException e) {
			LOGGER.error(String.format("Repository issue while send account %s to peer %s", address, peer), e);
		}
	}

	private void onRNSNetworkGetAccountBalanceMessage(RNSPeer peer, Message message) {
		GetAccountBalanceMessage getAccountBalanceMessage = (GetAccountBalanceMessage) message;
		String address = getAccountBalanceMessage.getAddress();
		long assetId = getAccountBalanceMessage.getAssetId();
		this.stats.getAccountBalanceMessageStats.requests.incrementAndGet();

		try (final Repository repository = RepositoryManager.getRepository()) {
			AccountBalanceData accountBalanceData = repository.getAccountRepository().getBalance(address, assetId);

			if (accountBalanceData == null) {
				// We don't have this account
				this.stats.getAccountBalanceMessageStats.unknownAccounts.getAndIncrement();

				// Send valid, yet unexpected message type in response, so peer doesn't have to wait for timeout
				LOGGER.debug(() -> String.format("Sending 'account unknown' response to peer %s for GET_ACCOUNT_BALANCE request for unknown account %s and asset ID %d", peer, address, assetId));

				// Send generic 'unknown' message as it's very short
				Message accountUnknownMessage = new GenericUnknownMessage();
				accountUnknownMessage.setId(message.getId());
				peer.sendMessage(accountUnknownMessage);
				return;
			}

			AccountBalanceMessage accountMessage = new AccountBalanceMessage(accountBalanceData);
			accountMessage.setId(message.getId());

			// handle in timeout callback instead
			//if (!peer.sendMessage(accountMessage)) {
			//	peer.disconnect("failed to send account balance");
			//}
			peer.sendMessage(accountMessage);

		} catch (DataException e) {
			LOGGER.error(String.format("Repository issue while send balance for account %s and asset ID %d to peer %s", address, assetId, peer), e);
		}
	}

	private void onRNSNetworkGetAccountTransactionsMessage(RNSPeer peer, Message message) {
		GetAccountTransactionsMessage getAccountTransactionsMessage = (GetAccountTransactionsMessage) message;
		String address = getAccountTransactionsMessage.getAddress();
		int limit = Math.min(getAccountTransactionsMessage.getLimit(), 100);
		int offset = getAccountTransactionsMessage.getOffset();
		this.stats.getAccountTransactionsMessageStats.requests.incrementAndGet();

		try (final Repository repository = RepositoryManager.getRepository()) {
			List<byte[]> signatures = repository.getTransactionRepository().getSignaturesMatchingCriteria(null, null, null,
					null, null, null, address, TransactionsResource.ConfirmationStatus.CONFIRMED, limit, offset, false);

			// Expand signatures to transactions
			List<TransactionData> transactions = new ArrayList<>(signatures.size());
			for (byte[] signature : signatures) {
				transactions.add(repository.getTransactionRepository().fromSignature(signature));
			}

			if (transactions == null) {
				// We don't have this account
				this.stats.getAccountTransactionsMessageStats.unknownAccounts.getAndIncrement();

				// Send valid, yet unexpected message type in response, so peer doesn't have to wait for timeout
				LOGGER.debug(() -> String.format("Sending 'account unknown' response to peer %s for GET_ACCOUNT_TRANSACTIONS request for unknown account %s", peer, address));

				// Send generic 'unknown' message as it's very short
				Message accountUnknownMessage = new GenericUnknownMessage();
				accountUnknownMessage.setId(message.getId());
				peer.sendMessage(accountUnknownMessage);
				return;
			}

			TransactionsMessage transactionsMessage = new TransactionsMessage(transactions);
			transactionsMessage.setId(message.getId());

			// handle in timeout callback instead
			//if (!peer.sendMessage(transactionsMessage)) {
			//	peer.disconnect("failed to send account transactions");
			//}
			peer.sendMessage(transactionsMessage);

		} catch (DataException e) {
			LOGGER.error(String.format("Repository issue while sending transactions for account %s %d to peer %s", address, peer), e);
		} catch (MessageException e) {
			LOGGER.error(String.format("Message serialization issue while sending transactions for account %s %d to peer %s", address, peer), e);
		}
	}

	private void onRNSNetworkGetAccountNamesMessage(RNSPeer peer, Message message) {
		GetAccountNamesMessage getAccountNamesMessage = (GetAccountNamesMessage) message;
		String address = getAccountNamesMessage.getAddress();
		this.stats.getAccountNamesMessageStats.requests.incrementAndGet();

		try (final Repository repository = RepositoryManager.getRepository()) {
			List<NameData> namesDataList = repository.getNameRepository().getNamesByOwner(address);

			if (namesDataList == null) {
				// We don't have this account
				this.stats.getAccountNamesMessageStats.unknownAccounts.getAndIncrement();

				// Send valid, yet unexpected message type in response, so peer doesn't have to wait for timeout
				LOGGER.debug(() -> String.format("Sending 'account unknown' response to peer %s for GET_ACCOUNT_NAMES request for unknown account %s", peer, address));

				// Send generic 'unknown' message as it's very short
				Message accountUnknownMessage = new GenericUnknownMessage();
				accountUnknownMessage.setId(message.getId());
				peer.sendMessage(accountUnknownMessage);
				return;
			}

			NamesMessage namesMessage = new NamesMessage(namesDataList);
			namesMessage.setId(message.getId());

			// handle in timeout callback instead
			//if (!peer.sendMessage(namesMessage)) {
			//	peer.disconnect("failed to send account names");
			//}
			peer.sendMessage(namesMessage);

		} catch (DataException e) {
			LOGGER.error(String.format("Repository issue while send names for account %s to peer %s", address, peer), e);
		}
	}

	private void onRNSNetworkGetNameMessage(RNSPeer peer, Message message) {
		GetNameMessage getNameMessage = (GetNameMessage) message;
		String name = getNameMessage.getName();
		this.stats.getNameMessageStats.requests.incrementAndGet();

		try (final Repository repository = RepositoryManager.getRepository()) {
			NameData nameData = repository.getNameRepository().fromName(name);

			if (nameData == null) {
				// We don't have this account
				this.stats.getNameMessageStats.unknownAccounts.getAndIncrement();

				// Send valid, yet unexpected message type in response, so peer doesn't have to wait for timeout
				LOGGER.debug(() -> String.format("Sending 'name unknown' response to peer %s for GET_NAME request for unknown name %s", peer, name));

				// Send generic 'unknown' message as it's very short
				Message nameUnknownMessage = new GenericUnknownMessage();
				nameUnknownMessage.setId(message.getId());
				if (!peer.sendMessage(nameUnknownMessage))
				peer.sendMessage(nameUnknownMessage);
				return;
			}

			NamesMessage namesMessage = new NamesMessage(Arrays.asList(nameData));
			namesMessage.setId(message.getId());

			// handle in timeout callback instead
			//if (!peer.sendMessage(namesMessage)) {
			//	peer.disconnect("failed to send name data");
			//}
			peer.sendMessage(namesMessage);

		} catch (DataException e) {
			LOGGER.error(String.format("Repository issue while send name %s to peer %s", name, peer), e);
		}
	}

	/**
	 * Returns whether we think our node has up-to-date blockchain based on our info about other peers.
	 * @param minLatestBlockTimestamp - the minimum block timestamp to be considered recent
	 * @return boolean - whether our node's blockchain is up to date or not
	 */
	public boolean isUpToDateRNS(Long minLatestBlockTimestamp) {
		if (Settings.getInstance().isLite()) {
			// Lite nodes are always "up to date"
			return true;
		}

		// Do we even have a vaguely recent block?
		if (minLatestBlockTimestamp == null)
			return false;

		final BlockData latestBlockData = getChainTip();
		if (latestBlockData == null || latestBlockData.getTimestamp() < minLatestBlockTimestamp)
			return false;

		if (Settings.getInstance().isSingleNodeTestnet())
			// Single node testnets won't have peers, so we can assume up to date from this point
			return true;

		// Needs a mutable copy of the unmodifiableList
		List<RNSPeer> peers = new ArrayList<>(RNSNetwork.getInstance().getActiveImmutableLinkedPeers());
		if (peers == null)
			return false;

		//// Disregard peers that have "misbehaved" recently
		//peers.removeIf(hasMisbehaved);
		//
		//// Disregard peers that don't have a recent block
		//peers.removeIf(hasNoRecentBlock);

		// Check we have enough peers to potentially synchronize/mint
		if (peers.size() < Settings.getInstance().getReticulumMinDesiredPeers())
			return false;

		// If we don't have any peers left then can't synchronize, therefore consider ourself not up to date
		return !peers.isEmpty();
	}

	/**
	 * Returns whether we think our node has up-to-date blockchain based on our info about other peers.
	 * Uses the default minLatestBlockTimestamp value.
	 * @return boolean - whether our node's blockchain is up to date or not
	 */
	public boolean isUpToDateRNS() {
		final Long minLatestBlockTimestamp = getMinimumLatestBlockTimestamp();
		return this.isUpToDate(minLatestBlockTimestamp);
	}
}
