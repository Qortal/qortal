package org.qortal.rngit;

import io.reticulum.destination.Destination;
import io.reticulum.destination.DestinationType;
import io.reticulum.destination.Direction;
import io.reticulum.destination.Request;
import io.reticulum.destination.RequestPolicy;
import io.reticulum.destination.Response;
import io.reticulum.identity.Identity;
import io.reticulum.identity.IdentityKnownDestination;
import io.reticulum.link.Link;
import io.reticulum.link.LinkStatus;
import io.reticulum.utils.DestinationUtils;
import io.reticulum.utils.IdentityUtils;
import io.reticulum.utils.MsgPackUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.binary.Hex;
import org.qortal.rngit.RngitPagesGit.BlobInfo;
import org.qortal.rngit.RngitPagesGit.CommitEntry;
import org.qortal.rngit.RngitPagesGit.CommitInfo;
import org.qortal.rngit.RngitPagesGit.FileChange;
import org.qortal.rngit.RngitPagesGit.RefInfo;
import org.qortal.rngit.RngitPagesGit.TreeEntry;
import org.qortal.rngit.RngitPermissions.Permission;
import org.qortal.rngit.RngitRepositories.Group;
import org.qortal.rngit.RngitRepositories.Repository;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.apache.commons.codec.binary.Hex.encodeHexString;
import static org.qortal.rngit.RngitMicron.escape;
import static org.qortal.rngit.RngitMicron.f;
import static org.qortal.rngit.RngitMicron.heading;
import static org.qortal.rngit.RngitMicron.italic;
import static org.qortal.rngit.RngitMicron.link;
import static org.qortal.rngit.RngitMicron.linkR;

/**
 * A Nomad Network page node on the {@code nomadnetwork.node} destination of the
 * repositories identity ({@code pages.py} {@code NomadNetworkNode}): browsable
 * Micron pages for groups, repositories, files, commits, refs, releases and
 * work documents, plus file, artifact and image downloads, under the same
 * permissions as the git node. Visitors that do not identify are treated as
 * the null identity, which an operator can block to require identification.
 * <p>
 * Qortal names are groups here too: their repositories are listed from QDN,
 * and the front page has a field to open a name's group (Qortal names cannot
 * be enumerated like configured groups).
 * <p>
 * Deliberate differences from the reference:
 * <ul>
 *   <li>No syntax highlighting or WebP conversion: pages render as the
 *       reference renders them without pygments or a media backend.</li>
 *   <li>No statistics: Core does not record views, fetches or downloads, so
 *       the Stats link is not shown and the stats page reports them unavailable.</li>
 *   <li>A work document download is a file response carrying its name, the
 *       form artifacts use, rather than a {@code [name, data]} list.</li>
 *   <li>Requests that the reference answers with {@code False} get no response.</li>
 *   <li>Release tags from a request must be a single path component, a
 *       published release without notes lists without a preview where the
 *       reference fails, a first thanks shows its count where the reference
 *       shows 0, and a mirror never synced shows no sync age.</li>
 * </ul>
 */
@Slf4j
final class RngitPages {

    static final String APP_NAME = "nomadnetwork";
    static final String ASPECT = "node";

    /** {@code RNS.Identity.from_bytes(bytes(64)).hash}: whoever did not identify. */
    static final String NULL_IDENTITY_HASH = "d7db22f63b453c23bb0688dde565b7c1";

    static final long LINK_CLEAN_INTERVAL_MS = 60_000;

    static final String PATH_INDEX = "/page/index.mu";
    static final String PATH_GROUP = "/page/group.mu";
    static final String PATH_REPO = "/page/repo.mu";
    static final String PATH_TREE = "/page/tree.mu";
    static final String PATH_BLOB = "/page/blob.mu";
    static final String PATH_COMMITS = "/page/commits.mu";
    static final String PATH_COMMIT = "/page/commit.mu";
    static final String PATH_REFS = "/page/refs.mu";
    static final String PATH_STATS = "/page/stats.mu";
    static final String PATH_RELEASES = "/page/releases.mu";
    static final String PATH_RELEASE = "/page/release.mu";
    static final String PATH_WORK = "/page/work.mu";
    static final String PATH_WORK_DOC = "/page/work_doc.mu";
    static final String PATH_MEDIA = "/media";
    static final String FILE_ARTIFACT = "/file/artifact";
    static final String FILE_DOWNLOAD = "/file/download";
    static final String FILE_WORKDOC = "/file/workdoc";

    static final int BLOB_SIZE_LIMIT = 256 * 1024;
    static final int TREE_ENTRIES_PER_PAGE = 1000;
    static final int COMMITS_PER_PAGE = 100;
    static final int MAX_RENDER_WIDTH = 100;
    static final long TEMPLATE_TIMEOUT_SECONDS = 10;

    /** Icons by name: {Nerd Font, unicode} code points, as the reference's NF_ICON_* and U_ICON_*. */
    private static final Map<String, int[]> ICONS = Map.of(
            "sep", new int[]{0x2022, 0x2022},
            "folder", new int[]{0xf0256, 0x1f5c0},
            "file", new int[]{0xf0f6, 0x1f5ce},
            "branch", new int[]{0xf062c, 0x2443},
            "commits", new int[]{0xf02da, 0x1f5b9},
            "tag", new int[]{0xf04fc, 0x2306},
            "stats", new int[]{0xf201, 0x1f5e0},
            "heart", new int[]{0xf02d1, 0x2665},
            "package", new int[]{0xf03d7, 0x25c7},
            "work", new int[]{0xf1323, 0x2638});

    static final String CLR_FOLDER = "`Ffe6";
    static final String CLR_FILE = "`F66d";
    static final String CLR_DIM = "`F666";
    static final String CLR_DIM_H = "`F444";
    static final String CLR_OK_DIM = "`FT537855";
    static final String CLR_DIFF_A = "`F0a0";
    static final String CLR_DIFF_R = "`F900";
    static final String CLR_DIFF_P = "`F0aa";

    /** Yes, the reference is intentionally weird here: three spaces is all a tab gets. */
    static final String TAB_WIDTH = "   ";

    static final List<String> CONVERTABLE_EXTS = List.of(".md");
    static final List<String> RENDERABLE_EXTS = List.of(".md", ".mu");
    static final List<String> RENDER_DEFAULT = List.of(".md", ".mu");
    static final List<String> IMAGE_EXTS = List.of(".webp", ".png", ".jpg", ".jpeg", ".gif", ".tiff", ".tif", ".bmp");

    static final String DEFAULT_BASE_TEMPLATE = "#!c=0\n> {NODE_NAME}\n\n{NAVIGATION}\n{PAGE_CONTENT}\n<\n-\n"
            + "`a`F666`[Served by rngit {VERSION}`:/page/index.mu] - {GEN_TIME}`f";
    static final String DEFAULT_FRONT_TEMPLATE = "> Groups\n\n{PAGE_CONTENT}";
    static final String DEFAULT_NO_IDENT_TEMPLATE = ">>No Identity\n\nThis page requires identification, and none was received.\n";
    static final List<String> TEMPLATE_NAMES = List.of("group", "repo", "releases", "release", "tree", "blob", "commits",
            "commit", "refs", "stats", "work", "work_doc");

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATE_MINUTES = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter DATE_SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Identity identity;
    private final String nodeName;
    private final long announceIntervalMillis;
    private final RngitRepositories repositories;
    private final byte[] repositoriesDestinationHash;
    private final Path templatesDir;
    private final String version;
    private final boolean useNerdFonts;
    private final Map<String, String> templates = new LinkedHashMap<>();
    private final RngitMicron mdc = new RngitMicron(MAX_RENDER_WIDTH, null);
    /** {@code thanks_deque}: who already thanked what, as hashes of link id and path. */
    private final Deque<String> thanksDeque = new ArrayDeque<>();

    private Destination destination;
    private long lastAnnounce = 0;
    private long lastLinkClean = 0;
    private final Map<String, Link> activeLinks = new ConcurrentHashMap<>();
    /** Files being sent on a link, deleted when it closes. */
    private final Map<String, List<Path>> linkTempFiles = new ConcurrentHashMap<>();

    RngitPages(Identity identity, String nodeName, long announceIntervalMillis, RngitRepositories repositories,
               byte[] repositoriesDestinationHash, Path configDir, RngitConfig config, String version) {
        this.identity = identity;
        this.nodeName = nodeName;
        this.announceIntervalMillis = announceIntervalMillis;
        this.repositories = repositories;
        this.repositoriesDestinationHash = repositoriesDestinationHash;
        this.templatesDir = configDir.resolve("templates");
        this.version = version;
        this.useNerdFonts = !config.getBool("pages", "unicode_icons", false);

        templates.put("base", DEFAULT_BASE_TEMPLATE);
        templates.put("front", DEFAULT_FRONT_TEMPLATE);
        for (String name : TEMPLATE_NAMES) templates.put(name, "{PAGE_CONTENT}");
        templates.put("no_ident", DEFAULT_NO_IDENT_TEMPLATE);

        if (!Files.isDirectory(templatesDir)) {
            try {
                Files.createDirectories(templatesDir);
            } catch (IOException e) {
                log.error("Could not create templates directory {}", templatesDir, e);
            }
        }
    }

    /** The handlers by request path, in the reference's registration order. */
    Map<String, Handler> handlers() {
        Map<String, Handler> out = new LinkedHashMap<>();
        out.put(PATH_INDEX, this::frontPage);
        out.put(PATH_GROUP, this::groupPage);
        out.put(PATH_REPO, this::repoPage);
        out.put(PATH_TREE, this::treePage);
        out.put(PATH_BLOB, this::blobPage);
        out.put(PATH_COMMITS, this::commitsPage);
        out.put(PATH_COMMIT, this::commitPage);
        out.put(PATH_REFS, this::refsPage);
        out.put(PATH_STATS, this::statsPage);
        out.put(PATH_RELEASES, this::releasesPage);
        out.put(PATH_RELEASE, this::releasePage);
        out.put(PATH_WORK, this::workPage);
        out.put(PATH_WORK_DOC, this::workDocPage);
        out.put(PATH_MEDIA, this::media);
        out.put(FILE_ARTIFACT, this::artifact);
        out.put(FILE_DOWNLOAD, this::download);
        out.put(FILE_WORKDOC, this::workDocDownload);
        return out;
    }

    /** Registers the page destination and its handlers. Reticulum must be running. */
    void start() {
        destination = new Destination(identity, Direction.IN, DestinationType.SINGLE, APP_NAME, ASPECT);
        destination.acceptsLinks(true);
        destination.setLinkEstablishedCallback(this::remoteConnected);
        // Images are sent as they are stored, as the reference sends media uncompressed
        handlers().forEach((path, handler) -> register(path, handler, !path.equals(PATH_MEDIA)));
        log.info("Git Nomad Network Node listening on <{}>", encodeHexString(destination.getHash()));
    }

    Destination getDestination() {
        return destination;
    }

    /** A page request: its data map, who asked (null when unidentified), and the link it came on. */
    static final class PageRequest {
        final Map<Object, Object> data;
        final String remote;
        final String linkId;

        PageRequest(Map<Object, Object> data, String remote, String linkId) {
            this.data = data == null ? Map.of() : data;
            this.remote = remote;
            this.linkId = linkId;
        }

        /** {@code data.get("var_<name>", fallback)}. */
        String var(String name, String fallback) {
            Object value = data.get("var_" + name);
            if (value == null) return fallback;
            if (value instanceof byte[]) return new String((byte[]) value, StandardCharsets.UTF_8);
            return value instanceof String ? (String) value : String.valueOf(value);
        }

        String identityHash() {
            return remote == null ? NULL_IDENTITY_HASH : remote;
        }
    }

    @FunctionalInterface
    interface Handler {
        Response apply(PageRequest request) throws Exception;
    }

    @SuppressWarnings("unchecked")
    private void register(String path, Handler handler, boolean autoCompress) {
        destination.registerRequestHandler(path, request -> {
            try {
                Object data = request.getDataObject();
                Identity remote = request.getRemoteIdentity();
                return handler.apply(new PageRequest(data instanceof Map ? (Map<Object, Object>) data : null,
                        remote == null ? null : encodeHexString(remote.getHash()), encodeHexString(request.getLinkId())));
            } catch (Exception e) {
                log.error("Error while serving page {}", path, e);
                return null;
            }
        }, RequestPolicy.ALLOW_ALL, null, autoCompress);
    }

    // ------------------------------------------------------------------
    // Links, temporary files and periodic jobs

    private void remoteConnected(Link link) {
        String linkId = encodeHexString(link.getLinkId());
        activeLinks.put(linkId, link);
        link.setLinkClosedCallback(closed -> cleanupLink(linkId));
    }

    private void cleanupLink(String linkId) {
        activeLinks.remove(linkId);
        List<Path> files = linkTempFiles.remove(linkId);
        if (files == null) return;
        for (Path file : files) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                log.error("Error while cleaning temporary file {}", file, e);
            }
        }
    }

    /** Called from the git node's jobs: announces, and cleans up after stale links. */
    void runJobs(long now) {
        if (announceIntervalMillis > 0 && now > lastAnnounce + announceIntervalMillis) {
            log.debug("Announcing page node destination");
            lastAnnounce = now;
            destination.announce(nodeName.getBytes(StandardCharsets.UTF_8));
        }
        if (now > lastLinkClean + LINK_CLEAN_INTERVAL_MS) {
            lastLinkClean = now;
            for (Map.Entry<String, Link> entry : new ArrayList<>(activeLinks.entrySet())) {
                if (entry.getValue().getStatus() != LinkStatus.ACTIVE) cleanupLink(entry.getKey());
            }
        }
    }

    void shutdown() {
        for (String linkId : new ArrayList<>(linkTempFiles.keySet())) cleanupLink(linkId);
    }

    /** A file response from bytes, through a temporary file deleted with the link. */
    private Response fileResponse(PageRequest request, byte[] content, String name) throws IOException {
        Path file = Files.createTempFile("rngit-page-", ".tmp");
        Files.write(file, content);
        if (request.linkId != null) linkTempFiles.computeIfAbsent(request.linkId, k -> new ArrayList<>()).add(file);
        return Response.ofFile(file.toFile(), Map.of("name", name.getBytes(StandardCharsets.UTF_8)));
    }

    // ------------------------------------------------------------------
    // Templates and formatting

    String icon(String name) {
        int[] icon = ICONS.get(name);
        return icon == null ? "" : new String(Character.toChars(icon[useNerdFonts ? 0 : 1]));
    }

    /** {@code get_template}: a custom template, run if executable; null if none. */
    private String getTemplate(String name) {
        Path path = templatesDir.resolve(name + ".mu");
        if (!Files.isRegularFile(path)) return null;
        try {
            if (Files.isExecutable(path)) {
                Process process = new ProcessBuilder(path.toString()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
                byte[] out = process.getInputStream().readAllBytes();
                if (!process.waitFor(TEMPLATE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) process.destroyForcibly();
                return new String(out, StandardCharsets.UTF_8).stripTrailing();
            }
            return Files.readString(path, StandardCharsets.UTF_8).stripTrailing();
        } catch (Exception e) {
            log.error("Could not get template content from {}", path, e);
            return null;
        }
    }

    /** {@code render_template}. */
    Response render(String pageContent, String navContent, String template, long startNanos) {
        pageContent = pageContent.replace("\t", TAB_WIDTH);
        String custom = template == null ? null : getTemplate(template);
        if (custom != null && !custom.isEmpty()) {
            pageContent = custom.replace("{PAGE_CONTENT}", pageContent);
        } else if (template != null && templates.containsKey(template)) {
            pageContent = templates.get(template).replace("{PAGE_CONTENT}", pageContent);
        }

        String base = getTemplate("base");
        if (base == null || base.isEmpty()) base = templates.get("base");
        base = base.replace("{NODE_NAME}", nodeName).replace("{VERSION}", version);
        base = base.replace("{NAVIGATION}", navContent == null ? "" : navContent);
        double elapsed = (System.nanoTime() - startNanos) / 1e9;
        base = base.replace("{GEN_TIME}", "Generated in " + prettyTime(elapsed, false));
        base = base.replace("{PAGE_CONTENT}", pageContent);
        return Response.of(base.getBytes(StandardCharsets.UTF_8));
    }

    private Response render(String content, long st) {
        return render(content, null, null, st);
    }

    private Response noIdent(String navContent, long st) {
        return render("", navContent, "no_ident", st);
    }

    /** The reference renders the no-identity page when an unidentified visitor's null identity is blocked. */
    private boolean blockedAnonymous(PageRequest request) {
        return request.remote == null && repositories.isBlocked(NULL_IDENTITY_HASH);
    }

    /** {@code RNS.prettysize}. */
    static String prettySize(double num) {
        String[] units = {"", "K", "M", "G", "T", "P", "E", "Z"};
        for (String unit : units) {
            if (Math.abs(num) < 1000.0) {
                return unit.isEmpty() ? String.format(Locale.ROOT, "%.0f %sB", num, unit)
                        : String.format(Locale.ROOT, "%.2f %sB", num, unit);
            }
            num /= 1000.0;
        }
        return String.format(Locale.ROOT, "%.2fYB", num);
    }

    /** {@code RNS.prettytime}, not verbose. */
    static String prettyTime(double time, boolean compact) {
        boolean neg = time < 0;
        time = Math.abs(time);
        long days = (long) (time / 86400);
        time = time % 86400;
        long hours = (long) (time / 3600);
        time %= 3600;
        long minutes = (long) (time / 60);
        time %= 60;
        String seconds;
        boolean hasSeconds;
        if (compact) {
            seconds = Long.toString((long) time);
            hasSeconds = (long) time > 0;
        } else {
            double rounded = Math.round(time * 100) / 100.0;
            seconds = Double.toString(rounded);
            hasSeconds = rounded > 0;
        }

        List<String> components = new ArrayList<>();
        if (days > 0 && (!compact || components.size() < 2)) components.add(days + "d");
        if (hours > 0 && (!compact || components.size() < 2)) components.add(hours + "h");
        if (minutes > 0 && (!compact || components.size() < 2)) components.add(minutes + "m");
        if (hasSeconds && (!compact || components.size() < 2)) components.add(seconds + "s");

        StringBuilder out = new StringBuilder();
        for (int i = 1; i <= components.size(); i++) {
            if (i > 1) out.append(i < components.size() ? ", " : " and ");
            out.append(components.get(i - 1));
        }
        if (out.length() == 0) return "0s";
        return neg ? "-" + out : out.toString();
    }

    static String prettyHex(byte[] data) {
        return "<" + Hex.encodeHexString(data) + ">";
    }

    private static String localTime(double timestamp, DateTimeFormatter format) {
        return Instant.ofEpochMilli((long) (timestamp * 1000)).atZone(ZoneId.systemDefault()).format(format);
    }

    /** {@code format_relative_time}. */
    static String relativeTime(long timestamp) {
        double diff = System.currentTimeMillis() / 1000.0 - timestamp;
        if (diff < 60) return "just now";
        long[][] steps = {{3600, 60}, {86400, 3600}, {604800, 86400}, {2592000, 604800}, {31536000, 2592000}};
        String[] names = {"minute", "hour", "day", "week", "month"};
        for (int i = 0; i < steps.length; i++) {
            if (diff < steps[i][0]) {
                long n = (long) (diff / steps[i][1]);
                return n + " " + names[i] + (n != 1 ? "s" : "") + " ago";
            }
        }
        long years = (long) (diff / 31536000);
        return years + " year" + (years != 1 ? "s" : "") + " ago";
    }

    /** {@code format_diff}: colours for additions, removals, hunks and headers. */
    static String formatDiff(String diff) {
        List<String> out = new ArrayList<>();
        for (String line : diff.replace("\\", "\\\\").split("\n", -1)) {
            if (line.startsWith("+")) {
                out.add(line.startsWith("+++") ? escape(line) : CLR_DIFF_A + escape(line) + "`f");
            } else if (line.startsWith("-")) {
                out.add(line.startsWith("---") ? escape("\\" + line) : CLR_DIFF_R + escape(line) + "`f");
            } else if (line.startsWith("@@")) {
                out.add(CLR_DIFF_P + escape(line) + "`f");
            } else if (line.startsWith("diff ") || line.startsWith("index ") || line.startsWith("new file")
                    || line.startsWith("deleted file")) {
                if (line.startsWith("diff --git a")) out.add("");
                out.add(CLR_DIM + escape(line) + "`f");
            } else {
                out.add(escape(line));
            }
        }
        return String.join("\n", out);
    }

    /** {@code format_commit}: a commit message, with lines starting "-" escaped. */
    static String formatCommit(String message) {
        List<String> out = new ArrayList<>();
        for (String line : message.replace("\\", "\\\\").split("\n", -1)) {
            out.add(escape(line.startsWith("-") ? "\\" + line : line));
        }
        return String.join("\n", out);
    }

    /** {@code os.path.splitext(path)[1].lower()}: leading dots of a name start no extension. */
    static String fileExtension(String path) {
        String name = baseName(path);
        int first = 0;
        while (first < name.length() && name.charAt(first) == '.') first++;
        int dot = name.lastIndexOf('.');
        return dot > first ? name.substring(dot).toLowerCase(Locale.ROOT) : "";
    }

    private static String baseName(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static String stem(String path) {
        String name = baseName(path);
        String ext = fileExtension(name);
        return name.substring(0, name.length() - ext.length());
    }

    static String unquotePlus(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return value;
        }
    }

    /** At most {@code n} code points of {@code s}. */
    private static String head(String s, int n) {
        return s.codePointCount(0, s.length()) <= n ? s : s.substring(0, s.offsetByCodePoints(0, n));
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1).toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------
    // Permissions (get_accessible_*)

    private boolean canRead(PageRequest request, String group, String repository) {
        return repositories.resolvePermission(request.identityHash(), group, repository, Permission.READ);
    }

    /** Configured groups with the repositories the visitor may read; QDN names are opened by name. */
    private Map<String, List<String>> accessibleGroups(PageRequest request) {
        Map<String, List<String>> out = new java.util.TreeMap<>();
        for (Map.Entry<String, Group> group : repositories.getGroups().entrySet()) {
            List<String> repos = accessibleRepositories(request, group.getKey());
            if (!repos.isEmpty()) out.put(group.getKey(), repos);
        }
        return out;
    }

    /** Sorted names; a QDN group lists from QDN, where every repository is public. */
    private List<String> accessibleRepositories(PageRequest request, String groupName) {
        Group group = repositories.getGroup(groupName);
        if (group == null) return List.of();
        List<String> out = new ArrayList<>();
        if (repositories.isQdnGroup(groupName)) {
            if (!repositories.isBlocked(request.identityHash())) out.addAll(RngitQdn.listRepositories(groupName));
        } else {
            for (String name : group.getRepositories().keySet()) {
                if (canRead(request, groupName, name)) out.add(name);
            }
        }
        out.sort(String::compareTo);
        return out;
    }

    private Repository accessibleRepository(PageRequest request, String group, String repository) {
        Repository repo = repositories.getRepository(group, repository);
        return repo != null && canRead(request, group, repository) ? repo : null;
    }

    private boolean canReadDocument(PageRequest request, String group, String repository, long docId) {
        return repositories.resolveDocumentPermission(request.identityHash(), group, repository, docId, Permission.READ);
    }

    /**
     * A repository's description: from its descriptor for QDN, where a group
     * listing uses only descriptors already materialised here, so listing a
     * name does not fetch every repository.
     */
    private String description(String group, String repository, Path path, boolean fetch) {
        if (repositories.isQdnGroup(group)) {
            RngitQdn.Descriptor descriptor = fetch ? repositories.qdnDescriptor(group, repository)
                    : repositories.cachedQdnDescriptor(group, repository);
            return descriptor == null || descriptor.description == null || descriptor.description.isBlank()
                    ? null : descriptor.description.strip();
        }
        return path == null ? null : RngitPagesGit.description(path);
    }

    /** {@code repository_thanks} / {@code release_thanks}: the count, after adding one if asked. */
    private long thanks(Path thanksFile, String thankedPath, boolean add, String linkId) {
        if (add) {
            byte[] link = new byte[0];
            try {
                if (linkId != null) link = Hex.decodeHex(linkId);
            } catch (Exception ignored) {
                // hashed without it
            }
            String hash = encodeHexString(IdentityUtils.fullHash(concat(link, thankedPath.getBytes(StandardCharsets.UTF_8))));
            synchronized (thanksDeque) {
                if (thanksDeque.contains(hash)) {
                    add = false;
                } else {
                    thanksDeque.addLast(hash);
                    while (thanksDeque.size() > 256) thanksDeque.removeFirst();
                }
            }
        }

        synchronized (thanksDeque) {
            try {
                long count = 0;
                if (Files.isRegularFile(thanksFile)) {
                    Object data = MsgPackUtils.unpackObject(Files.readAllBytes(thanksFile));
                    if (!(data instanceof Map) || !(((Map<?, ?>) data).get("count") instanceof Long)) {
                        throw new IOException("Invalid data in thanks file");
                    }
                    count = (Long) ((Map<?, ?>) data).get("count");
                }
                if (add) count++;
                if (add || !Files.isRegularFile(thanksFile)) {
                    Files.write(thanksFile, MsgPackUtils.packObject(Map.of("count", count)));
                }
                return count;
            } catch (Exception e) {
                log.error("Error while processing thanks for {}", thankedPath, e);
                return 0;
            }
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    // ------------------------------------------------------------------
    // Pages

    private String nodeLink() {
        return link("Node", PATH_INDEX);
    }

    private String groupLink(String group) {
        return link(group, PATH_GROUP, f("g", group));
    }

    private String repoLink(String group, String repo) {
        return link(repo, PATH_REPO, f("g", group, "r", repo));
    }

    /** {@code serve_front_page}, with a field to open a Qortal name's group when QDN is enabled. */
    Response frontPage(PageRequest request) {
        long st = System.nanoTime();
        StringBuilder content = new StringBuilder();
        String nav = ">>\n" + nodeLink() + " /\n";

        Map<String, List<String>> groups = accessibleGroups(request);
        if (groups.isEmpty()) {
            content.append(">>\nNo groups available\n");
        } else {
            for (Map.Entry<String, List<String>> group : groups.entrySet()) {
                int count = group.getValue().size();
                content.append(link("  " + RngitMicron.BULLET + " " + group.getKey(), PATH_GROUP, f("g", group.getKey())))
                        .append(" (").append(count).append(count == 1 ? " repository" : " repositories").append(")\n");
            }
        }
        if (repositories.getQdnGateway() != null) {
            content.append("\n>>Qortal Names\n\nEvery registered Qortal name is a group of repositories on QDN.\n\n")
                    .append("Name: `B333`<24|g`>`b ").append("`!`[Open`:").append(PATH_GROUP).append("`g]`!\n");
        }

        if (blockedAnonymous(request)) return noIdent(nav, st);
        return render(content.toString(), nav, "front", st);
    }

    /** {@code serve_group_page}; the group may also come from the front page's name field. */
    Response groupPage(PageRequest request) {
        long st = System.nanoTime();
        String group = request.var("g", "");
        if (group.isEmpty() && request.data.get("field_g") instanceof String) group = ((String) request.data.get("field_g")).strip();
        if (group.isEmpty()) return render(heading("Error", 2) + "\nInvalid request\n", st);

        String nav = ">>\n" + nodeLink() + " / " + escape(group) + "\n";
        if (blockedAnonymous(request)) return noIdent(nav, st);

        List<String> repos = accessibleRepositories(request, group);
        if (repos.isEmpty()) {
            return render(heading("Group Not Found", 2) + "\nThe requested group was not found\n", nav, null, st);
        }

        StringBuilder content = new StringBuilder(heading(" Repositories", 1)).append("\n");
        boolean qdn = repositories.isQdnGroup(group);
        Group groupData = repositories.getGroup(group);
        if (groupData == null) return render(heading("Group Not Found", 2) + "\nThe requested group was not found\n", nav, null, st);
        for (String name : repos) {
            content.append(link("  " + RngitMicron.BULLET + " " + name, PATH_REPO, f("g", group, "r", name)));
            Repository repository = qdn ? null : groupData.getRepositories().get(name);
            String description = description(group, name, repository == null ? null : repository.getPath(), false);
            content.append(description != null ? " - " + description + "\n" : "\n");
        }
        return render(content.toString(), nav, "group", st);
    }

    /** {@code serve_repo_page}. */
    Response repoPage(PageRequest request) {
        long st = System.nanoTime();
        String group = request.var("g", "");
        String repoName = request.var("r", "");
        String ref = request.var("ref", "HEAD");
        boolean thanks = !request.var("thanks", "").isEmpty();
        if (group.isEmpty() || repoName.isEmpty()) return render(heading("Error", 2) + "\nInvalid request\n", st);

        StringBuilder nav = new StringBuilder();
        String repoUrl = CLR_DIM + "rns://" + encodeHexString(repositoriesDestinationHash) + "/" + group + "/" + repoName + "`f";
        nav.append(">>\n").append(nodeLink()).append(" / ").append(groupLink(group)).append(" / ").append(repoName)
                .append(" ").append(repoUrl).append("\n");

        Repository repo = accessibleRepository(request, group, repoName);
        if (blockedAnonymous(request)) return noIdent(nav.toString(), st);
        if (repo == null) {
            return render(heading("Not Found", 1) + "\nThe requested repository was not found.\n", nav.toString(), null, st);
        }
        Path path = repo.getPath();

        String forkSource = repo.getForkSource();
        String mirrorSource = repo.getMirrorSource();
        if (repositories.isQdnGroup(group)) {
            RngitQdn.Descriptor descriptor = repositories.qdnDescriptor(group, repoName);
            if (descriptor != null && descriptor.upstream != null) {
                if ("fork".equals(descriptor.type)) forkSource = descriptor.upstream;
                else if ("mirror".equals(descriptor.type)) mirrorSource = descriptor.upstream;
            }
        }
        if (forkSource != null || mirrorSource != null) {
            String sourceType = forkSource != null ? "fork" : "mirror";
            String sourceUrl = forkSource != null ? forkSource : mirrorSource;
            String sourceLink = sourceLink(sourceUrl);

            long synced = RngitGit.upstreamSynced(path);
            String syncStr = "\n";
            if (synced > 0) {
                double ago = Math.max(0, System.currentTimeMillis() / 1000.0 - synced);
                syncStr = " `*" + CLR_DIM_H + "synced " + prettyTime(ago, true).split(" ")[0] + " ago`f`*\n";
            }
            String sourceDesc = sourceType + "ed from";
            String prefix = "Node / " + group + " / " + repoName;
            String indent = " ".repeat(Math.max(0, prefix.codePointCount(0, prefix.length()) - sourceDesc.length()));
            if (!sourceLink.isEmpty()) sourceUrl = sourceLink;
            nav.append(CLR_DIM).append(capitalize(sourceDesc)).append(indent).append(" ").append(sourceUrl).append("`f")
                    .append(syncStr).append("\n");
        }

        StringBuilder content = new StringBuilder();
        String description = description(group, repoName, path, true);
        content.append(description != null ? description + "\n\n" : "");
        long thanksCount = thanks(Path.of(path + ".thanks"), path.toString(), thanks, request.linkId);

        String resolved = RngitPagesGit.resolveRef(path, ref);
        int commits = resolved != null ? RngitPagesGit.commitCount(path, resolved) : 0;
        Map<String, List<RefInfo>> refs = RngitPagesGit.refs(path, null);
        int branches = refs.get("heads").size();
        int tags = refs.get("tags").size();
        int work = countWork(Path.of(path + ".work").resolve("active"));
        RngitReleases.ListData releases = new RngitReleases(path).listData();
        long releaseCount = releases == null ? 0
                : releases.releases.stream().filter(r -> "published".equals(r.get("status"))).count();

        String sep = icon("sep");
        content.append(linkR(icon("folder") + " Files", PATH_TREE, f("g", group, "r", repoName, "ref", "HEAD"))).append(" ").append(sep).append(" ");
        if (releaseCount > 0) {
            content.append(linkR(icon("package") + " Releases (" + releaseCount + ")", PATH_RELEASES, f("g", group, "r", repoName)))
                    .append(" ").append(sep).append(" ");
        }
        content.append(linkR(icon("work") + " Work (" + work + ")", PATH_WORK, f("g", group, "r", repoName))).append(" ").append(sep).append(" ");
        content.append(linkR(icon("commits") + " Commits (" + commits + ")", PATH_COMMITS, f("g", group, "r", repoName, "ref", "HEAD")))
                .append(" ").append(sep).append(" ");
        content.append(linkR(icon("branch") + " Branches (" + branches + ")", PATH_REFS, f("g", group, "r", repoName, "type", "heads")))
                .append(" ").append(sep).append(" ");
        content.append(linkR(icon("tag") + " Tags (" + tags + ")", PATH_REFS, f("g", group, "r", repoName, "type", "tags")))
                .append(" ").append(sep).append(" ");
        content.append(linkR(icon("heart") + " Thanks (" + thanksCount + ")", PATH_REPO, f("g", group, "r", repoName, "thanks", "y")));
        content.append("\n\n<");

        Object[] readme = RngitPagesGit.readme(path);
        if (readme != null) {
            String text = (String) readme[0];
            String lead = text.stripLeading();
            if (!lead.startsWith("#") && !lead.startsWith(">")) content.append(RngitMicron.divider());
            if ((Boolean) readme[1]) {
                String scope = ":/page/blob.mu`g=" + group + "|r=" + repoName + "|ref=" + ref + "|path=";
                content.append(new RngitMicron(MAX_RENDER_WIDTH, scope).formatBlock(text));
            } else {
                content.append("\n").append(text.stripTrailing()).append("\n");
            }
        } else {
            content.append(RngitMicron.divider()).append("\n").append(italic("No README file found in this repository.")).append("\n");
        }
        return render(content.toString(), nav.toString(), "repo", st);
    }

    /** The source of a fork or mirror as a link to its page node, when it is an rns:// URL of a known node. */
    private static String sourceLink(String sourceUrl) {
        if (!sourceUrl.toLowerCase(Locale.ROOT).startsWith("rns://")) return "";
        try {
            String[] components = sourceUrl.split("/", -1);
            if (components.length == 5 && components[2].length() == 32) {
                Identity source = IdentityKnownDestination.recall(Hex.decodeHex(components[2]));
                if (source != null) {
                    byte[] page = DestinationUtils.hashFromNameAndIdentity(APP_NAME + "." + ASPECT, source);
                    return RngitMicron.linkE(sourceUrl, encodeHexString(page), PATH_REPO, f("g", components[3], "r", components[4]));
                }
            }
        } catch (Exception ignored) {
            // shown without a link
        }
        return "";
    }

    private static int countWork(Path activeDir) {
        if (!Files.isDirectory(activeDir)) return 0;
        try (Stream<Path> entries = Files.list(activeDir)) {
            return (int) entries.filter(p -> p.getFileName().toString().matches("[0-9]+") && Files.isDirectory(p)).count();
        } catch (IOException e) {
            return 0;
        }
    }

    private static String notFoundOrNoAccess() {
        return heading("Not Found", 1) + "\n\nThe requested repository does not exist or you do not have access to it.\n";
    }

    private static int pageNumber(PageRequest request) {
        try {
            return Math.max(0, Integer.parseInt(request.var("page", "0").strip()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** {@code serve_tree_page}. */
    Response treePage(PageRequest request) {
        long st = System.nanoTime();
        String group = request.var("g", "");
        String repoName = request.var("r", "");
        String ref = request.var("ref", "HEAD");
        String treePath = unquotePlus(request.var("path", ""));
        int pageNum = pageNumber(request);

        if (blockedAnonymous(request)) return noIdent(null, st);
        Repository repo = accessibleRepository(request, group, repoName);
        if (repo == null) return render(notFoundOrNoAccess(), st);
        Path path = repo.getPath();

        String resolved = RngitPagesGit.resolveRef(path, ref);
        if (resolved == null) {
            return render(heading("Error", 2) + "\n\nThe ref '" + ref + "' does not exist in this repository.\n"
                    + "\n" + link("View All Refs", PATH_REFS, f("g", group, "r", repoName)) + "\n", st);
        }

        List<String> crumbs = new ArrayList<>(List.of(nodeLink(), groupLink(group), repoLink(group, repoName),
                link("files", PATH_TREE, f("g", group, "r", repoName))));
        if (!treePath.isEmpty()) {
            String[] components = RngitPagesGit.strip(treePath, '/').split("/", -1);
            String current = "";
            for (int i = 0; i < components.length; i++) {
                current = current.isEmpty() ? components[i] : current + "/" + components[i];
                crumbs.add(i == components.length - 1 ? components[i]
                        : link(components[i], PATH_TREE, f("g", group, "r", repoName, "ref", ref, "path", current)));
            }
        } else {
            crumbs.add("");
        }
        String nav = ">>\n" + String.join(" / ", crumbs) + "\n";

        List<String> content = new ArrayList<>();
        List<TreeEntry> entries = RngitPagesGit.treeEntries(path, resolved, treePath);
        if (entries == null) {
            content.add("Error reading directory contents.\n");
        } else if (entries.isEmpty()) {
            content.add("Empty directory.\n");
        } else {
            String iFile = icon("file");
            String iFolder = icon("folder");
            entries.sort(Comparator.comparing((TreeEntry e) -> !(e.type.equals("tree") || e.type.equals("commit")))
                    .thenComparing(e -> e.name.toLowerCase(Locale.ROOT)));

            int total = entries.size();
            int start = pageNum * TREE_ENTRIES_PER_PAGE;
            int end = start + TREE_ENTRIES_PER_PAGE;
            List<TreeEntry> pageEntries = start >= total ? List.of() : entries.subList(start, Math.min(end, total));

            content.add(heading("Contents: " + ref + " (" + resolved.substring(0, 8) + ")", 2));
            content.add("\n");
            if (total > TREE_ENTRIES_PER_PAGE) {
                content.add(CLR_DIM + "Showing " + (start + 1) + "-" + Math.min(end, total) + " of " + total + " entries`f\n\n");
            }

            if (!treePath.isEmpty()) {
                String trimmed = treePath.replaceAll("/+$", "");
                String parent = trimmed.contains("/") ? trimmed.substring(0, trimmed.lastIndexOf('/')) : "";
                Map<String, Object> fields = f("g", group, "r", repoName, "ref", ref, "path", parent);
                content.add(CLR_FOLDER + linkR(iFolder, PATH_TREE, fields) + "`f" + linkR(" ../", PATH_TREE, fields) + "\n");
            }

            for (TreeEntry entry : pageEntries) {
                String subpath = treePath.isEmpty() ? entry.name : treePath + "/" + entry.name;
                if (entry.type.equals("tree")) {
                    Map<String, Object> fields = f("g", group, "r", repoName, "ref", ref, "path", subpath);
                    content.add(CLR_FOLDER + linkR(iFolder, PATH_TREE, fields) + "`f" + linkR(" " + entry.name + "/", PATH_TREE, fields) + "\n");
                } else if (entry.type.equals("commit")) {
                    content.add(CLR_FOLDER + "⧉`f " + entry.name + " `F666(submodule)`f\n");
                } else if (entry.type.equals("link")) {
                    String target = entry.linkTarget == null ? "None" : entry.linkTarget;
                    content.add(CLR_FILE + "↳`f " + entry.name + " `F666→ " + escape(target) + "`f\n");
                } else {
                    Map<String, Object> fields = f("g", group, "r", repoName, "ref", ref, "path", subpath);
                    content.add(CLR_FILE + linkR(iFile, PATH_BLOB, fields) + "`f" + linkR(" " + entry.name, PATH_BLOB, fields)
                            + " `F666(" + prettySize(entry.size) + ")`f\n");
                }
            }
            content.add("\n");

            if (total > TREE_ENTRIES_PER_PAGE) {
                List<String> navLinks = new ArrayList<>();
                if (pageNum > 0) {
                    navLinks.add(link("« Previous", PATH_TREE, f("g", group, "r", repoName, "ref", ref, "path", treePath, "page", pageNum - 1)));
                }
                int totalPages = (total + TREE_ENTRIES_PER_PAGE - 1) / TREE_ENTRIES_PER_PAGE;
                navLinks.add("Page " + (pageNum + 1) + " of " + totalPages);
                if (end < total) {
                    navLinks.add(link("Next »", PATH_TREE, f("g", group, "r", repoName, "ref", ref, "path", treePath, "page", pageNum + 1)));
                }
                content.add(String.join(" | ", navLinks) + "\n");
            }
        }
        if (content.get(content.size() - 1).equals("\n")) content.set(content.size() - 1, "");
        return render(String.join("", content), nav, "tree", st);
    }

    /** {@code serve_blob_page}. */
    Response blobPage(PageRequest request) {
        long st = System.nanoTime();
        String group = request.var("g", "");
        String repoName = request.var("r", "");
        String ref = request.var("ref", "HEAD");
        String filePath = unquotePlus(request.var("path", ""));
        boolean render = !request.var("render", "").isEmpty();
        boolean raw = !request.var("raw", "").isEmpty();

        if (blockedAnonymous(request)) return noIdent(null, st);
        Repository repo = accessibleRepository(request, group, repoName);
        if (repo == null) return render(notFoundOrNoAccess(), st);
        Path path = repo.getPath();

        String resolved = RngitPagesGit.resolveRef(path, ref);
        if (resolved == null) {
            return render(heading("Ref Not Found", 1) + "\n\nThe ref '" + ref + "' does not exist in this repository.\n", st);
        }
        if (filePath.isEmpty()) return render(heading("Invalid Path", 1) + "\n\nNo file path specified.\n", st);

        if (filePath.startsWith("./")) filePath = filePath.substring(2);
        filePath = filePath.replace("/./", "/");
        String ext = fileExtension(filePath);
        boolean renderable = RENDERABLE_EXTS.contains(ext);
        boolean convertable = CONVERTABLE_EXTS.contains(ext);
        if (!renderable) {
            raw = true;
            render = false;
        } else if (raw) {
            render = false;
        } else if (!render && RENDER_DEFAULT.contains(ext)) {
            render = true;
        }

        List<String> crumbs = new ArrayList<>(List.of(nodeLink(), groupLink(group), repoLink(group, repoName),
                link("files", PATH_TREE, f("g", group, "r", repoName))));
        String[] components = RngitPagesGit.strip(filePath, '/').split("/", -1);
        String current = "";
        for (int i = 0; i < components.length; i++) {
            current = current.isEmpty() ? components[i] : current + "/" + components[i];
            crumbs.add(i == components.length - 1 ? components[i]
                    : link(components[i], PATH_TREE, f("g", group, "r", repoName, "ref", ref, "path", current)));
        }
        StringBuilder nav = new StringBuilder(">>\n").append(String.join(" / ", crumbs)).append("\n");
        String sep = icon("sep");

        String dlLink = link("Download", FILE_DOWNLOAD, f("g", group, "r", repoName, "ref", ref, "path", filePath));
        if (!renderable) {
            nav.append("\nDisplaying Raw ").append(sep).append(" ").append(dlLink).append("\n");
        } else {
            String rndLink = link("View rendered", PATH_BLOB, f("g", group, "r", repoName, "ref", ref, "path", filePath, "render", "y"));
            String rawLink = link("View raw", PATH_BLOB, f("g", group, "r", repoName, "ref", ref, "path", filePath, "raw", "y"));
            String muLink = link("as micron", FILE_DOWNLOAD, f("g", group, "r", repoName, "ref", ref, "path", filePath, "fmt", "mu"));
            String controls = render ? "Displaying Rendered " + sep + " " + rawLink : "Displaying Raw " + sep + " " + rndLink;
            nav.append("\n").append(controls).append(" ").append(sep).append(" ").append(dlLink);
            if (convertable) nav.append(" ").append(CLR_DIM).append(muLink).append("`f");
            nav.append("\n");
        }

        StringBuilder content = new StringBuilder();
        BlobInfo info = RngitPagesGit.blobInfo(path, resolved, filePath);
        if (info == null) {
            content.append("File not found at this ref.\n");
        } else {
            if (info.isTree) return treePage(request);

            String type = info.isBinary ? "Binary" : "Text";
            String symlink = info.isSymlink ? " | Symlink → " + escape(info.symlinkTarget == null ? "unknown" : info.symlinkTarget) : "";
            content.append(heading(filePath + " " + CLR_DIM_H + ref + " (" + resolved.substring(0, 8) + ") " + type + ", "
                    + prettySize(info.size) + symlink + "`f\n", 2));

            if (info.isSymlink) {
                content.append("`*").append(escape(info.symlinkTarget == null ? "unknown" : info.symlinkTarget)).append("`*\n");
            } else if (info.isBinary) {
                if (IMAGE_EXTS.contains(ext)) {
                    content.append("`(Image file`w=n`a=c`:/media/").append(group).append("/").append(repoName).append("/").append(ref)
                            .append("/").append(RngitMicron.quotePlus(filePath)).append(")\n");
                } else {
                    content.append("This file appears to be binary and cannot be displayed as text.\n");
                }
            } else if (info.size > BLOB_SIZE_LIMIT) {
                content.append("This file is ").append(prettySize(info.size)).append(", which exceeds the display limit of ")
                        .append(prettySize(BLOB_SIZE_LIMIT)).append(".\n");
            } else {
                String text = RngitPagesGit.blobContent(path, resolved, filePath);
                if (text == null) {
                    content.append("Error reading file content.\n");
                } else if (renderable && render) {
                    if (ext.equals(".mu")) {
                        content.append(text.stripTrailing()).append("\n");
                    } else {
                        content.append(new RngitMicron(MAX_RENDER_WIDTH, blobScope(group, repoName, ref, filePath))
                                .formatBlock(text).stripTrailing()).append("\n");
                    }
                } else {
                    content.append(RngitMicron.plainHighlight(text).stripTrailing()).append("\n");
                }
            }
        }
        return render(content.toString(), nav.toString(), "blob", st);
    }

    /** The URL scope relative Markdown links in a file resolve against: its directory. */
    private static String blobScope(String group, String repo, String ref, String filePath) {
        String[] components = RngitPagesGit.strip(filePath, '/').split("/", -1);
        String dir = components.length > 1 ? String.join("/", List.of(components).subList(0, components.length - 1)) + "/" : "";
        return ":/page/blob.mu`g=" + group + "|r=" + repo + "|ref=" + ref + "|path=" + dir;
    }

    /** {@code serve_commits_page}. */
    Response commitsPage(PageRequest request) {
        long st = System.nanoTime();
        String group = request.var("g", "");
        String repoName = request.var("r", "");
        String ref = request.var("ref", "HEAD");
        String filePath = unquotePlus(request.var("path", ""));
        int pageNum = pageNumber(request);

        if (blockedAnonymous(request)) return noIdent(null, st);
        Repository repo = accessibleRepository(request, group, repoName);
        if (repo == null) return render(notFoundOrNoAccess(), st);
        Path path = repo.getPath();

        String resolved = RngitPagesGit.resolveRef(path, ref);
        if (resolved == null) {
            return render(heading("Ref Not Found", 1) + "\n\nThe ref '" + ref + "' does not exist in this repository.\n", st);
        }

        List<String> crumbs = new ArrayList<>(List.of(nodeLink(), groupLink(group), repoLink(group, repoName), "commits"));
        if (!filePath.isEmpty()) crumbs.add(3, escape(filePath));
        String nav = ">>\n" + String.join(" / ", crumbs) + "\n";
        String titleSuffix = filePath.isEmpty() ? "" : " for " + filePath;

        StringBuilder content = new StringBuilder();
        List<CommitEntry> commits = RngitPagesGit.commits(path, resolved, filePath, pageNum * COMMITS_PER_PAGE, COMMITS_PER_PAGE);
        if (commits == null) {
            content.append("Error reading commit history.\n");
        } else if (commits.isEmpty()) {
            content.append("No commits found.\n");
        } else {
            content.append(heading("Commits" + titleSuffix + " " + CLR_DIM_H + ref + " (" + resolved.substring(0, 8) + ")`f", 2)).append("\n");
            for (CommitEntry commit : commits) {
                String date = localTime(commit.timestamp, DATE_SECONDS) + " - " + relativeTime(commit.timestamp);
                String hashLink = link(commit.hash.substring(0, 7), PATH_COMMIT, f("g", group, "r", repoName, "ref", ref, "h", commit.hash));
                content.append("`F66d").append(hashLink).append("`f ").append(escape(commit.author)).append(" ").append(CLR_DIM)
                        .append(date).append("`f\n").append(escape(commit.subject)).append("\n\n");
            }
            boolean hasMore = commits.size() == COMMITS_PER_PAGE;
            if (pageNum > 0 || hasMore) {
                List<String> navLinks = new ArrayList<>();
                if (pageNum > 0) {
                    navLinks.add(link("« Newer", PATH_COMMITS, f("g", group, "r", repoName, "ref", ref, "path", filePath, "page", pageNum - 1)));
                }
                navLinks.add("Page " + (pageNum + 1));
                if (hasMore) {
                    navLinks.add(link("Older »", PATH_COMMITS, f("g", group, "r", repoName, "ref", ref, "path", filePath, "page", pageNum + 1)));
                }
                content.append(String.join(" | ", navLinks)).append("\n");
            }
        }
        return render(content.toString(), nav, "commits", st);
    }

    /** {@code serve_commit_page}. */
    Response commitPage(PageRequest request) {
        long st = System.nanoTime();
        String group = request.var("g", "");
        String repoName = request.var("r", "");
        String ref = request.var("ref", "HEAD");
        String hash = request.var("h", "");
        if (group.isEmpty() || repoName.isEmpty()) return render(heading("Error", 2) + "\nInvalid request\n", st);

        if (blockedAnonymous(request)) return noIdent(null, st);
        Repository repo = accessibleRepository(request, group, repoName);
        if (repo == null) return render(heading("Error", 2) + "\nThe requested repository was not found.\n", st);
        Path path = repo.getPath();

        if (RngitPagesGit.resolveRef(path, ref) == null) {
            return render(heading("Ref Not Found", 1) + "\n\nThe ref '" + ref + "' does not exist in this repository.\n", st);
        }
        if (hash.codePointCount(0, hash.length()) < 7) return render(heading("Error", 2) + "\nNo valid commit hash specified.\n", st);
        String resolved = RngitPagesGit.resolveRef(path, hash);
        if (resolved == null) {
            return render(heading("Error", 2) + "\nThe commit " + hash + " does not exist in this repository.\n", st);
        }

        String nav = ">>\n" + nodeLink() + " / " + groupLink(group) + " / " + repoLink(group, repoName) + " / "
                + link("commits", PATH_COMMITS, f("g", group, "r", repoName, "ref", ref)) + " / " + resolved.substring(0, 7) + "\n";

        if (!"commit".equals(RngitPagesGit.objectType(path, resolved))) {
            return render(heading("Error", 2) + "\nThe hash " + hash + " does not refer to a commit.\n", st);
        }
        CommitInfo info = RngitPagesGit.commitInfo(path, resolved);
        if (info == null) return render(heading("Error", 2) + "\n\nCould not retrieve commit information.\n", st);

        StringBuilder content = new StringBuilder(heading("Commit " + resolved, 2)).append("\n");
        content.append(link(icon("folder") + " Browse tree at this commit", PATH_TREE, f("g", group, "r", repoName, "ref", resolved)))
                .append("\n\n");

        RngitCommitSignatures.Status signature = RngitCommitSignatures.check(RngitPagesGit.rawCommit(path, resolved));
        String sigText = null;
        if (signature.signed) {
            if (signature.valid && signature.authorMatch) sigText = "`FT66BB85Valid, signed by author`f";
            else if (signature.valid) sigText = "`Faa0" + escape(signature.message) + "`f";
            else sigText = "`F900" + escape(signature.message) + "`f";
        }

        if (!info.parents.isEmpty()) {
            List<String> parentLinks = new ArrayList<>();
            for (String parent : info.parents) {
                parentLinks.add(link(parent.substring(0, 7), PATH_COMMIT, f("g", group, "r", repoName, "ref", ref, "h", parent)));
            }
            content.append("Parents    : ").append(String.join(" ", parentLinks)).append("\n");
        }
        content.append("Author     : ").append(escape(info.authorName)).append(" <").append(escape(info.authorEmail)).append(">\n");
        if (sigText != null) content.append("Signature  : ").append(sigText).append("\n");
        content.append("Date       : ").append(info.authorDate).append("\n");
        if (!info.committerName.equals(info.authorName)) {
            content.append("Committer : ").append(escape(info.committerName)).append(" <").append(escape(info.committerEmail)).append(">\n");
            content.append("Date      : ").append(info.committerDate).append("\n");
        }
        content.append("\n");

        if (!info.message.isEmpty()) content.append(formatCommit(info.message)).append("\n").append("\n");

        if (!info.files.isEmpty()) {
            content.append(heading("Changes", 2)).append("\n");
            int additions = info.files.stream().mapToInt(c -> c.additions).sum();
            int deletions = info.files.stream().mapToInt(c -> c.deletions).sum();
            content.append("  ").append(info.files.size()).append(" files changed, ").append(additions).append(" insertions(+), ")
                    .append(deletions).append(" deletions(-)\n\n");
            Map<String, String> indicators = Map.of("A", "`F0a0A`f", "D", "`F900D`f", "M", "`Faa0M`f", "R", "`F0aaR`f");
            for (FileChange change : info.files) {
                String fileLink = link(escape(change.path), PATH_BLOB, f("g", group, "r", repoName, "ref", resolved, "path", change.path));
                List<String> stats = new ArrayList<>();
                if (change.additions > 0) stats.add("`F0a0+" + change.additions + "`f");
                if (change.deletions > 0) stats.add("`F900-" + change.deletions + "`f");
                content.append("  ").append(indicators.getOrDefault(change.status, change.status)).append(" ").append(fileLink)
                        .append(" ").append(String.join(" ", stats)).append("\n");
            }
            content.append("\n");
        }

        if (info.diff != null && !info.diff.isEmpty()) {
            content.append(heading("Diff", 2)).append("\n").append(formatDiff(info.diff).stripLeading());
        }
        return render(content.toString(), nav, "commit", st);
    }

    /** {@code serve_refs_page}. */
    Response refsPage(PageRequest request) {
        long st = System.nanoTime();
        String group = request.var("g", "");
        String repoName = request.var("r", "");
        String refType = request.var("type", "");

        String nav = ">>\n" + nodeLink() + " / " + groupLink(group) + " / " + repoLink(group, repoName) + " / refs\n";
        if (blockedAnonymous(request)) return noIdent(null, st);
        if (group.isEmpty() || repoName.isEmpty()) return render(heading("Error", 2) + "\nInvalid request\n", st);

        Repository repo = accessibleRepository(request, group, repoName);
        if (repo == null) return render(heading("Error", 2) + "\nThe requested repository was not found.\n", nav, null, st);
        Path path = repo.getPath();

        StringBuilder content = new StringBuilder();
        String sep = " " + icon("sep") + " ";
        content.append(String.join(sep, List.of(link("All", PATH_REFS, f("g", group, "r", repoName)),
                link("Branches only", PATH_REFS, f("g", group, "r", repoName, "type", "heads")),
                link("Tags only", PATH_REFS, f("g", group, "r", repoName, "type", "tags"))))).append("\n\n");

        boolean showHeads = refType.isEmpty() || refType.equals("heads");
        boolean showTags = refType.isEmpty() || refType.equals("tags");
        Map<String, List<RefInfo>> refs = RngitPagesGit.refs(path, RngitPagesGit.defaultBranch(path));
        List<RefInfo> heads = refs.get("heads");
        List<RefInfo> tags = refs.get("tags");

        if (showHeads && !heads.isEmpty()) {
            content.append(heading("Branches (" + heads.size() + ")", 2)).append("\n");
            for (RefInfo info : heads) {
                String name = info.isDefault ? "`F0a0" + info.name + "`f" : info.name;
                String marker = info.isDefault ? " `F0a0(default)`f" : "";
                content.append(name).append(marker).append(" [")
                        .append(link("tree", PATH_TREE, f("g", group, "r", repoName, "ref", info.name))).append("] [")
                        .append(link("commits", PATH_COMMITS, f("g", group, "r", repoName, "ref", info.name))).append("]\n")
                        .append(info.shortHash).append(": ").append(escape(info.commitSubject)).append("\n\n");
            }
        }
        if (showTags && !tags.isEmpty()) {
            content.append(heading("Tags (" + tags.size() + ")", 2)).append("\n");
            for (int i = tags.size() - 1; i >= 0; i--) {
                RefInfo info = tags.get(i);
                String marker = info.isAnnotated ? " `Faa0(annotated)`f" : "";
                content.append(info.name).append(marker).append(" ").append(CLR_DIM).append(info.shortHash).append("`f [")
                        .append(link("tree", PATH_TREE, f("g", group, "r", repoName, "ref", info.name))).append("] [")
                        .append(link("commits", PATH_COMMITS, f("g", group, "r", repoName, "ref", info.name))).append("]\n");
                if (info.isAnnotated && info.tagMessage != null && !info.tagMessage.isEmpty()) {
                    content.append(escape(head(info.tagMessage, 512))).append("\n\n");
                } else {
                    content.append(escape(info.commitSubject)).append("\n\n");
                }
            }
        }
        if (showHeads && heads.isEmpty() && showTags && tags.isEmpty()) content.append("No refs found in this repository.\n");
        return render(content.toString().stripTrailing() + "\n", nav, "refs", st);
    }

    /** {@code serve_stats_page}: Core records no statistics, so they are always unavailable. */
    Response statsPage(PageRequest request) {
        long st = System.nanoTime();
        String group = request.var("g", "");
        String repoName = request.var("r", "");
        if (group.isEmpty() || repoName.isEmpty()) return render(heading("Error", 2) + "\nInvalid request\n", st);
        if (blockedAnonymous(request)) return noIdent(null, st);

        String nav = ">>\n" + nodeLink() + " / " + groupLink(group) + " / " + repoLink(group, repoName) + " / stats\n";
        Repository repo = accessibleRepository(request, group, repoName);
        boolean statsPermission = repositories.resolvePermission(request.identityHash(), group, repoName, Permission.STATS);
        if (repo == null || !statsPermission) {
            return render(heading("Error", 2) + "\nThe requested repository was not found.\n", nav, null, st);
        }
        return render(heading("Stats Unavailable", 2) + "\nCould not retrieve statistics for this repository.\n", nav, null, st);
    }

    /** {@code serve_releases_page}. */
    Response releasesPage(PageRequest request) {
        long st = System.nanoTime();
        String group = request.var("g", "");
        String repoName = request.var("r", "");
        if (group.isEmpty() || repoName.isEmpty()) return render(heading("Error", 2) + "\nInvalid request\n", st);
        if (blockedAnonymous(request)) return noIdent(null, st);

        Repository repo = accessibleRepository(request, group, repoName);
        if (repo == null) return render(heading("Error", 2) + "\nThe requested repository was not found.\n", st);

        String nav = ">>\n" + nodeLink() + " / " + groupLink(group) + " / " + repoLink(group, repoName) + " / releases\n";
        RngitReleases.ListData list = new RngitReleases(repo.getPath()).listData();
        if (list == null || list.releases.isEmpty()) {
            return render(heading("Releases", 2) + "\nNo releases available for this repository.\n", nav, "repo", st);
        }

        List<Map<String, Object>> published = new ArrayList<>();
        for (Map<String, Object> release : list.releases) if ("published".equals(release.get("status"))) published.add(release);

        StringBuilder content = new StringBuilder(heading("Releases (" + published.size() + ")", 2)).append("\n");
        String sep = icon("sep");
        for (Map<String, Object> release : published) {
            String tag = String.valueOf(release.getOrDefault("tag", "unknown"));
            long created = ((Number) release.getOrDefault("created", 0L)).longValue();
            String date = created != 0 ? localTime(created, DATE) : "unknown";
            long artifacts = ((Number) release.getOrDefault("artifacts", 0L)).longValue();
            String format = String.valueOf(release.getOrDefault("format", "markdown"));
            String[] previewLines = String.valueOf(release.getOrDefault("preview", "")).split("\\R");
            String firstLine = previewLines.length > 0 ? previewLines[0] : "";
            String preview = head(firstLine, 2048);
            if (firstLine.length() > preview.length()) preview += "…";

            String latest = tag.equals(list.latest) ? " " + sep + " " + CLR_OK_DIM + "`*Latest`*`f" : "";
            String artifactsStr = "`*" + artifacts + " artifact" + (artifacts != 1 ? "s" : "") + "`*";
            content.append(link(tag, PATH_RELEASE, f("g", group, "r", repoName, "t", tag))).append(" ").append(CLR_DIM).append(date)
                    .append(" ").append(sep).append(" ").append(artifactsStr).append(latest).append("`f\n");
            if (!preview.isEmpty()) {
                if (format.equals("markdown")) content.append(mdc.formatBlock(preview)).append("\n");
                else if (format.equals("micron")) content.append(preview).append("\n");
                else content.append(escape(preview)).append("\n");
            }
            content.append("\n");
        }
        return render(content.toString().stripTrailing() + "\n", nav, "releases", st);
    }

    /** The tag "latest" stands for: the latest release, else the most recently created. */
    private static String resolveLatest(RngitReleases.ListData list) {
        if (list.latest != null) return list.latest;
        return list.releases.stream()
                .max(Comparator.comparingLong(r -> ((Number) r.getOrDefault("created", 0L)).longValue()))
                .map(r -> String.valueOf(r.get("tag"))).orElse(null);
    }

    /** {@code serve_release_page}. */
    Response releasePage(PageRequest request) {
        long st = System.nanoTime();
        String group = request.var("g", "");
        String repoName = request.var("r", "");
        String tag = request.var("t", "");
        if (group.isEmpty() || repoName.isEmpty() || tag.isEmpty()) return render(heading("Error", 2) + "\nInvalid request\n", st);
        if (blockedAnonymous(request)) return noIdent(null, st);

        Repository repo = accessibleRepository(request, group, repoName);
        if (repo == null) return render(heading("Error", 2) + "\nThe requested repository was not found.\n", st);

        String nav = ">>\n" + nodeLink() + " / " + groupLink(group) + " / " + repoLink(group, repoName) + " / "
                + link("releases", PATH_RELEASES, f("g", group, "r", repoName)) + " / " + tag + "\n";

        RngitReleases releases = new RngitReleases(repo.getPath());
        if (tag.equals("latest")) {
            RngitReleases.ListData list = releases.listData();
            if (list == null || list.releases.isEmpty()) {
                return render(heading("Release Not Found", 2) + "\nNo releases exist.\n", nav, null, st);
            }
            tag = resolveLatest(list);
        }

        Path releaseDir = tag == null || RngitReleases.pathComponent(tag) == null ? null : releases.getReleasesPath().resolve(tag);
        if (releaseDir == null || !Files.isDirectory(releaseDir)) {
            return render(heading("Release Not Found", 2) + "\nThe release " + tag + " does not exist.\n", nav, null, st);
        }
        Map<String, Object> info = RngitReleases.releaseData(releaseDir, tag);
        if (info == null) return render(heading("Error", 2) + "\nCould not load release data.\n", nav, null, st);
        if (!"published".equals(info.get("status"))) {
            return render(heading("Release Not Found", 2) + "\nThe release " + tag + " does not exist.\n", nav, null, st);
        }

        String sep = icon("sep");
        boolean thanks = !request.var("thanks", "").isEmpty();
        long thanksCount = thanks(releaseDir.resolve("THANKS"), releaseDir.toString(), thanks, request.linkId);
        StringBuilder content = new StringBuilder();
        content.append(linkR(icon("heart") + " Thanks (" + thanksCount + ")", PATH_RELEASE,
                f("g", group, "r", repoName, "t", tag, "thanks", "y"))).append("\n\n");

        long created = ((Number) info.getOrDefault("created", 0L)).longValue();
        String ts = created != 0 ? " " + sep + " " + localTime(created, DATE_SECONDS) : "";
        content.append(heading("Release " + tag + ts, 2)).append("\n");

        String notes = String.valueOf(info.getOrDefault("notes", ""));
        if (!notes.isEmpty()) {
            String format = String.valueOf(info.getOrDefault("notes_format", "text"));
            if (format.equals("micron")) content.append(notes).append("\n");
            else if (format.equals("markdown")) content.append(mdc.formatBlock(notes)).append("\n");
            else content.append("`=").append(notes).append("`=\n");
            content.append("\n");
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> artifacts = (List<Map<String, Object>>) info.getOrDefault("artifacts", List.of());
        if (!artifacts.isEmpty()) {
            content.append(heading("Artifacts (" + artifacts.size() + ")", 2)).append("\n");
            List<Map<String, Object>> sorted = new ArrayList<>(artifacts);
            sorted.sort(Comparator.comparing(a -> String.valueOf(a.get("name"))));
            for (Map<String, Object> artifact : sorted) {
                String name = String.valueOf(artifact.getOrDefault("name", "unknown"));
                long size = ((Number) artifact.getOrDefault("size", 0L)).longValue();
                String sizeStr = size != 0 ? prettySize(size) : "0 B";
                Map<String, Object> fields = f("g", group, "r", repoName, "t", tag, "a", name);
                content.append(linkR(icon("file") + " " + escape(name), FILE_ARTIFACT, fields)).append(" ").append(CLR_DIM)
                        .append(linkR("(" + sizeStr + ")", FILE_ARTIFACT, fields)).append("`f\n");
            }
        } else {
            content.append(heading("Artifacts", 2)).append("\n`*No artifacts for this release`*\n");
        }
        return render(content.toString(), nav, "release", st);
    }

    /** {@code serve_work_page}. */
    Response workPage(PageRequest request) {
        long st = System.nanoTime();
        String group = request.var("g", "");
        String repoName = request.var("r", "");
        String scope = request.var("scope", "active");
        if (!List.of("active", "completed", "proposed", "all").contains(scope)) scope = "active";
        if (group.isEmpty() || repoName.isEmpty()) return render(heading("Error", 2) + "\nInvalid request\n", st);
        if (blockedAnonymous(request)) return noIdent(null, st);

        Repository repo = accessibleRepository(request, group, repoName);
        if (repo == null) return render(heading("Error", 2) + "\nThe requested repository was not found.\n", st);

        Path workPath = Path.of(repo.getPath() + ".work");
        List<String> scopesToShow = scope.equals("all") ? RngitWork.SCOPES : List.of(scope);
        String nav = ">>\n" + nodeLink() + " / " + groupLink(group) + " / " + repoLink(group, repoName) + " / work\n";

        Map<String, List<Map<String, Object>>> docs = new LinkedHashMap<>();
        for (String s : RngitWork.SCOPES) docs.put(s, workDocuments(request, group, repoName, workPath.resolve(s)));

        String sep = icon("sep");
        int adc = docs.get("active").size();
        int cdc = docs.get("completed").size();
        int pdc = docs.get("proposed").size();
        List<String> filters = new ArrayList<>();
        String[][] scopes = {{"active", "Active"}, {"completed", "Completed"}, {"proposed", "Proposed"}, {"all", "All"}};
        int[] counts = {adc, cdc, pdc, adc + cdc + pdc};
        for (int i = 0; i < scopes.length; i++) {
            String u = scope.equals(scopes[i][0]) ? "`_" : "";
            filters.add(u + link(scopes[i][1], PATH_WORK, f("g", group, "r", repoName, "scope", scopes[i][0])) + u + " (" + counts[i] + ")");
        }

        List<String> content = new ArrayList<>();
        content.add(String.join(" " + sep + " ", filters) + "\n\n");
        for (String s : scopesToShow) {
            List<Map<String, Object>> list = docs.get(s);
            if (list.isEmpty()) {
                content.add(heading(capitalize(s), 2) + "\n`*No " + s + " work documents`*\n");
                content.add("\n");
                continue;
            }
            content.add(heading(capitalize(s), 2));
            content.add("\n");
            for (Map<String, Object> doc : list) {
                String fullTitle = (String) doc.get("title");
                String title = head(fullTitle, 92);
                if (title.length() < fullTitle.length()) title += "…";
                byte[] author = (byte[]) doc.get("author");
                double created = (Double) doc.get("created");
                long comments = (Long) doc.get("comments");
                content.add(link(icon("file") + " " + title, PATH_WORK_DOC, f("g", group, "r", repoName, "id", doc.get("id"), "scope", s))
                        + " " + CLR_DIM + "#" + doc.get("id") + "`f\n");
                content.add(CLR_DIM + (created != 0 ? localTime(created, DATE) : "") + " by "
                        + (author.length > 0 ? prettyHex(author) : "unknown") + "`f\n");
                if (comments > 0) content.add(CLR_DIM + comments + " updates`f\n");
                content.add("\n");
            }
        }
        if (content.get(content.size() - 1).equals("\n")) content.set(content.size() - 1, "");
        return render(String.join("", content), nav, "work", st);
    }

    private static double number(Object value) {
        return value instanceof Number ? ((Number) value).doubleValue() : 0;
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> meta(Map<Object, Object> document) {
        Object meta = document.get("meta");
        return meta instanceof Map ? (Map<Object, Object>) meta : Map.of();
    }

    /** The readable documents in one scope directory, newest first. */
    private List<Map<String, Object>> workDocuments(PageRequest request, String group, String repo, Path folder) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!Files.isDirectory(folder)) return out;
        try (Stream<Path> entries = Files.list(folder)) {
            for (Path docDir : (Iterable<Path>) entries::iterator) {
                String name = docDir.getFileName().toString();
                if (!Files.isDirectory(docDir) || !name.matches("[0-9]+")) continue;
                long docId = Long.parseLong(name);
                if (!canReadDocument(request, group, repo, docId)) continue;
                Map<Object, Object> doc = RngitWork.load(docDir.resolve("root"));
                if (doc == null) continue;
                Map<Object, Object> meta = meta(doc);
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", docId);
                entry.put("title", String.valueOf(meta.getOrDefault("title", "Untitled")));
                entry.put("created", number(meta.get("created")));
                entry.put("edited", number(meta.get("edited")));
                entry.put("author", meta.get("author") instanceof byte[] ? meta.get("author") : new byte[0]);
                entry.put("comments", (long) numberedFiles(docDir).size());
                out.add(entry);
            }
        } catch (Exception e) {
            log.debug("Could not list work documents in {}", folder, e);
        }
        out.sort(Comparator.comparingDouble((Map<String, Object> d) -> Math.max((Double) d.get("created"), (Double) d.get("edited"))).reversed());
        return out;
    }

    private static List<Path> numberedFiles(Path docDir) {
        try (Stream<Path> files = Files.list(docDir)) {
            List<Path> out = new ArrayList<>();
            files.filter(p -> p.getFileName().toString().matches("[0-9]+") && Files.isRegularFile(p)).forEach(out::add);
            out.sort(Comparator.comparingLong(p -> Long.parseLong(p.getFileName().toString())));
            return out;
        } catch (IOException e) {
            return List.of();
        }
    }

    /** The directory of a document in a scope; "all" searches active, completed, then proposed. */
    private static Path[] documentDir(Path workPath, long docId, String scope) {
        if (!scope.equals("all")) return new Path[]{workPath.resolve(scope).resolve(Long.toString(docId)), Path.of(scope)};
        for (String s : List.of("active", "completed")) {
            Path dir = workPath.resolve(s).resolve(Long.toString(docId));
            if (Files.isDirectory(dir)) return new Path[]{dir, Path.of(s)};
        }
        return new Path[]{workPath.resolve("proposed").resolve(Long.toString(docId)), Path.of("proposed")};
    }

    /** {@code serve_work_doc_page}. */
    Response workDocPage(PageRequest request) {
        long st = System.nanoTime();
        String group = request.var("g", "");
        String repoName = request.var("r", "");
        String id = request.var("id", "");
        String scope = request.var("scope", "all");
        if (!List.of("active", "completed", "proposed", "all").contains(scope)) scope = "active";
        if (group.isEmpty() || repoName.isEmpty() || id.isEmpty()) return render(heading("Error", 2) + "\nInvalid request\n", st);
        if (blockedAnonymous(request)) return noIdent(null, st);

        Long docId = RngitWork.parseDocId(id);
        if (docId == null) return render(heading("Error", 2) + "\nInvalid document ID\n", st);
        Repository repo = accessibleRepository(request, group, repoName);
        if (repo == null) return render(heading("Error", 2) + "\nThe requested repository was not found\n", st);
        if (!canReadDocument(request, group, repoName, docId)) {
            return render(heading("Error", 2) + "\nThe requested work document was not found\n", st);
        }

        Path[] found = documentDir(Path.of(repo.getPath() + ".work"), docId, scope);
        Path docDir = found[0];
        scope = found[1].toString();
        if (!Files.isRegularFile(docDir.resolve("root"))) {
            return render(heading("Not Found", 2) + "\nThe requested work document was not found\n", st);
        }
        Map<Object, Object> doc = RngitWork.load(docDir.resolve("root"));
        if (doc == null) return render(heading("Error", 2) + "\nCould not load work document\n", st);

        String dlLink = link("Download", FILE_WORKDOC, f("g", group, "r", repoName, "id", docId));
        String nav = ">>\n" + nodeLink() + " / " + groupLink(group) + " / " + repoLink(group, repoName) + " / "
                + link("work", PATH_WORK, f("g", group, "r", repoName)) + " / #" + docId + "\n" + "\n" + dlLink + "\n";

        Map<Object, Object> meta = meta(doc);
        String fullTitle = String.valueOf(meta.getOrDefault("title", "Untitled"));
        String title = head(fullTitle, 256);
        if (title.length() < fullTitle.length()) title += "…";
        byte[] author = meta.get("author") instanceof byte[] ? (byte[]) meta.get("author") : new byte[0];
        double created = number(meta.get("created"));
        double edited = number(meta.get("edited"));
        String format = String.valueOf(meta.getOrDefault("format", "markdown"));
        String text = doc.get("content") instanceof String ? (String) doc.get("content") : "";

        String signatureStr = "Document not signed";
        Object signature = meta.get("signature");
        Object pubkey = meta.get("identity");
        if (signature instanceof byte[] && ((byte[]) signature).length == 64) {
            if (pubkey instanceof byte[] && ((byte[]) pubkey).length == 64) {
                signatureStr = "Not valid";
                Identity signer = new Identity(false);
                if (signer.loadPublicKey((byte[]) pubkey) && signer.validate((byte[]) signature, text.getBytes(StandardCharsets.UTF_8))) {
                    signatureStr = "Valid";
                }
            }
        }

        StringBuilder content = new StringBuilder(heading(title, 2));
        content.append("\n").append(CLR_DIM).append("Author    : ").append(author.length > 0 ? prettyHex(author) : "Unknown").append("`f\n");
        content.append(CLR_DIM).append("Signature : ").append(signatureStr).append("`f\n");
        content.append(CLR_DIM).append("Created   : ").append(created != 0 ? localTime(created, DATE_MINUTES) : "unknown").append("`f\n");
        if (edited != 0 && edited != created) content.append(CLR_DIM).append("Edited    : ").append(localTime(edited, DATE_MINUTES)).append("`f\n");
        content.append(CLR_DIM).append("Status    : ").append(capitalize(scope)).append("`f\n\n");

        text = text.strip();
        if (!text.isEmpty()) {
            content.append(format.equals("micron") ? text : mdc.formatBlock(text)).append("\n");
        }

        List<Path> commentFiles = numberedFiles(docDir);
        List<String> rendered = new ArrayList<>();
        for (Path commentPath : commentFiles) {
            Map<Object, Object> comment = RngitWork.load(commentPath);
            if (comment == null) continue;
            Map<Object, Object> cmeta = meta(comment);
            // The reference reads a comment's format from the top level, where none is stored: always Markdown
            Object commentFormat = comment.getOrDefault("format", "markdown");
            String commentText = comment.get("content") instanceof String ? (String) comment.get("content") : "";
            String body = "markdown".equals(commentFormat) ? mdc.formatBlock(commentText) : commentText;
            byte[] cauthor = cmeta.get("author") instanceof byte[] ? (byte[]) cmeta.get("author") : new byte[0];
            double ccreated = number(cmeta.get("created"));
            rendered.add("\n" + CLR_DIM + "#" + commentPath.getFileName() + " by " + (cauthor.length > 0 ? prettyHex(cauthor) : "Unknown")
                    + " on " + (ccreated != 0 ? localTime(ccreated, DATE_MINUTES) : "unknown") + "`f\n" + body + "\n");
        }
        if (!rendered.isEmpty()) {
            content.append("\n").append(heading("Updates (" + rendered.size() + ")", 2));
            rendered.forEach(content::append);
        }
        return render(content.toString(), nav, "work_doc", st);
    }

    // ------------------------------------------------------------------
    // Files

    /** {@code serve_artifact}: a published release's artifact, as a file response carrying its name. */
    Response artifact(PageRequest request) {
        String group = request.var("g", "");
        String repoName = request.var("r", "");
        String tag = request.var("t", "");
        String artifact = unquotePlus(request.var("a", ""));
        if (RngitReleases.pathComponent(artifact) == null) return null;

        Repository repo = accessibleRepository(request, group, repoName);
        if (repo == null) return null;
        RngitReleases releases = new RngitReleases(repo.getPath());
        if (tag.equals("latest")) {
            RngitReleases.ListData list = releases.listData();
            if (list == null || list.releases.isEmpty()) return null;
            tag = resolveLatest(list);
        }
        if (tag == null || RngitReleases.pathComponent(tag) == null) return null;

        Path releaseDir = releases.getReleasesPath().resolve(tag);
        Map<String, Object> info = RngitReleases.releaseData(releaseDir, tag);
        if (info == null || !"published".equals(info.get("status"))) return null;
        Path artifactPath = releaseDir.resolve("artifacts").resolve(artifact);
        if (!Files.isRegularFile(artifactPath)) return null;
        return Response.ofFile(artifactPath.toFile(), Map.of("name", artifact.getBytes(StandardCharsets.UTF_8)));
    }

    /** {@code serve_media}: an image for a blob page, from {@code /media/<group>/<repo>/<ref>/<path>}. */
    Response media(PageRequest request) throws IOException {
        if (!request.data.containsKey("key") || !(request.data.get("path") instanceof String)) return null;
        String mediaPath = (String) request.data.get("path");
        if (mediaPath.startsWith(PATH_MEDIA)) mediaPath = mediaPath.substring(PATH_MEDIA.length());
        while (mediaPath.startsWith("/")) mediaPath = mediaPath.substring(1);
        String[] comps = mediaPath.split("/", -1);
        if (comps.length < 4) return null;

        String filePath = unquotePlus(String.join("/", List.of(comps).subList(3, comps.length)));
        Repository repo = accessibleRepository(request, comps[0], comps[1]);
        if (repo == null) return null;
        String resolved = RngitPagesGit.resolveRef(repo.getPath(), comps[2]);
        if (resolved == null || filePath.isEmpty()) return null;
        byte[] content = RngitPagesGit.blobBytes(repo.getPath(), resolved, filePath);
        return content == null ? null : fileResponse(request, content, baseName(filePath));
    }

    /** {@code serve_download}: a file at a ref, or a Markdown file converted to Micron with {@code fmt=mu}. */
    Response download(PageRequest request) throws IOException {
        String group = request.var("g", "");
        String repoName = request.var("r", "");
        String ref = request.var("ref", "HEAD");
        String fmt = request.var("fmt", "");
        String filePath = unquotePlus(request.var("path", ""));
        String fileName = baseName(filePath);
        if (!fmt.isEmpty() && !CONVERTABLE_EXTS.contains(fileExtension(filePath))) return null;

        Repository repo = accessibleRepository(request, group, repoName);
        if (repo == null) return null;
        String resolved = RngitPagesGit.resolveRef(repo.getPath(), ref);
        if (resolved == null || filePath.isEmpty()) return null;
        byte[] content = RngitPagesGit.blobBytes(repo.getPath(), resolved, filePath);
        if (content == null) return null;

        if (fmt.isEmpty()) return fileResponse(request, content, fileName);
        if (!fmt.equals("mu")) return null;
        String text = RngitPagesGit.utf8(content);
        if (text == null) return null;
        String mu = new RngitMicron(MAX_RENDER_WIDTH, blobScope(group, repoName, ref, filePath)).formatBlock(text).stripTrailing();
        if (mu.isEmpty()) return null;
        return fileResponse(request, mu.getBytes(StandardCharsets.UTF_8), stem(fileName) + ".mu");
    }

    /** {@code serve_wd_download}: a work document's content, named after its title. */
    Response workDocDownload(PageRequest request) throws IOException {
        String group = request.var("g", "");
        String repoName = request.var("r", "");
        String id = request.var("id", "");
        String scope = request.var("scope", "all");
        if (!List.of("active", "completed", "all").contains(scope)) scope = "active";
        if (group.isEmpty() || repoName.isEmpty() || id.isEmpty()) return null;

        Long docId = RngitWork.parseDocId(id);
        if (docId == null) return null;
        Repository repo = accessibleRepository(request, group, repoName);
        if (repo == null || !canReadDocument(request, group, repoName, docId)) return null;

        Path docDir = documentDir(Path.of(repo.getPath() + ".work"), docId, scope)[0];
        Map<Object, Object> doc = RngitWork.load(docDir.resolve("root"));
        if (doc == null) return null;
        Map<Object, Object> meta = meta(doc);
        String format = String.valueOf(meta.getOrDefault("format", "markdown"));
        String title = head(String.valueOf(meta.getOrDefault("title", "Untitled")), 256);
        String content = doc.get("content") instanceof String ? ((String) doc.get("content")).strip() : "";
        if (content.isEmpty()) return null;
        return fileResponse(request, content.getBytes(StandardCharsets.UTF_8), title + (format.equals("micron") ? ".mu" : ".md"));
    }
}
