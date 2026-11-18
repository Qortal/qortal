package org.qortal.rngit;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.data.transaction.ArbitraryTransactionData;
import org.qortal.data.transaction.RegisterNameTransactionData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.rngit.RngitPermissions.Permission;
import org.qortal.test.common.ArbitraryUtils;
import org.qortal.test.common.BlockUtils;
import org.qortal.test.common.Common;
import org.qortal.test.common.TransactionUtils;
import org.qortal.test.common.transaction.TestTransaction;
import org.qortal.transaction.RegisterNameTransaction;
import org.qortal.utils.Base58;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Push flow 2 on Core's test chain: a node without the owner's key stages a
 * permitted push, prepares the resources that publish it, and the owner
 * publishes them as Hub's PUBLISH_MULTIPLE_QDN_RESOURCES would: each one a
 * single file from its base64 data.
 */
class RngitQdnStagingTests {

    private static final String NAME = "gitstage";
    private static final String CREATOR = "11111111111111111111111111111111";
    private static final String PUSHER = "33333333333333333333333333333333";
    private static final String STRANGER = "22222222222222222222222222222222";

    private Path tmp;
    private Path work;

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
        tmp = Files.createTempDirectory("rngit-staging");
        work = tmp.resolve("work");
    }

    private RevCommit commit(Git git, String content) throws Exception {
        Files.writeString(work.resolve("f.txt"), content);
        git.add().addFilepattern("f.txt").call();
        return git.commit().setMessage(content).setAuthor("t", "t@example.invalid").setSign(false).call();
    }

    private byte[] bundleOf(List<String> haves) throws Exception {
        Path bundle = tmp.resolve("b-" + System.nanoTime() + ".bundle");
        assertTrue(RngitGit.createBundle(work.resolve(".git"), List.of("refs/heads/master"), haves, bundle));
        return Files.readAllBytes(bundle);
    }

    private static RngitRepositories node(Path cache) {
        RngitRepositories repositories = new RngitRepositories(Map.of(), Map.of(), Set.of());
        RngitQdnGateway gateway = new RngitQdnGateway(cache);
        gateway.setStaging(new RngitQdnStaging(cache.resolveSibling(cache.getFileName() + "-staging"), gateway));
        repositories.setQdnGateway(gateway);
        return repositories;
    }

    @Test
    void stagePrepareAndPublishAsHubWould() throws Exception {
        try (Repository repository = RepositoryManager.getRepository();
             Git git = Git.init().setDirectory(work.toFile()).setInitialBranch("master").call()) {
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            registerName(repository, alice, NAME);

            // The owner's node: create, allow PUSHER to write, publish a first commit
            Path config = Files.createDirectories(tmp.resolve("rngit"));
            Files.writeString(config.resolve(RngitQdnPublisher.KEY_FILE), Base58.encode(alice.getPrivateKey()));
            RngitRepositories owner = node(tmp.resolve("cache-owner"));
            RngitQdnGateway ownerGateway = owner.getQdnGateway();
            RngitQdnPublisher publisher = RngitQdnPublisher.load(config, ownerGateway);
            ownerGateway.setPublisher(publisher);
            publisher.create(NAME, "repo", CREATOR);
            BlockUtils.mintBlock(repository);
            publisher.setAllowed(NAME, "repo", "adm:" + CREATOR + "\nw:" + PUSHER);
            BlockUtils.mintBlock(repository);
            RevCommit first = commit(git, "one");
            synchronized (ownerGateway.lock(NAME, "repo")) {
                Path b = tmp.resolve("first.bundle");
                Files.write(b, bundleOf(List.of()));
                assertTrue(RngitGit.applyBundle(ownerGateway.cachePath(NAME, "repo"), b, "refs/heads/master", "refs/heads/master", false).ok);
            }
            publisher.publish(NAME, "repo");
            BlockUtils.mintBlock(repository);

            // Another node, without the key: the descriptor's rules decide who may stage
            RngitRepositories other = node(tmp.resolve("cache-other"));
            RngitQdnStaging staging = other.getQdnGateway().getStaging();
            assertTrue(other.resolvePermission(PUSHER, NAME, "repo", Permission.WRITE));
            assertFalse(other.resolvePermission(STRANGER, NAME, "repo", Permission.WRITE));
            assertFalse(other.getQdnGateway().isWritableHere(NAME));

            RevCommit second = commit(git, "two");
            RngitQdnStaging.Change change = new RngitQdnStaging.Change();
            change.kind = "bundle";
            change.pusher = PUSHER;
            change.localRef = "refs/heads/master";
            change.ref = "refs/heads/master";
            RngitQdnStaging.Change staged = staging.stage(NAME, "repo", change, bundleOf(List.of(first.name())));
            assertEquals(1, staged.id);
            assertEquals(first.name(), staged.base);
            assertEquals(second.name(), staged.sha);
            assertEquals(first.name(), RngitGit.resolveRef(other.getRepository(NAME, "repo").getPath(), "refs/heads/master"),
                    "staging does not change the repository");

            // A change that does not apply to the current state is refused at staging
            RngitQdnStaging.Change rewind = new RngitQdnStaging.Change();
            rewind.kind = "update_ref";
            rewind.pusher = PUSHER;
            rewind.ref = "refs/heads/other";
            rewind.sha = "a".repeat(40);
            assertThrows(RngitQdnStaging.ConflictException.class, () -> staging.stage(NAME, "repo", rewind, null),
                    "an object the repository does not have");

            List<Map<String, Object>> listed = other.stagedChanges(NAME, "repo");
            assertEquals(1, listed.size());
            assertEquals(true, listed.get(0).get("applies"));

            // Prepare, then publish as the owner the way Hub does: one file per resource from data64
            Map<String, Object> prepared = other.prepareStagedChange(NAME, "repo", 1);
            assertNotNull(prepared);
            assertEquals("PUBLISH_MULTIPLE_QDN_RESOURCES", prepared.get("action"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> resources = (List<Map<String, Object>>) prepared.get("resources");
            assertEquals(List.of("repo~b~2", "repo"), List.of(resources.get(0).get("identifier"), resources.get(1).get("identifier")));
            for (Map<String, Object> resource : resources) {
                Path file = Files.createDirectories(tmp.resolve("hub-" + resource.get("identifier"))).resolve((String) resource.get("filename"));
                Files.write(file, Base64.getDecoder().decode((String) resource.get("data64")));
                ArbitraryUtils.createAndMintTxn(repository, Base58.encode(alice.getPublicKey()), file, NAME,
                        (String) resource.get("identifier"), ArbitraryTransactionData.Method.PUT, RngitQdn.SERVICE, alice);
            }

            assertEquals(second.name(), RngitGit.resolveRef(other.getRepository(NAME, "repo").getPath(), "refs/heads/master"));
            assertTrue(other.stagedChanges(NAME, "repo").isEmpty(), "published changes are cleared");
            assertEquals(second.name(), RngitGit.resolveRef(node(tmp.resolve("cache-fresh")).getRepository(NAME, "repo").getPath(),
                    "refs/heads/master"), "a fresh node agrees");

            // The operator can discard a staged change
            commit(git, "three");
            RngitQdnStaging.Change third = new RngitQdnStaging.Change();
            third.kind = "bundle";
            third.pusher = PUSHER;
            third.localRef = "refs/heads/master";
            third.ref = "refs/heads/master";
            long thirdId = staging.stage(NAME, "repo", third, bundleOf(List.of(second.name()))).id;
            assertTrue(other.removeStagedChange(NAME, "repo", thirdId));
            assertTrue(other.stagedChanges(NAME, "repo").isEmpty());
        }
    }

    private static void registerName(Repository repository, PrivateKeyAccount owner, String name) throws DataException {
        RegisterNameTransactionData data = new RegisterNameTransactionData(TestTransaction.generateBase(owner), name, "");
        data.setFee(new RegisterNameTransaction(null, null).getUnitFee(data.getTimestamp()));
        TransactionUtils.signAndMint(repository, data, owner);
    }
}
