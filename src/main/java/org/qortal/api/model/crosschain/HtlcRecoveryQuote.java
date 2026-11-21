package org.qortal.api.model.crosschain;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

/** All monetary fields are exact decimal strings in atomic units. No keys or secrets. */
@XmlAccessorType(XmlAccessType.FIELD)
public class HtlcRecoveryQuote {
    public String atAddress, tradeAddress, blockchain, network, operation, destination;
    public String fundingTxid;
    public int fundingOutputIndex;
    public String fundingAmount, expectedAmount, originalReserve, requiredFee, fee, additionalFee, receiveAmount;
    public int decimals = 8;
    public int maximumTransactionBytes;
    public long expiresAt;
    public String quoteId;
}
