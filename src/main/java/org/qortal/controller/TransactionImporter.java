package org.qortal.controller;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.controller.arbitrary.PeerMessage;
import org.qortal.data.block.BlockData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.network.Network;
import org.qortal.network.Peer;
import org.qortal.network.message.GetTransactionMessage;
import org.qortal.network.message.Message;
import org.qortal.network.message.TransactionMessage;
import org.qortal.network.message.TransactionSignaturesMessage;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.settings.Settings;
import org.qortal.transaction.Transaction;
import org.qortal.transform.TransformationException;
import org.qortal.utils.Base58;
import org.qortal.utils.NTP;

import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.stream.Collectors;

public class TransactionImporter extends Thread {

    private static final Logger LOGGER = LogManager.getLogger(TransactionImporter.class);

    private static TransactionImporter instance;
    private volatile boolean isStopping = false;

    private static final int MAX_INCOMING_TRANSACTIONS = 5000;

    /** Minimum time before considering an invalid unconfirmed transaction as "stale" */
    public static final long INVALID_TRANSACTION_STALE_TIMEOUT = 30 * 60 * 1000L; // ms
    /** Minimum frequency to re-request stale unconfirmed transactions from peers, to recheck validity */
    public static final long INVALID_TRANSACTION_RECHECK_INTERVAL = 60 * 60 * 1000L; // ms\
    /** Minimum frequency to re-request expired unconfirmed transactions from peers, to recheck validity
     * This mainly exists to stop expired transactions from bloating the list */
    public static final long EXPIRED_TRANSACTION_RECHECK_INTERVAL = 10 * 60 * 1000L; // ms


    /** Map of incoming transaction that are in the import queue. Key is transaction data, value is whether signature has been validated. */
    private final Map<TransactionData, Boolean> incomingTransactions = Collections.synchronizedMap(new HashMap<>());

    /** Map of recent invalid unconfirmed transactions. Key is base58 transaction signature, value is do-not-request expiry timestamp. */
    private final Map<String, Long> invalidUnconfirmedTransactions = Collections.synchronizedMap(new HashMap<>());

    /** Cached list of unconfirmed transactions, used when counting per creator. This is replaced regularly */
    public static List<TransactionData> unconfirmedTransactionsCache = null;

    public TransactionImporter() {
        signatureMessageScheduler.scheduleAtFixedRate(this::processNetworkTransactionSignaturesMessage, 60, 1, TimeUnit.SECONDS);
        getTransactionMessageScheduler.scheduleAtFixedRate(this::processNetworkGetTransactionMessages, 60, 1, TimeUnit.SECONDS);
        getUnconfirmedTransactionsMessageScheduler.scheduleAtFixedRate(this::processNetworkGetUnconfirmedTransactionsMessages, 60, 1, TimeUnit.SECONDS);
    }

    public static synchronized TransactionImporter getInstance() {
        if (instance == null) {
            instance = new TransactionImporter();
        }

        return instance;
    }

    @Override
    public void run() {
        Thread.currentThread().setName("Transaction Importer");

        try {
            while (!Controller.isStopping()) {
                Thread.sleep(500L);

                // Process incoming transactions queue
                validateTransactionsInQueue();
                importTransactionsInQueue();

                // Clean up invalid incoming transactions list
                cleanupInvalidTransactionsList(NTP.getTime());
            }
        } catch (InterruptedException e) {
            // Fall through to exit thread
        }
    }

    public void shutdown() {
        isStopping = true;
        this.interrupt();

        // Shutdown all schedulers
        LOGGER.info("Shutting down TransactionImporter schedulers");
        try {
            getTransactionMessageScheduler.shutdownNow();
            getUnconfirmedTransactionsMessageScheduler.shutdownNow();
            signatureMessageScheduler.shutdownNow();

            if (!getTransactionMessageScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                LOGGER.warn("getTransactionMessageScheduler did not terminate in time");
            }
            if (!getUnconfirmedTransactionsMessageScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                LOGGER.warn("getUnconfirmedTransactionsMessageScheduler did not terminate in time");
            }
            if (!signatureMessageScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                LOGGER.warn("signatureMessageScheduler did not terminate in time");
            }
        } catch (InterruptedException e) {
            LOGGER.warn("Interrupted while waiting for TransactionImporter schedulers to terminate", e);
            Thread.currentThread().interrupt();
        }
    }


    // Incoming transactions queue

    private boolean incomingTransactionQueueContains(byte[] signature) {
        synchronized (incomingTransactions) {
            return incomingTransactions.keySet().stream().anyMatch(t -> Arrays.equals(t.getSignature(), signature));
        }
    }

    private void removeIncomingTransaction(byte[] signature) {
        incomingTransactions.keySet().removeIf(t -> Arrays.equals(t.getSignature(), signature));
    }

    /**
     * Retrieve all pending unconfirmed transactions that have had their signatures validated.
     * @return a list of TransactionData objects, with valid signatures.
     */
    private List<TransactionData> getCachedSigValidTransactions() {
        synchronized (this.incomingTransactions) {
            return this.incomingTransactions.entrySet().stream()
                    .filter(t -> Boolean.TRUE.equals(t.getValue()))
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toList());
        }
    }

    /**
     * Validate the signatures of any transactions pending import, then update their
     * entries in the queue to mark them as valid/invalid.
     *
     * No database lock is required.
     */
    private void validateTransactionsInQueue() {
        if (this.incomingTransactions.isEmpty()) {
            // Nothing to do?
            return;
        }

        try (final Repository repository = RepositoryManager.getRepository()) {
            // Take a snapshot of incomingTransactions, so we don't need to lock it while processing
            Map<TransactionData, Boolean> incomingTransactionsCopy = Map.copyOf(this.incomingTransactions);

            int unvalidatedCount = Collections.frequency(incomingTransactionsCopy.values(), Boolean.FALSE);
            int validatedCount = 0;

            if (unvalidatedCount > 0) {
                LOGGER.debug("Validating signatures in incoming transactions queue (size {})...", unvalidatedCount);
            }

            // A list of all currently pending transactions that have valid signatures
            List<Transaction> sigValidTransactions = new ArrayList<>();

            // A list of signatures that became valid in this round
            List<byte[]> newlyValidSignatures = new ArrayList<>();

            boolean isLiteNode = Settings.getInstance().isLite();

            // We need the latest block in order to check for expired transactions
            BlockData latestBlock = Controller.getInstance().getChainTip();

            // Signature validation round - does not require blockchain lock
            for (Map.Entry<TransactionData, Boolean> transactionEntry : incomingTransactionsCopy.entrySet()) {
                // Quick exit?
                if (isStopping) {
                    return;
                }

                TransactionData transactionData = transactionEntry.getKey();
                Transaction transaction = Transaction.fromData(repository, transactionData);
                String signature58 = Base58.encode(transactionData.getSignature());

                Long now = NTP.getTime();
                if (now == null) {
                    return;
                }

                // Drop expired transactions before they are considered "sig valid"
                if (latestBlock != null && transaction.getDeadline() <= latestBlock.getTimestamp()) {
                    LOGGER.debug("Removing expired {} transaction {} from import queue", transactionData.getType().name(), signature58);
                    removeIncomingTransaction(transactionData.getSignature());
                    invalidUnconfirmedTransactions.put(signature58, (now + EXPIRED_TRANSACTION_RECHECK_INTERVAL));
                    continue;
                }

                // Only validate signature if we haven't already done so
                Boolean isSigValid = transactionEntry.getValue();
                if (!Boolean.TRUE.equals(isSigValid)) {
                    if (isLiteNode) {
                        // Lite nodes can't easily validate transactions, so for now we will have to assume that everything is valid
                        sigValidTransactions.add(transaction);
                        newlyValidSignatures.add(transactionData.getSignature());
                        // Add mark signature as valid if transaction still exists in import queue
                        incomingTransactions.computeIfPresent(transactionData, (k, v) -> Boolean.TRUE);
                        continue;
                    }

                    if (!transaction.isSignatureValid()) {
                        LOGGER.debug("Ignoring {} transaction {} with invalid signature", transactionData.getType().name(), signature58);
                        removeIncomingTransaction(transactionData.getSignature());

                        // Also add to invalidIncomingTransactions map
                        now = NTP.getTime();
                        if (now != null) {
                            Long expiry = now + INVALID_TRANSACTION_RECHECK_INTERVAL;
                            LOGGER.trace("Adding invalid transaction {} to invalidUnconfirmedTransactions...", signature58);
                            // Add to invalidUnconfirmedTransactions so that we don't keep requesting it
                            invalidUnconfirmedTransactions.put(signature58, expiry);
                        }

                        // We're done with this transaction
                        continue;
                    }

                    // Count the number that were validated in this round, for logging purposes
                    validatedCount++;

                    // Add mark signature as valid if transaction still exists in import queue
                    incomingTransactions.computeIfPresent(transactionData, (k, v) -> Boolean.TRUE);

                    // Signature validated in this round
                    newlyValidSignatures.add(transactionData.getSignature());

                } else {
                    LOGGER.trace(() -> String.format("Transaction %s known to have valid signature", Base58.encode(transactionData.getSignature())));
                }

                // Signature valid - add to shortlist
                sigValidTransactions.add(transaction);
            }

            if (unvalidatedCount > 0) {
                LOGGER.debug("Finished validating signatures in incoming transactions queue (valid this round: {}, total pending import: {})...", validatedCount, sigValidTransactions.size());
            }

        } catch (DataException e) {
            LOGGER.error("Repository issue while processing incoming transactions", e);
        }
    }

    /**
     * Import any transactions in the queue that have valid signatures.
     *
     * A database lock is required.
     */
    private void importTransactionsInQueue() {
        List<TransactionData> sigValidTransactions = this.getCachedSigValidTransactions();
        if (sigValidTransactions.isEmpty()) {
            // Don't bother locking if there are no new transactions to process
            return;
        }

        // discard general chat transactions, chat transactions with no group and no recipient
        sigValidTransactions.removeIf(
                transactionData -> transactionData.getType() == Transaction.TransactionType.CHAT &&
                        transactionData.getTxGroupId() == 0 &&
                        transactionData.getRecipient() == null
        );

        if (Synchronizer.getInstance().isSyncRequested() || Synchronizer.getInstance().isSynchronizing()) {
            // Prioritize syncing, and don't attempt to lock
            return;
        }

        ReentrantLock blockchainLock = Controller.getInstance().getBlockchainLock();
        if (!blockchainLock.tryLock()) {
            LOGGER.debug("Too busy to import incoming transactions queue");
            return;
        }

        LOGGER.debug("Importing incoming transactions queue (size {})...", sigValidTransactions.size());

        int processedCount = 0;
        try (final Repository repository = RepositoryManager.getRepository()) {

            // Use a single copy of the unconfirmed transactions list for each cycle, to speed up constant lookups
            // when counting unconfirmed transactions by creator.
            List<TransactionData> unconfirmedTransactions = repository.getTransactionRepository().getUnconfirmedTransactions();
            unconfirmedTransactions.removeIf(t -> t.getType() == Transaction.TransactionType.CHAT);
            unconfirmedTransactionsCache = unconfirmedTransactions;

            // A list of signatures were imported in this round
            List<byte[]> newlyImportedSignatures = new ArrayList<>();

            // Import transactions with valid signatures
            try {
                for (int i = 0; i < sigValidTransactions.size(); ++i) {
                    if (isStopping) {
                        return;
                    }

                    if (Synchronizer.getInstance().isSyncRequestPending()) {
                        LOGGER.debug("Breaking out of transaction importing with {} remaining, because a sync request is pending", sigValidTransactions.size() - i);
                        return;
                    }

                    TransactionData transactionData = sigValidTransactions.get(i);
                    Transaction transaction = Transaction.fromData(repository, transactionData);

                    Transaction.ValidationResult validationResult = transaction.importAsUnconfirmed();
                    processedCount++;

                    switch (validationResult) {
                        case TRANSACTION_ALREADY_EXISTS: {
                            LOGGER.trace(() -> String.format("Ignoring existing transaction %s", Base58.encode(transactionData.getSignature())));
                            break;
                        }

                        case NO_BLOCKCHAIN_LOCK: {
                            // Is this even possible considering we acquired blockchain lock above?
                            LOGGER.trace(() -> String.format("Couldn't lock blockchain to import unconfirmed transaction %s", Base58.encode(transactionData.getSignature())));
                            break;
                        }

                        case OK: {
                            LOGGER.debug(() -> String.format("Imported %s transaction %s", transactionData.getType().name(), Base58.encode(transactionData.getSignature())));

                            // Add to the unconfirmed transactions cache
                            if (transactionData.getType() != Transaction.TransactionType.CHAT && unconfirmedTransactionsCache != null) {
                                unconfirmedTransactionsCache.add(transactionData);
                            }

                            // Signature imported in this round
                            newlyImportedSignatures.add(transactionData.getSignature());

                            break;
                        }

                        // All other invalid cases:
                        default: {
                            final String signature58 = Base58.encode(transactionData.getSignature());
                            LOGGER.debug(() -> String.format("Ignoring invalid (%s) %s transaction %s", validationResult.name(), transactionData.getType().name(), signature58));

                            Long now = NTP.getTime();
                            if (now != null && now - transactionData.getTimestamp() > INVALID_TRANSACTION_STALE_TIMEOUT) {
                                Long expiryLength = INVALID_TRANSACTION_RECHECK_INTERVAL;

                                if (validationResult == Transaction.ValidationResult.TIMESTAMP_TOO_OLD) {
                                    // Use shorter recheck interval for expired transactions
                                    expiryLength = EXPIRED_TRANSACTION_RECHECK_INTERVAL;
                                }

                                Long expiry = now + expiryLength;
                                LOGGER.trace("Adding stale invalid transaction {} to invalidUnconfirmedTransactions...", signature58);
                                // Invalid, unconfirmed transaction has become stale - add to invalidUnconfirmedTransactions so that we don't keep requesting it
                                invalidUnconfirmedTransactions.put(signature58, expiry);
                            }
                        }
                    }

                    // Transaction has been processed, even if only to reject it
                    removeIncomingTransaction(transactionData.getSignature());
                }

                if (!newlyImportedSignatures.isEmpty()) {
                    LOGGER.debug("Broadcasting {} newly imported signatures", newlyImportedSignatures.size());
                    Message newTransactionSignatureMessage = new TransactionSignaturesMessage(newlyImportedSignatures);
                    Network.getInstance().broadcast(broadcastPeer -> newTransactionSignatureMessage);
                }
            } finally {
                LOGGER.debug("Finished importing {} incoming transaction{}", processedCount, (processedCount == 1 ? "" : "s"));
                blockchainLock.unlock();

                // Clear the unconfirmed transaction cache so new data can be populated in the next cycle
                unconfirmedTransactionsCache = null;
            }
        } catch (DataException e) {
            LOGGER.error("Repository issue while importing incoming transactions", e);
        }
    }

    private void cleanupInvalidTransactionsList(Long now) {
        if (now == null) {
            return;
        }
        // Periodically remove invalid unconfirmed transactions from the list, so that they can be fetched again
        invalidUnconfirmedTransactions.entrySet().removeIf(entry -> entry.getValue() == null || entry.getValue() < now);
    }


    // Network handlers

    public void onNetworkTransactionMessage(Peer peer, Message message) {
        TransactionMessage transactionMessage = (TransactionMessage) message;
        TransactionData transactionData = transactionMessage.getTransactionData();

        if (this.incomingTransactions.size() < MAX_INCOMING_TRANSACTIONS) {
            synchronized (this.incomingTransactions) {
                if (!incomingTransactionQueueContains(transactionData.getSignature())) {
                    this.incomingTransactions.put(transactionData, Boolean.FALSE);
                }
            }
        }
    }

    // List to collect messages
    private final List<PeerMessage> getTransactionMessageList = new ArrayList<>();
    // Lock to synchronize access to the list
    private final Object getTransactionMessageLock = new Object();

    // Scheduled executor service to process messages every second
    private final ScheduledExecutorService getTransactionMessageScheduler = Executors.newScheduledThreadPool(1);

    public void onNetworkGetTransactionMessage(Peer peer, Message message) {

        synchronized (getTransactionMessageLock) {
            getTransactionMessageList.add(new PeerMessage(peer, message));
        }
    }

    private void processNetworkGetTransactionMessages() {
        if (Controller.isStopping()) {
            return;
        }

        try {
            List<PeerMessage> messagesToProcess;
            synchronized (getTransactionMessageLock) {
                messagesToProcess = new ArrayList<>(getTransactionMessageList);
                getTransactionMessageList.clear();
            }

            if( messagesToProcess.isEmpty() ) return;

            Map<String, PeerMessage> peerMessageBySignature58 = new HashMap<>(messagesToProcess.size());

            for( PeerMessage peerMessage : messagesToProcess ) {
                GetTransactionMessage getTransactionMessage = (GetTransactionMessage) peerMessage.getMessage();
                byte[] signature = getTransactionMessage.getSignature();

                peerMessageBySignature58.put(Base58.encode(signature), peerMessage);
            }

            // Firstly check the sig-valid transactions that are currently queued for import
            Map<String, TransactionData> transactionsCachedBySignature58
                = this.getCachedSigValidTransactions().stream()
                    .collect(Collectors.toMap(t -> Base58.encode(t.getSignature()), Function.identity()));

            Map<Boolean, List<Map.Entry<String, PeerMessage>>> transactionsCachedBySignature58Partition
                = peerMessageBySignature58.entrySet().stream()
                    .collect(Collectors.partitioningBy(entry -> transactionsCachedBySignature58.containsKey(entry.getKey())));

            List<byte[]> signaturesNeeded
                = transactionsCachedBySignature58Partition.get(false).stream()
                    .map(Map.Entry::getValue)
                    .map(PeerMessage::getMessage)
                    .map(message -> (GetTransactionMessage) message)
                    .map(GetTransactionMessage::getSignature)
                    .collect(Collectors.toList());

            // transaction found in the import queue
            Map<String, TransactionData> transactionsToSendBySignature58 = new HashMap<>(messagesToProcess.size());
            for( Map.Entry<String, PeerMessage> entry : transactionsCachedBySignature58Partition.get(true)) {
                transactionsToSendBySignature58.put(entry.getKey(), transactionsCachedBySignature58.get(entry.getKey()));
            }

            if( !signaturesNeeded.isEmpty() ) {
                // Not found in import queue, so try the database
                try (final Repository repository = RepositoryManager.getRepository()) {
                    transactionsToSendBySignature58.putAll(
                            repository.getTransactionRepository().fromSignatures(signaturesNeeded).stream()
                                    .collect(Collectors.toMap(transactionData -> Base58.encode(transactionData.getSignature()), Function.identity()))
                    );
                } catch (DataException e) {
                    LOGGER.error(e.getMessage(), e);
                }
            }

            for( final Map.Entry<String, TransactionData> entry : transactionsToSendBySignature58.entrySet() ) {

                PeerMessage peerMessage = peerMessageBySignature58.get(entry.getKey());
                final Message message = peerMessage.getMessage();
                final Peer peer = peerMessage.getPeer();

                Runnable sendTransactionMessageRunner = () -> sendTransactionMessage(entry.getKey(), entry.getValue(), message, peer);
                Thread sendTransactionMessageThread = new Thread(sendTransactionMessageRunner);
                sendTransactionMessageThread.start();
            }
        } catch (Exception e) {
            LOGGER.error(e.getMessage(),e);
        }
    }

    private static void sendTransactionMessage(String signature58, TransactionData data, Message message, Peer peer) {
        try {
            Message transactionMessage = new TransactionMessage(data);
            transactionMessage.setId(message.getId());

            if (!peer.sendMessage(transactionMessage))
                peer.disconnect("failed to send transaction");
        }
        catch (TransformationException e) {
            LOGGER.error(String.format("Serialization issue while sending transaction %s to peer %s", signature58, peer), e);
        }
        catch (Exception e) {
            LOGGER.error(e.getMessage(), e);
        }
    }

    // List to collect messages
    private final List<PeerMessage> getUnconfirmedTransactionsMessageList = new ArrayList<>();
    // Lock to synchronize access to the list
    private final Object getUnconfirmedTransactionsMessageLock = new Object();

    // Scheduled executor service to process messages every second
    private final ScheduledExecutorService getUnconfirmedTransactionsMessageScheduler = Executors.newScheduledThreadPool(1);

    public void onNetworkGetUnconfirmedTransactionsMessage(Peer peer, Message message) {
        synchronized (getUnconfirmedTransactionsMessageLock) {
            getUnconfirmedTransactionsMessageList.add(new PeerMessage(peer, message));
        }
    }

    private void processNetworkGetUnconfirmedTransactionsMessages() {
        if (Controller.isStopping()) {
            return;
        }

        List<PeerMessage> messagesToProcess;
        synchronized (getUnconfirmedTransactionsMessageLock) {
            messagesToProcess = new ArrayList<>(getUnconfirmedTransactionsMessageList);
            getUnconfirmedTransactionsMessageList.clear();
        }

        if( messagesToProcess.isEmpty() ) return;

        List<byte[]> signatures = Collections.emptyList();

        // If we're NOT up-to-date then don't send out unconfirmed transactions
        // as it's possible they are already included in a later block that we don't have.
        if (Controller.getInstance().isUpToDate()) {
            try (final Repository repository = RepositoryManager.getRepository()) {
                signatures = repository.getTransactionRepository().getUnconfirmedTransactionSignatures();
            } catch (DataException e) {
                LOGGER.error(String.format("Repository issue while sending unconfirmed transaction signatures to peers"), e);
            }
        }

        Message transactionSignaturesMessage = new TransactionSignaturesMessage(signatures);

        for( PeerMessage messageToProcess : messagesToProcess ) {
            if (!messageToProcess.getPeer().sendMessage(transactionSignaturesMessage))
                messageToProcess.getPeer().disconnect("failed to send unconfirmed transaction signatures");
        }
    }

    // List to collect messages
    private final List<PeerMessage> signatureMessageList = new ArrayList<>();
    // Lock to synchronize access to the list
    private final Object signatureMessageLock = new Object();

    // Scheduled executor service to process messages every second
    private final ScheduledExecutorService signatureMessageScheduler = Executors.newScheduledThreadPool(1);

    public void onNetworkTransactionSignaturesMessage(Peer peer, Message message) {
        synchronized (signatureMessageLock) {
            signatureMessageList.add(new PeerMessage(peer, message));
        }
    }

    public void processNetworkTransactionSignaturesMessage() {
        if (Controller.isStopping()) {
            return;
        }

        try {
            List<PeerMessage> messagesToProcess;
            synchronized (signatureMessageLock) {
                messagesToProcess = new ArrayList<>(signatureMessageList);
                signatureMessageList.clear();
            }

            Map<String, byte[]> signatureBySignature58 = new HashMap<>(messagesToProcess.size() * 10);
            Map<String, Peer> peerBySignature58 = new HashMap<>( messagesToProcess.size() * 10 );

            for( PeerMessage peerMessage : messagesToProcess ) {

                TransactionSignaturesMessage transactionSignaturesMessage = (TransactionSignaturesMessage) peerMessage.getMessage();
                List<byte[]> signatures = transactionSignaturesMessage.getSignatures();

                for (byte[] signature : signatures) {
                    String signature58 = Base58.encode(signature);
                    if (invalidUnconfirmedTransactions.containsKey(signature58)) {
                        // Previously invalid transaction - don't keep requesting it
                        // It will be periodically removed from invalidUnconfirmedTransactions to allow for rechecks
                        continue;
                    }

                    // Ignore if this transaction is in the queue
                    if (incomingTransactionQueueContains(signature)) {
                        LOGGER.trace(() -> String.format("Ignoring existing queued transaction %s from peer %s", Base58.encode(signature), peerMessage.getPeer()));
                        continue;
                    }

                    signatureBySignature58.put(signature58, signature);
                    peerBySignature58.put(signature58, peerMessage.getPeer());
                }
            }

            if( !signatureBySignature58.isEmpty() ) {
                try (final Repository repository = RepositoryManager.getRepository()) {

                    // remove signatures in db already
                    repository.getTransactionRepository()
                            .fromSignatures(new ArrayList<>(signatureBySignature58.values())).stream()
                            .map(TransactionData::getSignature)
                            .map(signature -> Base58.encode(signature))
                            .forEach(signature58 -> signatureBySignature58.remove(signature58));
                } catch (DataException e) {
                    LOGGER.error(String.format("Repository issue while processing unconfirmed transactions from peer"), e);
                }
            }

            // Check isInterrupted() here and exit fast
            if (Thread.currentThread().isInterrupted())
                return;

            for (Map.Entry<String, byte[]> entry : signatureBySignature58.entrySet()) {

                Peer peer = peerBySignature58.get(entry.getKey());

                // Fetch actual transaction data from peer
                Message getTransactionMessage = new GetTransactionMessage(entry.getValue());
                if (peer != null && !peer.sendMessage(getTransactionMessage)) {
                    peer.disconnect("failed to request transaction");
                }
            }
        } catch (Exception e) {
            LOGGER.error(e.getMessage(), e);
        }
    }

}
