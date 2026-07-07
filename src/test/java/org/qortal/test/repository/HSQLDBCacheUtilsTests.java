package org.qortal.test.repository;

import org.junit.Assert;
import org.junit.Test;
import org.qortal.api.SearchMode;
import org.qortal.arbitrary.misc.Service;
import org.qortal.data.arbitrary.ArbitraryResourceData;
import org.qortal.data.arbitrary.ArbitraryResourceMetadata;
import org.qortal.data.arbitrary.ArbitraryResourceStatus;
import org.qortal.repository.hsqldb.HSQLDBCacheUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

public class HSQLDBCacheUtilsTests {

    private static final Map<String, Integer> NAME_LEVEL = Map.of("Joe", 4);
    private static final String SERVICE = "service";
    private static final String QUERY = "query";
    private static final String IDENTIFIER = "identifier";
    private static final String NAMES = "names";
    private static final String TITLE = "title";
    private static final String DESCRIPTION = "description";
    private static final String PREFIX_ONLY = "prefixOnly";
    private static final String EXACT_MATCH_NAMES = "exactMatchNames";
    private static final String KEYWORDS = "keywords";
    private static final String DEFAULT_RESOURCE = "defaultResource";
    private static final String MODE = "mode";
    private static final String MIN_LEVEL = "minLevel";
    private static final String FOLLOWED_ONLY = "followedOnly";
    private static final String EXCLUDE_BLOCKED = "excludeBlocked";
    private static final String INCLUDE_METADATA = "includeMetadata";
    private static final String INCLUDE_STATUS = "includeStatus";
    private static final String BEFORE = "before";
    private static final String AFTER = "after";
    private static final String LIMIT = "limit";
    private static final String OFFSET = "offset";
    private static final String REVERSE = "reverse";

    @Test
    public void test000EmptyQuery() {

        ArbitraryResourceData data = new ArbitraryResourceData();
        data.name = "joe";

        List<ArbitraryResourceData> candidates = List.of(data);

        filterListByMap(candidates, NAME_LEVEL, new HashMap<>(Map.of()), 1);
    }

    @Test
    public void testLatestModeNoService() {
        ArbitraryResourceData data = new ArbitraryResourceData();
        data.name = "joe";

        filterListByMap(
            List.of(data),
            NAME_LEVEL,
            new HashMap<>(Map.of(MODE, SearchMode.LATEST )),
            0);
    }

    @Test
    public void testLatestModeNoCreated() {
        ArbitraryResourceData data = new ArbitraryResourceData();
        data.name = "joe";
        data.service = Service.FILE;

        filterListByMap(
                List.of(data),
                NAME_LEVEL,
                new HashMap<>(Map.of(MODE, SearchMode.LATEST )),
                0);
    }

    @Test
    public void testLatestModeReturnFirst() {
        ArbitraryResourceData first = new ArbitraryResourceData();
        first.name = "joe";
        first.service = Service.FILE;
        first.created = 1L;

        ArbitraryResourceData last = new ArbitraryResourceData();
        last.name = "joe";
        last.service = Service.FILE;
        last.created = 2L;

        List<ArbitraryResourceData>
            results = filterListByMap(
                List.of(first, last),
                NAME_LEVEL,
                new HashMap<>(Map.of(MODE, SearchMode.LATEST)),
                1
        );

        ArbitraryResourceData singleResult = results.get(0);
        Assert.assertTrue( singleResult.created == 2 );
    }

    @Test
    public void testLatestModeReturn2From4() {
        ArbitraryResourceData firstFile = new ArbitraryResourceData();
        firstFile.name = "joe";
        firstFile.service = Service.FILE;
        firstFile.created = 1L;

        ArbitraryResourceData lastFile = new ArbitraryResourceData();
        lastFile.name = "joe";
        lastFile.service = Service.FILE;
        lastFile.created = 2L;

        List<ArbitraryResourceData>
                results = filterListByMap(
                List.of(firstFile, lastFile),
                NAME_LEVEL,
                new HashMap<>(Map.of(MODE, SearchMode.LATEST)),
                1
        );

        ArbitraryResourceData singleResult = results.get(0);
        Assert.assertTrue( singleResult.created == 2 );
    }

    @Test
    public void testServicePositive() {
        ArbitraryResourceData data = new ArbitraryResourceData();
        data.service = Service.AUDIO;
        data.name = "Joe";

        filterListByMap(
                List.of(data),
                NAME_LEVEL, new HashMap<>(Map.of(SERVICE, Service.AUDIO)),
                1
        );
    }

    @Test
    public void testQueryPositiveDescription() {

        ArbitraryResourceData data = new ArbitraryResourceData();
        data.metadata = new ArbitraryResourceMetadata();
        data.metadata.setDescription("has keyword");
        data.name = "Joe";

        filterListByMap(
                List.of(data),
                NAME_LEVEL, new HashMap<>(Map.of(QUERY, "keyword")),
                1
        );
    }

    @Test
    public void testQueryNegativeDescriptionPrefix() {
        ArbitraryResourceData data = new ArbitraryResourceData();
        data.metadata = new ArbitraryResourceMetadata();
        data.metadata.setDescription("has keyword");
        data.name = "Joe";

        filterListByMap(List.of(data),
                NAME_LEVEL, new HashMap<>((Map.of(QUERY, "keyword", PREFIX_ONLY, true))),
                0);
    }

    @Test
    public void testQueryPositiveDescriptionPrefix() {
        ArbitraryResourceData data = new ArbitraryResourceData();
        data.metadata = new ArbitraryResourceMetadata();
        data.metadata.setDescription("keyword starts sentence");
        data.name = "Joe";

        filterListByMap(List.of(data),
                NAME_LEVEL, new HashMap<>((Map.of(QUERY, "keyword", PREFIX_ONLY, true))),
                1);
    }

    @Test
    public void testQueryNegative() {

        ArbitraryResourceData data = new ArbitraryResourceData();

        data.name = "admin";
        data.identifier = "id-0";

        filterListByMap(
                List.of(data),
                NAME_LEVEL, new HashMap<>(Map.of(QUERY, "keyword")),
                0
        );
    }

    @Test
    public void testExactNamePositive() {

        ArbitraryResourceData data = new ArbitraryResourceData();
        data.name = "Joe";

        filterListByMap(
                List.of(data),
                NAME_LEVEL, new HashMap<>(Map.of(EXACT_MATCH_NAMES,List.of("Joe"))),
                1
        );
    }

    @Test
    public void testExactNameNegative() {

        ArbitraryResourceData data = new ArbitraryResourceData();
        data.name = "Joe";

        filterListByMap(
                List.of(data),
                NAME_LEVEL,
                new HashMap<>(Map.of(EXACT_MATCH_NAMES,List.of("Joey"))),
                0
        );
    }

    @Test
    public void testNamePositive() {

        ArbitraryResourceData data = new ArbitraryResourceData();
        data.name = "Mr Joe";

        filterListByMap(
                List.of(data),
                NAME_LEVEL,
                new HashMap<>(Map.of(NAMES,List.of("Joe"))),
                1
        );
    }

    @Test
    public void testNameNegative() {

        ArbitraryResourceData data = new ArbitraryResourceData();
        data.name = "Jay";

        filterListByMap(
                List.of(data),
                NAME_LEVEL,
                new HashMap<>(Map.of(NAMES,List.of("Joe"))),
                0
        );
    }

    @Test
    public void testNamePrefixOnlyPositive() {

        ArbitraryResourceData data = new ArbitraryResourceData();
        data.name = "Joey";

        filterListByMap(
                List.of(data),
                NAME_LEVEL,
                new HashMap<>(Map.of(NAMES,List.of("Joe"), PREFIX_ONLY, true)),
                1
        );
    }

    @Test
    public void testNamePrefixOnlyNegative() {

        ArbitraryResourceData data = new ArbitraryResourceData();
        data.name = "Joe";

        filterListByMap(
                List.of(data),
                NAME_LEVEL,
                new HashMap<>(Map.of(NAMES,List.of("Joey"), PREFIX_ONLY, true)),
                0
        );
    }

    @Test
    public void testIdentifierPositive() {
        ArbitraryResourceData data = new ArbitraryResourceData();
        data.identifier = "007";
        data.name = "Joe";

        filterListByMap(
                List.of(data),
                NAME_LEVEL, new HashMap<>(Map.of(IDENTIFIER, "007")),
                1
        );
    }

    @Test
    public void testAfterPositive() {
        ArbitraryResourceData data = new ArbitraryResourceData();
        data.created = 10L;
        data.name = "Joe";

        filterListByMap(
                List.of(data),
                NAME_LEVEL, new HashMap<>(Map.of(AFTER, 9L)),
                1
        );
    }

    @Test
    public void testAfterNegative() {
        ArbitraryResourceData data = new ArbitraryResourceData();
        data.created = 10L;
        data.name = "Joe";

        filterListByMap(
                List.of(data),
                NAME_LEVEL, new HashMap<>(Map.of(AFTER, 11L)),
                0
        );
    }

    @Test
    public void testBeforePositive(){
        ArbitraryResourceData data = new ArbitraryResourceData();
        data.created = 10L;
        data.name = "Joe";

        filterListByMap(
                List.of(data),
                NAME_LEVEL, new HashMap<>(Map.of(BEFORE, 11L)),
                1
        );
    }

    @Test
    public void testBeforeNegative(){
        ArbitraryResourceData data = new ArbitraryResourceData();
        data.created = 10L;
        data.name = "Joe";

        filterListByMap(
                List.of(data),
                NAME_LEVEL, new HashMap<>(Map.of(BEFORE, 9L)),
                0
        );
    }

    @Test
    public void testTitlePositive() {

        ArbitraryResourceData data = new ArbitraryResourceData();
        data.metadata = new ArbitraryResourceMetadata();
        data.metadata.setTitle("Sunday");
        data.name = "Joe";

        filterListByMap(
                List.of(data),
                NAME_LEVEL, new HashMap<>(Map.of(TITLE, "Sunday")),
                1
        );
    }

    @Test
    public void testDescriptionPositive(){

        ArbitraryResourceData data = new ArbitraryResourceData();
        data.metadata = new ArbitraryResourceMetadata();
        data.metadata.setDescription("Once upon a time.");
        data.name = "Joe";

        filterListByMap(
                List.of(data),
                NAME_LEVEL, new HashMap<>(Map.of(DESCRIPTION, "Once upon a time.")),
                1
        );
    }

    @Test
    public void testMetadataNullificationBugSolution(){

        ArbitraryResourceData data = new ArbitraryResourceData();
        data.metadata = new ArbitraryResourceMetadata();
        data.metadata.setDescription("Once upon a time.");
        data.name = "Joe";

        List<ArbitraryResourceData> list = List.of(data);

        filterListByMap(
                List.of(data),
                NAME_LEVEL, new HashMap<>(Map.of(DESCRIPTION, "Once upon a time.")),
                1
        );

        Assert.assertNotNull(data.metadata);
    }

    @Test
    public void testMinLevelPositive() {

        ArbitraryResourceData data = new ArbitraryResourceData();
        data.name = "Joe";

        filterListByMap(
                List.of(data),
                NAME_LEVEL,
                new HashMap<>(Map.of(MIN_LEVEL, 4)),
                1
        );
    }

    @Test
    public void testMinLevelNegative() {

        ArbitraryResourceData data = new ArbitraryResourceData();
        data.name = "Joe";

        filterListByMap(
                List.of(data),
                NAME_LEVEL,
                new HashMap<>(Map.of(MIN_LEVEL, 5)),
                0
        );
    }

    @Test
    public void testDefaultResourcePositive() {

        ArbitraryResourceData data = new ArbitraryResourceData();
        data.name = "Joe";

        filterListByMap(
                List.of(data),
                NAME_LEVEL, new HashMap<>(Map.of(DEFAULT_RESOURCE, true)),
                1
        );
    }

    @Test
    public void testDefaultResourceExcludesIdentifiersWithoutQuery() {

        ArbitraryResourceData defaultResource = new ArbitraryResourceData();
        defaultResource.name = "Joe";

        ArbitraryResourceData identifiedResource = new ArbitraryResourceData();
        identifiedResource.name = "Joe";
        identifiedResource.identifier = "app";

        List<ArbitraryResourceData> result = filterListByMap(
                List.of(defaultResource, identifiedResource),
                NAME_LEVEL, new HashMap<>(Map.of(DEFAULT_RESOURCE, true)),
                1
        );

        Assert.assertNull(result.get(0).identifier);
    }

    @Test
    public void testDefaultResourceQuerySearchesNameOnly() {

        ArbitraryResourceData defaultResource = new ArbitraryResourceData();
        defaultResource.name = "Joe";

        ArbitraryResourceData identifiedResource = new ArbitraryResourceData();
        identifiedResource.name = "Other";
        identifiedResource.identifier = "Joe";

        List<ArbitraryResourceData> result = filterListByMap(
                List.of(defaultResource, identifiedResource),
                NAME_LEVEL, new HashMap<>(Map.of(DEFAULT_RESOURCE, true, QUERY, "Joe")),
                1
        );

        Assert.assertNull(result.get(0).identifier);
    }

    @Test
    public void testDefaultResourceAcceptsDefaultIdentifierLiteral() {

        ArbitraryResourceData data = new ArbitraryResourceData();
        data.name = "Joe";
        data.identifier = "default";

        List<ArbitraryResourceData> result = filterListByMap(
                List.of(data),
                NAME_LEVEL, new HashMap<>(Map.of(DEFAULT_RESOURCE, true)),
                1
        );

        Assert.assertEquals("default", result.get(0).identifier);
    }

    @Test
    public void testFollowedNamesNegative() {

        ArbitraryResourceData data = new ArbitraryResourceData();
        data.name = "Joe";

        Supplier<List<String>> supplier = () -> List.of("admin");

        filterListByMap(
                List.of(data),
                NAME_LEVEL, new HashMap<>(Map.of(FOLLOWED_ONLY, supplier)),
                0
        );
    }

    @Test
    public void testFollowedNamesPositive() {

        ArbitraryResourceData data = new ArbitraryResourceData();
        data.name = "Joe";

        Supplier<List<String>> supplier = () -> List.of("Joe");

        filterListByMap(
                List.of(data),
                NAME_LEVEL, new HashMap<>(Map.of(FOLLOWED_ONLY, supplier)),
                1
        );
    }

    @Test
    public void testExcludeBlockedNegative() {

        ArbitraryResourceData data = new ArbitraryResourceData();
        data.name = "Joe";

        Supplier<List<String>> supplier = () -> List.of("admin");

        filterListByMap(
                List.of(data),
                NAME_LEVEL, new HashMap<>(Map.of(EXCLUDE_BLOCKED, supplier)),
                1
        );
    }

    @Test
    public void testExcludeBlockedPositive() {

        ArbitraryResourceData data = new ArbitraryResourceData();
        data.name = "Joe";

        Supplier<List<String>> supplier = () -> List.of("Joe");

        filterListByMap(
                List.of(data),
                NAME_LEVEL, new HashMap<>(Map.of(EXCLUDE_BLOCKED, supplier)),
                0
        );
    }

    @Test
    public void testIncludeMetadataPositive() {

        ArbitraryResourceData data = new ArbitraryResourceData();
        data.metadata = new ArbitraryResourceMetadata();
        data.name = "Joe";

        ArbitraryResourceData result
            = filterListByMap(
                List.of(data),
                NAME_LEVEL, new HashMap<>(Map.of(INCLUDE_METADATA, true)),
                1
        ).get(0);

        Assert.assertNotNull(result);
        Assert.assertNotNull(result.metadata);
    }

    @Test
    public void testIncludesMetadataNegative() {

        ArbitraryResourceData data = new ArbitraryResourceData();
        data.metadata = new ArbitraryResourceMetadata();
        data.name = "Joe";

        ArbitraryResourceData result
                = filterListByMap(
                        List.of(data),
                NAME_LEVEL, new HashMap<>(Map.of(INCLUDE_METADATA, false)),
                1
        ).get(0);

        Assert.assertNotNull(result);
        Assert.assertNull(result.metadata);
    }

    @Test
    public void testIncludeStatusPositive() {

        ArbitraryResourceData data = new ArbitraryResourceData();
        data.status = new ArbitraryResourceStatus();
        data.name = "Joe";

        ArbitraryResourceData result
            = filterListByMap(
                List.of(data),
                NAME_LEVEL, new HashMap<>(Map.of(INCLUDE_STATUS, true)),
                1
        ).get(0);

        Assert.assertNotNull(result);
        Assert.assertNotNull(result.status);
    }

    @Test
    public void testIncludeStatusNegative() {

        ArbitraryResourceData data = new ArbitraryResourceData();
        data.status = new ArbitraryResourceStatus();
        data.name = "Joe";

        ArbitraryResourceData result
                = filterListByMap(
                        List.of(data),
                NAME_LEVEL, new HashMap<>(Map.of(INCLUDE_STATUS, false)),
                1
        ).get(0);

        Assert.assertNotNull(result);
        Assert.assertNull(result.status);
    }

    @Test
    public void testLimit() {

        ArbitraryResourceData data1 = new ArbitraryResourceData();
        data1.name = "Joe";

        ArbitraryResourceData data2 = new ArbitraryResourceData();
        data2.name = "Joe";

        ArbitraryResourceData data3 = new ArbitraryResourceData();
        data3.name = "Joe";

        filterListByMap(
            List.of(data1, data2, data3),
                NAME_LEVEL, new HashMap<>(Map.of(LIMIT, 2)),
            2
        );
    }

    @Test
    public void testLimitZero() {

        ArbitraryResourceData data = new ArbitraryResourceData();
        data.name = "Joe";

        filterListByMap(
                List.of(data),
                NAME_LEVEL,
                new HashMap<>(Map.of(LIMIT, 0)),
                1
        );
    }

    @Test
    public void testOffset() {

        ArbitraryResourceData data1 = new ArbitraryResourceData();
        data1.created = 1L;
        data1.name = "Joe";

        ArbitraryResourceData data2 = new ArbitraryResourceData();
        data2.created = 2L;
        data2.name = "Joe";

        ArbitraryResourceData data3 = new ArbitraryResourceData();
        data3.created = 3L;
        data3.name = "Joe";

        List<ArbitraryResourceData> result
            = filterListByMap(
                List.of(data1, data2, data3),
                NAME_LEVEL, new HashMap<>(Map.of(OFFSET, 1)),
                2
        );

        Assert.assertNotNull(result.get(0));
        Assert.assertTrue(2L == result.get(0).created);
    }

    @Test
    public void testOrder() {

        ArbitraryResourceData data2 = new ArbitraryResourceData();
        data2.created = 2L;
        data2.name = "Joe";

        ArbitraryResourceData data1 = new ArbitraryResourceData();
        data1.created = 1L;
        data1.name = "Joe";

        List<ArbitraryResourceData> result
                = filterListByMap(
                        List.of(data2, data1),
                NAME_LEVEL, new HashMap<>(),
                2
        );

        Assert.assertNotNull(result.get(0));
        Assert.assertTrue( result.get(0).created == 1L );
    }

    @Test
    public void testReverseOrder() {
        ArbitraryResourceData data1 = new ArbitraryResourceData();
        data1.created = 1L;
        data1.name = "Joe";

        ArbitraryResourceData data2 = new ArbitraryResourceData();
        data2.created = 2L;
        data2.name = "Joe";

        List<ArbitraryResourceData> result
            = filterListByMap(
                List.of(data1, data2),
                NAME_LEVEL, new HashMap<>(Map.of(REVERSE, true)),
                2);

        Assert.assertNotNull( result.get(0));
        Assert.assertTrue( result.get(0).created == 2L);

    }
    public static List<ArbitraryResourceData> filterListByMap(
            List<ArbitraryResourceData> candidates,
            Map<String, Integer> levelByName,
            HashMap<String, Object> valueByKey,
            int sizeToAssert) {

        Optional<Service> service = Optional.ofNullable((Service) valueByKey.get(SERVICE));
        Optional<String> query = Optional.ofNullable( (String) valueByKey.get(QUERY));
        Optional<String> identifier = Optional.ofNullable((String) valueByKey.get(IDENTIFIER));
        Optional<List<String>> names = Optional.ofNullable((List<String>) valueByKey.get(NAMES));
        Optional<String> title = Optional.ofNullable((String) valueByKey.get(TITLE));
        Optional<String> description = Optional.ofNullable((String) valueByKey.get(DESCRIPTION));
        boolean prefixOnly = valueByKey.containsKey(PREFIX_ONLY);
        Optional<List<String>> exactMatchNames = Optional.ofNullable((List<String>) valueByKey.get(EXACT_MATCH_NAMES));
        Optional<List<String>> keywords = Optional.ofNullable((List<String>) valueByKey.get(KEYWORDS));
        boolean defaultResource = valueByKey.containsKey(DEFAULT_RESOURCE);
        Optional<SearchMode> mode = Optional.of((SearchMode) valueByKey.getOrDefault(MODE, SearchMode.ALL));
        Optional<Integer> minLevel = Optional.ofNullable((Integer) valueByKey.get(MIN_LEVEL));
        Optional<Supplier<List<String>>> followedOnly = Optional.ofNullable((Supplier<List<String>>) valueByKey.get(FOLLOWED_ONLY));
        Optional<Supplier<List<String>>> excludeBlocked = Optional.ofNullable((Supplier<List<String>>) valueByKey.get(EXCLUDE_BLOCKED));
        Optional<Boolean> includeMetadata = Optional.ofNullable((Boolean) valueByKey.get(INCLUDE_METADATA));
        Optional<Boolean> includeStatus = Optional.ofNullable((Boolean) valueByKey.get(INCLUDE_STATUS));
        Optional<Long> before = Optional.ofNullable((Long) valueByKey.get(BEFORE));
        Optional<Long> after = Optional.ofNullable((Long) valueByKey.get(AFTER));
        Optional<Integer> limit = Optional.ofNullable((Integer) valueByKey.get(LIMIT));
        Optional<Integer> offset = Optional.ofNullable((Integer) valueByKey.get(OFFSET));
        Optional<Boolean> reverse = Optional.ofNullable((Boolean) valueByKey.get(REVERSE));

        List<ArbitraryResourceData> filteredList
                = HSQLDBCacheUtils.filterList(
                candidates,
                levelByName,
                mode,
                service,
                query,
                identifier,
                names,
                title,
                description,
                prefixOnly,
                exactMatchNames,
                keywords,
                defaultResource,
                minLevel,
                followedOnly,
                excludeBlocked,
                includeMetadata,
                includeStatus,
                before,
                after,
                limit,
                offset,
                reverse);

        Assert.assertEquals(sizeToAssert, filteredList.size());

        return filteredList;
    }
}
