package org.qortal.network.message;

import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import org.qortal.data.network.TradePresenceData;
import org.qortal.transform.Transformer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * For requesting trade presences from remote peer, given our list of known trade presences.
 *
 * Groups of: number of entries, timestamp, then AT trade pubkey for each entry.
 */
public class GetTradePresencesMessage extends Message {
	private List<TradePresenceData> tradePresences;

	public GetTradePresencesMessage(List<TradePresenceData> tradePresences) {
		super(MessageType.GET_TRADE_PRESENCES);

		// Shortcut in case we have no trade presences
		if (tradePresences.isEmpty()) {
			this.dataBytes = Ints.toByteArray(0);
			this.checksumBytes = Message.generateChecksum(this.dataBytes);
			return;
		}

		// How many of each timestamp
		Map<Long, Integer> countByTimestamp = new HashMap<>();

		for (TradePresenceData tradePresenceData : tradePresences) {
			Long timestamp = tradePresenceData.getTimestamp();
			countByTimestamp.compute(timestamp, (k, v) -> v == null ? 1 : ++v);
		}

		// We should know exactly how many bytes to allocate now
		int byteSize = countByTimestamp.size() * (Transformer.INT_LENGTH + Transformer.TIMESTAMP_LENGTH)
				+ tradePresences.size() * Transformer.PUBLIC_KEY_LENGTH;

		ByteArrayOutputStream bytes = new ByteArrayOutputStream(byteSize);

		try {
			for (long timestamp : countByTimestamp.keySet()) {
				bytes.write(Ints.toByteArray(countByTimestamp.get(timestamp)));

				bytes.write(Longs.toByteArray(timestamp));

				for (TradePresenceData tradePresenceData : tradePresences) {
					if (tradePresenceData.getTimestamp() == timestamp)
						bytes.write(tradePresenceData.getPublicKey());
				}
			}
		} catch (IOException e) {
			throw new AssertionError("IOException shouldn't occur with ByteArrayOutputStream");
		}

		this.dataBytes = bytes.toByteArray();
		this.checksumBytes = Message.generateChecksum(this.dataBytes);
	}

	private GetTradePresencesMessage(int id, List<TradePresenceData> tradePresences) {
		super(id, MessageType.GET_TRADE_PRESENCES);

		this.tradePresences = tradePresences;
	}

	public List<TradePresenceData> getTradePresences() {
		return this.tradePresences;
	}

	public static Message fromByteBuffer(int id, ByteBuffer bytes) throws MessageException {
		int groupedEntriesCount = GroupedMessageUtils.readInitialGroupCount(bytes, Transformer.PUBLIC_KEY_LENGTH, "invalid grouped entries count");
		if (groupedEntriesCount == 0)
			return new GetTradePresencesMessage(id, List.of());

		List<TradePresenceData> tradePresences = new ArrayList<>(groupedEntriesCount);

		while (true) {
			long timestamp = bytes.getLong();

			for (int i = 0; i < groupedEntriesCount; ++i) {
				byte[] publicKey = new byte[Transformer.PUBLIC_KEY_LENGTH];
				bytes.get(publicKey);

				tradePresences.add(new TradePresenceData(timestamp, publicKey));
			}

			if (!bytes.hasRemaining())
				break;

			groupedEntriesCount = GroupedMessageUtils.readNextGroupCount(bytes, Transformer.PUBLIC_KEY_LENGTH, "invalid grouped entries count");
		}

		return new GetTradePresencesMessage(id, tradePresences);
	}

}
