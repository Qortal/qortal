package org.qortal.controller.arbitrary;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.api.resource.TransactionsResource.ConfirmationStatus;
import org.qortal.arbitrary.ArbitraryDataFile;
import org.qortal.arbitrary.ArbitraryDataResource;
import org.qortal.arbitrary.metadata.ArbitraryDataTransactionMetadata;
import org.qortal.arbitrary.misc.Service;
import org.qortal.controller.Controller;
import org.qortal.data.transaction.ArbitraryTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.event.DataMonitorEvent;
import org.qortal.event.EventBus;
import org.qortal.network.Network;
import org.qortal.network.Peer;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.settings.Settings;
import org.qortal.transaction.ArbitraryTransaction;
import org.qortal.transaction.Transaction.TransactionType;
import org.qortal.utils.ArbitraryTransactionUtils;
import org.qortal.utils.Base58;
import org.qortal.utils.ListUtils;
import org.qortal.utils.NTP;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

public class ArbitraryDataManager extends Thread {

	private static final Logger LOGGER = LogManager.getLogger(ArbitraryDataManager.class);
	private static final List<TransactionType> ARBITRARY_TX_TYPE = Arrays.asList(TransactionType.ARBITRARY);

	/** Difficulty (leading zero bits) used in arbitrary data transactions
	 * Set here so that it can be more easily reduced when running unit tests */
	private int powDifficulty = 14; // Must not be final, as unit tests need to reduce this value

	/** Request timeout when transferring arbitrary data */
	public static final long ARBITRARY_REQUEST_TIMEOUT = 24 * 1000L; // ms

	/** Maximum time to hold information about an in-progress relay */
	public static final long ARBITRARY_RELAY_TIMEOUT = 120 * 1000L; // ms

	/** Maximum time to hold direct peer connection information */
	public static final long ARBITRARY_DIRECT_CONNECTION_INFO_TIMEOUT = 2 * 60 * 1000L; // ms

	/** Maximum time to hold information about recent data requests that we can fulfil */
	public static final long ARBITRARY_RECENT_DATA_REQUESTS_TIMEOUT = 2 * 60 * 1000L; // ms

	/** Maximum number of hops that an arbitrary signatures request is allowed to make */
	private static int ARBITRARY_SIGNATURES_REQUEST_MAX_HOPS = 3;

	private long lastMetadataFetchTime = 0L;
	private static long METADATA_FETCH_INTERVAL = 5 * 60 * 1000L;

	private long lastDataFetchTime = 0L;
	private static long DATA_FETCH_INTERVAL = 1 * 60 * 1000L;

	private static ArbitraryDataManager instance;
	private final Object peerDataLock = new Object();

	private volatile boolean isStopping = false;

	/**
	 * Map to keep track of cached arbitrary transaction resources.
	 * When an item is present in this list with a timestamp in the future, we won't invalidate
	 * its cache when serving that data. This reduces the amount of database lookups that are needed.
	 */
	private Map<String, Long> arbitraryDataCachedResources = Collections.synchronizedMap(new HashMap<>());

	/**
	 * The amount of time to cache a data resource before it is invalidated
	 */
	private static long ARBITRARY_DATA_CACHE_TIMEOUT = 60 * 60 * 1000L; // 60 minutes



	private ArbitraryDataManager() {
	}

	public static ArbitraryDataManager getInstance() {
		if (instance == null)
			instance = new ArbitraryDataManager();

		return instance;
	}

	@Override
	public void run() {
		Thread.currentThread().setName("Arbitrary Data Manager");
		Thread.currentThread().setPriority(NORM_PRIORITY);

		// Create data directory in case it doesn't exist yet
		this.createDataDirectory();

		try {
			// Wait for node to finish starting up and making connections
			Thread.sleep(2 * 60 * 1000L);

			while (!isStopping) {
				Thread.sleep(2000);

				// Don't run if QDN is disabled
				if (!Settings.getInstance().isQdnEnabled()) {
					Thread.sleep(60 * 60 * 1000L);
					continue;
				}

				Long now = NTP.getTime();
				if (now == null) {
					continue;
				}

				// Needs a mutable copy of the unmodifiableList
				List<Peer> peers = new ArrayList<>(Network.getInstance().getImmutableHandshakedPeers());

				// Disregard peers that have "misbehaved" recently
				peers.removeIf(Controller.hasMisbehaved);

				// Don't fetch data if we don't have enough up-to-date peers
				if (peers.size() < Settings.getInstance().getMinBlockchainPeers()) {
					continue;
				}

				// Fetch metadata
				if (NTP.getTime() - lastMetadataFetchTime >= METADATA_FETCH_INTERVAL) {
					this.fetchAllMetadata();
					lastMetadataFetchTime = NTP.getTime();
				}

				// Check if we need to fetch any data
				if (NTP.getTime() - lastDataFetchTime < DATA_FETCH_INTERVAL) {
					// Nothing to do yet
					continue;
				}

				// In case the data directory has been deleted...
				this.createDataDirectory();

				// Fetch data according to storage policy
				switch (Settings.getInstance().getStoragePolicy()) {
					case FOLLOWED:
					case FOLLOWED_OR_VIEWED:
						this.processNames();
						break;

					case ALL:
						this.processAll();
						break;

					case NONE:
					case VIEWED:
					default:
						// Nothing to fetch in advance
						Thread.sleep(60000);
						break;
				}

				lastDataFetchTime = NTP.getTime();
			}
		} catch (InterruptedException e) {
			// Fall-through to exit thread...
		}
	}

	public void shutdown() {
		isStopping = true;
		this.interrupt();
	}

	private void processNames() throws InterruptedException {
		// Fetch latest list of followed names
		List<String> followedNames = ListUtils.followedNames();
		if (followedNames == null || followedNames.isEmpty()) {
			return;
		}

		// Loop through the names in the list and fetch transactions for each
		for (String name : followedNames) {
			this.fetchAndProcessTransactions(name);
		}
	}

	private void processAll() throws InterruptedException {
		this.fetchAndProcessTransactions(null);
	}

	private void fetchAndProcessTransactions(String name) throws InterruptedException {
		ArbitraryDataStorageManager storageManager = ArbitraryDataStorageManager.getInstance();

		// Paginate queries when fetching arbitrary transactions
		final int limit = 100;
		int offset = 0;

		List<ArbitraryTransactionData> allArbitraryTransactionsInDescendingOrder;

		try (final Repository repository = RepositoryManager.getRepository()) {

			if( name == null ) {
				allArbitraryTransactionsInDescendingOrder
						= repository.getArbitraryRepository()
						.getLatestArbitraryTransactions();
			}
			else {
				allArbitraryTransactionsInDescendingOrder
						= repository.getArbitraryRepository()
						.getLatestArbitraryTransactionsByName(name);
			}
		} catch( Exception e) {
			LOGGER.error(e.getMessage(), e);
			allArbitraryTransactionsInDescendingOrder = new ArrayList<>(0);
		}

		// collect processed transactions in a set to ensure outdated data transactions do not get fetched
		Set<ArbitraryTransactionDataHashWrapper> processedTransactions = new HashSet<>();

		while (!isStopping) {
			Thread.sleep(1000L);

			// Any arbitrary transactions we want to fetch data for?
			try (final Repository repository = RepositoryManager.getRepository()) {
				List<byte[]> signatures = processTransactionsForSignatures(limit, offset, allArbitraryTransactionsInDescendingOrder, processedTransactions);

				if (signatures == null || signatures.isEmpty()) {
					offset = 0;
					break;
				}
				offset += limit;

				// Loop through signatures and remove ones we don't need to process
				Iterator iterator = signatures.iterator();
				while (iterator.hasNext()) {
					Thread.sleep(25L); // Reduce CPU usage
					byte[] signature = (byte[]) iterator.next();

					ArbitraryTransaction arbitraryTransaction = fetchTransaction(repository, signature);
					if (arbitraryTransaction == null) {
						// Best not to process this one
						iterator.remove();
						continue;
					}
					ArbitraryTransactionData arbitraryTransactionData = (ArbitraryTransactionData) arbitraryTransaction.getTransactionData();

					// Skip transactions that we don't need to proactively store data for
					ArbitraryDataExamination arbitraryDataExamination = storageManager.shouldPreFetchData(repository, arbitraryTransactionData);
					if (!arbitraryDataExamination.isPass()) {
						iterator.remove();

						EventBus.INSTANCE.notify(
							new DataMonitorEvent(
								System.currentTimeMillis(),
								arbitraryTransactionData.getIdentifier(),
								arbitraryTransactionData.getName(),
								arbitraryTransactionData.getService().name(),
								arbitraryDataExamination.getNotes(),
								arbitraryTransactionData.getTimestamp(),
								arbitraryTransactionData.getTimestamp()
							)
						);
						continue;
					}

					// Remove transactions that we already have local data for
					if (hasLocalData(arbitraryTransaction)) {
						iterator.remove();
						EventBus.INSTANCE.notify(
							new DataMonitorEvent(
								System.currentTimeMillis(),
								arbitraryTransactionData.getIdentifier(),
								arbitraryTransactionData.getName(),
								arbitraryTransactionData.getService().name(),
								"already have local data, skipping",
								arbitraryTransactionData.getTimestamp(),
								arbitraryTransactionData.getTimestamp()
							)
						);
                    }
				}

				if (signatures.isEmpty()) {
					continue;
				}

				// Pick one at random
				final int index = new Random().nextInt(signatures.size());
				byte[] signature = signatures.get(index);

				if (signature == null) {
					continue;
				}

				// Check to see if we have had a more recent PUT
				ArbitraryTransactionData arbitraryTransactionData = ArbitraryTransactionUtils.fetchTransactionData(repository, signature);

				Optional<ArbitraryTransactionData> moreRecentPutTransaction = ArbitraryTransactionUtils.hasMoreRecentPutTransaction(repository, arbitraryTransactionData);

				if (moreRecentPutTransaction.isPresent()) {
					EventBus.INSTANCE.notify(
						new DataMonitorEvent(
							System.currentTimeMillis(),
							arbitraryTransactionData.getIdentifier(),
							arbitraryTransactionData.getName(),
							arbitraryTransactionData.getService().name(),
							"not fetching old data",
							arbitraryTransactionData.getTimestamp(),
							moreRecentPutTransaction.get().getTimestamp()
						)
					);
					// There is a more recent PUT transaction than the one we are currently processing.
					// When a PUT is issued, it replaces any layers that would have been there before.
					// Therefore any data relating to this older transaction is no longer needed and we
					// shouldn't fetch it from the network.
					continue;
				}

				EventBus.INSTANCE.notify(
					new DataMonitorEvent(
						System.currentTimeMillis(),
						arbitraryTransactionData.getIdentifier(),
						arbitraryTransactionData.getName(),
						arbitraryTransactionData.getService().name(),
						"fetching data",
						arbitraryTransactionData.getTimestamp(),
						arbitraryTransactionData.getTimestamp()
					)
				);

				// Ask our connected peers if they have files for this signature
				// This process automatically then fetches the files themselves if a peer is found
				fetchData(arbitraryTransactionData);

				EventBus.INSTANCE.notify(
					new DataMonitorEvent(
						System.currentTimeMillis(),
						arbitraryTransactionData.getIdentifier(),
						arbitraryTransactionData.getName(),
						arbitraryTransactionData.getService().name(),
						"fetched data",
						arbitraryTransactionData.getTimestamp(),
						arbitraryTransactionData.getTimestamp()
					)
				);

			} catch (DataException e) {
				LOGGER.error("Repository issue when fetching arbitrary transaction data", e);
			}
		}
	}

	private void fetchAllMetadata() throws InterruptedException {
		ArbitraryDataStorageManager storageManager = ArbitraryDataStorageManager.getInstance();

		// Paginate queries when fetching arbitrary transactions
		final int limit = 100;
		int offset = 0;

		List<ArbitraryTransactionData> allArbitraryTransactionsInDescendingOrder;

		try (final Repository repository = RepositoryManager.getRepository()) {
			allArbitraryTransactionsInDescendingOrder
					= repository.getArbitraryRepository()
						.getLatestArbitraryTransactions();
		} catch( Exception e) {
			LOGGER.error(e.getMessage(), e);
			allArbitraryTransactionsInDescendingOrder = new ArrayList<>(0);
		}

		// collect processed transactions in a set to ensure outdated data transactions do not get fetched
		Set<ArbitraryTransactionDataHashWrapper> processedTransactions = new HashSet<>();

		while (!isStopping) {
			final int minSeconds = 3;
			final int maxSeconds = 10;
			final int randomSleepTime = new Random().nextInt((maxSeconds - minSeconds + 1)) + minSeconds;
			Thread.sleep(randomSleepTime * 1000L);

			// Any arbitrary transactions we want to fetch data for?
			try (final Repository repository = RepositoryManager.getRepository()) {
				List<byte[]> signatures = processTransactionsForSignatures(limit, offset, allArbitraryTransactionsInDescendingOrder, processedTransactions);

				if (signatures == null || signatures.isEmpty()) {
					offset = 0;
					break;
				}
				offset += limit;

				// Loop through signatures and remove ones we don't need to process
				Iterator iterator = signatures.iterator();
				while (iterator.hasNext()) {
					Thread.sleep(25L); // Reduce CPU usage
					byte[] signature = (byte[]) iterator.next();

					ArbitraryTransaction arbitraryTransaction = fetchTransaction(repository, signature);
					if (arbitraryTransaction == null) {
						// Best not to process this one
						iterator.remove();
						continue;
					}
					ArbitraryTransactionData arbitraryTransactionData = (ArbitraryTransactionData) arbitraryTransaction.getTransactionData();

					// Skip transactions that are blocked
					if (storageManager.isBlocked(arbitraryTransactionData)) {
						iterator.remove();
						continue;
					}

					// Remove transactions that we already have local data for
					if (hasLocalMetadata(arbitraryTransaction)) {
						iterator.remove();
                    }
				}

				if (signatures.isEmpty()) {
					continue;
				}

				// Pick one at random
				final int index = new Random().nextInt(signatures.size());
				byte[] signature = signatures.get(index);

				if (signature == null) {
					continue;
				}

				// No longer need to see if we have had a more recent PUT since we compared the transactions to process
				// to the transactions previously processed, so we can fetch the transactiondata, notify the event bus,
				// fetch the metadata and notify the event bus again
				ArbitraryTransactionData arbitraryTransactionData = ArbitraryTransactionUtils.fetchTransactionData(repository, signature);

				// Ask our connected peers if they have metadata for this signature
				fetchMetadata(arbitraryTransactionData);

				EventBus.INSTANCE.notify(
					new DataMonitorEvent(
						System.currentTimeMillis(),
						arbitraryTransactionData.getIdentifier(),
						arbitraryTransactionData.getName(),
						arbitraryTransactionData.getService().name(),
						"fetched metadata",
						arbitraryTransactionData.getTimestamp(),
						arbitraryTransactionData.getTimestamp()
					)
				);
			} catch (DataException e) {
				LOGGER.error("Repository issue when fetching arbitrary transaction data", e);
			} catch (Exception e) {
				LOGGER.error(e.getMessage(), e);
			}
		}
	}

	private static List<byte[]> processTransactionsForSignatures(
			int limit,
			int offset,
			List<ArbitraryTransactionData> transactionsInDescendingOrder,
			Set<ArbitraryTransactionDataHashWrapper> processedTransactions) {
		// these transactions are in descending order, latest transactions come first
		List<ArbitraryTransactionData> transactions
				= transactionsInDescendingOrder.stream()
					.skip(offset)
					.limit(limit)
					.collect(Collectors.toList());

		// wrap the transactions, so they can be used for hashing and comparing
		// Class ArbitraryTransactionDataHashWrapper supports hashCode() and equals(...) for this purpose
		List<ArbitraryTransactionDataHashWrapper> wrappedTransactions
				= transactions.stream()
					.map(transaction -> new ArbitraryTransactionDataHashWrapper(transaction))
					.collect(Collectors.toList());

		// create a set of wrappers and populate it first to last, so that all outdated transactions get rejected
		Set<ArbitraryTransactionDataHashWrapper> transactionsToProcess = new HashSet<>(wrappedTransactions.size());
		for(ArbitraryTransactionDataHashWrapper wrappedTransaction : wrappedTransactions) {
			transactionsToProcess.add(wrappedTransaction);
		}

		// remove the matches for previously processed transactions,
		// because these transactions have had updates that have already been processed
		transactionsToProcess.removeAll(processedTransactions);

		// add to processed transactions to compare and remove matches from future processing iterations
		processedTransactions.addAll(transactionsToProcess);

		List<byte[]> signatures
				= transactionsToProcess.stream()
					.map(transactionToProcess -> transactionToProcess.getData()
					.getSignature())
					.collect(Collectors.toList());

		return signatures;
	}

	private ArbitraryTransaction fetchTransaction(final Repository repository, byte[] signature) {
		try {
			TransactionData transactionData = repository.getTransactionRepository().fromSignature(signature);
			if (!(transactionData instanceof ArbitraryTransactionData))
				return null;

			return new ArbitraryTransaction(repository, transactionData);

		} catch (DataException e) {
			return null;
		}
	}

	private boolean hasLocalData(ArbitraryTransaction arbitraryTransaction) {
		try {
			return arbitraryTransaction.isDataLocal();

		} catch (DataException e) {
			LOGGER.error("Repository issue when checking arbitrary transaction's data is local", e);
			return true; // Assume true for now, to avoid network spam on error
		}
	}

	private boolean hasLocalMetadata(ArbitraryTransaction arbitraryTransaction) {
		try {
			ArbitraryTransactionData arbitraryTransactionData = (ArbitraryTransactionData) arbitraryTransaction.getTransactionData();
			byte[] signature = arbitraryTransactionData.getSignature();
			byte[] metadataHash = arbitraryTransactionData.getMetadataHash();

			if (metadataHash == null) {
				// This transaction doesn't have metadata associated with it, so return true to indicate that we have everything
				return true;
			}

			ArbitraryDataFile metadataFile = ArbitraryDataFile.fromHash(metadataHash, signature);

			return metadataFile.exists();

		} catch (DataException e) {
			LOGGER.error("Repository issue when checking arbitrary transaction's metadata is local", e);
			return true; // Assume true for now, to avoid network spam on error
		}
	}

	// Entrypoint to request new data from peers
	public boolean fetchData(ArbitraryTransactionData arbitraryTransactionData) {
		return ArbitraryDataFileListManager.getInstance().fetchArbitraryDataFileList(arbitraryTransactionData);
	}

	// Entrypoint to request new metadata from peers
	public ArbitraryDataTransactionMetadata fetchMetadata(ArbitraryTransactionData arbitraryTransactionData) {

		if (arbitraryTransactionData.getService() == null) {
			// Can't fetch metadata without a valid service
			return null;
		}

		ArbitraryDataResource resource = new ArbitraryDataResource(
				arbitraryTransactionData.getName(),
				ArbitraryDataFile.ResourceIdType.NAME,
				arbitraryTransactionData.getService(),
				arbitraryTransactionData.getIdentifier()
		);
		return ArbitraryMetadataManager.getInstance().fetchMetadata(resource, true);
	}


	// Useful methods used by other parts of the app

	public boolean isSignatureRateLimited(byte[] signature) {
		return ArbitraryDataFileListManager.getInstance().isSignatureRateLimited(signature);
	}

	public long lastRequestForSignature(byte[] signature) {
		return ArbitraryDataFileListManager.getInstance().lastRequestForSignature(signature);
	}


	// Arbitrary data resource cache

	public void cleanupRequestCache(Long now) {
		if (now == null) {
			return;
		}

		// Cleanup file list request caches
		ArbitraryDataFileListManager.getInstance().cleanupRequestCache(now);

		// Cleanup file request caches
		ArbitraryDataFileManager.getInstance().cleanupRequestCache(now);

		// Clean up metadata request caches
		ArbitraryMetadataManager.getInstance().cleanupRequestCache(now);
	}

	public boolean isResourceCached(ArbitraryDataResource resource) {
		if (resource == null) {
			return false;
		}
		String key = resource.getUniqueKey();

		// We don't have an entry for this resource ID, it is not cached
		if (this.arbitraryDataCachedResources == null) {
			return false;
		}
		if (!this.arbitraryDataCachedResources.containsKey(key)) {
			return false;
		}
		Long timestamp = this.arbitraryDataCachedResources.get(key);
		if (timestamp == null) {
			return false;
		}

		// If the timestamp has reached the timeout, we should remove it from the cache
		long now = NTP.getTime();
		if (now > timestamp) {
			this.arbitraryDataCachedResources.remove(key);
			return false;
		}

		// Current time hasn't reached the timeout, so treat it as cached
		return true;
	}

	public void addResourceToCache(ArbitraryDataResource resource) {
		if (resource == null) {
			return;
		}
		String key = resource.getUniqueKey();

		// Just in case
		if (this.arbitraryDataCachedResources == null) {
			this.arbitraryDataCachedResources = new HashMap<>();
		}

		Long now = NTP.getTime();
		if (now == null) {
			return;
		}

		// Set the timestamp to now + the timeout
		Long timestamp = NTP.getTime() + ARBITRARY_DATA_CACHE_TIMEOUT;
		this.arbitraryDataCachedResources.put(key, timestamp);
	}

	public void invalidateCache(ArbitraryTransactionData arbitraryTransactionData) {
		String signature58 = Base58.encode(arbitraryTransactionData.getSignature());

		if (arbitraryTransactionData.getName() != null && arbitraryTransactionData.getService() != null) {
			String resourceId = arbitraryTransactionData.getName().toLowerCase();
			Service service = arbitraryTransactionData.getService();
			String identifier = arbitraryTransactionData.getIdentifier();

			ArbitraryDataResource resource =
					new ArbitraryDataResource(resourceId, ArbitraryDataFile.ResourceIdType.NAME, service, identifier);
			String key = resource.getUniqueKey();
			LOGGER.trace("Clearing cache for {}...", resource);

			if (this.arbitraryDataCachedResources.containsKey(key)) {
				this.arbitraryDataCachedResources.remove(key);
			}

			// Also remove from the failed builds queue in case it previously failed due to missing chunks
			ArbitraryDataBuildManager buildManager = ArbitraryDataBuildManager.getInstance();
			if (buildManager.arbitraryDataFailedBuilds.containsKey(key)) {
				buildManager.arbitraryDataFailedBuilds.remove(key);
			}

			// Remove from the signature requests list now that we have all files for this signature
			ArbitraryDataFileListManager.getInstance().removeFromSignatureRequests(signature58);

			// Delete cached files themselves
			try {
				resource.deleteCache();
			} catch (IOException e) {
				LOGGER.info("Unable to delete cache for resource {}: {}", resource, e.getMessage());
			}
		}
	}

	private boolean createDataDirectory() {
		// Create the data directory if it doesn't exist
		String dataPath = Settings.getInstance().getDataPath();
		Path dataDirectory = Paths.get(dataPath);
		try {
			Files.createDirectories(dataDirectory);
		} catch (IOException e) {
			LOGGER.error("Unable to create data directory");
			return false;
		}
		return true;
	}

	public void onExpiredArbitraryTransaction(ArbitraryTransactionData arbitraryTransactionData) {
		if (arbitraryTransactionData.getName() == null) {
			// No name, so we don't care about this transaction
			return;
		}

		// Add to queue for update/deletion
		ArbitraryDataCacheManager.getInstance().addToUpdateQueue(arbitraryTransactionData);

	}

	public int getPowDifficulty() {
		return this.powDifficulty;
	}

}
