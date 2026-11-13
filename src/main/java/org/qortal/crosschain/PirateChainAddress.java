package org.qortal.crosschain;

import org.bitcoinj.core.AddressFormatException;
import org.bitcoinj.core.Bech32;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Mainnet shielded addresses and the trade bot's persisted receiving address. */
public final class PirateChainAddress {

	// Both shielded pools encode a 43-byte receiver as 69 five-bit values.
	private static final int RECEIVER_DATA_LENGTH = 69;

	private PirateChainAddress() {
	}

	public static boolean isValid(String address) {
		if (address == null || (address.length() != 78 && address.length() != 82))
			return false;

		try {
			Bech32.Bech32Data decoded = Bech32.decode(address);
			return ("zs".equals(decoded.hrp) || "pirate".equals(decoded.hrp))
					&& decoded.data.length == RECEIVER_DATA_LENGTH
					// The final five-bit value has one unused padding bit.
					&& (decoded.data[RECEIVER_DATA_LENGTH - 1] & 1) == 0;
		} catch (AddressFormatException e) {
			return false;
		}
	}

	/** Keep Sapling's existing format; store Ironwood's full address, including its pool. */
	public static byte[] toReceivingAccountInfo(String address) {
		if (!isValid(address))
			throw new IllegalArgumentException("Invalid Pirate Chain receiving address");

		Bech32.Bech32Data decoded = Bech32.decode(address);
		if ("zs".equals(decoded.hrp))
			return decoded.data;

		return address.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
	}

	/** Read old Sapling trades/backups as well as addresses stored with their prefix. */
	public static String fromReceivingAccountInfo(byte[] receivingAccountInfo) {
		if (receivingAccountInfo == null)
			throw new IllegalArgumentException("Missing Pirate Chain receiving address");

		String address;
		if (receivingAccountInfo.length == RECEIVER_DATA_LENGTH) {
			for (byte value : receivingAccountInfo)
				if (value < 0 || value > 31)
					throw new IllegalArgumentException("Invalid legacy Pirate Chain receiving address");

			address = Bech32.encode("zs", receivingAccountInfo);
		} else {
			address = new String(receivingAccountInfo, StandardCharsets.US_ASCII);
		}

		if (!isValid(address))
			throw new IllegalArgumentException("Invalid stored Pirate Chain receiving address");

		return address.toLowerCase(Locale.ROOT);
	}
}
