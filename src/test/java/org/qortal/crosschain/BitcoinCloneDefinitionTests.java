package org.qortal.crosschain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.Test;
import org.qortal.test.common.Common;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.*;


public class BitcoinCloneDefinitionTests extends Common {
    interface CheckedAction { void run() throws Exception; }
    static <T extends Throwable> T assertThrows(Class<T> type, CheckedAction action) {
        try { action.run(); } catch (Throwable actual) {
            if (type.isInstance(actual)) return type.cast(actual);
            throw new AssertionError("Expected " + type.getName(), actual);
        }
        throw new AssertionError("Expected " + type.getName());
    }

    static ObjectNode fixture() throws Exception {
        return (ObjectNode) new ObjectMapper().readTree(BitcoinCloneDefinitionTests.class.getResourceAsStream(
                "/crosschain/bitcoinclone/regtest.json"));
    }

    static BitcoinCloneDefinition load(ObjectNode root) throws Exception {
        return BitcoinCloneLoader.load(new ByteArrayInputStream(root.toString().getBytes(StandardCharsets.UTF_8))).get(0);
    }

    @Test public void loadsActualIsolatedRegtestParameters() throws Exception {
        BitcoinCloneDefinition d = load(fixture());
        assertEquals("0f9188f13cb7b2c71f2a335e3a4fc328bf5beb436012afca590b1a11466e2206", d.genesisHash);
        assertEquals(d.genesisHash, d.parameters().getGenesisBlock().getHashAsString());
        assertEquals(111, d.parameters().getAddressHeader());
        assertEquals(196, d.parameters().getP2SHHeader());
        assertEquals(239, d.parameters().getDumpedPrivateKeyHeader());
        assertTrue(d.supports(BitcoinCloneDefinition.Capability.SIGN));
        assertFalse(d.supports(BitcoinCloneDefinition.Capability.HTLC));
    }

    @Test public void rejectsMissingUnknownAndIncorrectlyTypedFields() throws Exception {
        for (String field : List.of("genesisHash", "p2shPrefix", "derivation", "feePerKb", "capabilities", "electrumServers")) {
            ObjectNode root = fixture(); ((ObjectNode) root.get("chains").get(0)).remove(field);
            assertThrows(IllegalArgumentException.class, () -> load(root));
        }
        ObjectNode unknown = fixture(); ((ObjectNode) unknown.get("chains").get(0)).put("className", "java.lang.Runtime");
        assertThrows(IllegalArgumentException.class, () -> load(unknown));
        ObjectNode root = fixture(); ((ObjectNode) root.get("chains").get(0)).put("feePerKb", "1000");
        final ObjectNode typed = root;
        assertThrows(IllegalArgumentException.class, () -> load(typed));
    }

    @Test public void rejectsUnsupportedProtocolRulesAndPlaceholders() throws Exception {
        for (String field : List.of("protocol", "signatureHash", "headerMode", "transactionFormat", "derivation",
                "genesisHash", "genesisHeader", "xpubVersion", "network")) {
            ObjectNode root = fixture(); ((ObjectNode) root.get("chains").get(0)).put(field, "REQUIRED");
            assertThrows(IllegalArgumentException.class, () -> load(root));
        }
        for (String field : List.of("p2pkhPrefix", "p2pPort", "feePerKb", "minimumRelayFeePerKb", "dustThreshold", "transactionVersion")) {
            ObjectNode root = fixture(); ((ObjectNode) root.get("chains").get(0)).put(field, -1);
            assertThrows(IllegalArgumentException.class, () -> load(root));
        }
    }

    @Test public void rejectsCapabilityEscalationAndUnsafeDestinations() throws Exception {
        for (String cap : List.of("HTLC", "ACCT", "TRADEBOT")) {
            ObjectNode root = fixture(); ((com.fasterxml.jackson.databind.node.ArrayNode) root.get("chains").get(0).get("capabilities")).add(cap);
            assertThrows(IllegalArgumentException.class, () -> load(root));
        }
        for (String host : List.of("localhost", "example.org", "169.254.169.254", "10.0.0.1", "8.8.8.8", "127.0.0.2")) {
            ObjectNode root = fixture(); ((ObjectNode) root.get("chains").get(0).get("electrumServers").get(0)).put("host", host);
            assertThrows(IllegalArgumentException.class, () -> load(root));
        }
        ObjectNode root = fixture(); ((ObjectNode) root.get("chains").get(0)).put("network", "MAINNET");
        assertThrows(IllegalArgumentException.class, () -> load(root));
    }

    @Test public void boundsInputAndRejectsDuplicateJsonKeysAndTrailingDocuments() {
        assertThrows(IllegalArgumentException.class, () -> BitcoinCloneLoader.load(new ByteArrayInputStream(new byte[1024 * 1024 + 1])));
        assertThrows(Exception.class, () -> BitcoinCloneLoader.load(new ByteArrayInputStream(
                "{\"schemaVersion\":1,\"schemaVersion\":1,\"chains\":[]}".getBytes(StandardCharsets.UTF_8))));
        assertThrows(Exception.class, () -> BitcoinCloneLoader.load(new ByteArrayInputStream(
                "{\"schemaVersion\":1,\"chains\":[]} {}".getBytes(StandardCharsets.UTF_8))));
        assertThrows(IllegalArgumentException.class, () -> BitcoinCloneLoader.load(new ByteArrayInputStream(
                ("[".repeat(20) + "0" + "]".repeat(20)).getBytes(StandardCharsets.UTF_8))));
    }

    @Test public void registryRejectsIdentityConflictsAndSnapshotsBuilder() throws Exception {
        BitcoinCloneDefinition d = load(fixture());
        BitcoinCloneRegistry.Builder builder = BitcoinCloneRegistry.builder().register(d);
        BitcoinCloneRegistry registry = builder.build();
        assertSame(d, registry.get("fixturecoin", BitcoinCloneDefinition.Network.REGTEST));
        assertEquals(List.of(d), registry.getEnabled());
        assertThrows(IllegalArgumentException.class, () -> builder.register(d));
        assertThrows(UnsupportedOperationException.class, () -> d.servers.clear());
        assertThrows(UnsupportedOperationException.class, () -> d.capabilities.clear());
        assertThrows(UnsupportedOperationException.class, () -> registry.getAll().clear());
        assertFalse(registry.supportedBlockchain(d.id, d.network).isPresent());
        for (String id : List.of("bitcoin", "litecoin", "dogecoin", "digibyte", "ravencoin", "piratechain", "monero")) {
            ObjectNode root = fixture(); ((ObjectNode) root.get("chains").get(0)).put("id", id);
            BitcoinCloneDefinition reserved = load(root);
            assertThrows(IllegalArgumentException.class, () -> BitcoinCloneRegistry.builder().register(reserved));
        }
        ObjectNode conflict = fixture(); ((ObjectNode) conflict.get("chains").get(0)).put("id", "anothercoin");
        assertThrows(IllegalArgumentException.class, () -> builder.register(load(conflict)));
        ObjectNode disabled = fixture(); ((ObjectNode) disabled.get("chains").get(0)).put("enabled", false);
        BitcoinCloneRegistry inactive = BitcoinCloneRegistry.builder().register(load(disabled)).build();
        assertTrue(inactive.getEnabled().isEmpty());
        assertThrows(IllegalStateException.class, () -> inactive.backend(d.id, d.network));
        assertEquals(1, registry.getAll().size());
    }

    @Test public void networksAreSelectedExplicitlyAndBackendsArePerRegistry() throws Exception {
        BitcoinCloneDefinition d = load(fixture());
        BitcoinCloneRegistry first = BitcoinCloneRegistry.builder().register(d).build();
        BitcoinCloneRegistry second = BitcoinCloneRegistry.builder().register(d).build();
        assertNull(first.get(d.id, BitcoinCloneDefinition.Network.MAINNET));
        assertThrows(IllegalArgumentException.class, () -> first.backend(d.id, BitcoinCloneDefinition.Network.MAINNET));
        assertSame(first.backend(d.id, d.network), first.backend(d.id, d.network));
        assertNotSame(first.backend(d.id, d.network), second.backend(d.id, d.network));
    }

    @Test public void sameChainCanRegisterIndependentNetworksWithoutMutatingAnExistingSnapshot() throws Exception {
        BitcoinCloneDefinition regtest = load(fixture());
        ObjectNode root = fixture();
        ObjectNode chain = (ObjectNode) root.get("chains").get(0);
        chain.put("network", "TESTNET");
        chain.putArray("capabilities").add("ADDRESS").add("BALANCE").add("HISTORY");
        ObjectNode server = (ObjectNode) chain.get("electrumServers").get(0);
        server.put("host", "8.8.8.8"); server.put("transport", "SSL");
        // Mine a distinct tiny-difficulty header for this isolated configuration fixture.
        byte[] header = org.bitcoinj.core.Utils.HEX.decode(chain.get("genesisHeader").asText());
        for (long nonce = 3; ; ++nonce) {
            org.bitcoinj.core.Utils.uint32ToByteArrayLE(nonce, header, 76);
            org.bitcoinj.core.Sha256Hash hash = org.bitcoinj.core.Sha256Hash.wrapReversed(org.bitcoinj.core.Sha256Hash.hashTwice(header));
            if (hash.toBigInteger().compareTo(org.bitcoinj.params.RegTestParams.get().getMaxTarget()) <= 0) {
                chain.put("genesisHeader", org.bitcoinj.core.Utils.HEX.encode(header));
                chain.put("genesisHash", hash.toString()); break;
            }
        }
        BitcoinCloneDefinition testnet = load(root);
        BitcoinCloneRegistry.Builder builder = BitcoinCloneRegistry.builder().register(regtest);
        BitcoinCloneRegistry before = builder.build();
        BitcoinCloneRegistry after = builder.register(testnet).build();
        assertEquals(1, before.getAll().size());
        assertEquals(2, after.getAll().size());
        assertSame(regtest, after.get(regtest.id, regtest.network));
        assertSame(testnet, after.get(testnet.id, testnet.network));
        assertNotSame(after.backend(regtest.id, regtest.network), after.backend(testnet.id, testnet.network));
        chain.put("bip44CoinType", 2);
        assertThrows(IllegalArgumentException.class, () -> BitcoinCloneRegistry.builder().register(regtest).register(load(root)));
    }

    @Test public void startupLoadsRelativeFileAndHonorsWalletDisableSettings() throws Exception {
        java.nio.file.Path directory = java.nio.file.Files.createTempDirectory("bitcoinclone-startup-");
        java.nio.file.Path file = directory.resolve("coins.json");
        try {
            java.nio.file.Files.writeString(file, fixture().toString());
            org.qortal.settings.Settings settings = org.mockito.Mockito.mock(org.qortal.settings.Settings.class);
            org.mockito.Mockito.when(settings.getBitcoinNet()).thenReturn(Bitcoin.BitcoinNet.MAIN);
            org.mockito.Mockito.when(settings.getLitecoinNet()).thenReturn(Litecoin.LitecoinNet.TEST3);
            org.mockito.Mockito.when(settings.getBitcoinCloneDefinitions()).thenReturn("coins.json");
            org.mockito.Mockito.when(settings.getUserPath()).thenReturn(directory.toString());
            org.mockito.Mockito.when(settings.isWalletEnabled(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
            org.mockito.Mockito.when(settings.isWalletEnabled("FIX")).thenReturn(false);
            BitcoinCloneRegistry registry = BitcoinCloneSupport.load(settings);
            assertEquals(3, registry.getAll().size());
            assertEquals(2, registry.getEnabled().size());
            assertEquals(SupportedBlockchain.BITCOIN, registry.supportedBlockchain("bitcoin", BitcoinCloneDefinition.Network.MAINNET).get());
            assertEquals(SupportedBlockchain.LITECOIN, registry.supportedBlockchain("litecoin", BitcoinCloneDefinition.Network.TESTNET).get());
            assertThrows(IllegalStateException.class, () -> registry.backend("fixturecoin", BitcoinCloneDefinition.Network.REGTEST));
            java.nio.file.Files.writeString(file, "{}");
            assertThrows(IllegalArgumentException.class, () -> BitcoinCloneSupport.load(settings));
        } finally {
            java.nio.file.Files.deleteIfExists(file); java.nio.file.Files.deleteIfExists(directory);
        }
    }
}
