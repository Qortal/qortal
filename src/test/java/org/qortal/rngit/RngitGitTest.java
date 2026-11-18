package org.qortal.rngit;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The JGit operations behind the rngit handlers, on real repositories: the list
 * format, bundles that apply elsewhere, thin and empty bundles, and the
 * fast-forward rule on push. Interop with the {@code git} CLI is covered by the
 * live test, which drives the stock client.
 */
class RngitGitTest {

    @TempDir
    Path tmp;

    private Path work;
    private Path server;
    private RevCommit first;
    private RevCommit second;

    @BeforeEach
    void setUp() throws Exception {
        work = tmp.resolve("work");
        server = tmp.resolve("server.git");

        try (Git git = Git.init().setDirectory(work.toFile()).setInitialBranch("master").call()) {
            Files.writeString(work.resolve("a.txt"), "one");
            git.add().addFilepattern("a.txt").call();
            first = git.commit().setMessage("first").setAuthor("t", "t@example.invalid").setSign(false).call();
            Files.writeString(work.resolve("a.txt"), "two");
            git.add().addFilepattern("a.txt").call();
            second = git.commit().setMessage("second").setAuthor("t", "t@example.invalid").setSign(false).call();
        }

        RngitGit.initBare(server);
    }

    private Path bundleFrom(Path repo, List<String> refs, List<String> haves) throws Exception {
        Path bundle = Files.createTempFile(tmp, "b-", ".bundle");
        assertTrue(RngitGit.createBundle(repo, refs, haves, bundle), "bundle written");
        return bundle;
    }

    @Test
    void bareDetection() {
        assertTrue(RngitGit.isGitRepository(server));
        assertTrue(RngitGit.isBareRepository(server));
        assertFalse(RngitGit.isGitRepository(tmp.resolve("nothing")));
    }

    @Test
    void emptyRepositoryListsOnlyHead() throws Exception {
        assertEquals("@refs/heads/master HEAD\n", RngitGit.listRefs(server));
    }

    @Test
    void pushedBundleAppliesAndLists() throws Exception {
        Path bundle = bundleFrom(work.resolve(".git"), List.of("refs/heads/master"), List.of());

        RngitGit.Result applied = RngitGit.applyBundle(server, bundle, "refs/heads/master", "refs/heads/master", false);
        assertTrue(applied.ok, applied.message);
        assertEquals(second.name(), RngitGit.resolveRef(server, "refs/heads/master"));
        assertEquals(second.name() + " refs/heads/master\n@refs/heads/master HEAD\n", RngitGit.listRefs(server));
    }

    @Test
    void thinBundleNeedsItsPrerequisite() throws Exception {
        Path thin = bundleFrom(work.resolve(".git"), List.of("refs/heads/master"), List.of(first.name()));

        assertFalse(RngitGit.applyBundle(server, thin, "refs/heads/master", "refs/heads/master", false).ok,
                "server lacks the prerequisite commit");

        // Give the server the first commit, then the thin bundle applies on top
        try (Git git = Git.open(work.toFile())) {
            git.branchCreate().setName("base").setStartPoint(first).call();
        }
        Path base = bundleFrom(work.resolve(".git"), List.of("refs/heads/base"), List.of());
        assertTrue(RngitGit.applyBundle(server, base, "refs/heads/base", "refs/heads/master", false).ok);
        assertTrue(RngitGit.applyBundle(server, thin, "refs/heads/master", "refs/heads/master", false).ok);
        assertEquals(second.name(), RngitGit.resolveRef(server, "refs/heads/master"));
    }

    @Test
    void fullyKnownHistoryIsAnEmptyBundle() throws Exception {
        Path bundle = Files.createTempFile(tmp, "e-", ".bundle");
        assertFalse(RngitGit.createBundle(work.resolve(".git"), List.of("refs/heads/master"), List.of(second.name()), bundle));
    }

    @Test
    void nonFastForwardNeedsForce() throws Exception {
        Path full = bundleFrom(work.resolve(".git"), List.of("refs/heads/master"), List.of());
        assertTrue(RngitGit.applyBundle(server, full, "refs/heads/master", "refs/heads/master", false).ok);

        // Rewind the work branch and push the older tip back
        try (Git git = Git.open(work.toFile())) {
            git.branchCreate().setName("old").setStartPoint(first).call();
        }
        Path old = bundleFrom(work.resolve(".git"), List.of("refs/heads/old"), List.of());

        assertFalse(RngitGit.applyBundle(server, old, "refs/heads/old", "refs/heads/master", false).ok);
        assertEquals(second.name(), RngitGit.resolveRef(server, "refs/heads/master"));
        assertTrue(RngitGit.applyBundle(server, old, "refs/heads/old", "refs/heads/master", true).ok);
        assertEquals(first.name(), RngitGit.resolveRef(server, "refs/heads/master"));
    }

    @Test
    void updateAndDeleteRefs() throws Exception {
        Path full = bundleFrom(work.resolve(".git"), List.of("refs/heads/master"), List.of());
        assertTrue(RngitGit.applyBundle(server, full, "refs/heads/master", "refs/heads/master", false).ok);

        assertTrue(RngitGit.updateRef(server, "refs/heads/feature", first.name()).ok);
        assertEquals(first.name(), RngitGit.resolveRef(server, "refs/heads/feature"));
        assertTrue(RngitGit.hasObject(server, first.name()));

        assertTrue(RngitGit.deleteRef(server, "refs/heads/feature").ok);
        assertNull(RngitGit.resolveRef(server, "refs/heads/feature"));
    }

    @Test
    void upstreamMetadataRoundTripsThroughGitConfig() throws Exception {
        assertNull(RngitGit.rngitType(server));
        RngitGit.setUpstream(server, "mirror", "https://example.invalid/repo.git");
        assertTrue(RngitGit.setUpstreamSynced(server));

        assertEquals("mirror", RngitGit.rngitType(server));
        assertEquals("https://example.invalid/repo.git", RngitGit.upstreamSource(server));
        assertTrue(RngitGit.upstreamSynced(server) > 1_700_000_000L);
        assertTrue(Files.readString(server.resolve("config")).contains("[repository \"rngit.upstream\"]"),
                "stored where git config repository.rngit.upstream.source finds it");
    }

    @Test
    void allRefsBundleAppliesForcedAndHeadFollowsUpstream() throws Exception {
        try (Git git = Git.open(work.toFile())) {
            git.branchCreate().setName("main").setStartPoint(first).call();
        }
        Path bundle = bundleFrom(work.resolve(".git"), List.of("refs/heads/master", "refs/heads/main"), List.of());
        assertTrue(RngitGit.applyBundleAllRefs(server, bundle).ok);
        assertEquals(second.name(), RngitGit.resolveRef(server, "refs/heads/master"));
        assertEquals(first.name(), RngitGit.resolveRef(server, "refs/heads/main"));

        assertTrue(RngitGit.updateHead(server, "refs/heads/main"));
        assertTrue(RngitGit.listRefs(server).endsWith("@refs/heads/main HEAD\n"));

        assertTrue(RngitGit.updateHead(server, "refs/heads/missing"), "unknown upstream HEAD falls back");
        assertTrue(RngitGit.listRefs(server).endsWith("@refs/heads/main HEAD\n"), "to the first branch by name");
    }

    @Test
    void sourceUrlSchemes() {
        assertTrue(RngitUpstream.isAllowedSource("https://github.com/x/y"));
        assertTrue(RngitUpstream.isAllowedSource("RNS://0123456789abcdef0123456789abcdef/g/r"));
        assertTrue(RngitUpstream.isAllowedSource("ssh://git@host/x"));
        assertFalse(RngitUpstream.isAllowedSource("file:///etc"));
        assertFalse(RngitUpstream.isAllowedSource("git@host:x/y"), "scp-like ssh has no scheme, as in the reference");

        assertEquals(List.of("0123456789abcdef0123456789abcdef", "g", "r"),
                List.of(RngitClient.parseRnsUrl("rns://0123456789abcdef0123456789abcdef/g/r")));
        assertNull(RngitClient.parseRnsUrl("rns://short/g/r"));
        assertNull(RngitClient.parseRnsUrl("rns://0123456789abcdef0123456789abcdef/g"));
    }
}
