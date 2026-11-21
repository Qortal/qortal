package org.qortal.crosschain.monero;

import org.bouncycastle.crypto.digests.KeccakDigest;
import org.bouncycastle.math.ec.rfc8032.Ed25519;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * Offline Monero address decoding with explicit network, block-Base58, checksum
 * and canonical prime-order public-key checks. This does not establish ownership
 * or payment receipt. A wallet backend must independently validate a destination
 * before signing. No Bitcoin Base58Check or SHA3-256 encoding is used here.
 */
public final class MoneroAddress {

	public enum Type {
		STANDARD, INTEGRATED, SUBADDRESS
	}

	private static final String ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
	private static final BigInteger RADIX = BigInteger.valueOf(58);
	private static final int CHECKSUM_LENGTH = 4;

	private final String address;
	private final MoneroNetwork network;
	private final Type type;
	private final byte[] publicSpendKey;
	private final byte[] publicViewKey;
	private final byte[] paymentId;

	private MoneroAddress(String address, MoneroNetwork network, Type type, byte[] decoded) {
		this.address = address;
		this.network = network;
		this.type = type;
		this.publicSpendKey = Arrays.copyOfRange(decoded, 1, 33);
		this.publicViewKey = Arrays.copyOfRange(decoded, 33, 65);
		this.paymentId = type == Type.INTEGRATED ? Arrays.copyOfRange(decoded, 65, 73) : null;
	}

	public static MoneroAddress parse(String address, MoneroNetwork expectedNetwork) {
		if (expectedNetwork == null)
			throw new IllegalArgumentException("An explicit Monero network is required");
		if (address == null || (address.length() != 95 && address.length() != 106))
			throw new IllegalArgumentException("Invalid Monero address length");

		byte[] decoded = decodeAddress(address);
		Type type = expectedNetwork.addressType(decoded[0] & 0xff);
		if (type == null)
			throw new IllegalArgumentException("Unexpected Monero address network or type");
		if (decoded.length != (type == Type.INTEGRATED ? 77 : 69))
			throw new IllegalArgumentException("Monero address type does not match its payload length");

		int payloadLength = decoded.length - CHECKSUM_LENGTH;
		KeccakDigest digest = new KeccakDigest(256);
		digest.update(decoded, 0, payloadLength);
		byte[] hash = new byte[digest.getDigestSize()];
		digest.doFinal(hash, 0);
		if (!MessageDigest.isEqual(Arrays.copyOf(hash, CHECKSUM_LENGTH),
				Arrays.copyOfRange(decoded, payloadLength, decoded.length)))
			throw new IllegalArgumentException("Invalid Monero address checksum");

		// Generated wallet keys are non-identity points in the prime-order subgroup.
		// Deliberately reject even checksum-valid encodings of unusable/low-order keys.
		if (!Ed25519.validatePublicKeyFull(decoded, 1) || !Ed25519.validatePublicKeyFull(decoded, 33))
			throw new IllegalArgumentException("Invalid Monero address public key");

		return new MoneroAddress(address, expectedNetwork, type, decoded);
	}

	public static boolean isValid(String address, MoneroNetwork expectedNetwork) {
		try {
			parse(address, expectedNetwork);
			return true;
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	public String getAddress() {
		return this.address;
	}

	public MoneroNetwork getNetwork() {
		return this.network;
	}

	public Type getType() {
		return this.type;
	}

	public byte[] getPublicSpendKey() {
		return this.publicSpendKey.clone();
	}

	public byte[] getPublicViewKey() {
		return this.publicViewKey.clone();
	}

	/** The embedded eight-byte payment ID, or null for standard addresses/subaddresses. */
	public byte[] getPaymentId() {
		return this.paymentId == null ? null : this.paymentId.clone();
	}

	private static byte[] decodeAddress(String address) {
		// Both supported lengths end with seven characters encoding five bytes.
		int fullBlocks = address.length() / 11;
		byte[] decoded = new byte[fullBlocks * 8 + 5];
		for (int block = 0; block <= fullBlocks; ++block) {
			int encodedSize = block == fullBlocks ? 7 : 11;
			int decodedSize = block == fullBlocks ? 5 : 8;
			BigInteger value = BigInteger.ZERO;
			for (int index = 0; index < encodedSize; ++index) {
				int digit = ALPHABET.indexOf(address.charAt(block * 11 + index));
				if (digit < 0)
					throw new IllegalArgumentException("Invalid Monero Base58 character");
				value = value.multiply(RADIX).add(BigInteger.valueOf(digit));
			}
			if (value.bitLength() > decodedSize * 8)
				throw new IllegalArgumentException("Monero Base58 block overflow");
			for (int index = decodedSize - 1; index >= 0; --index) {
				decoded[block * 8 + index] = value.byteValue();
				value = value.shiftRight(8);
			}
		}
		return decoded;
	}
}
