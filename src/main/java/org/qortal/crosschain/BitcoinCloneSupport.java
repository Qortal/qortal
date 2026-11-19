package org.qortal.crosschain;

import org.qortal.settings.Settings;

import java.io.IOException;
import java.nio.file.Path;

/** One immutable startup registry; changes to definitions require a restart. */
public final class BitcoinCloneSupport {
    private BitcoinCloneSupport() { }

    private static class Holder {
        static final BitcoinCloneRegistry REGISTRY = load(Settings.getInstance());
    }

    public static BitcoinCloneRegistry getRegistry() { return Holder.REGISTRY; }

    static BitcoinCloneRegistry load(Settings settings) {
        BitcoinCloneRegistry.Builder builder = BitcoinCloneRegistry.builder()
                .builtIn(Bitcoin.definitionFor(settings.getBitcoinNet()), Bitcoin::getInstance, () -> settings.isWalletEnabled("BTC"))
                .builtIn(Litecoin.definitionFor(settings.getLitecoinNet()), Litecoin::getInstance, () -> settings.isWalletEnabled("LTC"));
        String file = settings.getBitcoinCloneDefinitions();
        if (file != null) {
            if (file.isBlank()) throw new IllegalArgumentException("bitcoinCloneDefinitions must name a local JSON file");
            Path path = Path.of(file);
            if (!path.isAbsolute()) path = Path.of(settings.getUserPath()).resolve(path);
            try {
                for (BitcoinCloneDefinition definition : BitcoinCloneLoader.load(path))
                    builder.configured(definition, () -> settings.isWalletEnabled(definition.symbol));
            } catch (IOException e) {
                throw new IllegalArgumentException("Unable to read Bitcoin-clone definitions", e);
            }
        }
        return builder.build();
    }
}
