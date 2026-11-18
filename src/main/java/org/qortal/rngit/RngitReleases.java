package org.qortal.rngit;

import io.reticulum.destination.Response;
import io.reticulum.utils.MsgPackUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.qortal.rngit.RngitProtocol.RES_DISALLOWED;
import static org.qortal.rngit.RngitProtocol.RES_INVALID_REQ;
import static org.qortal.rngit.RngitProtocol.RES_NOT_FOUND;
import static org.qortal.rngit.RngitProtocol.RES_OK;
import static org.qortal.rngit.RngitProtocol.RES_REMOTE_FAIL;
import static org.qortal.rngit.RngitServer.ok;
import static org.qortal.rngit.RngitServer.result;

/**
 * A repository's releases ({@code server.py} {@code _release_*}), stored as the
 * reference stores them next to the repository:
 *
 * <pre>
 * &lt;repo&gt;.releases/
 *   latest                  tag of the latest release
 *   &lt;tag&gt;/META              ConfigObj: tag, hash, created, status, created_by, published_at
 *   &lt;tag&gt;/RELEASE.md|.mu    release notes
 *   &lt;tag&gt;/THANKS            msgpack {"count": n}
 *   &lt;tag&gt;/artifacts/        uploaded files, including their .rsg signatures and manifest.rsm
 * </pre>
 *
 * The node verifies no signatures: {@code rngit release create} signs every
 * artifact and the manifest client-side, and {@code rngit release fetch}
 * verifies them client-side against the manifest's signer.
 */
@Slf4j
final class RngitReleases {

    private final Path releasesPath;
    private final Path repositoryPath;

    RngitReleases(Path repositoryPath) {
        this.repositoryPath = repositoryPath;
        this.releasesPath = Path.of(repositoryPath + ".releases");
    }

    /** A tag or file name from a request: a non-empty single path component, or null. */
    static String pathComponent(Object value) {
        if (!(value instanceof String)) return null;
        String s = (String) value;
        if (s.isEmpty() || s.contains("/") || s.contains("\\") || s.equals(".") || s.equals("..")) return null;
        return s;
    }

    static Response packed(Object value) {
        byte[] packed = MsgPackUtils.packObject(value);
        byte[] out = new byte[1 + packed.length];
        out[0] = RES_OK;
        System.arraycopy(packed, 0, out, 1, packed.length);
        return Response.of(out);
    }

    private static RngitConfig readMeta(Path releaseDir) throws IOException {
        return RngitConfig.parse(Files.readString(releaseDir.resolve("META"), StandardCharsets.UTF_8));
    }

    private static String meta(RngitConfig meta, String key, String fallback) {
        return meta.getString(RngitConfig.ROOT, key, fallback);
    }

    private static void writeMeta(Path releaseDir, Map<String, String> values) throws IOException {
        Path tmp = releaseDir.resolve("META.tmp");
        Files.writeString(tmp, RngitConfig.writeFlat(values), StandardCharsets.UTF_8);
        Files.move(tmp, releaseDir.resolve("META"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private String readLatest() {
        Path latest = releasesPath.resolve("latest");
        try {
            return Files.isRegularFile(latest) ? Files.readString(latest).strip() : null;
        } catch (IOException e) {
            log.error("Could not determine latest release for {}", releasesPath, e);
            return null;
        }
    }

    private void writeLatest(String tag) throws IOException {
        Path tmp = releasesPath.resolve("latest.tmp");
        Files.writeString(tmp, tag);
        Files.move(tmp, releasesPath.resolve("latest"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    /** "latest" resolves through the latest file, as view and fetch do. */
    private String resolveTag(String tag) {
        return "latest".equals(tag) ? readLatest() : tag;
    }

    // ------------------------------------------------------------------
    // Reading

    /** Releases newest first, and the latest tag if it is published: {@code releases_list_data}. */
    static final class ListData {
        final List<Map<String, Object>> releases;
        final String latest;

        ListData(List<Map<String, Object>> releases, String latest) {
            this.releases = releases;
            this.latest = latest;
        }
    }

    Path getReleasesPath() {
        return releasesPath;
    }

    /** {@code releases_list_data}: null when the releases directory cannot be listed. */
    ListData listData() {
        List<Map<String, Object>> releases = new ArrayList<>();
        if (!Files.isDirectory(releasesPath)) return new ListData(releases, null);

        Map<String, Boolean> published = new LinkedHashMap<>();
        try (Stream<Path> entries = Files.list(releasesPath)) {
            for (Path releaseDir : (Iterable<Path>) entries::iterator) {
                if (!Files.isDirectory(releaseDir) || !Files.isRegularFile(releaseDir.resolve("META"))) continue;
                try {
                    RngitConfig meta = readMeta(releaseDir);
                    String tag = meta(meta, "tag", releaseDir.getFileName().toString());
                    String status = meta(meta, "status", "unknown");

                    Map<String, Object> info = new LinkedHashMap<>();
                    info.put("tag", tag);
                    info.put("hash", meta(meta, "hash", ""));
                    info.put("created", (long) meta.getInt(RngitConfig.ROOT, "created", 0));
                    info.put("status", status);
                    info.put("created_by", meta(meta, "created_by", ""));

                    String preview = "";
                    String format = "markdown";
                    for (String notesFile : List.of("RELEASE.md", "RELEASE.mu", "RELEASE.txt")) {
                        Path notesPath = releaseDir.resolve(notesFile);
                        if (!Files.isRegularFile(notesPath)) continue;
                        StringBuilder lines = new StringBuilder();
                        for (String line : Files.readString(notesPath, StandardCharsets.UTF_8).split("\\R", -1)) {
                            if (!line.startsWith("#") && !line.startsWith(">")) lines.append(line).append('\n');
                        }
                        preview = lines.toString().strip();
                        if (notesFile.endsWith(".mu")) format = "micron";
                        else if (notesFile.endsWith(".txt")) format = "text";
                        break;
                    }
                    info.put("preview", preview);
                    info.put("format", format);
                    info.put("artifacts", (long) artifactFiles(releaseDir).size());

                    releases.add(info);
                    published.put(tag, "published".equals(status));
                } catch (Exception e) {
                    log.debug("Error reading release metadata for {}", releaseDir, e);
                }
            }
        } catch (IOException e) {
            log.error("Error listing releases for {}", releasesPath, e);
            return null;
        }

        String latest = readLatest();
        String latestRelease = latest != null && Boolean.TRUE.equals(published.get(latest)) ? latest : null;
        releases.sort(Comparator.comparing((Map<String, Object> r) -> (Long) r.get("created")).reversed());
        return new ListData(releases, latestRelease);
    }

    /** {@code _release_list}. */
    Response list() {
        if (!Files.isDirectory(releasesPath)) return packed(List.of());
        ListData list = listData();
        if (list == null) return result(RES_REMOTE_FAIL, "Error listing releases");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("releases", list.releases);
        data.put("latest", list.latest);
        return packed(data);
    }

    private static List<Path> artifactFiles(Path releaseDir) throws IOException {
        Path artifacts = releaseDir.resolve("artifacts");
        if (!Files.isDirectory(artifacts)) return List.of();
        try (Stream<Path> files = Files.list(artifacts)) {
            List<Path> out = new ArrayList<>();
            files.filter(Files::isRegularFile).forEach(out::add);
            return out;
        }
    }

    /** {@code release_data}: a release's metadata, notes and artifacts, or null. */
    static Map<String, Object> releaseData(Path releaseDir, String tag) {
        if (!Files.isRegularFile(releaseDir.resolve("META"))) {
            log.error("Release metadata missing for {}/{}", releaseDir, tag);
            return null;
        }
        try {
            RngitConfig meta = readMeta(releaseDir);
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("tag", meta(meta, "tag", tag));
            info.put("hash", meta(meta, "hash", ""));
            info.put("created", (long) meta.getInt(RngitConfig.ROOT, "created", 0));
            info.put("status", meta(meta, "status", "unknown"));
            info.put("created_by", meta(meta, "created_by", ""));

            String notes = "";
            String notesFormat = "text";
            for (String[] candidate : new String[][]{{"RELEASE.md", "markdown"}, {"RELEASE.mu", "micron"}}) {
                Path notesPath = releaseDir.resolve(candidate[0]);
                if (Files.isRegularFile(notesPath)) {
                    notes = Files.readString(notesPath, StandardCharsets.UTF_8);
                    notesFormat = candidate[1];
                    break;
                }
            }
            info.put("notes", notes);
            info.put("notes_format", notesFormat);

            List<Map<String, Object>> artifacts = new ArrayList<>();
            for (Path artifact : artifactFiles(releaseDir)) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("name", artifact.getFileName().toString());
                entry.put("size", Files.size(artifact));
                artifacts.add(entry);
            }
            info.put("artifacts", artifacts);
            info.put("thanks", thanksCount(releaseDir));
            return info;
        } catch (Exception e) {
            log.error("Error while getting release data for {}", releaseDir, e);
            return null;
        }
    }

    /** {@code _release_view}. */
    Response view(Map<Object, Object> data) {
        String requested = pathComponent(data.get("tag"));
        if (requested == null) return result(RES_INVALID_REQ, "Invalid tag specified");
        String tag = resolveTag(requested);
        if (tag == null) return result(RES_NOT_FOUND, "No latest release found");

        Path releaseDir = releasesPath.resolve(tag);
        if (pathComponent(tag) == null || !Files.isDirectory(releaseDir)) return result(RES_NOT_FOUND, "Release not found");

        Map<String, Object> info = releaseData(releaseDir, tag);
        return info == null ? result(RES_REMOTE_FAIL, "Error getting release data") : packed(info);
    }

    private static long thanksCount(Path releaseDir) {
        Path thanks = releaseDir.resolve("THANKS");
        try {
            if (!Files.isRegularFile(thanks)) return 0;
            Object value = MsgPackUtils.unpackObject(Files.readAllBytes(thanks));
            if (value instanceof Map && ((Map<?, ?>) value).get("count") instanceof Long) {
                return (Long) ((Map<?, ?>) value).get("count");
            }
        } catch (Exception e) {
            log.debug("Could not read thanks for {}", releaseDir, e);
        }
        return 0;
    }

    /** {@code _release_fetch}: one artifact as a file response carrying its name. */
    Response fetch(Map<Object, Object> data) {
        String requested = pathComponent(data.get("tag"));
        if (requested == null) return result(RES_INVALID_REQ, "Invalid tag specified");
        String artifact = pathComponent(data.get("artifact"));
        if (artifact == null) return result(RES_INVALID_REQ, "Invalid artifact specified");

        String tag = resolveTag(requested);
        if (tag == null) return result(RES_NOT_FOUND, "No latest release found");
        Path releaseDir = releasesPath.resolve(tag);
        if (pathComponent(tag) == null || !Files.isDirectory(releaseDir)) return result(RES_NOT_FOUND, "Release not found");

        Path artifactPath = releaseDir.resolve("artifacts").resolve(artifact);
        if (!Files.isRegularFile(artifactPath)) return result(RES_NOT_FOUND, "Artifact not found");
        return Response.ofFile(artifactPath.toFile(), Map.of("name", artifact.getBytes(StandardCharsets.UTF_8)));
    }

    // ------------------------------------------------------------------
    // Writing

    /** {@code _release_create}: the init, artifact and finalize steps. */
    Response create(Map<Object, Object> data, String creatorHashHex) {
        Object step = data.get("step");
        if (step == null || "".equals(step)) return result(RES_INVALID_REQ, "Invalid request");
        switch (String.valueOf(step)) {
            case "init": return createInit(data, creatorHashHex);
            case "artifact": return createArtifact(data);
            case "finalize": return createFinalize(data);
            default: return result(RES_INVALID_REQ, "Invalid request");
        }
    }

    private Response createInit(Map<Object, Object> data, String creatorHashHex) {
        Object tagValue = data.get("tag");
        if (!(tagValue instanceof String) || ((String) tagValue).isEmpty() || ((String) tagValue).contains("/")) {
            return result(RES_INVALID_REQ, "Invalid tag specified");
        }
        String tag = pathComponent(tagValue);
        if (tag == null) return result(RES_INVALID_REQ, "Invalid tag name");
        Object commitHash = data.get("hash");
        Object notes = data.getOrDefault("notes", "");
        Object notesFormat = data.getOrDefault("notes_format", "markdown");

        try {
            if (RngitGit.resolveRef(repositoryPath, "refs/tags/" + tag) == null) {
                return result(RES_INVALID_REQ, "Tag '" + tag + "' does not exist in repository");
            }
            Files.createDirectories(releasesPath);
            Path releaseDir = releasesPath.resolve(tag);
            if (Files.isDirectory(releaseDir)) return result(RES_DISALLOWED, "Release already exists");
            Files.createDirectories(releaseDir.resolve("artifacts"));

            Map<String, String> meta = new LinkedHashMap<>();
            meta.put("tag", tag);
            if (commitHash instanceof String && !((String) commitHash).isEmpty()) meta.put("hash", (String) commitHash);
            meta.put("created", String.valueOf(System.currentTimeMillis() / 1000));
            meta.put("status", "draft");
            meta.put("created_by", creatorHashHex);
            writeMeta(releaseDir, meta);

            if (notes instanceof String && !((String) notes).isEmpty()) {
                String notesFile = "micron".equals(notesFormat) ? "RELEASE.mu" : "RELEASE.md";
                Files.writeString(releaseDir.resolve(notesFile), (String) notes, StandardCharsets.UTF_8);
            }
            Files.write(releaseDir.resolve("THANKS"), MsgPackUtils.packObject(Map.of("count", 0)));

            log.info("Created release {} in draft status", tag);
            return ok();
        } catch (Exception e) {
            log.error("Error creating release {}", tag, e);
            return result(RES_REMOTE_FAIL, "Remote error");
        }
    }

    /** The release directory of a draft, or a refusal. */
    private Object draft(String tag) throws IOException {
        Path releaseDir = releasesPath.resolve(tag);
        if (!Files.isDirectory(releaseDir)) return result(RES_NOT_FOUND, "Release not found");
        if (!"draft".equals(meta(readMeta(releaseDir), "status", null))) {
            return result(RES_DISALLOWED, "Release was finalized and is not writable");
        }
        return releaseDir;
    }

    private Response createArtifact(Map<Object, Object> data) {
        Object tagValue = data.get("tag");
        Object nameValue = data.get("artifact_name");
        Object artifactData = data.get("artifact_data");
        if (tagValue == null || "".equals(tagValue) || nameValue == null || "".equals(nameValue)) {
            return result(RES_INVALID_REQ, "Missing tag or artifact name");
        }
        String tag = pathComponent(tagValue);
        if (tag == null) return result(RES_INVALID_REQ, "Invalid tag specified");
        if (artifactData == null) return result(RES_INVALID_REQ, "No artifact data");
        String name = nameValue instanceof String ? Path.of((String) nameValue).getFileName().toString() : null;
        if (pathComponent(name) == null) return result(RES_INVALID_REQ, "Missing tag or artifact name");

        try {
            Object releaseDir = draft(tag);
            if (releaseDir instanceof Response) return (Response) releaseDir;
            Path artifacts = ((Path) releaseDir).resolve("artifacts");
            Files.createDirectories(artifacts);
            byte[] bytes = artifactData instanceof byte[] ? (byte[]) artifactData
                    : String.valueOf(artifactData).getBytes(StandardCharsets.UTF_8);
            Files.write(artifacts.resolve(name), bytes);
            log.info("Added artifact {} to release {}", name, tag);
            return ok();
        } catch (Exception e) {
            log.error("Error adding artifact {} to release {}", name, tag, e);
            return result(RES_REMOTE_FAIL, "Remote error");
        }
    }

    private Response createFinalize(Map<Object, Object> data) {
        Object tagValue = data.get("tag");
        if (tagValue == null || "".equals(tagValue)) return result(RES_INVALID_REQ, "No tag specified");
        String tag = pathComponent(tagValue);
        if (tag == null) return result(RES_INVALID_REQ, "Invalid tag specified");

        try {
            Object releaseDir = draft(tag);
            if (releaseDir instanceof Response) return (Response) releaseDir;
            Path dir = (Path) releaseDir;

            RngitConfig current = readMeta(dir);
            Map<String, String> meta = new LinkedHashMap<>();
            for (String key : current.section(RngitConfig.ROOT).keySet()) meta.put(key, meta(current, key, ""));
            meta.put("status", "published");
            meta.put("published_at", String.valueOf(System.currentTimeMillis() / 1000));
            writeMeta(dir, meta);

            try {
                writeLatest(tag);
            } catch (IOException e) {
                log.error("Error setting latest release for {}", releasesPath, e);
            }
            log.info("Finalized release {} for {}", tag, releasesPath);
            return ok();
        } catch (Exception e) {
            log.error("Error finalizing release {}", tag, e);
            return result(RES_REMOTE_FAIL, "Remote error");
        }
    }

    /** {@code _release_delete}. */
    Response delete(Map<Object, Object> data) {
        Object tagValue = data.get("tag");
        if (tagValue == null || "".equals(tagValue)) return result(RES_INVALID_REQ, "No tag specified");
        String tag = pathComponent(tagValue);
        if (tag == null) return result(RES_INVALID_REQ, "Invalid tag specified");
        Path releaseDir = releasesPath.resolve(tag);
        if (!Files.isDirectory(releaseDir)) return result(RES_NOT_FOUND, "Release not found");

        try (Stream<Path> walk = Files.walk(releaseDir)) {
            List<Path> paths = new ArrayList<>();
            walk.forEach(paths::add);
            paths.sort(Comparator.reverseOrder());
            for (Path p : paths) Files.delete(p);
            log.info("Deleted release {} from {}", tag, releasesPath);
            return ok();
        } catch (Exception e) {
            log.error("Error deleting release {} from {}", tag, releasesPath, e);
            return result(RES_REMOTE_FAIL, "Remote error");
        }
    }

    /** {@code _release_latest}. */
    Response latest(Map<Object, Object> data) {
        Object tagValue = data.get("tag");
        if (tagValue == null || "".equals(tagValue)) return result(RES_INVALID_REQ, "No tag specified");
        String tag = pathComponent(tagValue);
        if (tag == null) return result(RES_INVALID_REQ, "Invalid tag specified");
        if (!Files.isDirectory(releasesPath.resolve(tag))) return result(RES_NOT_FOUND, "Release not found");

        try {
            writeLatest(tag);
            log.info("Set {} as latest release for {}", tag, releasesPath);
            return ok();
        } catch (Exception e) {
            log.error("Error setting latest release for {}", releasesPath, e);
            return result(RES_REMOTE_FAIL, "Remote error");
        }
    }
}
