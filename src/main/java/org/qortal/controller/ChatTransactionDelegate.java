package org.qortal.controller;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.asset.Asset;
import org.qortal.block.BlockChain;
import org.qortal.crypto.Crypto;
import org.qortal.crypto.MemoryPoW;
import org.qortal.data.account.AccountBalanceData;
import org.qortal.data.chat.ActiveChats;
import org.qortal.data.chat.ChatMessage;
import org.qortal.data.group.GroupData;
import org.qortal.data.group.GroupMemberData;
import org.qortal.data.transaction.ChatTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.group.Group;
import org.qortal.repository.ChatRepository;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.settings.Settings;
import org.qortal.transaction.ChatTransaction;
import org.qortal.transaction.Transaction;
import org.qortal.transform.TransformationException;
import org.qortal.transform.transaction.ChatTransactionTransformer;
import org.qortal.transform.transaction.TransactionTransformer;
import org.qortal.utils.Base58;
import org.qortal.utils.JsonUtils;
import org.qortal.utils.ListUtils;
import org.qortal.utils.NTP;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Class ChatTransactionDelegate
 */
public class ChatTransactionDelegate implements ChatRepository {

    private static final Logger LOGGER = LogManager.getLogger(ChatTransactionDelegate.class);

    /**
     * Data File
     *
     * The data file name and location to save the validated chats to periodically. This file gets loaded when
     * this gets constucted.
     */
    private static final File DATA_FILE = new File("qortal-backup/ChatTransactions.json");

    /**
     * Too Far In The Future
     *
     * 300,000 milliseconds
     * 300 seconds
     * 5 minutes is to far into the future
     */
    private static final long TOO_FAR_IN_THE_FUTURE = 5 * 60 * 1000L;

    /**
     * Singleton
     */
    private static ChatTransactionDelegate singleton = new ChatTransactionDelegate();

    /**
     * Incoming Chats
     *
     * Chat data that gets delegated to this singleton, then get scheduled for validation.
     */
    private final List<ChatTransactionData> incomingChats;

    /**
     * Validated Chats
     *
     * Chats that have been validated and are available for responding to external request from other nodes and clients.
     */
    private final List<ChatTransactionData> validatedChats;

    /**
     * Recent Chats By Address
     *
     * Chats that were broadcast within the last hour by address. Address -> List of Recent Chats
     */
    private final Map<String, List<ChatTransactionData>> recentChatsByAddress;

    /**
     * Signatures By Data
     *
     * Validated chat transactions hashed by their signature that is Base58 encoded
     */
    private final Map<String, ChatTransactionData> dataBySignature;

    /**
     * References By Data
     *
     * Validated chat transactions hashed by their reference that is Base58 encoded.
     */
    private final Map<String, ChatTransactionData> dataByReference;

    /**
     * Chat References By Data
     *
     * Validated chat transactions hashed by their chat reference that is Base58 encoded.
     */
    private final Map<String, ChatTransactionData> dataByChatReference;

    /**
     * Latest Chat By Group Id
     *
     * The latest validated chat transaction for a group.
     */
    private final Map<Integer, ChatTransactionData> latestChatByGroupId;

    /**
     * Latest Chat With Chat Reference By Group Id
     *
     * The latest validated chat transaction with a chat reference for a group.
     */
    private final Map<Integer, ChatTransactionData> latestChatWithChatReferenceByGroupId;

    /**
     * Latest Chat Without Chat Reference By Group Id
     *
     * The latest validated chat transaction without a chat reference for a group.
     */
    private final Map<Integer, ChatTransactionData> latestChatWithoutChatReferenceByGroupId;

    /**
     * List By Involved
     *
     * A list of validated chats hashed by an address that is involved in each one of those chats.
     *
     * address -> list of chats where the address is either the sender or recipient
     */
    private final Map<String, List<ChatTransactionData>> listByInvolved;

    /**
     * Chat Data Lock
     *
     * The lock used when reading or writing to chat data.
     */
    private final Object chatDataLock = new Object();

    /**
     * Chat Data Scheduler
     *
     * The scheduler that periodically updates chat data.
     */
    private final ScheduledExecutorService chatDataScheduler;

    /**
     * Name Data Lock
     *
     * The lock used when reading or writing to name data.
     */
    private final Object nameDataLock = new Object();

    /**
     * Name Data Scheduler
     *
     * The scheduler that periodically updates name data.
     */
    private final ScheduledExecutorService nameDataScheduler;

    /**
     * Primary Name By Owner
     *
     * Primary name hashed by owner address.
     * address -> primary name
     */
    private final Map<String, String> primaryNameByOwner;

    /**
     * Balance Data Lock
     *
     * The lock used when reading or writing balance data to the balanceByAddress map.
     */
    private final Object balanceDataLock = new Object();

    /**
     * Balance Data Scheduler
     *
     * A scheduler that periodically updates the balanceByAddress map.
     */
    private final ScheduledExecutorService balanceDataScheduler;

    /**
     * Balance By Address
     *
     * The balance for every address.
     * Address -> Balance
     */
    private final Map<String, Long> balancesByAddress;

    /**
     * Group Data Lock
     *
     * The lock for reading and writing to the group data maps.
     */
    private final Object groupDataLock = new Object();

    /**
     * Group Data Scheduler
     *
     * A scheduler that periodically updates the group data.
     */
    private final ScheduledExecutorService groupDataScheduler;

    /**
     * Group By Id
     *
     * Group data hashed by Group Id.
     *
     * Group Id -> Group
     */
    private final Map<Integer, GroupData> groupById;

    /**
     * Group Id By Address
     *
     * A list of group Id's hashed by address. Each group Id represent group membership for the address.
     */
    private final Map<String, List<Integer>> groupIdsByAddress;

    /**
     * Cleanup Scheduler
     *
     * A scheduler that periodically removes verified chats.
     */
    private final ScheduledExecutorService cleanupScheduler;

    /**
     * Backup Scheduler
     *
     * A scheduler that periodically backups the verified chats to a file.
     */
    private final ScheduledExecutorService backupScheduler;

    /**
     * Constructor
     *
     * A private constructor because this class is intended to be used as a singleton.
     */
    private ChatTransactionDelegate() {

        LOGGER.info("loading validated chats from file");
        this.validatedChats = JsonUtils.loadListFromJson(DATA_FILE);

        LOGGER.info("loaded validated chats from file {}", this.validatedChats.size());

        if( validatedChats.isEmpty() ) {

            LOGGER.info("loading validated chats from database");
            try (final Repository repository = RepositoryManager.getRepository()) {

                this.validatedChats.addAll(repository.getChatRepository().getAllChatData());

            } catch (DataException e) {
                LOGGER.error(e.getMessage(), e);
            }
            LOGGER.info("loaded validated chats from database {}", this.validatedChats.size());
        }

        this.recentChatsByAddress = new HashMap<>();
        this.dataBySignature = new HashMap<>(this.validatedChats.size());
        this.dataByReference = new HashMap<>(this.validatedChats.size());
        this.dataByChatReference = new HashMap<>(this.validatedChats.size());
        this.listByInvolved = new HashMap<>();
        this.latestChatByGroupId = new HashMap<>();
        this.latestChatWithChatReferenceByGroupId = new HashMap<>();
        this.latestChatWithoutChatReferenceByGroupId = new HashMap<>();

        for( ChatTransactionData validatedChat : this.validatedChats ) {
            mapChat(validatedChat);
        }

        LOGGER.info("loaded hash maps");

        this.incomingChats = new ArrayList<>();

        this.primaryNameByOwner = new HashMap<>();

        this.balancesByAddress = new HashMap<>();

        this.groupById = new HashMap<>();
        this.groupIdsByAddress = new HashMap<>();

        this.chatDataScheduler = Executors.newScheduledThreadPool(1 );
        this.chatDataScheduler.scheduleWithFixedDelay(this::validateChats, 120_000, 500, TimeUnit.MILLISECONDS);

        processNames();
        this.nameDataScheduler = Executors.newScheduledThreadPool( 1);
        this.nameDataScheduler.scheduleWithFixedDelay(this::processNames, 4, 5, TimeUnit.MINUTES);

        processBalances();
        this.balanceDataScheduler = Executors.newScheduledThreadPool(1);
        this.balanceDataScheduler.scheduleWithFixedDelay(this::processBalances, 5, 5, TimeUnit.MINUTES);

        processGroups();
        this.groupDataScheduler = Executors.newScheduledThreadPool(1);
        this.groupDataScheduler.scheduleWithFixedDelay(this::processGroups, 6, 5, TimeUnit.MINUTES);

        cleanup();
        this.cleanupScheduler = Executors.newScheduledThreadPool(1);
        this.cleanupScheduler.scheduleAtFixedRate(this::cleanup, 1, 1, TimeUnit.HOURS);

        this.backupScheduler = Executors.newScheduledThreadPool(1);
        this.backupScheduler.scheduleAtFixedRate(this::backupToFile, 15, 15, TimeUnit.MINUTES);
    }

    /**
     * Cleanup
     *
     * Remove validated chats and there mappings due to time expiration.
     */
    private void cleanup() {

        Long now = NTP.getTime();
        if (now == null) return;

        long cutoffTimestamp = now - BlockChain.getInstance().getTransactionExpiryPeriod();
        long agingTimestamp = now - Settings.getInstance().getRecentChatMessagesMaxAge();

        List<ChatTransactionData> chatsToEvaluate;
        synchronized (this.chatDataLock) {
            chatsToEvaluate = new ArrayList<>(this.validatedChats);
        }

        List<ChatTransactionData> chatsToRemove = new ArrayList<>(chatsToEvaluate.size());
        for( ChatTransactionData chatToEvaluate : chatsToEvaluate ) {

            if( chatToEvaluate.getTimestamp() < cutoffTimestamp ) {
                chatsToRemove.add(chatToEvaluate);
            }
        }

        LOGGER.info("chats to remove {}", chatsToRemove.size());

        synchronized (this.chatDataLock) {
            for( Map.Entry<String, List<ChatTransactionData>> entry : this.recentChatsByAddress.entrySet() ) {

                entry.getValue().removeIf( data -> data.getTimestamp() < agingTimestamp);
            }

            this.validatedChats.removeAll(chatsToRemove);
            this.dataBySignature.values().removeAll(chatsToRemove);
            this.dataByReference.values().removeAll(chatsToRemove);
            this.dataByChatReference.values().removeAll(chatsToRemove);
            this.latestChatByGroupId.values().removeAll(chatsToRemove);
            this.latestChatWithChatReferenceByGroupId.values().removeAll(chatsToRemove);
            this.latestChatWithoutChatReferenceByGroupId.values().removeAll(chatsToRemove);

            for( List<ChatTransactionData> chats : this.listByInvolved.values()) {

                chats.removeAll(chatsToRemove);
            }
        }
    }

    /**
     * Map Chat
     *
     * Put the validated chat into all the maps, so they can get fetched in the future.
     *
     * @param validatedChat the validated chat transaction data
     */
    private void mapChat(ChatTransactionData validatedChat) {

        try {
            long now = NTP.getTime() != null ? NTP.getTime() : System.currentTimeMillis();
            long maxAge = Settings.getInstance().getRecentChatMessagesMaxAge();
            long cutoff = now - maxAge;

            if( validatedChat.getTimestamp() > cutoff) {
                this.recentChatsByAddress.computeIfAbsent(validatedChat.getSender(), sender -> new ArrayList<>()).add(validatedChat);
            }

            this.dataBySignature.put(Base58.encode(validatedChat.getSignature()), validatedChat);
            this.dataByReference.put(Base58.encode(validatedChat.getReference()), validatedChat);
            if( validatedChat.getChatReference() != null ) this.dataByChatReference.put(Base58.encode(validatedChat.getChatReference()), validatedChat);

            // if there is a group referenced
            if( validatedChat.getTxGroupId() > 0 ) {

                setLatest(validatedChat, this.latestChatByGroupId);

                if( validatedChat.getChatReference() != null ) {
                    setLatest(validatedChat, this.latestChatWithChatReferenceByGroupId);
                }
                else {
                    setLatest(validatedChat, this.latestChatWithoutChatReferenceByGroupId);
                }
            }
            // if there is no group reference, then this is a direct chat where a sender and recipient are involved
            else if( validatedChat.getRecipient() != null && validatedChat.getSender() != null) {
                this.listByInvolved
                    .computeIfAbsent(validatedChat.getRecipient(), k -> new ArrayList<>())
                    .add(validatedChat);
                this.listByInvolved
                    .computeIfAbsent(validatedChat.getSender(), k -> new ArrayList<>())
                    .add(validatedChat);
            }
        } catch (Exception e) {
            LOGGER.error(e.getMessage(), e);
        }
    }

    /**
     * Set Latest
     *
     * Evaluates the chat data and if it is the latest chat for its group, then it will be set to that's group Id in the map.
     *
     * @param validatedChat the validated chat to evaluate for setting
     * @param latestChatByGroupId the map to set it to
     */
    private void setLatest(ChatTransactionData validatedChat, Map<Integer, ChatTransactionData> latestChatByGroupId) {
        // get the latest for the group reference
        ChatTransactionData latestChat = latestChatByGroupId.get(validatedChat.getTxGroupId());

        // if the validated chat is the latest for the group referenced, then set it to the map
        if( latestChat == null || latestChat.getTimestamp() < validatedChat.getTimestamp()) {
            latestChatByGroupId.put(validatedChat.getTxGroupId(), validatedChat);
        }
    }

    /**
     * Process Names
     *
     * Get all the names from the database repository and sets them to the names by owner map.
     */
    private void processNames() {

        LOGGER.info("processing names ...");

        Map<String, String> collectedNames;

        try (final Repository repository = RepositoryManager.getRepository()) {

            collectedNames = repository.getNameRepository().getAllPrimaryNamesByOwner();

        } catch (DataException e) {
            LOGGER.error(e.getMessage(), e);
            return;
        }

        synchronized (nameDataLock) {
            this.primaryNameByOwner.clear();
            this.primaryNameByOwner.putAll(collectedNames);
        }

        LOGGER.info("processed names {}", collectedNames.size());
    }

    /**
     * Process Balances
     *
     * Get all the account balances from the database repository and sets them to the balances by address map.
     */
    private void processBalances() {

        LOGGER.info("processing balances ...");

        Map<String, Long> collectedBalances;
        try( final Repository repository = RepositoryManager.getRepository()) {

            collectedBalances
                = repository.getAccountRepository()
                    .getAssetBalances(Asset.QORT, true).stream()
                    .collect(Collectors.toMap(AccountBalanceData::getAddress, AccountBalanceData::getBalance));

        } catch (DataException e) {
            LOGGER.error(e.getMessage(), e);
            return;
        }

        synchronized (this.balanceDataLock) {
            this.balancesByAddress.clear();
            this.balancesByAddress.putAll(collectedBalances);
        }

        LOGGER.info( "processed balances {}", collectedBalances.size());
    }

    /**
     * Process Groups
     *
     * Gets all the groups and group members from the database repository and sets them to the balances by address map.
     */
    private void processGroups() {

        List<GroupMemberData> collectedMemberships;
        List<GroupData> collectedGroups;

        try( final Repository repository = RepositoryManager.getRepository()) {

            collectedGroups = repository.getGroupRepository().getAllGroups();
            collectedMemberships = repository.getGroupRepository().getAllGroupMemberships();
        } catch (DataException e) {
            LOGGER.error(e.getMessage(), e);
            return;
        }

        Map<Integer, GroupData> mappedGroups
                = collectedGroups.stream().collect(Collectors.toMap(GroupData::getGroupId, Function.identity()));

        Map<String, List<Integer>> mappedMemberships = collectedMemberships.stream()
                .collect(Collectors.groupingBy(
                        GroupMemberData::getMember,  // The key mapper (function to extract the key)
                        Collectors.mapping(           // The downstream collector
                                GroupMemberData::getGroupId, // The value mapper (function to extract the value)
                                Collectors.toList()       // Collect the mapped values into a List
                        )
                ));
        synchronized (this.groupDataLock) {
            this.groupById.clear();
            this.groupById.putAll(mappedGroups);

            this.groupIdsByAddress.clear();
            this.groupIdsByAddress.putAll(mappedMemberships);
        }

        LOGGER.info("processed groups {} groups {} addresses", mappedGroups.size(), mappedMemberships.size());
    }

    /**
     * Validate Chats
     *
     * Validate then map all incoming chats.
     */
    private void validateChats() {

        List<ChatTransactionData> chatsToValidate;

        synchronized (this.incomingChats ) {
            chatsToValidate = new ArrayList<>(this.incomingChats.size());
            chatsToValidate.addAll(this.incomingChats);
            this.incomingChats.clear();
        }

        for (ChatTransactionData chat : chatsToValidate) {

            synchronized (this.chatDataLock) {
                // if chat has not been validated and is valid, then add to validated chats
                if (!this.validatedChats.contains(chat) && isValid(chat, true) == Transaction.ValidationResult.OK) {
                    this.validatedChats.add(chat);
                    mapChat(chat);
                    Controller.getInstance().onNewTransaction(chat);
                }
            }
        }
    }

    /**
     * Is Valid?
     *
     * Is the chat transaction data valid? This checks time of message, sender status, sender signature and group
     * member status of the sender.
     *
     * @param chatTransactionData the chat data
     * @param checkBlocking true to check is the sender is on any block lists, otherwise false
     *
     * @return the validation result
     */
    public Transaction.ValidationResult isValid(ChatTransactionData chatTransactionData, boolean checkBlocking)  {

        // discard messages to far in the future
        if (chatTransactionData.getTimestamp() > NTP.getTime() + TOO_FAR_IN_THE_FUTURE) {
            return Transaction.ValidationResult.TIMESTAMP_TOO_NEW;
        }

        // if blocking is checked, then look at the block lists
        if( checkBlocking ) {
            // block message by address
            if (ListUtils.isAddressBlocked(chatTransactionData.getSender())) {
                return Transaction.ValidationResult.ADDRESS_BLOCKED;
            }

            // block message by registered name
            String name = this.primaryNameByOwner.get(chatTransactionData.getSender());
            if (name != null) {
                if (ListUtils.isNameBlocked(name)) {
                    return Transaction.ValidationResult.NAME_BLOCKED;
                }
            }
        }

        final int recentCount;

        synchronized (this.chatDataLock) {
            recentCount = this.recentChatsByAddress.getOrDefault(chatTransactionData.getSender(), new ArrayList<>(0)).size();
        }

        // discard message due to high recent activity
        if (recentCount >= Settings.getInstance().getMaxRecentChatMessagesPerAccount()) {
            LOGGER.info("chat rate limit exceeded for {}: {} recent messages (limit {})",
                    chatTransactionData.getSender(), recentCount, Settings.getInstance().getMaxRecentChatMessagesPerAccount());
            return Transaction.ValidationResult.TOO_MANY_UNCONFIRMED;
        }

        // signature and memory proof of work validation
        if (!isSignatureValid(chatTransactionData))
            return Transaction.ValidationResult.INVALID_TIMESTAMP_SIGNATURE;

        // group validation, zero is also valid and used for direct messages
        if( !isValidTxGroupId(chatTransactionData)) {
            return Transaction.ValidationResult.GROUP_DOES_NOT_EXIST;
        }

        // If we have a recipient, check it is a valid address
        String recipientAddress = chatTransactionData.getRecipient();
        if (recipientAddress != null && !Crypto.isValidAddress(recipientAddress))
            return Transaction.ValidationResult.INVALID_ADDRESS;

        // Check data length
        if (chatTransactionData.getData().length < 1 || chatTransactionData.getData().length > ChatTransaction.MAX_DATA_SIZE)
            return Transaction.ValidationResult.INVALID_DATA_LENGTH;

        return Transaction.ValidationResult.OK;
    }

    /**
     * Is Valid Transaction Group Id?
     *
     * Does this transaction data have a valid group Id?
     *
     * @param transactionData the transaction data representing a chat transaction
     *
     * @return true if the txGroupId is zero or a valid group Id, otherwise false
     */
    protected boolean isValidTxGroupId(ChatTransactionData transactionData) {
        int txGroupId = transactionData.getTxGroupId();

        // valid for direct message
        if (txGroupId == Group.NO_GROUP)
            return true;

        synchronized (this.groupDataLock) {
            // Group even exist?
            if (!this.groupById.containsKey(txGroupId))
                return false;

            // Is transaction's creator is group member?
            if (!this.groupIdsByAddress.getOrDefault(transactionData.getSender(), new ArrayList<>(0)).contains(txGroupId))
                return false;
        }

        return true;
    }

    /**
     * Is Signature Valid?
     *
     * Is signature and memory proof of work valid?
     *
     * @param transactionData the data from the transaction to validate
     *
     * @return true if valid, otherwise false
     */
    public boolean isSignatureValid( ChatTransactionData transactionData ) {

        byte[] signature = transactionData.getSignature();
        if (signature == null) return false;

        byte[] transactionBytes;

        try {
            transactionBytes = ChatTransactionTransformer.toBytesForSigning(transactionData);
        } catch (TransformationException e) {
            throw new RuntimeException("Unable to transform transaction to byte array for verification", e);
        }

        // verify signature
        if (!Crypto.verify(transactionData.getCreatorPublicKey(), signature, transactionBytes))
            return false;

        // get nonce
        int nonce = transactionData.getNonce();

        // clear nonce
        ChatTransactionTransformer.clearNonce(transactionBytes);

        int difficulty = getDifficulty(transactionData);

        // verify memory proof of wrok nonce
        return MemoryPoW.verify2(transactionBytes, ChatTransaction.POW_BUFFER_SIZE, difficulty, nonce);
    }

    /**
     * Get Difficulty
     *
     * Get th difficulty required for the chat data sender
     *
     * @param chatTransactionData the chat data
     *
     * @return the difficulty number
     */
    private int getDifficulty(ChatTransactionData chatTransactionData) {
        int difficulty;

        synchronized (this.balanceDataLock) {
            difficulty = this.balancesByAddress.getOrDefault(chatTransactionData.getSender(), 0L) >= ChatTransaction.POW_QORT_THRESHOLD
                ?
                ChatTransaction.POW_DIFFICULTY_ABOVE_QORT_THRESHOLD
                :
                ChatTransaction.POW_DIFFICULTY_BELOW_QORT_THRESHOLD;
        }
        return difficulty;
    }

    /**
     * Get Instance
     *
     * @return this singleton
     */
    public static ChatTransactionDelegate getInstance() {

        return singleton;
    }

    /**
     * Delegate
     *
     * Delegate chat transaction data to this object.
     *
     * @param chatTransactionData the chat transaction data
     */
    public void delegate(ChatTransactionData chatTransactionData) {

        synchronized (this.incomingChats) {
            incomingChats.add(chatTransactionData);
        }
    }

    /**
     * Get Validated Chat Transactions
     *
     * @return the validated chat transactions
     */
    public List<ChatTransactionData> getValidatedChatTransactions() {

        ArrayList<ChatTransactionData> data;

        synchronized (this.chatDataLock) {
            data = new ArrayList<>(this.validatedChats);
        }

        return data;
    }

    /**
     * Get Data By Signature
     *
     * @return the chat data hashed by signature, the signature is Base58 encoded
     */
    public Map<String, ChatTransactionData> getDataBySignature() {

        Map<String, ChatTransactionData> copy;

        synchronized (this.chatDataLock) {
            copy = new HashMap<>(this.dataBySignature);
        }

        return copy;
    }

    /**
     * Shutdown
     *
     * Shutdown the schedulers and back up the validated chats to a file.
     */
    public void shutdown() {
        LOGGER.info("shutdown schedulers...");

        shutdownScheduler(this.chatDataScheduler);
        shutdownScheduler(this.balanceDataScheduler);
        shutdownScheduler(this.groupDataScheduler);
        shutdownScheduler(this.nameDataScheduler);
        shutdownScheduler(this.cleanupScheduler);

        backupToFile();
    }

    /**
     * Backup To File
     *
     * Backup the validated chats to a file.
     */
    private void backupToFile() {
        LOGGER.info("saving validated chats as JSON...");
        synchronized (this.chatDataLock) {
            JsonUtils.saveListToJson(DATA_FILE, this.validatedChats);
        }
        LOGGER.info("saved validated chats as JSON");
    }

    /**
     * Shutdown Scheduler
     *
     * @param scheduler the scheduler to shutdown
     */
    private void shutdownScheduler(ScheduledExecutorService scheduler) {
        try {
            scheduler.shutdown();
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
        }
    }

    @Override
    public List<ChatMessage> getMessagesMatchingCriteria(
            Long before,
            Long after,
            Integer txGroupId,
            byte[] referenceBytes,
            byte[] chatReferenceBytes,
            Boolean hasChatReference,
            List<String> involving,
            String senderAddress,
            ChatMessage.Encoding encoding,
            Integer limit,
            Integer offset,
            Boolean reverse) throws DataException {

        // check for invalid query criteria
        if ((txGroupId != null && involving != null && !involving.isEmpty())
                || (txGroupId == null && (involving == null || involving.size() != 2)))
            throw new DataException("invalid criteria for fetching chat messages from chat delegate");

        List<ChatTransactionData> candidates;

        // if referenced bytes and chat reference bytes are set, then check for both
        // this must be an edge case, but it is possible
        if( referenceBytes != null && chatReferenceBytes != null) {

            final ChatTransactionData referenceCandidate;
            final ChatTransactionData chatReferenceCandidate;

            synchronized (this.chatDataLock) {
                referenceCandidate = this.dataByReference.get(Base58.encode(referenceBytes));
                chatReferenceCandidate = this.dataByChatReference.get(Base58.encode(chatReferenceBytes));
            }

            if( referenceCandidate != null && referenceCandidate.equals(chatReferenceCandidate)) {
                candidates = List.of(referenceCandidate);
            }
            else {
                return new ArrayList<>(0);
            }
        }
        // if reference bytes are set, then get the data from the mapped indexed by the reference encoded
        else if( referenceBytes != null) {

            final ChatTransactionData referenceCandidate;

            synchronized (this.chatDataLock) {
                referenceCandidate = this.dataByReference.get(Base58.encode(referenceBytes));
            }

            if( referenceCandidate != null ) {
                candidates = List.of(referenceCandidate);
            }
            else {
                return new ArrayList<>(0);
            }
        }
        // if chat reference bytes are set, then get the data from the mapped indexed by the chat reference encoded
        else if( chatReferenceBytes != null ){

            final ChatTransactionData chatReferenceCandidate;

            synchronized (this.chatDataLock) {
                chatReferenceCandidate = this.dataByChatReference.get(Base58.encode(chatReferenceBytes));
            }

            if( chatReferenceCandidate != null) {
                candidates = List.of(chatReferenceCandidate);
            }
            else {
                return new ArrayList<>(0);
            }
        }
        // if reference and chat reference are not set, then all the validated chats are still candidates
        else {
            synchronized (this.chatDataLock) {
                candidates = new ArrayList<>(this.validatedChats);
            }
        }

        Stream<ChatTransactionData> stream = candidates.stream();

        // if before is set, then filter everything after the before timestamp
        if (before != null) {
            stream = stream.filter( candidate -> candidate.getTimestamp() < before);
        }

        // if after is set, then filter everything before the after timestamp
        if (after != null) {
            stream = stream.filter( candidate -> candidate.getTimestamp() > after);
        }

        // if has chat reference is set to true, then filter out candidates where chat reference is not set
        if (hasChatReference != null && hasChatReference) {
            stream = stream.filter( candidate -> candidate.getChatReference() != null);
        }
        // if has chat reference is set to false, then filter out candidates where chat reference is set
        else if (hasChatReference != null && !hasChatReference) {
            stream = stream.filter( candidate -> candidate.getChatReference() == null);
        }
        // if has chat reference is not set, then do no filtering here

        // filter for sender address
        if (senderAddress != null) {
            stream = stream.filter( candidate -> candidate.getSender().equals(senderAddress));
        }

        if (txGroupId != null) {
            synchronized (this.groupDataLock) {
                stream
                        = stream.filter(
                        candidate -> candidate.getTxGroupId() == txGroupId
                                &&
                                candidate.getRecipient() == null
                                &&
                                // ensure that the sender is in the targeted group
                                this.groupIdsByAddress.getOrDefault(candidate.getSender(), new ArrayList<>(0)).contains(txGroupId)
                );
            }
        } else {
            // for each on the involving list, keep candidates that have them as recipient or sender
            for( String involvingToProcess : involving ) {
                stream = stream.filter( candidate -> involvingToProcess.equals(candidate.getRecipient()) || involvingToProcess.equals(candidate.getSender()));
            }
        }

        if( reverse != null && reverse ) {
            stream = stream.sorted( Comparator.comparing(ChatTransactionData::getTimestamp).reversed());
        }
        else {
            stream = stream.sorted( Comparator.comparing(ChatTransactionData::getTimestamp));
        }

        if( offset != null && offset > 0 ) {
            stream = stream.skip(offset);
        }

        if( limit != null && limit > 0 ) {
            stream = stream.limit(limit);
        }

        List<ChatMessage> chatMessages = new ArrayList<>();

        for( ChatTransactionData chatTransactionData : stream.collect(Collectors.toList())) {

            final String senderName;
            final String recipientName;

            // need to get the names from the maps
            synchronized (this.nameDataLock) {
                senderName = this.primaryNameByOwner.getOrDefault(chatTransactionData.getSender(), chatTransactionData.getSender());
                recipientName = this.primaryNameByOwner.getOrDefault(chatTransactionData.getRecipient(), chatTransactionData.getRecipient());
            }

            ChatMessage chatMessage
                = new ChatMessage(
                    chatTransactionData.getTimestamp(),
                    chatTransactionData.getTxGroupId(),
                    chatTransactionData.getReference(),
                    chatTransactionData.getSenderPublicKey(),
                    chatTransactionData.getSender(),
                    senderName,
                    chatTransactionData.getRecipient(),
                    recipientName,
                    chatTransactionData.getChatReference(),
                    encoding,
                    chatTransactionData.getData(),
                    chatTransactionData.getIsText(),
                    chatTransactionData.getIsEncrypted(),
                    chatTransactionData.getSignature()
            );

            chatMessages.add(chatMessage);
        }

        return chatMessages;
    }

    @Override
    public ChatMessage toChatMessage(ChatTransactionData chatTransactionData, ChatMessage.Encoding encoding) throws DataException {

        String senderName;
        String recipientName;

        synchronized (this.nameDataLock) {
            senderName = this.primaryNameByOwner.getOrDefault( chatTransactionData.getSender(), chatTransactionData.getSender());
            recipientName = this.primaryNameByOwner.getOrDefault( chatTransactionData.getRecipient(), chatTransactionData.getRecipient());
        }

        long timestamp = chatTransactionData.getTimestamp();
        int groupId = chatTransactionData.getTxGroupId();
        byte[] reference = chatTransactionData.getReference();
        byte[] senderPublicKey = chatTransactionData.getSenderPublicKey();
        String sender = chatTransactionData.getSender();
        String recipient = chatTransactionData.getRecipient();
        byte[] chatReference = chatTransactionData.getChatReference();
        byte[] data = chatTransactionData.getData();
        boolean isText = chatTransactionData.getIsText();
        boolean isEncrypted = chatTransactionData.getIsEncrypted();
        byte[] signature = chatTransactionData.getSignature();

        return new ChatMessage(timestamp, groupId, reference, senderPublicKey, sender,
                senderName, recipient, recipientName, chatReference, encoding, data,
                isText, isEncrypted, signature);
    }

    @Override
    public ActiveChats getActiveChats(String address, ChatMessage.Encoding encoding, Boolean hasChatReference) {
        List<ActiveChats.GroupChat> groupChats = getActiveGroupChats(address, encoding, hasChatReference);
        List<ActiveChats.DirectChat> directChats = getActiveDirectChats(address, hasChatReference);

        return new ActiveChats(groupChats, directChats);
    }

    /**
     * Get Active Groups Chats
     *
     * Get the status for each group that has been active.
     *
     * @param address the address must belong to the groups for evaluation
     * @param encoding the encoding used
     * @param hasChatReference true if the message should have a chat message, false if they should not, null if it does not matter
     *
     * @return the status for each active group
     */
    private List<ActiveChats.GroupChat> getActiveGroupChats(String address, ChatMessage.Encoding encoding, Boolean hasChatReference) {

        List<Integer> groupIds;

        synchronized (this.groupDataLock) {
            groupIds = new ArrayList<>( this.groupIdsByAddress.getOrDefault(address, new ArrayList<>()) );
        }

        Map<Integer, Optional<ChatTransactionData>> latestChatByUserGroupId = new HashMap<>(groupIds.size());

        for( int groupId : groupIds ) {
            synchronized (this.groupDataLock) {
                if (hasChatReference == null) {
                    latestChatByUserGroupId.put(groupId, Optional.ofNullable(this.latestChatByGroupId.get(groupId)));
                } else if (hasChatReference) {
                    latestChatByUserGroupId.put(groupId, Optional.ofNullable(this.latestChatWithChatReferenceByGroupId.get(groupId)));
                } else {
                    latestChatByUserGroupId.put(groupId, Optional.ofNullable(this.latestChatWithoutChatReferenceByGroupId.get(groupId)));
                }
            }
        }

        List<ActiveChats.GroupChat> groupChats = new ArrayList<>();

        // for each group Id -> status pairing
        for( Map.Entry<Integer, Optional<ChatTransactionData>> entry : latestChatByUserGroupId.entrySet()) {

            final int groupId = entry.getKey();
            final GroupData group;

            synchronized (this.groupDataLock) {
                group = this.groupById.get(groupId);
            }

            if( group != null) {
                if (entry.getValue().isPresent()) {
                    final ChatTransactionData chat = entry.getValue().get();
                    final String senderName;
                    synchronized ( this.primaryNameByOwner) {
                        senderName = this.primaryNameByOwner.getOrDefault(chat.getSender(), chat.getSender());
                    }

                    groupChats.add(
                        new ActiveChats.GroupChat(
                            groupId,
                            group.getGroupName(),
                            chat.getTimestamp(),
                            chat.getSender(),
                            senderName,
                            chat.getSignature(),
                            encoding,
                            chat.getData())
                    );
                } else {
                    groupChats.add(new ActiveChats.GroupChat(groupId, group.getGroupName(), null, null, null, null, encoding, null));
                }
            }
            else {
                LOGGER.warn("group data is not available, id {}", groupId);
            }
        }

        LOGGER.debug("returning {} group chats for address {}", groupChats.size(), address);

        return groupChats.stream()
                .sorted(
                    Comparator.comparing(
                        ActiveChats.GroupChat::getTimestamp,
                        Comparator.nullsLast(Comparator.reverseOrder())
                    )
                )
                .collect(Collectors.toList());
    }

    /**
     * Get Active Direct Chats
     *
     * @param address the address required for group membership
     * @param hasChatReference true if chat reference is required, false if is not required, null means it does not matter
     *
     * @return the status for each group
     */
    private List<ActiveChats.DirectChat> getActiveDirectChats(String address, Boolean hasChatReference) {

        final List<ChatTransactionData> directChatsForAddress;

        synchronized (this.chatDataLock) {
            directChatsForAddress = this.listByInvolved.getOrDefault(address, new ArrayList<>(0));
        }

        Map<String, ActiveChats.DirectChat> directChatsByOtherAddress = new HashMap<>(directChatsForAddress.size());

        for (ChatTransactionData chatTransactionData : directChatsForAddress) {

            // if has chat reference is set to either true or false
            if( hasChatReference != null) {
                // if true and null or false and not null, then continue to the next chat data
                if( (hasChatReference && chatTransactionData.getChatReference() == null ) ||
                        (!hasChatReference && chatTransactionData.getChatReference() != null)) {
                    continue;
                }
            }

            String otherAddress;

            // if the sender is the address, then the recipient is the other
            if( chatTransactionData.getSender().equals(address)) {
                otherAddress = chatTransactionData.getRecipient();
            }
            // if the sender is not the address, then the sender is the other
            else {
                otherAddress = chatTransactionData.getSender();
            }

            final String name;
            final String sender;
            final String senderName;

            synchronized (this.nameDataLock) {
                name = this.primaryNameByOwner.getOrDefault(otherAddress, otherAddress);
                sender = chatTransactionData.getSender();
                senderName = this.primaryNameByOwner.getOrDefault(chatTransactionData.getSender(), chatTransactionData.getSender());
            }

            long timestamp = chatTransactionData.getTimestamp();

            final ActiveChats.DirectChat directChat = new ActiveChats.DirectChat(otherAddress, name, timestamp, sender, senderName);

            directChatsByOtherAddress.compute(
                    otherAddress,
                    (key, existingValue) ->
                        (existingValue == null || directChat.getTimestamp() > existingValue.getTimestamp())
                            ? directChat
                            : existingValue  );
        }

        return new ArrayList<>(directChatsByOtherAddress.values());
    }

    @Override
    public List<ChatTransactionData> getAllChatData() {
        return getValidatedChatTransactions();
    }

    /**
     * From Signature
     *
     * Get chat data for transaction signature.
     *
     * @param signature58 the signature enocoded to a Base58 string
     *
     * @return the chat data
     */
    public ChatTransactionData fromSignature(String signature58) {
        synchronized (this.dataBySignature) {
            return this.dataBySignature.get(signature58);
        }
    }

    /**
     * Compute Nonce
     *
     * Compute nonce for memory proof of work and set it to the chat data.
     *
     * @param chatTransactionData the chat data to compute and set nonce to
     */
    public void computeNonce( ChatTransactionData chatTransactionData)  {
        byte[] transactionBytes;

        try {
            transactionBytes = TransactionTransformer.toBytesForSigning(chatTransactionData);
        } catch (TransformationException e) {
            throw new RuntimeException("Unable to transform transaction to byte array for verification", e);
        }

        // Clear nonce from transactionBytes
        ChatTransactionTransformer.clearNonce(transactionBytes);

        int difficulty = getDifficulty(chatTransactionData);

        // Calculate nonce
        chatTransactionData.setNonce(MemoryPoW.compute2(transactionBytes, ChatTransaction.POW_BUFFER_SIZE, difficulty));
    }
}