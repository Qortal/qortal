package org.qortal.crosschain;

import org.bitcoinj.core.*;
import org.bitcoinj.script.ScriptBuilder;
import org.bitcoinj.script.ScriptChunk;
import org.bitcoinj.script.ScriptOpCodes;
import org.qortal.crypto.Crypto;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;

/** Shared legacy-P2SH fee bounds. Ordinary wallet sends and ARRR JNI are separate. */
public final class BitcoinyHtlcFees {
    private BitcoinyHtlcFees() { }

    public static final class ApprovalRequiredException extends ForeignBlockchainException {
        public final long reserve, requiredFee;
        public final boolean refund;
        ApprovalRequiredException(long reserve, long requiredFee, boolean refund) {
            super("HTLC_FEE_APPROVAL_REQUIRED: originalReserve=" + reserve + " requiredFee=" + requiredFee
                    + " additionalFee=" + (requiredFee - reserve) + " atomic units; explicit recovery approval required");
            this.reserve = reserve; this.requiredFee = requiredFee; this.refund = refund;
        }
    }

    /** A new one-input contract must cover the largest canonical redeem signature. */
    public static long fundingReserve(Bitcoiny coin, Long timestamp) throws ForeignBlockchainException {
        long configured = coin.getP2shFee(timestamp);
        if (configured < 0) throw new ForeignBlockchainException("Invalid HTLC fee floor");
        if ("ARRR".equals(coin.getCurrencyCode())) return configured;
        // 4 version + 1 input count + 36 outpoint + 1 script length + 224 scriptSig
        // + 4 sequence + 1 output count + 8 value + 1 length + 25 P2PKH + 4 locktime.
        return Math.max(configured, rateFee(coin, 309));
    }

    private static long rateFee(Bitcoiny coin, int bytes) throws ForeignBlockchainException {
        if (bytes <= 0) throw new ForeignBlockchainException("Invalid transaction size");
        try {
            return BigInteger.valueOf(BitcoinyFees.perKb(coin)).multiply(BigInteger.valueOf(bytes))
                    .add(BigInteger.valueOf(999)).divide(BigInteger.valueOf(1000)).longValueExact();
        } catch (ArithmeticException e) { throw new ForeignBlockchainException("HTLC fee overflow"); }
    }

    public static long requiredFee(Bitcoiny coin, int bytes) throws ForeignBlockchainException {
        long configured = coin.getP2shFee(null);
        if (configured < 0) throw new ForeignBlockchainException("Invalid HTLC fee floor");
        return Math.max(configured, rateFee(coin, bytes));
    }

    /** Never silently combine distinct deposits or unrelated scripts. */
    public static TransactionOutput uniqueFunding(List<TransactionOutput> outputs, byte[] redeemScript, long amount)
            throws ForeignBlockchainException {
        if (amount <= 0) throw new ForeignBlockchainException("Invalid HTLC payment amount");
        byte[] expected = ScriptBuilder.createP2SHOutputScript(Crypto.hash160(redeemScript)).getProgram();
        TransactionOutput selected = null;
        for (TransactionOutput output : outputs) {
            if (!Arrays.equals(expected, output.getScriptBytes()) || output.getValue().value < amount) continue;
            if (selected != null && !selected.getOutPointFor().equals(output.getOutPointFor()))
                throw new ForeignBlockchainException("Ambiguous HTLC deposits; recovery record retained");
            selected = output;
        }
        if (selected == null) throw new ForeignBlockchainException("No verified funding output covers the agreed HTLC amount");
        return selected;
    }

    /** No private key or transaction signature is needed to calculate the upper bound. */
    public static int maximumSpendSize(NetworkParameters params, TransactionOutput funding, byte[] script,
                                       byte[] publicKey, boolean refund) {
        Transaction tx = new Transaction(params);
        tx.setVersion(2);
        tx.addOutput(Coin.SATOSHI, ScriptBuilder.createP2PKHOutputScript(new byte[20]));
        ScriptBuilder sig = new ScriptBuilder();
        if (!refund) sig.data(new byte[32]);
        sig.data(new byte[73]).data(publicKey).addChunk(new ScriptChunk(ScriptOpCodes.OP_PUSHDATA1, script));
        tx.addInput(new TransactionInput(params, null, sig.build().getProgram(), funding.getOutPointFor()));
        return tx.bitcoinSerialize().length;
    }

    public static Transaction buildRedeemTransaction(Bitcoiny coin, Coin amount, ECKey key,
            List<TransactionOutput> funding, byte[] script, byte[] secret, byte[] receiving)
            throws ForeignBlockchainException {
        return build(coin, amount, key, funding, script, secret, null, receiving);
    }

    public static Transaction buildRefundTransaction(Bitcoiny coin, Coin amount, ECKey key,
            List<TransactionOutput> funding, byte[] script, long locktime, byte[] receiving)
            throws ForeignBlockchainException {
        return build(coin, amount, key, funding, script, null, locktime, receiving);
    }

    private static Transaction build(Bitcoiny coin, Coin amount, ECKey key, List<TransactionOutput> outputs,
            byte[] script, byte[] secret, Long locktime, byte[] receiving) throws ForeignBlockchainException {
        if (amount == null || amount.value <= 0 || key == null || !key.isCompressed()
                || receiving == null || receiving.length != 20 || script == null || script.length != 81
                || (locktime == null && (secret == null || secret.length != 32)))
            throw new ForeignBlockchainException("Invalid HTLC spend parameters");
        byte[] keyHash = Arrays.copyOfRange(script, locktime == null ? 36 : 5, locktime == null ? 56 : 25);
        if (!Arrays.equals(key.getPubKeyHash(), keyHash)
                || (locktime == null && !Arrays.equals(Crypto.hash160(secret), Arrays.copyOfRange(script, 59, 79))))
            throw new ForeignBlockchainException("HTLC key or secret does not match the contract");
        TransactionOutput funding = uniqueFunding(outputs, script, amount.value);
        long reserve = funding.getValue().value - amount.value;
        long required = requiredFee(coin, maximumSpendSize(coin.getNetworkParameters(), funding, script, key.getPubKey(), locktime != null));
        if (reserve < required) throw new ApprovalRequiredException(reserve, required, locktime != null);
        Transaction tx = locktime == null
                ? BitcoinyHTLC.buildRedeemTransaction(coin.getNetworkParameters(), amount, key, List.of(funding), script, secret, receiving)
                : BitcoinyHTLC.buildRefundTransaction(coin.getNetworkParameters(), amount, key, List.of(funding), script, locktime, receiving);
        if (reserve < requiredFee(coin, tx.bitcoinSerialize().length))
            throw new ForeignBlockchainException("HTLC fee changed during preparation; retry the preview");
        if (tx.getOutput(0).isDust() || amount.value < LocalWalletSupport.minimumP2pkhOutput(coin.getCurrencyCode()))
            throw new ForeignBlockchainException("HTLC recovery payout would be dust");
        return tx;
    }
}
