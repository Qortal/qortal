package org.qortal.network.helper;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlRootElement;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Represents the set of capabilities advertised by a peer on the network.
 * Provides methods to access, modify, and transform these capabilities
 * into various formats.
 *
 * @author Ice
 * @since v5.1.0
 */
@XmlRootElement(name = "capabilities")
@XmlAccessorType(XmlAccessType.FIELD)
public class PeerCapabilities {

    // Empty Set of Peer Capabilities
    private Map<String, Object> capabilities;

    /**
     * Constructs an empty set of peer capabilities.
     */
    public PeerCapabilities() {
        capabilities = new HashMap<>() ;
    }

    /**
     * Constructs a peer capabilities instance initialized with a provided map.
     *
     * @param caps the initial capabilities map
     */
    public PeerCapabilities(Map<String, Object> caps) {
        capabilities = caps;
    }

    /**
     * Retrieves a capability value by name.
     *
     * @param name the name of the capability
     * @return the value of the capability, or {@code null} if not found
     */
    public Object getCapability(String name) {
        if(capabilities.containsKey(name)) {
            return capabilities.get(name);
        }
        return null;
    }

    /**
     * Returns the full map of peer capabilities.
     *
     * @return a map of capability names to values
     */
    public Map<String, Object> getPeerCapabilities() {
        return capabilities;
    }

    /**
     * Returns the peer capabilities as a list of {@link PeerCapability} objects.
     *
     * @return a list of peer capabilities
     */
    public List<PeerCapability> getPeerCapabilitesList() {
        return capabilities.entrySet().stream()
                .map(entry -> {
                    return new PeerCapability(entry.getKey(), entry.getValue());
                })
                .collect(Collectors.toList());
    }

    /**
     * Returns the peer capabilities as a list of singleton maps.
     * Each map contains a single capability entry.
     *
     * @return a list of maps representing individual capabilities
     */
    public List<Map<String, Object>> getPeerCapabilitesListMap() {
        return capabilities.entrySet().stream()
                .map(entry -> {
                    Map<String, Object> singleEntryMap = new LinkedHashMap<>();
                    singleEntryMap.put(entry.getKey(), entry.getValue());
                    return singleEntryMap;
                })
                .collect(Collectors.toList());
    }

    /**
     * Sets the peer capabilities map.
     *
     * @param caps the new capabilities map
     */
    public void setPeerCapabilities(Map<String, Object> caps) {
        capabilities = caps;
    }

    /**
     * Returns the number of capabilities.
     *
     * @return the size of the capabilities map
     */
    public int size() {
        if (capabilities == null)
            return 0;
        return capabilities.size();
    }

    /**
     * Represents a single peer capability.
     * Internally uses a map to store the key-value pair.
     *
     * @author Ice
     * @since v5.1.0
     */
    public static class PeerCapability {
        private final String key;
        private Map<String, Object> cap;

        /**
         * Constructs a new peer capability.
         *
         * @param key the capability name
         * @param val the capability value
         */
        public PeerCapability (String key, Object val) {
            this.key = key;
            this.cap = new HashMap<>();
            this.cap.put(key, val);
        }

        /**
         * Checks if the capability matches the specified key.
         *
         * @param key the capability name to check
         * @return {@code true} if the capability is key, {@code false} otherwise
         *
         * @author Ice
         * @since v5.1.0
         */
        public Boolean isCapability (String key) {
            return this.cap.containsKey(key);
        }

        /**
         * Sets the value associated with this capability.
         *
         * @param val the new capability value
         */
        public void setCapability(Object val) {
            this.cap.put(this.key, val);
        }

        /**
         * Returns the map representing this capability.
         *
         * @return a map with a single capability entry
         */
        public Map<String, Object> getCapability() {
            return cap;
        }
    }
}
