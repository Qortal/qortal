package org.qortal.network.message;

import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import org.qortal.data.network.OnlineAccountData;
import org.qortal.transform.Transformer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * For sending online accounts info to remote peer.
 *
 * Same format as V2, but with added support for a mempow nonce.
 */
public class OnlineAccountsV3Message extends Message {

	public static final long MIN_PEER_VERSION = 0x300060000L; // 3.6.0
	private static final long ENTRY_SIZE
			= Transformer.SIGNATURE_LENGTH +
				Transformer.PUBLIC_KEY_LENGTH +
				Transformer.INT_LENGTH; // nonce

	private List<OnlineAccountData> onlineAccounts;

	public OnlineAccountsV3Message(List<OnlineAccountData> onlineAccounts) {
		super(MessageType.ONLINE_ACCOUNTS_V3);

		// Shortcut in case we have no online accounts
		if (onlineAccounts.isEmpty()) {
			this.dataBytes = Ints.toByteArray(0);
			this.checksumBytes = Message.generateChecksum(this.dataBytes);
			return;
		}

		// How many of each timestamp
		Map<Long, Integer> countByTimestamp = new HashMap<>();

		for (OnlineAccountData onlineAccountData : onlineAccounts) {
			Long timestamp = onlineAccountData.getTimestamp();
			countByTimestamp.compute(timestamp, (k, v) -> v == null ? 1 : ++v);
		}

		// We should know exactly how many bytes to allocate now
		int byteSize = countByTimestamp.size() * (Transformer.INT_LENGTH + Transformer.TIMESTAMP_LENGTH)
				+ onlineAccounts.size() * (Transformer.SIGNATURE_LENGTH + Transformer.PUBLIC_KEY_LENGTH);

		ByteArrayOutputStream bytes = new ByteArrayOutputStream(byteSize);

		try {
			for (long timestamp : countByTimestamp.keySet()) {
				bytes.write(Ints.toByteArray(countByTimestamp.get(timestamp)));

				bytes.write(Longs.toByteArray(timestamp));

				for (OnlineAccountData onlineAccountData : onlineAccounts) {
					if (onlineAccountData.getTimestamp() == timestamp) {
						bytes.write(onlineAccountData.getSignature());
						bytes.write(onlineAccountData.getPublicKey());

						// Nonce is optional; use -1 as placeholder if missing
						int nonce = onlineAccountData.getNonce() != null ? onlineAccountData.getNonce() : -1;
						bytes.write(Ints.toByteArray(nonce));
					}
				}
			}
		} catch (IOException e) {
			throw new AssertionError("IOException shouldn't occur with ByteArrayOutputStream");
		}

		this.dataBytes = bytes.toByteArray();
		this.checksumBytes = Message.generateChecksum(this.dataBytes);
	}

	private OnlineAccountsV3Message(int id, List<OnlineAccountData> onlineAccounts) {
		super(id, MessageType.ONLINE_ACCOUNTS_V3);

		this.onlineAccounts = onlineAccounts;
	}

	public List<OnlineAccountData> getOnlineAccounts() {
		return this.onlineAccounts;
	}

	public static Message fromByteBuffer(int id, ByteBuffer bytes) throws MessageException {
		int accountCount = GroupedMessageUtils.readInitialGroupCount(bytes, ENTRY_SIZE, "invalid online accounts count");
		if (accountCount == 0)
			return new OnlineAccountsV3Message(id, List.of());

		List<OnlineAccountData> onlineAccounts = new ArrayList<>(accountCount);

		while (true) {
			long timestamp = bytes.getLong();

			for (int i = 0; i < accountCount; ++i) {
				byte[] signature = new byte[Transformer.SIGNATURE_LENGTH];
				bytes.get(signature);

				byte[] publicKey = new byte[Transformer.PUBLIC_KEY_LENGTH];
				bytes.get(publicKey);

				// Nonce is optional - will be -1 if missing
				// ... but we should skip/ignore an online account if it has no nonce
				Integer nonce = bytes.getInt();
				if (nonce < 0) {
					continue;
				}

				onlineAccounts.add(new OnlineAccountData(timestamp, signature, publicKey, nonce));
			}

			if (!bytes.hasRemaining())
				break;

			accountCount = GroupedMessageUtils.readNextGroupCount(bytes, ENTRY_SIZE, "invalid online accounts count");
		}

		return new OnlineAccountsV3Message(id, onlineAccounts);
	}

}
