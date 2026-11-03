package org.qortal.controller.arbitrary;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.qortal.arbitrary.ArbitraryDataFile;
import org.qortal.block.BlockChain;
import org.qortal.block.GenesisBlock;
import org.qortal.controller.Controller;
import org.qortal.crypto.Crypto;
import org.qortal.data.arbitrary.ArbitraryDirectConnectionInfo;
import org.qortal.data.arbitrary.ArbitraryFileListResponseInfo;
import org.qortal.data.arbitrary.ArbitraryRelayInfo;
import org.qortal.data.network.PeerData;
import org.qortal.data.transaction.ArbitraryTransactionData;
import org.qortal.data.transaction.BaseTransactionData;
import org.qortal.list.ResourceListManager;
import org.qortal.network.Peer;
import org.qortal.network.PeerAddress;
import org.qortal.network.message.ArbitraryDataFileListMessage;
import org.qortal.network.message.GetArbitraryDataFileListMessage;
import org.qortal.network.message.Message;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryFactory;
import org.qortal.repository.RepositoryManager;
import org.qortal.repository.TransactionRepository;
import org.qortal.settings.Settings;
import org.qortal.utils.Base58;
import org.qortal.utils.NTP;
import org.qortal.utils.Triple;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.security.Security;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.Assert.*;

/** Exercises the actual batched handlers without starting Core, sockets, or a database. */
@RunWith(Parameterized.class)
public class ArbitraryDataFileListRelayTests {
    @Parameterized.Parameters
    public static Collection<Object[]> modes() {
        return Arrays.asList(new Object[][] {{false}, {true}});
    }

    private final boolean relayEnabled;

    public ArbitraryDataFileListRelayTests(boolean relayEnabled) {
        this.relayEnabled = relayEnabled;
    }

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private Settings oldSettings;
    private Object oldLists;
    private Object oldFileManager;
    private Object oldFileListManager;
    private Object oldOffset;
    private Object oldOffsetSet;
    private Object oldStorageManager;
    private Object oldBlockChain;
    private Object oldGenesisBlockData;
    private Object oldGenesisTransactions;
    private Object oldGenesisAssets;
    private Object oldController;
    private boolean addedCryptoProvider;
    private RepositoryFactory oldRepositoryFactory;
    private ArbitraryDataFileManager files;
    private ArbitraryDataFileListManager lists;
    private ArbitraryTransactionData transaction;
    private final List<ArbitraryTransactionData> transactions = new ArrayList<>();
    private Runnable duringLookup = () -> {};
    private final byte[] signature = new byte[64];
    private final byte[] contents = new byte[] {1, 2, 3, 4};
    private final byte[] firstHash = Crypto.digest(contents);
    private final byte[] secondHash = Crypto.digest(new byte[] {5, 6, 7});
    private RecordingPeer requester;
    private RecordingPeer otherRequester;
    private RecordingPeer holder;
    private RecordingPeer otherHolder;

    @Before
    public void setUp() throws Exception {
        oldSettings = (Settings) read(Settings.class, "instance");
        oldLists = read(ResourceListManager.class, "instance");
        oldFileManager = read(ArbitraryDataFileManager.class, "instance");
        oldFileListManager = read(ArbitraryDataFileListManager.class, "instance");
        oldRepositoryFactory = RepositoryManager.getRepositoryFactory();
        oldOffset = read(NTP.class, "offset");
        oldOffsetSet = read(NTP.class, "isOffsetSet");
        oldStorageManager = read(ArbitraryDataStorageManager.class, "instance");
        oldBlockChain = read(BlockChain.class, "instance");
        oldGenesisBlockData = read(GenesisBlock.class, "genesisBlockData");
        oldGenesisTransactions = read(GenesisBlock.class, "transactionsData");
        oldGenesisAssets = read(GenesisBlock.class, "initialAssets");
        oldController = read(Controller.class, "instance");

        Settings settings = construct(Settings.class);
        write(Settings.class, "instance", settings);
        write(settings, "relayModeEnabled", relayEnabled);
        write(settings, "dataPath", temporaryFolder.newFolder("data").getAbsolutePath());
        write(settings, "tempDataPath", temporaryFolder.newFolder("temp").getAbsolutePath());
        write(settings, "listsPath", temporaryFolder.newFolder("lists").getAbsolutePath());
        write(ResourceListManager.class, "instance", null);
        NTP.setFixedOffset(0L);
        Arrays.fill(signature, (byte) 7);
        BaseTransactionData base = new BaseTransactionData(NTP.getTime(), 0, new byte[64], new byte[32], 0L, signature);
        transaction = new ArbitraryTransactionData(base, 5, 100, 0, contents.length,
                "unfollowed-relay-test", "test", ArbitraryTransactionData.Method.PUT, new byte[32],
                ArbitraryTransactionData.Compression.NONE, firstHash, ArbitraryTransactionData.DataType.DATA_HASH,
                null, Collections.emptyList());
        transactions.add(transaction);
        installRepository();

        write(ArbitraryDataFileManager.class, "instance", null);
        write(ArbitraryDataFileListManager.class, "instance", null);
        write(ArbitraryDataStorageManager.class, "instance", null);
        files = ArbitraryDataFileManager.getInstance();
        lists = ArbitraryDataFileListManager.getInstance();
        stopSchedulers(files);
        stopSchedulers(lists);
        requester = new RecordingPeer(12391);
        otherRequester = new RecordingPeer(12392);
        holder = new RecordingPeer(12393);
        otherHolder = new RecordingPeer(12394);
    }

    @After
    public void tearDown() throws Exception {
        if (files != null) stopSchedulers(files);
        if (lists != null) stopSchedulers(lists);
        Object storageManager = read(ArbitraryDataStorageManager.class, "instance");
        if (storageManager != null) stopSchedulers(storageManager);
        RepositoryManager.setRepositoryFactory(oldRepositoryFactory);
        write(ArbitraryDataFileManager.class, "instance", oldFileManager);
        write(ArbitraryDataFileListManager.class, "instance", oldFileListManager);
        write(ArbitraryDataStorageManager.class, "instance", oldStorageManager);
        write(BlockChain.class, "instance", oldBlockChain);
        write(GenesisBlock.class, "genesisBlockData", oldGenesisBlockData);
        write(GenesisBlock.class, "transactionsData", oldGenesisTransactions);
        write(GenesisBlock.class, "initialAssets", oldGenesisAssets);
        write(Controller.class, "instance", oldController);
        write(ResourceListManager.class, "instance", oldLists);
        write(Settings.class, "instance", oldSettings);
        write(NTP.class, "offset", oldOffset);
        write(NTP.class, "isOffsetSet", oldOffsetSet);
        if (addedCryptoProvider) Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME);
    }

    @Test
    public void foreignDirectDiscoveryNeverStartsLocalDownloads() throws Exception {
        request(1, requester);
        reply(holder, 1, true, firstHash, secondHash);
        processReplies();

        assertTrue(downloads().isEmpty());
        assertTrue(directConnections().isEmpty());
        assertFalse(permanentFile(firstHash).exists());
        assertEquals(1, requester.sent.size());
        ArbitraryDataFileListMessage forwarded = decodedReply(requester.sent.get(0));
        assertEquals(1, forwarded.getId());
        assertEquals(Integer.valueOf(2), forwarded.getRequestHops());
        assertTrue(forwarded.isDirectConnectable());
        assertEquals(relayEnabled, forwarded.isRelayPossible());
        assertEquals(relayEnabled ? 2 : 0, relayRoutes().size());
    }

    @Test
    public void foreignRelayOnlyDiscoveryRequiresPayloadRelay() throws Exception {
        request(1, requester);
        reply(holder, 1, false, firstHash);
        processReplies();

        assertTrue(downloads().isEmpty());
        assertTrue(directConnections().isEmpty());
        assertEquals(relayEnabled ? 1 : 0, requester.sent.size());
        assertEquals(relayEnabled ? 1 : 0, relayRoutes().size());
    }

    @Test
    public void foreignDiscoveryNeverPromotesCachedChunks() throws Exception {
        cacheChunk();
        request(1, requester);
        reply(holder, 1, true, firstHash);
        processReplies();

        assertFalse(permanentFile(firstHash).exists());
        assertTrue(downloads().isEmpty());
        assertArrayEquals(contents, files.loadFromRelayCache(Base58.encode(firstHash)));
    }

    @Test
    public void foreignDiscoveryDoesNotBypassStoragePolicyNone() throws Exception {
        write(Settings.getInstance(), "storagePolicy", "NONE");
        request(1, requester);
        reply(holder, 1, true, firstHash);
        processReplies();
        assertTrue(downloads().isEmpty());
    }

    @Test
    public void localDirectDownloadsWorkInBothRelayModes() throws Exception {
        request(1, null);
        reply(holder, 1, true, firstHash, secondHash);
        processReplies();

        assertEquals(2, downloads().size());
        assertEquals(1, directConnections().size());
        assertTrue(relayRoutes().isEmpty());
        assertTrue(requester.sent.isEmpty());
    }

    @Test
    public void localDownloadsCanUseAnotherNodesRelayInBothModes() throws Exception {
        request(1, null);
        reply(holder, 1, false, firstHash);
        processReplies();

        assertEquals(1, downloads().size());
        assertEquals(holder.getPeerData().getAddress().toString(), downloads().get(0).getPeerData().getAddress().toString());
        assertFalse(downloads().get(0).isDirectConnectable());
    }

    @Test
    public void localDownloadsCanReuseCachedChunksInBothModes() throws Exception {
        cacheChunk();
        request(1, null);
        reply(holder, 1, true, firstHash);
        processReplies();

        assertTrue(permanentFile(firstHash).exists());
        assertArrayEquals(contents, permanentFile(firstHash).getBytes());
        assertTrue(downloads().isEmpty());
    }

    @Test
    public void unknownMismatchedAndCompletedRequestsAreIgnored() throws Exception {
        reply(holder, 1, true, firstHash);
        lists.arbitraryDataFileListRequests.put(2, new Triple<>("wrong-signature", requester, NTP.getTime()));
        reply(holder, 2, true, firstHash);
        lists.arbitraryDataFileListRequests.put(3, new Triple<>(null, null, NTP.getTime()));
        reply(holder, 3, true, firstHash);
        processReplies();

        assertTrue(downloads().isEmpty());
        assertTrue(requester.sent.isEmpty());
        assertTrue(relayRoutes().isEmpty());
    }

    @Test
    public void blockedResourcesAreNeitherDownloadedNorForwarded() throws Exception {
        ResourceListManager.getInstance().addToList("blockedNames", transaction.getName(), false);
        request(1, null);
        request(2, requester);
        reply(holder, 1, true, firstHash);
        reply(holder, 2, true, secondHash);
        processReplies();

        assertTrue(downloads().isEmpty());
        assertTrue(requester.sent.isEmpty());
    }

    @Test
    public void unknownTransactionsAreIgnored() throws Exception {
        transactions.clear();
        request(1, requester);
        reply(holder, 1, true, firstHash);
        processReplies();
        assertTrue(downloads().isEmpty());
        assertTrue(requester.sent.isEmpty());
    }

    @Test
    public void localThenForeignRepliesKeepTheirOwnershipAndHashes() throws Exception {
        mixedRequests(false);
    }

    @Test
    public void foreignThenLocalRepliesKeepTheirOwnershipAndHashes() throws Exception {
        mixedRequests(true);
    }

    @Test
    public void differentRequestersForTheSameResourceReceiveTheirOwnReplies() throws Exception {
        request(1, requester);
        request(2, otherRequester);
        reply(holder, 1, true, firstHash);
        reply(otherHolder, 2, true, secondHash);
        processReplies();

        assertTrue(downloads().isEmpty());
        assertEquals(1, requester.sent.size());
        assertEquals(1, otherRequester.sent.size());
        assertEquals(1, requester.sent.get(0).getId());
        assertEquals(2, otherRequester.sent.get(0).getId());
        assertArrayEquals(firstHash, decodedReply(requester.sent.get(0)).getHashes().get(0));
        assertArrayEquals(secondHash, decodedReply(otherRequester.sent.get(0)).getHashes().get(0));
    }

    @Test
    public void eachLocalHolderRetainsItsOwnHashList() throws Exception {
        request(1, null);
        reply(holder, 1, true, firstHash);
        reply(otherHolder, 1, true, secondHash);
        processReplies();

        assertEquals(2, downloads().size());
        assertEquals(Base58.encode(firstHash), downloads().get(0).getHash58());
        assertEquals(holder.getPeerData().getAddress().toString(), downloads().get(0).getPeerData().getAddress().toString());
        assertEquals(Base58.encode(secondHash), downloads().get(1).getHash58());
        assertEquals(otherHolder.getPeerData().getAddress().toString(), downloads().get(1).getPeerData().getAddress().toString());
    }

    @Test
    public void allForeignHoldersCanContributeDisjointChunks() throws Exception {
        request(1, requester);
        reply(holder, 1, true, firstHash);
        reply(otherHolder, 1, true, secondHash);
        processReplies();

        assertTrue(downloads().isEmpty());
        assertEquals(2, requester.sent.size());
        assertArrayEquals(firstHash, decodedReply(requester.sent.get(0)).getHashes().get(0));
        assertArrayEquals(secondHash, decodedReply(requester.sent.get(1)).getHashes().get(0));
        assertEquals(relayEnabled ? 2 : 0, relayRoutes().size());
    }

    @Test
    public void foreignCachedChunksAreNotPromotedByAnOverlappingLocalRequest() throws Exception {
        cacheChunk();
        request(1, requester);
        request(2, null);
        reply(otherHolder, 1, true, firstHash);
        reply(holder, 2, true, secondHash);
        processReplies();

        assertFalse(permanentFile(firstHash).exists());
        assertEquals(1, downloads().size());
        assertEquals(Base58.encode(secondHash), downloads().get(0).getHash58());
    }

    @Test
    public void completingLocalDownloadsPreservesOtherPeersDiscoveryRequests() throws Exception {
        request(1, null);
        request(2, requester);
        lists.arbitraryDataFileListRequests.put(3, new Triple<>("another-signature", null, NTP.getTime()));
        lists.deleteFileListRequestsForSignature(Base58.encode(signature));

        assertNull(lists.arbitraryDataFileListRequests.get(1).getA());
        assertEquals(Base58.encode(signature), lists.arbitraryDataFileListRequests.get(2).getA());
        assertSame(requester, lists.arbitraryDataFileListRequests.get(2).getB());
        assertEquals("another-signature", lists.arbitraryDataFileListRequests.get(3).getA());
        reply(holder, 2, true, firstHash);
        processReplies();
        assertEquals(1, requester.sent.size());
        assertTrue(downloads().isEmpty());
    }

    @Test
    public void requestsCompletedDuringTransactionLookupAreNotDownloaded() throws Exception {
        request(1, null);
        reply(holder, 1, true, firstHash);
        duringLookup = () -> lists.deleteFileListRequestsForSignature(Base58.encode(signature));
        processReplies();
        assertTrue(downloads().isEmpty());
    }

    @Test
    public void incomingRequestsForTheSameResourceKeepTheirRequesterAndHashList() throws Exception {
        prepareServing();
        incomingRequest(requester, 1, firstHash);
        incomingRequest(otherRequester, 2, secondHash);
        invoke(lists, "processNetworkGetArbitraryDataFileListMessage", new Class<?>[0]);

        assertEquals(1, requester.sent.size());
        assertEquals(1, otherRequester.sent.size());
        assertEquals(1, requester.sent.get(0).getId());
        assertEquals(2, otherRequester.sent.get(0).getId());
        assertArrayEquals(firstHash, decodedReply(requester.sent.get(0)).getHashes().get(0));
        assertArrayEquals(secondHash, decodedReply(otherRequester.sent.get(0)).getHashes().get(0));
        assertTrue(downloads().isEmpty());
    }

    @Test
    public void repeatedIncomingRequestIdsDoNotReplaceTheOriginalRequester() throws Exception {
        prepareServing();
        incomingRequest(requester, 1, firstHash);
        incomingRequest(otherRequester, 1, secondHash);
        invoke(lists, "processNetworkGetArbitraryDataFileListMessage", new Class<?>[0]);

        assertEquals(1, requester.sent.size());
        assertTrue(otherRequester.sent.isEmpty());
        assertArrayEquals(firstHash, decodedReply(requester.sent.get(0)).getHashes().get(0));
    }

    private void prepareServing() throws Exception {
        // Load only immutable chain configuration needed by the unstarted Network singletons.
        // No network start method or repository-backed Core lifecycle is invoked.
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
            addedCryptoProvider = true;
        }
        if (BlockChain.getInstance() == null) BlockChain.fileInstance("", null);
        // Both files must be physically present to be advertised.
        new ArbitraryDataFile(contents, signature, false).save();
        new ArbitraryDataFile(new byte[] {5, 6, 7}, signature, false).save();
    }

    private void incomingRequest(RecordingPeer source, int id, byte[] hash) throws Exception {
        Message outgoing = new GetArbitraryDataFileListMessage(signature, Collections.singletonList(hash),
                NTP.getTime(), ArbitraryDataFileListManager.SEARCH_DEPTH_MAX_HOPS, "127.0.0.1:12390");
        outgoing.setId(id);
        Message incoming = GetArbitraryDataFileListMessage.fromByteBuffer(id,
                ByteBuffer.wrap((byte[]) read(outgoing, Message.class, "dataBytes")));
        lists.onNetworkGetArbitraryDataFileListMessage(source, incoming);
    }

    private void mixedRequests(boolean foreignFirst) throws Exception {
        request(1, null);
        request(2, requester);
        if (foreignFirst) reply(otherHolder, 2, true, secondHash);
        reply(holder, 1, true, firstHash);
        if (!foreignFirst) reply(otherHolder, 2, true, secondHash);
        processReplies();

        assertEquals(1, downloads().size());
        assertEquals(Base58.encode(firstHash), downloads().get(0).getHash58());
        assertEquals(holder.getPeerData().getAddress().toString(), downloads().get(0).getPeerData().getAddress().toString());
        assertEquals(1, requester.sent.size());
        assertEquals(2, requester.sent.get(0).getId());
        assertArrayEquals(secondHash, decodedReply(requester.sent.get(0)).getHashes().get(0));
    }

    private void request(int id, Peer requestingPeer) {
        lists.arbitraryDataFileListRequests.put(id, new Triple<>(Base58.encode(signature), requestingPeer, NTP.getTime()));
    }

    private void reply(RecordingPeer source, int id, boolean direct, byte[]... hashes) throws Exception {
        Message outgoing = new ArbitraryDataFileListMessage(signature, Arrays.asList(hashes), NTP.getTime(), 1,
                source.getPeerData().getAddress().toString(), "holder-" + source.getPeerData().getAddress().getPort(), true, direct);
        outgoing.setId(id);
        lists.onNetworkArbitraryDataFileListMessage(source, decodedReply(outgoing));
    }

    private static ArbitraryDataFileListMessage decodedReply(Message outgoing) throws Exception {
        return (ArbitraryDataFileListMessage) ArbitraryDataFileListMessage.fromByteBuffer(outgoing.getId(),
                ByteBuffer.wrap((byte[]) read(outgoing, Message.class, "dataBytes")));
    }

    private void processReplies() throws Exception {
        invoke(lists, "processNetworkArbitraryDataFileListMessage", new Class<?>[0]);
    }

    private void cacheChunk() throws Exception {
        invoke(files, "saveToRelayCache", new Class<?>[] {String.class, byte[].class}, Base58.encode(firstHash), contents);
    }

    private ArbitraryDataFile permanentFile(byte[] hash) throws Exception {
        return ArbitraryDataFile.fromHash(hash, signature);
    }

    @SuppressWarnings("unchecked")
    private List<ArbitraryFileListResponseInfo> downloads() throws Exception {
        return (List<ArbitraryFileListResponseInfo>) read(files, "arbitraryDataFileHashResponses");
    }

    @SuppressWarnings("unchecked")
    private List<ArbitraryDirectConnectionInfo> directConnections() throws Exception {
        return (List<ArbitraryDirectConnectionInfo>) read(files, "directConnectionInfo");
    }

    @SuppressWarnings("unchecked")
    private List<ArbitraryRelayInfo> relayRoutes() throws Exception {
        return (List<ArbitraryRelayInfo>) read(files, "arbitraryRelayMap");
    }

    private void installRepository() {
        TransactionRepository transactionRepository = proxy(TransactionRepository.class, (p, method, args) -> {
            if (method.getName().equals("fromSignatures")) {
                duringLookup.run();
                return new ArrayList<>(transactions);
            }
            throw new AssertionError("Unexpected transaction repository call: " + method);
        });
        Repository repository = proxy(Repository.class, (p, method, args) -> {
            if (method.getName().equals("getTransactionRepository")) return transactionRepository;
            if (method.getName().equals("close")) return null;
            throw new AssertionError("Unexpected repository call: " + method);
        });
        RepositoryManager.setRepositoryFactory(proxy(RepositoryFactory.class, (p, method, args) -> {
            if (method.getName().equals("getRepository")) return repository;
            throw new AssertionError("Unexpected factory call: " + method);
        }));
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
    }

    private static <T> T construct(Class<T> type) throws Exception {
        Constructor<T> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static void invoke(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        method.invoke(target, args);
    }

    private static Object read(Object target, String name) throws Exception {
        return read(target, target instanceof Class ? (Class<?>) target : target.getClass(), name);
    }

    private static Object read(Object target, Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target instanceof Class ? null : target);
    }

    private static void write(Object target, String name, Object value) throws Exception {
        Class<?> type = target instanceof Class ? (Class<?>) target : target.getClass();
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target instanceof Class ? null : target, value);
    }

    private static void stopSchedulers(Object manager) throws Exception {
        for (Field field : manager.getClass().getDeclaredFields()) {
            if (ScheduledExecutorService.class.isAssignableFrom(field.getType())) {
                field.setAccessible(true);
                ((ScheduledExecutorService) field.get(manager)).shutdownNow();
            }
        }
    }

    private static class RecordingPeer extends Peer {
        final List<Message> sent = new ArrayList<>();

        RecordingPeer(int port) {
            super(new PeerData(PeerAddress.fromString("127.0.0.1:" + port), 0L, "relay-test"), Peer.NETWORKDATA);
        }

        @Override
        public boolean sendMessage(Message message) {
            sent.add(message);
            return true;
        }
    }
}
