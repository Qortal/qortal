package org.qortal.controller;

import com.google.common.hash.HashCode;
import com.google.common.primitives.Longs;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.account.Account;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.block.Block;
import org.qortal.block.BlockChain;
import org.qortal.crypto.Crypto;
import org.qortal.crypto.MemoryPoW;
import org.qortal.crypto.Qortal25519Extras;
import org.qortal.data.account.MintingAccountData;
import org.qortal.data.account.RewardShareData;
import org.qortal.data.group.GroupMemberData;
import org.qortal.data.network.OnlineAccountData;
import org.qortal.network.Network;
import org.qortal.network.Peer;
import org.qortal.network.message.GetOnlineAccountsV3Message;
import org.qortal.network.message.Message;
import org.qortal.network.message.OnlineAccountsV3Message;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.settings.Settings;
import org.qortal.utils.Base58;
import org.qortal.utils.Groups;
import org.qortal.utils.NTP;
import org.qortal.utils.NamedThreadFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

public class OnlineAccountsManager {

    private static final Logger LOGGER = LogManager.getLogger(OnlineAccountsManager.class);

    // 'Current' as in 'now'

    /**
     * How long online accounts signatures last before they expire.
     */
    private static final long ONLINE_TIMESTAMP_MODULUS_V1 = 5 * 60 * 1000L;
    private static final long ONLINE_TIMESTAMP_MODULUS_V2 = 30 * 60 * 1000L;
    private static final long ONLINE_TIMESTAMP_MODULUS_V3 = 10 * 60 * 1000L;

    /**
     * How many 'current' timestamp-sets of online accounts we cache.
     */
    private static final int MAX_CACHED_TIMESTAMP_SETS = 2;

    /**
     * How many timestamp-sets of online accounts we cache for 'latest blocks'.
     */
    private static final int MAX_BLOCKS_CACHED_ONLINE_ACCOUNTS = 3;

    private static final long ONLINE_ACCOUNTS_QUEUE_INTERVAL = 100L; // ms
    private static final long ONLINE_ACCOUNTS_TASKS_INTERVAL = 10 * 1000L; // ms
    private static final long ONLINE_ACCOUNTS_COMPUTE_INTERVAL = 5 * 1000L; // ms
    private static final long ONLINE_ACCOUNTS_BROADCAST_INTERVAL = 60 * 1000L; // ms
    // After switching to a new online timestamp, we "burst" the online accounts requests
    // at an increased interval for a specified amount of time
    private static final long ONLINE_ACCOUNTS_BROADCAST_BURST_INTERVAL = 5 * 1000L; // ms
    private static final long ONLINE_ACCOUNTS_BROADCAST_BURST_LENGTH = 5 * 60 * 1000L; // ms

    private static final long ONLINE_ACCOUNTS_COMPUTE_INITIAL_SLEEP_INTERVAL = 30 * 1000L; // ms

    // MemoryPoW - mainnet
    public static final int POW_BUFFER_SIZE = 1024 * 1024; // bytes
    public static final int POW_DIFFICULTY_V1 = 18; // leading zero bits
    public static final int POW_DIFFICULTY_V2 = 19; // leading zero bits
    public static final int POW_DIFFICULTY_V3 = 6; // leading zero bits

    // MemoryPoW - testnet
    public static final int POW_BUFFER_SIZE_TESTNET = 1024 * 1024; // bytes
    public static final int POW_DIFFICULTY_TESTNET = 5; // leading zero bits

    // IMPORTANT: if we ever need to dynamically modify the buffer size using a feature trigger, the
    // pre-allocated buffer below will NOT work, and we should instead use a dynamically allocated
    // one for the transition period.
    private static long[] POW_VERIFY_WORK_BUFFER = new long[getPoWBufferSize() / 8];

    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(4, new NamedThreadFactory("OnlineAccounts", Thread.NORM_PRIORITY));
    private volatile boolean isStopping = false;

    private final Set<OnlineAccountData> onlineAccountsImportQueue = ConcurrentHashMap.newKeySet();

    /**
     * Cache of 'current' online accounts, keyed by timestamp
     */
    private final Map<Long, Set<OnlineAccountData>> currentOnlineAccounts = new ConcurrentHashMap<>();
    /**
     * Cache of hash-summary of 'current' online accounts, keyed by timestamp, then leading byte of public key.
     */
    private final Map<Long, Map<Byte, byte[]>> currentOnlineAccountsHashes = new ConcurrentHashMap<>();

    /**
     * Cache of online accounts for latest blocks - not necessarily 'current' / now.
     * <i>Probably</i> only accessed / modified by a single Synchronizer thread.
     */
    private final SortedMap<Long, Set<OnlineAccountData>> latestBlocksOnlineAccounts = new ConcurrentSkipListMap<>();

    private long lastOnlineAccountsRequest = 0;

    private boolean hasOurOnlineAccounts = false;

    public static long getOnlineTimestampModulus() {
        Long now = NTP.getTime();
        if (now != null && now >= BlockChain.getInstance().getOnlineAccountsModulusV2Timestamp() && now < BlockChain.getInstance().getOnlineAccountsModulusV3Timestamp()) {
            return ONLINE_TIMESTAMP_MODULUS_V2;
        }
        if (now != null && now >= BlockChain.getInstance().getOnlineAccountsModulusV3Timestamp()) {
            return ONLINE_TIMESTAMP_MODULUS_V3;
        }
        return ONLINE_TIMESTAMP_MODULUS_V1;
    }

    public static Long getCurrentOnlineAccountTimestamp() {
        Long now = NTP.getTime();
        if (now == null)
            return null;

        long onlineTimestampModulus = getOnlineTimestampModulus();
        return (now / onlineTimestampModulus) * onlineTimestampModulus;
    }

    public static long toOnlineAccountTimestamp(long timestamp) {
        return (timestamp / getOnlineTimestampModulus()) * getOnlineTimestampModulus();
    }

    private static int getPoWBufferSize() {
        if (Settings.getInstance().isTestNet())
            return POW_BUFFER_SIZE_TESTNET;

        return POW_BUFFER_SIZE;
    }

    private static int getPoWDifficulty(long timestamp) {
        if (Settings.getInstance().isTestNet())
            return POW_DIFFICULTY_TESTNET;

        if (timestamp >= BlockChain.getInstance().getIncreaseOnlineAccountsDifficultyTimestamp() && timestamp < BlockChain.getInstance().getDecreaseOnlineAccountsDifficultyTimestamp())
            return POW_DIFFICULTY_V2;

        if (timestamp >= BlockChain.getInstance().getDecreaseOnlineAccountsDifficultyTimestamp())
            return POW_DIFFICULTY_V3;

        return POW_DIFFICULTY_V1;
    }

    private OnlineAccountsManager() {
    }

    private static class SingletonContainer {
        private static final OnlineAccountsManager INSTANCE = new OnlineAccountsManager();
    }

    public static OnlineAccountsManager getInstance() {
        return SingletonContainer.INSTANCE;
    }

    public void start() {
        // Expire old online accounts signatures
        executor.scheduleAtFixedRate(this::expireOldOnlineAccounts, ONLINE_ACCOUNTS_TASKS_INTERVAL, ONLINE_ACCOUNTS_TASKS_INTERVAL, TimeUnit.MILLISECONDS);

        // Request online accounts from peers
        executor.scheduleAtFixedRate(this::requestRemoteOnlineAccounts, ONLINE_ACCOUNTS_BROADCAST_BURST_INTERVAL, ONLINE_ACCOUNTS_BROADCAST_BURST_INTERVAL, TimeUnit.MILLISECONDS);

        // Process import queue
        executor.scheduleWithFixedDelay(this::processOnlineAccountsImportQueue, ONLINE_ACCOUNTS_QUEUE_INTERVAL, ONLINE_ACCOUNTS_QUEUE_INTERVAL, TimeUnit.MILLISECONDS);

        // Send our online accounts (using increased initial delay)
        // This allows some time for initial online account lists to be retrieved, and
        // reduces the chances of the same nonce being computed twice
        executor.scheduleAtFixedRate(this::sendOurOnlineAccountsInfo, ONLINE_ACCOUNTS_COMPUTE_INITIAL_SLEEP_INTERVAL, ONLINE_ACCOUNTS_COMPUTE_INTERVAL, TimeUnit.MILLISECONDS);
    }

    public void shutdown() {
        isStopping = true;
        executor.shutdownNow();
    }

    // Testing support
    public void ensureTestingAccountsOnline(PrivateKeyAccount... onlineAccounts) {
        if (!BlockChain.getInstance().isTestChain()) {
            LOGGER.warn("Ignoring attempt to ensure test account is online for non-test chain!");
            return;
        }

        final Long onlineAccountsTimestamp = getCurrentOnlineAccountTimestamp();
        if (onlineAccountsTimestamp == null)
            return;

        byte[] timestampBytes = Longs.toByteArray(onlineAccountsTimestamp);

        Set<OnlineAccountData> replacementAccounts = new HashSet<>();
        for (PrivateKeyAccount onlineAccount : onlineAccounts) {
            // Check mintingAccount is actually reward-share?

            byte[] signature = Qortal25519Extras.signForAggregation(onlineAccount.getPrivateKey(), timestampBytes);
            byte[] publicKey = onlineAccount.getPublicKey();

            Integer nonce = new Random().nextInt(500000);

            OnlineAccountData ourOnlineAccountData = new OnlineAccountData(onlineAccountsTimestamp, signature, publicKey, nonce);
            replacementAccounts.add(ourOnlineAccountData);
        }

        this.currentOnlineAccounts.clear();
        addAccounts(replacementAccounts);
    }

    // Online accounts import queue

    private void processOnlineAccountsImportQueue() {
        if (this.onlineAccountsImportQueue.isEmpty())
            // Nothing to do
            return;

        LOGGER.debug("Processing online accounts import queue (size: {})", this.onlineAccountsImportQueue.size());

        Set<OnlineAccountData> onlineAccountsToAdd = new HashSet<>();
        Set<OnlineAccountData> onlineAccountsToRemove = new HashSet<>();
        try (final Repository repository = RepositoryManager.getRepository()) {

            int blockHeight = repository.getBlockRepository().getBlockchainHeight();

            List<String> mintingGroupMemberAddresses
                = Groups.getAllMembers(
                    repository.getGroupRepository(),
                    Groups.getGroupIdsToMint(BlockChain.getInstance(), blockHeight)
            );

            for (OnlineAccountData onlineAccountData : this.onlineAccountsImportQueue) {
                if (isStopping)
                    return;

                // Skip this account if it's already validated
                Set<OnlineAccountData> onlineAccounts = this.currentOnlineAccounts.get(onlineAccountData.getTimestamp());
                if (onlineAccounts != null && onlineAccounts.contains(onlineAccountData)) {
                    // We have already validated this online account
                    onlineAccountsImportQueue.remove(onlineAccountData);
                    continue;
                }

                boolean isValid = this.isValidCurrentAccount(repository, mintingGroupMemberAddresses, onlineAccountData);
                if (isValid)
                    onlineAccountsToAdd.add(onlineAccountData);

                // Don't remove from the queue yet - we'll do this at the end of the process
                // This prevents duplicates being added to the queue whilst it's being processed
                onlineAccountsToRemove.add(onlineAccountData);
            }
        } catch (DataException e) {
            LOGGER.error("Repository issue while verifying online accounts", e);

        } finally {
            if (!onlineAccountsToAdd.isEmpty()) {
                LOGGER.debug("Merging {} validated online accounts from import queue", onlineAccountsToAdd.size());
                addAccounts(onlineAccountsToAdd);
            }
            onlineAccountsImportQueue.removeAll(onlineAccountsToRemove);
        }
    }

    /**
     * Check if supplied onlineAccountData is superior (i.e. has a nonce value) than existing record.
     * Two entries are considered equal even if the nonce differs, to prevent multiple variations
     * co-existing. For this reason, we need to be able to check if a new OnlineAccountData entry should
     * replace the existing one, which may be missing the nonce.
     * @param onlineAccountData
     * @return true if supplied data is superior to existing entry
     */
    private boolean isOnlineAccountsDataSuperior(OnlineAccountData onlineAccountData) {
        if (onlineAccountData.getNonce() == null || onlineAccountData.getNonce() < 0) {
            // New online account data has no usable nonce value, so it won't be better than anything we already have
            return false;
        }

        // New online account data has a nonce value, so check if there is any existing data to compare against
        Set<OnlineAccountData> existingOnlineAccountsForTimestamp = this.currentOnlineAccounts.get(onlineAccountData.getTimestamp());
        if (existingOnlineAccountsForTimestamp == null) {
            // No existing online accounts data with this timestamp yet
            return false;
        }

        // Check if a duplicate entry exists
        OnlineAccountData existingOnlineAccountData = null;
        for (OnlineAccountData existingAccount : existingOnlineAccountsForTimestamp) {
            if (existingAccount.equals(onlineAccountData)) {
                // Found existing online account data
                existingOnlineAccountData = existingAccount;
                break;
            }
        }

        if (existingOnlineAccountData == null) {
            // No existing online accounts data, so nothing to compare
            return false;
        }

        if (existingOnlineAccountData.getNonce() == null || existingOnlineAccountData.getNonce() < 0) {
            // Existing data has no usable nonce value(s) so we want to replace it with the new one
            return true;
        }

        // Both new and old data have nonce values so the new data isn't considered superior
        return false;
    }


    // Utilities

    public static byte[] xorByteArrayInPlace(byte[] inplaceArray, byte[] otherArray) {
        if (inplaceArray == null)
            return Arrays.copyOf(otherArray, otherArray.length);

        // Start from index 1 to enforce static leading byte
        for (int i = 1; i < otherArray.length; i++)
            inplaceArray[i] ^= otherArray[i];

        return inplaceArray;
    }

    private static boolean isValidCurrentAccount(Repository repository, List<String> mintingGroupMemberAddresses, OnlineAccountData onlineAccountData) throws DataException {
        final Long now = NTP.getTime();
        if (now == null)
            return false;

        byte[] rewardSharePublicKey = onlineAccountData.getPublicKey();
        long onlineAccountTimestamp = onlineAccountData.getTimestamp();

        // Check timestamp is 'recent' here
        if (Math.abs(onlineAccountTimestamp - now) > getOnlineTimestampModulus() * 2) {
            LOGGER.trace(() -> String.format("Rejecting online account %s with out of range timestamp %d", Base58.encode(rewardSharePublicKey), onlineAccountTimestamp));
            return false;
        }

        // Check timestamp is a multiple of online timestamp modulus
        if (onlineAccountTimestamp % getOnlineTimestampModulus() != 0) {
            LOGGER.trace(() -> String.format("Rejecting online account %s with invalid timestamp %d", Base58.encode(rewardSharePublicKey), onlineAccountTimestamp));
            return false;
        }

        // Verify signature
        byte[] data = Longs.toByteArray(onlineAccountData.getTimestamp());
        boolean isSignatureValid = Qortal25519Extras.verifyAggregated(rewardSharePublicKey, onlineAccountData.getSignature(), data);
        if (!isSignatureValid) {
            LOGGER.trace(() -> String.format("Rejecting invalid online account %s", Base58.encode(rewardSharePublicKey)));
            return false;
        }

        // Qortal: check online account is actually reward-share
        RewardShareData rewardShareData = repository.getAccountRepository().getRewardShare(rewardSharePublicKey);
        if (rewardShareData == null) {
            // Reward-share doesn't even exist - probably not a good sign
            LOGGER.trace(() -> String.format("Rejecting unknown online reward-share public key %s", Base58.encode(rewardSharePublicKey)));
            return false;
        }
        // reject account address that are not in the MINTER Group
        else if( !mintingGroupMemberAddresses.contains(rewardShareData.getMinter())) {
            LOGGER.trace(() -> String.format("Rejecting online reward-share that is not in MINTER Group, account %s", rewardShareData.getMinter()));
            return false;
        }

        Account mintingAccount = new Account(repository, rewardShareData.getMinter());
        if (!mintingAccount.canMint(true)) {  // group validation is a few lines above
            // Minting-account component of reward-share can no longer mint - disregard
            LOGGER.trace(() -> String.format("Rejecting online reward-share with non-minting account %s", mintingAccount.getAddress()));
            return false;
        }

        // Validate mempow
        if (!getInstance().verifyMemoryPoW(onlineAccountData, POW_VERIFY_WORK_BUFFER)) {
            LOGGER.trace(() -> String.format("Rejecting online reward-share for account %s due to invalid PoW nonce", mintingAccount.getAddress()));
            return false;
        }

        return true;
    }

    /** Adds accounts, maybe rebuilds hashes, returns whether any new accounts were added / hashes rebuilt. */
    private boolean addAccounts(Collection<OnlineAccountData> onlineAccountsToAdd) {
        // For keeping track of which hashes to rebuild
        Map<Long, Set<Byte>> hashesToRebuild = new HashMap<>();

        for (OnlineAccountData onlineAccountData : onlineAccountsToAdd) {
            boolean isNewEntry = this.addAccount(onlineAccountData);

            if (isNewEntry)
                hashesToRebuild.computeIfAbsent(onlineAccountData.getTimestamp(), k -> new HashSet<>()).add(onlineAccountData.getPublicKey()[0]);
        }

        if (hashesToRebuild.isEmpty())
            return false;

        for (var entry : hashesToRebuild.entrySet()) {
            Long timestamp = entry.getKey();

            LOGGER.trace(() -> String.format("Rehashing for timestamp %d and leading bytes %s",
                            timestamp,
                            entry.getValue().stream().sorted(Byte::compareUnsigned).map(leadingByte -> String.format("%02x", leadingByte)).collect(Collectors.joining(", "))
                    )
            );

            for (Byte leadingByte : entry.getValue()) {
                byte[] pubkeyHash = currentOnlineAccounts.get(timestamp).stream()
                        .map(OnlineAccountData::getPublicKey)
                        .filter(publicKey -> leadingByte == publicKey[0])
                        .reduce(null, OnlineAccountsManager::xorByteArrayInPlace);

                currentOnlineAccountsHashes.computeIfAbsent(timestamp, k -> new ConcurrentHashMap<>()).put(leadingByte, pubkeyHash);

                LOGGER.trace(() -> String.format("Rebuilt hash %s for timestamp %d and leading byte %02x using %d public keys",
                        HashCode.fromBytes(pubkeyHash),
                        timestamp,
                        leadingByte,
                        currentOnlineAccounts.get(timestamp).stream()
                                .map(OnlineAccountData::getPublicKey)
                                .filter(publicKey -> leadingByte == publicKey[0])
                                .count()
                ));
            }
        }

        LOGGER.trace(String.format("we have online accounts for timestamps: %s", String.join(", ", this.currentOnlineAccounts.keySet().stream().map(l -> Long.toString(l)).collect(Collectors.joining(", ")))));

        return true;
    }

    private boolean addAccount(OnlineAccountData onlineAccountData) {
        byte[] rewardSharePublicKey = onlineAccountData.getPublicKey();
        long onlineAccountTimestamp = onlineAccountData.getTimestamp();

        Set<OnlineAccountData> onlineAccounts = this.currentOnlineAccounts.computeIfAbsent(onlineAccountTimestamp, k -> ConcurrentHashMap.newKeySet());

        boolean isSuperiorEntry = isOnlineAccountsDataSuperior(onlineAccountData);
        if (isSuperiorEntry)
            // Remove existing inferior entry so it can be re-added below (it's likely the existing copy is missing a nonce value)
            onlineAccounts.removeIf(a -> Arrays.equals(a.getPublicKey(), onlineAccountData.getPublicKey()));

        boolean isNewEntry = onlineAccounts.add(onlineAccountData);

        if (isNewEntry)
            LOGGER.trace(() -> String.format("Added online account %s with timestamp %d", Base58.encode(rewardSharePublicKey), onlineAccountTimestamp));
        else
            LOGGER.trace(() -> String.format("Not updating existing online account %s with timestamp %d", Base58.encode(rewardSharePublicKey), onlineAccountTimestamp));

        return isNewEntry;
    }

    /**
     * Expire old entries.
     */
    private void expireOldOnlineAccounts() {
        final Long now = NTP.getTime();
        if (now == null)
            return;

        final long cutoffThreshold = now - MAX_CACHED_TIMESTAMP_SETS * getOnlineTimestampModulus();
        this.currentOnlineAccounts.keySet().removeIf(timestamp -> timestamp < cutoffThreshold);
        this.currentOnlineAccountsHashes.keySet().removeIf(timestamp -> timestamp < cutoffThreshold);
    }

    /**
     * Request data from other peers
     */
    private void requestRemoteOnlineAccounts() {
        final Long now = NTP.getTime();
        if (now == null)
            return;

        // Don't bother if we're not up to date
        if (!Controller.getInstance().isUpToDate())
            return;

        long onlineAccountsTimestamp = getCurrentOnlineAccountTimestamp();
        if (now - onlineAccountsTimestamp >= ONLINE_ACCOUNTS_BROADCAST_BURST_LENGTH) {
            // New online timestamp started more than 5 mins ago - we probably don't need to request so frequently

            if (Controller.uptime() < ONLINE_ACCOUNTS_BROADCAST_BURST_LENGTH) {
                // The node recently started up, so we should request at the burst interval
                // This could allow accounts to move around the network more easily when an auto update is occurring
            }
            else if (now - lastOnlineAccountsRequest < ONLINE_ACCOUNTS_BROADCAST_INTERVAL) {
                // We already requested online accounts in the last minute, so no need to request again
                return;
            }
        }

        LOGGER.debug("Requesting online accounts via broadcast...");

        lastOnlineAccountsRequest = now;
        Message messageV3 = new GetOnlineAccountsV3Message(currentOnlineAccountsHashes);
        Network.getInstance().broadcast(peer -> messageV3);
    }

    /**
     * Send online accounts that are minting on this node.
     */
    private void sendOurOnlineAccountsInfo() {
        // 'current' timestamp
        final Long onlineAccountsTimestamp = getCurrentOnlineAccountTimestamp();
        if (onlineAccountsTimestamp == null)
            return;

        Long now = NTP.getTime();
        if (now == null) {
            return;
        }

        // Don't submit if we're more than 2 hours out of sync (unless we're in recovery mode)
        final Long minLatestBlockTimestamp = now - (2 * 60 * 60 * 1000L);
        if (!Controller.getInstance().isUpToDate(minLatestBlockTimestamp) && !Synchronizer.getInstance().getRecoveryMode()) {
            return;
        }

        // 'next' timestamp (prioritize this as it's the most important, if mempow active)
        final long nextOnlineAccountsTimestamp = toOnlineAccountTimestamp(now) + getOnlineTimestampModulus();
        boolean success = computeOurAccountsForTimestamp(nextOnlineAccountsTimestamp);
        if (!success) {
            // We didn't compute the required nonce value(s), and so can't proceed until they have been retried
            return;
        }

        // 'current' timestamp
        computeOurAccountsForTimestamp(onlineAccountsTimestamp);
    }

    private boolean computeOurAccountsForTimestamp(Long onlineAccountsTimestamp) {
        if (onlineAccountsTimestamp != null) {
            List<MintingAccountData> mintingAccounts;
            try (final Repository repository = RepositoryManager.getRepository()) {
                mintingAccounts = repository.getAccountRepository().getMintingAccounts();

                // We have no accounts to send
                if (mintingAccounts.isEmpty())
                    return false;

                // Only active reward-shares allowed
                Iterator<MintingAccountData> iterator = mintingAccounts.iterator();
                int i = 0;
                while (iterator.hasNext()) {
                    MintingAccountData mintingAccountData = iterator.next();

                    RewardShareData rewardShareData = repository.getAccountRepository().getRewardShare(mintingAccountData.getPublicKey());
                    if (rewardShareData == null) {
                        // Reward-share doesn't even exist - probably not a good sign
                        iterator.remove();
                        continue;
                    }

                    Account mintingAccount = new Account(repository, rewardShareData.getMinter());
                    if (!mintingAccount.canMint(true)) {
                        // Minting-account component of reward-share can no longer mint - disregard
                        iterator.remove();
                        continue;
                    }

                    if (++i > 1 + 1) {
                        iterator.remove();
                    }
                }
            } catch (DataException e) {
                LOGGER.warn(String.format("Repository issue trying to fetch minting accounts: %s", e.getMessage()));
                return false;
            }

            byte[] timestampBytes = Longs.toByteArray(onlineAccountsTimestamp);
            List<OnlineAccountData> ourOnlineAccounts = new ArrayList<>();

            int remaining = mintingAccounts.size();
            for (MintingAccountData mintingAccountData : mintingAccounts) {
                remaining--;
                byte[] privateKey = mintingAccountData.getPrivateKey();
                byte[] publicKey = Crypto.toPublicKey(privateKey);

                // We don't want to compute the online account nonce and signature again if it already exists
                Set<OnlineAccountData> onlineAccounts = this.currentOnlineAccounts.computeIfAbsent(onlineAccountsTimestamp, k -> ConcurrentHashMap.newKeySet());
                boolean alreadyExists = onlineAccounts.stream().anyMatch(a -> Arrays.equals(a.getPublicKey(), publicKey));
                if (alreadyExists) {
                    this.hasOurOnlineAccounts = true;

                    if (remaining > 0) {
                        // Move on to next account
                        continue;
                    } else {
                        // Everything exists, so return true
                        return true;
                    }
                }

                // Generate bytes for mempow
                byte[] mempowBytes;
                try {
                    mempowBytes = this.getMemoryPoWBytes(publicKey, onlineAccountsTimestamp);
                } catch (IOException e) {
                    LOGGER.info("Unable to create bytes for MemoryPoW. Moving on to next account...");
                    continue;
                }

                // Compute nonce
                Integer nonce;
                try {
                    nonce = this.computeMemoryPoW(mempowBytes, publicKey, onlineAccountsTimestamp);
                    if (nonce == null) {
                        // A nonce is required
                        return false;
                    }
                } catch (TimeoutException e) {
                    LOGGER.info(String.format("Timed out computing nonce for account %.8s", Base58.encode(publicKey)));
                    return false;
                }

                byte[] signature = Qortal25519Extras.signForAggregation(privateKey, timestampBytes);

                // Our account is online
                OnlineAccountData ourOnlineAccountData = new OnlineAccountData(onlineAccountsTimestamp, signature, publicKey, nonce);

                // Make sure to verify before adding
                if (verifyMemoryPoW(ourOnlineAccountData, null)) {
                    ourOnlineAccounts.add(ourOnlineAccountData);
                } else {
                    LOGGER.warn("Computed nonce failed local verification for account {} timestamp {} nonce {} (difficulty {}, buffer {})",
                            Base58.encode(publicKey),
                            onlineAccountsTimestamp,
                            nonce,
                            getPoWDifficulty(onlineAccountsTimestamp),
                            getPoWBufferSize());
                }
            }

            this.hasOurOnlineAccounts = !ourOnlineAccounts.isEmpty();

            boolean hasInfoChanged = addAccounts(ourOnlineAccounts);

            if (!hasInfoChanged) {
                if (!ourOnlineAccounts.isEmpty()) {
                    long matchingCount = this.currentOnlineAccounts.getOrDefault(onlineAccountsTimestamp, Collections.emptySet()).stream()
                            .filter(a -> ourOnlineAccounts.stream().anyMatch(our -> Arrays.equals(our.getPublicKey(), a.getPublicKey())))
                            .count();
                    LOGGER.info("No online-account cache update for timestamp {} despite {} locally verified account(s); matching pubkey entries currently in cache: {}",
                            onlineAccountsTimestamp, ourOnlineAccounts.size(), matchingCount);
                }
                return false;
            }

            Network.getInstance().broadcast(peer -> new OnlineAccountsV3Message(ourOnlineAccounts));

            LOGGER.debug("Broadcasted {} online account{} with timestamp {}", ourOnlineAccounts.size(), (ourOnlineAccounts.size() != 1 ? "s" : ""), onlineAccountsTimestamp);

            return true;
        }

        return false;
    }



    // MemoryPoW

    private byte[] getMemoryPoWBytes(byte[] publicKey, long onlineAccountsTimestamp) throws IOException {
        byte[] timestampBytes = Longs.toByteArray(onlineAccountsTimestamp);

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        outputStream.write(publicKey);
        outputStream.write(timestampBytes);

        return outputStream.toByteArray();
    }

    private Integer computeMemoryPoW(byte[] bytes, byte[] publicKey, long onlineAccountsTimestamp) throws TimeoutException {
        LOGGER.info(String.format("Computing nonce for account %.8s and timestamp %d...", Base58.encode(publicKey), onlineAccountsTimestamp));

        // Calculate the time until the next online timestamp and use it as a timeout when computing the nonce
        Long startTime = NTP.getTime();
        final long nextOnlineAccountsTimestamp = toOnlineAccountTimestamp(startTime) + getOnlineTimestampModulus();
        long timeUntilNextTimestamp = nextOnlineAccountsTimestamp - startTime;

        int difficulty = getPoWDifficulty(onlineAccountsTimestamp);
        Integer nonce = MemoryPoW.compute2(bytes, getPoWBufferSize(), difficulty, timeUntilNextTimestamp);

        double totalSeconds = (NTP.getTime() - startTime) / 1000.0f;
        int minutes = (int) ((totalSeconds % 3600) / 60);
        int seconds = (int) (totalSeconds % 60);
        double hashRate = nonce / totalSeconds;

        LOGGER.info(String.format("Computed nonce for timestamp %d and account %.8s: %d. Buffer size: %d. Difficulty: %d. " +
                        "Time taken: %02d:%02d. Hashrate: %f", onlineAccountsTimestamp, Base58.encode(publicKey),
                nonce, getPoWBufferSize(), difficulty, minutes, seconds, hashRate));

        return nonce;
    }

    public boolean verifyMemoryPoW(OnlineAccountData onlineAccountData, long[] workBuffer) {
        // Require a valid nonce value
        if (onlineAccountData.getNonce() == null || onlineAccountData.getNonce() < 0) {
            return false;
        }

        int nonce = onlineAccountData.getNonce();

        byte[] mempowBytes;
        try {
            mempowBytes = this.getMemoryPoWBytes(onlineAccountData.getPublicKey(), onlineAccountData.getTimestamp());
        } catch (IOException e) {
            return false;
        }

        // Verify the nonce
        return MemoryPoW.verify2(mempowBytes, workBuffer, getPoWBufferSize(), getPoWDifficulty(onlineAccountData.getTimestamp()), nonce);
    }


    /**
     * Returns whether online accounts manager has any online accounts with timestamp recent enough to be considered currently online.
     */
    // BlockMinter: only calls this to check whether returned list is empty or not, to determine whether minting is even possible or not
    public boolean hasOnlineAccounts() {
        // 'current' timestamp
        final Long onlineAccountsTimestamp = getCurrentOnlineAccountTimestamp();
        if (onlineAccountsTimestamp == null)
            return false;

        return this.currentOnlineAccounts.containsKey(onlineAccountsTimestamp);
    }

    /**
     * Whether we have submitted - or attempted to submit - our online account
     * signature(s) to the network.
     * @return true if our signature(s) have been submitted recently.
     */
    public boolean hasActiveOnlineAccountSignatures() {
        final Long now = NTP.getTime();
        if (now == null)
            return false;
    
        final long minLatestBlockTimestamp = now - (2 * 60 * 60 * 1000L);
        boolean isUpToDate = Controller.getInstance().isUpToDate(minLatestBlockTimestamp);
    
        return isUpToDate && hasOurOnlineAccounts();
    }

    public boolean hasOurOnlineAccounts() {
        return this.hasOurOnlineAccounts;
    }

    /**
     * Returns list of online accounts matching given timestamp.
     */
    // Block::mint() - only wants online accounts with (online) timestamp that matches block's (online) timestamp so they can be added to new block
    public List<OnlineAccountData> getOnlineAccounts(long onlineTimestamp) {
        LOGGER.debug(String.format("caller's timestamp: %d, our timestamps: %s", onlineTimestamp, String.join(", ", this.currentOnlineAccounts.keySet().stream().map(l -> Long.toString(l)).collect(Collectors.joining(", ")))));

        return new ArrayList<>(Set.copyOf(this.currentOnlineAccounts.getOrDefault(onlineTimestamp, Collections.emptySet())));
    }

    /**
     * Returns list of online accounts with timestamp recent enough to be considered currently online.
     */
    // API: calls this to return list of online accounts - probably expects ALL timestamps - but going to get 'current' from now on
    public List<OnlineAccountData> getOnlineAccounts() {
        // 'current' timestamp
        final Long onlineAccountsTimestamp = getCurrentOnlineAccountTimestamp();
        if (onlineAccountsTimestamp == null)
            return Collections.emptyList();

        return getOnlineAccounts(onlineAccountsTimestamp);
    }

    // Block processing

    /**
     * Removes previously validated entries from block's online accounts.
     * <p>
     * Checks both 'current' and block caches.
     * <p>
     * Typically called by {@link Block#areOnlineAccountsValid()}
     */
    public void removeKnown(Set<OnlineAccountData> blocksOnlineAccounts, Long timestamp) {
        Set<OnlineAccountData> onlineAccounts = this.currentOnlineAccounts.get(timestamp);

        // If not 'current' timestamp - try block cache instead
        if (onlineAccounts == null)
            onlineAccounts = this.latestBlocksOnlineAccounts.get(timestamp);

        if (onlineAccounts != null)
            blocksOnlineAccounts.removeAll(onlineAccounts);
    }

    /**
     * Adds block's online accounts to one of OnlineAccountManager's caches.
     * <p>
     * It is assumed that the online accounts have been verified.
     * <p>
     * Typically called by {@link Block#areOnlineAccountsValid()}
     */
    public void addBlocksOnlineAccounts(Set<OnlineAccountData> blocksOnlineAccounts, Long timestamp) {
        // If these are current accounts, then there is no need to cache them, and should instead rely
        // on the more complete entries we already have in self.currentOnlineAccounts.
        // Note: since sig-agg, we no longer have individual signatures included in blocks, so we
        // mustn't add anything to currentOnlineAccounts from here.
        if (this.currentOnlineAccounts.containsKey(timestamp))
            return;

        // Add to block cache instead
        this.latestBlocksOnlineAccounts.computeIfAbsent(timestamp, k -> ConcurrentHashMap.newKeySet())
                .addAll(blocksOnlineAccounts);

        // If block cache has grown too large then we need to trim.
        if (this.latestBlocksOnlineAccounts.size() > MAX_BLOCKS_CACHED_ONLINE_ACCOUNTS) {
            // However, be careful to trim the opposite end to the entry we just added!
            Long firstKey = this.latestBlocksOnlineAccounts.firstKey();
            if (!firstKey.equals(timestamp))
                this.latestBlocksOnlineAccounts.remove(firstKey);
            else
                this.latestBlocksOnlineAccounts.remove(this.latestBlocksOnlineAccounts.lastKey());
        }
    }


    // Utils

    public void removeAllOnlineAccounts() {
        LOGGER.warn("removeAllOnlineAccounts() called - clearing current online accounts cache", new IllegalStateException("removeAllOnlineAccounts caller trace"));
        this.currentOnlineAccounts.clear();
    }


    // Network handlers

    public void onNetworkGetOnlineAccountsV3Message(Peer peer, Message message) {
        GetOnlineAccountsV3Message getOnlineAccountsMessage = (GetOnlineAccountsV3Message) message;

        Map<Long, Map<Byte, byte[]>> peersHashes = getOnlineAccountsMessage.getHashesByTimestampThenByte();
        List<OnlineAccountData> outgoingOnlineAccounts = new ArrayList<>();

        // Warning: no double-checking/fetching - we must be ConcurrentMap compatible!
        // So no contains()-then-get() or multiple get()s on the same key/map.
        // We also use getOrDefault() with emptySet() on currentOnlineAccounts in case corresponding timestamp entry isn't there.
        for (var ourOuterMapEntry : currentOnlineAccountsHashes.entrySet()) {
            Long timestamp = ourOuterMapEntry.getKey();

            var ourInnerMap = ourOuterMapEntry.getValue();
            var peersInnerMap = peersHashes.get(timestamp);

            if (peersInnerMap == null) {
                // Peer doesn't have this timestamp, so if it's valid (i.e. not too old) then we'd have to send all of ours
                Set<OnlineAccountData> timestampsOnlineAccounts = this.currentOnlineAccounts.getOrDefault(timestamp, Collections.emptySet());
                outgoingOnlineAccounts.addAll(timestampsOnlineAccounts);

                LOGGER.trace(() -> String.format("Going to send all %d online accounts for timestamp %d", timestampsOnlineAccounts.size(), timestamp));
            } else {
                // Quick cache of which leading bytes to send so we only have to filter once
                Set<Byte> outgoingLeadingBytes = new HashSet<>();

                // We have entries for this timestamp so compare against peer's entries
                for (var ourInnerMapEntry : ourInnerMap.entrySet()) {
                    Byte leadingByte = ourInnerMapEntry.getKey();
                    byte[] peersHash = peersInnerMap.get(leadingByte);

                    if (!Arrays.equals(ourInnerMapEntry.getValue(), peersHash)) {
                        // For this leading byte: hashes don't match or peer doesn't have entry
                        // Send all online accounts for this timestamp and leading byte
                        outgoingLeadingBytes.add(leadingByte);
                    }
                }

                int beforeAddSize = outgoingOnlineAccounts.size();

                this.currentOnlineAccounts.getOrDefault(timestamp, Collections.emptySet()).stream()
                        .filter(account -> outgoingLeadingBytes.contains(account.getPublicKey()[0]))
                        .forEach(outgoingOnlineAccounts::add);

                if (outgoingOnlineAccounts.size() > beforeAddSize)
                    LOGGER.trace(String.format("Going to send %d online accounts for timestamp %d and leading bytes %s",
                            outgoingOnlineAccounts.size() - beforeAddSize,
                            timestamp,
                            outgoingLeadingBytes.stream().sorted(Byte::compareUnsigned).map(leadingByte -> String.format("%02x", leadingByte)).collect(Collectors.joining(", "))
                            )
                    );
            }
        }

        peer.sendMessage(new OnlineAccountsV3Message(outgoingOnlineAccounts));

        LOGGER.trace("Sent {} online accounts to {}", outgoingOnlineAccounts.size(), peer);
    }

    public void onNetworkOnlineAccountsV3Message(Peer peer, Message message) {
        OnlineAccountsV3Message onlineAccountsMessage = (OnlineAccountsV3Message) message;

        List<OnlineAccountData> peersOnlineAccounts = onlineAccountsMessage.getOnlineAccounts();
        LOGGER.trace("Received {} online accounts from {}", peersOnlineAccounts.size(), peer);

        int importCount = 0;

        // Add any online accounts to the queue that aren't already present
        for (OnlineAccountData onlineAccountData : peersOnlineAccounts) {

            Set<OnlineAccountData> onlineAccounts = this.currentOnlineAccounts.computeIfAbsent(onlineAccountData.getTimestamp(), k -> ConcurrentHashMap.newKeySet());
            if (onlineAccounts.contains(onlineAccountData))
                // We have already validated this online account
                continue;

            boolean isNewEntry = onlineAccountsImportQueue.add(onlineAccountData);

            if (isNewEntry)
                importCount++;
        }

        if (importCount > 0)
            LOGGER.debug("Added {} online accounts to queue", importCount);
    }
}
