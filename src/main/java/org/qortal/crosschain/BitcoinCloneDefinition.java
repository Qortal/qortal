package org.qortal.crosschain;

import org.bitcoinj.core.NetworkParameters;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.Collection;
import java.util.stream.Collectors;

/** Validated, immutable registration data for one chain/network. No executable configuration. */
public final class BitcoinCloneDefinition {
    public enum Network { MAINNET, TESTNET, REGTEST }
    public enum Protocol { BITCOIN_WIRE_SHA256D, LITECOIN_EXISTING }
    public enum Derivation { QORTAL_BIP32_MASTER_P2PKH }
    public enum Capability { ADDRESS, BALANCE, HISTORY, SIGN, BROADCAST, HTLC, ACCT, TRADEBOT }

    public static final class Endpoint {
        public final String host;
        public final ChainableServer.ConnectionType transport;
        public final int port;

        public Endpoint(String host, ChainableServer.ConnectionType transport, int port) {
            if (host == null || host.isBlank() || transport == null || port < 1 || port > 65535)
                throw new IllegalArgumentException("Invalid Electrum endpoint");
            this.host = host;
            this.transport = transport;
            this.port = port;
        }

        ElectrumX.Server toServer() { return new ElectrumX.Server(host, transport, port); }
    }

    public final String id;
    public final String name;
    public final String symbol;
    public final Network network;
    public final Protocol protocol;
    public final Derivation derivation;
    public final int bip44CoinType;
    public final String genesisHash;
    public final long feePerKb;
    public final long p2shFee;
    public final long minimumOrderAmount;
    public final Set<Capability> capabilities;
    public final List<Endpoint> servers;
    public final boolean enabled;
    private final boolean builtIn;
    private final NetworkParameters parameters;

    BitcoinCloneDefinition(String id, String name, String symbol, Network network, Protocol protocol,
            int bip44CoinType, String genesisHash, long feePerKb, long p2shFee, long minimumOrderAmount,
            Set<Capability> capabilities, List<Endpoint> servers, boolean enabled,
            NetworkParameters parameters, boolean builtIn) {
        if (id == null || !id.matches("[a-z][a-z0-9-]{1,39}")
                || name == null || name.isBlank() || name.length() > 80
                || symbol == null || !symbol.matches("[A-Z][A-Z0-9]{1,11}")
                || network == null || protocol == null || parameters == null
                || bip44CoinType < 0 || feePerKb <= 0 || feePerKb > 100_000_000L
                || p2shFee <= 0 || p2shFee > 100_000_000L || minimumOrderAmount < 0)
            throw new IllegalArgumentException("Invalid chain identity, parameters or fees");
        if ((genesisHash == null && !(builtIn && network == Network.REGTEST))
                || (genesisHash != null && (!genesisHash.matches("[0-9a-f]{64}")
                || genesisHash.equals("0".repeat(64)))))
            throw new IllegalArgumentException("A concrete lowercase genesis hash is required");
        if (capabilities == null || !capabilities.contains(Capability.ADDRESS))
            throw new IllegalArgumentException("ADDRESS capability is required");
        if (capabilities.contains(Capability.SIGN) && !capabilities.containsAll(EnumSet.of(Capability.BALANCE, Capability.HISTORY))
                || capabilities.contains(Capability.BROADCAST) && !capabilities.contains(Capability.SIGN)
                || capabilities.contains(Capability.HTLC) && !capabilities.contains(Capability.SIGN)
                || capabilities.contains(Capability.ACCT) && !capabilities.contains(Capability.HTLC)
                || capabilities.contains(Capability.TRADEBOT) && !capabilities.containsAll(
                        EnumSet.of(Capability.ACCT, Capability.BROADCAST, Capability.BALANCE, Capability.HISTORY)))
            throw new IllegalArgumentException("Inconsistent capability prerequisites");
        if (servers == null || servers.isEmpty() || servers.size() > 64)
            throw new IllegalArgumentException("Between 1 and 64 Electrum endpoints required");
        if (!builtIn) {
            if (protocol != Protocol.BITCOIN_WIRE_SHA256D)
                throw new IllegalArgumentException("Configured protocol has no reviewed adapter");
            if (!Collections.disjoint(capabilities, EnumSet.of(Capability.HTLC, Capability.ACCT, Capability.TRADEBOT)))
                throw new IllegalArgumentException("Configured chains cannot activate trading");
            if (network != Network.REGTEST && (capabilities.contains(Capability.SIGN) || capabilities.contains(Capability.BROADCAST)))
                throw new IllegalArgumentException("Configured mainnet/testnet chains are read-only");
            for (Endpoint server : servers) BitcoinCloneLoader.validateDestination(server, network);
        }
        this.id = id;
        this.name = name;
        this.symbol = symbol;
        this.network = network;
        this.protocol = protocol;
        this.derivation = Derivation.QORTAL_BIP32_MASTER_P2PKH;
        this.bip44CoinType = bip44CoinType;
        this.genesisHash = genesisHash;
        this.feePerKb = feePerKb;
        this.p2shFee = p2shFee;
        this.minimumOrderAmount = minimumOrderAmount;
        this.capabilities = Collections.unmodifiableSet(EnumSet.copyOf(capabilities));
        this.servers = List.copyOf(servers);
        this.enabled = enabled;
        this.parameters = parameters;
        this.builtIn = builtIn;
    }

    NetworkParameters parameters() { return parameters; }

    static BitcoinCloneDefinition builtIn(String id, String name, String symbol, String network,
            Protocol protocol, int coinType, NetworkParameters params, String genesis, long fee,
            long p2shFee, long minimumOrder, Collection<ElectrumX.Server> servers) {
        Network selected = network.equals("MAIN") ? Network.MAINNET
                : network.equals("TEST3") ? Network.TESTNET : Network.REGTEST;
        return new BitcoinCloneDefinition(id, name, symbol, selected, protocol, coinType, genesis,
                fee, p2shFee, minimumOrder, EnumSet.allOf(Capability.class), servers.stream()
                .map(server -> new Endpoint(server.getHostName(), server.getConnectionType(), server.getPort()))
                .collect(Collectors.toList()), true, params, true);
    }
    public boolean isBuiltIn() { return builtIn; }
    public String key() { return id + ":" + network.name(); }
    public boolean supports(Capability capability) { return capabilities.contains(capability); }

    public void require(Capability capability) {
        if (!supports(capability)) throw new IllegalStateException("Capability unavailable: " + capability);
    }
}
