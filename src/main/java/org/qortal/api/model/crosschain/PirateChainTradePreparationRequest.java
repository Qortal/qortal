package org.qortal.api.model.crosschain;

import io.swagger.v3.oas.annotations.media.Schema;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

/** Amounts use integer arrrtoshis, without the decimal coin amount adapter. */
@XmlAccessorType(XmlAccessType.FIELD)
public class PirateChainTradePreparationRequest {
    @Schema(description = "Wallet entropy, Base58 encoded")
    public String entropy58;
    @Schema(description = "Contract principal plus HTLC spend and funding fees, in arrrtoshis")
    public long requiredAmount;
    @Schema(description = "Exact native quote approved by the wallet owner; omit to query only")
    public String approvedQuote;
    @Schema(description = "Approved preparation fee in arrrtoshis; omit to query only")
    public Long maxFee;
}
