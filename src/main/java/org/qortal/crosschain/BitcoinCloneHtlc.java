package org.qortal.crosschain;

/** Explicit gate for reuse of Qortal's existing HASH160/CLTV legacy P2SH contract. */
public final class BitcoinCloneHtlc {
    private BitcoinCloneHtlc() { }

    public static byte[] buildScript(BitcoinCloneDefinition definition, byte[] refundHash, int lockTime,
            byte[] redeemHash, byte[] secretHash) {
        definition.require(BitcoinCloneDefinition.Capability.HTLC);
        if (!definition.isBuiltIn() || refundHash == null || refundHash.length != 20
                || redeemHash == null || redeemHash.length != 20 || secretHash == null || secretHash.length != 20
                || lockTime < BitcoinyHTLC.MIN_LOCKTIME)
            throw new IllegalArgumentException("Unsupported HTLC parameters or unreviewed adapter");
        return BitcoinyHTLC.buildScript(refundHash, lockTime, redeemHash, secretHash);
    }
}
