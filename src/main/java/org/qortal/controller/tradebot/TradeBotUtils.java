package org.qortal.controller.tradebot;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bitcoinj.core.Transaction;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.api.resource.CrossChainUtils;
import org.qortal.crosschain.ACCT;
import org.qortal.crosschain.Bitcoiny;
import org.qortal.crosschain.BitcoinyHTLC;
import org.qortal.crosschain.ForeignBlockchainException;
import org.qortal.crypto.Crypto;
import org.qortal.data.crosschain.CrossChainTradeData;
import org.qortal.data.crosschain.TradeBotData;
import org.qortal.data.transaction.MessageTransactionData;
import org.qortal.group.Group;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.transaction.MessageTransaction;
import org.qortal.utils.Base58;
import org.qortal.utils.NTP;
import org.qortal.transaction.Transaction.ValidationResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.qortal.controller.tradebot.TradeStates.State;

public class TradeBotUtils {

    private static final Logger LOGGER = LogManager.getLogger(TradeBotUtils.class);
    /**
     * Creates trade-bot entries from the 'Alice' viewpoint, i.e. matching Bitcoiny coin to existing offers.
     * <p>
     * Requires chosen trade offers from Bob, passed by <tt>crossChainTradeData</tt>
     * and access to a Blockchain wallet via <tt>foreignKey</tt>.
     * <p>
     * The <tt>crossChainTradeData</tt> contains the current trade offers state
     * as extracted from the AT's data segment.
     * <p>
     * Access to a funded wallet is via a Blockchain BIP32 hierarchical deterministic key,
     * passed via <tt>foreignKey</tt>.
     * <b>This key will be stored in your node's database</b>
     * to allow trade-bot to create/fund the necessary P2SH transactions!
     * However, due to the nature of BIP32 keys, it is possible to give the trade-bot
     * only a subset of wallet access (see BIP32 for more details).
     * <p>
     * As an example, the foreignKey can be extract from a <i>legacy, password-less</i>
     * Electrum wallet by going to the console tab and entering:<br>
     * <tt>wallet.keystore.xprv</tt><br>
     * which should result in a base58 string starting with either 'xprv' (for Blockchain main-net)
     * or 'tprv' for (Blockchain test-net).
     * <p>
     * It is envisaged that the value in <tt>foreignKey</tt> will actually come from a Qortal-UI-managed wallet.
     * <p>
     * If sufficient funds are available, <b>this method will actually fund the P2SH-A</b>
     * with the Blockchain amount expected by 'Bob'.
     * <p>
     * If the Blockchain transaction is successfully broadcast to the network then
     * we also send a MESSAGE to Bob's trade-bot to let them know; one message for each trade.
     * <p>
     * The trade-bot entries are saved to the repository and the cross-chain trading process commences.
     * <p>
     *
     * @param repository for backing up the trade bot data
     * @param crossChainTradeDataList chosen trade OFFERs that Alice wants to match
     * @param receiveAddress Alice's Qortal address
     * @param foreignKey              funded wallet xprv in base58
     * @param bitcoiny the bitcoiny chain to match the sell offer with
     * @return true if P2SH-A funding transaction successfully broadcast to Blockchain network, false otherwise
     * @throws DataException
     */
    public static AcctTradeBot.ResponseResult startResponseMultiple(
            Repository repository,
            ACCT acct,
            List<CrossChainTradeData> crossChainTradeDataList,
            String receiveAddress,
            String foreignKey,
            Bitcoiny bitcoiny) throws DataException {

        // Check we have enough funds via foreignKey to fund P2SH to cover expectedForeignAmount
        long now = NTP.getTime();
        long p2shFee;
        try {
            p2shFee = bitcoiny.getP2shFee(now);
        } catch (ForeignBlockchainException e) {
            LOGGER.debug("Couldn't estimate blockchain transaction fees?");
            return AcctTradeBot.ResponseResult.NETWORK_ISSUE;
        }

        Map<String, Long> valueByP2shAddress = new HashMap<>(crossChainTradeDataList.size());

        class DataCombiner{
            CrossChainTradeData crossChainTradeData;
            TradeBotData tradeBotData;
            String p2shAddress;

            public DataCombiner(CrossChainTradeData crossChainTradeData, TradeBotData tradeBotData, String p2shAddress) {
                this.crossChainTradeData = crossChainTradeData;
                this.tradeBotData = tradeBotData;
                this.p2shAddress = p2shAddress;
            }
        }

        List<DataCombiner> dataToProcess = new ArrayList<>();

        for(CrossChainTradeData crossChainTradeData : crossChainTradeDataList) {
            byte[] tradePrivateKey = TradeBot.generateTradePrivateKey();
            byte[] secretA = TradeBot.generateSecret();
            byte[] hashOfSecretA = Crypto.hash160(secretA);

            byte[] tradeNativePublicKey = TradeBot.deriveTradeNativePublicKey(tradePrivateKey);
            byte[] tradeNativePublicKeyHash = Crypto.hash160(tradeNativePublicKey);
            String tradeNativeAddress = Crypto.toAddress(tradeNativePublicKey);

            byte[] tradeForeignPublicKey = TradeBot.deriveTradeForeignPublicKey(tradePrivateKey);
            byte[] tradeForeignPublicKeyHash = Crypto.hash160(tradeForeignPublicKey);
            // We need to generate lockTime-A: add tradeTimeout to now
            int lockTimeA = (crossChainTradeData.tradeTimeout * 60) + (int) (now / 1000L);
            byte[] receivingPublicKeyHash = Base58.decode(receiveAddress); // Actually the whole address, not just PKH

            TradeBotData tradeBotData = new TradeBotData(tradePrivateKey, acct.getClass().getSimpleName(),
                    State.ALICE_WAITING_FOR_AT_LOCK.name(), State.ALICE_WAITING_FOR_AT_LOCK.value,
                    receiveAddress,
                    crossChainTradeData.qortalAtAddress,
                    now,
                    crossChainTradeData.qortAmount,
                    tradeNativePublicKey, tradeNativePublicKeyHash, tradeNativeAddress,
                    secretA, hashOfSecretA,
                    crossChainTradeData.foreignBlockchain,
                    tradeForeignPublicKey, tradeForeignPublicKeyHash,
                    crossChainTradeData.expectedForeignAmount,
                    foreignKey, null, lockTimeA, receivingPublicKeyHash);

            // Fee for redeem/refund is subtracted from P2SH-A balance.
            // Do not include fee for funding transaction as this is covered by buildSpend()
            long amountA = crossChainTradeData.expectedForeignAmount + p2shFee /*redeeming/refunding P2SH-A*/;

            // P2SH-A to be funded
            byte[] redeemScriptBytes = BitcoinyHTLC.buildScript(tradeForeignPublicKeyHash, lockTimeA, crossChainTradeData.creatorForeignPKH, hashOfSecretA);
            String p2shAddress = bitcoiny.deriveP2shAddress(redeemScriptBytes);

            valueByP2shAddress.put(p2shAddress, amountA);

            dataToProcess.add(new DataCombiner(crossChainTradeData, tradeBotData, p2shAddress));
        }

        // Attempt to backup the trade bot data
        // Include tradeBotData as an additional parameter, since it's not in the repository yet
        TradeBot.backupTradeBotData(repository, dataToProcess.stream().map( data -> data.tradeBotData).collect(Collectors.toList()));

        // Build transaction for funding P2SH-A
        Transaction p2shFundingTransaction = bitcoiny.buildSpendMultiple(foreignKey, valueByP2shAddress, null);
        if (p2shFundingTransaction == null) {
            LOGGER.debug("Unable to build P2SH-A funding transaction - lack of funds?");
            return AcctTradeBot.ResponseResult.BALANCE_ISSUE;
        }

        try {
            bitcoiny.broadcastTransaction(p2shFundingTransaction);
        } catch (ForeignBlockchainException e) {
            // We couldn't fund P2SH-A at this time
            LOGGER.debug("Couldn't broadcast P2SH-A funding transaction?");
            return AcctTradeBot.ResponseResult.NETWORK_ISSUE;
        }

        for(DataCombiner datumToProcess : dataToProcess ) {
            // Attempt to send MESSAGE to Bob's Qortal trade address
            TradeBotData tradeBotData = datumToProcess.tradeBotData;

            byte[] messageData = CrossChainUtils.buildOfferMessage(tradeBotData.getTradeForeignPublicKeyHash(), tradeBotData.getHashOfSecret(), tradeBotData.getLockTimeA());
            CrossChainTradeData crossChainTradeData = datumToProcess.crossChainTradeData;
            String messageRecipient = crossChainTradeData.qortalCreatorTradeAddress;

            boolean isMessageAlreadySent = repository.getMessageRepository().exists(tradeBotData.getTradeNativePublicKey(), messageRecipient, messageData);
            if (!isMessageAlreadySent) {
                // Do this in a new thread so caller doesn't have to wait for computeNonce()
                // In the unlikely event that the transaction doesn't validate then the buy won't happen and eventually Alice's AT will be refunded
                new Thread(() -> {
                    try (final Repository threadsRepository = RepositoryManager.getRepository()) {
                        PrivateKeyAccount sender = new PrivateKeyAccount(threadsRepository, tradeBotData.getTradePrivateKey());
                        MessageTransaction messageTransaction = MessageTransaction.build(threadsRepository, sender, Group.NO_GROUP, messageRecipient, messageData, false, false);

                        LOGGER.info("Computing nonce at difficulty {} for AT {} and recipient {}", messageTransaction.getPoWDifficulty(), tradeBotData.getAtAddress(), messageRecipient);
                        messageTransaction.computeNonce();
                        MessageTransactionData newMessageTransactionData = (MessageTransactionData) messageTransaction.getTransactionData();
                        LOGGER.info("Computed nonce {} at difficulty {}", newMessageTransactionData.getNonce(), messageTransaction.getPoWDifficulty());
                        messageTransaction.sign(sender);

                        // reset repository state to prevent deadlock
                        threadsRepository.discardChanges();

                        if (messageTransaction.isSignatureValid()) {
                            ValidationResult result = messageTransaction.importAsUnconfirmed();

                            if (result != ValidationResult.OK) {
                                LOGGER.warn(() -> String.format("Unable to send MESSAGE to Bob's trade-bot %s: %s", messageRecipient, result.name()));
                            }
                        } else {
                            LOGGER.warn(() -> String.format("Unable to send MESSAGE to Bob's trade-bot %s: signature invalid", messageRecipient));
                        }
                    } catch (DataException e) {
                        LOGGER.warn(() -> String.format("Unable to send MESSAGE to Bob's trade-bot %s: %s", messageRecipient, e.getMessage()));
                    }
                }, "TradeBot response").start();
            }

            TradeBot.updateTradeBotState(repository, tradeBotData, () -> String.format("Funding P2SH-A %s. Messaged Bob. Waiting for AT-lock", datumToProcess.p2shAddress));
        }

        return AcctTradeBot.ResponseResult.OK;
    }
}