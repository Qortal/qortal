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
import org.qortal.data.arbitrary.ArbitraryDataIndex;
import org.qortal.data.arbitrary.ArbitraryDataIndexDetail;
import org.qortal.data.arbitrary.ArbitraryResourceData;
import org.qortal.data.arbitrary.IndexCache;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

                if (RepositoryManager.getRepositoryFactory() == null) {
                    LOGGER.debug("Repository unavailable; skipping arbitrary index cache fill");
                    return;
                }

                try {
                    fillCache(IndexCache.getInstance());
                } catch (DataException e) {
                    if (isRepositoryUnavailable(e)) {
                        LOGGER.debug("Repository unavailable; skipping arbitrary index cache fill");
                    } else {
                        LOGGER.warn(e.getMessage());
                    }
                } catch (IOException e) {
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
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    true);

            List<ArbitraryDataIndexDetail> indexDetails = new ArrayList<>();

            LOGGER.debug("processing index resource data: count = " + indexResources.size());

            // process all index resources
            for( ArbitraryResourceData indexResource : indexResources ) {

                try {
                    LOGGER.debug("processing index resource: name = " + indexResource.name + ", identifier = " + indexResource.identifier);
                    String json = ArbitraryIndexUtils.getJson(indexResource.name, indexResource.identifier);

                    // map the JSON string to a list of Java objects
                    List<ArbitraryDataIndex> indices = OBJECT_MAPPER.readValue(json, new TypeReference<List<ArbitraryDataIndex>>() {});

                    LOGGER.debug("processed indices = " + indices);

                    // rank and create index detail for each index in this index resource
                    for( int rank = 1; rank <= indices.size(); rank++ ) {

                        indexDetails.add( new ArbitraryDataIndexDetail(indexResource.name, rank, indices.get(rank - 1), indexResource.identifier ));
                    }
                } catch (MissingDataException e) {
                    LOGGER.warn( e.getMessage() );
                } catch (InvalidFormatException e) {
                   LOGGER.debug("invalid format, skipping: " + indexResource);
                } catch (UnrecognizedPropertyException e) {
                    LOGGER.debug("unrecognized property, skipping " + indexResource);
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
}
