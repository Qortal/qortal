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
import org.qortal.test.common.Common;
import org.qortal.test.common.TransactionUtils;
import org.qortal.test.common.transaction.TestTransaction;
import org.qortal.transaction.RegisterNameTransaction;
import org.qortal.utils.Base58;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The QDN read gateway on Core's test chain: a repository published to QDN as
 * a descriptor plus bundles is materialised into a bare repository, refreshed
 * when the descriptor changes, and served read-only.
 * <p>
 * JUnit 5, calling {@link Common}'s repository setup itself: Core's pom has no
 * vintage engine, so JUnit 4 tests do not run under {@code mvn test}.
 */
class RngitQdnGatewayTests {

    private static final String NAME = "gitter";

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
        tmp = Files.createTempDirectory("rngit-qdn-test");
        work = tmp.resolve("work");
    }

    private RevCommit commit(Git git, String file, String content) throws Exception {
        Files.writeString(work.resolve(file), content);
        git.add().addFilepattern(file).call();
        return git.commit().setMessage(content).setAuthor("t", "t@example.invalid").setSign(false).call();
    }

    /** Publishes a bundle of master (thin against {@code have}) as bundle number {@code n}. */
    private String publishBundle(Repository repository, PrivateKeyAccount alice, int n, String have) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("bundle-" + n));
        assertTrue(RngitGit.createBundle(work.resolve(".git"), List.of("refs/heads/master"),
                have == null ? List.of() : List.of(have), dir.resolve(RngitQdn.BUNDLE_FILE)));
        String identifier = RngitQdn.bundleIdentifier("repo", n);
        ArbitraryUtils.createAndMintTxn(repository, Base58.encode(alice.getPublicKey()), dir, NAME, identifier,
                ArbitraryTransactionData.Method.PUT, RngitQdn.SERVICE, alice);
        return identifier;
    }

    private void publishDescriptor(Repository repository, PrivateKeyAccount alice, String tip, List<String> bundles, int version)
            throws Exception {
        RngitQdn.Descriptor descriptor = new RngitQdn.Descriptor();
        descriptor.repository = "repo";
        descriptor.head = "refs/heads/master";
        descriptor.refs = Map.of("refs/heads/master", tip);
        descriptor.bundles = bundles;
        descriptor.allowed = "r:all";
        Path dir = tmp.resolve("descriptor-" + version);
        RngitQdn.writeDescriptor(descriptor, dir);
        ArbitraryUtils.createAndMintTxn(repository, Base58.encode(alice.getPublicKey()), dir, NAME, "repo",
                ArbitraryTransactionData.Method.PUT, RngitQdn.SERVICE, alice);
    }

    @Test
    void materialisesAndRefreshesFromQdn() throws Exception {
        try (Repository repository = RepositoryManager.getRepository();
             Git git = Git.init().setDirectory(work.toFile()).setInitialBranch("master").call()) {
            PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
            registerName(repository, alice, NAME);

            RevCommit first = commit(git, "a.txt", "one");
            String b1 = publishBundle(repository, alice, 1, null);
            publishDescriptor(repository, alice, first.name(), List.of(b1), 1);

            RngitQdnGateway gateway = new RngitQdnGateway(tmp.resolve("cache"));
            RngitRepositories repositories = new RngitRepositories(Map.of(), Map.of(), java.util.Set.of());
            repositories.setQdnGateway(gateway);

            assertNull(repositories.getGroup("nobody-has-this-name"), "unregistered names are not groups");
            assertNotNull(repositories.getGroup(NAME));
            assertTrue(repositories.isQdnGroup(NAME));

            RngitRepositories.Repository repo = repositories.getRepository(NAME, "repo");
            assertNotNull(repo);
            assertEquals(first.name(), RngitGit.resolveRef(repo.getPath(), "refs/heads/master"));
            assertEquals(first.name() + " refs/heads/master\n@refs/heads/master HEAD\n", RngitGit.listRefs(repo.getPath()));

            String anyone = "00000000000000000000000000000001";
            assertTrue(repositories.resolvePermission(anyone, NAME, "repo", Permission.READ));
            assertFalse(repositories.resolvePermission(anyone, NAME, "repo", Permission.WRITE), "read-only until publishing exists");
            assertFalse(repositories.resolveGroupPermission(anyone, NAME, Permission.CREATE));

            // A second push: a thin bundle on top of the first, and an updated descriptor
            RevCommit second = commit(git, "a.txt", "two");
            String b2 = publishBundle(repository, alice, 2, first.name());
            publishDescriptor(repository, alice, second.name(), List.of(b1, b2), 2);

            RngitRepositories.Repository refreshed = repositories.getRepository(NAME, "repo");
            assertEquals(second.name(), RngitGit.resolveRef(refreshed.getPath(), "refs/heads/master"));
            assertEquals(List.of(b1, b2), Files.readAllLines(refreshed.getPath().resolve(RngitQdnGateway.APPLIED_FILE)));

            // The cache deleted while the node runs (runbook step 7): rebuilt from QDN
            org.apache.commons.io.FileUtils.deleteDirectory(tmp.resolve("cache").toFile());
            RngitRepositories.Repository rebuilt = repositories.getRepository(NAME, "repo");
            assertNotNull(rebuilt);
            assertEquals(second.name(), RngitGit.resolveRef(rebuilt.getPath(), "refs/heads/master"));

            assertNull(repositories.getRepository(NAME, "other"), "no such repository under the name");
        }
    }

    private static void registerName(Repository repository, PrivateKeyAccount owner, String name) throws DataException {
        RegisterNameTransactionData data = new RegisterNameTransactionData(TestTransaction.generateBase(owner), name, "");
        data.setFee(new RegisterNameTransaction(null, null).getUnitFee(data.getTimestamp()));
        TransactionUtils.signAndMint(repository, data, owner);
    }
}
