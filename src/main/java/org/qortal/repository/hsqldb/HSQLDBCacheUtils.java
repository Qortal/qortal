package org.qortal.repository.hsqldb;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.api.SearchMode;
import org.qortal.api.resource.TransactionsResource;
import org.qortal.arbitrary.misc.Category;
import org.qortal.arbitrary.misc.Service;
import org.qortal.controller.Controller;
import org.qortal.data.account.AccountBalanceData;
import org.qortal.data.account.AddressAmountData;
import org.qortal.data.account.BlockHeightRange;
import org.qortal.data.account.BlockHeightRangeAddressAmounts;
import org.qortal.data.arbitrary.ArbitraryResourceCache;
import org.qortal.data.arbitrary.ArbitraryResourceData;
import org.qortal.data.arbitrary.ArbitraryResourceMetadata;
import org.qortal.data.arbitrary.ArbitraryResourceStatus;
import org.qortal.data.transaction.TransactionData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.settings.Settings;
import org.qortal.utils.BalanceRecorderUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.Statement;
import java.time.format.DateTimeFormatter;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.qortal.api.SearchMode.LATEST;

public class HSQLDBCacheUtils {

    private static final Logger LOGGER = LogManager.getLogger(HSQLDBCacheUtils.class);
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final Comparator<? super ArbitraryResourceData> CREATED_WHEN_COMPARATOR = new Comparator<ArbitraryResourceData>() {
        @Override
        public int compare(ArbitraryResourceData data1, ArbitraryResourceData data2) {

            Long a = data1.created;
            Long b = data2.created;

            return Long.compare(a != null ? a : Long.MIN_VALUE, b != null ? b : Long.MIN_VALUE);
        }
    };
    private static final String DEFAULT_IDENTIFIER = "default";
    private static final int ZERO = 0;
    public static final String DB_CACHE_TIMER = "DB Cache Timer";
    public static final String DB_CACHE_TIMER_TASK = "DB Cache Timer Task";
    public static final String BALANCE_RECORDER_TIMER = "Balance Recorder Timer";
    public static final String BALANCE_RECORDER_TIMER_TASK = "Balance Recorder Timer Task";

    /**
     *
     * @param cache
     * @param service the service to filter
     * @param query query for name, identifier, title or description match
     * @param identifier the identifier to match
     * @param names the names to match, ignored if there are exact names
     * @param title the title to match for
     * @param description the description to match for
     * @param prefixOnly true to match on prefix only, false for match anywhere in string
     * @param exactMatchNames names to match exactly, overrides names
     * @param defaultResource true to query filter identifier on the default identifier and use the query terms to match candidates names only
     * @param mode LATEST or ALL
     * @param minLevel the minimum account level for resource creators
     * @param includeOnly names to retain, exclude all others
     * @param exclude names to exclude, retain all others
     * @param includeMetadata true to include resource metadata in the results, false to exclude metadata
     * @param includeStatus true to include resource status in the results, false to exclude status
     * @param before the latest creation timestamp for any candidate
     * @param after  the earliest creation timestamp for any candidate
     * @param limit  the maximum number of resource results to return
     * @param offset the number of resource results to skip after the results have been retained, filtered and sorted
     * @param reverse true to reverse the sort order, false to order in chronological order
     *
     * @return the resource results
     */
    public static List<ArbitraryResourceData> callCache(
            ArbitraryResourceCache cache,
            Service service,
            String query,
            String identifier,
            List<String> names,
            String title,
            String description,
            boolean prefixOnly,
            List<String> exactMatchNames,
            boolean defaultResource,
            SearchMode mode,
            Integer minLevel,
            Boolean followedOnly,
            Boolean excludeBlocked,
            Boolean includeMetadata,
            Boolean includeStatus,
            Long before,
            Long after,
            Integer limit,
            Integer offset,
            Boolean reverse) {

        List<ArbitraryResourceData> candidates = new ArrayList<>();

        // cache all results for requested service
        if( service != null ) {
            candidates.addAll(cache.getDataByService().getOrDefault(service.value, new ArrayList<>(0)));
        }
        // if no requested, then empty cache

        return candidates;
    }

    /**
     * Filter candidates
     *
     * @param candidates the candidates, they may be preprocessed
     * @param levelByName name -> level map
     * @param mode LATEST or ALL
     * @param service the service to filter
     * @param query query for name, identifier, title or description match
     * @param identifier the identifier to match
     * @param names the names to match, ignored if there are exact names
     * @param title the title to match for
     * @param description the description to match for
     * @param prefixOnly true to match on prefix only, false for match anywhere in string
     * @param exactMatchNames names to match exactly, overrides names
     * @param defaultResource true to query filter identifier on the default identifier and use the query terms to match candidates names only
     * @param minLevel the minimum account level for resource creators
     * @param includeOnly names to retain, exclude all others
     * @param exclude names to exclude, retain all others
     * @param includeMetadata true to include resource metadata in the results, false to exclude metadata
     * @param includeStatus true to include resource status in the results, false to exclude status
     * @param before the latest creation timestamp for any candidate
     * @param after  the earliest creation timestamp for any candidate
     * @param limit  the maximum number of resource results to return
     * @param offset the number of resource results to skip after the results have been retained, filtered and sorted
     * @param reverse true to reverse the sort order, false to order in chronological order
     *
     * @return the resource results
     */
    public static List<ArbitraryResourceData> filterList(
            List<ArbitraryResourceData> candidates,
            Map<String, Integer> levelByName,
            Optional<SearchMode> mode,
            Optional<Service> service,
            Optional<String> query,
            Optional<String> identifier,
            Optional<List<String>> names,
            Optional<String> title,
            Optional<String> description,
            boolean prefixOnly,
            Optional<List<String>> exactMatchNames,
            Optional<List<String>> keywords,
            boolean defaultResource,
            Optional<Integer> minLevel,
            Optional<Supplier<List<String>>> includeOnly,
            Optional<Supplier<List<String>>> exclude,
            Optional<Boolean> includeMetadata,
            Optional<Boolean> includeStatus,
            Optional<Long> before,
            Optional<Long> after,
            Optional<Integer> limit,
            Optional<Integer> offset,
            Optional<Boolean> reverse) {

        // retain only candidates with names
        Stream<ArbitraryResourceData> stream = candidates.stream().filter(candidate -> candidate.name != null );

        if(after.isPresent()) {
            stream = stream.filter( candidate -> candidate.created > after.get().longValue() );
        }

        if(before.isPresent()) {
            stream = stream.filter( candidate -> candidate.created < before.get().longValue() );
        }

        if(exclude.isPresent())
            stream = stream.filter( candidate -> !exclude.get().get().contains( candidate.name ));

        // filter by service
        if( service.isPresent() )
            stream = stream.filter(candidate -> candidate.service.equals(service.get()));

        // filter by query (either identifier, name, title or description)
        if (query.isPresent()) {

            Predicate<String> predicate
                    = prefixOnly ? getPrefixPredicate(query.get()) : getContainsPredicate(query.get());

            if (defaultResource) {
                stream = stream.filter( candidate -> DEFAULT_IDENTIFIER.equals( candidate.identifier ) && predicate.test(candidate.name));
            } else {
                stream = stream.filter( candidate -> passQuery(predicate, candidate));
            }
        }

        // filter for identifier, title and description
        stream = filterTerm(identifier, data -> data.identifier, prefixOnly, stream);
        stream = filterTerm(title, data -> data.metadata != null ? data.metadata.getTitle() : null, prefixOnly, stream);
        stream = filterTerm(description, data -> data.metadata != null ? data.metadata.getDescription() : null, prefixOnly, stream);

        // New: Filter by keywords if provided
        if (keywords.isPresent() && !keywords.get().isEmpty()) {
            List<String> searchKeywords = keywords.get().stream()
                .map(String::toLowerCase)
                .collect(Collectors.toList());

            stream = stream.filter(candidate -> {
                
                if (candidate.metadata != null && candidate.metadata.getDescription() != null) {
                    String descriptionLower = candidate.metadata.getDescription().toLowerCase();
                    return searchKeywords.stream().anyMatch(descriptionLower::contains);
                }
                return false;
            });
        }

        if (keywords.isPresent() && !keywords.get().isEmpty()) {
            List<String> searchKeywords = keywords.get().stream()
                .map(String::toLowerCase)
                .collect(Collectors.toList());

            stream = stream.filter(candidate -> {
                if (candidate.metadata != null && candidate.metadata.getDescription() != null) {
            String descriptionLower = candidate.metadata.getDescription().toLowerCase();
            return searchKeywords.stream().anyMatch(descriptionLower::contains);
                }
                return false;
            });
        }

        // if exact names is set, retain resources with exact names
        if( exactMatchNames.isPresent() && !exactMatchNames.get().isEmpty()) {

            // key the data by lower case name
            Map<String, List<ArbitraryResourceData>> dataByName
                    = stream.collect(Collectors.groupingBy(data -> data.name.toLowerCase()));

            // lower the case of the exact names
            // retain the lower case names of the data above
            List<String> exactNamesToSearch
                = exactMatchNames.get().stream()
                    .map(String::toLowerCase)
                    .collect(Collectors.toList());
            exactNamesToSearch.retainAll(dataByName.keySet());

            // get the data for the names retained and
            // set them to the stream
            stream
                = dataByName.entrySet().stream()
                    .filter(entry -> exactNamesToSearch.contains(entry.getKey())).flatMap(entry -> entry.getValue().stream());
        }
        // if exact names is not set, retain resources that match
        else if( names.isPresent() && !names.get().isEmpty() ) {

            stream = retainTerms(names.get(), data -> data.name, prefixOnly, stream);
        }

        // filter for minimum account level
        if(minLevel.isPresent())
            stream = stream.filter( candidate -> levelByName.getOrDefault(candidate.name, 0) >= minLevel.get() );

        // if latest mode or empty
        if( LATEST.equals( mode.orElse( LATEST ) ) ) {

            // Include latest item only for a name/service combination
            stream
                = stream.filter(candidate -> candidate.service != null && candidate.created != null ).collect(
                    Collectors.groupingBy(
                            data -> new AbstractMap.SimpleEntry<>(data.name, data.service), // name, service combination
                            Collectors.maxBy(Comparator.comparingLong(data -> data.created)) // latest data item
                    )).values().stream().filter(Optional::isPresent).map(Optional::get); // if there is a value for the group, then retain it
        }

        // sort
        if( reverse.isPresent() && reverse.get())
            stream = stream.sorted(CREATED_WHEN_COMPARATOR.reversed());
        else
            stream = stream.sorted(CREATED_WHEN_COMPARATOR);

        // skip to offset
        if( offset.isPresent() ) stream = stream.skip(offset.get());

        // truncate to limit
        if( limit.isPresent() && limit.get() > 0 ) stream = stream.limit(limit.get());

        List<ArbitraryResourceData> listCopy1 = stream.collect(Collectors.toList());

        List<ArbitraryResourceData> listCopy2 = new ArrayList<>(listCopy1.size());

        // remove metadata from the first copy
        if( includeMetadata.isEmpty() || !includeMetadata.get() ) {
            for( ArbitraryResourceData data : listCopy1 ) {
                ArbitraryResourceData copy = new ArbitraryResourceData();
                copy.name = data.name;
                copy.service = data.service;
                copy.identifier = data.identifier;
                copy.status = data.status;
                copy.metadata = null;

                copy.size = data.size;
                copy.created = data.created;
                copy.updated = data.updated;

                listCopy2.add(copy);
            }
        }
        // put the list copy 1 into the second copy
        else {
            listCopy2.addAll(listCopy1);
        }

        // remove status from final copy
        if( includeStatus.isEmpty() || !includeStatus.get() ) {

            List<ArbitraryResourceData> finalCopy = new ArrayList<>(listCopy2.size());

            for( ArbitraryResourceData data : listCopy2 ) {
                ArbitraryResourceData copy = new ArbitraryResourceData();
                copy.name = data.name;
                copy.service = data.service;
                copy.identifier = data.identifier;
                copy.status = null;
                copy.metadata = data.metadata;

                copy.size = data.size;
                copy.created = data.created;
                copy.updated = data.updated;

                finalCopy.add(copy);
            }

            return finalCopy;
        }
        // keep status included by returning the second copy
        else {
            return listCopy2;
        }
    }

    /**
     * Filter Terms
     *
     * @param term the term to filter
     * @param stringSupplier the string of interest from the resource candidates
     * @param prefixOnly true if prexif only, false for contains
     * @param stream the stream of candidates
     *
     * @return the stream that filtered the term
     */
    private static Stream<ArbitraryResourceData> filterTerm(
            Optional<String> term,
            Function<ArbitraryResourceData,String> stringSupplier,
            boolean prefixOnly,
            Stream<ArbitraryResourceData> stream) {

        if(term.isPresent()){
            Predicate<String> predicate
                    = prefixOnly ? getPrefixPredicate(term.get()): getContainsPredicate(term.get());
            stream = stream.filter(candidate -> predicate.test(stringSupplier.apply(candidate)));
        }

        return stream;
    }

    /**
     * Retain Terms
     *
     * Retain resources that satisfy terms given.
     *
     * @param terms the terms to retain
     * @param stringSupplier the string of interest from the resource candidates
     * @param prefixOnly true if prexif only, false for contains
     * @param stream the stream of candidates
     *
     * @return the stream that retained the terms
     */
    private static Stream<ArbitraryResourceData> retainTerms(
            List<String> terms,
            Function<ArbitraryResourceData,String> stringSupplier,
            boolean prefixOnly,
            Stream<ArbitraryResourceData> stream) {

        // collect the data to process, start the data to retain
        List<ArbitraryResourceData> toProcess = stream.collect(Collectors.toList());
        List<ArbitraryResourceData> toRetain = new ArrayList<>();

        // for each term, get the predicate, get a new stream process and
        // apply the predicate to each data item in the stream
        for( String term : terms ) {
            Predicate<String> predicate
                    = prefixOnly ? getPrefixPredicate(term) : getContainsPredicate(term);
            toRetain.addAll(
                toProcess.stream()
                    .filter(candidate -> predicate.test(stringSupplier.apply(candidate)))
                    .collect(Collectors.toList())
            );
        }

        return toRetain.stream();
    }

    private static Predicate<String> getContainsPredicate(String term) {
        return value -> value != null && value.toLowerCase().contains(term.toLowerCase());
    }

    private static Predicate<String> getPrefixPredicate(String term) {
        return value -> value != null && value.toLowerCase().startsWith(term.toLowerCase());
    }

    /**
     * Pass Query
     *
     * Compare name, identifier, title and description
     *
     * @param predicate the string comparison predicate
     * @param candidate the candiddte to compare
     *
     * @return true if there is a match, otherwise false
     */
    private static boolean passQuery(Predicate<String> predicate, ArbitraryResourceData candidate) {

        if( predicate.test(candidate.name) ) return true;

        if( predicate.test(candidate.identifier) ) return true;

        if( candidate.metadata != null ) {

            if( predicate.test(candidate.metadata.getTitle() )) return true;
            if( predicate.test(candidate.metadata.getDescription())) return true;
        }

        return false;
    }

    /**
     * Start Caching
     *
     * @param priorityRequested the thread priority to fill cache in
     * @param frequency         the frequency to fill the cache (in seconds)
     *
     * @return the data cache
     */
    public static void startCaching(int priorityRequested, int frequency) {

        Timer timer = buildTimer(DB_CACHE_TIMER, priorityRequested);

        TimerTask task = new TimerTask() {
            @Override
            public void run() {

                Thread.currentThread().setName(DB_CACHE_TIMER_TASK);

                if (RepositoryManager.getRepositoryFactory() == null) {
                    LOGGER.debug("Repository unavailable; skipping cache fill");
                    return;
                }

                try (final Repository respository = RepositoryManager.getRepository()) {
                    fillCache(ArbitraryResourceCache.getInstance(), respository);
                }
                catch( DataException e ) {
                    if (isRepositoryUnavailable(e)) {
                        LOGGER.debug("Repository unavailable; skipping cache fill");
                    } else {
                        LOGGER.error(e.getMessage(), e);
                    }
                }
            }
        };

        // delay 1 second
        timer.scheduleAtFixedRate(task, 1000, frequency * 1000);
    }

    /**
     * Start Recording Balances
     *
     * @param balancesByHeight height -> account balances
     * @param balanceDynamics every balance dynamic
     * @param priorityRequested the requested thread priority
     * @param frequency the recording frequencies, in minutes
     * @param capacity the maximum size of balanceDynamics
     */
    public static void startRecordingBalances(
            final ConcurrentHashMap<Integer, List<AccountBalanceData>> balancesByHeight,
            CopyOnWriteArrayList<BlockHeightRangeAddressAmounts> balanceDynamics,
            int priorityRequested,
            int frequency,
            int capacity) {

        Timer timer = buildTimer(BALANCE_RECORDER_TIMER, priorityRequested);

        TimerTask task = new TimerTask() {
            @Override
            public void run() {

                Thread.currentThread().setName(BALANCE_RECORDER_TIMER_TASK);

                if (RepositoryManager.getRepositoryFactory() == null) {
                    LOGGER.debug("Repository unavailable; skipping balance recorder");
                    return;
                }

                int currentHeight = recordCurrentBalances(balancesByHeight);

                LOGGER.debug("recorded balances: height = " + currentHeight);

                // remove invalidated recordings, recording after current height
                BalanceRecorderUtils.removeRecordingsAboveHeight(currentHeight, balancesByHeight);

                // remove invalidated dynamics, on or after current height
                BalanceRecorderUtils.removeDynamicsOnOrAboveHeight(currentHeight, balanceDynamics);

                // if there are 2 or more recordings, then produce balance dynamics for the first 2 recordings
                if( balancesByHeight.size() > 1 ) {

                    Optional<Integer> priorHeight = BalanceRecorderUtils.getPriorHeight(currentHeight, balancesByHeight);

                    // if there is a prior height
                    if(priorHeight.isPresent()) {

                        boolean isRewardDistribution = BalanceRecorderUtils.isRewardDistributionRange(priorHeight.get(), currentHeight);

                        // if this range has a reward recording block or if other blocks are enabled for recording
                        if( isRewardDistribution || !Settings.getInstance().isRewardRecordingOnly() ) {
                            produceBalanceDynamics(currentHeight, priorHeight, isRewardDistribution, balancesByHeight, balanceDynamics, capacity);
                        }
                    }
                    else {
                        LOGGER.warn("Expecting prior height and nothing was discovered, current height = " + currentHeight);
                    }
                }
                // else this should be the first recording
                else {
                    LOGGER.info("first balance recording completed");
                }
            }
        };

        // wait 5 minutes
        timer.scheduleAtFixedRate(task, 300_000, frequency * 60_000);
    }

    private static void produceBalanceDynamics(int currentHeight, Optional<Integer> priorHeight, boolean isRewardDistribution, ConcurrentHashMap<Integer, List<AccountBalanceData>> balancesByHeight, CopyOnWriteArrayList<BlockHeightRangeAddressAmounts> balanceDynamics, int capacity) {
        BlockHeightRange blockHeightRange = new BlockHeightRange(priorHeight.get(), currentHeight, isRewardDistribution);

        LOGGER.debug("building dynamics for block heights: range = " + blockHeightRange);

        List<AccountBalanceData> currentBalances = balancesByHeight.get(currentHeight);

        ArrayList<TransactionData> transactions = getTransactionDataForBlocks(blockHeightRange);

        LOGGER.info("transactions counted for balance adjustments: count = " + transactions.size());
        List<AddressAmountData> currentDynamics
            = BalanceRecorderUtils.buildBalanceDynamics(
                currentBalances,
                balancesByHeight.get(priorHeight.get()),
                Settings.getInstance().getMinimumBalanceRecording(),
                transactions);

        LOGGER.debug("dynamics built: count = " + currentDynamics.size());

        if(LOGGER.isDebugEnabled())
            currentDynamics.stream()
                .sorted(Comparator.comparingLong(AddressAmountData::getAmount).reversed())
                .limit(Settings.getInstance().getTopBalanceLoggingLimit())
                .forEach(top5Dynamic -> LOGGER.debug("Top Dynamics = " + top5Dynamic));

        BlockHeightRangeAddressAmounts amounts
            = new BlockHeightRangeAddressAmounts( blockHeightRange, currentDynamics );

        balanceDynamics.add(amounts);

        BalanceRecorderUtils.removeRecordingsBelowHeight(currentHeight - Settings.getInstance().getBalanceRecorderRollbackAllowance(), balancesByHeight);

        while(balanceDynamics.size() > capacity) {
            BlockHeightRangeAddressAmounts oldestDynamics = BalanceRecorderUtils.removeOldestDynamics(balanceDynamics);

            LOGGER.debug("removing oldest dynamics: range " + oldestDynamics.getRange());
        }
    }

    private static ArrayList<TransactionData> getTransactionDataForBlocks(BlockHeightRange blockHeightRange) {
        ArrayList<TransactionData> transactions;

        try (final Repository repository = RepositoryManager.getRepository()) {
            List<byte[]> signatures
                = repository.getTransactionRepository().getSignaturesMatchingCriteria(
                    blockHeightRange.getBegin() + 1, blockHeightRange.getEnd() - blockHeightRange.getBegin(),
                    null, null,null, null, null,
                    TransactionsResource.ConfirmationStatus.CONFIRMED,
                    null, null, null);

            transactions = new ArrayList<>(signatures.size());
            for (byte[] signature : signatures) {
                transactions.add(repository.getTransactionRepository().fromSignature(signature));
            }

            LOGGER.debug(String.format("Found %s transactions for " + blockHeightRange, transactions.size()));
        } catch (Exception e) {
            transactions = new ArrayList<>(0);
            if (isRepositoryUnavailable(e)) {
                LOGGER.debug("Repository unavailable; skipping balance transaction lookup");
            } else {
                LOGGER.warn("Problems getting transactions for balance recording: " + e.getMessage());
            }
        }
        return transactions;
    }

    private static int recordCurrentBalances(ConcurrentHashMap<Integer, List<AccountBalanceData>> balancesByHeight) {
        int currentHeight;

        try (final Repository repository = RepositoryManager.getRepository()) {

            // get current balances
            List<AccountBalanceData> accountBalances = getAccountBalances(repository);

            // get anyone of the balances
            Optional<AccountBalanceData> data = accountBalances.stream().findAny();

            // if there are any balances, then record them
            if (data.isPresent()) {
                // map all new balances to the current height
                balancesByHeight.put(data.get().getHeight(), accountBalances);

                currentHeight =  data.get().getHeight();
            }
            else {
                currentHeight = Integer.MAX_VALUE;
            }
        } catch (DataException e) {
            if (isRepositoryUnavailable(e)) {
                LOGGER.debug("Repository unavailable; skipping balance record");
            } else {
                LOGGER.error(e.getMessage(), e);
            }
            currentHeight = Integer.MAX_VALUE;
        }

        return currentHeight;
    }

    /**
     * Build Timer
     *
     * Build a timer for scheduling a timer task.
     *
     * @param name the name for the thread running the timer task
     * @param priorityRequested the priority for the thread running the timer task
     *
     * @return a timer for scheduling a timer task
     */
    private static Timer buildTimer( final String name, int priorityRequested) {
        // ensure priority is in between 1-10
        final int priority = Math.max(0, Math.min(10, priorityRequested));

        // Create a custom Timer with updated priority threads
        Timer timer = new Timer(true) { // 'true' to make the Timer daemon
            @Override
            public void schedule(TimerTask task, long delay) {
                Thread thread = new Thread(task, name) {
                    @Override
                    public void run() {
                        this.setPriority(priority);
                        super.run();
                    }
                };
                thread.setPriority(priority);
                thread.start();
            }
        };
        return timer;
    }

    private static boolean isRepositoryUnavailable(Throwable e) {
        if (e == null)
            return false;

        if (e instanceof DataException && e.getMessage() != null && e.getMessage().contains("No repository available")) {
            return true;
        }

        Throwable cause = e.getCause();
        if (cause == null || cause == e)
            return false;

        return isRepositoryUnavailable(cause);
    }

    /**
     * Fill Cache
     *
     * @param cache the cache to fill
     * @param repository the data source to fill the cache with
     */
    public static void fillCache(ArbitraryResourceCache cache, Repository repository) {

        try {
            // ensure all data is committed in, before we query it
            repository.saveChanges();

            List<ArbitraryResourceData> resources = getResources(repository);

            Map<Integer, List<ArbitraryResourceData>> dataByService
                    = resources.stream()
                        .collect(Collectors.groupingBy(data -> data.service.value));

            // lock, clear and refill
            synchronized (cache.getDataByService()) {
                cache.getDataByService().clear();
                cache.getDataByService().putAll(dataByService);
            }

            fillNamepMap(cache.getLevelByName(), repository);
        }
        catch (SQLNonTransientConnectionException e ) {
            LOGGER.warn("Connection problems. Retry later.");
        }
        catch (Exception e) {
            LOGGER.error(e.getMessage(), e);
        }
    }

    /**
     * Fill Name Map
     *
     * Name -> Level
     *
     * @param levelByName the map to fill
     * @param repository the data source
     *
     * @throws SQLException
     */
    private static void fillNamepMap(ConcurrentHashMap<String, Integer> levelByName, Repository repository ) throws SQLException {

        StringBuilder sql = new StringBuilder(512);

        sql.append("SELECT name, level ");
        sql.append("FROM NAMES ");
        sql.append("INNER JOIN ACCOUNTS on owner = account ");

        Statement statement = repository.getConnection().createStatement();

        ResultSet resultSet = statement.executeQuery(sql.toString());

        if (resultSet == null)
            return;

        if (!resultSet.next())
            return;

        do {
            levelByName.put(resultSet.getString(1), resultSet.getInt(2));
        } while(resultSet.next());
    }

    /**
     * Get Resource
     *
     * @param repository source data
     *
     * @return the resources
     * @throws SQLException
     */
    private static List<ArbitraryResourceData> getResources( Repository repository) throws SQLException {

        List<ArbitraryResourceData> resources = new ArrayList<>();

        StringBuilder sql = new StringBuilder(512);

        sql.append("SELECT name, service, identifier, size, status, created_when, updated_when, ");
        sql.append("title, description, category, tag1, tag2, tag3, tag4, tag5 ");
        sql.append("FROM ArbitraryResourcesCache ");
        sql.append("LEFT JOIN ArbitraryMetadataCache USING (service, name, identifier) WHERE name IS NOT NULL");

        List<ArbitraryResourceData> arbitraryResources = new ArrayList<>();
        Statement statement = repository.getConnection().createStatement();

        ResultSet resultSet = statement.executeQuery(sql.toString());

        if (resultSet == null)
            return resources;

        if (!resultSet.next())
            return resources;

        do {
            String nameResult = resultSet.getString(1);
            int serviceResult = resultSet.getInt(2);
            String identifierResult = resultSet.getString(3);
            Integer sizeResult = resultSet.getInt(4);
            Integer status = resultSet.getInt(5);
            Long created = resultSet.getLong(6);
            Long updated = resultSet.getLong(7);

            String titleResult = resultSet.getString(8);
            String descriptionResult = resultSet.getString(9);
            String category = resultSet.getString(10);
            String tag1 = resultSet.getString(11);
            String tag2 = resultSet.getString(12);
            String tag3 = resultSet.getString(13);
            String tag4 = resultSet.getString(14);
            String tag5 = resultSet.getString(15);

            if (Objects.equals(identifierResult, "default")) {
                // Map "default" back to null. This is optional but probably less confusing than returning "default".
                identifierResult = null;
            }

            ArbitraryResourceData arbitraryResourceData = new ArbitraryResourceData();
            arbitraryResourceData.name = nameResult;
            arbitraryResourceData.service = Service.valueOf(serviceResult);
            arbitraryResourceData.identifier = identifierResult;
            arbitraryResourceData.size = sizeResult;
            arbitraryResourceData.created = created;
            arbitraryResourceData.updated = (updated == 0) ? null : updated;

            arbitraryResourceData.setStatus(ArbitraryResourceStatus.Status.valueOf(status));

            ArbitraryResourceMetadata metadata = new ArbitraryResourceMetadata();
            metadata.setTitle(titleResult);
            metadata.setDescription(descriptionResult);
            metadata.setCategory(Category.uncategorizedValueOf(category));

            List<String> tags = new ArrayList<>();
            if (tag1 != null) tags.add(tag1);
            if (tag2 != null) tags.add(tag2);
            if (tag3 != null) tags.add(tag3);
            if (tag4 != null) tags.add(tag4);
            if (tag5 != null) tags.add(tag5);
            metadata.setTags(!tags.isEmpty() ? tags : null);

            if (metadata.hasMetadata()) {
                arbitraryResourceData.metadata = metadata;
            }

            resources.add( arbitraryResourceData );
        } while (resultSet.next());

        return resources;
    }

    public static List<AccountBalanceData> getAccountBalances(Repository repository) {

        StringBuilder sql = new StringBuilder();

        sql.append("SELECT account, balance, height ");
        sql.append("FROM ACCOUNTBALANCES as balances ");
        sql.append("JOIN (SELECT height FROM BLOCKS ORDER BY height DESC LIMIT 1) AS max_height ON true ");
        sql.append("WHERE asset_id=0");

        List<AccountBalanceData> data = new ArrayList<>();

        LOGGER.info( "Getting account balances ...");

        try {
            Statement statement = repository.getConnection().createStatement();

            ResultSet resultSet = statement.executeQuery(sql.toString());

            if (resultSet == null || !resultSet.next())
                return new ArrayList<>(0);

            do {
                String account = resultSet.getString(1);
                long balance = resultSet.getLong(2);
                int height = resultSet.getInt(3);

                data.add(new AccountBalanceData(account, ZERO, balance, height));
            } while (resultSet.next());
        } catch (SQLException e) {
            LOGGER.warn(e.getMessage());
        } catch (Exception e) {
            LOGGER.error(e.getMessage(), e);
        }

        LOGGER.info("Retrieved account balances: count = " + data.size());

        return data;
    }
}
