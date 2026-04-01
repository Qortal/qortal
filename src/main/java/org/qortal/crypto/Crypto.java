package org.qortal.crypto;

import com.google.common.primitives.Bytes;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.X25519PublicKeyParameters;
import org.bouncycastle.math.ec.rfc8032.Ed25519;
import org.qortal.account.Account;
import org.qortal.utils.Base58;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

public abstract class Crypto {

	public static final int SIGNATURE_LENGTH = 64;
	public static final int SHARED_SECRET_LENGTH = 32;

	public static final byte ADDRESS_VERSION = 58; // Q
	public static final byte AT_ADDRESS_VERSION = 23; // A
	public static final byte NODE_ADDRESS_VERSION = 53; // N

	/**
	 * Returns 32-byte SHA-256 digest of message passed in input.
	 * 
	 * @param input
	 *            variable-length byte[] message
	 * @return byte[32] digest, or null if SHA-256 algorithm can't be accessed
	 */
	public static byte[] digest(byte[] input) {
		if (input == null)
			return null;

		try {
			// SHA2-256
			MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
			return sha256.digest(input);
		} catch (NoSuchAlgorithmException e) {
			throw new RuntimeException("SHA-256 message digest not available");
		}
	}

	/**
	 * Returns 32-byte SHA-256 digest of message passed in input.
	 * 
	 * @param input
	 *            variable-length byte[] message
	 * @return byte[32] digest, or null if SHA-256 algorithm can't be accessed
	 */
	public static byte[] digest(ByteBuffer input) {
		if (input == null)
			return null;

		try {
			// SHA2-256
			MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
			sha256.update(input);
			return sha256.digest();
		} catch (NoSuchAlgorithmException e) {
			throw new RuntimeException("SHA-256 message digest not available");
		}
	}

	public static byte[] digestFileStream(File file) throws IOException {
        // Buffer size: 256KB - reduces system calls significantly while remaining memory-friendly
        final int BUFFER_SIZE = 256 * 1024;
        try (BufferedInputStream fis = new BufferedInputStream(new FileInputStream(file), BUFFER_SIZE)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 algorithm not available", e);
        }
    }

	/**
	 * Returns 32-byte digest of two rounds of SHA-256 on message passed in input.
	 * 
	 * @param input
	 *            variable-length byte[] message
	 * @return byte[32] digest, or null if SHA-256 algorithm can't be accessed
	 */
	public static byte[] doubleDigest(byte[] input) {
		return digest(digest(input));
	}

	/**
	 * Returns 32-byte SHA-256 digest of file passed in input.
	 *
	 * @param file
	 *            file in which to perform digest
	 * @return byte[32] digest, or null if SHA-256 algorithm can't be accessed
	 *
	 * @throws IOException if the file cannot be read
	 */
	public static byte[] digest(File file) throws IOException {
		return Crypto.digest(file, 8192);
	}

	/**
	 * Returns 32-byte SHA-256 digest of file passed in input, in hex format
	 *
	 * @param file
	 *            file in which to perform digest
	 * @return String digest as a hexadecimal string, or null if SHA-256 algorithm can't be accessed
	 *
	 * @throws IOException if the file cannot be read
	 */
	public static String digestHexString(File file, int bufferSize) throws IOException {
		byte[] digest = Crypto.digest(file, bufferSize);

		// Convert to hex
		StringBuilder stringBuilder = new StringBuilder();
		for (byte b : digest) {
			stringBuilder.append(String.format("%02x", b));
		}
		return stringBuilder.toString();
	}

	/**
	 * Returns 32-byte SHA-256 digest of file passed in input.
	 *
	 * @param file
	 *            file in which to perform digest
	 * @param bufferSize
	 * 			  the number of bytes to load into memory
	 * @return byte[32] digest, or null if SHA-256 algorithm can't be accessed
	 *
	 * @throws IOException if the file cannot be read
	 */
	public static byte[] digest(File file, int bufferSize) throws IOException {
		try {
			MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
			FileInputStream fileInputStream = new FileInputStream(file);
			byte[] bytes = new byte[bufferSize];
			int count;

			while ((count = fileInputStream.read(bytes)) != -1) {
				sha256.update(bytes, 0, count);
			}
			fileInputStream.close();

			return sha256.digest();

		} catch (NoSuchAlgorithmException e) {
			throw new RuntimeException("SHA-256 message digest not available");
		}
	}

	/**
	 * Returns 64-byte duplicated digest of message passed in input.
	 * <p>
	 * Effectively <tt>Bytes.concat(digest(input), digest(input)).
	 *
	 * @param input
	 */
	public static byte[] dupDigest(byte[] input) {
		final byte[] digest = digest(input);
		return Bytes.concat(digest, digest);
	}

	/** Returns RMD160(SHA256(data)) */
	public static byte[] hash160(byte[] data) {
		byte[] interim = digest(data);

		try {
			MessageDigest md160 = MessageDigest.getInstance("RIPEMD160");
			return md160.digest(interim);
		} catch (NoSuchAlgorithmException e) {
			throw new RuntimeException("RIPEMD160 message digest not available");
		}
	}

	private static String toAddress(byte addressVersion, byte[] input) {
		// SHA2-256 input to create new data and of known size
		byte[] inputHash = digest(input);

		// Use RIPEMD160 to create shorter address
		// Use legit MD160
		try {
			MessageDigest md160 = MessageDigest.getInstance("RIPEMD160");
			inputHash = md160.digest(inputHash);
		} catch (NoSuchAlgorithmException e) {
			throw new RuntimeException("RIPEMD160 message digest not available");
		}

		// Create address data using above hash and addressVersion (prepended)
		byte[] addressBytes = new byte[inputHash.length + 1];
		System.arraycopy(inputHash, 0, addressBytes, 1, inputHash.length);
		addressBytes[0] = addressVersion;

		// Generate checksum
		byte[] checksum = doubleDigest(addressBytes);

		// Append checksum
		byte[] addressWithChecksum = new byte[addressBytes.length + 4];
		System.arraycopy(addressBytes, 0, addressWithChecksum, 0, addressBytes.length);
		System.arraycopy(checksum, 0, addressWithChecksum, addressBytes.length, 4);

		// Return Base58-encoded
		return Base58.encode(addressWithChecksum);
	}

	public static String toAddress(byte[] publicKey) {
		return toAddress(ADDRESS_VERSION, publicKey);
	}

	public static String toATAddress(byte[] signature) {
		return toAddress(AT_ADDRESS_VERSION, signature);
	}

	public static String toNodeAddress(byte[] publicKey) {
		return toAddress(NODE_ADDRESS_VERSION, publicKey);
	}

	public static boolean isValidAddress(String address) {
		return isValidTypedAddress(address, ADDRESS_VERSION, AT_ADDRESS_VERSION);
	}

	public static boolean isValidAddress(byte[] addressBytes) {
		return areValidTypedAddressBytes(addressBytes, ADDRESS_VERSION, AT_ADDRESS_VERSION);
	}

	public static boolean isValidAtAddress(String address) {
		return isValidTypedAddress(address, AT_ADDRESS_VERSION);
	}

	private static boolean isValidTypedAddress(String address, byte...addressVersions) {
		byte[] addressBytes;

		try {
			// Attempt Base58 decoding
			addressBytes = Base58.decode(address);
		} catch (NumberFormatException e) {
			return false;
		}

		return areValidTypedAddressBytes(addressBytes, addressVersions);
	}

	private static boolean areValidTypedAddressBytes(byte[] addressBytes, byte...addressVersions) {
		if (addressVersions == null || addressVersions.length == 0)
			return false;

		// Check address length
		if (addressBytes == null || addressBytes.length != Account.ADDRESS_LENGTH)
			return false;

		// Check by address type
		for (byte addressVersion : addressVersions)
			if (addressBytes[0] == addressVersion) {
				byte[] addressWithoutChecksum = Arrays.copyOf(addressBytes, addressBytes.length - 4);
				byte[] passedChecksum = Arrays.copyOfRange(addressBytes, addressBytes.length - 4, addressBytes.length);

				byte[] generatedChecksum = Arrays.copyOf(doubleDigest(addressWithoutChecksum), 4);
				return Arrays.equals(passedChecksum, generatedChecksum);
			}

		return false;
	}

	public static byte[] toPublicKey(byte[] privateKey) {
		return new Ed25519PrivateKeyParameters(privateKey, 0).generatePublicKey().getEncoded();
	}

	public static boolean verify(byte[] publicKey, byte[] signature, byte[] message) {
		try {
			return Ed25519.verify(signature, 0, publicKey, 0, message, 0, message.length);
		} catch (Exception e) {
			return false;
		}
	}

	public static byte[] sign(Ed25519PrivateKeyParameters edPrivateKeyParams, byte[] message) {
		byte[] signature = new byte[SIGNATURE_LENGTH];

		edPrivateKeyParams.sign(Ed25519.Algorithm.Ed25519,null, message, 0, message.length, signature, 0);

		return signature;
	}

	public static byte[] sign(byte[] privateKey, byte[] message) {
		byte[] signature = new byte[SIGNATURE_LENGTH];

		new Ed25519PrivateKeyParameters(privateKey, 0).sign(Ed25519.Algorithm.Ed25519,null, message, 0, message.length, signature, 0);

		return signature;
	}

	public static byte[] getSharedSecret(byte[] privateKey, byte[] publicKey) {
		byte[] x25519PrivateKey = Qortal25519Extras.toX25519PrivateKey(privateKey);
		X25519PrivateKeyParameters xPrivateKeyParams = new X25519PrivateKeyParameters(x25519PrivateKey, 0);

		byte[] x25519PublicKey = Qortal25519Extras.toX25519PublicKey(publicKey);
		X25519PublicKeyParameters xPublicKeyParams = new X25519PublicKeyParameters(x25519PublicKey, 0);

		byte[] sharedSecret = new byte[SHARED_SECRET_LENGTH];
		xPrivateKeyParams.generateSecret(xPublicKeyParams, sharedSecret, 0);

		return sharedSecret;
	}
}
