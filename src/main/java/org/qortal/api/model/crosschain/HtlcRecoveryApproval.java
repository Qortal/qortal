package org.qortal.api.model.crosschain;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

/** Approval of the complete public preview, including its net payout and fee. */
@XmlAccessorType(XmlAccessType.FIELD)
public class HtlcRecoveryApproval {
    public String tradeAddress;
    public String quoteId;
    public long expiresAt;
}
