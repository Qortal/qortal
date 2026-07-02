package org.qortal.test;

import com.google.common.hash.HashCode;
import com.google.common.primitives.Bytes;
import com.google.common.primitives.Longs;
import org.bouncycastle.crypto.digests.SHA512Digest;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider;
import org.junit.Test;
import org.qortal.crypto.Qortal25519Extras;
import org.qortal.data.network.OnlineAccountData;
import org.qortal.test.common.AccountUtils;
import org.qortal.transform.Transformer;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.security.Security;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

public class SchnorrTests extends Qortal25519Extras {

    static {
        // This must go before any calls to LogManager/Logger
        System.setProperty("java.util.logging.manager", "org.apache.logging.log4j.jul.LogManager");

        Security.insertProviderAt(new BouncyCastleProvider(), 0);
        Security.insertProviderAt(new BouncyCastleJsseProvider(), 1);
    }

    @Test
    public void testConversion() {
        // Scalar form
        byte[] scalarA = HashCode.fromString("0100000000000000000000000000000000000000000000000000000000000000".toLowerCase()).asBytes();
        System.out.printf("a: %s%n", HashCode.fromBytes(scalarA));

        byte[] pointA = HashCode.fromString("5866666666666666666666666666666666666666666666666666666666666666".toLowerCase()).asBytes();

        BigInteger expectedY = new BigInteger("46316835694926478169428394003475163141307993866256225615783033603165251855960");

        PointAccum pointAccum = Qortal25519Extras.newPointAccum();
        scalarMultBase(scalarA, pointAccum);

        byte[] encoded = new byte[POINT_BYTES];
        if (0 == encodePoint(pointAccum, encoded, 0))
            fail("Point encoding failed");

        System.out.printf("aG: %s%n", HashCode.fromBytes(encoded));
        assertArrayEquals(pointA, encoded);

        byte[] yBytes = new byte[POINT_BYTES];
        System.arraycopy(encoded,0, yBytes, 0, encoded.length);
        Bytes.reverse(yBytes);

        System.out.printf("yBytes: %s%n", HashCode.fromBytes(yBytes));
        BigInteger yBI = new BigInteger(yBytes);

        System.out.printf("aG y: %s%n", yBI);
        assertEquals(expectedY, yBI);
    }

    @Test
    public void testAddition() {
        /*
         * 1G:  b'5866666666666666666666666666666666666666666666666666666666666666'
         * 2G:  b'c9a3f86aae465f0e56513864510f3997561fa2c9e85ea21dc2292309f3cd6022'
         * 3G:  b'd4b4f5784868c3020403246717ec169ff79e26608ea126a1ab69ee77d1b16712'
         */

        // Scalar form
        byte[] s1 = HashCode.fromString("0100000000000000000000000000000000000000000000000000000000000000".toLowerCase()).asBytes();
        byte[] s2 = HashCode.fromString("0200000000000000000000000000000000000000000000000000000000000000".toLowerCase()).asBytes();

        // Point form
        byte[] g1 = HashCode.fromString("5866666666666666666666666666666666666666666666666666666666666666".toLowerCase()).asBytes();
        byte[] g2 = HashCode.fromString("c9a3f86aae465f0e56513864510f3997561fa2c9e85ea21dc2292309f3cd6022".toLowerCase()).asBytes();
        byte[] g3 = HashCode.fromString("d4b4f5784868c3020403246717ec169ff79e26608ea126a1ab69ee77d1b16712".toLowerCase()).asBytes();

        PointAccum p1 = Qortal25519Extras.newPointAccum();
        scalarMultBase(s1, p1);

        PointAccum p2 = Qortal25519Extras.newPointAccum();
        scalarMultBase(s2, p2);

        pointAdd(pointCopy(p1), p2);

        byte[] encoded = new byte[POINT_BYTES];
        if (0 == encodePoint(p2, encoded, 0))
            fail("Point encoding failed");

        System.out.printf("sum: %s%n", HashCode.fromBytes(encoded));
        assertArrayEquals(g3, encoded);
    }

    @Test
    public void testSimpleSign() {
        byte[] privateKey = HashCode.fromString("0100000000000000000000000000000000000000000000000000000000000000".toLowerCase()).asBytes();
        byte[] message = HashCode.fromString("01234567".toLowerCase()).asBytes();

        byte[] signature = signForAggregation(privateKey, message);
        System.out.printf("signature: %s%n", HashCode.fromBytes(signature));
    }

    @Test
    public void testSimpleVerify() {
        byte[] privateKey = HashCode.fromString("0100000000000000000000000000000000000000000000000000000000000000".toLowerCase()).asBytes();
        byte[] message = HashCode.fromString("01234567".toLowerCase()).asBytes();
        byte[] signature = HashCode.fromString("13e58e88f3df9e06637d2d5bbb814c028e3ba135494530b9d3b120bdb31168d62c70a37ae9cfba816fe6038ee1ce2fb521b95c4a91c7ff0bb1dd2e67733f2b0d".toLowerCase()).asBytes();

        byte[] publicKey = new byte[Transformer.PUBLIC_KEY_LENGTH];
        Qortal25519Extras.generatePublicKey(privateKey, 0, publicKey, 0);

        assertTrue(verifyAggregated(publicKey, signature, message));
    }

    @Test
    public void testSimpleSignAndVerify() {
        byte[] privateKey = HashCode.fromString("0100000000000000000000000000000000000000000000000000000000000000".toLowerCase()).asBytes();
        byte[] message = HashCode.fromString("01234567".toLowerCase()).asBytes();

        byte[] signature = signForAggregation(privateKey, message);

        byte[] publicKey = new byte[Transformer.PUBLIC_KEY_LENGTH];
        Qortal25519Extras.generatePublicKey(privateKey, 0, publicKey, 0);

        assertTrue(verifyAggregated(publicKey, signature, message));
    }

    @Test
    public void testSimpleAggregate() {
        List<OnlineAccountData> onlineAccounts = AccountUtils.generateOnlineAccounts(1);

        byte[] aggregatePublicKey = aggregatePublicKeys(onlineAccounts.stream().map(OnlineAccountData::getPublicKey).collect(Collectors.toUnmodifiableList()));
        System.out.printf("Aggregate public key: %s%n", HashCode.fromBytes(aggregatePublicKey));

        byte[] aggregateSignature = aggregateSignatures(onlineAccounts.stream().map(OnlineAccountData::getSignature).collect(Collectors.toUnmodifiableList()));
        System.out.printf("Aggregate signature: %s%n", HashCode.fromBytes(aggregateSignature));

        OnlineAccountData onlineAccount = onlineAccounts.get(0);

        assertArrayEquals(String.format("expected: %s, actual: %s", HashCode.fromBytes(onlineAccount.getPublicKey()), HashCode.fromBytes(aggregatePublicKey)), onlineAccount.getPublicKey(), aggregatePublicKey);
        assertArrayEquals(String.format("expected: %s, actual: %s", HashCode.fromBytes(onlineAccount.getSignature()), HashCode.fromBytes(aggregateSignature)), onlineAccount.getSignature(), aggregateSignature);

        // This is the crucial test:
        long timestamp = onlineAccount.getTimestamp();
        byte[] timestampBytes = Longs.toByteArray(timestamp);
        assertTrue(verifyAggregated(aggregatePublicKey, aggregateSignature, timestampBytes));
    }

    @Test
    public void testMultipleAggregate() {
        List<OnlineAccountData> onlineAccounts = AccountUtils.generateOnlineAccounts(5000);

        byte[] aggregatePublicKey = aggregatePublicKeys(onlineAccounts.stream().map(OnlineAccountData::getPublicKey).collect(Collectors.toUnmodifiableList()));
        System.out.printf("Aggregate public key: %s%n", HashCode.fromBytes(aggregatePublicKey));

        byte[] aggregateSignature = aggregateSignatures(onlineAccounts.stream().map(OnlineAccountData::getSignature).collect(Collectors.toUnmodifiableList()));
        System.out.printf("Aggregate signature: %s%n", HashCode.fromBytes(aggregateSignature));

        OnlineAccountData onlineAccount = onlineAccounts.get(0);

        // This is the crucial test:
        long timestamp = onlineAccount.getTimestamp();
        byte[] timestampBytes = Longs.toByteArray(timestamp);
        assertTrue(verifyAggregated(aggregatePublicKey, aggregateSignature, timestampBytes));
    }

    // --- C-01 fix: secure per-account Ed25519 (challenge bound to R and A) ---

    @Test
    public void testSecureSignAndVerify() {
        byte[] privateKey = HashCode.fromString("0100000000000000000000000000000000000000000000000000000000000000".toLowerCase()).asBytes();
        byte[] message = HashCode.fromString("01234567".toLowerCase()).asBytes();

        byte[] publicKey = new byte[Transformer.PUBLIC_KEY_LENGTH];
        Qortal25519Extras.generatePublicKey(privateKey, 0, publicKey, 0);

        byte[] signature = Qortal25519Extras.sign(privateKey, message);

        // Honest signature verifies
        assertTrue(Qortal25519Extras.verify(publicKey, signature, message));

        // Tampered message is rejected
        byte[] otherMessage = message.clone();
        otherMessage[0] ^= 0x01;
        assertFalse(Qortal25519Extras.verify(publicKey, signature, otherMessage));
    }

    /**
     * Reproduces the C-01 universal forgery (R = s*B - k*A, k = H(message) only) and asserts that:
     * - the legacy {@code verifyAggregated} accepts it (demonstrating the original vulnerability), and
     * - the secure {@code verify} (challenge bound to R and A) rejects it.
     */
    @Test
    public void testForgeryRejectedBySecureVerify() {
        byte[] message = HashCode.fromString("0000018bcfe25ac0".toLowerCase()).asBytes();

        // A victim public key the attacker does NOT own
        byte[] victimPrivateKey = new byte[Transformer.PUBLIC_KEY_LENGTH];
        new SecureRandom().nextBytes(victimPrivateKey);
        byte[] victimPublicKey = new byte[Transformer.PUBLIC_KEY_LENGTH];
        Qortal25519Extras.generatePublicKey(victimPrivateKey, 0, victimPublicKey, 0);

        byte[] forged = forge(victimPublicKey, message);

        // Legacy scheme is forgeable...
        assertTrue("legacy verifyAggregated should accept the forgery", verifyAggregated(victimPublicKey, forged, message));

        // ...but the secure scheme rejects it (no private key was used).
        assertFalse("secure verify must reject the forgery", Qortal25519Extras.verify(victimPublicKey, forged, message));
    }

    /** k = reduceScalar(SHA512(message)) -- exactly as the legacy verifyAggregated computes it. */
    private static byte[] challenge(byte[] message) {
        SHA512Digest d = new SHA512Digest();
        byte[] h = new byte[d.getDigestSize()];
        d.update(message, 0, message.length);
        d.doFinal(h, 0);
        return reduceScalar(h);
    }

    /** Pick a valid scalar s (< L) without any secret. */
    private static byte[] anyScalar() {
        byte[] seed = new byte[64];
        new SecureRandom().nextBytes(seed);
        return reduceScalar(seed);
    }

    /** Forge a signature for public key A and message with NO private key: pR = s*B - k*A. */
    private static byte[] forge(byte[] publicKey, byte[] message) {
        byte[] k = challenge(message);
        byte[] s = anyScalar();

        PointAffine pA = Qortal25519Extras.newPointAffine();
        if (!decodePointVar(publicKey, 0, true, pA)) // negate=true -> pA = -A
            throw new IllegalStateException("could not decode public key");

        int[] nS = new int[SCALAR_INTS];
        decodeScalar(s, 0, nS);
        int[] nA = new int[SCALAR_INTS];
        decodeScalar(k, 0, nA);

        PointAccum pR = Qortal25519Extras.newPointAccum();
        scalarMultStrausVar(nS, nA, pA, pR); // pR = s*B + k*(-A) = s*B - k*A

        byte[] R = new byte[POINT_BYTES];
        if (0 == encodePoint(pR, R, 0))
            throw new IllegalStateException("could not encode R");

        byte[] sig = new byte[SIGNATURE_SIZE];
        System.arraycopy(R, 0, sig, 0, POINT_BYTES);
        System.arraycopy(s, 0, sig, POINT_BYTES, SCALAR_BYTES);
        return sig;
    }
}
