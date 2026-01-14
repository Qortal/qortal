package org.qortal.controller;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bitcoinj.core.Coin;
import org.json.JSONArray;
import org.json.JSONObject;
import org.qortal.controller.tradebot.TradeBot;
import org.qortal.controller.tradebot.TradeStates;
import org.qortal.crosschain.ACCT;
import org.qortal.crosschain.AcctMode;
import org.qortal.crosschain.Bitcoiny;
import org.qortal.crosschain.ForeignBlockchain;
import org.qortal.crosschain.SupportedBlockchain;
import org.qortal.crypto.Crypto;
import org.qortal.data.at.ATData;
import org.qortal.data.crosschain.CrossChainTradeData;
import org.qortal.data.crosschain.ForeignFeeData;
import org.qortal.data.crosschain.TradeBotData;
import org.qortal.data.crosschain.ForeignFeeDecodedData;
import org.qortal.data.crosschain.ForeignFeeEncodedData;
import org.qortal.event.Event;
import org.qortal.event.EventBus;
import org.qortal.event.FeeWaitingEvent;
import org.qortal.event.LockingFeeUpdateEvent;
import org.qortal.event.RequiredFeeUpdateEvent;
import org.qortal.event.Listener;
import org.qortal.network.Network;
import org.qortal.network.Peer;
import org.qortal.network.message.ForeignFeesMessage;
import org.qortal.network.message.GetForeignFeesMessage;
import org.qortal.network.message.Message;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.repository.hsqldb.HSQLDBImportExport;
import org.qortal.settings.Settings;
import org.qortal.utils.Base58;
import org.qortal.utils.ForeignFeesMessageUtils;
import org.qortal.utils.NTP;
import org.qortal.utils.NamedThreadFactory;
import org.qortal.utils.Triple;

import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;

public class ForeignFeesManager implements Listener {

    private static final Logger LOGGER = LogManager.getLogger(ForeignFeesManager.class);

    public static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static final String SIGNED_FOREIGN_FEES_TYPE = "signedForeignFees";
    public static final String SIGNED_FOREIGN_FEES_FILE_NAME = "SignedForeignFees.json";
    public static final String CURRENT_DATASET_LABEL = "current";
    private static final String REQUIRED_FOREIGN_FEES_TYPE = "requiredForeignFees";
    private static final String LOCKING_FOREIGN_FEES_TYPE = "lockingForeignFees";
    public static final String REQUIRED_FOREIGN_FEES_FILE_NAME = "RequiredForeignFees.json";
    public static final String LOCKING_FOREIGN_FEES_FILE_NAME = "LockingForeignFees.json";

    private final ScheduledExecutorService executor
        = Executors.newScheduledThreadPool(4, new NamedThreadFactory("Foreign Fee Manager", Thread.NORM_PRIORITY));

    private volatile boolean isStopping = false;

    private final Set<ForeignFeeDecodedData> foreignFeesImportQueue = ConcurrentHashMap.newKeySet();

    /**
     * Cache of signed foreign fees, keyed by AT address
     */
    private final ConcurrentHashMap<String, Optional<ForeignFeeDecodedData>> signedByAT = new ConcurrentHashMap<>();

    /**
     * Cache of unsigned foreign fees on this node, key by AT address
     */
    private final ConcurrentHashMap<String, ForeignFeeEncodedData> unsignedByAT = new ConcurrentHashMap<>();

    /**
     * Cache of trade offers, keyed by creator address
     */
    private final ConcurrentHashMap<String, List<CrossChainTradeData>> offersByAddress = new ConcurrentHashMap<>();

    /**
     * Need to Backup Locking Foreign Fees?
     *
     * Set when the locking foreign fees need to be backed up to a file.
     */
    private AtomicBoolean needToBackupLockingForeignFees = new AtomicBoolean(true);

    /**
     * Need to Backup Required Foreign Fees?
     *
     * Set when the required foreign fees need to be backed up to a file.
     */
    private AtomicBoolean needToBackupRequiredForeignFees = new AtomicBoolean(true);

    /**
     * Need to Backup Signed Foreign Fees?
     *
     * Set when the signed foreign fees for this node need to be backed up to a file.
     */
    private AtomicBoolean needToBackupSignedForeignFees = new AtomicBoolean(true);

    private ForeignFeesManager() {

        EventBus.INSTANCE.addListener(this);
    }

    /**
     * Import Data
     *
     * Import signed transaction data for this node and the required fees for unlocking foreign trade funds
     * for this node.
     */
    private void importData() {

        try {
            String exportPath = Settings.getInstance().getExportPath();

            // import signed foreign fees
            try {
                Path importSignedForeignFeesPath = Paths.get(exportPath, SIGNED_FOREIGN_FEES_FILE_NAME);
                importDataFromFile(importSignedForeignFeesPath.toString());
            }
            catch (FileNotFoundException e) {
                LOGGER.warn(e.getMessage());
            }

            // import required foreign fees
            try {
                Path importRequiredForeignFeespath = Paths.get(exportPath, REQUIRED_FOREIGN_FEES_FILE_NAME);
                importDataFromFile(importRequiredForeignFeespath.toString());
            }
            catch (FileNotFoundException e) {
                LOGGER.warn(e.getMessage());
            }

            // import locking foreign fees
            try {
                Path importLockingForeignFeespath = Paths.get(exportPath, LOCKING_FOREIGN_FEES_FILE_NAME);
                importDataFromFile(importLockingForeignFeespath.toString());
            }
            catch (FileNotFoundException e) {
                LOGGER.warn(e.getMessage());
            }

        }
        catch (DataException | IOException e) {
            LOGGER.debug("Unable to import data into foreign fees manager: {}", e.getMessage());
        }
        catch (Exception e) {
            LOGGER.error(e.getMessage(), e);
        }
    }

    /**
     * Get Unsigned Fees For Address
     *
     * @param address the address
     *
     * @return the unsigned fee data
     */
    public List<ForeignFeeEncodedData> getUnsignedFeesForAddress(String address) {

        // the trade offers for this address on this node
        List<String> atAddressesForOffers
            = this.offersByAddress.getOrDefault(address, new ArrayList<>(0)).stream()
                .map( data -> data.qortalAtAddress )
                .collect(Collectors.toList());

        // the unsigned fee data for the address's trade offers
        return this.unsignedByAT.entrySet().stream()
            .filter( entry -> atAddressesForOffers.contains(entry.getKey()))
            .map( entry -> entry.getValue())
            .collect(Collectors.toList());
    }

    /**
     * Get Signed Fees
     *
     * @return the signed fee data on this node
     */
    public List<ForeignFeeDecodedData> getSignedFees() {

        return this.signedByAT.values().stream()
            .filter(Optional::isPresent)
            .map(Optional::get)
            .collect(Collectors.toList());
    }

    /**
     * Add Signed Fees
     *
     * Add signed fees to the import queue.
     *
     * @param signedFees the signed fees
     */
    public void addSignedFees(List<ForeignFeeEncodedData> signedFees) {

        LOGGER.debug("adding signed fees: count = " + signedFees.size());

        // for each encoided fee, decode and add to import queue
        for( ForeignFeeEncodedData signedFeeEncoded : signedFees ) {

            LOGGER.debug("adding to import queue: " + signedFeeEncoded);

            // decode the fee data and add to the queue
            this.foreignFeesImportQueue.add(
                new ForeignFeeDecodedData(
                    signedFeeEncoded.getTimestamp(),
                    Base58.decode(signedFeeEncoded.getData()),
                    signedFeeEncoded.getAtAddress(),
                    signedFeeEncoded.getFee()
                )
            );
            LOGGER.debug("added");
        }

        LOGGER.debug("done adding to queue: count = " + this.foreignFeesImportQueue.size());

        // process the fees immediately (not waiting for the fee process timer task already in place)
        processForeignFeesImportQueue();
    }

    @Override
    public void listen(Event event) {

        // locking fee update, then flag locking fee backup
        if( event instanceof LockingFeeUpdateEvent) {
            this.needToBackupLockingForeignFees.compareAndSet(false, true);
        }
        // if required fee update, then flag required fee update and process fees for the coin updated
        else if( event instanceof RequiredFeeUpdateEvent) {

            this.needToBackupRequiredForeignFees.compareAndSet(false, true);

            for( String address : processLocalForeignFeesForCoin(((RequiredFeeUpdateEvent) event).getBitcoiny()) ) {
                EventBus.INSTANCE.notify(new FeeWaitingEvent(true, address));
            }
        }
        //
        else if( event instanceof TradeBot.StateChangeEvent ) {

            TradeBotData data = ((TradeBot.StateChangeEvent) event).getTradeBotData();

            // if offer is waiting and the time now is determined,
            // then process the trade data to be signed later
            if( data.getStateValue() == TradeStates.State.BOB_WAITING_FOR_MESSAGE.value ) {
                Optional<Long> nowDetermined = determineNow();
                if (nowDetermined.isPresent()) {

                    long now = nowDetermined.get();
                    try (final Repository repository = RepositoryManager.getRepository()) {
                        LOGGER.debug("processing trade offer in waiting event");

                        Optional<CrossChainTradeData> offerOptional = getTradeOfferData(repository, data.getAtAddress());

                        if( offerOptional.isPresent() ) {
                            CrossChainTradeData offer = offerOptional.get();
                            this.offersByAddress.computeIfAbsent( offer.qortalCreator, x -> new ArrayList<>()).add(offer);

                            if( processTradeOfferInWaiting(now, data) ) {
                                EventBus.INSTANCE.notify(new FeeWaitingEvent(true, data.getCreatorAddress()));
                            }
                        }
                        else {
                            LOGGER.warn("offer not present for new trade bot offer = " + data);
                        }
                    } catch (IOException | DataException e) {
                        LOGGER.error(e.getMessage(), e);
                    }
                }
            }
        }
    }

    private static class SingletonContainer {
        private static final ForeignFeesManager INSTANCE = new ForeignFeesManager();
    }

    public static ForeignFeesManager getInstance() {
        return SingletonContainer.INSTANCE;
    }

    /**
     * Get Signed Foreign Fee Data By AT Address
     *
     * @return st address -> signed foreign fee data
     */
    public ConcurrentHashMap<String, Optional<ForeignFeeDecodedData>> getSignedByAT() {
        return signedByAT;
    }

    /**
     * Start Manager
     */
    public void start() {

        // import data after a 1 minute delay
        // this will get locking fees, required fees and signed data
        // there will be nothing to import the first time running this manager
        executor.schedule(this::importData, 1, TimeUnit.MINUTES);

        // process local foreign fees for all coins once after a 2 minute delay
        // this will get the unsigned fee data
        executor.schedule(this::processLocalForeignFeesForAll, 2, TimeUnit.MINUTES);

        // maintain AT's every 5 minutes
        executor.scheduleAtFixedRate(this::maintainCrossChainOffers, 3, 5, TimeUnit.MINUTES);

        // request foreign fees from peers every 5 minutes
        executor.scheduleAtFixedRate(this::requestRemoteForeignFees, 4, 5, TimeUnit.MINUTES);

        // process imported foreign fees every 5 minutes
        executor.scheduleWithFixedDelay(this::processForeignFeesImportQueue, 5, 5, TimeUnit.MINUTES);

        // backup data every 5 minutes
        executor.scheduleAtFixedRate(this::backup, 6, 5, TimeUnit.MINUTES);
    }

    /**
     * Backup
     *
     * Backup member data used by this manager.
     */
    private void backup() {

        try {
            if( this.needToBackupLockingForeignFees.compareAndSet( true, false )) {
                LOGGER.debug("backing up locking foreign fees");
                backupForeignFeeData( bitcoiny -> bitcoiny.getFeePerKb().value, LOCKING_FOREIGN_FEES_FILE_NAME, LOCKING_FOREIGN_FEES_TYPE);
            }
            if( this.needToBackupRequiredForeignFees.compareAndSet(true, false) ) {
                LOGGER.debug("backing up required foreign fees");
                backupForeignFeeData(Bitcoiny::getFeeRequired, REQUIRED_FOREIGN_FEES_FILE_NAME, REQUIRED_FOREIGN_FEES_TYPE);
            }

            if( this.needToBackupSignedForeignFees.compareAndSet( true, false ) ) {
                LOGGER.debug("backing up signed foreign fees");
                backupSignedForeignFeeData();
            }
        } catch (DataException e) {
            LOGGER.error(e.getMessage(), e);
        }
    }

    /**
     * Shutdown Manager
     */
    public void shutdown() {
        isStopping = true;
        executor.shutdownNow();
    }

    /**
     * Process Import Queue
     */
    private void processForeignFeesImportQueue() {

        LOGGER.debug("processing foreign fees import queue ...");

        if (this.foreignFeesImportQueue.isEmpty()) {

            LOGGER.debug("foreign fees import queue is empty");
            return;
        }

        LOGGER.debug("Processing foreign fee import queue (size: {})", this.foreignFeesImportQueue.size());

        Set<ForeignFeeDecodedData> foreignFeesToRemove = new HashSet<>(this.foreignFeesImportQueue.size());

        try (final Repository repository = RepositoryManager.getRepository()) {

            // for each signed foreign fee in the queue,
            // compare timestamps to prior imports, verify signature and possibly import in
            for (ForeignFeeDecodedData foreignFeeToImport : this.foreignFeesImportQueue) {
                if (isStopping)
                    return;

                // need to get the AT address for mapping key identification purposes
                String atAddress = foreignFeeToImport.getAtAddress();

                LOGGER.debug("foreign fee import, timestamp = " + getFormattedDateTime(foreignFeeToImport.getTimestamp()));

                Optional<ForeignFeeDecodedData> validatedForeignFeeData
                    = this.signedByAT.getOrDefault( atAddress, Optional.empty() );

                // if there is no established, validated foreign fee for this AT address or
                // if the import foreign fee is after the validated foreign fee,
                // then verify the signature and map it to the AT address
                if (validatedForeignFeeData.isEmpty() || validatedForeignFeeData.get().getTimestamp() < foreignFeeToImport.getTimestamp()) {

                    ATData atData = repository.getATRepository().fromATAddress(atAddress);

                    LOGGER.debug("verify signer for atAddress = " + atAddress);

                    // determine if the creator authorized the foreign fee
                    byte[] publicKey = atData.getCreatorPublicKey();
                    byte[] signature = foreignFeeToImport.getData();
                    byte[] message
                        = ForeignFeesMessageUtils.buildForeignFeesDataMessage(
                            foreignFeeToImport.getTimestamp(),
                            atAddress,
                            foreignFeeToImport.getFee()
                        );

                    // if trade offer creator authorized the imported fee,
                    // then finish the import and clear it from the unsigned mapping
                    if( Crypto.verify(publicKey, signature, message) ) {
                        LOGGER.debug("signer verified");
                        this.signedByAT.put(atAddress, Optional.of(foreignFeeToImport));
                        this.needToBackupSignedForeignFees.compareAndSet(false, true);
                        this.unsignedByAT.remove(atAddress);

                        String tradeOfferCreatorAddress = Crypto.toAddress(publicKey);
                        boolean allSignedForCreatorAddress
                            = this.offersByAddress
                                .getOrDefault(tradeOfferCreatorAddress, new ArrayList<>(0)).stream()
                                .map(data -> data.qortalAtAddress)
                                .filter(qortalAtAddress -> this.unsignedByAT.contains(qortalAtAddress))
                                .findAny()
                                .isEmpty();

                        LOGGER.debug("tradeOfferCreatorAddress = " + tradeOfferCreatorAddress);
                        LOGGER.debug("allSignedForCreatorAddress = " + allSignedForCreatorAddress);

                        if(allSignedForCreatorAddress) {
                            EventBus.INSTANCE.notify(new FeeWaitingEvent(false, tradeOfferCreatorAddress));
                        }
                    }
                    // otherwise this fee will get discarded
                    else {
                        LOGGER.debug("invalid signature");
                    }
                }
                else {
                    LOGGER.debug(
                        "skipping imported fee since the timestamp is not updated: atAddress = {}, timestamp = {}",
                        atAddress,
                        foreignFeeToImport.getTimestamp()
                    );
                }

                // now that this fee has been processed, remove it from the process queue
                foreignFeesToRemove.add(foreignFeeToImport);
            }
        } catch (Exception e) {
            LOGGER.error("Repository issue while verifying foreign fees", e);
        } finally {
            LOGGER.debug("removing foreign fees from import queue: count = " + foreignFeesToRemove.size());
            this.foreignFeesImportQueue.removeAll(foreignFeesToRemove);
        }
    }

    /**
     * Get Formatted Date Time
     *
     * For logging purposes only.
     *
     * @param timestamp utc time in milliseconds
     *
     * @return the formatted string
     */
    private static String getFormattedDateTime( long timestamp ) {

        Instant instant = Instant.ofEpochMilli(timestamp);
        ZonedDateTime zdt = ZonedDateTime.ofInstant(instant, ZoneId.systemDefault());
        String formattedDateTime = zdt.format(TIMESTAMP_FORMATTER);

        return formattedDateTime;
    }

    /**
     * Maintain AT's
     *
     * This removes fee data for AT addresses that are no longer offered on the trade portal.
     */
    private void maintainCrossChainOffers() {

        LOGGER.debug("maintaining ATs ...");

        try (final Repository repository = RepositoryManager.getRepository()) {

            List<CrossChainTradeData> crossChainTradeOffers = resetOffersByAddress(repository);

            // remove failed trades, then collect AT addresses for trade offers
            Set<String> atAddresses
                = TradeBot.getInstance()
                    .removeFailedTrades(repository, crossChainTradeOffers).stream()
                    .map( data -> data.qortalAtAddress )
                    .collect(Collectors.toSet());

            LOGGER.debug("foreign fees before AT removal: count = " + this.signedByAT.size() );

            // retain the fees for the current sell offers, remove all others
            if( retainFeeByAT(this.signedByAT, atAddresses) ) {
                this.needToBackupSignedForeignFees.compareAndSet(false, true);
            }

            LOGGER.debug("foreign fees after AT removal: count = " + this.signedByAT.size() );
        } catch (Exception e) {
            LOGGER.error(e.getMessage(), e);
        }
    }

    /**
     * Reset Offers By Address
     *
     * @param repository the data repository
     *
     * @return address -> cross chain trades
     *
     * @throws DataException
     */
    private List<CrossChainTradeData> resetOffersByAddress(Repository repository) throws DataException {
        List<CrossChainTradeData> crossChainTradeOffers = new ArrayList<>();

        // lockdown map while resetting offers by address
        synchronized( this.offersByAddress) {

            // for each supported foreign blockchaine, get AT data for trade offers
            for ( SupportedBlockchain blockchain : SupportedBlockchain.values()) {
                crossChainTradeOffers.addAll( getCrossTradeOffers(repository, blockchain) );
            }

            // group all trade offers by trade offer creator, then reset map
            Map<String, List<CrossChainTradeData>> groupedOffersByAddress
                    = crossChainTradeOffers.stream().collect(Collectors.groupingBy(data -> data.qortalCreator));

            this.offersByAddress.clear();
            this.offersByAddress.putAll(groupedOffersByAddress);
        }

        return crossChainTradeOffers;
    }

    /**
     * Retain Fee By AT
     *
     * Retain fees for a list of AT addresses; remove all others.
     *
     * @param feeByAT the foreign fee data for each trade offer AT address
     * @param atAddresses the AT addresses to retain fees for
     *
     * @return true if any removals, otherwise false
     */
    private static boolean retainFeeByAT(ConcurrentHashMap<String, Optional<ForeignFeeDecodedData>> feeByAT, Set<String> atAddresses) {

        // return value, false until there is a removal
        boolean anyRemovals = false;

        // prepare iterator for all AT -> fee mappings
        Iterator<Map.Entry<String, Optional<ForeignFeeDecodedData>>> iterator = feeByAT.entrySet().iterator();

        // iterate over all AT's mapped under management
        while (iterator.hasNext()) {
            Map.Entry<String, Optional<ForeignFeeDecodedData>> entry = iterator.next();

            // if the at address do not contain this entry in the iteration,
            // then remove it
            if (!atAddresses.contains(entry.getKey())) {

                iterator.remove();
                anyRemovals = true;
            }
        }

        return anyRemovals;
    }

    /**
     * Get Cross Trade Offers
     *
     * @param repository the data repository
     * @param blockchain the foreign blockchain supporting the trade
     *
     * @return the trade offers
     *
     * @throws DataException
     */
    private static List<CrossChainTradeData> getCrossTradeOffers(Repository repository, SupportedBlockchain blockchain) throws DataException {

        // get ACCT for the foreign blockchain
        ACCT acct = blockchain.getLatestAcct();

        // get AT's for foreign blockchain
        List<ATData> ats
            = repository.getATRepository()
                .getATsByFunctionality(acct.getCodeBytesHash(), true, null, null, null);

        // prepare the return list of cross chain data
        List<CrossChainTradeData> crossChainTradeOffers = new ArrayList<>(ats.size());

        // for each AT, get cross chain data and look for trade offer to add
        for (ATData at : ats) {

            CrossChainTradeData crossChainTrade = acct.populateTradeData(repository, at);

            // if the trade is in offering mode, then add it to return list
            if (crossChainTrade.mode == AcctMode.OFFERING) {
                crossChainTradeOffers.add(crossChainTrade);
            }
        }

        return crossChainTradeOffers;
    }

    private static Optional<CrossChainTradeData> getTradeOfferData(Repository repository, String atAddress) throws DataException {

        ATData atData = repository.getATRepository().fromATAddress(atAddress);

        ACCT acct = SupportedBlockchain.getAcctByCodeHash(atData.getCodeHash());
        CrossChainTradeData crossChainTrade = acct.populateTradeData(repository, atData);

        // if the trade is in offering mode, then add it to return list
        if (crossChainTrade.mode == AcctMode.OFFERING) {
            return Optional.of(crossChainTrade);
        }
        else {
            return Optional.empty();
        }
    }

    /**
     * Request data from other peers
     */
    private void requestRemoteForeignFees() {

        LOGGER.debug("requesting remote foreign fees ...");

        if (!isUpToDate()) return;

        LOGGER.debug("Requesting foreign fees via broadcast...");

        Message message
            = new GetForeignFeesMessage(
                this.signedByAT.values().stream()
                    .filter(Optional::isPresent).map(Optional::get)
                    .collect(Collectors.toList())
            );

        Network.getInstance().broadcast(peer -> message);

        LOGGER.debug("Requested foreign fees via broadcast...");
    }

    /**
     * Is Up To Date?
     *
     * @return true if up to date, otherwise false
     */
    private static boolean isUpToDate() {
        final Long now = NTP.getTime();

        if (now == null) {

            LOGGER.warn("time is null, aborting");
            return false;
        }

        if (!Controller.getInstance().isUpToDate()) {

            LOGGER.debug("not up to date, aborting");
            return false;
        }
        return true;
    }

    /**
     * Process foreign fees for all coins
     *
     * Collect foreign fees for trades waiting locally and store to this manager.
     *
     * @return if any fee signatures are needed after this process
     */
    private void processLocalForeignFeesForAll() {

        Set<String> addressesThatNeedSignatures = new HashSet<>();

        List<String> names
            = Arrays.stream(SupportedBlockchain.values())
                .map( value -> value.getLatestAcct().getClass().getSimpleName())
                .collect(Collectors.toList());

        for( String name : names ) {
            ForeignBlockchain blockchain = SupportedBlockchain.getAcctByName(name).getBlockchain();

            if( blockchain instanceof Bitcoiny ) {
                addressesThatNeedSignatures.addAll( processLocalForeignFeesForCoin((Bitcoiny) blockchain) );
            }
        }

        for( String addressThatNeedsSignature : addressesThatNeedSignatures ) {
            EventBus.INSTANCE.notify(new FeeWaitingEvent(true, addressThatNeedsSignature));
        }
    }

    /**
     * Process foreign fees for coin
     *
     * Collect foreign fees for trades waiting locally and store to this manager.
     *
     * @param bitcoiny the coin
     *
     * @return addresses that need fee signatures
     */
    private Set<String> processLocalForeignFeesForCoin(final Bitcoiny bitcoiny) {

        Set<String> addressesThatNeedSignatures = new HashSet<>();

        LOGGER.debug("processing local foreign fees ...");

        Optional<Long> nowDetermined = determineNow();
        if (nowDetermined.isEmpty()){
            return new HashSet<>(0);
        }

        long now = nowDetermined.get();

        try (final Repository repository = RepositoryManager.getRepository()) {
            List<TradeBotData> allTradeBotData = repository.getCrossChainRepository().getAllTradeBotData();

            // collect all local trade offers waiting
            List<TradeBotData> tradeOffersWaiting
                    = allTradeBotData.stream()
                    .filter(d -> d.getStateValue() == TradeStates.State.BOB_WAITING_FOR_MESSAGE.value)
                    .filter(d -> SupportedBlockchain.getAcctByName( d.getAcctName() ).getBlockchain().equals( bitcoiny ))
                    .collect(Collectors.toList());

            LOGGER.debug("trade offers waiting: count = " + tradeOffersWaiting.size());

            // process each local trade offer waiting (listed)
            for (TradeBotData tradeOfferWaiting : tradeOffersWaiting) {

                // process trade offer first,
                // then reset the fee signatures needed status next relative to prior status
                if(processTradeOfferInWaiting(now, tradeOfferWaiting) ) {
                    addressesThatNeedSignatures.add(tradeOfferWaiting.getCreatorAddress());
                }
            }
        } catch (Exception e) {
            LOGGER.error(e.getMessage(), e);
        }

        return addressesThatNeedSignatures;
    }

    /**
     * Determine Now
     *
     * @return now if in synce, otherwise empty
     */
    private static Optional<Long> determineNow() {
        // if time is not available, then abort
        Long now = NTP.getTime();
        if (now == null) {

            LOGGER.warn("current time is not available, abort sending foreign fees");
            return Optional.empty();
        }

        // if 2 hours behind w/o recovery mode, then abort
        final Long minLatestBlockTimestamp = now - (2 * 60 * 60 * 1000L);
        if (!Controller.getInstance().isUpToDate(minLatestBlockTimestamp) && !Synchronizer.getInstance().getRecoveryMode()) {

            LOGGER.warn("out of sync, abort sending foreign fees");
            return Optional.empty();
        }
        return Optional.of(now);
    }

    /**
     * Process Trade Offer In Waiting
     *
     * @param now the time in millis for now
     * @param tradeOfferWaiting the trade offer in waiting
     *
     * @return true if the fee for this offer needs to be signed, otherwise false
     *
     * @throws IOException
     */
    private boolean processTradeOfferInWaiting(Long now, TradeBotData tradeOfferWaiting) throws IOException {

        boolean isFeeWaiting = false;

        // derive the supported blockchain for the trade offer waiting
        String foreignBlockchain = tradeOfferWaiting.getForeignBlockchain();
        SupportedBlockchain supportedBlockchain = SupportedBlockchain.fromString(foreignBlockchain);

        LOGGER.debug("trade offer waiting: blockchain = " + foreignBlockchain);

        // if the supported blockchain is a Bitcoiny blockchain, then the fee will be available
        if (supportedBlockchain.getInstance() instanceof Bitcoiny) {

            // get the foreign blockcahin, the AT address and the foreign fee set to this node
            Bitcoiny bitcoiny = (Bitcoiny) supportedBlockchain.getInstance();
            String atAddress = tradeOfferWaiting.getAtAddress();
            int fee = Math.toIntExact(bitcoiny.getFeeRequired());

            LOGGER.debug("atAddress = {}, fee = {}", atAddress, fee);

            // get the signed foreign fee, if it exists
            Optional<ForeignFeeDecodedData> foreignFeeDecodedData = this.signedByAT.get(atAddress);

            // if the foreign fee has been signed
            if (foreignFeeDecodedData != null && foreignFeeDecodedData.isPresent()) {

                LOGGER.debug("signed available");

                // if the local fee is different than the fee stored in this manager,
                // then empty the fee in the manager and set the updated fee to unsigned data
                if (!foreignFeeDecodedData.get().getFee().equals(fee)) {

                    LOGGER.debug("fee updated");
                    this.signedByAT.remove(atAddress);

                    this.needToBackupSignedForeignFees.compareAndSet(false, true);
                    setUnsignedData(now, atAddress, fee);
                    isFeeWaiting = true;
                }
                else {
                    LOGGER.debug("fee not updated");
                }
            }
            // if the foreign fee has not been signed, then set the fee to unsigned data
            else {
                LOGGER.debug("fee not signed");
                setUnsignedData(now, atAddress, fee);
                isFeeWaiting = true;
            }
        }
        // if the supported blockchain is not a Bitcoiny blockchain, then the fee is not available
        else {
            LOGGER.warn("Blockchain fee not available: blockchain = " + foreignBlockchain);
        }

        return isFeeWaiting;
    }

    /**
     * Set Unisgned Data
     *
     * @param timestamp
     * @param atAddress
     * @param fee
     *
     * @throws IOException
     */
    private void setUnsignedData(Long timestamp, String atAddress, int fee) throws IOException {
        ForeignFeeEncodedData feeData
            = new ForeignFeeEncodedData(
                timestamp,
                Base58.encode(ForeignFeesMessageUtils.buildForeignFeesDataMessage(timestamp, atAddress, fee)),
                atAddress,
                fee
        );

        LOGGER.debug("updating unsigned");
        this.unsignedByAT.put(atAddress, feeData);
        LOGGER.debug("updated unsigned = " + this.unsignedByAT);
    }

    // Network handlers

    /**
     * Handle GetForeignFeesMessage
     *
     * @param peer
     * @param message
     */
    public void onNetworkGetForeignFeesMessage(Peer peer, Message message) {
        GetForeignFeesMessage getForeignFeesMessage = (GetForeignFeesMessage) message;

        // map the fees the peer already has
        Map<String, ForeignFeeDecodedData> inMessageDataByAT
            = getForeignFeesMessage.getForeignFeeData().stream()
                .collect(Collectors.toMap( ForeignFeeDecodedData::getAtAddress, Function.identity()));

        // start collecting fees to send to the peer
        List<ForeignFeeDecodedData> outgoingForeignFees = new ArrayList<>();

        // for all the signed fees locally stored, compare to what the peer currently has and send out what they need
        for(Map.Entry<String, Optional<ForeignFeeDecodedData>> entry : this.signedByAT.entrySet() ) {

            Optional<ForeignFeeDecodedData> signedForeignFeeData = entry.getValue();

            // if the fee has been signed, then evaluate it for sending
            if (signedForeignFeeData.isPresent()) {

                String atAddress = entry.getKey();

                LOGGER.debug("comparing signed foreign fee for get foreign fee message: atAddress = " + atAddress);

                // if message contains AT address, then check timestamps
                if (inMessageDataByAT.containsKey(atAddress) ) {

                    LOGGER.debug("message does contain: atAddress = " + atAddress);

                    // get data from message for AT address
                    ForeignFeeDecodedData feeData = inMessageDataByAT.get(atAddress);

                    // if message data is earlier than what is here locally, then send local out to the peer
                    if( feeData != null && signedForeignFeeData.get().getTimestamp() > feeData.getTimestamp()) {
                        outgoingForeignFees.add(signedForeignFeeData.get());
                    }
                }
                // if the message does not contain data for this AT, then send the data out to the peer
                else {
                    LOGGER.debug("message does not contain: atAddress = " + atAddress);

                    outgoingForeignFees.add(signedForeignFeeData.get());
                }
            }
            // if value is empty, then do nothing
            else {
                LOGGER.debug("unavailable signed foreign fee for get foreign fee message: atAddress = " + entry.getKey());
            }
        }

        LOGGER.debug("Sending {} foreign fees to {}", outgoingForeignFees.size(), peer);

        // send out to peer
        peer.sendMessage(new ForeignFeesMessage(outgoingForeignFees));

        LOGGER.debug("Sent {} foreign fees to {}", outgoingForeignFees.size(), peer);
    }

    /**
     * Handle ForeignFeesMessage
     *
     * @param peer
     * @param message
     */
    public void onNetworkForeignFeesMessage(Peer peer, Message message) {
        ForeignFeesMessage onlineAccountsMessage = (ForeignFeesMessage) message;

        List<ForeignFeeDecodedData> peersForeignFees = onlineAccountsMessage.getForeignFees();
        LOGGER.debug("Received {} foreign fees from {}", peersForeignFees.size(), peer);

        int importCount = 0;

        // add any foreign fees to the queue that aren't already present
        for (ForeignFeeDecodedData peerForeignFee : peersForeignFees) {

            if( foreignFeesImportQueue.add(peerForeignFee) )
                importCount++;
        }

        if (importCount > 0)
            LOGGER.debug("Added {} foreign to queue", importCount);
    }

    /**
     * Backup Signed Foreign Fee Data
     *
     * @throws DataException
     */
    private void backupSignedForeignFeeData() throws DataException {
        try {
            Path backupDirectory = HSQLDBImportExport.getExportDirectory(true);

            // get all signed foreigh fee data on this node
            List<ForeignFeeDecodedData> signedForeignFees
                = this.signedByAT.values().stream()
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .collect(Collectors.toList());

            // get the JSON for the signed foreign fees
            JSONArray currentSignedForeignFeeDataJson = new JSONArray();
            for (ForeignFeeDecodedData signedForeignFee : signedForeignFees) {
                JSONObject foreignFeeSignatureJson = signedForeignFee.toJson();
                currentSignedForeignFeeDataJson.put(foreignFeeSignatureJson);
            }

            // put data into a JSON
            JSONObject currentSignedForeignFeeDataJsonWrapper = new JSONObject();
            currentSignedForeignFeeDataJsonWrapper.put("type", SIGNED_FOREIGN_FEES_TYPE);
            currentSignedForeignFeeDataJsonWrapper.put("dataset", CURRENT_DATASET_LABEL);
            currentSignedForeignFeeDataJsonWrapper.put("data", currentSignedForeignFeeDataJson);

            // write signed fee data to backup file
            String fileName = Paths.get(backupDirectory.toString(), SIGNED_FOREIGN_FEES_FILE_NAME).toString();
            FileWriter writer = new FileWriter(fileName);
            writer.write(currentSignedForeignFeeDataJsonWrapper.toString(2));
            writer.close();

        }
        catch (DataException e) {
            throw new DataException("Unable to export foreign fee signatures.");
        }
        catch (Exception e) {
            LOGGER.error(e.getMessage(), e);
        }
    }

    /**
     * Backup Foreign Fee Data
     *
     * @param feeGetter the fee from the Bitcoiny instance
     * @param filename the backup file name
     * @param type the type of fee label
     *
     * @throws DataException
     */
    private void backupForeignFeeData(Function<Bitcoiny, Long> feeGetter, String filename, String type) throws DataException {
        try {
            Path backupDirectory = HSQLDBImportExport.getExportDirectory(true);

            // get the names of the supported blockchains
            List<String> names
                = Arrays.stream(SupportedBlockchain.values())
                    .map( value -> value.getLatestAcct().getClass().getSimpleName())
                    .collect(Collectors.toList());

            // start list of required foreign fees
            List<ForeignFeeData> foreignFees = new ArrayList<>(names.size());

            // for each blockchain name, get the blockchain and collect the foreign fee for this node
            for( String name : names) {
                ForeignBlockchain blockchain = SupportedBlockchain.getAcctByName(name).getBlockchain();

                // if the blockchain supports fees, add the data to the list
                if( blockchain instanceof Bitcoiny ) {
                    foreignFees.add( new ForeignFeeData(name, feeGetter.apply((Bitcoiny) blockchain)) );
                }
            }

            // put the list of fee data into a JSON array
            JSONArray currentForeignFeesJson = new JSONArray();
            for (ForeignFeeData foreignFee : foreignFees) {
                JSONObject requiredForeignFeeJson = foreignFee.toJson();
                currentForeignFeesJson.put(requiredForeignFeeJson);
            }

            // put the JSON array and some metadata into a JSON object
            JSONObject currentForeignFeeDataJsonWrapper = new JSONObject();
            currentForeignFeeDataJsonWrapper.put("type", type);
            currentForeignFeeDataJsonWrapper.put("dataset", CURRENT_DATASET_LABEL);
            currentForeignFeeDataJsonWrapper.put("data", currentForeignFeesJson);

            // write the JSON to the backup file
            String fileName = Paths.get(backupDirectory.toString(), filename).toString();
            FileWriter writer = new FileWriter(fileName);
            writer.write(currentForeignFeeDataJsonWrapper.toString(2));
            writer.close();

        }
        catch (DataException e) {
            throw new DataException("Unable to export required foreign fees.");
        }
        catch (Exception e) {
            LOGGER.error(e.getMessage(), e);
        }
    }

    /**
     * Import Data From File
     *
     * @param filename the file name
     *
     * @throws DataException
     * @throws IOException
     */
    public void importDataFromFile(String filename) throws DataException, IOException {

        // file path and check for existence
        Path path = Paths.get(filename);
        if (!path.toFile().exists()) {
            throw new FileNotFoundException(String.format("File doesn't exist: %s", filename));
        }

        // read in the file
        byte[] fileContents = Files.readAllBytes(path);
        if (fileContents == null) {
            throw new FileNotFoundException(String.format("Unable to read file contents: %s", filename));
        }

        LOGGER.debug(String.format("Importing %s into foreign fees manager ...", filename));

        String jsonString = new String(fileContents);

        // get the data type and data from the JSON
        Triple<String, String, JSONArray> parsedJSON = HSQLDBImportExport.parseJSONString(jsonString);
        if (parsedJSON.getA() == null || parsedJSON.getC() == null) {
            throw new DataException(String.format("Missing data when importing %s into foreign fees manager", filename));
        }
        String type = parsedJSON.getA();
        JSONArray data = parsedJSON.getC();

        Iterator<Object> iterator = data.iterator();
        while(iterator.hasNext()) {
            JSONObject dataJsonObject = (JSONObject)iterator.next();

            if (type.equals(SIGNED_FOREIGN_FEES_TYPE)) {
                importSignedForeignFeeDataJSON(dataJsonObject);
            }
            else if( type.equals(REQUIRED_FOREIGN_FEES_TYPE)) {
                importRequiredForeignFeeDataJSON(dataJsonObject);
            }
            else if( type.equals(LOCKING_FOREIGN_FEES_TYPE)) {
                importLockingForeignFeeDataJSON(dataJsonObject);
            }
            else {
                throw new DataException(String.format("Unrecognized data type when importing %s", filename));
            }
        }

        LOGGER.debug(String.format("Imported %s into foreign fees manager from %s", type, filename));
    }

    /**
     * Import Signed Foreign Fee Data JSON
     *
     * @param signedForeignFeeDataJson the JSON object
     *
     * @throws DataException
     */
    private void importSignedForeignFeeDataJSON(JSONObject signedForeignFeeDataJson) throws DataException {

        ForeignFeeDecodedData signedForeignFeeData = ForeignFeeDecodedData.fromJson(signedForeignFeeDataJson);

        this.signedByAT.put(signedForeignFeeData.getAtAddress(), Optional.of(signedForeignFeeData));
    }

    /**
     * Import Required Foreign Fee Data JSON
     *
     * @param requiredForeignFeeDataJson the JSON object
     *
     * @throws DataException
     */
    private static void importRequiredForeignFeeDataJSON(JSONObject requiredForeignFeeDataJson) throws DataException {

        // the data
        ForeignFeeData requiredForeignFeeData = ForeignFeeData.fromJson( requiredForeignFeeDataJson );

        // the blockchain
        ForeignBlockchain blockchain
            = SupportedBlockchain
                .getAcctByName(requiredForeignFeeData.getBlockchain())
                .getBlockchain();

        // if the blockchain is Bitcoiny, then get the required fee and set it to blockchain
        if( blockchain != null && blockchain instanceof Bitcoiny ) {
            ((Bitcoiny) blockchain).setFeeRequired( requiredForeignFeeData.getFee() );
        }
        else {
            LOGGER.warn("no support for required fee import: blockchain = " + requiredForeignFeeData.getBlockchain());
        }
    }

    /**
     * Import Locking Foreign Fee Data JSON
     *
     * @param lockingForeignFeeDataJson the JSON object
     *
     * @throws DataException
     */
    private static void importLockingForeignFeeDataJSON(JSONObject lockingForeignFeeDataJson) throws DataException {

        // get the data
        ForeignFeeData lockingForeignFeeData = ForeignFeeData.fromJson(lockingForeignFeeDataJson);

        // get the blockchain
        ForeignBlockchain blockchain
            = SupportedBlockchain
                .getAcctByName(lockingForeignFeeData.getBlockchain())
                .getBlockchain();

        // if the blockchain is Bitcoiny, then set the locking fee to it
        if( blockchain != null && blockchain instanceof Bitcoiny ) {
            ((Bitcoiny) blockchain).setFeePerKb(Coin.valueOf(lockingForeignFeeData.getFee()));
        }
        else {
            LOGGER.warn("no support for locking fee import: blockchain = " + lockingForeignFeeData.getBlockchain());
        }
    }
}