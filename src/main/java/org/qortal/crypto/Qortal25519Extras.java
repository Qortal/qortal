package org.qortal.crypto;

import org.bouncycastle.crypto.Digest;
import org.bouncycastle.crypto.digests.SHA512Digest;
import org.bouncycastle.math.ec.rfc7748.X25519;
import org.bouncycastle.math.ec.rfc7748.X25519Field;
import org.bouncycastle.math.ec.rfc8032.Ed25519;
import org.bouncycastle.math.raw.Nat256;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collection;

/**
 * Additions to BouncyCastle providing:
 * <p></p>
 * <ul>
 *     <li>Ed25519 to X25519 key conversion</li>
 *     <li>Aggregate public keys</li>
 *     <li>Aggregate signatures</li>
 * </ul>
 */
public abstract class Qortal25519Extras extends BouncyCastleEd25519 {

	private static final SecureRandom SECURE_RANDOM = new SecureRandom();

	public static byte[] toX25519PublicKey(byte[] ed25519PublicKey) {
		int[] one = new int[X25519Field.SIZE];
		X25519Field.one(one);

		PointAffine pA = new PointAffine();
		if (!decodePointVar(ed25519PublicKey, 0, true, pA))
			return null;

		int[] y = pA.y;

		int[] oneMinusY = new int[X25519Field.SIZE];
		X25519Field.sub(one, y, oneMinusY);

		int[] onePlusY = new int[X25519Field.SIZE];
		X25519Field.add(one, y, onePlusY);

		int[] oneMinusYInverted = new int[X25519Field.SIZE];
		X25519Field.inv(oneMinusY, oneMinusYInverted);

		int[] u = new int[X25519Field.SIZE];
		X25519Field.mul(onePlusY, oneMinusYInverted, u);

		X25519Field.normalize(u);

		byte[] x25519PublicKey = new byte[X25519.SCALAR_SIZE];
		X25519Field.encode(u, x25519PublicKey, 0);

		return x25519PublicKey;
	}

	public static byte[] toX25519PrivateKey(byte[] ed25519PrivateKey) {
		Digest d = Ed25519.createPrehash();
		byte[] h = new byte[d.getDigestSize()];

		d.update(ed25519PrivateKey, 0, ed25519PrivateKey.length);
		d.doFinal(h, 0);

		byte[] s = new byte[X25519.SCALAR_SIZE];

		System.arraycopy(h, 0, s, 0, X25519.SCALAR_SIZE);
		s[0] &= 0xF8;
		s[X25519.SCALAR_SIZE - 1] &= 0x7F;
		s[X25519.SCALAR_SIZE - 1] |= 0x40;

		return s;
	}

	// Mostly for test support
	public static PointAccum newPointAccum() {
		return new PointAccum();
	}

	// Mostly for test support
	public static PointAffine newPointAffine() {
		return new PointAffine();
	}

	public static byte[] aggregatePublicKeys(Collection<byte[]> publicKeys) {
		PointAccum rAccum = null;

		for (byte[] publicKey : publicKeys) {
			PointAffine pA = new PointAffine();
			if (!decodePointVar(publicKey, 0, false, pA))
				// Failed to decode
				return null;

			if (rAccum == null) {
				rAccum = new PointAccum();
				pointCopy(pA, rAccum);
			} else {
				pointAdd(pointCopy(pA), rAccum);
			}
		}

		byte[] publicKey = new byte[SCALAR_BYTES];
		if (0 == encodePoint(rAccum, publicKey, 0))
			// Failed to encode
			return null;

		return publicKey;
	}

	public static byte[] aggregateSignatures(Collection<byte[]> signatures) {
		// Signatures are (R, s)
		// R is a point
		// s is a scalar
		PointAccum rAccum = null;
		int[] sAccum = new int[SCALAR_INTS];

		byte[] rEncoded = new byte[POINT_BYTES];
		int[] sPart = new int[SCALAR_INTS];
		for (byte[] signature : signatures) {
			System.arraycopy(signature,0, rEncoded, 0, rEncoded.length);

			PointAffine pA = new PointAffine();
			if (!decodePointVar(rEncoded, 0, false, pA))
				// Failed to decode
				return null;

			if (rAccum == null) {
				rAccum = new PointAccum();
				pointCopy(pA, rAccum);

				decode32(signature, rEncoded.length, sAccum, 0, SCALAR_INTS);
			} else {
				pointAdd(pointCopy(pA), rAccum);

				decode32(signature, rEncoded.length, sPart, 0, SCALAR_INTS);
				Nat256.addTo(sPart, sAccum);

				// "mod L" on sAccum
				if (Nat256.gte(sAccum, L))
					Nat256.subFrom(L, sAccum);
			}
		}

		byte[] signature = new byte[SIGNATURE_SIZE];
		if (0 == encodePoint(rAccum, signature, 0))
			// Failed to encode
			return null;

		for (int i = 0; i < sAccum.length; ++i) {
			encode32(sAccum[i], signature, POINT_BYTES + i * 4);
		}

		return signature;
	}

	public static byte[] signForAggregation(byte[] privateKey, byte[] message) {
		// Very similar to BouncyCastle's implementation except we use secure random nonce and different hash
		Digest d = new SHA512Digest();
		byte[] h = new byte[d.getDigestSize()];

		d.reset();
		d.update(privateKey, 0, privateKey.length);
		d.doFinal(h, 0);

		byte[] sH = new byte[SCALAR_BYTES];
		pruneScalar(h, 0, sH);

		byte[] publicKey = new byte[SCALAR_BYTES];
		scalarMultBaseEncoded(sH, publicKey, 0);

		byte[] rSeed = new byte[d.getDigestSize()];
		SECURE_RANDOM.nextBytes(rSeed);

		byte[] r = new byte[SCALAR_BYTES];
		pruneScalar(rSeed, 0, r);

		byte[] R = new byte[POINT_BYTES];
		scalarMultBaseEncoded(r, R, 0);

		d.reset();
		d.update(message, 0, message.length);
		d.doFinal(h, 0);
		byte[] k = reduceScalar(h);

		byte[] s = calculateS(r, k, sH);

		byte[] signature = new byte[SIGNATURE_SIZE];
		System.arraycopy(R, 0, signature, 0, POINT_BYTES);
		System.arraycopy(s, 0, signature, POINT_BYTES, SCALAR_BYTES);

		return signature;
	}

	public static boolean verifyAggregated(byte[] publicKey, byte[] signature, byte[] message) {
		byte[] R = Arrays.copyOfRange(signature, 0, POINT_BYTES);

		byte[] s = Arrays.copyOfRange(signature, POINT_BYTES, POINT_BYTES + SCALAR_BYTES);

		if (!checkPointVar(R))
			// R out of bounds
			return false;

		if (!checkScalarVar(s))
			// s out of bounds
			return false;

		byte[] S = new byte[POINT_BYTES];
		scalarMultBaseEncoded(s, S, 0);

		PointAffine pA = new PointAffine();
		if (!decodePointVar(publicKey, 0, true, pA))
			// Failed to decode
			return false;

		Digest d = new SHA512Digest();
		byte[] h = new byte[d.getDigestSize()];

		d.update(message, 0, message.length);
		d.doFinal(h, 0);

		byte[] k = reduceScalar(h);

		int[] nS = new int[SCALAR_INTS];
		decodeScalar(s, 0, nS);

		int[] nA = new int[SCALAR_INTS];
		decodeScalar(k, 0, nA);

		/*PointAccum*/
		PointAccum pR = new PointAccum();
		scalarMultStrausVar(nS, nA, pA, pR);

		byte[] check = new byte[POINT_BYTES];
		if (0 == encodePoint(pR, check, 0))
			// Failed to encode
			return false;

		return Arrays.equals(check, R);
	}

	/**
	 * Produce a standard Ed25519 signature over {@code message}.
	 * <p>
	 * Unlike {@link #signForAggregation(byte[], byte[])}, the challenge here is bound to the
	 * nonce point {@code R} and the public key {@code A} (i.e. {@code k = H(R || A || message)}),
	 * as required for a secure Schnorr/EdDSA signature. This replaces the forgeable
	 * aggregate-signature scheme on the online-accounts consensus path.
	 * <p>
	 * The private key is the same 32-byte seed used by {@link #signForAggregation(byte[], byte[])},
	 * so the resulting public key is identical.
	 */
	public static byte[] sign(byte[] privateKey, byte[] message) {
		byte[] signature = new byte[SIGNATURE_SIZE];
		Ed25519.sign(privateKey, 0, message, 0, message.length, signature, 0);
		return signature;
	}

	/**
	 * Verify a standard Ed25519 signature (challenge bound to {@code R} and {@code A}) over {@code message}.
	 * <p>
	 * This is the secure replacement for {@link #verifyAggregated(byte[], byte[], byte[])} on the
	 * online-accounts path: each online account's signature is verified individually against its own
	 * public key, so the universal forgery (which relied on a challenge independent of {@code R}) is closed.
	 */
	public static boolean verify(byte[] publicKey, byte[] signature, byte[] message) {
		return Ed25519.verify(signature, 0, publicKey, 0, message, 0, message.length);
	}
}
