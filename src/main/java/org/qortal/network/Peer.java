package org.qortal.network;

//import java.io.IOException;
import java.net.InetAddress;
//import java.net.InetSocketAddress;
//import java.net.SocketTimeoutException;
//import java.net.StandardSocketOptions;
//import java.net.UnknownHostException;
//import java.nio.ByteBuffer;
//import java.nio.channels.SelectionKey;
//import java.nio.channels.SocketChannel;
//import java.security.SecureRandom;
//import java.util.ArrayList;
//import java.util.Arrays;
//import java.util.Collections;
//import java.util.Comparator;
//import java.util.Iterator;
//import java.util.List;
//import java.util.Map;
//import java.util.Random;
//import java.util.UUID;
//import java.util.concurrent.ArrayBlockingQueue;
//import java.util.concurrent.BlockingQueue;
//import java.util.concurrent.ConcurrentHashMap;
//import java.util.concurrent.LinkedBlockingQueue;
//import java.util.concurrent.TimeUnit;
//import java.util.concurrent.atomic.AtomicInteger;
//import java.util.concurrent.atomic.LongAdder;
//import java.util.regex.Matcher;
//import java.util.regex.Pattern;
//
//import org.apache.logging.log4j.LogManager;
//import org.apache.logging.log4j.Logger;
import org.qortal.controller.Controller;
import org.qortal.data.block.BlockSummaryData;
import org.qortal.data.block.CommonBlockData;
import org.qortal.data.network.PeerData;
import org.qortal.network.helper.PeerDownloadSpeedTracker;
import org.qortal.network.message.Message;
import org.qortal.network.RNSCommon.PeerMetaType;
import org.qortal.network.helper.PeerCapabilities;
//import org.qortal.network.helper.PeerCapabilities;
//import org.qortal.network.helper.PeerDownloadSpeedTracker;
//import org.qortal.network.message.ArbitraryDataFileMessage;
//import org.qortal.network.message.ChallengeMessage;
//import org.qortal.network.message.GetArbitraryDataFileMessage;
//import org.qortal.network.message.Message;
//import org.qortal.network.message.MessageException;
import org.qortal.network.message.MessageType;
//import org.qortal.network.task.MessageTask;
//import org.qortal.network.task.PingTask;
//import org.qortal.settings.Settings;
//import org.qortal.utils.Base58;
import org.qortal.utils.ExecuteProduceConsume.Task;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

public interface Peer {
    // harmonizing v6.1
    static int FETCH_BLOCKS_TIMEOUT = 10000;
    static int SYNC_RESPONSE_TIMEOUT = 4_000;
    static final int NETWORK = 0;
    static final int NETWORKDATA = 1;
    default boolean isAwaitingHelloV2Response() { return false; }
    default void setHandshakeResponseValidated(boolean handshakeResponseValidated) { return; }
    default boolean tryCompleteHandshake() { return false; }
    default void setHandshakeResponseSent(boolean handshakeResponseSent) { return; }
    default void setPeersCapabilities(PeerCapabilities capabilities) { return; }
    default void setAwaitingHelloV2Response(boolean awaitingHelloV2Response) { return; }
    default int getPeerType() { return NETWORK; }
    default void setPeerType(int peertype) { return; }
    default Object getPeerCapability(String capName) {  return null; }
    // Note: possibly turn into non-default ie implement in ReticulumPeer.
    default boolean hasStuckWrite(long timeoutMs) { return false; }
    default String getStuckWriteInfo() { return null; }
    void QDNUse();
    long getLastQDNUse();
    static boolean isAddressLocal(InetAddress address) {
        return address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress();
    }
    static boolean addressEquals(InetSocketAddress knownAddress, InetSocketAddress peerAddress) {
        if (knownAddress.getPort() != peerAddress.getPort()) {
            return false;
        }
        return knownAddress.getHostString().equalsIgnoreCase(peerAddress.getHostString());
    }
    PeerCapabilities getPeersCapabilities();
    PeerDownloadSpeedTracker getDownloadSpeedTracker();
    int getSendQueueSize();
    int getSendQueueCapacity();
    boolean sendPreSerializedMessage(int messageId, MessageType messageType, byte[] searizedByte, int timeout) throws IOException;

    // move Peer to interface
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
    Task getMessageTask(int peerType);
    Task getPingTask(Long now);
    List<byte[]> getPendingSignatureRequests();
    void removePendingSignatureRequest(byte[] signature);
    void setPeersConnectionTimestamp(Long peersConnectionTimestamp);
    long getConnectionEstablishedTime();
    Long getLastTooDivergentTime();
    boolean sendMessageWithTimeout(Message message, int timeout) throws IOException;
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
    default SocketChannel connect(int peerType) { return null; }
    default SocketChannel getSocketChannel() { return null; }
    default boolean hasReachedMaxConnectionAge() { return false; }
    default void resetHandshakeMessagePending() { return; }
    PeerMetaType getPeerMetaType();

}

