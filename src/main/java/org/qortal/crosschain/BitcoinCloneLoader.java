package org.qortal.crosschain;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.net.InetAddresses;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Utils;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Strict local-file loader. JSON selects a closed protocol profile, never a Java class. */
public final class BitcoinCloneLoader {
    private static final int MAX_BYTES = 1024 * 1024;
    private BitcoinCloneLoader() { }

    public static List<BitcoinCloneDefinition> load(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            return load(input);
        }
    }

    public static List<BitcoinCloneDefinition> load(InputStream input) throws IOException {
        byte[] bytes = input.readNBytes(MAX_BYTES + 1);
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("Definition file exceeds 1 MiB");
        ObjectMapper mapper = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        mapper.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        // Bound nesting before building a recursive tree, including on older Jackson versions.
        try (JsonParser parser = mapper.getFactory().createParser(bytes)) {
            int depth = 0, tokens = 0;
            JsonToken token;
            while ((token = parser.nextToken()) != null) {
                if (++tokens > 20000) throw new IllegalArgumentException("Too many definition fields");
                if (token == JsonToken.START_ARRAY || token == JsonToken.START_OBJECT) {
                    if (++depth > 8) throw new IllegalArgumentException("Definition nesting too deep");
                } else if (token == JsonToken.END_ARRAY || token == JsonToken.END_OBJECT) --depth;
            }
        }
        JsonNode root = mapper.readTree(bytes);
        fields(root, "schemaVersion", "chains");
        exact(root, "schemaVersion", 1);
        JsonNode chains = root.get("chains");
        if (!chains.isArray() || chains.size() > 32) throw new IllegalArgumentException("Expected at most 32 chains");
        List<BitcoinCloneDefinition> result = new ArrayList<>();
        for (JsonNode chain : chains) result.add(parse(chain));
        return List.copyOf(result);
    }

    private static BitcoinCloneDefinition parse(JsonNode c) {
        fields(c, "id", "name", "symbol", "network", "protocol", "derivation", "bip44CoinType", "enabled",
                "genesisHash", "genesisHeader", "p2pkhPrefix", "p2shPrefix", "wifPrefix", "xpubVersion", "xprvVersion",
                "bech32Hrp", "networkMagic", "p2pPort", "coinbaseMaturity", "maxMoneyAtomic", "feePerKb",
                "minimumRelayFeePerKb", "dustThreshold", "maxTransactionBytes", "transactionVersion",
                "transactionFormat", "signatureHash", "headerMode", "capabilities", "electrumServers");
        exact(c, "protocol", "BITCOIN_WIRE_SHA256D");
        exact(c, "derivation", "QORTAL_BIP32_MASTER_P2PKH");
        exact(c, "transactionFormat", "BITCOIN_LEGACY_WITH_WITNESS_PARSING");
        exact(c, "signatureHash", "BITCOIN_ALL");
        exact(c, "headerMode", "ELECTRUM_TRUSTED_80");
        // These are bitcoinj's implemented standard wallet rules, not arbitrary promised overrides.
        exact(c, "transactionVersion", 1);
        exact(c, "minimumRelayFeePerKb", 1000);
        exact(c, "dustThreshold", 546);
        exact(c, "maxTransactionBytes", 100000);
        int p2pkh = (int) number(c, "p2pkhPrefix", 0, 255);
        int p2sh = (int) number(c, "p2shPrefix", 0, 255);
        int wif = (int) number(c, "wifPrefix", 0, 255);
        if (p2pkh == p2sh || wif == p2pkh || wif == p2sh) throw new IllegalArgumentException("Conflicting address/key prefixes");
        int xpub = version(c, "xpubVersion"), xprv = version(c, "xprvVersion");
        if (xpub == xprv) throw new IllegalArgumentException("Conflicting extended key versions");
        String hrp = c.get("bech32Hrp").isNull() ? null : string(c, "bech32Hrp");
        if (hrp != null && !hrp.matches("[a-z0-9]{1,20}")) throw new IllegalArgumentException("Invalid Bech32 HRP");
        String header = string(c, "genesisHeader");
        if (!header.matches("[0-9a-f]{160}")) throw new IllegalArgumentException("An 80-byte genesis header is required");
        byte[] raw = Utils.HEX.decode(header);
        String hash = string(c, "genesisHash");
        if (!Sha256Hash.wrapReversed(Sha256Hash.hashTwice(raw)).toString().equals(hash))
            throw new IllegalArgumentException("Genesis header/hash mismatch for SHA256d profile");
        String id = string(c, "id");
        BitcoinCloneDefinition.Network network = BitcoinCloneDefinition.Network.valueOf(string(c, "network"));
        long maxMoney = number(c, "maxMoneyAtomic", 1, Long.MAX_VALUE / 4);
        long fee = number(c, "feePerKb", 1000, 100_000_000);
        if (maxMoney < fee || maxMoney < 546) throw new IllegalArgumentException("Monetary limits conflict with wallet policy");
        BitcoinCloneParameters params = new BitcoinCloneParameters(id + "." + network, p2pkh, p2sh, wif, xpub, xprv,
                hrp, number(c, "networkMagic", 1, 0xffffffffL), (int) number(c, "p2pPort", 1, 65535),
                raw, maxMoney, 546, (int) number(c, "coinbaseMaturity", 1, 10000));
        JsonNode caps = c.get("capabilities"), servers = c.get("electrumServers");
        if (!caps.isArray() || !servers.isArray()) throw new IllegalArgumentException("Capabilities and servers must be arrays");
        Set<BitcoinCloneDefinition.Capability> capabilities = EnumSet.noneOf(BitcoinCloneDefinition.Capability.class);
        for (JsonNode cap : caps) {
            if (!cap.isTextual() || !capabilities.add(BitcoinCloneDefinition.Capability.valueOf(cap.textValue())))
                throw new IllegalArgumentException("Invalid or duplicate capability");
        }
        List<BitcoinCloneDefinition.Endpoint> endpoints = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        for (JsonNode server : servers) {
            fields(server, "host", "transport", "port");
            BitcoinCloneDefinition.Endpoint endpoint = new BitcoinCloneDefinition.Endpoint(string(server, "host"),
                    ChainableServer.ConnectionType.valueOf(string(server, "transport")), (int) number(server, "port", 1, 65535));
            if (!unique.add(endpoint.toServer().toString())) throw new IllegalArgumentException("Duplicate Electrum endpoint");
            endpoints.add(endpoint);
        }
        if (!c.get("enabled").isBoolean()) throw new IllegalArgumentException("enabled must be boolean");
        return new BitcoinCloneDefinition(id, string(c, "name"), string(c, "symbol"), network,
                BitcoinCloneDefinition.Protocol.BITCOIN_WIRE_SHA256D, (int) number(c, "bip44CoinType", 0, 0x7fffffff),
                hash, fee, 1000, 0, capabilities, endpoints, c.get("enabled").booleanValue(), params, false);
    }

    static void validateDestination(BitcoinCloneDefinition.Endpoint endpoint, BitcoinCloneDefinition.Network network) {
        if (!InetAddresses.isInetAddress(endpoint.host)) throw new IllegalArgumentException("Configured servers require literal IPs");
        InetAddress ip = InetAddresses.forString(endpoint.host); // Never resolves DNS.
        if (network == BitcoinCloneDefinition.Network.REGTEST) {
            if (!endpoint.host.equals("127.0.0.1") && !endpoint.host.equals("::1"))
                throw new IllegalArgumentException("Regtest endpoints must be isolated loopback addresses");
        } else {
            byte[] address = ip.getAddress();
            if (ip.isAnyLocalAddress() || ip.isLoopbackAddress() || ip.isLinkLocalAddress()
                    || ip.isSiteLocalAddress() || ip.isMulticastAddress()
                    || (address.length == 16 && ((address[0] & 0xfe) == 0xfc || address[0] == 0))
                    || (address.length == 4 && ((address[0] & 0xff) == 0
                        || (address[0] & 0xff) >= 224
                        || ((address[0] & 0xff) == 100 && (address[1] & 0xc0) == 64))))
                throw new IllegalArgumentException("Mainnet/testnet endpoints must use public IPs");
            if (endpoint.transport != ChainableServer.ConnectionType.SSL)
                throw new IllegalArgumentException("Configured mainnet/testnet endpoints require SSL");
        }
    }

    private static void fields(JsonNode node, String... keys) {
        if (node == null || !node.isObject()) throw new IllegalArgumentException("Expected definition object");
        Set<String> expected = new HashSet<>(Arrays.asList(keys));
        node.fieldNames().forEachRemaining(key -> {
            if (!expected.remove(key)) throw new IllegalArgumentException("Unrecognized definition field: " + key);
        });
        if (!expected.isEmpty()) throw new IllegalArgumentException("Missing definition fields: " + expected);
    }

    private static String string(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || !value.isTextual() || value.textValue().isBlank() || value.textValue().length() > 200)
            throw new IllegalArgumentException("Invalid string field: " + key);
        return value.textValue();
    }

    private static long number(JsonNode node, String key, long min, long max) {
        JsonNode value = node.get(key);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < min || value.longValue() > max)
            throw new IllegalArgumentException("Invalid integer field: " + key);
        return value.longValue();
    }

    private static int version(JsonNode node, String key) {
        String value = string(node, key);
        if (!value.matches("[0-9a-f]{8}") || value.equals("00000000")) throw new IllegalArgumentException("Invalid key version: " + key);
        return (int) Long.parseLong(value, 16);
    }

    private static void exact(JsonNode node, String key, String expected) {
        if (!expected.equals(string(node, key))) throw new IllegalArgumentException("Unsupported " + key);
    }

    private static void exact(JsonNode node, String key, long expected) {
        if (number(node, key, expected, expected) != expected) throw new IllegalArgumentException("Unsupported " + key);
    }
}
