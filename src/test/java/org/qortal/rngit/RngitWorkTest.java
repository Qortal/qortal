package org.qortal.rngit;

import io.reticulum.destination.Response;
import io.reticulum.identity.Identity;
import io.reticulum.utils.MsgPackUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.qortal.rngit.RngitPermissions.Permission;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.apache.commons.codec.binary.Hex.encodeHexString;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Work documents on real files: signatures, storage layout, authorship and document rules. */
class RngitWorkTest {

    @TempDir
    Path tmp;

    private final Identity alice = new Identity();
    private final Identity bob = new Identity();
    private RngitRepositories repositories;
    private RngitWork work;
    private Path repo;

    @BeforeEach
    void setUp() throws Exception {
        Path group = Files.createDirectories(tmp.resolve("public"));
        repo = group.resolve("repo");
        RngitGit.initBare(repo);
        repositories = new RngitRepositories(Map.of(), Map.of("public", List.of("r:all", "i:all")), Set.of());
        repositories.loadGroup("public", group);
        work = new RngitWork(repo, repositories, "public", "repo");
    }

    private static String text(Response r) {
        return new String(r.getData(), 1, r.getData().length - 1, StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> body(Response r) {
        assertEquals(0, r.getData()[0], () -> "expected RES_OK, got: " + text(r));
        return (Map<Object, Object>) MsgPackUtils.unpackObject(java.util.Arrays.copyOfRange(r.getData(), 1, r.getData().length));
    }

    private static Map<Object, Object> request(Object... kv) {
        Map<Object, Object> data = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) data.put(kv[i], kv[i + 1]);
        return data;
    }

    private Map<Object, Object> signed(Identity who, Object... kv) {
        Map<Object, Object> data = request(kv);
        data.put("signature", who.sign(((String) data.get("content")).strip().getBytes(StandardCharsets.UTF_8)));
        return data;
    }

    @Test
    void createRequiresAValidSignatureByTheRequester() {
        Map<Object, Object> forged = signed(bob, "title", "T", "content", "Body");
        assertEquals("Invalid signature", text(work.create(forged, alice, false)));
        assertEquals("No signature provided", text(work.create(request("title", "T", "content", "Body"), alice, false)));
        assertEquals("Invalid signature length", text(work.create(request("title", "T", "content", "Body", "signature", new byte[10]), alice, false)));
    }

    @Test
    void documentLifecycle() throws Exception {
        Map<Object, Object> created = body(work.create(signed(alice, "title", " Fix it ", "content", " Body \n"), alice, false));
        assertEquals(1L, created.get("id"));
        assertEquals("active", created.get("scope"));
        assertTrue(Files.isRegularFile(Path.of(repo + ".work/active/1/root")));

        assertEquals(Map.of("id", 1L), body(work.comment(request("doc_id", 1L, "content", "On it"), bob)));

        @SuppressWarnings("unchecked")
        Map<Object, Object> view = body(work.view(request("doc_id", "1")));
        @SuppressWarnings("unchecked")
        Map<Object, Object> meta = (Map<Object, Object>) view.get("meta");
        assertEquals("Body", view.get("content"));
        assertEquals("Fix it", meta.get("title"));
        assertEquals(encodeHexString(alice.getHash()), meta.get("author"));
        assertArrayEquals(alice.getPublicKey(), (byte[]) meta.get("identity"));
        assertTrue(alice.validate((byte[]) meta.get("signature"), "Body".getBytes(StandardCharsets.UTF_8)),
                "stored signature verifies the stored content, as rngit work view checks it");
        assertEquals(1, ((List<?>) view.get("comments")).size());

        assertEquals("No access, not author", text(work.edit(signed(bob, "doc_id", 1L, "content", "Hijack"), bob)));
        assertEquals(0, work.edit(signed(alice, "doc_id", 1L, "content", "Body v2"), alice).getData()[0]);

        assertEquals("completed", body(work.move(request("doc_id", 1L), alice, true)).get("scope"));
        assertEquals("Not allowed", text(work.move(request("doc_id", 1L), bob, false)));
        assertEquals("active", body(work.move(request("doc_id", 1L), alice, false)).get("scope"));

        // The reference fails this delete: the document has no .allowed file
        assertEquals("No access, not author", text(work.delete(request("doc_id", 1L), bob)));
        assertEquals(0, work.delete(request("doc_id", 1L), alice).getData()[0]);
        assertFalse(Files.exists(Path.of(repo + ".work/active/1")));
        assertEquals("Document not found", text(work.delete(request("doc_id", 1L), alice)));
    }

    @Test
    void proposalGrantsItsAuthorDocumentRules() throws Exception {
        Map<Object, Object> proposed = body(work.create(signed(bob, "title", "Idea", "content", "Do X"), bob, true));
        assertEquals(1L, proposed.get("id"));
        String bobHex = encodeHexString(bob.getHash());
        assertEquals("i:" + bobHex + "\nw:" + bobHex + "\n", Files.readString(Path.of(repo + ".work/1.allowed")));

        assertTrue(repositories.resolveDocumentPermission(bobHex, "public", "repo", 1L, Permission.WRITE), "from the document's rules");
        assertFalse(repositories.resolveDocumentPermission(encodeHexString(alice.getHash()), "public", "repo", 1L, Permission.WRITE));
        assertFalse(repositories.resolveDocumentPermission(bobHex, "public", "repo", 2L, Permission.WRITE), "other documents unaffected");
    }

    @Test
    void documentNoneDeniesAndIdsFollowPythonInt() throws Exception {
        Files.createDirectories(Path.of(repo + ".work"));
        Files.writeString(Path.of(repo + ".work/7.allowed"), "r:none\n");
        String aliceHex = encodeHexString(alice.getHash());
        assertFalse(repositories.resolveDocumentPermission(aliceHex, "public", "repo", 7L, Permission.READ));
        assertTrue(repositories.resolveDocumentPermission(aliceHex, "public", "repo", 8L, Permission.READ), "repository r:all");

        assertEquals(3L, RngitWork.parseDocId(" 3 "));
        assertEquals(3L, RngitWork.parseDocId(3.9));
        assertNull(RngitWork.parseDocId("three"));
        assertNull(RngitWork.parseDocId(new byte[]{1}));
    }
}
