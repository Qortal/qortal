package org.qortal.rngit;

import io.reticulum.identity.Identity;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.data.transaction.BuyNameTransactionData;
import org.qortal.data.transaction.RegisterNameTransactionData;
import org.qortal.data.transaction.SellNameTransactionData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.rngit.RngitPermissions.Permission;
import org.qortal.test.common.ArbitraryUtils;
import org.qortal.test.common.Common;
import org.qortal.test.common.GroupUtils;
import org.qortal.test.common.TransactionUtils;
import org.qortal.test.common.transaction.TestTransaction;
import org.qortal.transaction.RegisterNameTransaction;
import org.qortal.utils.Base58;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.apache.commons.codec.binary.Hex.encodeHexString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Account to RNS identity bindings on Core's test chain, and the name:, group:
 * and owner rule targets that use them.
 */
class RngitIdentityBindingsTests {

    private Path tmp;

    @BeforeAll
    static void openRepository() throws DataException {
        Common.setRepositoryInMemory();
    }

    @AfterAll
    static void closeRepository() throws DataException {
        Common.closeRepository();
    }

    @BeforeEach
    void beforeTest() throws Exception {
        Common.useDefaultSettings();
        tmp = Files.createTempDirectory("rngit-bindings");
    }

    private void publishBinding(Repository repository, PrivateKeyAccount account, String name, RngitIdentityBindings.Record record)
            throws Exception {
        Path file = tmp.resolve(name + "-" + System.nanoTime() + ".json");
        RngitIdentityBindings.writeRecord(record, file);
        ArbitraryUtils.createAndMintTxn(repository, Base58.encode(account.getPublicKey()), file, name,
                RngitIdentityBindings.IDENTIFIER, org.qortal.data.transaction.ArbitraryTransactionData.Method.PUT,
                RngitIdentityBindings.SERVICE, account);
    }

    @Test
    void bindingsAndQortalTargets() throws Exception {
        try (Repository repository = RepositoryManager.getRepository()) {
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");
            registerName(repository, alice, "bindtest");

            Identity mine = new Identity();
            Identity stranger = new Identity();
            String mineHex = encodeHexString(mine.getHash());
            String strangerHex = encodeHexString(stranger.getHash());

            // One valid entry, and one whose signature was made over another account's message
            RngitIdentityBindings.Record record = RngitIdentityBindings.createRecord(alice.getAddress(), List.of(mine));
            RngitIdentityBindings.Record forged = RngitIdentityBindings.createRecord(bob.getAddress(), List.of(stranger));
            record.identities.add(forged.identities.get(0));
            publishBinding(repository, alice, "bindtest", record);

            RngitIdentityBindings bindings = new RngitIdentityBindings();
            assertEquals(Set.of(mineHex), bindings.boundIdentities(alice.getAddress()));
            assertEquals(Set.of(), bindings.boundIdentities(bob.getAddress()));

            // name: and group: targets through the registry, for a configured group
            int groupId = GroupUtils.createGroup(repository, alice, "rngit-devs", true);
            RngitRepositories repositories = new RngitRepositories(Map.of(),
                    Map.of("public", List.of("w:name:bindtest", "r:group:" + groupId)), Set.of());
            Path groupDir = Files.createDirectories(tmp.resolve("public"));
            repositories.loadGroup("public", groupDir);
            repositories.setIdentityBindings(bindings);

            assertTrue(repositories.resolveGroupPermission(mineHex, "public", Permission.WRITE), "bound to bindtest's owner");
            assertFalse(repositories.resolveGroupPermission(strangerHex, "public", Permission.WRITE));
            assertTrue(repositories.resolveGroupPermission(mineHex, "public", Permission.READ), "alice is a group member");
            assertFalse(repositories.resolveGroupPermission(strangerHex, "public", Permission.READ));
            assertFalse(repositories.resolveGroupPermission(mineHex, "public", Permission.CREATE));

            // owner: only for a Qortal name's group, resolved through the name's owner
            RngitRepositories qdn = new RngitRepositories(Map.of(), Map.of("bindtest", List.of("c:owner")), Set.of());
            RngitQdnGateway gateway = new RngitQdnGateway(tmp.resolve("cache"));
            qdn.setQdnGateway(gateway);
            qdn.setIdentityBindings(bindings);
            Files.writeString(Files.createDirectories(tmp.resolve("node")).resolve(RngitQdnPublisher.KEY_FILE),
                    Base58.encode(alice.getPrivateKey()));
            gateway.setPublisher(RngitQdnPublisher.load(tmp.resolve("node"), gateway));
            assertTrue(qdn.resolveGroupPermission(mineHex, "bindtest", Permission.CREATE));
            assertFalse(qdn.resolveGroupPermission(strangerHex, "bindtest", Permission.CREATE));

            // Sold: the record under bindtest was signed by alice, so it binds nothing to bob,
            // and alice's binding returns once she republishes it under a name she owns again
            SellNameTransactionData sell = new SellNameTransactionData(TestTransaction.generateBase(alice), "bindtest", 100000000L);
            TransactionUtils.signAndMint(repository, sell, alice);
            BuyNameTransactionData buy = new BuyNameTransactionData(TestTransaction.generateBase(bob), "bindtest", 100000000L, alice.getAddress());
            TransactionUtils.signAndMint(repository, buy, bob);

            RngitIdentityBindings fresh = new RngitIdentityBindings();
            assertEquals(Set.of(), fresh.boundIdentities(bob.getAddress()), "a sold name carries no binding to the buyer");
            assertEquals(Set.of(), fresh.boundIdentities(alice.getAddress()));

            assertFalse(repositories.resolveGroupPermission(mineHex, "public", Permission.WRITE), "name:bindtest now means bob");

            registerName(repository, alice, "alicenew");
            publishBinding(repository, alice, "alicenew", RngitIdentityBindings.createRecord(alice.getAddress(), List.of(mine)));
            assertEquals(Set.of(mineHex), new RngitIdentityBindings().boundIdentities(alice.getAddress()));
        }
    }

    private static void registerName(Repository repository, PrivateKeyAccount owner, String name) throws DataException {
        RegisterNameTransactionData data = new RegisterNameTransactionData(TestTransaction.generateBase(owner), name, "");
        data.setFee(new RegisterNameTransaction(null, null).getUnitFee(data.getTimestamp()));
        TransactionUtils.signAndMint(repository, data, owner);
    }
}
