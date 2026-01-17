package org.qortal.network;

import org.qortal.controller.Controller;
import org.qortal.data.block.BlockSummaryData;
import org.qortal.data.block.CommonBlockData;
import org.qortal.data.network.PeerData;
import org.qortal.network.message.Message;
import org.qortal.network.RNSCommon.PeerType;
import org.qortal.utils.ExecuteProduceConsume.Task;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

public interface Peer {

    boolean sendMessage(Message message);
    void disconnect(String reason);
    boolean isOutbound();
    void setChainTipSummaries(List<BlockSummaryData> chainTipSummaries);
    BlockSummaryData getChainTipData();
    void setChainTipData(BlockSummaryData chainTipData);
    PeerData getPeerData();
    String getPeerIndexString();
    boolean hasActivePeerLink();
    void startPings();
    default boolean isLocal() { return false; }
    void shutdown();
    long getConnectionAge();
    long getMaxConnectionAge();
    boolean isSyncInProgress();
    void setSyncInProgress(boolean b);
    boolean canUseCachedCommonBlockData();
    void setCommonBlockData(CommonBlockData cbd);
    CommonBlockData getCommonBlockData();
    void setLastTooDivergentTime(Long time);
    Message getResponse(Message getBlockSummariesMessage) throws InterruptedException;
    void setLastPing(long l);
    void setIsDataPeer(boolean b);
    boolean isDataPeer();
    Task getMessageTask();
    Task getPingTask(Long now);
    List<byte[]> getPendingSignatureRequests();
    void removePendingSignatureRequest(byte[] signature);
    void setPeersConnectionTimestamp(long peersConnectionTimestamp);
    long getConnectionEstablishedTime();
    Long getLastTooDivergentTime();
    boolean sendMessageWithTimeoutNow(Message message, int timeout);
    void setPeersNodeId(String nodeAddress);
    String getPeersNodeId();
    boolean isStopping();

    // legacy from old Peer implementation as class (now IPPeer)
    public static final Pattern VERSION_PATTERN = Pattern.compile(Controller.VERSION_PREFIX
            + "(\\d{1,3})\\.(\\d{1,5})\\.(\\d{1,5})");
    Long getPeersVersion();
    default Handshake getHandshakeStatus(){
        return null;
    }
    default Long getLastPing() {
        return null;
    }
    default Long getConnectionTimestamp() {
        return null;
    }
    default Long getPeersConnectionTimestamp() {
        return null;
    }
    String getPeersVersionString();
    default UUID getPeerConnectionId() {
        return null;
    };
    // legacy (from) Handshake
    void setPeersVersion(String versionString, long version);
    boolean isAtLeastVersion(String minPeerVersion);
    default void setPeersPublicKey(byte[] peersPublicKey) { return; }
    default void setPeersChallenge(byte[] peersChallenge) { return; }
    default byte[] getOurChallenge() { return  null; }
    default byte[] getPeersPublicKey() { return new byte[]{}; }
    default byte[] getPeersChallenge() { return null; }
    default void setHandshakeStatus(Handshake handshake) { return; }
    default InetSocketAddress getResolvedAddress() { return null; }
    Message getResponseWithTimeout(Message getArbitraryDataFileMessage, int arbitraryRequestTimeout) throws InterruptedException;
    default void setMaxConnectionAge(long l) { return; };
    default void readChannel() throws IOException { return; };
    default boolean writeChannel() throws IOException { return false; };
    void addPendingSignatureRequest(byte[] signature);
    default SocketChannel connect() { return null; }
    default SocketChannel getSocketChannel() { return null; }
    default boolean hasReachedMaxConnectionAge() { return false; }
    default void resetHandshakeMessagePending() { return; }
    PeerType getPeerType();
}

