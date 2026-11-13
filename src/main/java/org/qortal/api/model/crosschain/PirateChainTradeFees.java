package org.qortal.api.model.crosschain;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

/** Atomic ARRR fees; funding includes a separate reserve for the eventual HTLC spend. */
@XmlAccessorType(XmlAccessType.FIELD)
public class PirateChainTradeFees {
    public long fundingFee;
    public long refundFee;
    public PirateChainTradeFees() { }
    public PirateChainTradeFees(long fundingFee, long refundFee) {
        this.fundingFee = fundingFee;
        this.refundFee = refundFee;
    }
}
