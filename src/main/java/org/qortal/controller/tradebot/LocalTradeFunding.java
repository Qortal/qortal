package org.qortal.controller.tradebot;

import com.google.common.hash.HashCode;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.crosschain.*;
import org.qortal.crypto.Crypto;
import org.qortal.data.crosschain.*;
import org.qortal.data.transaction.MessageTransactionData;
import org.qortal.group.Group;
import org.qortal.repository.*;
import org.qortal.transaction.MessageTransaction;
import org.qortal.transaction.Transaction.ValidationResult;
import org.qortal.utils.Base58;
import org.qortal.api.resource.CrossChainUtils;
import org.qortal.utils.NTP;
import java.util.*;
import static org.qortal.controller.tradebot.TradeStates.State;

/** Core owns only per-trade keys and a watch-only wallet key. Hub signs funding. */
public final class LocalTradeFunding {
    private LocalTradeFunding() {}

    public static synchronized Map<String, Object> prepare(Repository repository, ACCT acct,
            CrossChainTradeData offer, String xpub, String receiveAddress, Bitcoiny coin)
            throws DataException, ForeignBlockchainException {
        if (!acct.getClass().getSimpleName().endsWith("ACCTv3"))
            throw new IllegalArgumentException("Local funding requires ACCTv3");
        Long now = NTP.getTime();
        if (now == null) throw new ForeignBlockchainException("Network time unavailable");
        TradeBotData data = null;
        for (TradeBotData existing : repository.getCrossChainRepository().getAllTradeBotData()) {
            if (offer.qortalAtAddress.equals(existing.getAtAddress()) && xpub.equals(existing.getForeignKey())) {
                if (existing.getStateValue() != State.ALICE_WAITING_FOR_FUNDING.value
                        || !receiveAddress.equals(existing.getCreatorAddress()) || existing.getLockTimeA() * 1000L < now + 300000)
                    throw new IllegalArgumentException("Trade already started or expired");
                data = existing;
                break;
            }
        }
        if (data == null) {
            byte[] privateKey = TradeBot.generateTradePrivateKey();
            byte[] secret = TradeBot.generateSecret();
            byte[] nativeKey = TradeBot.deriveTradeNativePublicKey(privateKey);
            byte[] foreignKey = TradeBot.deriveTradeForeignPublicKey(privateKey);
            int lockTime = Math.toIntExact(now / 1000 + offer.tradeTimeout * 60L);
            data = new TradeBotData(privateKey, acct.getClass().getSimpleName(),
                State.ALICE_WAITING_FOR_FUNDING.name(), State.ALICE_WAITING_FOR_FUNDING.value,
                receiveAddress, offer.qortalAtAddress, now, offer.qortAmount,
                nativeKey, Crypto.hash160(nativeKey), Crypto.toAddress(nativeKey),
                secret, Crypto.hash160(secret), offer.foreignBlockchain, foreignKey, Crypto.hash160(foreignKey),
                offer.expectedForeignAmount, xpub, null, lockTime, Base58.decode(receiveAddress));
            repository.getCrossChainRepository().save(data);
        }
        // Refund keys MUST be durable before a client is allowed to fund an HTLC.
        repository.saveChanges();
        TradeBot.backupTradeBotData(repository, List.of(data));
        long fee = coin.getP2shFee((data.getLockTimeA() - offer.tradeTimeout * 60L) * 1000L);
        byte[] script = BitcoinyHTLC.buildScript(data.getTradeForeignPublicKeyHash(), data.getLockTimeA(), offer.creatorForeignPKH, data.getHashOfSecret());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("atAddress", offer.qortalAtAddress);
        result.put("receivingAddress", receiveAddress);
        result.put("address", coin.deriveP2shAddress(script));
        result.put("amount", Long.toString(Math.addExact(offer.expectedForeignAmount, fee)));
        result.put("fundingReserve", Long.toString(fee));
        result.put("redeemScript", HashCode.fromBytes(script).toString());
        result.put("lockTime", data.getLockTimeA());
        result.put("refundPublicKeyHash", HashCode.fromBytes(data.getTradeForeignPublicKeyHash()).toString());
        result.put("hashOfSecret", HashCode.fromBytes(data.getHashOfSecret()).toString());
        return result;
    }

    public static void progress(Repository repository, TradeBotData data, CrossChainTradeData offer, Bitcoiny coin)
            throws DataException, ForeignBlockchainException {
        // Reuse the existing state checks on expiry or changed offers. This also
        // recovers a crash after sending the offer message but before recording
        // the transition: an AT already locked to us must still be redeemed.
        Long now = NTP.getTime();
        if (now == null) return;
        if (now >= data.getLockTimeA() * 1000L || offer.mode != AcctMode.OFFERING) {
            TradeBot.updateTradeBotState(repository, data, State.ALICE_WAITING_FOR_AT_LOCK, () -> "Resume trade or refund after local funding");
            return;
        }
        byte[] script = BitcoinyHTLC.buildScript(data.getTradeForeignPublicKeyHash(), data.getLockTimeA(), offer.creatorForeignPKH, data.getHashOfSecret());
        long amount = Math.addExact(data.getForeignAmount(), coin.getP2shFee((data.getLockTimeA() - offer.tradeTimeout * 60L) * 1000L));
        BitcoinyHTLC.Status status = BitcoinyHTLC.determineHtlcStatus(coin.getBlockchainProvider(), coin.deriveP2shAddress(script), amount);
        if (status != BitcoinyHTLC.Status.FUNDED) return;
        byte[] message = CrossChainUtils.buildOfferMessage(data.getTradeForeignPublicKeyHash(), data.getHashOfSecret(), data.getLockTimeA());
        if (!repository.getMessageRepository().exists(data.getTradeNativePublicKey(), offer.qortalCreatorTradeAddress, message)) {
            PrivateKeyAccount sender = new PrivateKeyAccount(repository, data.getTradePrivateKey());
            MessageTransaction tx = MessageTransaction.build(repository, sender, Group.NO_GROUP, offer.qortalCreatorTradeAddress, message, false, false);
            tx.computeNonce();
            tx.sign(sender);
            repository.discardChanges();
            if (!tx.isSignatureValid() || tx.importAsUnconfirmed() != ValidationResult.OK) return;
        }
        TradeBot.updateTradeBotState(repository, data, State.ALICE_WAITING_FOR_AT_LOCK, () -> "Locally signed funding confirmed");
    }
}
