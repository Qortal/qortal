package org.qortal.crosschain;

import org.junit.Test;
import org.qortal.crosschain.PirateChain.PirateChainNet;
import pirate.wallet.sdk.rpc.CompactFormats.CompactBlock;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/** Explicitly opt-in, read-only production admission and compact-history gate. */
public class PirateProductionLightwalletTests {

    @Test
    public void atLeastTwoConfiguredMainnetServersAreReadyAndCompatible() throws Exception {
        assumeTrue(Boolean.getBoolean("qortal.runPirateProductionLightwalletTests"));

        List<Integer> readyHeights = new ArrayList<>();
        for (PirateLightClient.Server server : PirateChainNet.MAIN.getServers()) {
            PirateLightClient client = new PirateLightClient("PirateChain-MAIN",
                    PirateChainNet.MAIN.getGenesisHash(), List.of(server), ports(), () -> 152_855);
            try {
                Optional<ChainableServerConnection> result = client.probeServer(server, "production acceptance");
                if (result.isEmpty() || !result.get().isSuccess()) {
                    System.out.printf("PIRATE_PRODUCTION_ENDPOINT host=%s status=UNAVAILABLE%n", server.getHostName());
                    continue;
                }

                int height = client.getCurrentHeight();
                assertTrue("Ready endpoint returned a non-positive height", height > 1);
                List<CompactBlock> blocks = client.getCompactBlocksBounded(height - 1L, height, 15L);
                assertEquals("Ready endpoint did not return exactly two compact blocks", 2, blocks.size());
                assertEquals(height - 1L, blocks.get(0).getHeight());
                assertEquals(height, blocks.get(1).getHeight());
                assertFalse("Previous compact block hash was empty", blocks.get(0).getHash().isEmpty());
                assertFalse("Tip compact block hash was empty", blocks.get(1).getHash().isEmpty());
                assertEquals("Recent compact blocks were not hash-linked",
                        blocks.get(0).getHash(), blocks.get(1).getPrevHash());
                readyHeights.add(height);
                System.out.printf("PIRATE_PRODUCTION_ENDPOINT host=%s status=READY height=%d%n",
                        server.getHostName(), height);
            } finally {
                client.closeCurrentConnection();
            }
        }

        assertTrue("Fewer than two configured Pirate mainnet endpoints were fully ready",
                readyHeights.size() >= 2);
        int minimum = readyHeights.stream().mapToInt(Integer::intValue).min().orElseThrow();
        int maximum = readyHeights.stream().mapToInt(Integer::intValue).max().orElseThrow();
        assertTrue("Ready Pirate endpoints disagreed by more than 100 blocks", maximum - minimum <= 100);
    }

    private static Map<ChainableServer.ConnectionType, Integer> ports() {
        Map<ChainableServer.ConnectionType, Integer> ports =
                new EnumMap<>(ChainableServer.ConnectionType.class);
        ports.put(ChainableServer.ConnectionType.TCP, 9067);
        ports.put(ChainableServer.ConnectionType.SSL, 443);
        return ports;
    }
}
