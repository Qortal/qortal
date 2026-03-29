package org.qortal.utils;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.api.SearchMode;
import org.qortal.arbitrary.ArbitraryDataFile;
import org.qortal.arbitrary.ArbitraryDataReader;
import org.qortal.arbitrary.exception.MissingDataException;
import org.qortal.arbitrary.misc.Service;
import org.qortal.controller.Controller;
import org.qortal.controller.arbitrary.ArbitraryTransactionDataHashWrapper;
import org.qortal.data.arbitrary.AdvancedStringMatcher;
import org.qortal.data.arbitrary.ArbitraryDataIndex;
import org.qortal.data.arbitrary.ArbitraryDataIndexDetail;
import org.qortal.data.arbitrary.ArbitraryDataIndexScoreKey;
import org.qortal.data.arbitrary.ArbitraryDataIndexScorecard;
import org.qortal.data.arbitrary.ArbitraryResourceData;
import org.qortal.data.arbitrary.IndexCache;
import org.qortal.data.arbitrary.IssuerIndexKey;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Timer;
import java.util.TimerTask;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ArbitraryIndexUtils {

    public static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Logger LOGGER = LogManager.getLogger(ArbitraryIndexUtils.class);

    public static final String INDEX_CACHE_TIMER = "Arbitrary Index Cache Timer";
    public static final String INDEX_CACHE_TIMER_TASK = "Arbitrary Index Cache Timer Task";

    public static void startCaching(int priorityRequested, int frequency) {

        Timer timer = buildTimer(INDEX_CACHE_TIMER, priorityRequested);

        TimerTask task = new TimerTask() {
            @Override
            public void run() {

                Thread.currentThread().setName(INDEX_CACHE_TIMER_TASK);

                try {
                    fillCache(IndexCache.getInstance());
                } catch (IOException | DataException e) {
                    LOGGER.warn(e.getMessage());
                }
            }
        };

        // delay by the frequency
        timer.scheduleAtFixedRate(task, frequency * 60_000, frequency * 60_000);
    }

    private static void fillCache(IndexCache instance) throws DataException, IOException {

        try (final Repository repository = RepositoryManager.getRepository()) {

            List<ArbitraryResourceData> indexResources
                = repository.getArbitraryRepository().searchArbitraryResources(
                    Service.JSON,
                    null,
                    "idx-",
                    null,
                    null,
                    null,
                    null,
                    true,
                    null,
                    false,
                    SearchMode.ALL,
                    0,
                    null,
                    true,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    true);

            List<ArbitraryDataIndexDetail> indexDetails = new ArrayList<>();

            LOGGER.info("processing index resource data: count = " + indexResources.size());

            Map<ArbitraryTransactionDataHashWrapper, IndexCache.IndexResourceDetails> indexResourceDetailsByHashWrapper
                    = instance.getIndexResourceDetailsByHashWrapper();

            // process all index resources
            for( ArbitraryResourceData indexResource : indexResources ) {

                // get hash wrapper for this index, so we can access it from or store it to a hash map
                ArbitraryTransactionDataHashWrapper hashWrapper
                        = new ArbitraryTransactionDataHashWrapper(Service.JSON.value, indexResource.name, indexResource.identifier);

                IndexCache.IndexResourceDetails indexResourceDetails = indexResourceDetailsByHashWrapper.get(hashWrapper);

                // if the details are already available and they were read with the updated resource, then use them
                if( indexResourceDetails != null && detailsAreUpdatedToResource(indexResource, indexResourceDetails)) {
                   indexDetails.addAll(indexResourceDetails.indexDetails);
                }
                // if the details are not available or updated, then read them in and add them to the map
                else {
                    try {
                        LOGGER.debug("processing index resource: name = " + indexResource.name + ", identifier = " + indexResource.identifier);
                        String json = ArbitraryIndexUtils.getJson(indexResource.name, indexResource.identifier);

                        // map the JSON string to a list of Java objects
                        List<ArbitraryDataIndex> indices = OBJECT_MAPPER.readValue(json, new TypeReference<List<ArbitraryDataIndex>>() {
                        });

                        LOGGER.debug("processed indices = " + indices);

                        List<ArbitraryDataIndexDetail> newIndexDetails = new ArrayList<>(indices.size());

                        // rank and create index detail for each index in this index resource
                        for (int rank = 1; rank <= indices.size(); rank++) {

                            newIndexDetails.add(new ArbitraryDataIndexDetail(indexResource.name, rank, indices.get(rank - 1), indexResource.identifier));
                        }

                        indexDetails.addAll(newIndexDetails);
                        indexResourceDetailsByHashWrapper.put(hashWrapper, new IndexCache.IndexResourceDetails(indexResource, newIndexDetails));
                    } catch (MissingDataException e) {
                        LOGGER.warn(e.getMessage());
                    } catch (InvalidFormatException e) {
                        LOGGER.debug("invalid format, skipping: " + indexResource);
                    } catch (UnrecognizedPropertyException e) {
                        LOGGER.debug("unrecognized property, skipping " + indexResource);
                    }
                }
            }

            LOGGER.debug("processing indices by term ...");
            Map<String, List<ArbitraryDataIndexDetail>> indicesByTerm
                = indexDetails.stream().collect(
                    Collectors.toMap(
                        detail -> detail.term,          // map by term
                        detail -> List.of(detail),      // create list for term
                        (list1, list2)                  // merge lists for same term
                            -> Stream.of(list1, list2)
                                .flatMap(List::stream)
                                .collect(Collectors.toList())
                    )
            );

            // lock, clear old, load new
            synchronized( IndexCache.getInstance().getIndicesByTerm() ) {
                IndexCache.getInstance().getIndicesByTerm().clear();
                IndexCache.getInstance().getIndicesByTerm().putAll(indicesByTerm);
            }

            LOGGER.debug("processing indices by issuer ...");
            Map<String, List<ArbitraryDataIndexDetail>> indicesByIssuer
                = indexDetails.stream().collect(
                    Collectors.toMap(
                        detail -> detail.issuer,        // map by issuer
                        detail -> List.of(detail),      // create list for issuer
                        (list1, list2)                  // merge lists for same issuer
                                -> Stream.of(list1, list2)
                                .flatMap(List::stream)
                                .collect(Collectors.toList())
                    )
            );

            // lock, clear old, load new
            synchronized( IndexCache.getInstance().getIndicesByIssuer() ) {
                IndexCache.getInstance().getIndicesByIssuer().clear();
                IndexCache.getInstance().getIndicesByIssuer().putAll(indicesByIssuer);
            }
        }
    }

    /**
     * Are Resource Details Updated?
     *
     * @param indexResource the resource to use as a comparative reference
     * @param indexResourceDetails the resource details
     *
     * @return true if the details are updated, otherwise false
     */
    private static boolean detailsAreUpdatedToResource(ArbitraryResourceData indexResource, IndexCache.IndexResourceDetails indexResourceDetails) {
        // if the index resource has not been updated, then assume the details are the latest update
        if( indexResource.updated == null ) return true;

        // if the index resource has been updated and details have been updated and the details are updated to or later than the index resource,
        // then assume the details are the latest update
        if( indexResourceDetails.resource.updated != null && indexResourceDetails.resource.updated >= indexResource.updated ) return true;

        return false;
    }

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


    public static String getJsonWithExceptionHandling( String name, String identifier ) {
        try {
            return getJson(name, identifier);
        }
        catch( Exception e ) {
            LOGGER.error(e.getMessage(), e);
            return e.getMessage();
        }
    }

    public static String getJson(String name, String identifier) throws IOException, MissingDataException {

        try {
            ArbitraryDataReader arbitraryDataReader
                = new ArbitraryDataReader(name, ArbitraryDataFile.ResourceIdType.NAME, Service.JSON, identifier);

            int attempts = 0;
            Integer maxAttempts = 5;

            while (!Controller.isStopping()) {
                attempts++;
                if (!arbitraryDataReader.isBuilding()) {
                    try {
                        arbitraryDataReader.loadSynchronously(false);
                        break;
                    } catch (MissingDataException e) {
                        if (attempts > maxAttempts) {
                            // Give up after 5 attempts
                            throw e;
                        }
                    }
                }
            }

            java.nio.file.Path outputPath = arbitraryDataReader.getFilePath();
            if (outputPath == null) {
                // Assume the resource doesn't exist
                throw new IOException( "File not found");
            }

            // No file path supplied - so check if this is a single file resource
            String[] files = ArrayUtils.removeElement(outputPath.toFile().list(), ".qortal");
            String filepath = files[0];

            java.nio.file.Path path = Paths.get(outputPath.toString(), filepath);
            if (!Files.exists(path)) {
                String message = String.format("No file exists at filepath: %s", filepath);
                throw new IOException( message );
            }

            String data = Files.readString(path);

            return data;
        } catch (Exception e) {
            throw new IOException(String.format("Unable to load %s %s: %s", Service.JSON, name, e.getMessage()));
        }
    }

    /**
     * Get Matched Terms
     *
     * @param terms the terms of interest
     * @param indexedTerms the terms indexed
     *
     * @return the terms of interest that match the terms indexed
     */
    public static List<AdvancedStringMatcher.MatchResult> getMatchedTerms(String[] terms, List<String> indexedTerms) {
        // Create matcher with morphological matching enabled
        AdvancedStringMatcher.MatcherConfig config = new AdvancedStringMatcher.MatcherConfig();
        config.setMisspellingThreshold(0.3);
        config.setPartialMatchThreshold(0.6);
        config.setPartialPhraseThreshold(0.7);
        config.setSynonymThreshold(0.3);
        config.setMorphologicalThreshold(0.6);
        config.setCaseSensitive(false);
        config.setEnableMorphological(true);
        config.setStopWordWeight(0.2);
        config.setMinWordsForPartialMatch(2);

        AdvancedStringMatcher matcher = new AdvancedStringMatcher(indexedTerms, config);

        List<AdvancedStringMatcher.MatchResult> matchedTerms = new ArrayList<>();

        // for each term of interest, add matches
        for( String term : terms) {

            matchedTerms.addAll(matcher.findMatches(term));
        }

        // for each matched string, only retain the strongest match
        return matchedTerms.stream()
                .collect(Collectors.groupingBy(
                        index -> index.getMatchedString(),
                        Collectors.maxBy(Comparator.comparingDouble(index -> index.getSimilarity()))
                ))
                .values()
                .stream()
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toList());
    }

    /**
     * Get Arbitrary Data Index Scorecards
     *
     * @param indices the indices to score
     *
     * @return the scorecards
     */
    public static List<ArbitraryDataIndexScorecard> getArbitraryDataIndexScorecards(List<ArbitraryDataIndexDetail> indices) {
        // sum up the scores for each index with identical attributes,
        // for any index with identical attributes only one per issuer
        Map<ArbitraryDataIndexScoreKey, Double> scoreForKey
            = indices.stream()
                .collect(Collectors.groupingBy(
                        index -> new IssuerIndexKey(index),
                        Collectors.minBy(Comparator.comparingInt(index -> index.rank))
                ))
                .values()
                .stream()
                .filter(Optional::isPresent)
                .map(Optional::get)
                    .collect(
                        Collectors.groupingBy(
                                index -> new ArbitraryDataIndexScoreKey(index.name, index.category, index.link),
                                Collectors.summingDouble(detail -> 1.0 / detail.rank)
                        )
                );

        // create scorecards for each index group and put them in descending order by score
        List<ArbitraryDataIndexScorecard> scorecards
            = scoreForKey.entrySet().stream().map(
                    entry
                            ->
                            new ArbitraryDataIndexScorecard(
                                    entry.getValue(),
                                    entry.getKey().name,
                                    entry.getKey().category,
                                    entry.getKey().link)
            )
            .sorted(Comparator.comparingDouble(ArbitraryDataIndexScorecard::getScore).reversed())
            .collect(Collectors.toList());

        return scorecards;
    }

    /**
     * Reduce Ranks
     *
     * For indices that are not exact matches, reduce their ranks for post-processing.
     *
     * @param indices the indices collected
     * @param similarity the similarity to apply to all index details
     * @param details the details of each index for the matched string of the match result
     */
    public static void reduceRanks(List<ArbitraryDataIndexDetail> indices, double similarity, List<ArbitraryDataIndexDetail> details) {

        // if the similarity is less than 1, reduce the rank of each index to at least 2
        if( similarity < 1.0 ) {
            for( ArbitraryDataIndexDetail detail : details) {
                double rankReduced = detail.rank / similarity;
                indices.add(
                    new ArbitraryDataIndexDetail(
                        detail.issuer,
                        Math.max(2, (int) rankReduced),
                        new ArbitraryDataIndex(detail.term, detail.name, detail.category, detail.link),
                        detail.indexIdentifer)
                );
            }
        }
        // if the similarity is 1 or higher, then do not reduce the rank
        else {
            indices.addAll(details);
        }
    }
}