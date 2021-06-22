package org.qortal.controller;

import java.math.BigInteger;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import java.util.Iterator;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.account.Account;
import org.qortal.account.PublicKeyAccount;
import org.qortal.block.Block;
import org.qortal.block.Block.ValidationResult;
import org.qortal.block.BlockChain;
import org.qortal.data.block.BlockData;
import org.qortal.data.block.BlockSummaryData;
import org.qortal.data.block.CommonBlockData;
import org.qortal.data.network.PeerChainTipData;
import org.qortal.data.transaction.RewardShareTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.network.Peer;
import org.qortal.network.message.BlockMessage;
import org.qortal.network.message.BlockSummariesMessage;
import org.qortal.network.message.GetBlockMessage;
import org.qortal.network.message.GetBlockSummariesMessage;
import org.qortal.network.message.GetSignaturesV2Message;
import org.qortal.network.message.Message;
import org.qortal.network.message.SignaturesMessage;
import org.qortal.network.message.MessageType;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.settings.Settings;
import org.qortal.transaction.Transaction;
import org.qortal.utils.Base58;
import org.qortal.utils.NTP;

public class Synchronizer {

	private static final Logger LOGGER = LogManager.getLogger(Synchronizer.class);

	/** Max number of new blocks we aim to add to chain tip in each sync round */
	private static final int SYNC_BATCH_SIZE = 200; // XXX move to Settings?

	/** Initial jump back of block height when searching for common block with peer */
	private static final int INITIAL_BLOCK_STEP = 8;
	/** Maximum jump back of block height when searching for common block with peer */
	private static final int MAXIMUM_BLOCK_STEP = 128;

	/** Maximum difference in block height between tip and peer's common block before peer is considered TOO DIVERGENT */
	private static final int MAXIMUM_COMMON_DELTA = 240; // XXX move to Settings?

	/** Maximum number of block signatures we ask from peer in one go */
	private static final int MAXIMUM_REQUEST_SIZE = 200; // XXX move to Settings?


	// Keep track of the size of the last re-org, so it can be logged
	private int lastReorgSize;

	private static Synchronizer instance;

	public enum SynchronizationResult {
		OK, NOTHING_TO_DO, GENESIS_ONLY, NO_COMMON_BLOCK, TOO_DIVERGENT, NO_REPLY, INFERIOR_CHAIN, INVALID_DATA, NO_BLOCKCHAIN_LOCK, REPOSITORY_ISSUE, SHUTTING_DOWN;
	}

	// Constructors

	private Synchronizer() {
	}

	public static Synchronizer getInstance() {
		if (instance == null)
			instance = new Synchronizer();

		return instance;
	}


	/**
	 * Iterate through a list of supplied peers, and attempt to find our common block with each.
	 * If a common block is found, its summary will be retained in the peer's commonBlockSummary property, for processing later.
	 * <p>
	 * Will return <tt>SynchronizationResult.OK</tt> on success.
	 * <p>
	 * @param peers
	 * @return SynchronizationResult.OK if the process completed successfully, or a different SynchronizationResult if something went wrong.
	 * @throws InterruptedException
	 */
	public SynchronizationResult findCommonBlocksWithPeers(List<Peer> peers) throws InterruptedException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			try {

				if (peers.size() == 0)
					return SynchronizationResult.NOTHING_TO_DO;

				// If our latest block is very old, it's best that we don't try and determine the best peers to sync to.
				// This is because it can involve very large chain comparisons, which is too intensive.
				// In reality, most forking problems occur near the chain tips, so we will reserve this functionality for those situations.
				final Long minLatestBlockTimestamp = Controller.getMinimumLatestBlockTimestamp();
				if (minLatestBlockTimestamp == null)
					return SynchronizationResult.REPOSITORY_ISSUE;

				final BlockData ourLatestBlockData = repository.getBlockRepository().getLastBlock();
				if (ourLatestBlockData.getTimestamp() < minLatestBlockTimestamp) {
					LOGGER.debug(String.format("Our latest block is very old, so we won't collect common block info from peers"));
					return SynchronizationResult.NOTHING_TO_DO;
				}

				LOGGER.debug(String.format("Searching for common blocks with %d peers...", peers.size()));
				final long startTime = System.currentTimeMillis();
				int commonBlocksFound = 0;
				boolean wereNewRequestsMade = false;

				for (Peer peer : peers) {
					// Are we shutting down?
					if (Controller.isStopping())
						return SynchronizationResult.SHUTTING_DOWN;

					// Check if we can use the cached common block data, by comparing the peer's current chain tip against the peer's chain tip when we last found our common block
					if (peer.canUseCachedCommonBlockData()) {
						LOGGER.debug(String.format("Skipping peer %s because we already have the latest common block data in our cache. Cached common block sig is %.08s", peer, Base58.encode(peer.getCommonBlockData().getCommonBlockSummary().getSignature())));
						commonBlocksFound++;
						continue;
					}

					// Cached data is stale, so clear it and repopulate
					peer.setCommonBlockData(null);

					// Search for the common block
					Synchronizer.getInstance().findCommonBlockWithPeer(peer, repository);
					if (peer.getCommonBlockData() != null)
						commonBlocksFound++;

					// This round wasn't served entirely from the cache, so we may want to log the results
					wereNewRequestsMade = true;
				}

				if (wereNewRequestsMade) {
					final long totalTimeTaken = System.currentTimeMillis() - startTime;
					LOGGER.debug(String.format("Finished searching for common blocks with %d peer%s. Found: %d. Total time taken: %d ms", peers.size(), (peers.size() != 1 ? "s" : ""), commonBlocksFound, totalTimeTaken));
				}

				return SynchronizationResult.OK;
			} finally {
				repository.discardChanges(); // Free repository locks, if any, also in case anything went wrong
			}
		} catch (DataException e) {
			LOGGER.error("Repository issue during synchronization with peer", e);
			return SynchronizationResult.REPOSITORY_ISSUE;
		}
	}

	/**
	 * Attempt to find the find our common block with supplied peer.
	 * If a common block is found, its summary will be retained in the peer's commonBlockSummary property, for processing later.
	 * <p>
	 * Will return <tt>SynchronizationResult.OK</tt> on success.
	 * <p>
	 * @param peer
	 * @param repository
	 * @return SynchronizationResult.OK if the process completed successfully, or a different SynchronizationResult if something went wrong.
	 * @throws InterruptedException
	 */
	public SynchronizationResult findCommonBlockWithPeer(Peer peer, Repository repository) throws InterruptedException {
		try {
			final BlockData ourLatestBlockData = repository.getBlockRepository().getLastBlock();
			final int ourInitialHeight = ourLatestBlockData.getHeight();

			PeerChainTipData peerChainTipData = peer.getChainTipData();
			int peerHeight = peerChainTipData.getLastHeight();
			byte[] peersLastBlockSignature = peerChainTipData.getLastBlockSignature();

			byte[] ourLastBlockSignature = ourLatestBlockData.getSignature();
			LOGGER.debug(String.format("Fetching summaries from peer %s at height %d, sig %.8s, ts %d; our height %d, sig %.8s, ts %d", peer,
					peerHeight, Base58.encode(peersLastBlockSignature), peer.getChainTipData().getLastBlockTimestamp(),
					ourInitialHeight, Base58.encode(ourLastBlockSignature), ourLatestBlockData.getTimestamp()));

			List<BlockSummaryData> peerBlockSummaries = new ArrayList<>();
			SynchronizationResult findCommonBlockResult = fetchSummariesFromCommonBlock(repository, peer, ourInitialHeight, false, peerBlockSummaries, false);
			if (findCommonBlockResult != SynchronizationResult.OK) {
				// Logging performed by fetchSummariesFromCommonBlock() above
				peer.setCommonBlockData(null);
				return findCommonBlockResult;
			}

			// First summary is common block
			final BlockData commonBlockData = repository.getBlockRepository().fromSignature(peerBlockSummaries.get(0).getSignature());
			final BlockSummaryData commonBlockSummary = new BlockSummaryData(commonBlockData);
			final int commonBlockHeight = commonBlockData.getHeight();
			final byte[] commonBlockSig = commonBlockData.getSignature();
			final String commonBlockSig58 = Base58.encode(commonBlockSig);
			LOGGER.debug(String.format("Common block with peer %s is at height %d, sig %.8s, ts %d", peer,
					commonBlockHeight, commonBlockSig58, commonBlockData.getTimestamp()));
			peerBlockSummaries.remove(0);

			// Store the common block summary against the peer, and the current chain tip (for caching)
			peer.setCommonBlockData(new CommonBlockData(commonBlockSummary, peerChainTipData));

			return SynchronizationResult.OK;
		} catch (DataException e) {
			LOGGER.error("Repository issue during synchronization with peer", e);
			return SynchronizationResult.REPOSITORY_ISSUE;
		}
	}


	/**
	 * Compare a list of peers to determine the best peer(s) to sync to next.
	 * <p>
	 * Will return a filtered list of peers on success, or an identical list of peers on failure.
	 * This allows us to fall back to legacy behaviour (random selection from the entire list of peers), if we are unable to make the comparison.
	 * <p>
	 * @param peers
	 * @return a list of peers, possibly filtered.
	 * @throws InterruptedException
	 */
	public List<Peer> comparePeers(List<Peer> peers) throws InterruptedException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			try {

				// If our latest block is very old, it's best that we don't try and determine the best peers to sync to.
				// This is because it can involve very large chain comparisons, which is too intensive.
				// In reality, most forking problems occur near the chain tips, so we will reserve this functionality for those situations.
				final Long minLatestBlockTimestamp = Controller.getMinimumLatestBlockTimestamp();
				if (minLatestBlockTimestamp == null)
					return peers;

				final BlockData ourLatestBlockData = repository.getBlockRepository().getLastBlock();
				if (ourLatestBlockData.getTimestamp() < minLatestBlockTimestamp) {
					LOGGER.debug(String.format("Our latest block is very old, so we won't filter the peers list"));
					return peers;
				}

				// We will switch to a new chain weight consensus algorithm at a hard fork, so determine if this has happened yet
				boolean usingSameLengthChainWeight = (NTP.getTime() >= BlockChain.getInstance().getCalcChainWeightTimestamp());
				LOGGER.debug(String.format("Using %s chain weight consensus algorithm", (usingSameLengthChainWeight ? "same-length" : "variable-length")));

				// Retrieve a list of unique common blocks from this list of peers
				List<BlockSummaryData> commonBlocks = this.uniqueCommonBlocks(peers);

				// Order common blocks by height, in ascending order
				// This is essential for the logic below to make the correct decisions when discarding chains - do not remove
				commonBlocks.sort((b1, b2) -> Integer.valueOf(b1.getHeight()).compareTo(Integer.valueOf(b2.getHeight())));

				// Get our latest height
				final int ourHeight = ourLatestBlockData.getHeight();

				// Create a placeholder to track of common blocks that we can discard due to being inferior chains
				int dropPeersAfterCommonBlockHeight = 0;

				// Remove peers with no common block data
				Iterator iterator = peers.iterator();
				while (iterator.hasNext()) {
					Peer peer = (Peer) iterator.next();
					if (peer.getCommonBlockData() == null) {
						LOGGER.debug(String.format("Removed peer %s because it has no common block data", peer));
						iterator.remove();
					}
				}

				// Loop through each group of common blocks
				for (BlockSummaryData commonBlockSummary : commonBlocks) {
					List<Peer> peersSharingCommonBlock = peers.stream().filter(peer -> peer.getCommonBlockData().getCommonBlockSummary().equals(commonBlockSummary)).collect(Collectors.toList());

					// Check if we need to discard this group of peers
					if (dropPeersAfterCommonBlockHeight > 0) {
						if (commonBlockSummary.getHeight() > dropPeersAfterCommonBlockHeight) {
							// We have already determined that the correct chain diverged from a lower height. We are safe to skip these peers.
							for (Peer peer : peersSharingCommonBlock) {
								LOGGER.debug(String.format("Peer %s has common block at height %d but the superior chain is at height %d. Removing it from this round.", peer, commonBlockSummary.getHeight(), dropPeersAfterCommonBlockHeight));
								Controller.getInstance().addInferiorChainSignature(peer.getChainTipData().getLastBlockSignature());
							}
							continue;
						}
					}

					// Calculate the length of the shortest peer chain sharing this common block, including our chain
					final int ourAdditionalBlocksAfterCommonBlock = ourHeight - commonBlockSummary.getHeight();
					int minChainLength = this.calculateMinChainLengthOfPeers(peersSharingCommonBlock, commonBlockSummary);

					// Fetch block summaries from each peer
					for (Peer peer : peersSharingCommonBlock) {

						// If we're shutting down, just return the latest peer list
						if (Controller.isStopping())
							return peers;

						// Count the number of blocks this peer has beyond our common block
						final PeerChainTipData peerChainTipData = peer.getChainTipData();
						final int peerHeight = peerChainTipData.getLastHeight();
						final byte[] peerLastBlockSignature = peerChainTipData.getLastBlockSignature();
						final int peerAdditionalBlocksAfterCommonBlock = peerHeight - commonBlockSummary.getHeight();
						// Limit the number of blocks we are comparing. FUTURE: we could request more in batches, but there may not be a case when this is needed
						int summariesRequired = Math.min(peerAdditionalBlocksAfterCommonBlock, MAXIMUM_REQUEST_SIZE);

						// Check if we can use the cached common block summaries, by comparing the peer's current chain tip against the peer's chain tip when we last found our common block
						boolean useCachedSummaries = false;
						if (peer.canUseCachedCommonBlockData()) {
							if (peer.getCommonBlockData().getBlockSummariesAfterCommonBlock() != null) {
								if (peer.getCommonBlockData().getBlockSummariesAfterCommonBlock().size() == summariesRequired) {
									LOGGER.trace(String.format("Using cached block summaries for peer %s", peer));
									useCachedSummaries = true;
								}
							}
						}

						if (useCachedSummaries == false) {
							if (summariesRequired > 0) {
								LOGGER.trace(String.format("Requesting %d block summar%s from peer %s after common block %.8s. Peer height: %d", summariesRequired, (summariesRequired != 1 ? "ies" : "y"), peer, Base58.encode(commonBlockSummary.getSignature()), peerHeight));

								// Forget any cached summaries
								peer.getCommonBlockData().setBlockSummariesAfterCommonBlock(null);

								// Request new block summaries
								List<BlockSummaryData> blockSummaries = this.getBlockSummaries(peer, commonBlockSummary.getSignature(), summariesRequired);
								if (blockSummaries != null) {
									LOGGER.trace(String.format("Peer %s returned %d block summar%s", peer, blockSummaries.size(), (blockSummaries.size() != 1 ? "ies" : "y")));

									if (blockSummaries.size() < summariesRequired)
										// This could mean that the peer has re-orged. Exclude this peer until they return the summaries we expect.
										LOGGER.debug(String.format("Peer %s returned %d block summar%s instead of expected %d - excluding them from this round", peer, blockSummaries.size(), (blockSummaries.size() != 1 ? "ies" : "y"), summariesRequired));
									else if (blockSummaryWithSignature(peerLastBlockSignature, blockSummaries) == null)
										// We don't have a block summary for the peer's reported chain tip, so should exclude it
										LOGGER.debug(String.format("Peer %s didn't return a block summary with signature %.8s - excluding them from this round", peer, Base58.encode(peerLastBlockSignature)));
									else
										// All looks good, so store the retrieved block summaries in the peer's cache
										peer.getCommonBlockData().setBlockSummariesAfterCommonBlock(blockSummaries);
								}
							} else {
								// There are no block summaries after this common block
								peer.getCommonBlockData().setBlockSummariesAfterCommonBlock(null);
							}
						}

						// Reduce minChainLength if needed. If we don't have any blocks, this peer will be excluded from chain weight comparisons later in the process, so we shouldn't update minChainLength
						List <BlockSummaryData> peerBlockSummaries = peer.getCommonBlockData().getBlockSummariesAfterCommonBlock();
						if (peerBlockSummaries != null && peerBlockSummaries.size() > 0)
							if (peerBlockSummaries.size() < minChainLength)
								minChainLength = peerBlockSummaries.size();
					}

					// Fetch our corresponding block summaries. Limit to MAXIMUM_REQUEST_SIZE, in order to make the comparison fairer, as peers have been limited too
					final int ourSummariesRequired = Math.min(ourAdditionalBlocksAfterCommonBlock, MAXIMUM_REQUEST_SIZE);
					LOGGER.trace(String.format("About to fetch our block summaries from %d to %d. Our height: %d", commonBlockSummary.getHeight() + 1, commonBlockSummary.getHeight() + ourSummariesRequired, ourHeight));
					List<BlockSummaryData> ourBlockSummaries = repository.getBlockRepository().getBlockSummaries(commonBlockSummary.getHeight() + 1, commonBlockSummary.getHeight() + ourSummariesRequired);
					if (ourBlockSummaries.isEmpty()) {
						LOGGER.debug(String.format("We don't have any block summaries so can't compare our chain against peers with this common block. We can still compare them against each other."));
					}
					else {
						populateBlockSummariesMinterLevels(repository, ourBlockSummaries);
						// Reduce minChainLength if we have less summaries
						if (ourBlockSummaries.size() < minChainLength)
							minChainLength = ourBlockSummaries.size();
					}

					// Create array to hold peers for comparison
					List<Peer> superiorPeersForComparison = new ArrayList<>();

					// Calculate max height for chain weight comparisons
					int maxHeightForChainWeightComparisons = commonBlockSummary.getHeight() + minChainLength;

					// Calculate our chain weight
					BigInteger ourChainWeight = BigInteger.valueOf(0);
					if (ourBlockSummaries.size() > 0)
						ourChainWeight = Block.calcChainWeight(commonBlockSummary.getHeight(), commonBlockSummary.getSignature(), ourBlockSummaries, maxHeightForChainWeightComparisons);

					NumberFormat formatter = new DecimalFormat("0.###E0");
					NumberFormat accurateFormatter = new DecimalFormat("0.################E0");
					LOGGER.debug(String.format("Our chain weight based on %d blocks is %s", (usingSameLengthChainWeight ? minChainLength : ourBlockSummaries.size()), formatter.format(ourChainWeight)));

					LOGGER.debug(String.format("Listing peers with common block %.8s...", Base58.encode(commonBlockSummary.getSignature())));
					for (Peer peer : peersSharingCommonBlock) {
						final int peerHeight = peer.getChainTipData().getLastHeight();
						final int peerAdditionalBlocksAfterCommonBlock = peerHeight - commonBlockSummary.getHeight();
						final CommonBlockData peerCommonBlockData = peer.getCommonBlockData();

						if (peerCommonBlockData == null || peerCommonBlockData.getBlockSummariesAfterCommonBlock() == null || peerCommonBlockData.getBlockSummariesAfterCommonBlock().isEmpty()) {
							// No response - remove this peer for now
							LOGGER.debug(String.format("Peer %s doesn't have any block summaries - removing it from this round", peer));
							peers.remove(peer);
							continue;
						}

						final List<BlockSummaryData> peerBlockSummariesAfterCommonBlock = peerCommonBlockData.getBlockSummariesAfterCommonBlock();
						populateBlockSummariesMinterLevels(repository, peerBlockSummariesAfterCommonBlock);

						// Calculate cumulative chain weight of this blockchain subset, from common block to highest mutual block held by all peers in this group.
						LOGGER.debug(String.format("About to calculate chain weight based on %d blocks for peer %s with common block %.8s (peer has %d blocks after common block)", (usingSameLengthChainWeight ? minChainLength : peerBlockSummariesAfterCommonBlock.size()), peer, Base58.encode(commonBlockSummary.getSignature()), peerAdditionalBlocksAfterCommonBlock));
						BigInteger peerChainWeight = Block.calcChainWeight(commonBlockSummary.getHeight(), commonBlockSummary.getSignature(), peerBlockSummariesAfterCommonBlock, maxHeightForChainWeightComparisons);
						peer.getCommonBlockData().setChainWeight(peerChainWeight);
						LOGGER.debug(String.format("Chain weight of peer %s based on %d blocks (%d - %d) is %s", peer, (usingSameLengthChainWeight ? minChainLength : peerBlockSummariesAfterCommonBlock.size()), peerBlockSummariesAfterCommonBlock.get(0).getHeight(), peerBlockSummariesAfterCommonBlock.get(peerBlockSummariesAfterCommonBlock.size()-1).getHeight(), formatter.format(peerChainWeight)));

						// Compare against our chain - if our blockchain has greater weight then don't synchronize with peer (or any others in this group)
						if (ourChainWeight.compareTo(peerChainWeight) > 0) {
							// This peer is on an inferior chain - remove it
							LOGGER.debug(String.format("Peer %s is on an inferior chain to us - removing it from this round", peer));
							peers.remove(peer);
						}
						else {
							// Our chain is inferior or equal
							LOGGER.debug(String.format("Peer %s is on an equal or better chain to us. We will compare the other peers sharing this common block against each other, and drop all peers sharing higher common blocks.", peer));
							dropPeersAfterCommonBlockHeight = commonBlockSummary.getHeight();
							superiorPeersForComparison.add(peer);
						}
					}

					// Now that we have selected the best peers, compare them against each other and remove any with lower weights
					if (superiorPeersForComparison.size() > 0) {
						BigInteger bestChainWeight = null;
						for (Peer peer : superiorPeersForComparison) {
							// Increase bestChainWeight if needed
							if (bestChainWeight == null || peer.getCommonBlockData().getChainWeight().compareTo(bestChainWeight) >= 0)
								bestChainWeight = peer.getCommonBlockData().getChainWeight();
						}
						for (Peer peer : superiorPeersForComparison) {
							// Check if we should discard an inferior peer
							if (peer.getCommonBlockData().getChainWeight().compareTo(bestChainWeight) < 0) {
								BigInteger difference = bestChainWeight.subtract(peer.getCommonBlockData().getChainWeight());
								LOGGER.debug(String.format("Peer %s has a lower chain weight (difference: %s) than other peer(s) in this group - removing it from this round.", peer, accurateFormatter.format(difference)));
								peers.remove(peer);
							}
						}
						// FUTURE: we may want to prefer peers with additional blocks, and compare the additional blocks against each other.
						// This would fast track us to the best candidate for the latest block.
						// Right now, peers with the exact same chain as us are treated equally to those with an additional block.
					}
				}

				return peers;
			} finally {
				repository.discardChanges(); // Free repository locks, if any, also in case anything went wrong
			}
		} catch (DataException e) {
			LOGGER.error("Repository issue during peer comparison", e);
			return peers;
		}
	}

	private List<BlockSummaryData> uniqueCommonBlocks(List<Peer> peers) {
		List<BlockSummaryData> commonBlocks = new ArrayList<>();

		for (Peer peer : peers) {
			if (peer.getCommonBlockData() != null && peer.getCommonBlockData().getCommonBlockSummary() != null) {
				LOGGER.trace(String.format("Peer %s has common block %.8s", peer, Base58.encode(peer.getCommonBlockData().getCommonBlockSummary().getSignature())));

				BlockSummaryData commonBlockSummary = peer.getCommonBlockData().getCommonBlockSummary();
				if (!commonBlocks.contains(commonBlockSummary))
					commonBlocks.add(commonBlockSummary);
			}
			else {
				LOGGER.trace(String.format("Peer %s has no common block data. Skipping...", peer));
			}
		}

		return commonBlocks;
	}

	private int calculateMinChainLengthOfPeers(List<Peer> peersSharingCommonBlock, BlockSummaryData commonBlockSummary) {
		// Calculate the length of the shortest peer chain sharing this common block
		int minChainLength = 0;
		for (Peer peer : peersSharingCommonBlock) {
			final int peerHeight = peer.getChainTipData().getLastHeight();
			final int peerAdditionalBlocksAfterCommonBlock = peerHeight - commonBlockSummary.getHeight();

			if (peerAdditionalBlocksAfterCommonBlock < minChainLength || minChainLength == 0)
				minChainLength = peerAdditionalBlocksAfterCommonBlock;
		}
		return minChainLength;
	}

	private BlockSummaryData blockSummaryWithSignature(byte[] signature, List<BlockSummaryData> blockSummaries) {
		if (blockSummaries != null)
			return blockSummaries.stream().filter(blockSummary -> Arrays.equals(blockSummary.getSignature(), signature)).findAny().orElse(null);
		return null;
	}


	/**
	 * Attempt to synchronize blockchain with peer.
	 * <p>
	 * Will return <tt>true</tt> if synchronization succeeded,
	 * even if no changes were made to our blockchain.
	 * <p>
	 * @param peer
	 * @return false if something went wrong, true otherwise.
	 * @throws InterruptedException
	 */
	public SynchronizationResult synchronize(Peer peer, boolean force) throws InterruptedException {
		// Make sure we're the only thread modifying the blockchain
		// If we're already synchronizing with another peer then this will also return fast
		ReentrantLock blockchainLock = Controller.getInstance().getBlockchainLock();
		if (!blockchainLock.tryLock())
			// Wasn't peer's fault we couldn't sync
			return SynchronizationResult.NO_BLOCKCHAIN_LOCK;

		try {
			try (final Repository repository = RepositoryManager.getRepository()) {
				try {
					final BlockData ourLatestBlockData = repository.getBlockRepository().getLastBlock();
					final int ourInitialHeight = ourLatestBlockData.getHeight();

					PeerChainTipData peerChainTipData = peer.getChainTipData();
					int peerHeight = peerChainTipData.getLastHeight();
					byte[] peersLastBlockSignature = peerChainTipData.getLastBlockSignature();

					byte[] ourLastBlockSignature = ourLatestBlockData.getSignature();
					String syncString = String.format("Synchronizing with peer %s at height %d, sig %.8s, ts %d; our height %d, sig %.8s, ts %d", peer,
							peerHeight, Base58.encode(peersLastBlockSignature), peer.getChainTipData().getLastBlockTimestamp(),
							ourInitialHeight, Base58.encode(ourLastBlockSignature), ourLatestBlockData.getTimestamp());

					// If our latest block is very old, we should log that we're attempting to sync with a peer
					// Otherwise, it can appear as though nothing is happening for a while after launch
					final Long minLatestBlockTimestamp = Controller.getMinimumLatestBlockTimestamp();
					if (minLatestBlockTimestamp != null && ourLatestBlockData.getTimestamp() < minLatestBlockTimestamp) {
						LOGGER.info(syncString);
					}
					else {
						LOGGER.debug(syncString);
					}

					// Reset last re-org size as we are starting a new sync round
					this.lastReorgSize = 0;

					List<BlockSummaryData> peerBlockSummaries = new ArrayList<>();
					SynchronizationResult findCommonBlockResult = fetchSummariesFromCommonBlock(repository, peer, ourInitialHeight, force, peerBlockSummaries, true);
					if (findCommonBlockResult != SynchronizationResult.OK) {
						// Logging performed by fetchSummariesFromCommonBlock() above
						// Clear our common block cache for this peer
						peer.setCommonBlockData(null);
						return findCommonBlockResult;
					}

					// First summary is common block
					final BlockData commonBlockData = repository.getBlockRepository().fromSignature(peerBlockSummaries.get(0).getSignature());
					final int commonBlockHeight = commonBlockData.getHeight();
					final byte[] commonBlockSig = commonBlockData.getSignature();
					final String commonBlockSig58 = Base58.encode(commonBlockSig);
					LOGGER.debug(String.format("Common block with peer %s is at height %d, sig %.8s, ts %d", peer,
							commonBlockHeight, commonBlockSig58, commonBlockData.getTimestamp()));
					peerBlockSummaries.remove(0);

					// If common block height is higher than peer's last reported height
					// then peer must have a very recent sync. Update our idea of peer's height.
					if (commonBlockHeight > peerHeight) {
						LOGGER.debug(String.format("Peer height %d was lower than common block height %d - using higher value", peerHeight, commonBlockHeight));
						peerHeight = commonBlockHeight;
					}

					// If common block is peer's latest block then we simply have the same, or longer, chain to peer, so exit now
					if (commonBlockHeight == peerHeight) {
						if (peerHeight == ourInitialHeight)
							LOGGER.debug(String.format("We have the same blockchain as peer %s", peer));
						else
							LOGGER.debug(String.format("We have the same blockchain as peer %s, but longer", peer));

						return SynchronizationResult.NOTHING_TO_DO;
					}

					// Unless we're doing a forced sync, we might need to compare blocks after common block
					if (!force && ourInitialHeight > commonBlockHeight) {
						SynchronizationResult chainCompareResult = compareChains(repository, commonBlockData, ourLatestBlockData, peer, peerHeight, peerBlockSummaries);
						if (chainCompareResult != SynchronizationResult.OK)
							return chainCompareResult;
					}

					SynchronizationResult syncResult = null;
					if (commonBlockHeight < ourInitialHeight) {
						// Peer's chain is better, sync to that one
						syncResult = syncToPeerChain(repository, commonBlockData, ourInitialHeight, peer, peerHeight, peerBlockSummaries);
					} else {
						// Simply fetch and apply blocks as they arrive
						syncResult = applyNewBlocks(repository, commonBlockData, ourInitialHeight, peer, peerHeight, peerBlockSummaries);
					}

					if (syncResult != SynchronizationResult.OK)
						return syncResult;

					// Commit
					repository.saveChanges();

					// Create string for logging
					final BlockData newLatestBlockData = repository.getBlockRepository().getLastBlock();
					String syncLog = String.format("Synchronized with peer %s to height %d, sig %.8s, ts: %d", peer,
							newLatestBlockData.getHeight(), Base58.encode(newLatestBlockData.getSignature()),
							newLatestBlockData.getTimestamp());

					// Append re-org info
					if (this.lastReorgSize > 0) {
						syncLog = syncLog.concat(String.format(", size: %d", this.lastReorgSize));
					}

					// Log sync info
					LOGGER.info(syncLog);

					return SynchronizationResult.OK;
				} finally {
					repository.discardChanges(); // Free repository locks, if any, also in case anything went wrong
				}
			} catch (DataException e) {
				LOGGER.error("Repository issue during synchronization with peer", e);
				return SynchronizationResult.REPOSITORY_ISSUE;
			}
		} finally {
			blockchainLock.unlock();
		}
	}

	/**
	 * Returns list of peer's block summaries starting with common block with peer.
	 *
	 * @param peer
	 * @return block summaries, or empty list if no common block, or null if there was an issue
	 * @throws DataException
	 * @throws InterruptedException
	 */
	public SynchronizationResult fetchSummariesFromCommonBlock(Repository repository, Peer peer, int ourHeight, boolean force, List<BlockSummaryData> blockSummariesFromCommon, boolean infoLogWhenNotFound) throws DataException, InterruptedException {
		// Start by asking for a few recent block hashes as this will cover a majority of reorgs
		// Failing that, back off exponentially
		int step = INITIAL_BLOCK_STEP;

		int testHeight = Math.max(ourHeight - step, 1);
		BlockData testBlockData = null;

		List<BlockSummaryData> blockSummariesBatch = null;

		while (testHeight >= 1) {
			// Are we shutting down?
			if (Controller.isStopping())
				return SynchronizationResult.SHUTTING_DOWN;

			// Fetch our block signature at this height
			testBlockData = repository.getBlockRepository().fromHeight(testHeight);
			if (testBlockData == null) {
				// Not found? But we've locked the blockchain and height is below blockchain's tip!
				LOGGER.error("Failed to get block at height lower than blockchain tip during synchronization?");
				return SynchronizationResult.REPOSITORY_ISSUE;
			}

			// Ask for block signatures since test block's signature
			byte[] testSignature = testBlockData.getSignature();
			LOGGER.trace(String.format("Requesting %d summar%s after height %d", step, (step != 1 ? "ies": "y"), testHeight));
			blockSummariesBatch = this.getBlockSummaries(peer, testSignature, step);

			if (blockSummariesBatch == null) {
				if (infoLogWhenNotFound)
					LOGGER.info(String.format("Error while trying to find common block with peer %s", peer));
				else
					LOGGER.debug(String.format("Error while trying to find common block with peer %s", peer));

				// No response - give up this time
				return SynchronizationResult.NO_REPLY;
			}

			LOGGER.trace(String.format("Received %s summar%s", blockSummariesBatch.size(), (blockSummariesBatch.size() != 1 ? "ies" : "y")));

			// Empty list means remote peer is unaware of test signature OR has no new blocks after test signature
			if (!blockSummariesBatch.isEmpty())
				// We have entries so we have found a common block
				break;

			// No blocks after genesis block?
			// We don't get called for a peer at genesis height so this means NO blocks in common
			if (testHeight == 1) {
				LOGGER.info(String.format("Failure to find common block with peer %s", peer));
				return SynchronizationResult.NO_COMMON_BLOCK;
			}

			// If common block is too far behind us then we're on massively different forks so give up.
			if (!force && testHeight < ourHeight - MAXIMUM_COMMON_DELTA) {
				LOGGER.info(String.format("Blockchain too divergent with peer %s", peer));
				return SynchronizationResult.TOO_DIVERGENT;
			}

			step <<= 1;
			step = Math.min(step, MAXIMUM_BLOCK_STEP);

			testHeight = Math.max(testHeight - step, 1);
		}

		// Prepend test block's summary as first block summary, as summaries returned are *after* test block
		BlockSummaryData testBlockSummary = new BlockSummaryData(testBlockData);
		blockSummariesFromCommon.add(0, testBlockSummary);
		blockSummariesFromCommon.addAll(blockSummariesBatch);

		// Trim summaries so that first summary is common block.
		// Currently we work forward from common block until we hit a block we don't have
		// TODO: rewrite as modified binary search!
		int i;
		for (i = 1; i < blockSummariesFromCommon.size(); ++i) {
			if (Controller.isStopping())
				return SynchronizationResult.SHUTTING_DOWN;

			if (!repository.getBlockRepository().exists(blockSummariesFromCommon.get(i).getSignature()))
				break;
		}

		// Note: index i - 1 isn't cleared: List.subList is fromIndex inclusive to toIndex exclusive
		blockSummariesFromCommon.subList(0, i - 1).clear();

		return SynchronizationResult.OK;
	}

	private SynchronizationResult compareChains(Repository repository, BlockData commonBlockData, BlockData ourLatestBlockData,
			Peer peer, int peerHeight, List<BlockSummaryData> peerBlockSummaries) throws DataException, InterruptedException {
		final int commonBlockHeight = commonBlockData.getHeight();
		final byte[] commonBlockSig = commonBlockData.getSignature();

		// If our latest block is very old, we're very behind and should ditch our fork.
		final Long minLatestBlockTimestamp = Controller.getMinimumLatestBlockTimestamp();
		if (minLatestBlockTimestamp == null)
			return SynchronizationResult.REPOSITORY_ISSUE;

		if (ourLatestBlockData.getTimestamp() < minLatestBlockTimestamp) {
			LOGGER.info(String.format("Ditching our chain after height %d as our latest block is very old", commonBlockHeight));
		} else {
			// Compare chain weights

			LOGGER.debug(String.format("Comparing chains from block %d with peer %s", commonBlockHeight + 1, peer));

			// Fetch remaining peer's block summaries (which we also use to fill signatures list)
			int peerBlockCount = peerHeight - commonBlockHeight;

			while (peerBlockSummaries.size() < peerBlockCount) {
				if (Controller.isStopping())
					return SynchronizationResult.SHUTTING_DOWN;

				int lastSummaryHeight = commonBlockHeight + peerBlockSummaries.size();
				byte[] previousSignature;
				if (peerBlockSummaries.isEmpty())
					previousSignature = commonBlockSig;
				else
					previousSignature = peerBlockSummaries.get(peerBlockSummaries.size() - 1).getSignature();

				List<BlockSummaryData> moreBlockSummaries = this.getBlockSummaries(peer, previousSignature, peerBlockCount - peerBlockSummaries.size());

				if (moreBlockSummaries == null || moreBlockSummaries.isEmpty()) {
					LOGGER.info(String.format("Peer %s failed to respond with block summaries after height %d, sig %.8s", peer,
							lastSummaryHeight, Base58.encode(previousSignature)));
					return SynchronizationResult.NO_REPLY;
				}

				// Check peer sent valid heights
				for (int i = 0; i < moreBlockSummaries.size(); ++i) {
					if (Controller.isStopping())
						return SynchronizationResult.SHUTTING_DOWN;

					++lastSummaryHeight;

					BlockSummaryData blockSummary = moreBlockSummaries.get(i);

					if (blockSummary.getHeight() != lastSummaryHeight) {
						LOGGER.info(String.format("Peer %s responded with invalid block summary for height %d, sig %.8s", peer,
								lastSummaryHeight, Base58.encode(blockSummary.getSignature())));
						return SynchronizationResult.NO_REPLY;
					}
				}

				peerBlockSummaries.addAll(moreBlockSummaries);
			}

			// Fetch our corresponding block summaries
			List<BlockSummaryData> ourBlockSummaries = repository.getBlockRepository().getBlockSummaries(commonBlockHeight + 1, ourLatestBlockData.getHeight());

			// Populate minter account levels for both lists of block summaries
			populateBlockSummariesMinterLevels(repository, ourBlockSummaries);
			populateBlockSummariesMinterLevels(repository, peerBlockSummaries);

			final int mutualHeight = commonBlockHeight + Math.min(ourBlockSummaries.size(), peerBlockSummaries.size());

			// Calculate cumulative chain weights of both blockchain subsets, from common block to highest mutual block.
			BigInteger ourChainWeight = Block.calcChainWeight(commonBlockHeight, commonBlockSig, ourBlockSummaries, mutualHeight);
			BigInteger peerChainWeight = Block.calcChainWeight(commonBlockHeight, commonBlockSig, peerBlockSummaries, mutualHeight);

			NumberFormat formatter = new DecimalFormat("0.###E0");
			LOGGER.debug(String.format("Our chain weight: %s, peer's chain weight: %s (higher is better)", formatter.format(ourChainWeight), formatter.format(peerChainWeight)));

			// If our blockchain has greater weight then don't synchronize with peer
			if (ourChainWeight.compareTo(peerChainWeight) >= 0) {
				LOGGER.debug(String.format("Not synchronizing with peer %s as we have better blockchain", peer));
				return SynchronizationResult.INFERIOR_CHAIN;
			}
		}

		return SynchronizationResult.OK;
	}

	private SynchronizationResult syncToPeerChain(Repository repository, BlockData commonBlockData, int ourInitialHeight,
			Peer peer, final int peerHeight, List<BlockSummaryData> peerBlockSummaries) throws DataException, InterruptedException {
		final int commonBlockHeight = commonBlockData.getHeight();
		final byte[] commonBlockSig = commonBlockData.getSignature();
		String commonBlockSig58 = Base58.encode(commonBlockSig);

		byte[] latestPeerSignature = commonBlockSig;
		int height = commonBlockHeight;

		LOGGER.debug(() -> String.format("Fetching peer %s chain from height %d, sig %.8s", peer, commonBlockHeight, commonBlockSig58));

		final int maxRetries = Settings.getInstance().getMaxRetries();

		// Overall plan: fetch peer's blocks first, then orphan, then apply

		// Convert any leftover (post-common) block summaries into signatures to request from peer
		List<byte[]> peerBlockSignatures = peerBlockSummaries.stream().map(BlockSummaryData::getSignature).collect(Collectors.toList());

		// Keep a list of blocks received so far
		List<Block> peerBlocks = new ArrayList<>();

		// Calculate the total number of additional blocks this peer has beyond the common block
		int additionalPeerBlocksAfterCommonBlock = peerHeight - commonBlockHeight;
		// Subtract the number of signatures that we already have, as we don't need to request them again
		int numberSignaturesRequired = additionalPeerBlocksAfterCommonBlock - peerBlockSignatures.size();

		int retryCount = 0;
		while (height < peerHeight) {
			if (Controller.isStopping())
				return SynchronizationResult.SHUTTING_DOWN;

            // Ensure we don't request more than MAXIMUM_REQUEST_SIZE
            int numberRequested = Math.min(numberSignaturesRequired, MAXIMUM_REQUEST_SIZE);

            // Do we need more signatures?
			if (peerBlockSignatures.isEmpty() && numberRequested > 0) {
                LOGGER.trace(String.format("Requesting %d signature%s after height %d, sig %.8s",
                        numberRequested, (numberRequested != 1 ? "s" : ""), height, Base58.encode(latestPeerSignature)));

                peerBlockSignatures = this.getBlockSignatures(peer, latestPeerSignature, numberRequested);

                if (peerBlockSignatures == null || peerBlockSignatures.isEmpty()) {
                    LOGGER.info(String.format("Peer %s failed to respond with more block signatures after height %d, sig %.8s", peer,
                            height, Base58.encode(latestPeerSignature)));

					// Clear our cache of common block summaries for this peer, as they are likely to be invalid
					CommonBlockData cachedCommonBlockData = peer.getCommonBlockData();
					if (cachedCommonBlockData != null)
						cachedCommonBlockData.setBlockSummariesAfterCommonBlock(null);

                    // If we have already received newer blocks from this peer that what we have already, go ahead and apply them
                    if (peerBlocks.size() > 0) {
						final BlockData ourLatestBlockData = repository.getBlockRepository().getLastBlock();
                    	final Block peerLatestBlock = peerBlocks.get(peerBlocks.size() - 1);
						final Long minLatestBlockTimestamp = Controller.getMinimumLatestBlockTimestamp();
						if (ourLatestBlockData != null && peerLatestBlock != null && minLatestBlockTimestamp != null) {

							// If our latest block is very old....
							if (ourLatestBlockData.getTimestamp() < minLatestBlockTimestamp) {
								// ... and we have received a block that is more recent than our latest block ...
								if (peerLatestBlock.getBlockData().getTimestamp() > ourLatestBlockData.getTimestamp()) {
									// ... then apply the blocks, as it takes us a step forward.
									// This is particularly useful when starting up a node that was on a small fork when it was last shut down.
									// In these cases, we now allow the node to sync forward, and get onto the main chain again.
									// Without this, we would require that the node syncs ENTIRELY with this peer,
									// and any problems downloading a block would cause all progress to be lost.
									LOGGER.debug(String.format("Newly received blocks are %d ms newer than our latest block - so we will apply them", peerLatestBlock.getBlockData().getTimestamp() - ourLatestBlockData.getTimestamp()));
									break;
								}
							}
						}
                    }
					// Otherwise, give up and move on to the next peer, to avoid putting our chain into an outdated or incomplete state
                    return SynchronizationResult.NO_REPLY;
                }

                numberSignaturesRequired = peerHeight - height - peerBlockSignatures.size();
                LOGGER.trace(String.format("Received %s signature%s", peerBlockSignatures.size(), (peerBlockSignatures.size() != 1 ? "s" : "")));
			}

			if (peerBlockSignatures.isEmpty()) {
				LOGGER.trace(String.format("No more signatures or blocks to request from peer %s", peer));
				break;
			}

			byte[] nextPeerSignature = peerBlockSignatures.get(0);
			int nextHeight = height + 1;

			LOGGER.trace(String.format("Fetching block %d, sig %.8s from %s", nextHeight, Base58.encode(nextPeerSignature), peer));
			Block newBlock = this.fetchBlock(repository, peer, nextPeerSignature);

			if (newBlock == null) {
				LOGGER.info(String.format("Peer %s failed to respond with block for height %d, sig %.8s", peer,
						nextHeight, Base58.encode(nextPeerSignature)));

				if (retryCount >= maxRetries) {
					// If we have already received newer blocks from this peer that what we have already, go ahead and apply them
					if (peerBlocks.size() > 0) {
						final BlockData ourLatestBlockData = repository.getBlockRepository().getLastBlock();
						final Block peerLatestBlock = peerBlocks.get(peerBlocks.size() - 1);
						final Long minLatestBlockTimestamp = Controller.getMinimumLatestBlockTimestamp();
						if (ourLatestBlockData != null && peerLatestBlock != null && minLatestBlockTimestamp != null) {

							// If our latest block is very old....
							if (ourLatestBlockData.getTimestamp() < minLatestBlockTimestamp) {
								// ... and we have received a block that is more recent than our latest block ...
								if (peerLatestBlock.getBlockData().getTimestamp() > ourLatestBlockData.getTimestamp()) {
									// ... then apply the blocks, as it takes us a step forward.
									// This is particularly useful when starting up a node that was on a small fork when it was last shut down.
									// In these cases, we now allow the node to sync forward, and get onto the main chain again.
									// Without this, we would require that the node syncs ENTIRELY with this peer,
									// and any problems downloading a block would cause all progress to be lost.
									LOGGER.debug(String.format("Newly received blocks are %d ms newer than our latest block - so we will apply them", peerLatestBlock.getBlockData().getTimestamp() - ourLatestBlockData.getTimestamp()));
									break;
								}
							}
						}
					}
					// Otherwise, give up and move on to the next peer, to avoid putting our chain into an outdated or incomplete state
					return SynchronizationResult.NO_REPLY;

				} else {
					// Re-fetch signatures, in case the peer is now on a different fork
					peerBlockSignatures.clear();
					numberSignaturesRequired = peerHeight - height;

					// Retry until retryCount reaches maxRetries
					retryCount++;
					int triesRemaining = maxRetries - retryCount;
					LOGGER.info(String.format("Re-issuing request to peer %s (%d attempt%s remaining)", peer, triesRemaining, (triesRemaining != 1 ? "s" : "")));
					continue;
				}
			}

			// Reset retryCount because the last request succeeded
			retryCount = 0;

			LOGGER.trace(String.format("Fetched block %d, sig %.8s from %s", nextHeight, Base58.encode(latestPeerSignature), peer));

			if (!newBlock.isSignatureValid()) {
				LOGGER.info(String.format("Peer %s sent block with invalid signature for height %d, sig %.8s", peer,
						nextHeight, Base58.encode(latestPeerSignature)));
				return SynchronizationResult.INVALID_DATA;
			}

			// Transactions are transmitted without approval status so determine that now
			for (Transaction transaction : newBlock.getTransactions())
				transaction.setInitialApprovalStatus();

			peerBlocks.add(newBlock);

			// Now that we've received this block, we can increase our height and move on to the next one
			latestPeerSignature = nextPeerSignature;
			peerBlockSignatures.remove(0);
			++height;
		}

		// Unwind to common block (unless common block is our latest block)
		int ourHeight = ourInitialHeight;
		LOGGER.debug(String.format("Orphaning blocks back to common block height %d, sig %.8s. Our height: %d", commonBlockHeight, commonBlockSig58, ourHeight));
		int reorgSize = ourHeight - commonBlockHeight;

		BlockData orphanBlockData = repository.getBlockRepository().fromHeight(ourInitialHeight);
		while (ourHeight > commonBlockHeight) {
			if (Controller.isStopping())
				return SynchronizationResult.SHUTTING_DOWN;

			Block block = new Block(repository, orphanBlockData);
			block.orphan();

			LOGGER.trace(String.format("Orphaned block height %d, sig %.8s", ourHeight, Base58.encode(orphanBlockData.getSignature())));

			repository.saveChanges();

			--ourHeight;
			orphanBlockData = repository.getBlockRepository().fromHeight(ourHeight);

			repository.discardChanges(); // clear transaction status to prevent deadlocks
			Controller.getInstance().onOrphanedBlock(orphanBlockData);
		}

		LOGGER.debug(String.format("Orphaned blocks back to height %d, sig %.8s - applying new blocks from peer %s", commonBlockHeight, commonBlockSig58, peer));

		for (Block newBlock : peerBlocks) {
			if (Controller.isStopping())
				return SynchronizationResult.SHUTTING_DOWN;

			ValidationResult blockResult = newBlock.isValid();
			if (blockResult != ValidationResult.OK) {
				LOGGER.info(String.format("Peer %s sent invalid block for height %d, sig %.8s: %s", peer,
						newBlock.getBlockData().getHeight(), Base58.encode(newBlock.getSignature()), blockResult.name()));
				return SynchronizationResult.INVALID_DATA;
			}

			// Save transactions attached to this block
			for (Transaction transaction : newBlock.getTransactions()) {
				TransactionData transactionData = transaction.getTransactionData();
				repository.getTransactionRepository().save(transactionData);
			}

			newBlock.process();

			LOGGER.trace(String.format("Processed block height %d, sig %.8s", newBlock.getBlockData().getHeight(), Base58.encode(newBlock.getBlockData().getSignature())));

			repository.saveChanges();

			Controller.getInstance().onNewBlock(newBlock.getBlockData());
		}

		this.lastReorgSize = reorgSize;
		return SynchronizationResult.OK;
	}

	private SynchronizationResult applyNewBlocks(Repository repository, BlockData commonBlockData, int ourInitialHeight,
			Peer peer, int peerHeight, List<BlockSummaryData> peerBlockSummaries) throws InterruptedException, DataException {
		LOGGER.debug(String.format("Fetching new blocks from peer %s", peer));

		final int commonBlockHeight = commonBlockData.getHeight();
		final byte[] commonBlockSig = commonBlockData.getSignature();

		int ourHeight = ourInitialHeight;

		// Fetch, and apply, blocks from peer
		byte[] latestPeerSignature = commonBlockSig;
		int maxBatchHeight = commonBlockHeight + SYNC_BATCH_SIZE;

		// Convert any block summaries from above into signatures to request from peer
		List<byte[]> peerBlockSignatures = peerBlockSummaries.stream().map(BlockSummaryData::getSignature).collect(Collectors.toList());

		while (ourHeight < peerHeight && ourHeight < maxBatchHeight) {
			if (Controller.isStopping())
				return SynchronizationResult.SHUTTING_DOWN;

			// Do we need more signatures?
			if (peerBlockSignatures.isEmpty()) {
				int numberRequested = Math.min(maxBatchHeight - ourHeight, MAXIMUM_REQUEST_SIZE);

				LOGGER.trace(String.format("Requesting %d signature%s after height %d, sig %.8s",
						numberRequested, (numberRequested != 1 ? "s": ""), ourHeight, Base58.encode(latestPeerSignature)));

				peerBlockSignatures = this.getBlockSignatures(peer, latestPeerSignature, numberRequested);

				if (peerBlockSignatures == null || peerBlockSignatures.isEmpty()) {
					LOGGER.info(String.format("Peer %s failed to respond with more block signatures after height %d, sig %.8s", peer,
							ourHeight, Base58.encode(latestPeerSignature)));
					return SynchronizationResult.NO_REPLY;
				}

				LOGGER.trace(String.format("Received %s signature%s", peerBlockSignatures.size(), (peerBlockSignatures.size() != 1 ? "s" : "")));
			}

			latestPeerSignature = peerBlockSignatures.get(0);
			peerBlockSignatures.remove(0);
			++ourHeight;

			LOGGER.trace(String.format("Fetching block %d, sig %.8s from %s", ourHeight, Base58.encode(latestPeerSignature), peer));
			Block newBlock = this.fetchBlock(repository, peer, latestPeerSignature);
			LOGGER.trace(String.format("Fetched block %d, sig %.8s from %s", ourHeight, Base58.encode(latestPeerSignature), peer));

			if (newBlock == null) {
				LOGGER.info(String.format("Peer %s failed to respond with block for height %d, sig %.8s", peer,
						ourHeight, Base58.encode(latestPeerSignature)));
				return SynchronizationResult.NO_REPLY;
			}

			if (!newBlock.isSignatureValid()) {
				LOGGER.info(String.format("Peer %s sent block with invalid signature for height %d, sig %.8s", peer,
						ourHeight, Base58.encode(latestPeerSignature)));
				return SynchronizationResult.INVALID_DATA;
			}

			// Transactions are transmitted without approval status so determine that now
			for (Transaction transaction : newBlock.getTransactions())
				transaction.setInitialApprovalStatus();

			ValidationResult blockResult = newBlock.isValid();
			if (blockResult != ValidationResult.OK) {
				LOGGER.info(String.format("Peer %s sent invalid block for height %d, sig %.8s: %s", peer,
						ourHeight, Base58.encode(latestPeerSignature), blockResult.name()));
				return SynchronizationResult.INVALID_DATA;
			}

			// Save transactions attached to this block
			for (Transaction transaction : newBlock.getTransactions()) {
				TransactionData transactionData = transaction.getTransactionData();
				repository.getTransactionRepository().save(transactionData);
			}

			newBlock.process();

			LOGGER.trace(String.format("Processed block height %d, sig %.8s", newBlock.getBlockData().getHeight(), Base58.encode(newBlock.getBlockData().getSignature())));

			repository.saveChanges();

			Controller.getInstance().onNewBlock(newBlock.getBlockData());
		}

		return SynchronizationResult.OK;
	}

	private List<BlockSummaryData> getBlockSummaries(Peer peer, byte[] parentSignature, int numberRequested) throws InterruptedException {
		Message getBlockSummariesMessage = new GetBlockSummariesMessage(parentSignature, numberRequested);

		Message message = peer.getResponse(getBlockSummariesMessage);
		if (message == null || message.getType() != MessageType.BLOCK_SUMMARIES)
			return null;

		BlockSummariesMessage blockSummariesMessage = (BlockSummariesMessage) message;

		return blockSummariesMessage.getBlockSummaries();
	}

	private List<byte[]> getBlockSignatures(Peer peer, byte[] parentSignature, int numberRequested) throws InterruptedException {
		Message getSignaturesMessage = new GetSignaturesV2Message(parentSignature, numberRequested);

		Message message = peer.getResponse(getSignaturesMessage);
		if (message == null || message.getType() != MessageType.SIGNATURES)
			return null;

		SignaturesMessage signaturesMessage = (SignaturesMessage) message;

		return signaturesMessage.getSignatures();
	}

	private Block fetchBlock(Repository repository, Peer peer, byte[] signature) throws InterruptedException {
		Message getBlockMessage = new GetBlockMessage(signature);

		Message message = peer.getResponse(getBlockMessage);
		if (message == null || message.getType() != MessageType.BLOCK)
			return null;

		BlockMessage blockMessage = (BlockMessage) message;

		return new Block(repository, blockMessage.getBlockData(), blockMessage.getTransactions(), blockMessage.getAtStates());
	}

	private void populateBlockSummariesMinterLevels(Repository repository, List<BlockSummaryData> blockSummaries) throws DataException {
		final int firstBlockHeight = blockSummaries.get(0).getHeight();

		for (int i = 0; i < blockSummaries.size(); ++i) {
			if (Controller.isStopping())
				return;

			BlockSummaryData blockSummary = blockSummaries.get(i);

			// Qortal: minter is always a reward-share, so find actual minter and get their effective minting level
			int minterLevel = Account.getRewardShareEffectiveMintingLevel(repository, blockSummary.getMinterPublicKey());
			if (minterLevel == 0) {
				// It looks like this block's minter's reward-share has been cancelled.
				// So search for REWARD_SHARE transactions since common block to find missing minter info
				List<byte[]> transactionSignatures = repository.getTransactionRepository().getSignaturesMatchingCriteria(Transaction.TransactionType.REWARD_SHARE, null, firstBlockHeight, null);

				for (byte[] transactionSignature : transactionSignatures) {
					RewardShareTransactionData transactionData = (RewardShareTransactionData) repository.getTransactionRepository().fromSignature(transactionSignature);

					if (transactionData != null && Arrays.equals(transactionData.getRewardSharePublicKey(), blockSummary.getMinterPublicKey())) {
						Account rewardShareMinter = new PublicKeyAccount(repository, transactionData.getMinterPublicKey());
						minterLevel = rewardShareMinter.getEffectiveMintingLevel();
						break;
					}
				}

				if (minterLevel == 0) {
					// We don't want to throw, or use zero, as this will kill Controller thread and make client unstable.
					// So we log this but use 1 instead
					LOGGER.debug(() -> String.format("Unexpected zero effective minter level for reward-share %s - using 1 instead!", Base58.encode(blockSummary.getMinterPublicKey())));
					minterLevel = 1;
				}
			}

			blockSummary.setMinterLevel(minterLevel);
		}
	}

}
