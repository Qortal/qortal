package org.qortal.crosschain;

import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import org.junit.Test;
import pirate.wallet.sdk.rpc.Service.BlockID;
import pirate.wallet.sdk.rpc.Service.LightdInfo;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PirateLightClientTrustTests {

    @Test
    public void maliciousFirstHeightCannotPoisonHonestCorroboration() {
        PirateLightClient.ServerHeightTracker tracker = new PirateLightClient.ServerHeightTracker();
        ChainableServer malicious = server("malicious.example");
        ChainableServer honestOne = server("honest-one.example");
        ChainableServer honestTwo = server("honest-two.example");

        assertEquals(PirateLightClient.HeightAssessment.ACCEPTED_UNCORROBORATED,
                tracker.assess(malicious, 50_000_000L));
        assertNull(tracker.getTrustedReferenceHeight());
        assertEquals(PirateLightClient.HeightAssessment.ACCEPTED_UNCORROBORATED,
                tracker.assess(honestOne, 2_000_000L));
        assertEquals(PirateLightClient.HeightAssessment.ACCEPTED_TRUSTED,
                tracker.assess(honestTwo, 2_000_004L));
        assertEquals(Long.valueOf(2_000_004L), tracker.getTrustedReferenceHeight());
        assertEquals(PirateLightClient.HeightAssessment.IMPLAUSIBLY_AHEAD,
                tracker.assess(malicious, 50_000_000L));
    }

    @Test
    public void oneServerCannotCorroborateItself() {
        PirateLightClient.ServerHeightTracker tracker = new PirateLightClient.ServerHeightTracker();
        ChainableServer one = server("single.example");
        assertEquals(PirateLightClient.HeightAssessment.ACCEPTED_UNCORROBORATED,
                tracker.assess(one, 2_000_000L));
        assertEquals(PirateLightClient.HeightAssessment.ACCEPTED_UNCORROBORATED,
                tracker.assess(one, 2_000_001L));
        assertNull(tracker.getTrustedReferenceHeight());
    }

    @Test
    public void chainIdentityMustMatchConfiguredNetwork() {
        assertTrue(PirateLightClient.matchesExpectedChainName("main", "MAIN"));
        assertFalse(PirateLightClient.matchesExpectedChainName("main", "test"));
        assertEquals("main", PirateLightClient.expectedChainName("PirateChain-MAIN"));
        assertEquals("test", PirateLightClient.expectedChainName("PirateChain-TEST3"));
        assertEquals("regtest", PirateLightClient.expectedChainName("PirateChain-REGTEST"));
    }

    @Test
    public void admissionRejectsUnavailableOrDivergentLatestBlockWithoutPoisoningServer() throws Exception {
        LightdInfo info = LightdInfo.newBuilder().setChainName("main").setBlockHeight(2_100_000).build();

        FakeManagedChannel unavailableChannel = new FakeManagedChannel();
        ProbeLightClient unavailable = new ProbeLightClient(unavailableChannel, info,
                new IllegalStateException("cache unavailable"));
        Optional<ChainableServerConnection> unavailableResult =
                unavailable.setCurrentServer(server("unavailable.example"), "test");
        assertTrue(unavailableResult.isPresent());
        assertFalse(unavailableResult.get().isSuccess());
        assertTrue(unavailableChannel.isTerminated());
        assertFalse(unavailable.getUselessServers().contains(server("unavailable.example")));

        FakeManagedChannel divergentChannel = new FakeManagedChannel();
        ProbeLightClient divergent = new ProbeLightClient(divergentChannel, info, 2_300_000L);
        Optional<ChainableServerConnection> divergentResult =
                divergent.setCurrentServer(server("divergent.example"), "test");
        assertTrue(divergentResult.isPresent());
        assertFalse(divergentResult.get().isSuccess());
        assertTrue(divergentChannel.isTerminated());
        assertFalse(divergent.getUselessServers().contains(server("divergent.example")));
    }

    @Test
    public void admissionAcceptsMatchingInfoAndLatestBlock() throws Exception {
        FakeManagedChannel channel = new FakeManagedChannel();
        LightdInfo info = LightdInfo.newBuilder().setChainName("main").setBlockHeight(2_100_000).build();
        ProbeLightClient client = new ProbeLightClient(channel, info, 2_100_000L);
        Optional<ChainableServerConnection> result = client.setCurrentServer(server("ready.example"), "test");
        assertTrue(result.isPresent());
        assertTrue(result.get().isSuccess());
        assertFalse(channel.isShutdown());
        assertEquals(server("ready.example"), client.getCurrentServer());
    }

    private static PirateLightClient.Server server(String hostname) {
        return new PirateLightClient.Server(hostname, ChainableServer.ConnectionType.SSL, 443);
    }

    private static final class ProbeLightClient extends PirateLightClient {
        private final ManagedChannel channel;
        private final LightdInfo info;
        private final Object latestOutcome;

        private ProbeLightClient(ManagedChannel channel, LightdInfo info, Object latestOutcome) {
            super("PirateChain-MAIN", "genesis", Collections.emptyList(), ports(), () -> 1);
            this.channel = channel;
            this.info = info;
            this.latestOutcome = latestOutcome;
        }

        @Override
        protected ManagedChannel buildProbeChannel(ChainableServer server) {
            return this.channel;
        }

        @Override
        protected LightdInfo fetchLightdInfo(ManagedChannel probeChannel) {
            return this.info;
        }

        @Override
        protected BlockID fetchLatestBlock(ManagedChannel probeChannel) {
            if (this.latestOutcome instanceof RuntimeException) {
                throw (RuntimeException) this.latestOutcome;
            }
            return BlockID.newBuilder().setHeight((Long) this.latestOutcome).build();
        }

        private static Map<ChainableServer.ConnectionType, Integer> ports() {
            Map<ChainableServer.ConnectionType, Integer> ports =
                    new EnumMap<>(ChainableServer.ConnectionType.class);
            ports.put(ChainableServer.ConnectionType.SSL, 443);
            return ports;
        }
    }

    private static final class FakeManagedChannel extends ManagedChannel {
        private boolean shutdown;
        private boolean terminated;

        @Override
        public ManagedChannel shutdown() {
            this.shutdown = true;
            this.terminated = true;
            return this;
        }

        @Override
        public boolean isShutdown() {
            return this.shutdown;
        }

        @Override
        public boolean isTerminated() {
            return this.terminated;
        }

        @Override
        public ManagedChannel shutdownNow() {
            this.shutdown = true;
            this.terminated = true;
            return this;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return this.terminated;
        }

        @Override
        public <RequestT, ResponseT> ClientCall<RequestT, ResponseT> newCall(
                MethodDescriptor<RequestT, ResponseT> methodDescriptor, CallOptions callOptions) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String authority() {
            return "test";
        }
    }
}
