package org.qortal.test.network;

import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import io.druid.extendedset.intset.ConciseSet;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.block.Block;
import org.qortal.block.BlockChain;
import org.qortal.controller.BlockMinter;
import org.qortal.controller.OnlineAccountsManager;
import org.qortal.crypto.Qortal25519Extras;
import org.qortal.data.block.BlockData;
import org.qortal.data.network.OnlineAccountData;
import org.qortal.network.message.OnlineAccountsV3Message;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.settings.Settings;
import org.qortal.test.common.AccountUtils;
import org.qortal.test.common.Common;
import org.qortal.transform.Transformer;
import org.qortal.utils.Base58;
import org.qortal.utils.NTP;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.security.Security;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class OnlineAccountsTests extends Common {

    private static final Random RANDOM = new Random();
    static {
        // This must go before any calls to LogManager/Logger
        System.setProperty("java.util.logging.manager", "org.apache.logging.log4j.jul.LogManager");

        Security.insertProviderAt(new BouncyCastleProvider(), 0);
        Security.insertProviderAt(new BouncyCastleJsseProvider(), 1);
    }

    @Before
    public void beforeTest() throws DataException, IOException {
        Common.useSettingsAndDb("test-settings-v2.json", false);
        NTP.setFixedOffset(Settings.getInstance().getTestNtpOffset());
    }


    @Test
    public void testOnlineAccountsModulusV1() throws IllegalAccessException, DataException {
        try (final Repository repository = RepositoryManager.getRepository()) {

            // Set feature trigger timestamp to MAX long so that it is inactive
            FieldUtils.writeField(BlockChain.getInstance(), "onlineAccountsModulusV2Timestamp", Long.MAX_VALUE, true);

            List<String> onlineAccountSignatures = new ArrayList<>();
            long fakeNTPOffset = 0L;

            // Mint a block and store its timestamp
            Block block = BlockMinter.mintTestingBlock(repository, Common.getTestAccount(repository, "alice-reward-share"));
            long lastBlockTimestamp = block.getBlockData().getTimestamp();

            // Mint some blocks and keep track of the different online account signatures
            for (int i = 0; i < 30; i++) {
                block = BlockMinter.mintTestingBlock(repository, Common.getTestAccount(repository, "alice-reward-share"));

                // Increase NTP fixed offset by the block time, to simulate time passing
                long blockTimeDelta = block.getBlockData().getTimestamp() - lastBlockTimestamp;
                lastBlockTimestamp = block.getBlockData().getTimestamp();
                fakeNTPOffset += blockTimeDelta;
                NTP.setFixedOffset(fakeNTPOffset);

                String lastOnlineAccountSignatures58 = Base58.encode(block.getBlockData().getOnlineAccountsSignatures());
                if (!onlineAccountSignatures.contains(lastOnlineAccountSignatures58)) {
                    onlineAccountSignatures.add(lastOnlineAccountSignatures58);
                }
            }

            // We expect at least 6 unique signatures over 30 blocks (generally 6-8, but could be higher due to block time differences)
            System.out.println(String.format("onlineAccountSignatures count: %d", onlineAccountSignatures.size()));
            assertTrue(onlineAccountSignatures.size() >= 6);
        }
    }

    @Test
    public void testOnlineAccountsSignatureV2() throws IllegalAccessException, DataException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            // Activate the secure per-account Ed25519 signature scheme (C-01 fix) immediately
            long originalOnlineAccountsSignatureV2Height = setOnlineAccountsSignatureV2Height(0L);

            try {
                PrivateKeyAccount[] onlineAccounts = new PrivateKeyAccount[] {
                        Common.getTestAccount(repository, "alice-reward-share"),
                        Common.getTestAccount(repository, "bob-reward-share"),
                        Common.getTestAccount(repository, "chloe-reward-share"),
                        Common.getTestAccount(repository, "dilbert-reward-share")
                };

                // Minting fully re-validates online accounts via Block.areOnlineAccountsValid(), which now
                // verifies each account's standard Ed25519 signature individually. A non-null block means
                // the V2 verification path accepted the per-account signatures.
                Block block = BlockMinter.mintTestingBlock(repository, onlineAccounts);
                assertTrue("V2 block should have minted and validated", block != null);

                int onlineAccountsCount = block.getBlockData().getOnlineAccountsCount();
                assertTrue("expected at least one online account", onlineAccountsCount >= 1);

                // Under the V2 scheme each online account stores its own signature, so the signature count
                // must equal the online-accounts count (rather than the single legacy aggregate).
                assertEquals(onlineAccountsCount, block.getBlockData().getOnlineAccountsSignaturesCount());
            } finally {
                setOnlineAccountsSignatureV2Height(originalOnlineAccountsSignatureV2Height);
            }
        }
    }

    @Test
    public void testOnlineAccountsSignaturesCountRejectsMalformedBytes() {
        byte[] malformedSignatures = new byte[Transformer.SIGNATURE_LENGTH + Transformer.INT_LENGTH + 1];

        BlockData blockData = new BlockData(4, new byte[Transformer.SIGNATURE_LENGTH], 0, 0L,
                new byte[Transformer.SIGNATURE_LENGTH], 2, 0L, new byte[Transformer.PUBLIC_KEY_LENGTH],
                new byte[Transformer.SIGNATURE_LENGTH], 0, 0L, new byte[0], 1, 0L, malformedSignatures);

        try {
            blockData.getOnlineAccountsSignaturesCount();
            fail("Malformed online account signatures should be rejected");
        } catch (IllegalStateException expected) {
            // Expected
        }
    }

    @Test
    public void testOnlineAccountsSignatureV2RejectsTamperedBlockSignature() throws IllegalAccessException, DataException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            // Activate the secure per-account Ed25519 signature scheme (C-01 fix) immediately
            long originalOnlineAccountsSignatureV2Height = setOnlineAccountsSignatureV2Height(0L);

            try {
                PrivateKeyAccount[] onlineAccounts = new PrivateKeyAccount[] {
                        Common.getTestAccount(repository, "alice-reward-share"),
                        Common.getTestAccount(repository, "bob-reward-share"),
                        Common.getTestAccount(repository, "chloe-reward-share"),
                        Common.getTestAccount(repository, "dilbert-reward-share")
                };

                Block block = BlockMinter.mintTestingBlock(repository, onlineAccounts);
                assertTrue("V2 block should have minted and validated", block != null);

                byte[] tamperedSignatures = block.getBlockData().getOnlineAccountsSignatures().clone();
                tamperedSignatures[0] ^= 0x01;

                BlockData tamperedBlockData = new BlockData(block.getBlockData());
                tamperedBlockData.setOnlineAccountsSignatures(tamperedSignatures);

                Block tamperedBlock = new Block(repository, tamperedBlockData);
                assertEquals(Block.ValidationResult.ONLINE_ACCOUNT_SIGNATURE_INCORRECT, tamperedBlock.areOnlineAccountsValid());
            } finally {
                setOnlineAccountsSignatureV2Height(originalOnlineAccountsSignatureV2Height);
            }
        }
    }

    @Test
    public void testOnlineAccountsSignatureV2CacheRejectsTamperedSignature() {
        byte[] privateKey = new byte[Transformer.PRIVATE_KEY_LENGTH];
        new SecureRandom().nextBytes(privateKey);

        byte[] publicKey = new byte[Transformer.PUBLIC_KEY_LENGTH];
        Qortal25519Extras.generatePublicKey(privateKey, 0, publicKey, 0);

        long onlineAccountsTimestamp = 1781295600000L;
        byte[] timestampBytes = Longs.toByteArray(onlineAccountsTimestamp);
        byte[] signature = Qortal25519Extras.sign(privateKey, timestampBytes);

        assertTrue(OnlineAccountsManager.getInstance().verifyOrCacheV2OnlineAccountSignature(publicKey, signature, onlineAccountsTimestamp));
        assertTrue(OnlineAccountsManager.getInstance().verifyOrCacheV2OnlineAccountSignature(publicKey, signature, onlineAccountsTimestamp));

        byte[] tamperedSignature = signature.clone();
        tamperedSignature[0] ^= 0x01;

        assertFalse(OnlineAccountsManager.getInstance().verifyOrCacheV2OnlineAccountSignature(publicKey, tamperedSignature, onlineAccountsTimestamp));
    }

    @Test
    public void testOnlineAccountsSignatureV2HeightFiltersLegacyCachedAccount() throws IllegalAccessException, DataException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            int nextBlockHeight = repository.getBlockRepository().getBlockchainHeight() + 1;
            long originalOnlineAccountsSignatureV2Height = setOnlineAccountsSignatureV2Height(nextBlockHeight);

            try {
                clearOnlineAccountCaches();

                PrivateKeyAccount account = Common.getTestAccount(repository, "alice-reward-share");
                long onlineAccountsTimestamp = OnlineAccountsManager.getCurrentOnlineAccountTimestamp();
                byte[] timestampBytes = Longs.toByteArray(onlineAccountsTimestamp);
                int nonce = 12345;

                byte[] legacySignature = Qortal25519Extras.signForAggregation(account.getPrivateKey(), timestampBytes);
                byte[] v2Signature = Qortal25519Extras.sign(account.getPrivateKey(), timestampBytes);

                OnlineAccountData legacyAccount = new OnlineAccountData(onlineAccountsTimestamp, legacySignature, account.getPublicKey(), nonce);
                OnlineAccountData v2Account = new OnlineAccountData(onlineAccountsTimestamp, v2Signature, account.getPublicKey(), nonce);

                Map<Long, Set<OnlineAccountData>> currentOnlineAccounts = getCurrentOnlineAccounts();
                currentOnlineAccounts.computeIfAbsent(onlineAccountsTimestamp, ignored -> ConcurrentHashMap.newKeySet()).add(legacyAccount);

                assertTrue("legacy online account should not be usable for V2 block height",
                        OnlineAccountsManager.getInstance().getOnlineAccounts(onlineAccountsTimestamp, nextBlockHeight).isEmpty());

                currentOnlineAccounts.get(onlineAccountsTimestamp).clear();
                currentOnlineAccounts.get(onlineAccountsTimestamp).add(v2Account);

                List<OnlineAccountData> onlineAccounts = OnlineAccountsManager.getInstance().getOnlineAccounts(onlineAccountsTimestamp, nextBlockHeight);
                assertEquals(1, onlineAccounts.size());
                assertArrayEquals(v2Signature, onlineAccounts.get(0).getSignature());
            } finally {
                clearOnlineAccountCaches();
                setOnlineAccountsSignatureV2Height(originalOnlineAccountsSignatureV2Height);
            }
        }
    }

    @Test
    public void testOnlineAccountsSignatureV2HeightReplacesLegacyQueuedAccount() throws IllegalAccessException, DataException {
        try (final Repository repository = RepositoryManager.getRepository()) {
            int nextBlockHeight = repository.getBlockRepository().getBlockchainHeight() + 1;
            long originalOnlineAccountsSignatureV2Height = setOnlineAccountsSignatureV2Height(nextBlockHeight);

            try {
                clearOnlineAccountCaches();

                PrivateKeyAccount account = Common.getTestAccount(repository, "alice-reward-share");
                long onlineAccountsTimestamp = OnlineAccountsManager.getCurrentOnlineAccountTimestamp();
                byte[] timestampBytes = Longs.toByteArray(onlineAccountsTimestamp);
                int nonce = 12345;

                byte[] legacySignature = Qortal25519Extras.signForAggregation(account.getPrivateKey(), timestampBytes);
                byte[] v2Signature = Qortal25519Extras.sign(account.getPrivateKey(), timestampBytes);

                OnlineAccountData legacyAccount = new OnlineAccountData(onlineAccountsTimestamp, legacySignature, account.getPublicKey(), nonce);
                OnlineAccountData v2Account = new OnlineAccountData(onlineAccountsTimestamp, v2Signature, account.getPublicKey(), nonce);

                Set<OnlineAccountData> onlineAccountsImportQueue = getOnlineAccountsImportQueue();
                onlineAccountsImportQueue.add(legacyAccount);

                OnlineAccountsV3Message onlineAccountsMessage = new OnlineAccountsV3Message(Collections.singletonList(v2Account));
                FieldUtils.writeField(onlineAccountsMessage, "onlineAccounts", Collections.singletonList(v2Account), true);

                OnlineAccountsManager.getInstance().onNetworkOnlineAccountsV3Message(null, onlineAccountsMessage);

                assertEquals(1, onlineAccountsImportQueue.size());
                OnlineAccountData queuedAccount = onlineAccountsImportQueue.iterator().next();
                assertTrue(Arrays.equals(account.getPublicKey(), queuedAccount.getPublicKey()));
                assertArrayEquals(v2Signature, queuedAccount.getSignature());
            } finally {
                clearOnlineAccountCaches();
                setOnlineAccountsSignatureV2Height(originalOnlineAccountsSignatureV2Height);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static long setOnlineAccountsSignatureV2Height(long height) throws IllegalAccessException {
        Map<String, Long> featureTriggers = (Map<String, Long>) FieldUtils.readField(BlockChain.getInstance(), "featureTriggers", true);
        String featureTrigger = BlockChain.FeatureTrigger.onlineAccountsSignatureV2Height.name();
        long previousHeight = featureTriggers.get(featureTrigger);
        featureTriggers.put(featureTrigger, height);
        return previousHeight;
    }

    @SuppressWarnings("unchecked")
    private static Map<Long, Set<OnlineAccountData>> getCurrentOnlineAccounts() throws IllegalAccessException {
        return (Map<Long, Set<OnlineAccountData>>) FieldUtils.readField(OnlineAccountsManager.getInstance(), "currentOnlineAccounts", true);
    }

    @SuppressWarnings("unchecked")
    private static Set<OnlineAccountData> getOnlineAccountsImportQueue() throws IllegalAccessException {
        return (Set<OnlineAccountData>) FieldUtils.readField(OnlineAccountsManager.getInstance(), "onlineAccountsImportQueue", true);
    }

    @SuppressWarnings("unchecked")
    private static void clearOnlineAccountCaches() throws IllegalAccessException {
        getCurrentOnlineAccounts().clear();
        getOnlineAccountsImportQueue().clear();

        Map<Long, Map<Byte, byte[]>> currentOnlineAccountsHashes =
                (Map<Long, Map<Byte, byte[]>>) FieldUtils.readField(OnlineAccountsManager.getInstance(), "currentOnlineAccountsHashes", true);
        currentOnlineAccountsHashes.clear();
    }

    @Test
    public void benchmarkOnlineAccountsSignatureValidationV1VsV2() {
        if (!Boolean.getBoolean("runOnlineAccountsBenchmark"))
            System.out.println("Skipping benchmark. Run manually with -DrunOnlineAccountsBenchmark=true");
        Assume.assumeTrue(Boolean.getBoolean("runOnlineAccountsBenchmark"));

        final int warmupIterations = 2;
        final int measuredIterations = 5;

        System.out.println("Online account signature validation benchmark");
        System.out.println("Includes legacy aggregate public-key construction, as block validation does.");
        System.out.println("Times are wall-clock milliseconds on this machine/JVM, not a stable JMH benchmark.");

        for (int accountCount : new int[] { 300, 1000, 5000 }) {
            SignatureBenchmarkData benchmarkData = generateSignatureBenchmarkData(accountCount);

            for (int i = 0; i < warmupIterations; ++i) {
                assertTrue(verifyLegacyOnlineAccountSignatures(benchmarkData));
                assertTrue(verifyV2OnlineAccountSignatures(benchmarkData));
                assertTrue(verifyCachedV2OnlineAccountSignatures(benchmarkData));
            }

            long legacyNanos = 0L;
            long v2Nanos = 0L;
            long cachedV2Nanos = 0L;

            for (int i = 0; i < measuredIterations; ++i) {
                long beforeLegacy = System.nanoTime();
                assertTrue(verifyLegacyOnlineAccountSignatures(benchmarkData));
                legacyNanos += System.nanoTime() - beforeLegacy;

                long beforeV2 = System.nanoTime();
                assertTrue(verifyV2OnlineAccountSignatures(benchmarkData));
                v2Nanos += System.nanoTime() - beforeV2;

                long beforeCachedV2 = System.nanoTime();
                assertTrue(verifyCachedV2OnlineAccountSignatures(benchmarkData));
                cachedV2Nanos += System.nanoTime() - beforeCachedV2;
            }

            double legacyMillis = legacyNanos / 1_000_000.0 / measuredIterations;
            double v2Millis = v2Nanos / 1_000_000.0 / measuredIterations;
            double cachedV2Millis = cachedV2Nanos / 1_000_000.0 / measuredIterations;

            int legacyRawBytes = Transformer.SIGNATURE_LENGTH + accountCount * Transformer.INT_LENGTH;
            int v2RawBytes = accountCount * (Transformer.SIGNATURE_LENGTH + Transformer.INT_LENGTH);

            System.out.printf(
                    "accounts=%d legacyVerify=%.3fms v2Verify=%.3fms cachedV2Verify=%.3fms v2Ratio=%.2fx cachedV2Ratio=%.2fx legacyBytes=%d v2Bytes=%d%n",
                    accountCount, legacyMillis, v2Millis, cachedV2Millis, v2Millis / legacyMillis, cachedV2Millis / legacyMillis, legacyRawBytes, v2RawBytes);
        }
    }

    @Test
    @Ignore(value = "For informational use")
    public void testOnlineAccountNonceCompression() throws IOException {
        List<OnlineAccountData> onlineAccounts = AccountUtils.generateOnlineAccounts(5000);

        // Build array of nonce values
        List<Integer> accountNonces = new ArrayList<>();
        for (OnlineAccountData onlineAccountData : onlineAccounts) {
            accountNonces.add(onlineAccountData.getNonce());
        }

        // Write nonces into ConciseSet
        ConciseSet nonceSet = new ConciseSet();
        nonceSet = nonceSet.convert(accountNonces);
        byte[] conciseEncodedNonces = nonceSet.toByteBuffer().array();

        // Also write to regular byte array of ints, for comparison
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (Integer nonce : accountNonces) {
            bytes.write(Ints.toByteArray(nonce));
        }
        byte[] standardEncodedNonces = bytes.toByteArray();

        System.out.println(String.format("Standard: %d", standardEncodedNonces.length));
        System.out.println(String.format("Concise: %d", conciseEncodedNonces.length));
    }

    private static SignatureBenchmarkData generateSignatureBenchmarkData(int accountCount) {
        SecureRandom secureRandom = new SecureRandom();
        long onlineAccountsTimestamp = 1781295600000L + accountCount;
        byte[] message = Longs.toByteArray(onlineAccountsTimestamp);

        List<byte[]> publicKeys = new ArrayList<>(accountCount);
        List<byte[]> legacySignatures = new ArrayList<>(accountCount);
        List<byte[]> v2Signatures = new ArrayList<>(accountCount);

        for (int i = 0; i < accountCount; ++i) {
            byte[] privateKey = new byte[Transformer.PRIVATE_KEY_LENGTH];
            secureRandom.nextBytes(privateKey);

            byte[] publicKey = new byte[Transformer.PUBLIC_KEY_LENGTH];
            Qortal25519Extras.generatePublicKey(privateKey, 0, publicKey, 0);

            publicKeys.add(publicKey);
            legacySignatures.add(Qortal25519Extras.signForAggregation(privateKey, message));
            v2Signatures.add(Qortal25519Extras.sign(privateKey, message));
        }

        byte[] aggregateSignature = Qortal25519Extras.aggregateSignatures(legacySignatures);
        return new SignatureBenchmarkData(onlineAccountsTimestamp, message, publicKeys, aggregateSignature, v2Signatures);
    }

    private static boolean verifyLegacyOnlineAccountSignatures(SignatureBenchmarkData benchmarkData) {
        byte[] aggregatePublicKey = Qortal25519Extras.aggregatePublicKeys(benchmarkData.publicKeys);
        return Qortal25519Extras.verifyAggregated(aggregatePublicKey, benchmarkData.aggregateSignature, benchmarkData.message);
    }

    private static boolean verifyV2OnlineAccountSignatures(SignatureBenchmarkData benchmarkData) {
        for (int i = 0; i < benchmarkData.publicKeys.size(); ++i)
            if (!Qortal25519Extras.verify(benchmarkData.publicKeys.get(i), benchmarkData.v2Signatures.get(i), benchmarkData.message))
                return false;

        return true;
    }

    private static boolean verifyCachedV2OnlineAccountSignatures(SignatureBenchmarkData benchmarkData) {
        for (int i = 0; i < benchmarkData.publicKeys.size(); ++i)
            if (!OnlineAccountsManager.getInstance().verifyOrCacheV2OnlineAccountSignature(
                    benchmarkData.publicKeys.get(i),
                    benchmarkData.v2Signatures.get(i),
                    benchmarkData.onlineAccountsTimestamp))
                return false;

        return true;
    }

    private static class SignatureBenchmarkData {
        private final long onlineAccountsTimestamp;
        private final byte[] message;
        private final List<byte[]> publicKeys;
        private final byte[] aggregateSignature;
        private final List<byte[]> v2Signatures;

        private SignatureBenchmarkData(long onlineAccountsTimestamp, byte[] message, List<byte[]> publicKeys, byte[] aggregateSignature, List<byte[]> v2Signatures) {
            this.onlineAccountsTimestamp = onlineAccountsTimestamp;
            this.message = message;
            this.publicKeys = publicKeys;
            this.aggregateSignature = aggregateSignature;
            this.v2Signatures = v2Signatures;
        }
    }
}
