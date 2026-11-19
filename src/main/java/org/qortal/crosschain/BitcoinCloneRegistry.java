package org.qortal.crosschain;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** A startup snapshot. Registration is available only on its builder, never on an active registry. */
public final class BitcoinCloneRegistry {
    private static final Set<String> RESERVED_IDS = Set.of("bitcoin", "litecoin", "dogecoin", "digibyte",
            "ravencoin", "piratechain", "monero", "qortal");
    private static final Set<String> RESERVED_SYMBOLS = Set.of("BTC", "LTC", "DOGE", "DGB", "RVN", "ARRR", "XMR", "QORT");
    private final Map<String, Registration> registrations;
    private final Map<String, BitcoinClone> backends = new ConcurrentHashMap<>();

    private BitcoinCloneRegistry(Map<String, Registration> registrations) {
        this.registrations = Collections.unmodifiableMap(new LinkedHashMap<>(registrations));
    }

    public static Builder builder() { return new Builder(); }

    public BitcoinCloneDefinition get(String id, BitcoinCloneDefinition.Network network) {
        Registration registration = registrations.get(key(id, network));
        return registration == null ? null : registration.definition;
    }

    public List<BitcoinCloneDefinition> getAll() {
        return registrations.values().stream().map(r -> r.definition).collect(Collectors.toUnmodifiableList());
    }

    public List<BitcoinCloneDefinition> getEnabled() {
        return registrations.values().stream().filter(r -> r.definition.enabled && r.enabled.getAsBoolean())
                .map(r -> r.definition).collect(Collectors.toUnmodifiableList());
    }

    public BitcoinClone backend(String id, BitcoinCloneDefinition.Network network) {
        String key = key(id, network);
        Registration registration = registrations.get(key);
        if (registration == null) throw new IllegalArgumentException("Unknown chain/network");
        if (!registration.definition.enabled || !registration.enabled.getAsBoolean())
            throw new IllegalStateException("Wallet is disabled");
        BitcoinClone backend = backends.computeIfAbsent(key, ignored -> registration.factory.get());
        if (backend == null) throw new IllegalStateException("Wallet is disabled");
        return backend;
    }

    /** Stable existing trading identity; custom registrations cannot acquire an enum/market. */
    public Optional<SupportedBlockchain> supportedBlockchain(String id, BitcoinCloneDefinition.Network network) {
        BitcoinCloneDefinition definition = get(id, network);
        if (definition == null || !definition.isBuiltIn()) return Optional.empty();
        return Optional.ofNullable(SupportedBlockchain.fromString(id.toUpperCase(Locale.ROOT)));
    }

    private static String key(String id, BitcoinCloneDefinition.Network network) {
        if (id == null || network == null) throw new IllegalArgumentException("Chain ID and network required");
        return id + ":" + network.name();
    }

    private static final class Registration {
        final BitcoinCloneDefinition definition;
        final Supplier<BitcoinClone> factory;
        final BooleanSupplier enabled;
        Registration(BitcoinCloneDefinition definition, Supplier<BitcoinClone> factory, BooleanSupplier enabled) {
            this.definition = definition;
            this.factory = factory;
            this.enabled = enabled;
        }
    }

    public static final class Builder {
        private final Map<String, Registration> entries = new LinkedHashMap<>();

        public Builder register(BitcoinCloneDefinition definition) {
            return configured(definition, () -> true);
        }

        Builder configured(BitcoinCloneDefinition definition, BooleanSupplier enabled) {
            if (definition.isBuiltIn() || RESERVED_IDS.contains(definition.id) || RESERVED_SYMBOLS.contains(definition.symbol))
                throw new IllegalArgumentException("Existing chain identities cannot be configured");
            return add(definition, () -> BitcoinClone.create(definition), enabled);
        }

        Builder builtIn(BitcoinCloneDefinition definition, Supplier<BitcoinClone> factory, BooleanSupplier enabled) {
            if (!definition.isBuiltIn()) throw new IllegalArgumentException("Expected reviewed built-in adapter");
            return add(definition, factory, enabled);
        }

        private Builder add(BitcoinCloneDefinition definition, Supplier<BitcoinClone> factory, BooleanSupplier enabled) {
            if (entries.containsKey(definition.key())) throw new IllegalArgumentException("Duplicate chain/network ID");
            for (Registration entry : entries.values()) {
                BitcoinCloneDefinition other = entry.definition;
                if (other.id.equals(definition.id)) {
                    if (!other.symbol.equals(definition.symbol) || !other.name.equals(definition.name)
                            || other.protocol != definition.protocol || other.bip44CoinType != definition.bip44CoinType
                            || other.derivation != definition.derivation)
                        throw new IllegalArgumentException("Conflicting identity across networks");
                } else if (other.symbol.equals(definition.symbol)) {
                    throw new IllegalArgumentException("Symbol belongs to another chain");
                }
                if (other.network == definition.network && other.genesisHash != null
                        && other.genesisHash.equals(definition.genesisHash))
                    throw new IllegalArgumentException("Genesis identity belongs to another registration");
            }
            entries.put(definition.key(), new Registration(definition, factory, enabled));
            return this;
        }

        public BitcoinCloneRegistry build() { return new BitcoinCloneRegistry(entries); }
    }
}
