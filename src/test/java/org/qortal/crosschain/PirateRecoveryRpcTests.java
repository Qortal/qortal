package org.qortal.crosschain;

import com.google.protobuf.ByteString;
import io.grpc.*;
import io.grpc.netty.*;
import io.grpc.stub.StreamObserver;
import org.junit.Test;
import org.qortal.test.common.Common;
import org.qortal.utils.Base58;
import pirate.wallet.sdk.rpc.*;
import pirate.wallet.sdk.rpc.CompactFormats.CompactBlock;
import pirate.wallet.sdk.rpc.Service.*;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Real loopback RPCs, synthetic contracts and no wallet/native/broadcast access. */
public class PirateRecoveryRpcTests extends Common {
    private static void set(Object object, String name, Object value) throws Exception {
        Field field = PirateLightClient.class.getDeclaredField(name);
        field.setAccessible(true); field.set(object, value);
    }

    private static final class Lightd extends CompactTxStreamerGrpc.CompactTxStreamerImplBase {
        final Map<String, Boolean> deadlines = new HashMap<>();
        final byte[] script = ByteBuffer.allocate(23).put((byte) 0xa9).put((byte) 20).put(new byte[20]).put((byte) 0x87).array();
        Status missing = Status.UNKNOWN.withDescription("-5: No such mempool or blockchain transaction. Use gettransaction for wallet transactions.");
        boolean stallUtxos;
        void record(String method) { deadlines.put(method, Context.current().getDeadline() != null); }
        @Override public void getLatestBlock(ChainSpec request, StreamObserver<BlockID> out) {
            record("latest"); out.onNext(BlockID.newBuilder().setHeight(2000000).build()); out.onCompleted();
        }
        @Override public void getTransaction(TxFilter request, StreamObserver<RawTransaction> out) {
            record("transaction"); out.onError(missing.asRuntimeException());
        }
        @Override public void getAddressUtxos(GetAddressUtxosArg request, StreamObserver<GetAddressUtxosReplyList> out) {
            record("utxos");
            if (stallUtxos) return;
            out.onNext(GetAddressUtxosReplyList.newBuilder().addAddressUtxos(GetAddressUtxosReply.newBuilder()
                    .setAddress(request.getAddresses(0)).setTxid(ByteString.copyFrom(new byte[32])).setIndex(2)
                    .setScript(ByteString.copyFrom(script)).setValueZat(100010000L).setHeight(90)).build());
            out.onCompleted();
        }
        @Override public void getTaddressTxids(TransparentAddressBlockFilter request, StreamObserver<RawTransaction> out) {
            record("history");
            if (request.getRange().getStart().getHeight() <= 90) {
                ByteBuffer raw = ByteBuffer.allocate(100).order(ByteOrder.LITTLE_ENDIAN);
                raw.putInt(0x80000004).putInt(0x892f2085).put((byte) 0).put((byte) 1)
                        .putLong(100010000L).put((byte) script.length).put(script).putInt(1).putInt(100);
                out.onNext(RawTransaction.newBuilder().setHeight(90)
                        .setData(ByteString.copyFrom(Arrays.copyOf(raw.array(), raw.position()))).build());
            }
            out.onCompleted();
        }
        @Override public void getBlockRange(BlockRange request, StreamObserver<CompactBlock> out) {
            record("blocks");
            for (long h = request.getStart().getHeight(); h <= request.getEnd().getHeight(); h++)
                out.onNext(CompactBlock.newBuilder().setHeight(h).setTime(1700000000).build());
            out.onCompleted();
        }
    }

    private static final class Client extends PirateLightClient {
        Client() { super("PirateChain-MAIN", "genesis", List.of(), new EnumMap<>(ChainableServer.ConnectionType.class), () -> 1); }
        @Override protected long rpcTimeoutSeconds() { return 1; }
        @Override protected ManagedChannel buildProbeChannel(ChainableServer server) {
            return NettyChannelBuilder.forAddress("127.0.0.1", server.getPort()).usePlaintext().build();
        }
        @Override protected LightdInfo fetchLightdInfo(ManagedChannel channel) {
            return LightdInfo.newBuilder().setChainName("main").setBlockHeight(2000000).build();
        }
        @Override protected BlockID fetchLatestBlock(ManagedChannel channel) {
            return BlockID.newBuilder().setHeight(2000000).build();
        }
    }

    private static final class Loopback implements AutoCloseable {
        final Lightd service = new Lightd();
        final Server server;
        final ManagedChannel channel;
        final Client client = new Client();
        Loopback() throws Exception {
            server = NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0)).addService(service).build().start();
            channel = NettyChannelBuilder.forAddress("127.0.0.1", server.getPort()).usePlaintext().build();
            set(client, "channel", channel);
            set(client, "currentServer", new PirateLightClient.Server("127.0.0.1", ChainableServer.ConnectionType.TCP, server.getPort()));
        }
        @Override public void close() throws Exception {
            client.closeCurrentConnection(); channel.shutdownNow().awaitTermination(3, TimeUnit.SECONDS);
            server.shutdownNow().awaitTermination(3, TimeUnit.SECONDS);
        }
    }

    @Test public void definitiveLegacyAndTypedAbsenceAreRecognizedWithoutDiscardingServer() throws Exception {
        try (Loopback rpc = new Loopback()) {
            for (Status status : List.of(rpc.service.missing, Status.NOT_FOUND)) {
                rpc.service.missing = status;
                try { rpc.client.getRawTransaction(new byte[32]); fail(); }
                catch (ForeignBlockchainException.NotFoundException expected) { }
                assertNotNull(rpc.client.getCurrentServer());
            }
        }
    }

    @Test public void unrelatedErrorsNeverAuthorizeReplacement() throws Exception {
        try (Loopback rpc = new Loopback()) {
            for (String message : List.of("backend unavailable", "-5: invalid address", "No such mempool or blockchain transaction")) {
                rpc.service.missing = Status.UNKNOWN.withDescription(message);
                try { rpc.client.getRawTransaction(new byte[32]); fail(); }
                catch (ForeignBlockchainException.NotFoundException wrong) { fail("Ambiguous error classified as absence"); }
                catch (ForeignBlockchainException.NetworkException expected) { }
            }
        }
    }

    @Test public void contractBeforeWalletBirthdayIsStillFundedAndRetainsOutpoint() throws Exception {
        Common.useDefaultSettings();
        try (Loopback rpc = new Loopback()) {
            String address = Base58.encode(ByteBuffer.allocate(25).put((byte) 85).put(new byte[24]).array());
            assertEquals(2, PirateChainHTLC.getUnspentFundingOutput(rpc.client, address, 100010000L).index);
            assertEquals(BitcoinyHTLC.Status.FUNDED, PirateChainHTLC.determineHtlcStatus(rpc.client, address, 100010000L, 3));
        }
    }

    @Test public void recoveryReadsAllCarryDeadlines() throws Exception {
        try (Loopback rpc = new Loopback()) {
            rpc.client.getAddressBitcoinyTransactions("synthetic", false);
            rpc.client.getUnspentOutputs("synthetic", false);
            rpc.client.getBlockTimestamps(100, 11);
            assertEquals(Set.of("latest", "history", "utxos", "blocks"), rpc.service.deadlines.keySet());
            assertFalse(rpc.service.deadlines.containsValue(false));
        }
    }

    @Test public void stalledReadExpiresAndNextAttemptUsesAnotherServer() throws Exception {
        try (Loopback stalled = new Loopback(); Loopback healthy = new Loopback()) {
            stalled.service.stallUtxos = true;
            PirateLightClient.Server next = new PirateLightClient.Server("127.0.0.1", ChainableServer.ConnectionType.TCP, healthy.server.getPort());
            set(stalled.client, "remainingServers", new ArrayList<>(List.of(next)));
            long began = System.nanoTime();
            try { stalled.client.getUnspentOutputs("synthetic", false); fail(); }
            catch (ForeignBlockchainException.NetworkException expected) { }
            assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - began) < 4);
            assertNull(stalled.client.getCurrentServer());
            assertEquals(2000000, stalled.client.getCurrentHeight());
            assertEquals(next, stalled.client.getCurrentServer());
        }
    }

    @Test public void medianUsesElevenBlocksEndingAtTip() throws Exception {
        PirateChain pirate = mock(PirateChain.class, CALLS_REAL_METHODS);
        BitcoinyBlockchainProvider provider = mock(BitcoinyBlockchainProvider.class);
        Field field = Bitcoiny.class.getDeclaredField("blockchainProvider"); field.setAccessible(true); field.set(pirate, provider);
        when(provider.getCurrentHeight()).thenReturn(100);
        when(provider.getBlockTimestamps(90, 11)).thenReturn(new ArrayList<>(List.of(100L, 90L, 91L, 99L, 92L, 98L, 93L, 97L, 94L, 96L, 95L)));
        assertEquals(95, pirate.getMedianBlockTime());
    }
}
