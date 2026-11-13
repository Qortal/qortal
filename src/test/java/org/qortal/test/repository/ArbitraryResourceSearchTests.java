package org.qortal.test.repository;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortal.api.SearchMode;
import org.qortal.arbitrary.misc.Category;
import org.qortal.arbitrary.misc.Service;
import org.qortal.data.arbitrary.ArbitraryResourceData;
import org.qortal.data.arbitrary.ArbitraryResourceMetadata;
import org.qortal.data.arbitrary.ArbitraryResourceStatus.Status;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.settings.Settings;
import org.qortal.test.common.Common;

import java.lang.reflect.Field;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class ArbitraryResourceSearchTests extends Common {

    private Field cacheEnabled;
    private boolean originalCacheEnabled;

    @Before
    public void beforeTest() throws Exception {
        Common.useDefaultSettings();
        // Exercise the SQL fallback rather than the separate in-memory search implementation.
        cacheEnabled = Settings.class.getDeclaredField("dbCacheEnabled");
        cacheEnabled.setAccessible(true);
        originalCacheEnabled = cacheEnabled.getBoolean(Settings.getInstance());
        cacheEnabled.setBoolean(Settings.getInstance(), false);
    }

    @After
    public void restoreCacheSetting() throws Exception {
        cacheEnabled.setBoolean(Settings.getInstance(), originalCacheEnabled);
    }

    @Test
    public void testDefaultSearchDoesNotReadMetadataTable() throws Exception {
        try (Repository repository = RepositoryManager.getRepository()) {
            saveResource(repository, "Alpha", "default", 100L, "Metadata title");
            saveResource(repository, "Beta", "default", 200L, null);
            // A search that does not use metadata must still work when that table is unavailable.
            dropMetadataTable(repository);
            for (Boolean includeMetadata : Arrays.asList(null, false)) {
                List<ArbitraryResourceData> results = search(repository, "alp", null, null, null,
                        true, includeMetadata, SearchMode.ALL, null, null, null);
                assertEquals(1, results.size());
                assertEquals("Alpha", results.get(0).name);
                assertNull(results.get(0).identifier);
                assertNull(results.get(0).metadata);
                assertEquals(Status.PUBLISHED, results.get(0).status.getStatus());
                assertArrayEquals(new byte[64], results.get(0).latestSignature);
            }
        }
    }

    @Test
    public void testNonDefaultSearchWithoutQueryDoesNotReadMetadataTable() throws Exception {
        try (Repository repository = RepositoryManager.getRepository()) {
            saveResource(repository, "Alpha", "one", 100L, null);
            saveResource(repository, "Beta", "two", 200L, null);
            dropMetadataTable(repository);
            List<ArbitraryResourceData> results = search(repository, null, null, null, Collections.emptyList(),
                    false, false, SearchMode.ALL, 1, 1, true);
            assertEquals(1, results.size());
            assertEquals("Alpha", results.get(0).name);
            assertEquals("one", results.get(0).identifier);
            assertEquals(Long.valueOf(100L), results.get(0).created);
        }
    }

    @Test
    public void testMetadataFiltersWorkWithoutReturningMetadata() throws Exception {
        try (Repository repository = RepositoryManager.getRepository()) {
            saveResource(repository, "Alpha", "default", 100L, "Unique title");
            saveResource(repository, "Beta", "default", 200L, "Other");
            for (Boolean includeMetadata : Arrays.asList(null, false)) {
                // General non-default searches include title and description, even without metadata output.
                assertAlpha(search(repository, "unique", null, null, null,
                        false, includeMetadata, SearchMode.ALL, null, null, null));
                assertAlpha(search(repository, null, "unique", null, null,
                        true, includeMetadata, SearchMode.ALL, null, null, null));
                assertAlpha(search(repository, null, null, "unique", null,
                        true, includeMetadata, SearchMode.ALL, null, null, null));
                assertAlpha(search(repository, null, null, null, Arrays.asList("missing", "unique"),
                        true, includeMetadata, SearchMode.ALL, null, null, null));
                // Default-resource general queries search names only, not metadata.
                assertTrue(search(repository, "unique", null, null, null,
                        true, includeMetadata, SearchMode.ALL, null, null, null).isEmpty());
            }
        }
    }

    @Test
    public void testMetadataOutputIncludesTagsAndKeepsResourcesWithoutMetadata() throws Exception {
        try (Repository repository = RepositoryManager.getRepository()) {
            saveResource(repository, "Alpha", "default", 100L, "Unique title");
            saveResource(repository, "Beta", "default", 200L, null);
            List<ArbitraryResourceData> results = search(repository, null, null, null, null,
                    true, true, SearchMode.ALL, null, null, null);
            assertEquals(2, results.size());
            assertEquals("Unique title", results.get(0).metadata.getTitle());
            assertEquals("Unique title description", results.get(0).metadata.getDescription());
            assertEquals(Arrays.asList("tag-one", "tag-two"), results.get(0).metadata.getTags());
            assertEquals(Category.TECHNOLOGY, results.get(0).metadata.getCategory());
            assertArrayEquals(new byte[64], results.get(0).latestSignature);
            assertNull(results.get(1).metadata);
        }
    }

    @Test
    public void testLatestModeAndPaginationWithoutMetadata() throws Exception {
        try (Repository repository = RepositoryManager.getRepository()) {
            saveResource(repository, "Alpha", "old", 100L, "Old");
            saveResource(repository, "Alpha", "new", 300L, "New");
            saveResource(repository, "Beta", "default", 200L, null);
            dropMetadataTable(repository);
            for (SearchMode mode : Arrays.asList(null, SearchMode.LATEST)) {
                List<ArbitraryResourceData> results = search(repository, null, null, null, null,
                        false, false, mode, 1, 0, true);
                assertEquals(1, results.size());
                assertEquals("new", results.get(0).identifier);
                results = search(repository, null, null, null, null, false, false, mode, 1, 1, true);
                assertEquals(1, results.size());
                assertEquals("Beta", results.get(0).name);
            }
        }
    }

    private static List<ArbitraryResourceData> search(Repository repository, String query, String title,
            String description, List<String> keywords, boolean defaultResource, Boolean includeMetadata,
            SearchMode mode, Integer limit, Integer offset, Boolean reverse) throws Exception {
        return repository.getArbitraryRepository().searchArbitraryResources(Service.WEBSITE, query, null,
                null, title, description, keywords, false, null, defaultResource, mode, null, false, false,
                includeMetadata, true, null, null, limit, offset, reverse);
    }

    private static void assertAlpha(List<ArbitraryResourceData> results) {
        assertEquals(1, results.size());
        assertEquals("Alpha", results.get(0).name);
        assertNull(results.get(0).metadata);
    }

    private static void dropMetadataTable(Repository repository) throws Exception {
        try (Statement statement = repository.getConnection().createStatement()) {
            statement.execute("DROP TABLE ArbitraryMetadataCache");
        }
    }

    private static void saveResource(Repository repository, String name, String identifier, long created,
            String title) throws Exception {
        ArbitraryResourceData resource = new ArbitraryResourceData();
        resource.name = name;
        resource.service = Service.WEBSITE;
        resource.identifier = identifier;
        resource.size = 123;
        resource.created = created;
        resource.latestSignature = new byte[64];
        repository.getArbitraryRepository().save(resource);
        if (title != null) {
            ArbitraryResourceMetadata metadata = new ArbitraryResourceMetadata();
            metadata.setArbitraryResourceData(resource);
            metadata.setTitle(title);
            metadata.setDescription(title + " description");
            metadata.setTags(Arrays.asList("tag-one", "tag-two"));
            metadata.setCategory(Category.TECHNOLOGY);
            repository.getArbitraryRepository().save(metadata);
        }
        repository.saveChanges();
    }
}
