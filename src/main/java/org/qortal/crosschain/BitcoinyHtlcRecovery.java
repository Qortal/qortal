package org.qortal.crosschain;

import com.google.common.hash.HashCode;
import org.apache.logging.log4j.Logger;
import org.bitcoinj.core.*;
import org.bitcoinj.script.Script;
import org.qortal.api.model.crosschain.HtlcRecoveryApproval;
import org.qortal.api.model.crosschain.HtlcRecoveryQuote;
import org.qortal.controller.tradebot.PirateChainACCTv3TradeBot;
import org.qortal.crypto.Crypto;
import org.qortal.data.at.ATData;
import org.qortal.data.crosschain.CrossChainTradeData;
import org.qortal.data.crosschain.TradeBotData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.utils.NTP;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Explicit, quote-bound recovery for Bitcoin-family contracts with insufficient fee reserves. */
public final class BitcoinyHtlcRecovery {
    public static final long QUOTE_LIFETIME = 5 * 60_000L;
    private static final Map<String, Long> lastLogs = new LinkedHashMap<>();
    private BitcoinyHtlcRecovery() { }

    private static long now() throws ForeignBlockchainException {
        Long time = NTP.getTime();
        if (time == null) throw new ForeignBlockchainException("Clock is unavailable for HTLC recovery");
        return time;
    }

    public static void logApprovalRequired(Logger logger, TradeBotData trade, BitcoinyHtlcFees.ApprovalRequiredException error) {
        String operation = error.refund ? "refund" : "redeem";
        String key = trade.getTradeNativeAddress() + ":" + operation + ":" + error.requiredFee;
        long time = System.currentTimeMillis();
        synchronized (lastLogs) {
            if (time - lastLogs.getOrDefault(key, 0L) < 10 * 60_000L) return;
            lastLogs.put(key, time);
            while (lastLogs.size() > 256) lastLogs.remove(lastLogs.keySet().iterator().next());
        }
        logger.info("HTLC fee approval required for AT {} (trade {}): originalReserve={} requiredFee={} additionalFee={} atomic units. "
                + "No spend submitted. Preview with authenticated GET /crosschain/htlc/{}/{}/quote?tradeAddress={}; "
                + "review fee and receiveAmount, then POST /crosschain/htlc/{}/{} with the preview's tradeAddress, quoteId and expiresAt as JSON. "
                + "Batch recovery does not approve extra fees.", trade.getAtAddress(), trade.getTradeNativeAddress(),
                error.reserve, error.requiredFee, error.requiredFee - error.reserve, operation, trade.getAtAddress(),
                trade.getTradeNativeAddress(), operation, trade.getAtAddress());
    }

    /** BTC v1's other 1000 atomic units are paid by its separate B contract. */
    public static long paymentAmount(TradeBotData trade, CrossChainTradeData data) throws ForeignBlockchainException {
        long amount = data.expectedForeignAmount - ("BitcoinACCTv1".equals(trade.getAcctName()) ? 1000L : 0L);
        if (amount <= 0) throw new ForeignBlockchainException("Invalid HTLC payment amount");
        return amount;
    }

    private static final class Context {
        TradeBotData trade;
        Bitcoiny coin;
        byte[] script, secret, receiving;
        String destination;
        long expected;
        Long locktime;
        TransactionOutput funding;
        ECKey key;
    }

    private static Context context(Repository repository, TradeBotData trade, boolean refund)
            throws DataException, ForeignBlockchainException {
        if (trade.getState() == null || !trade.getState().startsWith(refund ? "ALICE_" : "BOB_"))
            throw new ForeignBlockchainException("Trade does not have the requested recovery role");
        ATData at = repository.getATRepository().fromATAddress(trade.getAtAddress());
        if (at == null) throw new ForeignBlockchainException("Recovery AT is unavailable");
        ACCT acct = SupportedBlockchain.getAcctByCodeHash(at.getCodeHash());
        if (acct == null || !(acct.getBlockchain() instanceof Bitcoiny))
            throw new ForeignBlockchainException("Recovery blockchain is unavailable");
        Bitcoiny coin = (Bitcoiny) acct.getBlockchain();
        if ("ARRR".equals(coin.getCurrencyCode()))
            throw new ForeignBlockchainException("ARRR uses its native recovery path, not Bitcoin-family fee approval");
        CrossChainTradeData data = acct.populateTradeData(repository, at);
        if (data == null || data.expectedForeignAmount <= 0 || data.expectedForeignAmount != trade.getForeignAmount())
            throw new ForeignBlockchainException("Invalid or inconsistent recovery amount");
        if (data.creatorForeignPKH == null || data.creatorForeignPKH.length != 20
                || (refund && (trade.getHashOfSecret() == null || trade.getHashOfSecret().length != 20
                    || trade.getTradeForeignPublicKeyHash() == null || trade.getTradeForeignPublicKeyHash().length != 20))
                || (!refund && (data.partnerForeignPKH == null || data.partnerForeignPKH.length != 20
                    || data.hashOfSecretA == null || data.hashOfSecretA.length != 20)))
            throw new ForeignBlockchainException("Recovery contract data is incomplete");
        if (trade.getTradePrivateKey() == null || trade.getTradePrivateKey().length != 32)
            throw new ForeignBlockchainException("Recovery key is unavailable");
        Context ctx = new Context(); ctx.trade = trade; ctx.coin = coin; ctx.expected = paymentAmount(trade, data);
        ctx.key = ECKey.fromPrivate(trade.getTradePrivateKey());
        if (refund) {
            if (!PirateChainACCTv3TradeBot.isRefundEligible(trade, at, data) || trade.getLockTimeA() == null
                    || trade.getForeignKey() == null || now() <= trade.getLockTimeA() * 1000L
                    || coin.getMedianBlockTime() <= trade.getLockTimeA())
                throw new ForeignBlockchainException("HTLC refund is not eligible or mature");
            ctx.locktime = (long) trade.getLockTimeA();
            ctx.script = BitcoinyHTLC.buildScript(trade.getTradeForeignPublicKeyHash(), trade.getLockTimeA(),
                    data.creatorForeignPKH, trade.getHashOfSecret());
            if (!Arrays.equals(ctx.key.getPubKeyHash(), trade.getTradeForeignPublicKeyHash()))
                throw new ForeignBlockchainException("Refund key does not match the contract");
            ctx.destination = coin.getUnusedReceiveAddress(trade.getForeignKey());
            Address address = Address.fromString(coin.getNetworkParameters(), ctx.destination);
            if (address.getOutputScriptType() != Script.ScriptType.P2PKH)
                throw new ForeignBlockchainException("Recovery destination must be the wallet's P2PKH address");
            ctx.receiving = address.getHash();
        } else {
            if (!at.getIsFinished() || data.mode != AcctMode.REDEEMED || data.lockTimeA == null
                    || !Arrays.equals(ctx.key.getPubKeyHash(), data.creatorForeignPKH))
                throw new ForeignBlockchainException("QORT settlement or seller key is not verified");
            ctx.secret = acct.findSecretA(repository, data);
            if (ctx.secret == null || ctx.secret.length != 32 || !Arrays.equals(Crypto.hash160(ctx.secret), data.hashOfSecretA))
                throw new ForeignBlockchainException("Verified redemption secret is unavailable");
            ctx.script = BitcoinyHTLC.buildScript(data.partnerForeignPKH, data.lockTimeA, data.creatorForeignPKH, data.hashOfSecretA);
            ctx.receiving = trade.getReceivingAccountInfo();
            if (ctx.receiving == null || ctx.receiving.length != 20)
                throw new ForeignBlockchainException("Stored seller destination is invalid");
            ctx.destination = coin.pkhToAddress(ctx.receiving);
        }
        String address = coin.deriveP2shAddress(ctx.script);
        // Discovery depends on the agreed amount, not today's mutable fee setting.
        List<TransactionOutput> outputs = coin.getUnspentOutputs(address, false);
        if (trade.hasFundingOutpoint()) {
            List<TransactionOutput> exact = new ArrayList<>();
            for (TransactionOutput output : outputs)
                if (trade.getFundingTxid().equals(output.getParentTransactionHash().toString())
                        && trade.getFundingOutputIndex() == output.getIndex()) exact.add(output);
            outputs = exact;
        }
        ctx.funding = BitcoinyHtlcFees.uniqueFunding(outputs, ctx.script, ctx.expected);
        return ctx;
    }

    private static HtlcRecoveryQuote quote(Context ctx, boolean refund, long expiry) throws ForeignBlockchainException {
        HtlcRecoveryQuote q = new HtlcRecoveryQuote();
        q.atAddress = ctx.trade.getAtAddress(); q.tradeAddress = ctx.trade.getTradeNativeAddress();
        q.blockchain = ctx.coin.getCurrencyCode(); q.network = ctx.coin.getBlockchainProvider().getNetId();
        q.operation = refund ? "refund" : "redeem"; q.destination = ctx.destination;
        q.fundingTxid = ctx.funding.getParentTransactionHash().toString(); q.fundingOutputIndex = ctx.funding.getIndex();
        long value = ctx.funding.getValue().value;
        q.maximumTransactionBytes = BitcoinyHtlcFees.maximumSpendSize(ctx.coin.getNetworkParameters(), ctx.funding,
                ctx.script, ctx.key.getPubKey(), refund);
        long required = BitcoinyHtlcFees.requiredFee(ctx.coin, q.maximumTransactionBytes);
        long net = Math.min(ctx.expected, value - required);
        if (net <= 0) throw new ForeignBlockchainException("Contract cannot cover the current recovery fee");
        Transaction dustCheck = new Transaction(ctx.coin.getNetworkParameters());
        dustCheck.addOutput(Coin.valueOf(net), Address.fromString(ctx.coin.getNetworkParameters(), ctx.destination));
        if (dustCheck.getOutput(0).isDust() || net < LocalWalletSupport.minimumP2pkhOutput(ctx.coin.getCurrencyCode()))
            throw new ForeignBlockchainException("Recovery payout would be dust");
        q.fundingAmount = Long.toString(value); q.expectedAmount = Long.toString(ctx.expected);
        q.originalReserve = Long.toString(value - ctx.expected); q.requiredFee = Long.toString(required);
        q.receiveAmount = Long.toString(net); q.fee = Long.toString(value - net);
        q.additionalFee = Long.toString(ctx.expected - net); q.expiresAt = expiry;
        q.quoteId = HashCode.fromBytes(Crypto.digest(String.join("\n", "QORTAL-HTLC-FEE-RECOVERY-1", q.atAddress,
                q.tradeAddress, q.blockchain, q.network, q.operation, q.destination, q.fundingTxid,
                Integer.toString(q.fundingOutputIndex), q.fundingAmount, q.expectedAmount, q.originalReserve,
                q.requiredFee, q.fee, q.additionalFee, q.receiveAmount, Integer.toString(q.maximumTransactionBytes),
                Long.toString(q.expiresAt)).getBytes(StandardCharsets.UTF_8))).toString();
        return q;
    }

    public static HtlcRecoveryQuote preview(Repository repository, TradeBotData trade, boolean refund)
            throws DataException, ForeignBlockchainException {
        HtlcRecoveryState pending = HtlcRecoveryState.decode(trade.getLastTransactionSignature());
        if (pending != null && !pending.confirmed)
            throw new ForeignBlockchainException("Approved HTLC recovery is pending: " + pending.txid + "; no replacement submitted");
        return quote(context(repository, trade, refund), refund, Math.addExact(now(), QUOTE_LIFETIME));
    }

    /** Caller serializes this with the trade-bot scheduler. No signature is created before approval validation. */
    public static boolean submit(Repository repository, TradeBotData trade, boolean refund, HtlcRecoveryApproval approval)
            throws DataException, ForeignBlockchainException {
        if (approval == null || !Objects.equals(trade.getTradeNativeAddress(), approval.tradeAddress)
                || approval.quoteId == null || !approval.quoteId.matches("[0-9a-f]{64}"))
            throw new ForeignBlockchainException("A complete HTLC recovery quote approval is required");
        long time = now();
        if (approval.expiresAt <= time || approval.expiresAt - time > QUOTE_LIFETIME)
            throw new ForeignBlockchainException("HTLC recovery quote expired; request a new preview");
        TradeBotData latest = repository.getCrossChainRepository().getTradeBotData(trade.getTradePrivateKey());
        if (latest == null) throw new ForeignBlockchainException("Recovery record no longer exists");
        HtlcRecoveryState previous = HtlcRecoveryState.decode(latest.getLastTransactionSignature());
        if (previous != null && !previous.confirmed)
            throw new ForeignBlockchainException("Approved recovery already pending: " + previous.txid + "; no replacement submitted");
        Context ctx = context(repository, latest, refund);
        HtlcRecoveryQuote current = quote(ctx, refund, approval.expiresAt);
        if (!MessageDigest.isEqual(HashCode.fromString(approval.quoteId).asBytes(), HashCode.fromString(current.quoteId).asBytes()))
            throw new ForeignBlockchainException("HTLC fee, output, destination or trade changed; review a new preview");
        Coin payout = Coin.valueOf(Long.parseLong(current.receiveAmount));
        Transaction transaction = refund
                ? BitcoinyHtlcFees.buildRefundTransaction(ctx.coin, payout, ctx.key, List.of(ctx.funding), ctx.script, ctx.locktime, ctx.receiving)
                : BitcoinyHtlcFees.buildRedeemTransaction(ctx.coin, payout, ctx.key, List.of(ctx.funding), ctx.script, ctx.secret, ctx.receiving);
        String txid = transaction.getTxId().toString();
        // Persist exact intent before the first network side effect. Unknown replies never trigger replacement.
        latest.setFundingTxid(current.fundingTxid); latest.setFundingOutputIndex(current.fundingOutputIndex);
        latest.setFundingFee(Long.parseLong(current.originalReserve));
        latest.setLastTransactionSignature(new HtlcRecoveryState(txid, time, Long.parseLong(current.fee), false).encode());
        repository.getCrossChainRepository().save(latest); repository.saveChanges();
        ctx.coin.getBlockchainProvider().broadcastTransaction(transaction.bitcoinSerialize(), txid);
        return true;
    }

    /** Hold ordinary spending/deletion until the explicitly approved transaction has three confirmations. */
    public static boolean holdPending(Repository repository, TradeBotData trade) throws DataException, ForeignBlockchainException {
        HtlcRecoveryState state = HtlcRecoveryState.decode(trade.getLastTransactionSignature());
        if (state == null || state.confirmed) return false;
        if (!trade.hasFundingOutpoint()) throw new ForeignBlockchainException("Pending recovery lacks its saved funding outpoint");
        ACCT acct = SupportedBlockchain.getAcctByName(trade.getAcctName());
        if (acct == null || !(acct.getBlockchain() instanceof Bitcoiny)) return true;
        Bitcoiny coin = (Bitcoiny) acct.getBlockchain();
        byte[] raw;
        try { raw = coin.getBlockchainProvider().getRawTransaction(state.txid); }
        catch (ForeignBlockchainException.NotFoundException e) { return true; }
        Transaction tx = coin.deserializeTransaction(raw);
        if (!tx.getTxId().toString().equals(state.txid) || tx.getInputs().size() != 1
                || !tx.getInput(0).getOutpoint().getHash().toString().equals(trade.getFundingTxid())
                || tx.getInput(0).getOutpoint().getIndex() != trade.getFundingOutputIndex())
            throw new ForeignBlockchainException("Pending recovery transaction evidence does not match its intent");
        List<TransactionOutput> funding = coin.getOutputs(HashCode.fromString(trade.getFundingTxid()).asBytes());
        if (trade.getFundingOutputIndex() >= funding.size()) throw new ForeignBlockchainException("Pending funding output is missing");
        byte[] script = funding.get(trade.getFundingOutputIndex()).getScriptBytes();
        for (TransactionHash item : coin.getBlockchainProvider().getAddressTransactions(script, true)) {
            if (state.txid.equals(item.txHash) && item.height > 0
                    && coin.getBlockchainProvider().getCurrentHeight() - item.height + 1 >= 3) {
                trade.setLastTransactionSignature(new HtlcRecoveryState(state.txid, state.attemptedAt, state.fee, true).encode());
                repository.getCrossChainRepository().save(trade); repository.saveChanges();
                return false;
            }
        }
        return true;
    }
}
