package org.qortal.rngit;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Read-only browsing behind the /git REST API, on a real bare repository. */
class RngitBrowseTest {

    @TempDir
    Path tmp;

    private Path bare;
    private RevCommit first;
    private RevCommit second;

    @BeforeEach
    void setUp() throws Exception {
        Path work = tmp.resolve("work");
        try (Git git = Git.init().setDirectory(work.toFile()).setInitialBranch("main").call()) {
            Files.createDirectories(work.resolve("src/lib"));
            Files.writeString(work.resolve("README.md"), "hello\n");
            Files.writeString(work.resolve("src/lib/util.txt"), "one\n");
            git.add().addFilepattern(".").call();
            first = git.commit().setMessage("first\n\nwith a body").setAuthor("Ann", "ann@example.invalid").setSign(false).call();

            Files.move(work.resolve("src/lib/util.txt"), work.resolve("src/lib/helpers.txt"));
            Files.writeString(work.resolve("README.md"), "hello\nworld\n");
            git.add().addFilepattern(".").call();
            git.rm().addFilepattern("src/lib/util.txt").call();
            second = git.commit().setMessage("second").setAuthor("Bob", "bob@example.invalid").setSign(false).call();
        }
        bare = tmp.resolve("bare.git");
        Git.cloneRepository().setURI(work.toUri().toString()).setDirectory(bare.toFile()).setBare(true).call().close();
    }

    @Test
    void logNewestFirstWithPaging() throws Exception {
        List<Map<String, Object>> log = RngitBrowse.log(bare, null, 0, 50);
        assertEquals(List.of(second.name(), first.name()), List.of(log.get(0).get("sha"), log.get(1).get("sha")));
        assertEquals("first", log.get(1).get("summary"));
        assertEquals("Ann", ((Map<?, ?>) log.get(1).get("author")).get("name"));
        assertEquals(List.of(first.name()), log.get(0).get("parents"));

        List<Map<String, Object>> paged = RngitBrowse.log(bare, "main", 1, 1);
        assertEquals(1, paged.size());
        assertEquals(first.name(), paged.get(0).get("sha"));
        assertEquals(first.name(), RngitBrowse.log(bare, first.name(), 0, 5).get(0).get("sha"), "a SHA works as ref");
    }

    @Test
    void treeAndBlob() throws Exception {
        List<Map<String, Object>> root = RngitBrowse.tree(bare, null, "");
        assertEquals("README.md", root.get(0).get("name"));
        assertEquals("blob", root.get(0).get("type"));
        assertEquals(12L, root.get(0).get("size"));
        assertEquals("tree", root.get(1).get("type"));

        List<Map<String, Object>> lib = RngitBrowse.tree(bare, "main", "/src/lib/");
        assertEquals(1, lib.size());
        assertEquals("src/lib/helpers.txt", lib.get(0).get("path"));
        assertEquals("src/lib/util.txt", RngitBrowse.tree(bare, first.name(), "src/lib").get(0).get("path"), "older ref");

        assertEquals("hello\nworld\n", new String(RngitBrowse.blob(bare, null, "README.md"), StandardCharsets.UTF_8));
        assertEquals("hello\n", new String(RngitBrowse.blob(bare, first.name(), "README.md"), StandardCharsets.UTF_8));
    }

    @Test
    void commitChangesAndDiff() throws Exception {
        Map<String, Object> commit = RngitBrowse.commit(bare, second.name(), true);
        assertEquals("second", commit.get("message"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> changes = (List<Map<String, Object>>) commit.get("changes");
        assertEquals(2, changes.size());
        assertTrue(changes.stream().anyMatch(c -> "rename".equals(c.get("type"))
                && "src/lib/util.txt".equals(c.get("oldPath")) && "src/lib/helpers.txt".equals(c.get("newPath"))));
        assertTrue(((String) commit.get("diff")).contains("+world"));
        assertFalse((Boolean) commit.get("diffTruncated"));

        Map<String, Object> root = RngitBrowse.commit(bare, first.name(), false);
        assertEquals(2, ((List<?>) root.get("changes")).size(), "the root commit adds everything");
        assertFalse(root.containsKey("diff"));
    }

    @Test
    void unknownThingsAndTraversalAreNotFound() {
        assertThrows(RngitBrowse.NotFoundException.class, () -> RngitBrowse.log(bare, "nope", 0, 5));
        assertThrows(RngitBrowse.NotFoundException.class, () -> RngitBrowse.log(bare, "--all", 0, 5));
        assertThrows(RngitBrowse.NotFoundException.class, () -> RngitBrowse.tree(bare, null, "missing"));
        assertThrows(RngitBrowse.NotFoundException.class, () -> RngitBrowse.tree(bare, null, "README.md"), "a file is no directory");
        assertThrows(RngitBrowse.NotFoundException.class, () -> RngitBrowse.blob(bare, null, "src"), "a directory is no file");
        assertThrows(RngitBrowse.NotFoundException.class, () -> RngitBrowse.blob(bare, null, "../config"));
        assertThrows(RngitBrowse.NotFoundException.class, () -> RngitBrowse.commit(bare, "a".repeat(40), false));
        assertThrows(RngitBrowse.NotFoundException.class, () -> RngitBrowse.commit(bare, "xyz", false));
    }
}
