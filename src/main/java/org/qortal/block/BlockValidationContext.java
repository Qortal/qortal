package org.qortal.block;

import org.qortal.data.transaction.TransactionData;
import org.qortal.utils.ByteArray;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Thread-local context used only during block transaction validation.
 * Allows transactions in the same block to be found by signature before they
 * are saved to the repository (e.g. so GROUP_APPROVAL can resolve a
 * same-block pending transaction).
 * <p>
 * Must be set at the start of {@link Block#areTransactionsValid()} and
 * cleared in a finally block so the ThreadLocal is never left set.
 */
public final class BlockValidationContext {

	private static final ThreadLocal<Map<ByteArray, TransactionData>> CURRENT_BLOCK_TRANSACTIONS = new ThreadLocal<>();

	private BlockValidationContext() {
	}

	/**
	 * Set the current block's transaction data for validation.
	 * Call this at the start of block transaction validation.
	 *
	 * @param transactions list of transaction data in the block (order preserved for iteration elsewhere; we index by signature)
	 */
	public static void set(List<TransactionData> transactions) {
		if (transactions == null || transactions.isEmpty()) {
			CURRENT_BLOCK_TRANSACTIONS.set(Collections.emptyMap());
			return;
		}
		Map<ByteArray, TransactionData> bySignature = new HashMap<>(transactions.size());
		for (TransactionData data : transactions) {
			if (data != null && data.getSignature() != null) {
				bySignature.put(ByteArray.wrap(data.getSignature()), data);
			}
		}
		CURRENT_BLOCK_TRANSACTIONS.set(Collections.unmodifiableMap(bySignature));
	}

	/**
	 * Look up transaction data by signature from the current block being validated.
	 *
	 * @param signature transaction signature
	 * @return the transaction data if present in the current block context, otherwise null
	 */
	public static TransactionData getBySignature(byte[] signature) {
		if (signature == null) {
			return null;
		}
		Map<ByteArray, TransactionData> map = CURRENT_BLOCK_TRANSACTIONS.get();
		if (map == null) {
			return null;
		}
		return map.get(ByteArray.wrap(signature));
	}

	/**
	 * Clear the current block context. Must be called in a finally block after validation.
	 */
	public static void clear() {
		CURRENT_BLOCK_TRANSACTIONS.remove();
	}
}
