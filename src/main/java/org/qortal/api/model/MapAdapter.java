package org.qortal.api.model; // Use the same package as ConnectedPeer

import javax.xml.bind.annotation.adapters.XmlAdapter;
import java.util.HashMap;
import java.util.Map;

// The types are <Map<String, Object> (the Java type), Map (the XML type)
public class MapAdapter extends XmlAdapter<Map, Map<String, Object>> {

    // Convert from Java Map to XML/JSON (marshalling)
    @Override
    public Map marshal(Map<String, Object> map) throws Exception {
        // We simply return the map itself. The serializer should now handle it correctly.
        return map;
    }

    // Convert from XML/JSON to Java Map (unmarshalling - often unused in API responses)
    @Override
    public Map<String, Object> unmarshal(Map jsonMap) throws Exception {
        if (jsonMap == null) {
            return new HashMap<>();
        }
        return (Map<String, Object>) jsonMap;
    }
}