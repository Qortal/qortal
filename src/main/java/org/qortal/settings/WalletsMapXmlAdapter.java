package org.qortal.settings;

import org.eclipse.persistence.oxm.annotations.XmlVariableNode;

import javax.xml.bind.annotation.XmlTransient;
import javax.xml.bind.annotation.XmlValue;
import javax.xml.bind.annotation.adapters.XmlAdapter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

/**
 * MOXy JSON adapter to support JSON objects mapping directly to Map entries, e.g.
 *
 * <pre>
 * "wallets": {
 *   "BTC": true,
 *   "LTC": false
 * }
 * </pre>
 */
public class WalletsMapXmlAdapter extends XmlAdapter<WalletsMapXmlAdapter.StringBooleanMap, Map<String, Boolean>> {

	public static class StringBooleanMap {
		@XmlVariableNode("key")
		List<MapEntry> entries = new ArrayList<>();
	}

	public static class MapEntry {
		@XmlTransient
		public String key;

		@XmlValue
		public Boolean value;
	}

	@Override
	public Map<String, Boolean> unmarshal(StringBooleanMap stringBooleanMap) {
		if (stringBooleanMap == null || stringBooleanMap.entries == null)
			return new HashMap<>();

		Map<String, Boolean> map = new HashMap<>(stringBooleanMap.entries.size());
		for (MapEntry entry : stringBooleanMap.entries) {
			if (entry == null || entry.key == null)
				continue;

			map.put(entry.key, entry.value);
		}

		return map;
	}

	@Override
	public StringBooleanMap marshal(Map<String, Boolean> map) {
		StringBooleanMap output = new StringBooleanMap();
		if (map == null)
			return output;

		for (Entry<String, Boolean> entry : map.entrySet()) {
			MapEntry mapEntry = new MapEntry();
			mapEntry.key = entry.getKey();
			mapEntry.value = entry.getValue();
			output.entries.add(mapEntry);
		}

		return output;
	}
}


