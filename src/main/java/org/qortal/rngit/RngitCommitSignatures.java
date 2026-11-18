package org.qortal.rngit;

import io.reticulum.identity.Identity;
import io.reticulum.utils.MsgPackUtils;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.apache.commons.codec.binary.Hex.encodeHexString;

/**
 * Verifies rngit commit signatures for the commit page ({@code pages.py}
 * {@code get_commit_signature}): an RSG signature by a Reticulum identity,
 * wrapped as an SSH signature in the commit's {@code gpgsig} header
 * ({@code commitsigs.py}), checked as {@code rnid.validate_rsg} checks it.
 * A valid signature counts as the author's when the commit's author email is
 * the signer's identity hash.
 */
final class RngitCommitSignatures {

    private static final int SIGLENGTH = 64;
    private static final byte[] SSHSIG_MAGIC = "SSHSIG".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] AUTHOR_TARGET = "author ".getBytes(StandardCharsets.US_ASCII);

    private RngitCommitSignatures() {
    }

    static final class Status {
        final boolean signed;
        final boolean valid;
        final String signerHash;
        final boolean authorMatch;
        final String message;

        Status(boolean signed, boolean valid, String signerHash, boolean authorMatch, String message) {
            this.signed = signed;
            this.valid = valid;
            this.signerHash = signerHash;
            this.authorMatch = authorMatch;
            this.message = message;
        }
    }

    /** The signature status of a raw commit object, or "Could not read commit object" for null. */
    static Status check(String commitContent) {
        if (commitContent == null) return new Status(false, false, null, false, "Could not read commit object");

        List<String> sigLines = new ArrayList<>();
        List<String> signedLines = new ArrayList<>();
        boolean inSignature = false;
        for (String line : commitContent.split("\n", -1)) {
            if (line.startsWith("gpgsig ") || line.startsWith("gpgsig-sha256 ")) {
                inSignature = true;
                sigLines.add(line.substring(line.indexOf(' ') + 1));
            } else if (inSignature) {
                if (line.startsWith(" ")) {
                    sigLines.add(line.substring(1));
                } else {
                    inSignature = false;
                    signedLines.add(line);
                }
            } else {
                signedLines.add(line);
            }
        }
        if (sigLines.isEmpty()) return new Status(false, false, null, false, "Not signed");

        byte[] signedContent = String.join("\n", signedLines).getBytes(StandardCharsets.UTF_8);
        try {
            byte[] sigData = unarmor(String.join("\n", sigLines));
            byte[] rsg;
            try {
                rsg = signatureData(sigData);
            } catch (IllegalArgumentException e) {
                return new Status(true, false, null, false, "Malformed SSH wrapping for RSG data");
            }

            Identity signer = validateRsg(rsg, signedContent);
            if (signer == null) return new Status(true, false, null, false, "Invalid signature");

            String signerHash = encodeHexString(signer.getHash());
            String author = commitAuthor(signedContent);
            if (author.isEmpty()) return new Status(true, true, signerHash, false, "Could not verify author");
            if (author.equals(signerHash)) return new Status(true, true, signerHash, true, "Valid, signed by <" + signerHash + ">");
            return new Status(true, true, signerHash, false, "Invalid signer <" + signerHash + ">, author is <" + author + ">");
        } catch (Exception e) {
            return new Status(true, false, null, false, "Signature validation error");
        }
    }

    /** {@code unarmor_ssh_signature}. */
    static byte[] unarmor(String armored) {
        StringBuilder b64 = new StringBuilder();
        boolean inSig = false;
        for (String line : armored.strip().split("\n")) {
            if (line.contains("BEGIN SSH SIGNATURE")) {
                inSig = true;
                continue;
            }
            if (line.contains("END SSH SIGNATURE")) break;
            if (inSig) b64.append(line.strip());
        }
        if (b64.length() == 0) throw new IllegalStateException("No signature data found in armored input");
        return Base64.getDecoder().decode(b64.toString());
    }

    /** {@code parse_ssh_signature}'s signature field; IllegalArgumentException if malformed. */
    static byte[] signatureData(byte[] sig) {
        if (sig.length < SSHSIG_MAGIC.length || !Arrays.equals(Arrays.copyOf(sig, SSHSIG_MAGIC.length), SSHSIG_MAGIC)) {
            throw new IllegalArgumentException("Invalid SSH signature: missing SSHSIG magic");
        }
        ByteBuffer buffer = ByteBuffer.wrap(sig, SSHSIG_MAGIC.length, sig.length - SSHSIG_MAGIC.length);
        if (buffer.remaining() < 4) throw new IllegalArgumentException("Invalid SSH signature: truncated");
        if (buffer.getInt() != 1) throw new IllegalArgumentException("Unsupported SSH signature version");
        byte[] field = null;
        for (int i = 0; i < 5; i++) {
            if (buffer.remaining() < 4) throw new IllegalArgumentException("Not enough data for string length");
            int length = buffer.getInt();
            if (length < 0 || buffer.remaining() < length) throw new IllegalArgumentException("Not enough data for string content");
            field = new byte[length];
            buffer.get(field);
        }
        return field;
    }

    /** {@code validate_rsg} without a required signer: the signing identity if valid, else null. */
    @SuppressWarnings("unchecked")
    static Identity validateRsg(byte[] rsg, byte[] message) throws Exception {
        if (rsg.length == SIGLENGTH) throw new IllegalArgumentException("Cannot validate legacy rsg format");
        if (rsg.length < SIGLENGTH + 1) return null;

        byte[] signature = Arrays.copyOfRange(rsg, 0, SIGLENGTH);
        byte[] envelope = Arrays.copyOfRange(rsg, SIGLENGTH, rsg.length);
        Object unpacked;
        try {
            unpacked = MsgPackUtils.unpackObject(envelope);
        } catch (Exception e) {
            return null;
        }
        if (!(unpacked instanceof Map)) return null;
        Map<Object, Object> signed = (Map<Object, Object>) unpacked;
        if (!signed.containsKey("hashtype") || !signed.containsKey("hash")) return null;
        if (!"sha256".equals(signed.get("hashtype"))) return null;
        if (!(signed.get("meta") instanceof Map)) return null;
        Map<Object, Object> meta = (Map<Object, Object>) signed.get("meta");
        if (!meta.containsKey("signer") || !(meta.get("pubkey") instanceof byte[])) return null;

        Identity identity = new Identity(false);
        if (!identity.loadPublicKey((byte[]) meta.get("pubkey"))) return null;
        if (!(signed.get("hash") instanceof byte[])
                || !Arrays.equals((byte[]) signed.get("hash"), MessageDigest.getInstance("SHA-256").digest(message))) {
            return null;
        }
        return identity.validate(signature, envelope) ? identity : null;
    }

    /** {@code extract_commit_author}: the email of the header's author line, or "". */
    static String commitAuthor(byte[] content) {
        String text = new String(content, StandardCharsets.UTF_8);
        for (String line : text.split("\\r\\n|\\n|\\r", -1)) {
            if (line.isEmpty()) break;
            if (!line.startsWith("author ")) continue;
            int spos = line.indexOf('<');
            int epos = line.indexOf('>');
            if (spos > AUTHOR_TARGET.length && epos > spos && epos < line.length() - 1) {
                return line.substring(spos + 1, epos);
            }
        }
        return "";
    }
}
