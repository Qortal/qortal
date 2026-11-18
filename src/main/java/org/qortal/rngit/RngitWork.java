package org.qortal.rngit;

import io.reticulum.destination.Response;
import io.reticulum.identity.Identity;
import io.reticulum.utils.MsgPackUtils;
import lombok.extern.slf4j.Slf4j;
import org.qortal.rngit.RngitPermissions.Permission;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.apache.commons.codec.binary.Hex.encodeHexString;
import static org.qortal.rngit.RngitProtocol.RES_DISALLOWED;
import static org.qortal.rngit.RngitProtocol.RES_INVALID_REQ;
import static org.qortal.rngit.RngitProtocol.RES_NOT_FOUND;
import static org.qortal.rngit.RngitProtocol.RES_REMOTE_FAIL;
import static org.qortal.rngit.RngitReleases.packed;
import static org.qortal.rngit.RngitServer.ok;
import static org.qortal.rngit.RngitServer.result;

/**
 * A repository's work documents ({@code server.py} {@code _work_*}), stored as
 * the reference stores them:
 *
 * <pre>
 * &lt;repo&gt;.work/
 *   active|completed|proposed/&lt;id&gt;/root   msgpack {"content", "meta": {format, title,
 *                                         created, edited, author, signature, identity}}
 *   active|completed|proposed/&lt;id&gt;/&lt;n&gt;    msgpack comments
 *   &lt;id&gt;.allowed                          per-document rules
 * </pre>
 *
 * Creating, proposing and editing require an Ed25519 signature by the remote
 * identity over the document content, which is stored with the document so any
 * reader can verify it. Comments are stored unverified, as the reference does.
 * <p>
 * Deliberate differences: deleting a document without a {@code .allowed} file
 * works (the reference fails with "Remote error", because only proposals get
 * one), and a delete or comment for an unknown id answers "Document not found"
 * where the reference fails with "Remote error".
 */
@Slf4j
final class RngitWork {

    static final int WORK_DOC_LIMIT = 256 * 1024;
    static final List<String> SCOPES = List.of("active", "completed", "proposed");
    private static final int SIGNATURE_LENGTH = 64;

    private final Path workPath;
    private final RngitRepositories repositories;
    private final String groupName;
    private final String repositoryName;

    RngitWork(Path repositoryPath, RngitRepositories repositories, String groupName, String repositoryName) {
        this.workPath = Path.of(repositoryPath + ".work");
        this.repositories = repositories;
        this.groupName = groupName;
        this.repositoryName = repositoryName;
    }

    // ------------------------------------------------------------------
    // Helpers

    /**
     * Python's {@code int(value)} for the request's doc_id: integers, floats
     * (truncated) and decimal strings. Null when it would raise.
     */
    static Long parseDocId(Object value) {
        if (value instanceof Long) return (Long) value;
        if (value instanceof Double) return (long) (double) (Double) value;
        if (value instanceof Boolean) return (Boolean) value ? 1L : 0L;
        if (value instanceof String) {
            try {
                return Long.parseLong(((String) value).strip().replace("_", ""));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** Python truthiness of a request value. */
    static boolean truthy(Object value) {
        if (value == null) return false;
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof Long) return (Long) value != 0;
        if (value instanceof Double) return (Double) value != 0;
        if (value instanceof String) return !((String) value).isEmpty();
        if (value instanceof byte[]) return ((byte[]) value).length > 0;
        if (value instanceof List) return !((List<?>) value).isEmpty();
        if (value instanceof Map) return !((Map<?, ?>) value).isEmpty();
        return true;
    }

    static String str(Map<Object, Object> data, String key, String fallback) {
        Object value = data.get(key);
        return value instanceof String ? (String) value : fallback;
    }

    /** Length as Python's {@code len()} counts a str: in code points. */
    static int pyLen(String s) {
        return s == null ? 0 : s.codePointCount(0, s.length());
    }

    private static double now() {
        return System.currentTimeMillis() / 1000.0;
    }

    /** The scope directory holding a document, searched in reference order, or null. */
    private Path findDocument(long docId) {
        for (String scope : SCOPES) {
            Path dir = workPath.resolve(scope).resolve(Long.toString(docId));
            if (Files.isDirectory(dir)) return dir;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    static Map<Object, Object> load(Path path) {
        try {
            Object value = MsgPackUtils.unpackObject(Files.readAllBytes(path));
            return value instanceof Map ? (Map<Object, Object>) value : null;
        } catch (Exception e) {
            return null;
        }
    }

    static boolean save(Path path, Map<String, Object> document) {
        try {
            Files.createDirectories(path.getParent());
            Path tmp = Path.of(path + ".tmp");
            Files.write(tmp, MsgPackUtils.packObject(document));
            Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (Exception e) {
            log.error("Error persisting work document {}", path, e);
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> meta(Map<Object, Object> document) {
        Object meta = document.get("meta");
        return meta instanceof Map ? (Map<Object, Object>) meta : new LinkedHashMap<>();
    }

    private static boolean isAuthor(Map<Object, Object> document, Identity remote) {
        Object author = meta(document).get("author");
        return author instanceof byte[] && Arrays.equals((byte[]) author, remote.getHash());
    }

    private static String authorHex(Map<Object, Object> meta) {
        Object author = meta.get("author");
        return author instanceof byte[] && ((byte[]) author).length > 0 ? encodeHexString((byte[]) author) : "";
    }

    /** Largest all-digit name in a directory plus one, or 1. */
    private static long nextNumber(Path dir) {
        if (!Files.isDirectory(dir)) return 1;
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.map(p -> p.getFileName().toString())
                    .filter(n -> !n.isEmpty() && n.chars().allMatch(Character::isDigit))
                    .mapToLong(Long::parseLong).max().orElse(0) + 1;
        } catch (IOException e) {
            return 1;
        }
    }

    private long nextDocId() {
        long next = 1;
        for (String scope : SCOPES) next = Math.max(next, nextNumber(workPath.resolve(scope)));
        return next;
    }

    private static List<Path> numberedFiles(Path docDir) {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> entries = Files.list(docDir)) {
            entries.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().chars().allMatch(Character::isDigit))
                    .forEach(out::add);
        } catch (IOException e) {
            log.debug("Could not list {}", docDir, e);
        }
        return out;
    }

    /** The signature checks shared by create, propose and edit, or null if they pass. */
    private static Response checkSignature(Object signature, String signedContent, Identity remote) {
        if (!truthy(signature)) return result(RES_INVALID_REQ, "No signature provided");
        if (!(signature instanceof byte[]) || ((byte[]) signature).length != SIGNATURE_LENGTH) {
            return result(RES_INVALID_REQ, "Invalid signature length");
        }
        if (!remote.validate((byte[]) signature, signedContent.getBytes(StandardCharsets.UTF_8))) {
            return result(RES_INVALID_REQ, "Invalid signature");
        }
        return null;
    }

    private static Map<String, Object> newDocument(String content, String format, String title, Object signature, Identity remote) {
        double now = now();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("format", "micron".equals(format) ? "micron" : "markdown");
        meta.put("title", title);
        meta.put("created", now);
        meta.put("edited", now);
        meta.put("author", remote.getHash());
        meta.put("signature", signature);
        meta.put("identity", remote.getPublicKey());

        Map<String, Object> document = new LinkedHashMap<>();
        document.put("content", content);
        document.put("meta", meta);
        return document;
    }

    // ------------------------------------------------------------------
    // Operations

    /** {@code _work_list}: the documents the remote may read, newest first per scope. */
    Response list(Map<Object, Object> data, String remoteHashHex) {
        String scope = str(data, "scope", "active");
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        for (String folder : SCOPES) {
            List<Map<String, Object>> entries = new ArrayList<>();
            result.put(folder, entries);
            if (!scope.equals(folder) && !scope.equals("all")) continue;

            Path folderPath = workPath.resolve(folder);
            if (!Files.isDirectory(folderPath)) continue;
            try (Stream<Path> dirs = Files.list(folderPath)) {
                for (Path docDir : (Iterable<Path>) dirs::iterator) {
                    Long docId = parseDocId(docDir.getFileName().toString());
                    if (docId == null || !Files.isDirectory(docDir)) continue;
                    if (!repositories.resolveDocumentPermission(remoteHashHex, groupName, repositoryName, docId, Permission.READ)) continue;
                    Map<Object, Object> doc = load(docDir.resolve("root"));
                    if (doc == null || doc.isEmpty()) continue;
                    Map<Object, Object> meta = meta(doc);

                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("id", docId);
                    entry.put("title", meta.getOrDefault("title", "Untitled"));
                    entry.put("created", meta.getOrDefault("created", 0L));
                    entry.put("edited", meta.getOrDefault("edited", 0L));
                    entry.put("author", authorHex(meta));
                    entry.put("format", meta.getOrDefault("format", "markdown"));
                    entry.put("comments", (long) numberedFiles(docDir).size());
                    entries.add(entry);
                }
            } catch (IOException e) {
                log.error("Could not list work documents in {}", folderPath, e);
            }
        }
        Comparator<Map<String, Object>> byCreated = Comparator.comparingDouble(e -> ((Number) e.get("created")).doubleValue());
        for (List<Map<String, Object>> entries : result.values()) entries.sort(byCreated.reversed());
        return packed(result);
    }

    /** {@code _work_view}: a document, its signature material and its comments. */
    Response view(Map<Object, Object> data) {
        Object scope = data.getOrDefault("scope", "all");
        if (!(scope instanceof String) || !(SCOPES.contains(scope) || "all".equals(scope))) return result(RES_INVALID_REQ, "Invalid request");
        if (data.get("doc_id") == null) return result(RES_INVALID_REQ, "No document ID specified");
        Long docId = parseDocId(data.get("doc_id"));
        if (docId == null) return result(RES_INVALID_REQ, "Invalid document ID");

        Path docDir = findDocument(docId);
        if (docDir == null) return result(RES_NOT_FOUND, "Not found");
        Path root = docDir.resolve("root");
        if (!Files.isRegularFile(root)) return result(RES_NOT_FOUND, "Document not found");
        Map<Object, Object> doc = load(root);
        if (doc == null || doc.isEmpty()) return result(RES_REMOTE_FAIL, "Error loading document");

        List<Map<String, Object>> comments = new ArrayList<>();
        for (Path commentPath : numberedFiles(docDir)) {
            Map<Object, Object> comment = load(commentPath);
            if (comment == null || comment.isEmpty()) continue;
            Map<Object, Object> meta = meta(comment);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", Long.parseLong(commentPath.getFileName().toString()));
            entry.put("content", comment.getOrDefault("content", ""));
            entry.put("created", meta.getOrDefault("created", 0L));
            entry.put("edited", meta.getOrDefault("edited", 0L));
            entry.put("author", authorHex(meta));
            entry.put("format", meta.getOrDefault("format", "markdown"));
            comments.add(entry);
        }
        comments.sort(Comparator.comparingLong(c -> (Long) c.get("id")));

        Map<Object, Object> docMeta = meta(doc);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("title", docMeta.getOrDefault("title", "Untitled"));
        meta.put("created", docMeta.getOrDefault("created", 0L));
        meta.put("edited", docMeta.getOrDefault("edited", 0L));
        meta.put("author", authorHex(docMeta));
        meta.put("identity", docMeta.get("identity"));
        meta.put("signature", docMeta.get("signature"));
        meta.put("format", docMeta.getOrDefault("format", "markdown"));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", docId);
        out.put("scope", docDir.getParent().getFileName().toString());
        out.put("content", doc.getOrDefault("content", ""));
        out.put("comments", comments);
        out.put("meta", meta);
        return packed(out);
    }

    /** {@code _work_create} and {@code _work_propose}: a new signed document. */
    Response create(Map<Object, Object> data, Identity remote, boolean proposal) {
        String title = str(data, "title", "").strip();
        String content = str(data, "content", "").strip();
        String format = str(data, "format", "markdown");

        Response refused = checkSignature(data.get("signature"), content, remote);
        if (refused != null) return refused;
        if (pyLen(title) + pyLen(content) + pyLen(format) > WORK_DOC_LIMIT) return result(RES_INVALID_REQ, "Content limit exceeded");
        if (title.isEmpty()) return result(RES_INVALID_REQ, "Title is required");
        if (content.isEmpty()) return result(RES_INVALID_REQ, "Content is required");

        String scope = proposal ? "proposed" : "active";
        try {
            long docId = nextDocId();
            Path root = workPath.resolve(scope).resolve(Long.toString(docId)).resolve("root");
            if (!save(root, newDocument(content, format, title, data.get("signature"), remote))) {
                return result(RES_REMOTE_FAIL, "Error saving document");
            }

            if (proposal) {
                // The proposer may edit and discuss their own proposal
                String hex = encodeHexString(remote.getHash());
                try {
                    Path allowed = workPath.resolve(docId + ".allowed");
                    Path tmp = Path.of(allowed + ".tmp");
                    Files.writeString(tmp, "i:" + hex + "\nw:" + hex + "\n", StandardCharsets.UTF_8);
                    Files.move(tmp, allowed, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    log.error("Error setting permissions for proposed document {}", docId, e);
                    return result(RES_REMOTE_FAIL, "Error setting document ownership");
                }
            }

            log.info("{} work document {} by {}", proposal ? "Proposed" : "Created", docId, encodeHexString(remote.getHash()));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", docId);
            out.put("scope", scope);
            return packed(out);
        } catch (Exception e) {
            log.error("Error {} work document", proposal ? "proposing" : "creating", e);
            return result(RES_REMOTE_FAIL, "Remote error");
        }
    }

    /** {@code _work_edit}: only the author may edit; the new content must be signed. */
    Response edit(Map<Object, Object> data, Identity remote) {
        Object scope = data.getOrDefault("scope", "active");
        String content = str(data, "content", "");
        String title = str(data, "title", "");
        int size = pyLen(title) + pyLen(content);

        if (!(scope instanceof String) || !(SCOPES.contains(scope) || "all".equals(scope))) return result(RES_INVALID_REQ, "Invalid request");
        Response refused = checkSignature(data.get("signature"), content, remote);
        if (refused != null) return refused;
        if (size > WORK_DOC_LIMIT) return result(RES_INVALID_REQ, "Content limit exceeded");
        if (content.isEmpty() && title.isEmpty()) return result(RES_INVALID_REQ, "No changes specified");
        if (!truthy(data.get("doc_id"))) return result(RES_INVALID_REQ, "No document ID specified");
        Long docId = parseDocId(data.get("doc_id"));
        if (docId == null) return result(RES_INVALID_REQ, "Invalid document ID");

        Path docDir = findDocument(docId);
        if (docDir == null) return result(RES_NOT_FOUND, "Not found");
        Path root = docDir.resolve("root");
        if (!Files.isRegularFile(root)) return result(RES_NOT_FOUND, "Document not found");
        Map<Object, Object> doc = load(root);
        if (doc == null || doc.isEmpty()) return result(RES_REMOTE_FAIL, "Error loading document");
        if (!isAuthor(doc, remote)) return result(RES_DISALLOWED, "No access, not author");

        Map<String, Object> updated = new LinkedHashMap<>();
        doc.forEach((k, v) -> updated.put(String.valueOf(k), v));
        Map<String, Object> meta = new LinkedHashMap<>();
        meta(doc).forEach((k, v) -> meta.put(String.valueOf(k), v));
        if (!title.isEmpty()) meta.put("title", title.strip());
        if (!content.isEmpty()) updated.put("content", content.strip());
        meta.put("edited", now());
        meta.put("signature", data.get("signature"));
        meta.put("identity", remote.getPublicKey());
        updated.put("meta", meta);

        if (!save(root, updated)) return result(RES_REMOTE_FAIL, "Error saving document");
        log.info("Edited work document {} by {}", docId, encodeHexString(remote.getHash()));
        return ok();
    }

    /** {@code _work_delete}: by its author or a document admin. */
    Response delete(Map<Object, Object> data, Identity remote) {
        Object scope = data.getOrDefault("scope", "active");
        if (!(scope instanceof String) || !(SCOPES.contains(scope) || "all".equals(scope))) return result(RES_INVALID_REQ, "Invalid request");
        if (data.get("doc_id") == null) return result(RES_INVALID_REQ, "No document ID specified");
        Long docId = parseDocId(data.get("doc_id"));
        if (docId == null) return result(RES_INVALID_REQ, "Invalid document ID");

        Path docDir = findDocument(docId);
        if (docDir == null || !Files.isRegularFile(docDir.resolve("root"))) return result(RES_NOT_FOUND, "Document not found");
        Map<Object, Object> doc = load(docDir.resolve("root"));
        if (doc == null || doc.isEmpty()) return result(RES_REMOTE_FAIL, "Error loading document");

        boolean admin = repositories.resolveDocumentPermission(encodeHexString(remote.getHash()), groupName, repositoryName, docId, Permission.ADMIN);
        if (!(isAuthor(doc, remote) || admin)) return result(RES_DISALLOWED, "No access, not author");

        try {
            Files.deleteIfExists(workPath.resolve(docId + ".allowed"));
            deleteTree(docDir);
            log.info("Deleted work document {} by {}", docId, encodeHexString(remote.getHash()));
            return ok();
        } catch (IOException e) {
            log.error("Error deleting work document {}", docId, e);
            return result(RES_REMOTE_FAIL, "Remote error");
        }
    }

    /** {@code _work_comment}. The signature is stored as sent, unverified, as in the reference. */
    Response comment(Map<Object, Object> data, Identity remote) {
        Object scope = data.getOrDefault("scope", "active");
        String content = str(data, "content", "").strip();
        String format = str(data, "format", "markdown");

        if (!(scope instanceof String) || !(SCOPES.contains(scope) || "all".equals(scope))) return result(RES_INVALID_REQ, "Invalid request");
        if (pyLen(content) > WORK_DOC_LIMIT) return result(RES_INVALID_REQ, "Content limit exceeded");
        if (data.get("doc_id") == null) return result(RES_INVALID_REQ, "No document ID specified");
        Long docId = parseDocId(data.get("doc_id"));
        if (docId == null) return result(RES_INVALID_REQ, "Invalid document ID");
        if (content.isEmpty()) return result(RES_INVALID_REQ, "Content is required");

        Path docDir = findDocument(docId);
        if (docDir == null || !Files.isRegularFile(docDir.resolve("root"))) return result(RES_NOT_FOUND, "Document not found");

        long commentId = nextNumber(docDir);
        double now = now();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("format", "micron".equals(format) ? "micron" : "markdown");
        meta.put("title", null);
        meta.put("created", now);
        meta.put("edited", now);
        meta.put("signature", data.get("signature"));
        meta.put("author", remote.getHash());
        Map<String, Object> comment = new LinkedHashMap<>();
        comment.put("content", content);
        comment.put("meta", meta);

        if (!save(docDir.resolve(Long.toString(commentId)), comment)) return result(RES_REMOTE_FAIL, "Error saving comment");
        log.info("Added comment {} to work document {} by {}", commentId, docId, encodeHexString(remote.getHash()));
        return packed(Map.of("id", commentId));
    }

    /**
     * {@code _work_complete} (active to completed) and {@code _work_activate}
     * (completed or proposed to active), by the author or a document admin.
     */
    Response move(Map<Object, Object> data, Identity remote, boolean complete) {
        if (data.get("doc_id") == null) return result(RES_INVALID_REQ, "No document ID specified");
        Long docId = parseDocId(data.get("doc_id"));
        if (docId == null) return result(RES_INVALID_REQ, "Invalid document ID");

        Path docDir = null;
        for (String scope : complete ? List.of("active") : List.of("completed", "proposed")) {
            Path dir = workPath.resolve(scope).resolve(Long.toString(docId));
            if (Files.isDirectory(dir)) {
                docDir = dir;
                break;
            }
        }
        if (docDir == null) return result(RES_NOT_FOUND, "Document not found");
        Map<Object, Object> doc = load(docDir.resolve("root"));
        if (doc == null || doc.isEmpty()) return result(RES_REMOTE_FAIL, "Error loading document");

        boolean admin = repositories.resolveDocumentPermission(encodeHexString(remote.getHash()), groupName, repositoryName, docId, Permission.ADMIN);
        if (!isAuthor(doc, remote) && !admin) return result(RES_DISALLOWED, "Not allowed");

        String target = complete ? "completed" : "active";
        try {
            Path destination = workPath.resolve(target).resolve(Long.toString(docId));
            Files.createDirectories(destination.getParent());
            Files.move(docDir, destination, StandardCopyOption.ATOMIC_MOVE);
            log.info("{} work document {} by {}", complete ? "Completed" : "Activated", docId, encodeHexString(remote.getHash()));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", docId);
            out.put("scope", target);
            return packed(out);
        } catch (IOException e) {
            log.error("Error moving work document {} to {}", docId, target, e);
            return result(RES_REMOTE_FAIL, "Remote error");
        }
    }

    /**
     * {@code _work_perms}: get or set a document's own rules. Allowed for a
     * document admin, or for its author while they can manage documents in the
     * repository (interact and write).
     */
    Response perms(Map<Object, Object> data, Identity remote) {
        String remoteHex = encodeHexString(remote.getHash());
        boolean read = repositories.resolvePermission(remoteHex, groupName, repositoryName, Permission.READ);
        boolean write = repositories.resolvePermission(remoteHex, groupName, repositoryName, Permission.WRITE);
        boolean interact = repositories.resolvePermission(remoteHex, groupName, repositoryName, Permission.INTERACT);
        boolean manage = interact && write;
        if (!read) return result(RES_NOT_FOUND, "Not found");
        if (!manage) return result(RES_DISALLOWED, "Not allowed");

        Object step = data.get("step");
        if (!truthy(step)) return result(RES_INVALID_REQ, "Invalid request");
        if (!"get".equals(step) && !"set".equals(step)) return result(RES_INVALID_REQ, "Invalid step");

        if (data.get("doc_id") == null) return result(RES_INVALID_REQ, "No document ID specified");
        Long docId = parseDocId(data.get("doc_id"));
        if (docId == null) return result(RES_INVALID_REQ, "Invalid document ID");
        Path docDir = findDocument(docId);
        if (docDir == null) return result(RES_NOT_FOUND, "Document not found");
        Map<Object, Object> doc = load(docDir.resolve("root"));
        if (doc == null || doc.isEmpty()) return result(RES_REMOTE_FAIL, "Error loading document");

        boolean admin = repositories.resolveDocumentPermission(remoteHex, groupName, repositoryName, docId, Permission.ADMIN);
        if (!((isAuthor(doc, remote) && manage) || admin)) return result(RES_DISALLOWED, "Not allowed");

        Path allowed = workPath.resolve(docId + ".allowed");
        if ("get".equals(step)) {
            try {
                String content = Files.isRegularFile(allowed) ? Files.readString(allowed, StandardCharsets.UTF_8) : "";
                return packed(Map.of("content", content));
            } catch (IOException e) {
                log.error("Error getting document permissions for {}", docId, e);
                return result(RES_REMOTE_FAIL, "Error getting permissions");
            }
        }

        Object contentValue = data.getOrDefault("content", "");
        if (!(contentValue instanceof String)) return result(RES_INVALID_REQ, "Invalid request");
        String invalid = repositories.validateAllowedContent((String) contentValue);
        if (invalid != null) return result(RES_INVALID_REQ, invalid);
        try {
            Files.createDirectories(workPath);
            Path tmp = Path.of(allowed + ".tmp");
            Files.writeString(tmp, (String) contentValue, StandardCharsets.UTF_8);
            Files.move(tmp, allowed, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            log.info("Permissions for work document {}/{}/{} updated by {}", groupName, repositoryName, docId, remoteHex);
            return ok();
        } catch (IOException e) {
            log.error("Error setting document permissions for {}", docId, e);
            return result(RES_REMOTE_FAIL, "Error setting permissions");
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            List<Path> paths = new ArrayList<>();
            walk.forEach(paths::add);
            paths.sort(Comparator.reverseOrder());
            for (Path p : paths) Files.delete(p);
        }
    }
}
