package org.qortal.data.arbitrary;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ArbitraryResourceCache {
    /**
     * service.value -> (name + "\0" + identifier) -> resource data
     *
     * The inner Map is accessed only while holding a lock on the outer ConcurrentHashMap
     * instance (i.e. synchronized (dataByService) { ... }).
     */
    private ConcurrentHashMap<Integer, Map<String, ArbitraryResourceData>> dataByService = new ConcurrentHashMap<>();
    private ConcurrentHashMap<String, Integer> levelByName = new ConcurrentHashMap<>();

    private ArbitraryResourceCache() {}

    private static ArbitraryResourceCache SINGLETON = new ArbitraryResourceCache();

    public static ArbitraryResourceCache getInstance(){
        return SINGLETON;
    }

    public ConcurrentHashMap<String, Integer> getLevelByName() {
        return levelByName;
    }

    public ConcurrentHashMap<Integer, Map<String, ArbitraryResourceData>> getDataByService() {
        return this.dataByService;
    }

    /** Compose the inner-map key from name and identifier. */
    public static String resourceKey(String name, String identifier) {
        return name + "\0" + (identifier != null ? identifier : "default");
    }
}
