package org.qortal.crosschain;

import com.google.common.hash.HashCode;
import com.google.protobuf.ByteString;
import io.grpc.*;
import io.grpc.netty.*;
import io.grpc.stub.StreamObserver;
import org.junit.Test;
import org.qortal.test.common.Common;
import pirate.wallet.sdk.rpc.CompactTxStreamerGrpc;
import pirate.wallet.sdk.rpc.Service.*;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/** Reproduce public Musiqo funding evidence using loopback RPCs; never access a wallet. */
public class PirateAddressIndexTests extends Common {
    private static final String ADDRESS = "bRYgz6zd6rSvP9SrDv5Yf49eewJT4A9edU";
    private static final String ALIAS = "RN6cNPYRMLs1wJxgcRkaXvLyvKrsGKbXWa";
    private static final String TXID = "9e20ff92f96a4609bfeabe559593445d04febfba46ad429862682c019a8f0087";
    private static final long VALUE = 500010000L;
    private static final int HEIGHT = 4220443;

    @Test public void musiqoConfirmedFundingIsFoundDespiteWrongIndexBucket() throws Exception {
        try (Fixture f = new Fixture()) {
            f.fund(ALIAS);
            UnspentOutput output = PirateChainHTLC.getUnspentFundingOutput(f.client, ADDRESS, VALUE);
            assertNotNull("Confirmed contract output must remain discoverable", output);
            assertEquals(0, output.index);
            assertEquals(VALUE, output.value);
            assertEquals(HEIGHT, output.height);
            assertEquals(ADDRESS, output.address);
            assertArrayEquals(f.hash.toByteArray(), output.hash);
            assertEquals(BitcoinyHTLC.Status.FUNDED, PirateChainHTLC.determineHtlcStatus(f.client, ADDRESS, VALUE, 3));
            assertEquals(List.of(ADDRESS, ALIAS), f.service.utxoAddresses);
            assertEquals(List.of(ADDRESS, ALIAS), f.service.historyAddresses);
            assertTrue(f.service.allDeadlines);
        }
    }

    @Test public void correctedIndexWorksAndDuplicateBucketsDoNotDuplicateFunds() throws Exception {
        for (boolean both : List.of(false, true)) {
            try (Fixture f = new Fixture()) {
                f.fund(ADDRESS);
                if (both) f.fund(ALIAS);
                assertEquals(1, f.client.getUnspentOutputs(ADDRESS, false).size());
                assertEquals("Verify duplicate outpoint only once", 1, f.service.rawRequests);
                assertEquals(1, f.client.getAddressBitcoinyTransactions(ADDRESS, false).size());
                assertEquals(BitcoinyHTLC.Status.FUNDED, PirateChainHTLC.determineHtlcStatus(f.client, ADDRESS, VALUE));
            }
        }
    }

    @Test public void mixedIndexesCannotHideRefundInEitherDirection() throws Exception {
        for (boolean reversed : List.of(false, true)) {
            try (Fixture f = new Fixture()) {
                f.fund(reversed ? ALIAS : ADDRESS);
                f.service.utxos.clear();
                f.service.history.put(reversed ? ADDRESS : ALIAS, List.of(f.refund(HEIGHT + 10)));
                assertEquals(2, f.client.getAddressBitcoinyTransactions(ADDRESS, false).size());
                assertNull(PirateChainHTLC.getUnspentFundingOutput(f.client, ADDRESS, VALUE));
                assertEquals(BitcoinyHTLC.Status.REFUNDED, PirateChainHTLC.determineHtlcStatus(f.client, ADDRESS, VALUE, 3));
            }
        }
    }

    @Test public void aliasSpendStillRequiresRequestedConfirmations() throws Exception {
        try (Fixture f = new Fixture()) {
            f.fund(ADDRESS);
            f.service.utxos.clear();
            f.service.history.put(ALIAS, List.of(f.refund(f.service.tip - 1)));
            assertEquals(BitcoinyHTLC.Status.REFUND_IN_PROGRESS,
                    PirateChainHTLC.determineHtlcStatus(f.client, ADDRESS, VALUE, 3));
            assertEquals(BitcoinyHTLC.Status.REFUNDED,
                    PirateChainHTLC.determineHtlcStatus(f.client, ADDRESS, VALUE));
        }
    }

    @Test public void sameHashP2pkhOutputsNeverBecomeContractFunds() throws Exception {
        try (Fixture f = new Fixture()) {
            byte[] p2pkh = HashCode.fromString("76a9148c99562ea007b0d503c1285a84794159c08eb49e88ac").asBytes();
            f.service.history.put(ALIAS, List.of(RawTransaction.newBuilder().setHeight(HEIGHT)
                    .setData(ByteString.copyFrom(transaction(new byte[0], p2pkh))).build()));
            f.service.utxos.put(ALIAS, List.of(f.utxo(ALIAS).toBuilder().setScript(ByteString.copyFrom(p2pkh)).build()));
            assertTrue(f.client.getUnspentOutputs(ADDRESS, false).isEmpty());
            assertEquals(0, f.service.rawRequests);
            assertTrue(f.client.getAddressBitcoinyTransactions(ADDRESS, false).isEmpty());
            assertEquals(BitcoinyHTLC.Status.UNFUNDED, PirateChainHTLC.determineHtlcStatus(f.client, ADDRESS, VALUE));
        }
    }

    @Test public void aliasOutpointsMustMatchRawValueIndexAndScript() throws Exception {
        for (String mismatch : List.of("value", "index", "script")) {
            try (Fixture f = new Fixture()) {
                f.fund(ALIAS);
                GetAddressUtxosReply.Builder reply = f.utxo(ALIAS).toBuilder();
                if (mismatch.equals("value")) reply.setValueZat(VALUE + 1);
                if (mismatch.equals("index")) reply.setIndex(2);
                if (mismatch.equals("script")) f.service.raw = transaction(new byte[0], new byte[]{0x51});
                f.service.utxos.put(ALIAS, List.of(reply.build()));
                try { f.client.getUnspentOutputs(ADDRESS, false); fail("Accepted mismatched " + mismatch); }
                catch (ForeignBlockchainException.NetworkException expected) { }
            }
        }
    }

    @Test public void incompleteAliasHistoryCannotReportPrimaryFundingAsUnspent() throws Exception {
        try (Fixture f = new Fixture()) {
            f.fund(ADDRESS);
            f.service.failAlias = true;
            try { PirateChainHTLC.determineHtlcStatus(f.client, ADDRESS, VALUE); fail("Incomplete history must not hide a spend"); }
            catch (ForeignBlockchainException.NetworkException expected) { }
        }
    }

    @Test public void conflictingDuplicateOutpointsAndHeightsAreRejected() throws Exception {
        try (Fixture f = new Fixture()) {
            f.fund(ADDRESS); f.fund(ALIAS);
            f.service.utxos.put(ALIAS, List.of(f.utxo(ALIAS).toBuilder().setHeight(HEIGHT + 1).build()));
            try { f.client.getUnspentOutputs(ADDRESS, false); fail(); }
            catch (ForeignBlockchainException.NetworkException expected) { }
            f.service.history.put(ALIAS, List.of(f.funding.toBuilder().setHeight(HEIGHT + 1).build()));
            try { f.client.getAddressBitcoinyTransactions(ADDRESS, false); fail(); }
            catch (ForeignBlockchainException.NetworkException expected) { }
        }
    }

    @Test public void ordinaryAddressStillUsesOneLookup() throws Exception {
        try (Fixture f = new Fixture()) {
            f.client.getUnspentOutputs(ALIAS, false);
            f.client.getAddressBitcoinyTransactions(ALIAS, false);
            assertEquals(List.of(ALIAS), f.service.utxoAddresses);
            assertEquals(List.of(ALIAS), f.service.historyAddresses);
        }
    }

    @Test public void spentLegacyDepositBindsTheExactReferencedRawTransaction() throws Exception {
        try (Fixture f = new Fixture()) {
            RawTransaction spent = f.refund(HEIGHT + 2);
            f.service.history.put(ALIAS, List.of(f.funding, spent));
            // Duplicate alias entries must not manufacture ambiguous deposits.
            f.service.history.put(ADDRESS, List.of(f.funding, spent));
            assertTrue(f.client.getUnspentOutputs(ADDRESS, false).isEmpty());
            UnspentOutput recovered = PirateChainHTLC.recoverSpentFundingOutput(f.client, ADDRESS, VALUE);
            assertNotNull(recovered); assertArrayEquals(f.hash.toByteArray(), recovered.hash);
            assertEquals(0, recovered.index); assertEquals(VALUE, recovered.value);
            assertEquals(BitcoinyHTLC.Status.REFUNDED, PirateChainHTLC.determineHtlcStatus(
                    f.client, ADDRESS, VALUE, 3, TXID, recovered.index));
        }
    }

    @Test public void spentLegacyDepositCannotBindDifferentBytesReturnedForTheReference() throws Exception {
        try (Fixture f = new Fixture()) {
            f.service.history.put(ALIAS, List.of(f.funding, f.refund(HEIGHT + 2)));
            f.service.raw = f.service.raw.clone(); f.service.raw[12] ^= 1;
            assertNull(PirateChainHTLC.recoverSpentFundingOutput(f.client, ADDRESS, VALUE));
        }
    }

    @Test public void multipleHistoricalDepositsCannotBeSilentlyAssignedToOneLegacyTrade() throws Exception {
        try (Fixture f = new Fixture()) {
            byte[] second = f.service.raw.clone(); second[12] ^= 1;
            f.service.history.put(ALIAS, List.of(f.funding, f.refund(HEIGHT + 2),
                    RawTransaction.newBuilder().setHeight(HEIGHT + 1).setData(ByteString.copyFrom(second)).build()));
            try { PirateChainHTLC.recoverSpentFundingOutput(f.client, ADDRESS, VALUE); fail("Ambiguous history"); }
            catch (ForeignBlockchainException expected) { assertTrue(expected.getMessage().contains("Ambiguous")); }
        }
    }

    private static byte[] transaction(byte[] inputScript, byte[] outputScript) {
        ByteBuffer b = ByteBuffer.allocate(1000).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0x80000006).putInt(0xd884b698).putInt(0x37a5165b).putInt(1795161189).putInt(4221200);
        b.put((byte)(inputScript.length == 0 ? 0 : 1));
        if (inputScript.length != 0) b.put(new byte[32]).putInt(0).put((byte)inputScript.length).put(inputScript).putInt(-2);
        b.put((byte)(outputScript.length == 0 ? 0 : 1));
        if (outputScript.length != 0) b.putLong(VALUE).put((byte)outputScript.length).put(outputScript);
        return Arrays.copyOf(b.array(), b.position());
    }

    private static class Lightd extends CompactTxStreamerGrpc.CompactTxStreamerImplBase {
        final Map<String, List<GetAddressUtxosReply>> utxos = new HashMap<>();
        final Map<String, List<RawTransaction>> history = new HashMap<>();
        final List<String> historyAddresses = new ArrayList<>();
        List<String> utxoAddresses;
        byte[] raw;
        int tip = HEIGHT + 100;
        int rawRequests;
        boolean failAlias;
        boolean allDeadlines = true;
        void deadline() { allDeadlines &= Context.current().getDeadline() != null; }
        @Override public void getLatestBlock(ChainSpec request, StreamObserver<BlockID> out) {
            deadline(); out.onNext(BlockID.newBuilder().setHeight(tip).build()); out.onCompleted();
        }
        @Override public void getAddressUtxos(GetAddressUtxosArg request, StreamObserver<GetAddressUtxosReplyList> out) {
            deadline(); utxoAddresses = new ArrayList<>(request.getAddressesList());
            GetAddressUtxosReplyList.Builder result = GetAddressUtxosReplyList.newBuilder();
            for (String address : request.getAddressesList()) result.addAllAddressUtxos(utxos.getOrDefault(address, List.of()));
            out.onNext(result.build()); out.onCompleted();
        }
        @Override public void getTaddressTxids(TransparentAddressBlockFilter request, StreamObserver<RawTransaction> out) {
            deadline(); historyAddresses.add(request.getAddress());
            assertEquals(1L, request.getRange().getStart().getHeight());
            if (failAlias && request.getAddress().equals(ALIAS)) { out.onError(Status.UNAVAILABLE.asRuntimeException()); return; }
            for (RawTransaction tx : history.getOrDefault(request.getAddress(), List.of())) out.onNext(tx);
            out.onCompleted();
        }
        @Override public void getTransaction(TxFilter request, StreamObserver<RawTransaction> out) {
            deadline(); ++rawRequests;
            out.onNext(RawTransaction.newBuilder().setHeight(HEIGHT).setData(ByteString.copyFrom(raw)).build()); out.onCompleted();
        }
    }

    private static class Fixture implements AutoCloseable {
        final Lightd service = new Lightd();
        final Server server;
        final ManagedChannel channel;
        final PirateLightClient client = new PirateLightClient(UUID.randomUUID().toString(), "genesis", List.of(), Map.of(), () -> 1);
        final ByteString hash;
        final RawTransaction funding;
        final byte[] script;
        Fixture() throws Exception {
            byte[] raw;
            try (var input = getClass().getResourceAsStream("/crosschain/pirate/musiqo-funding-9e20ff92.hex")) {
                assertNotNull(input); raw = HashCode.fromString(new String(input.readAllBytes(), StandardCharsets.US_ASCII).trim()).asBytes();
            }
            service.raw = raw;
            script = HashCode.fromString(PirateChainTransaction.read(raw).transaction.outputs.get(0).scriptPubKey).asBytes();
            byte[] id = HashCode.fromString(TXID).asBytes(); com.google.common.primitives.Bytes.reverse(id); hash = ByteString.copyFrom(id);
            funding = RawTransaction.newBuilder().setHeight(HEIGHT).setData(ByteString.copyFrom(raw)).build();
            server = NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0)).addService(service).build().start();
            channel = NettyChannelBuilder.forAddress("127.0.0.1", server.getPort()).usePlaintext().build();
            for (String name : List.of("channel", "currentServer")) {
                Field field = PirateLightClient.class.getDeclaredField(name); field.setAccessible(true);
                field.set(client, name.equals("channel") ? channel : new PirateLightClient.Server("127.0.0.1", ChainableServer.ConnectionType.TCP, server.getPort()));
            }
        }
        GetAddressUtxosReply utxo(String address) {
            return GetAddressUtxosReply.newBuilder().setAddress(address).setTxid(hash).setHeight(HEIGHT)
                    .setIndex(0).setValueZat(VALUE).setScript(ByteString.copyFrom(script)).build();
        }
        void fund(String address) { service.utxos.put(address, List.of(utxo(address))); service.history.put(address, List.of(funding)); }
        RawTransaction refund(int height) throws Exception {
            byte[] redeem = HashCode.fromString(PirateChainTransaction.read(service.raw).transaction.outputs.get(1).scriptPubKey.substring(6)).asBytes();
            byte[] signature = ByteBuffer.allocate(redeem.length + 5).put(new byte[]{1, 1, 0x51, 0x4c, (byte)redeem.length}).put(redeem).array();
            byte[] raw = transaction(signature, new byte[0]);
            System.arraycopy(hash.toByteArray(), 0, raw, 21, 32);
            return RawTransaction.newBuilder().setHeight(height).setData(ByteString.copyFrom(raw)).build();
        }
        @Override public void close() throws Exception {
            client.closeCurrentConnection(); channel.shutdownNow().awaitTermination(3, TimeUnit.SECONDS);
            server.shutdownNow().awaitTermination(3, TimeUnit.SECONDS);
        }
    }
}
