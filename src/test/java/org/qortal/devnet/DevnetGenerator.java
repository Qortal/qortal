package org.qortal.devnet;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.crypto.Crypto;
import org.qortal.rngit.RngitConfig;
import org.qortal.utils.Base58;

import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates an isolated devnet: a chain config derived from Core's current
 * mainnet {@code blockchain.json}, fresh keys for its genesis accounts, and one
 * settings file per node. See {@code reticulum/rngit-livetest/DEVNET-SETUP.md}.
 * <p>
 * The chain follows mainnet's rules as they apply today: every feature trigger
 * mainnet has passed is active from genesis, windows mainnet has closed are
 * empty, and triggers mainnet keeps in the far future stay there. Fees and
 * reward-share limits are mainnet's current values. Genesis creates the
 * minters (founders at level 10 with a self-share, admins of the minting
 * group), a faucet and named accounts with QORT.
 * <p>
 * Usage: {@code DevnetGenerator --out DIR --hosts h1,h2,h3 [options]}; run
 * through {@code reticulum/rngit-livetest/make-devnet.sh}.
 */
public class DevnetGenerator {

    /** Values at or above these mean "never" in blockchain.json. */
    static final long FAR_FUTURE_TIMESTAMP = 9_000_000_000_000L;
    static final long FAR_FUTURE_HEIGHT = 9_000_000L;

    static final int MINTING_GROUP_ID = 1;

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final SecureRandom RANDOM = new SecureRandom();

    static final class Options {
        Path out;
        Path base;
        List<String> hosts = new ArrayList<>(List.of("127.0.0.1", "127.0.0.1", "127.0.0.1"));
        int startMinutes = 15;
        int apiPort = 63391;
        int listenPort = 63392;
        int dataPort = 63394;
        /** On one machine, each further node gets ports this much higher. */
        int portStep = 0;
        int reticulumPort = 4240;
        /** The first this many nodes are Reticulum gateways, so the mesh survives one going offline. */
        int gateways = 2;
        String networkName = "qortal-devnet";
        String passphrase;
        Map<String, String> accounts = new LinkedHashMap<>(Map.of());
    }

    public static void main(String[] args) throws Exception {
        // Addresses need RIPEMD-160, which Core gets from BouncyCastle
        java.security.Security.insertProviderAt(new org.bouncycastle.jce.provider.BouncyCastleProvider(), 0);
        Options o = parse(args);
        Files.createDirectories(o.out);

        Map<String, Object> keys = new LinkedHashMap<>();
        List<PrivateKeyAccount> minters = new ArrayList<>();
        for (int i = 1; i <= o.hosts.size(); i++) {
            PrivateKeyAccount minter = newAccount();
            minters.add(minter);
            Map<String, Object> entry = describe(minter, "minter on node " + i);
            byte[] rewardSharePrivateKey = minter.getRewardSharePrivateKey(minter.getPublicKey());
            entry.put("mintingKey", Base58.encode(rewardSharePrivateKey));
            entry.put("rewardSharePublicKey", Base58.encode(Crypto.toPublicKey(rewardSharePrivateKey)));
            keys.put("minter" + i, entry);
        }
        Map<String, PrivateKeyAccount> named = new LinkedHashMap<>();
        for (String name : o.accounts.keySet()) {
            PrivateKeyAccount account = newAccount();
            named.put(name, account);
            keys.put(name, describe(account, name));
        }

        Map<String, Object> chain = chain(o, minters, named, keys);
        JSON.writeValue(o.out.resolve("devchain.json").toFile(), chain);

        Path keyFile = o.out.resolve("devnet-keys.json");
        JSON.writeValue(keyFile.toFile(), keys);
        try {
            Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // not a POSIX file system
        }

        for (int i = 0; i < o.hosts.size(); i++) {
            JSON.writeValue(o.out.resolve("settings-node-" + (i + 1) + ".json").toFile(), settings(o, i));
        }
        Files.writeString(o.out.resolve("rngit-config"), rngitConfig());

        long genesis = ((Number) ((Map<?, ?>) chain.get("genesisInfo")).get("timestamp")).longValue();
        System.out.println("Wrote " + o.out.resolve("devchain.json") + " (genesis at " + java.time.Instant.ofEpochMilli(genesis) + ")");
        System.out.println("Wrote " + keyFile + " (private keys: keep it out of git)");
        System.out.println("Wrote settings-node-1.." + o.hosts.size() + ".json");
        System.out.println("Wrote rngit-config (copy to each node's rngit/config before its first start)");
    }

    /**
     * Core's default rngit config, announcing every 10 minutes instead of 360. A gateway that
     * restarts forgets the paths to the other nodes' rngit destinations and never searches for
     * them, so it only relearns them from the next announce.
     */
    static String rngitConfig() {
        String config = RngitConfig.DEFAULT_CONFIG.replace("announce_interval = 360", "announce_interval = 10");
        if (config.equals(RngitConfig.DEFAULT_CONFIG)) {
            throw new IllegalStateException("announce_interval = 360 not found in RngitConfig.DEFAULT_CONFIG");
        }
        return config;
    }

    static Options parse(String[] args) {
        Options o = new Options();
        o.passphrase = Base58.encode(randomBytes(16));
        for (int i = 0; i < args.length; i++) {
            String value = i + 1 < args.length ? args[i + 1] : null;
            switch (args[i]) {
                case "--out": o.out = Path.of(value); i++; break;
                case "--base": o.base = Path.of(value); i++; break;
                case "--hosts": o.hosts = List.of(value.split(",")); i++; break;
                case "--start-minutes": o.startMinutes = Integer.parseInt(value); i++; break;
                case "--api-port": o.apiPort = Integer.parseInt(value); i++; break;
                case "--listen-port": o.listenPort = Integer.parseInt(value); i++; break;
                case "--data-port": o.dataPort = Integer.parseInt(value); i++; break;
                case "--port-step": o.portStep = Integer.parseInt(value); i++; break;
                case "--reticulum-port": o.reticulumPort = Integer.parseInt(value); i++; break;
                case "--gateways": o.gateways = Integer.parseInt(value); i++; break;
                case "--network-name": o.networkName = value; i++; break;
                case "--passphrase": o.passphrase = value; i++; break;
                case "--account": {
                    String[] parts = value.split(":", 2);
                    o.accounts.put(parts[0], parts.length > 1 ? parts[1] : "1000");
                    i++;
                    break;
                }
                default: throw new IllegalArgumentException("Unknown option " + args[i]);
            }
        }
        if (o.out == null) throw new IllegalArgumentException("--out is required");
        if (o.gateways < 1) throw new IllegalArgumentException("--gateways must be at least 1");
        if (o.accounts.isEmpty()) {
            o.accounts.put("faucet", "1000000");
            o.accounts.put("publisher", "10000");
            o.accounts.put("devuser", "10000");
            for (int i = 1; i <= 3; i++) o.accounts.put("dev" + i, "1000");
        }
        return o;
    }

    private static byte[] randomBytes(int n) {
        byte[] bytes = new byte[n];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private static PrivateKeyAccount newAccount() {
        return new PrivateKeyAccount(null, randomBytes(32));
    }

    private static Map<String, Object> describe(PrivateKeyAccount account, String role) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("role", role);
        entry.put("address", account.getAddress());
        entry.put("publicKey", Base58.encode(account.getPublicKey()));
        entry.put("privateKey", Base58.encode(account.getPrivateKey()));
        return entry;
    }

    // ------------------------------------------------------------------
    // Chain config

    @SuppressWarnings("unchecked")
    static Map<String, Object> chain(Options o, List<PrivateKeyAccount> minters, Map<String, PrivateKeyAccount> named,
                                     Map<String, Object> keys) throws Exception {
        Map<String, Object> base;
        if (o.base != null) {
            base = JSON.readValue(o.base.toFile(), LinkedHashMap.class);
        } else {
            try (InputStream in = DevnetGenerator.class.getClassLoader().getResourceAsStream("blockchain.json")) {
                if (in == null) throw new IllegalStateException("blockchain.json not on the classpath; pass --base");
                base = JSON.readValue(in, LinkedHashMap.class);
            }
        }

        Map<String, Object> chain = new LinkedHashMap<>();
        chain.put("isTestChain", true);
        for (Map.Entry<String, Object> entry : base.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (key.equals("checkpoints") || key.equals("genesisInfo") || key.equals("isTestChain")) continue;
            if (key.equals("featureTriggers")) {
                Map<String, Object> triggers = new LinkedHashMap<>();
                ((Map<String, Object>) value).forEach((name, trigger) -> triggers.put(name, activeNow(name, (Number) trigger)));
                value = triggers;
            } else if (value instanceof Number && (key.endsWith("Timestamp") || key.endsWith("Height") || key.equals("referenceTimestampBlock"))) {
                value = activeNow(key, (Number) value);
            } else if (key.equals("unitFees") || key.equals("nameRegistrationUnitFees") || key.equals("maxRewardSharesByTimestamp")) {
                List<Map<String, Object>> schedule = (List<Map<String, Object>>) value;
                Map<String, Object> current = new LinkedHashMap<>(schedule.get(schedule.size() - 1));
                current.put("timestamp", 0);
                value = List.of(current);
            } else if (key.equals("mintingGroupIds")) {
                value = List.of(Map.of("height", 0, "ids", List.of(MINTING_GROUP_ID)));
            }
            chain.put(key, value);
        }

        Map<String, Object> baseGenesis = (Map<String, Object>) base.get("genesisInfo");
        List<Map<String, Object>> transactions = new ArrayList<>();
        for (Map<String, Object> tx : (List<Map<String, Object>>) baseGenesis.get("transactions")) {
            if ("ISSUE_ASSET".equals(tx.get("type"))) transactions.add(tx);
        }

        for (PrivateKeyAccount minter : minters) transactions.add(genesisPayment(minter.getAddress(), "1000"));
        for (Map.Entry<String, PrivateKeyAccount> account : named.entrySet()) {
            transactions.add(genesisPayment(account.getValue().getAddress(), o.accounts.get(account.getKey())));
        }

        for (int i = 0; i < minters.size(); i++) {
            PrivateKeyAccount minter = minters.get(i);
            transactions.add(tx("ACCOUNT_FLAGS", "target", minter.getAddress(), "andMask", -1, "orMask", 1, "xorMask", 0));
            transactions.add(tx("ACCOUNT_LEVEL", "target", minter.getAddress(), "level", 10));
            transactions.add(tx("REWARD_SHARE", "minterPublicKey", Base58.encode(minter.getPublicKey()), "recipient", minter.getAddress(),
                    "rewardSharePublicKey", ((Map<String, Object>) keys.get("minter" + (i + 1))).get("rewardSharePublicKey"),
                    "sharePercent", 0));
        }

        // The minting group, as mainnet's genesis builds its dev group: created open, joined, then closed
        PrivateKeyAccount owner = minters.get(0);
        String ownerKey = Base58.encode(owner.getPublicKey());
        transactions.add(tx("CREATE_GROUP", "creatorPublicKey", ownerKey, "owner", owner.getAddress(), "groupName", "devnet-minters",
                "description", "devnet minting group", "isOpen", true, "approvalThreshold", "ONE",
                "minimumBlockDelay", 0, "maximumBlockDelay", 1440));
        for (PrivateKeyAccount minter : minters.subList(1, minters.size())) {
            transactions.add(tx("JOIN_GROUP", "joinerPublicKey", Base58.encode(minter.getPublicKey()), "groupId", MINTING_GROUP_ID));
            transactions.add(tx("ADD_GROUP_ADMIN", "ownerPublicKey", ownerKey, "groupId", MINTING_GROUP_ID, "member", minter.getAddress()));
        }
        transactions.add(tx("UPDATE_GROUP", "ownerPublicKey", ownerKey, "groupId", MINTING_GROUP_ID, "newOwner", owner.getAddress(),
                "newDescription", "devnet minting group", "newIsOpen", false, "newApprovalThreshold", "ONE",
                "minimumBlockDelay", 0, "maximumBlockDelay", 1440));

        Map<String, Object> genesis = new LinkedHashMap<>();
        genesis.put("version", baseGenesis.get("version"));
        genesis.put("timestamp", (System.currentTimeMillis() / 60_000 + o.startMinutes) * 60_000);
        genesis.put("transactions", transactions);
        chain.put("genesisInfo", genesis);
        return chain;
    }

    /** A trigger as it applies on mainnet today: 0 if passed, unchanged if mainnet keeps it in the far future. */
    static Object activeNow(String name, Number value) {
        long v = value.longValue();
        boolean timestamp = name.endsWith("Timestamp") || name.equals("referenceTimestampBlock");
        return v >= (timestamp ? FAR_FUTURE_TIMESTAMP : FAR_FUTURE_HEIGHT) ? value : 0;
    }

    private static Map<String, Object> genesisPayment(String recipient, String amount) {
        return tx("GENESIS", "recipient", recipient, "amount", amount);
    }

    private static Map<String, Object> tx(String type, Object... fields) {
        Map<String, Object> tx = new LinkedHashMap<>();
        tx.put("type", type);
        for (int i = 0; i + 1 < fields.length; i += 2) tx.put((String) fields[i], fields[i + 1]);
        return tx;
    }

    // ------------------------------------------------------------------
    // Node settings

    static Map<String, Object> settings(Options o, int index) {
        int step = o.portStep * index;
        // Core matches an inbound peer's IP against fixedNetwork as plain text, so a hostname
        // there rejects every inbound connection and the nodes never peer
        List<String> fixed = new ArrayList<>();
        for (int i = 0; i < o.hosts.size(); i++) {
            if (i != index) fixed.add(ipv4(o.hosts.get(i)) + ":" + (o.listenPort + o.portStep * i));
        }

        Map<String, Object> s = new LinkedHashMap<>();
        s.put("isTestNet", true);
        s.put("blockchainConfig", "devchain.json");
        s.put("repositoryPath", "db-devnet");
        s.put("bootstrap", false);
        s.put("apiPort", o.apiPort + step);
        s.put("listenPort", o.listenPort + step);
        s.put("listenDataPort", o.dataPort + step);
        s.put("fixedNetwork", fixed);
        s.put("minBlockchainPeers", 1);
        s.put("singleNodeTestnet", false);
        s.put("uPnPEnabled", false);
        for (String net : List.of("bitcoinNet", "litecoinNet", "dogecoinNet", "digibyteNet", "ravencoinNet")) s.put(net, "TEST3");
        s.put("apiDocumentationEnabled", true);
        s.put("reticulumNetworkName", o.networkName);
        s.put("reticulumPassphrase", o.passphrase);
        // The first nodes are the mesh's gateways, never the public ones. A gateway connects to
        // the gateways before it, every other node to all of them, so each node keeps a route to
        // the rest of the mesh while one gateway is offline
        int gateways = Math.min(o.gateways, o.hosts.size());
        List<String> gatewayServers = new ArrayList<>();
        for (int i = 0; i < Math.min(index, gateways); i++) {
            gatewayServers.add(o.hosts.get(i) + ":" + (o.reticulumPort + o.portStep * i));
        }
        s.put("reticulumHasServerInterface", index < gateways);
        s.put("reticulumPort", o.reticulumPort + step);
        s.put("reticulumBackboneGatewayServers", gatewayServers);
        s.put("reticulumTcpGatewayServers", List.of());
        s.put("rngitEnabled", true);
        return s;
    }

    /** The host's IPv4 address, as the text Core sees for an inbound peer. */
    static String ipv4(String host) {
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (address instanceof Inet4Address) return address.getHostAddress();
            }
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Can't resolve host " + host + "; pass its IP address in --hosts", e);
        }
        throw new IllegalArgumentException("Host " + host + " has no IPv4 address; pass one in --hosts");
    }
}
