package org.qortal.api.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.qortal.api.ApiException;
import org.qortal.rngit.RngitRepositories;

import javax.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * The /git REST endpoints over a configured group with one public and one
 * private repository: only what an "all" rule grants is served. QDN groups go
 * through the same code with the registry's QDN gateway.
 */
class GitResourceTest {

    @TempDir
    Path tmp;

    private final ObjectMapper json = new ObjectMapper();
    private GitResource resource;
    private RevCommit tip;

    @BeforeEach
    void setUp() throws Exception {
        Path group = Files.createDirectories(tmp.resolve("public"));
        Path work = tmp.resolve("work");
        try (Git git = Git.init().setDirectory(work.toFile()).setInitialBranch("main").call()) {
            Files.writeString(work.resolve("README.md"), "hello\n");
            git.add().addFilepattern(".").call();
            tip = git.commit().setMessage("first").setAuthor("Ann", "ann@example.invalid").setSign(false).call();
        }
        for (String name : List.of("open", "secret")) {
            Git.cloneRepository().setURI(work.toUri().toString()).setDirectory(group.resolve(name).toFile()).setBare(true).call().close();
        }
        Files.writeString(tmp.resolve("public/open.allowed"), "r:all\n");
        Files.writeString(tmp.resolve("public/secret.allowed"), "r:0123456789abcdef0123456789abcdef\n");

        RngitRepositories repositories = new RngitRepositories(Map.of(), Map.of(), Set.of());
        repositories.loadGroup("public", group);
        GitResource.registry = () -> repositories;
        GitResource.server = () -> null;

        resource = new GitResource();
        resource.request = mock(HttpServletRequest.class);
    }

    @AfterEach
    void restore() {
        GitResource.registry = () -> null;
    }

    @Test
    void servesPublicRepositories() throws Exception {
        assertEquals(List.of("open"), json.readValue(resource.listRepositories("public"), List.class), "secret is not listed");

        Map<?, ?> summary = json.readValue(resource.getRepository("public", "open"), Map.class);
        assertEquals("refs/heads/main", summary.get("head"));
        assertEquals(Map.of("refs/heads/main", tip.name()), summary.get("refs"));
        assertEquals(false, summary.get("qdn"));

        List<?> log = json.readValue(resource.getLog("public", "open", null, 0, 50), List.class);
        assertEquals(tip.name(), ((Map<?, ?>) log.get(0)).get("sha"));

        List<?> tree = json.readValue(resource.getTree("public", "open", "main", null), List.class);
        assertEquals("README.md", ((Map<?, ?>) tree.get(0)).get("name"));

        assertEquals("hello\n", new String(resource.getBlob("public", "open", null, "README.md"), StandardCharsets.UTF_8));

        Map<?, ?> commit = json.readValue(resource.getCommit("public", "open", tip.name(), true), Map.class);
        assertTrue(((String) commit.get("diff")).contains("+hello"));
    }

    @Test
    void refusesWhatAnyoneMayNotRead() {
        assertThrows(ApiException.class, () -> resource.getRepository("public", "secret"));
        assertThrows(ApiException.class, () -> resource.getBlob("public", "secret", null, "README.md"));
        assertThrows(ApiException.class, () -> resource.getRepository("public", "missing"));
        assertThrows(ApiException.class, () -> resource.getBlob("public", "open", null, "../secret/config"));
        assertFalse(resource.listRepositories("nogroup").contains("open"));
        assertThrows(ApiException.class, () -> resource.getDescriptor("public", "open"), "only QDN repositories have one");
        assertThrows(ApiException.class, () -> resource.getStaged("public", "open"), "only QDN repositories stage");

        GitResource.registry = () -> null;
        assertThrows(ApiException.class, () -> resource.listRepositories("public"), "rngit disabled");
    }
}
