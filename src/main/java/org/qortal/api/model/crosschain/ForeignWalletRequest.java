package org.qortal.api.model.crosschain;

/** Public discovery or finished signed bytes. Never accepts a wallet private key. */
public class ForeignWalletRequest {
    public String xpub58;
    public String expectedChainId;
    public String rawTransactionHex;
}
