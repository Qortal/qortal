package org.qortal.api.model.crosschain;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

@XmlAccessorType(XmlAccessType.FIELD)
public class PirateChainWalletInitializationRequest {
    public String entropy58;
    /** Omit both fields to inspect without creating or opening a wallet. */
    public Integer birthday;
    /** Explicit owner choice for a wallet that has never received funds. */
    public boolean useCurrentHeight;
}
