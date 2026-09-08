package org.qortal.api.model.crosschain;
import java.util.List;
public class LocalTradeRequest {
    public List<String> addresses;
    public String xpub58;
    public String receivingAddress;
    public String expectedChainId;
}
