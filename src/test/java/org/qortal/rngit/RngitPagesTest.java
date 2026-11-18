package org.qortal.rngit;

import io.reticulum.destination.Response;
import io.reticulum.identity.Identity;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.apache.commons.codec.binary.Hex.encodeHexString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the page node does beyond the reference, which the A/B test
 * ({@code reticulum/rngit-livetest/pages-ab.sh}) cannot compare: blocking the null
 * identity, the Qortal name field, refused release tags, templates, icons, and
 * the reference's formatting helpers.
 */
class RngitPagesTest {

    private static final String READER = "11111111111111111111111111111111";

    @TempDir
    Path tmp;

    private Path group;

    @BeforeEach
    void setUp() throws Exception {
        group = Files.createDirectories(tmp.resolve("public"));
        Path work = tmp.resolve("work");
        try (Git git = Git.init().setDirectory(work.toFile()).setInitialBranch("main").call()) {
            Files.writeString(work.resolve("README.md"), "# hello\n");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("first").setAuthor("Ann", "ann@example.invalid").setSign(false).call();
        }
        Git.cloneRepository().setURI(work.toUri().toString()).setDirectory(group.resolve("open").toFile()).setBare(true).call().close();
        Files.writeString(group.resolve("open.allowed"), "r:all\n");
    }

    private RngitPages pages(Set<String> blocked, String config) {
        RngitRepositories repositories = new RngitRepositories(Map.of(), Map.of(), blocked);
        repositories.loadGroup("public", group);
        return new RngitPages(new Identity(), "Test Node", 0, repositories, new byte[16], tmp.resolve("rngit"),
                RngitConfig.parse(config), "test");
    }

    private static String text(Response response) {
        return new String(response.getData(), StandardCharsets.UTF_8);
    }

    private static RngitPages.PageRequest request(String remote, String... keysAndValues) {
        Map<Object, Object> data = new HashMap<>();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) data.put(keysAndValues[i], keysAndValues[i + 1]);
        return new RngitPages.PageRequest(data, remote, "00112233445566778899aabbccddeeff");
    }

    @Test
    void nullIdentityIsTheReferences() {
        assertEquals(RngitPages.NULL_IDENTITY_HASH, encodeHexString(Identity.fromBytes(new byte[64]).getHash()));
    }

    @Test
    void blockingTheNullIdentityRequiresIdentification() {
        RngitPages pages = pages(Set.of(RngitPages.NULL_IDENTITY_HASH), "");
        String anonymous = text(pages.frontPage(request(null)));
        assertTrue(anonymous.contains(">>No Identity"), anonymous);
        assertFalse(anonymous.contains("public"));
        assertTrue(text(pages.repoPage(request(null, "var_g", "public", "var_r", "open"))).contains("No Identity"));

        String identified = text(pages.frontPage(request(READER)));
        assertTrue(identified.contains("public`:/page/group.mu`g=public]`! (1 repository)"), identified);
        assertTrue(text(pages.repoPage(request(READER, "var_g", "public", "var_r", "open"))).contains("Commits (1)"));
    }

    @Test
    void groupFromTheNameField() {
        RngitPages pages = pages(Set.of(), "");
        assertTrue(text(pages.groupPage(request(null, "field_g", " public "))).contains("open"));
        assertTrue(text(pages.groupPage(request(null))).contains("Invalid request"));
        assertFalse(text(pages.frontPage(request(null))).contains("Qortal Names"), "only with the QDN gateway");
    }

    @Test
    void releaseTagsArePathComponents() throws Exception {
        Path release = Files.createDirectories(group.resolve("open.releases").resolve("v1"));
        Files.writeString(release.resolve("META"), "tag = v1\ncreated = 1700000000\nstatus = published\n");
        Files.writeString(group.resolve("secret-META"), "status = published\n");
        RngitPages pages = pages(Set.of(), "");

        assertTrue(text(pages.releasePage(request(null, "var_g", "public", "var_r", "open", "var_t", "v1"))).contains("Release v1"));
        String traversal = text(pages.releasePage(request(null, "var_g", "public", "var_r", "open", "var_t", "../../work")));
        assertTrue(traversal.contains("Release Not Found"), traversal);
        assertNull(pages.artifact(request(null, "var_g", "public", "var_r", "open", "var_t", "..", "var_a", "META")));
    }

    @Test
    void customTemplatesAndIcons() throws Exception {
        Path templates = Files.createDirectories(tmp.resolve("rngit").resolve("templates"));
        Files.writeString(templates.resolve("base.mu"), "BASE {NODE_NAME} {VERSION}\n{PAGE_CONTENT}\n");
        Files.writeString(templates.resolve("front.mu"), "FRONT[{PAGE_CONTENT}]");
        String page = text(pages(Set.of(), "").frontPage(request(null)));
        assertTrue(page.startsWith("BASE Test Node test\nFRONT["), page);

        assertEquals("󰉖", pages(Set.of(), "").icon("folder"), "Nerd Font icons by default");
        assertEquals("🗀", pages(Set.of(), "[pages]\nunicode_icons = yes\n").icon("folder"));
    }

    @Test
    void formattingMatchesTheReference() {
        Map<Double, String> times = Map.of(0.0, "0s", 0.004, "0s", 0.0123, "0.01s", 0.5, "0.5s", 2.0, "2.0s",
                61.25, "1m and 1.25s", 3725.5, "1h, 2m and 5.5s", 90061.0, "1d, 1h, 1m and 1.0s");
        times.forEach((t, expected) -> assertEquals(expected, RngitPages.prettyTime(t, false), "prettytime " + t));
        assertEquals("2h and 1m", RngitPages.prettyTime(7260, true));
        assertEquals("3d and 5s", RngitPages.prettyTime(259205, true));
        assertEquals("0s", RngitPages.prettyTime(0.4, true));

        Map<Double, String> sizes = Map.of(0.0, "0 B", 999.0, "999 B", 1000.0, "1.00 KB", 1024.0, "1.02 KB",
                999999.0, "1000.00 KB", 1234567.0, "1.23 MB", 1e25, "10.00YB");
        sizes.forEach((n, expected) -> assertEquals(expected, RngitPages.prettySize(n), "prettysize " + n));

        assertEquals("a+b%2Fc~%2A%27%28x%29%21-_.%C3%A9", RngitMicron.quotePlus("a b/c~*'(x)!-_.é"));
        for (String[] ext : List.of(new String[]{"a/b.MD", ".md"}, new String[]{".bashrc", ""}, new String[]{"a.tar.gz", ".gz"},
                new String[]{"x.", "."}, new String[]{"..x", ""}, new String[]{"dir.d/file", ""})) {
            assertEquals(ext[1], RngitPages.fileExtension(ext[0]), "splitext " + ext[0]);
        }
    }
}
