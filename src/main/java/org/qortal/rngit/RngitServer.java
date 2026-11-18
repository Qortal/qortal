package org.qortal.rngit;

import io.reticulum.destination.Destination;
import io.reticulum.destination.DestinationType;
import io.reticulum.destination.Direction;
import io.reticulum.destination.Request;
import io.reticulum.destination.RequestPolicy;
import io.reticulum.destination.Response;
import io.reticulum.identity.Identity;
import io.reticulum.link.Link;
import io.reticulum.link.LinkStatus;
import io.reticulum.utils.MsgPackUtils;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.qortal.rngit.RngitPermissions.Permission;
import org.qortal.rngit.RngitRepositories.Group;
import org.qortal.rngit.RngitRepositories.Repository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

import static org.apache.commons.codec.binary.Hex.encodeHexString;
import static org.qortal.rngit.RngitProtocol.*;

/**
 * An rngit-compatible repository node on the {@code git.repositories}
 * destination ({@code server.py} {@code ReticulumGitNode}).
 * <p>
 * Implements the git operations ({@code /git/list}, {@code /git/fetch},
 * {@code /git/push}, {@code /git/delete}), {@code /git/create}, and forks,
 * mirrors and upstream sync ({@code /git/fork}, {@code /git/mirror},
 * {@code /git/sync}) with periodic mirror syncing, remote permission
 * management ({@code /mgmt/perms}), releases ({@code /mgmt/release}) and work
 * documents ({@code /mgmt/work}), and with {@code [pages] serve_nomadnet} the
 * Nomad Network page node ({@link RngitPages}) on the same identity. Responses
 * match the reference byte for byte, including its choice of "Not found" over
 * "Not allowed" where revealing a repository's existence would leak it.
 * <p>
 * Deliberate differences from the reference:
 * <ul>
 *   <li>A blocked identity is refused by every handler. The reference checks its
 *       blocklist only inside {@code resolve_permission}, so a blocked identity
 *       could still create repositories in a group granting {@code c:all}.</li>
 *   <li>Saving a repository's permissions requires admin on the repository, as
 *       its documentation says. The reference's set step checks group admin
 *       instead, while the step before it checks repository admin, so a
 *       repository's creator could read its rules but not save them.</li>
 * </ul>
 */
@Slf4j
public class RngitServer {

    static final long JOBS_INTERVAL_SECONDS = 5;
    static final long SYNC_CHECK_INTERVAL_MS = 15 * 60_000L;
    static final long DEFAULT_MIRROR_INTERVAL_MS = 24 * 3_600_000L;

    @Getter
    private final Path configDir;
    @Getter
    private Identity identity;
    /** Identifies this node to other rngit nodes when it fetches from an rns:// upstream. */
    private Identity clientIdentity;
    @Getter
    private Destination destination;
    @Getter
    private RngitRepositories repositories;
    private String nodeName = "Anonymous Git Node";
    private long announceIntervalMillis = 0;
    private long lastAnnounce = 0;
    private long mirrorIntervalMillis = DEFAULT_MIRROR_INTERVAL_MS;
    private long lastSyncCheck = System.currentTimeMillis();
    /** Held while the periodic mirror sync runs, so two never overlap. */
    private final ReentrantLock syncLock = new ReentrantLock();

    /** Identified links, by link id: the reference's {@code active_links}. */
    private final Map<String, Link> activeLinks = new ConcurrentHashMap<>();
    /** Fetch bundles still being transferred on a link; removed when the link goes. */
    private final Map<String, List<Path>> linkTempFiles = new ConcurrentHashMap<>();

    /** The Nomad Network page node, when {@code [pages] serve_nomadnet} is set; else null. */
    @Getter
    private RngitPages pages;
    /** Shown in the page footer; Core sets its build version. */
    @lombok.Setter
    private String version = "unknown";

    private ScheduledExecutorService jobs;
    /** Runs periodic mirror syncs, which can take long, off the jobs thread. */
    private ExecutorService syncs;

    public RngitServer(Path configDir) {
        this.configDir = configDir;
    }

    /**
     * Loads the config, identity and repository groups, and registers the
     * destination. Reticulum must already be running.
     */
    public void start() throws IOException {
        RngitConfig config = RngitConfig.loadOrCreate(configDir.resolve("config"));
        this.identity = loadOrCreateIdentity(configDir.resolve("repositories_identity"));
        this.clientIdentity = loadOrCreateIdentity(configDir.resolve("client_identity"));
        applyConfig(config);

        this.destination = new Destination(identity, Direction.IN, DestinationType.SINGLE, APP_NAME, ASPECT);
        this.destination.acceptsLinks(true);
        this.destination.setLinkEstablishedCallback(this::remoteConnected);
        registerRequestHandlers();

        if (config.getBool("pages", "serve_nomadnet", false)) {
            this.pages = new RngitPages(identity, nodeName, announceIntervalMillis, repositories, destination.getHash(),
                    configDir, config, version);
            this.pages.start();
        }

        this.jobs = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rngit-jobs");
            t.setDaemon(true);
            return t;
        });
        this.jobs.scheduleWithFixedDelay(this::runJobs, JOBS_INTERVAL_SECONDS, JOBS_INTERVAL_SECONDS, TimeUnit.SECONDS);
        this.syncs = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "rngit-mirror-sync");
            t.setDaemon(true);
            return t;
        });

        log.info("Reticulum Git Node \"{}\" listening on <{}>", nodeName, encodeHexString(destination.getHash()));
    }

    public void shutdown() {
        if (jobs != null) {
            jobs.shutdownNow();
        }
        if (syncs != null) {
            syncs.shutdownNow();
        }
        if (pages != null) {
            pages.shutdown();
        }
        RngitQdnGateway gateway = repositories == null ? null : repositories.getQdnGateway();
        if (gateway != null && gateway.getPublisher() != null) {
            gateway.getPublisher().shutdown();
        }
        for (String linkId : new ArrayList<>(linkTempFiles.keySet())) {
            cleanupLink(linkId);
        }
    }

    static Identity loadOrCreateIdentity(Path identityPath) throws IOException {
        if (Files.isReadable(identityPath)) {
            Identity identity = Identity.fromFile(identityPath);
            if (identity == null) {
                throw new IOException("Could not load repositories identity from " + identityPath);
            }
            log.info("Repositories identity loaded from {}", identityPath);
            return identity;
        }

        Identity identity = new Identity();
        Files.createDirectories(identityPath.getParent());
        identity.toFile(identityPath);
        log.info("Repositories identity generated and persisted to {}", identityPath);
        return identity;
    }

    /** {@code __apply_config}. */
    private void applyConfig(RngitConfig config) {
        Map<String, String> aliases = new HashMap<>();
        for (Map.Entry<String, String> entry : config.section("aliases").entrySet()) {
            String alias = entry.getKey();
            String hash = config.getString("aliases", alias, "");
            if (!RngitPermissions.isIdentityHashHex(hash)) {
                log.warn("Invalid identity hash for alias {} in rngit config, ignoring", alias);
            } else if (List.of("n", "none", "nobody", "a", "all", "everyone").contains(alias)) {
                log.warn("Invalid alias {} in rngit config, ignoring", alias);
            } else if (!aliases.containsKey(alias)) {
                aliases.put(alias, hash.toLowerCase(Locale.ROOT));
            }
        }

        this.nodeName = config.getString("rngit", "node_name", nodeName);
        this.announceIntervalMillis = config.getInt("rngit", "announce_interval", 0) * 60_000L;
        if (config.getString("rngit", "mirror_interval", null) != null) {
            this.mirrorIntervalMillis = Math.max(config.getInt("rngit", "mirror_interval", 24), 0) * 3_600_000L;
        }

        Set<String> blocked = new HashSet<>();
        for (String entry : config.getList("rngit", "blocked_identities")) {
            String hash = RngitPermissions.resolveAlias(entry, aliases);
            if (RngitPermissions.isIdentityHashHex(hash)) blocked.add(hash.toLowerCase(Locale.ROOT));
        }

        Map<String, List<String>> access = new HashMap<>();
        for (String groupName : config.section("access").keySet()) {
            access.put(groupName, config.getList("access", groupName));
        }

        this.repositories = new RngitRepositories(aliases, access, blocked);

        // Qortal names as groups, served from QDN. Needs Core's repository, so a
        // node run without one (the standalone live-test node) serves local groups only.
        if (config.getBool("qdn", "enabled", true)) {
            if (qdnAvailable()) {
                RngitQdnGateway gateway = new RngitQdnGateway(configDir.resolve("qdn-cache"));
                gateway.setStaging(new RngitQdnStaging(configDir.resolve("qdn-staging"), gateway));
                repositories.setQdnGateway(gateway);
                repositories.setIdentityBindings(new RngitIdentityBindings());
                log.info("rngit QDN gateway enabled: every registered name is a repository group");
                try {
                    RngitQdnPublisher publisher = RngitQdnPublisher.load(configDir, gateway);
                    if (publisher != null) {
                        publisher.setCompactAfter(config.getInt("qdn", "compact_after", RngitQdnPublisher.DEFAULT_COMPACT_AFTER));
                        gateway.setPublisher(publisher);
                        log.info("rngit QDN publishing enabled for names owned by {}", publisher.getAddress());
                    }
                } catch (Exception e) {
                    log.error("Could not load the rngit QDN publisher key; QDN repositories stay read-only here", e);
                }
            } else {
                log.info("rngit QDN gateway disabled: no Qortal repository available");
            }
        }

        for (String groupName : config.section("repositories").keySet()) {
            String raw = config.getString("repositories", groupName, "");
            Path groupPath = Path.of(raw.startsWith("~") ? System.getProperty("user.home") + raw.substring(1) : raw);
            if (!Files.isDirectory(groupPath)) {
                log.warn("The path \"{}\" specified for repository group \"{}\" does not exist, skipping.", groupPath, groupName);
            } else {
                repositories.loadGroup(groupName, groupPath);
            }
        }
    }

    private static boolean qdnAvailable() {
        try (org.qortal.repository.Repository repository = org.qortal.repository.RepositoryManager.getRepository()) {
            return repository != null;
        } catch (Exception e) {
            return false;
        }
    }

    private void registerRequestHandlers() {
        register(PATH_LIST, this::handleList);
        register(PATH_FETCH, this::handleFetch);
        register(PATH_PUSH, request -> qdnWrite(request, this::handlePush));
        register(PATH_DELETE, request -> qdnWrite(request, this::handleDelete));
        register(PATH_CREATE, this::handleCreate);
        register(PATH_FORK, request -> refuseOnQdn(request, null, r -> handleRemoteClone(r, "fork")));
        register(PATH_MIRROR, request -> refuseOnQdn(request, null, r -> handleRemoteClone(r, "mirror")));
        register(PATH_SYNC, request -> refuseOnQdn(request, null, this::handleSync));
        register(PATH_PERMS, this::handlePerms);
        register(PATH_RELEASE, request -> refuseOnQdn(request, List.of("list", "view", "fetch"), this::handleRelease));
        register(PATH_WORK, request -> refuseOnQdn(request, List.of("list", "view"), this::handleWork));
    }

    private void register(String path, Function<Request, Response> handler) {
        destination.registerRequestHandler(path, request -> {
            try {
                return handler.apply(request);
            } catch (Exception e) {
                log.error("Error while handling {} request", path, e);
                return result(RES_REMOTE_FAIL, "Remote error");
            }
        }, RequestPolicy.ALLOW_ALL, null, true);
    }

    // ------------------------------------------------------------------
    // QDN-backed repositories

    /** The group/repo a request names, or null. */
    private static String[] requestedPath(Request request) {
        Map<Object, Object> data = requestMap(request);
        return data == null ? null : parseRepositoryPath(intKey(data, IDX_REPOSITORY));
    }

    private boolean isQdn(String group) {
        return group != null && repositories.isQdnGroup(group);
    }

    static boolean isOk(Response response) {
        return response != null && !response.isFileResponse() && response.getData().length > 0
                && response.getData()[0] == RES_OK;
    }

    /**
     * A write to a QDN repository runs under the repository's lock, so a cache
     * refresh cannot interleave, and a successful one queues a publish.
     */
    private Response qdnWrite(Request request, Function<Request, Response> handler) {
        String[] path = requestedPath(request);
        if (path == null || path[1] == null || !isQdn(path[0])) return handler.apply(request);

        RngitQdnGateway gateway = repositories.getQdnGateway();
        if (!gateway.isWritableHere(path[0])) return stage(request, path[0], path[1]);

        Response response;
        synchronized (gateway.lock(path[0], path[1])) {
            response = handler.apply(request);
        }
        if (isOk(response) && gateway.getPublisher() != null) {
            gateway.getPublisher().schedule(path[0], path[1]);
        }
        return response;
    }

    /**
     * Push flow 2: on a node that cannot publish for the name, a permitted push
     * or ref delete is staged for the owner instead of applied. The pusher is
     * told so with a refusal, because the repository has not changed yet.
     */
    @SuppressWarnings("unchecked")
    private Response stage(Request request, String name, String repositoryName) {
        Map<Object, Object> data = requestMap(request);
        Response refused = precheck("Staged push", request, data);
        if (refused != null) return refused;

        String remote = remoteHash(request);
        boolean readAccess = repositories.resolvePermission(remote, name, repositoryName, Permission.READ);
        boolean writeAccess = repositories.resolvePermission(remote, name, repositoryName, Permission.WRITE);
        if (!writeAccess) return readAccess ? result(RES_DISALLOWED, "Not allowed") : result(RES_NOT_FOUND, "Not found");

        RngitQdnStaging.Change change = new RngitQdnStaging.Change();
        change.pusher = remote;
        byte[] bundle = null;

        Object bundleData = data.get("bundle");
        if (bundleData instanceof String) bundleData = ((String) bundleData).getBytes(StandardCharsets.UTF_8);
        Object operations = data.get("operations");
        if (data.containsKey("ref") && !data.containsKey("local_ref")) {
            change.kind = "delete";
            change.ref = data.get("ref") instanceof String ? RngitRefs.sanRef((String) data.get("ref")) : null;
            if (change.ref == null || !change.ref.startsWith("refs/")) return result(RES_INVALID_REQ, "Invalid request");
        } else if (bundleData instanceof byte[] && ((byte[]) bundleData).length > 0) {
            change.kind = "bundle";
            change.localRef = data.get("local_ref") instanceof String ? RngitRefs.sanRef((String) data.get("local_ref")) : null;
            change.ref = data.get("remote_ref") instanceof String ? RngitRefs.sanRef((String) data.get("remote_ref")) : null;
            change.force = Boolean.TRUE.equals(data.get("force"));
            if (change.localRef == null || change.ref == null) return result(RES_INVALID_REQ, "Missing ref specification");
            bundle = (byte[]) bundleData;
        } else if (operations instanceof List && ((List<Object>) operations).size() == 1
                && ((List<Object>) operations).get(0) instanceof Map) {
            Map<Object, Object> op = (Map<Object, Object>) ((List<Object>) operations).get(0);
            if (!"update_ref".equals(op.get("action"))) return result(RES_INVALID_REQ, "Unknown operation: " + op.get("action"));
            change.kind = "update_ref";
            change.ref = op.get("ref") instanceof String ? RngitRefs.sanRef((String) op.get("ref")) : null;
            change.sha = op.get("sha") instanceof String ? RngitRefs.sanSha((String) op.get("sha")) : null;
            change.force = Boolean.TRUE.equals(op.get("force"));
            if (change.ref == null || !change.ref.startsWith("refs/")) return result(RES_INVALID_REQ, "Invalid request");
            if (change.sha == null) return result(RES_INVALID_REQ, "Invalid SHA");
        } else {
            return result(RES_INVALID_REQ, "Invalid request data");
        }

        try {
            RngitQdnStaging.Change staged = repositories.getQdnGateway().getStaging().stage(name, repositoryName, change, bundle);
            return result(RES_DISALLOWED, "Staged as #" + staged.id + ", awaiting the owner's approval");
        } catch (RngitQdnStaging.ConflictException e) {
            return result(RES_DISALLOWED, e.getMessage());
        } catch (Exception e) {
            log.error("Could not stage a change to {}/{}", name, repositoryName, e);
            return result(RES_REMOTE_FAIL, "Could not stage the change");
        }
    }

    /**
     * Operations QDN repositories do not support yet: they would write to the
     * cache, which is rebuilt from QDN and would lose them.
     *
     * @param readOperations operations still allowed, or null to refuse all
     */
    private Response refuseOnQdn(Request request, List<String> readOperations, Function<Request, Response> handler) {
        String[] path = requestedPath(request);
        if (path != null && isQdn(path[0])) {
            Object operation = requestMap(request).get("operation");
            if (readOperations == null || !readOperations.contains(operation)) {
                return result(RES_DISALLOWED, "Not supported for QDN repositories yet");
            }
        }
        return handler.apply(request);
    }

    /** {@code /git/create} under a Qortal name: publishes a new descriptor. */
    private Response handleCreateQdn(Request request, String name, String repositoryName) {
        String remote = remoteHash(request);
        boolean readAccess = repositories.resolveGroupPermission(remote, name, Permission.READ);
        boolean createAccess = repositories.resolveGroupPermission(remote, name, Permission.CREATE);
        if (!createAccess) return readAccess ? result(RES_DISALLOWED, "Not allowed") : result(RES_NOT_FOUND, "Not found");
        if (!RngitQdn.isValidRepositoryName(repositoryName)) {
            return result(RES_INVALID_REQ, "Repository names on QDN are at most " + RngitQdn.MAX_REPOSITORY_NAME
                    + " characters, without ~");
        }
        if (RngitQdn.latestSignature(name, repositoryName) != null) return result(RES_DISALLOWED, "Repository already exists");

        RngitQdnPublisher publisher = repositories.getQdnGateway().getPublisher();
        if (publisher == null || !publisher.ownsName(name)) return result(RES_DISALLOWED, "Not allowed");
        try {
            log.info("Creating QDN repository {}/{} for {}", name, repositoryName, remote);
            publisher.create(name, repositoryName, remote);
            return ok();
        } catch (Exception e) {
            log.error("Could not publish new repository {}/{}", name, repositoryName, e);
            return result(RES_REMOTE_FAIL, "Could not publish repository: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Link lifecycle and periodic jobs

    private void remoteConnected(Link link) {
        log.debug("Peer connected to rngit destination");
        link.setRemoteIdentifiedCallback(this::remoteIdentified);
        link.setLinkClosedCallback(closed -> cleanupLink(encodeHexString(closed.getLinkId())));
    }

    private void remoteIdentified(Link link, Identity identity) {
        activeLinks.put(encodeHexString(link.getLinkId()), link);
        log.debug("rngit peer identified as {}", encodeHexString(identity.getHash()));
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

    private void runJobs() {
        try {
            long now = System.currentTimeMillis();
            if (announceIntervalMillis > 0 && now > lastAnnounce + announceIntervalMillis) {
                log.debug("Announcing repositories destination");
                destination.announce(nodeName.getBytes(StandardCharsets.UTF_8));
                lastAnnounce = now;
            }

            if (mirrorIntervalMillis > 0 && now > lastSyncCheck + SYNC_CHECK_INTERVAL_MS) {
                lastSyncCheck = now;
                syncs.submit(this::syncMirrors);
            }

            for (Map.Entry<String, Link> entry : new ArrayList<>(activeLinks.entrySet())) {
                if (entry.getValue().getStatus() != LinkStatus.ACTIVE) {
                    cleanupLink(entry.getKey());
                }
            }

            if (pages != null) pages.runJobs(now);
        } catch (Exception e) {
            log.error("Error while running rngit periodic jobs", e);
        }
    }

    // ------------------------------------------------------------------
    // Request helpers

    static Response result(byte code, String message) {
        byte[] text = message.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[1 + text.length];
        out[0] = code;
        System.arraycopy(text, 0, out, 1, text.length);
        return Response.of(out);
    }

    static Response ok() {
        return Response.of(new byte[]{RES_OK});
    }

    /** The request data as a map, or null when the requester sent anything else. */
    @SuppressWarnings("unchecked")
    static Map<Object, Object> requestMap(Request request) {
        Object data = request.getDataObject();
        return data instanceof Map ? (Map<Object, Object>) data : null;
    }

    /** A value under an integer key: msgpack ints decode as Long. */
    static Object intKey(Map<Object, Object> data, int key) {
        return data.get((long) key);
    }

    /** {@code parse_request_repository_path}: {@code group/repo}, or nulls. */
    static String[] parseRepositoryPath(Object path) {
        if (!(path instanceof String)) return new String[]{null, null};
        String[] components = ((String) path).split("/", -1);
        if (components.length != 2) return new String[]{null, null};
        if (components[0].length() > NAME_LIMIT || components[1].length() > NAME_LIMIT) return new String[]{null, null};
        return components;
    }

    private String remoteHash(Request request) {
        return encodeHexString(request.getRemoteIdentity().getHash());
    }

    /**
     * The checks every repository handler opens with. Returns a response to send
     * when the request must be refused, or null to carry on.
     */
    private Response precheck(String what, Request request, Map<Object, Object> data) {
        if (request.getRemoteIdentity() == null) return result(RES_DISALLOWED, "Not identified");
        String remote = remoteHash(request);
        if (repositories.isBlocked(remote)) {
            log.debug("Blocked: {} request from remote {}", what, remote);
            return result(RES_NOT_FOUND, "Not found");
        }
        log.debug("{} request from remote {}", what, remote);
        if (data == null) return result(RES_INVALID_REQ, "Invalid request");
        if (!data.containsKey((long) IDX_REPOSITORY)) return result(RES_INVALID_REQ, "No repository specified");
        return null;
    }

    // ------------------------------------------------------------------
    // Git operations

    /** {@code handle_list}. */
    Response handleList(Request request) {
        Map<Object, Object> data = requestMap(request);
        Response refused = precheck("List", request, data);
        if (refused != null) return refused;

        String[] path = parseRepositoryPath(intKey(data, IDX_REPOSITORY));
        String remote = remoteHash(request);
        boolean forPush = Boolean.TRUE.equals(data.get("for_push"));
        boolean readAccess = repositories.resolvePermission(remote, path[0], path[1], Permission.READ);
        boolean writeAccess = repositories.resolvePermission(remote, path[0], path[1], Permission.WRITE);
        boolean access = forPush ? writeAccess : readAccess;

        if (!access) return result(RES_NOT_FOUND, readAccess ? "Not allowed" : "Not found");

        Repository repository = repositories.getRepository(path[0], path[1]);
        try {
            log.debug("Listing refs for {}/{}", path[0], path[1]);
            return result(RES_OK, RngitGit.listRefs(repository.getPath()));
        } catch (Exception e) {
            log.error("Error while listing refs for {}/{}", path[0], path[1], e);
            return result(RES_REMOTE_FAIL, "Could not list refs");
        }
    }

    /** {@code handle_fetch}. */
    @SuppressWarnings("unchecked")
    Response handleFetch(Request request) {
        if (!activeLinks.containsKey(encodeHexString(request.getLinkId()))) {
            return result(RES_DISALLOWED, "Not identified");
        }
        Map<Object, Object> data = requestMap(request);
        Response refused = precheck("Fetch", request, data);
        if (refused != null) return refused;

        String[] path = parseRepositoryPath(intKey(data, IDX_REPOSITORY));
        if (!repositories.resolvePermission(remoteHash(request), path[0], path[1], Permission.READ)) {
            return result(RES_NOT_FOUND, "Not found");
        }
        Repository repository = repositories.getRepository(path[0], path[1]);

        Object refsValue = data.get("refs");
        if (!(refsValue instanceof List) || ((List<Object>) refsValue).isEmpty()) {
            return result(RES_INVALID_REQ, "No refs specified");
        }

        List<String> refNames = new ArrayList<>();
        List<String> haves = new ArrayList<>();
        for (Object entry : (List<Object>) refsValue) {
            if (!(entry instanceof Map)) return result(RES_INVALID_REQ, "Invalid request");
            Map<Object, Object> ref = (Map<Object, Object>) entry;
            Object name = ref.get("ref");
            if (!(name instanceof String) || RngitRefs.sanRef((String) name) == null) {
                return result(RES_INVALID_REQ, "Invalid request");
            }
            refNames.add((String) name);

            // Per-ref have: the client already has this ancestor
            Object have = ref.get("have");
            if (have instanceof String && !((String) have).isEmpty()) {
                if (RngitRefs.sanSha((String) have) == null) return result(RES_INVALID_REQ, "Invalid SHA");
                haves.add((String) have);
            }
        }

        // Global have list: objects the client already has, for thin bundles
        Object globalHaves = data.get("have");
        if (globalHaves instanceof List) {
            for (Object sha : (List<Object>) globalHaves) {
                if (!(sha instanceof String) || RngitRefs.sanSha((String) sha) == null) {
                    return result(RES_INVALID_REQ, "Invalid SHA");
                }
                haves.add((String) sha);
            }
        }

        try {
            log.debug("Fetching refs {} for {}/{}", refNames, path[0], path[1]);
            Path bundle = Files.createTempFile("rngit-fetch-", ".bundle");
            if (!RngitGit.createBundle(repository.getPath(), refNames, haves, bundle)) {
                Files.deleteIfExists(bundle);
                log.debug("Empty bundle for {}, all objects already on client", refNames);
                return ok();
            }
            linkTempFiles.computeIfAbsent(encodeHexString(request.getLinkId()), k -> new ArrayList<>()).add(bundle);
            return Response.ofFile(bundle.toFile(), Map.of(IDX_RESULT_CODE, (int) RES_OK));
        } catch (Exception e) {
            log.error("Error while fetching refs {} for {}/{}", refNames, path[0], path[1], e);
            return result(RES_REMOTE_FAIL, "Could not fetch refs");
        }
    }

    /** {@code handle_push}: a bundle with one ref update, or a list of direct ref updates. */
    @SuppressWarnings("unchecked")
    Response handlePush(Request request) {
        Map<Object, Object> data = requestMap(request);
        Response refused = precheck("Push", request, data);
        if (refused != null) return refused;

        String[] path = parseRepositoryPath(intKey(data, IDX_REPOSITORY));
        String remote = remoteHash(request);
        boolean readAccess = repositories.resolvePermission(remote, path[0], path[1], Permission.READ);
        boolean writeAccess = repositories.resolvePermission(remote, path[0], path[1], Permission.WRITE);
        if (!writeAccess) {
            return readAccess ? result(RES_DISALLOWED, "Not allowed") : result(RES_NOT_FOUND, "Not found");
        }
        Path repositoryPath = repositories.getRepository(path[0], path[1]).getPath();

        String localRef = data.get("local_ref") instanceof String ? RngitRefs.sanRef((String) data.get("local_ref")) : null;
        String remoteRef = data.get("remote_ref") instanceof String ? RngitRefs.sanRef((String) data.get("remote_ref")) : null;
        boolean force = Boolean.TRUE.equals(data.get("force"));
        Object bundleData = data.get("bundle");
        Object operations = data.get("operations");

        if (bundleData instanceof String) {
            bundleData = ((String) bundleData).getBytes(StandardCharsets.UTF_8);
        }

        if (bundleData instanceof byte[] && ((byte[]) bundleData).length > 0) {
            if (localRef == null || remoteRef == null) return result(RES_INVALID_REQ, "Missing ref specification");
            Path bundle = null;
            try {
                log.debug("Push {}:{} to {}/{}", localRef, remoteRef, path[0], path[1]);
                bundle = Files.createTempFile("rngit-push-", ".bundle");
                Files.write(bundle, (byte[]) bundleData);
                RngitGit.Result applied = RngitGit.applyBundle(repositoryPath, bundle, localRef, remoteRef, force);
                if (!applied.ok) {
                    log.error("Bundle push {}:{} to {}/{} failed: {}", localRef, remoteRef, path[0], path[1], applied.message);
                    return result(RES_REMOTE_FAIL, "Could not verify bundle");
                }
                return ok();
            } catch (Exception e) {
                log.error("Error while handling push request for {}/{}", path[0], path[1], e);
                return result(RES_REMOTE_FAIL, "Remote error");
            } finally {
                if (bundle != null) {
                    try {
                        Files.deleteIfExists(bundle);
                    } catch (IOException e) {
                        log.error("Could not remove push bundle {}", bundle, e);
                    }
                }
            }
        }

        // An empty list is falsy in the reference and falls through to "Invalid request data"
        if (operations != null && !(operations instanceof List && ((List<Object>) operations).isEmpty())) {
            if (!(operations instanceof List)) return result(RES_INVALID_REQ, "Invalid data for operations");
            try {
                for (Object entry : (List<Object>) operations) {
                    if (!(entry instanceof Map)) return result(RES_INVALID_REQ, "Invalid request");
                    Map<Object, Object> op = (Map<Object, Object>) entry;
                    Object action = op.getOrDefault("action", "");
                    String ref = op.get("ref") instanceof String ? RngitRefs.sanRef((String) op.get("ref")) : null;
                    String sha = op.get("sha") instanceof String ? RngitRefs.sanSha((String) op.get("sha")) : null;
                    boolean opForce = Boolean.TRUE.equals(op.get("force"));

                    if (!"update_ref".equals(action)) return result(RES_INVALID_REQ, "Unknown operation: " + action);
                    if (ref == null || !ref.startsWith("refs/")) return result(RES_INVALID_REQ, "Invalid request");
                    if (sha == null) return result(RES_INVALID_REQ, "Invalid SHA");

                    if (!RngitGit.hasObject(repositoryPath, sha)) {
                        return result(RES_REMOTE_FAIL, "Object " + sha + " does not exist in repository");
                    }

                    // An existing ref at a different SHA needs force
                    String existing = RngitGit.resolveRef(repositoryPath, ref);
                    if (existing != null && !existing.equals(sha) && !opForce) {
                        return result(RES_DISALLOWED, "Ref " + ref + " already exists at different SHA (force required)");
                    }

                    log.debug("Updating ref {} to {} in {}/{}", ref, sha, path[0], path[1]);
                    RngitGit.Result updated = RngitGit.updateRef(repositoryPath, ref, sha);
                    if (!updated.ok) {
                        log.error("Error while updating ref {} to {} for {}/{}: {}", ref, sha, path[0], path[1], updated.message);
                        return result(RES_REMOTE_FAIL, "Could not update refs");
                    }
                }
                return ok();
            } catch (Exception e) {
                log.error("Error while handling push operations for {}/{}", path[0], path[1], e);
                return result(RES_REMOTE_FAIL, "Remote error");
            }
        }

        return result(RES_INVALID_REQ, "Invalid request data");
    }

    /** {@code handle_delete}. */
    Response handleDelete(Request request) {
        Map<Object, Object> data = requestMap(request);
        Response refused = precheck("Delete", request, data);
        if (refused != null) return refused;

        String[] path = parseRepositoryPath(intKey(data, IDX_REPOSITORY));
        String remote = remoteHash(request);
        boolean readAccess = repositories.resolvePermission(remote, path[0], path[1], Permission.READ);
        boolean writeAccess = repositories.resolvePermission(remote, path[0], path[1], Permission.WRITE);
        if (!writeAccess) {
            return readAccess ? result(RES_DISALLOWED, "Not allowed") : result(RES_NOT_FOUND, "Not found");
        }

        String ref = data.get("ref") instanceof String ? RngitRefs.sanRef((String) data.get("ref")) : null;
        if (ref == null || !ref.startsWith("refs/")) return result(RES_INVALID_REQ, "Invalid request");

        log.debug("Deleting ref {} in {}/{}", ref, path[0], path[1]);
        RngitGit.Result deleted = RngitGit.deleteRef(repositories.getRepository(path[0], path[1]).getPath(), ref);
        if (!deleted.ok) {
            log.error("Error while deleting ref {} for {}/{}: {}", ref, path[0], path[1], deleted.message);
            return result(RES_REMOTE_FAIL, "Could not delete ref");
        }
        return ok();
    }

    // ------------------------------------------------------------------
    // Repository management

    /** {@code handle_create}: a new bare repository, its creator made admin. */
    Response handleCreate(Request request) {
        Map<Object, Object> data = requestMap(request);
        Response refused = precheck("Create", request, data);
        if (refused != null) return refused;

        String[] path = parseRepositoryPath(intKey(data, IDX_REPOSITORY));
        if (path[0] == null || path[1] == null) return result(RES_INVALID_REQ, "Invalid request");
        Group group = repositories.getGroup(path[0]);
        if (group == null) return result(RES_NOT_FOUND, "Not found");
        if (isQdn(path[0])) return handleCreateQdn(request, path[0], path[1]);

        String remote = remoteHash(request);
        boolean readAccess = repositories.resolveGroupPermission(remote, path[0], Permission.READ);
        boolean createAccess = repositories.resolveGroupPermission(remote, path[0], Permission.CREATE);
        if (!Files.exists(group.getPath())) return result(RES_NOT_FOUND, "Not found");
        if (!createAccess) {
            return readAccess ? result(RES_DISALLOWED, "Not allowed") : result(RES_NOT_FOUND, "Not found");
        }

        Path repositoryPath = group.getPath().resolve(path[1]).normalize();
        if (!repositoryPath.getParent().equals(group.getPath().normalize())) {
            return result(RES_INVALID_REQ, "Invalid request");
        }

        if (repositories.getRepository(path[0], path[1]) != null || Files.exists(repositoryPath)) {
            boolean existingRead = repositories.resolvePermission(remote, path[0], path[1], Permission.READ);
            return existingRead ? result(RES_DISALLOWED, "Repository already exists") : result(RES_NOT_FOUND, "Not found");
        }

        try {
            log.info("Creating repository {}/{} for {}", path[0], path[1], remote);
            Files.createDirectories(repositoryPath);
            RngitGit.initBare(repositoryPath);

            writeCreatorPermissions(repositoryPath, remote);

            if (!repositories.loadRepository(group, repositoryPath)) {
                log.error("Repository {} created, but runtime loading failed", repositoryPath);
                deleteRecursively(repositoryPath);
                return result(RES_REMOTE_FAIL, "Failed to register repository");
            }
            return ok();
        } catch (Exception e) {
            log.error("Error while creating repository {}/{} for {}", path[0], path[1], remote, e);
            deleteRecursively(repositoryPath);
            return result(RES_REMOTE_FAIL, "Could not initialize repository");
        }
    }

    // ------------------------------------------------------------------
    // Forks, mirrors and upstream sync

    /** {@code _handle_remote_clone}: a new repository fetched from an upstream URL. */
    Response handleRemoteClone(Request request, String repoType) {
        if (!activeLinks.containsKey(encodeHexString(request.getLinkId()))) {
            return result(RES_DISALLOWED, "Not identified");
        }
        Map<Object, Object> data = requestMap(request);
        Response refused = precheck(repoType.equals("fork") ? "Fork" : "Mirror", request, data);
        if (refused != null) return refused;

        Object source = data.get("source");
        if (source == null || "".equals(source)) return result(RES_INVALID_REQ, "No source specified");
        if (!(source instanceof String)) return result(RES_INVALID_REQ, "Invalid source URL");
        String sourceUrl = (String) source;
        if (!RngitUpstream.isAllowedSource(sourceUrl)) return result(RES_DISALLOWED, "Prohibited source URL");

        String[] path = parseRepositoryPath(intKey(data, IDX_REPOSITORY));
        if (path[0] == null || path[1] == null) return result(RES_INVALID_REQ, "Invalid request");
        Group group = repositories.getGroup(path[0]);
        if (group == null) return result(RES_NOT_FOUND, "Not found");

        String remote = remoteHash(request);
        boolean readAccess = repositories.resolveGroupPermission(remote, path[0], Permission.READ);
        boolean createAccess = repositories.resolveGroupPermission(remote, path[0], Permission.CREATE);
        if (!Files.exists(group.getPath())) return result(RES_NOT_FOUND, "Not found");
        if (!createAccess) {
            return readAccess ? result(RES_DISALLOWED, "Not allowed") : result(RES_NOT_FOUND, "Not found");
        }

        Path finalPath = group.getPath().resolve(path[1]).normalize();
        if (!finalPath.getParent().equals(group.getPath().normalize())) return result(RES_INVALID_REQ, "Invalid request");
        if (repositories.getRepository(path[0], path[1]) != null || Files.exists(finalPath)) {
            boolean existingRead = repositories.resolvePermission(remote, path[0], path[1], Permission.READ);
            return existingRead ? result(RES_DISALLOWED, "Repository already exists") : result(RES_NOT_FOUND, "Not found");
        }

        Path tmp = null;
        try {
            log.info("{} {} to {}/{} for {}", repoType.equals("fork") ? "Forking" : "Mirroring", sourceUrl, path[0], path[1], remote);
            tmp = Files.createTempDirectory("rngit-clone-");
            Path tempRepository = tmp.resolve(path[1]);
            Files.createDirectories(tempRepository);
            try {
                RngitGit.initBare(tempRepository);
            } catch (Exception e) {
                log.error("Failed to initialize bare repository at {}", tempRepository, e);
                return result(RES_REMOTE_FAIL, "Failed to initialize repository");
            }

            RngitUpstream.Fetched fetched = RngitUpstream.fetch(tempRepository, sourceUrl, clientIdentity);
            if (!fetched.ok) {
                log.error("Failed to fetch from {}: {}", sourceUrl, fetched.error);
                return result(RES_REMOTE_FAIL, "Failed to fetch from source: " + fetched.error);
            }
            if (!RngitGit.updateHead(tempRepository, fetched.headBranch)) {
                log.error("Failed to update HEAD for repository cloned from {}", sourceUrl);
            }

            try {
                RngitGit.setUpstream(tempRepository, repoType, sourceUrl);
            } catch (IOException e) {
                return result(RES_REMOTE_FAIL, "Failed to configure repository type: " + e.getMessage());
            }
            if (!RngitGit.setUpstreamSynced(tempRepository)) {
                return result(RES_REMOTE_FAIL, "Failed to configure repository type: could not record sync time");
            }

            try {
                writeCreatorPermissions(finalPath, remote);
            } catch (IOException e) {
                log.error("Could not set default repository permissions for {}/{}", path[0], path[1], e);
                return result(RES_REMOTE_FAIL, "Could not initialize repository");
            }

            try {
                moveDirectory(tempRepository, finalPath);
            } catch (IOException e) {
                log.warn("Failed to deploy fetched repository to group directory", e);
                return result(RES_REMOTE_FAIL, "Could not write repository");
            }

            if (!repositories.loadRepository(group, finalPath)) {
                log.error("Repository {} created, but runtime loading failed", finalPath);
                deleteRecursively(finalPath);
                return result(RES_REMOTE_FAIL, "Failed to register repository");
            }
            log.info("Repository {}/{} {}ed successfully from {}", path[0], path[1], repoType, sourceUrl);
            return ok();
        } catch (Exception e) {
            log.error("Error while {}ing repository {}/{}", repoType, path[0], path[1], e);
            deleteRecursively(finalPath);
            return result(RES_REMOTE_FAIL, "Remote error");
        } finally {
            if (tmp != null) deleteRecursively(tmp);
        }
    }

    /** {@code handle_sync}: fetch a fork's or mirror's upstream now. */
    Response handleSync(Request request) {
        Map<Object, Object> data = requestMap(request);
        Response refused = precheck("Upstream sync", request, data);
        if (refused != null) return refused;

        String[] path = parseRepositoryPath(intKey(data, IDX_REPOSITORY));
        String remote = remoteHash(request);
        if (!repositories.resolvePermission(remote, path[0], path[1], Permission.READ)) return result(RES_NOT_FOUND, "Not found");
        if (!repositories.resolvePermission(remote, path[0], path[1], Permission.WRITE)) return result(RES_DISALLOWED, "Not allowed");

        Repository repository = repositories.getRepository(path[0], path[1]);
        if (repository.getMirrorSource() != null) {
            return syncUpstream(repository, true) ? ok() : result(RES_REMOTE_FAIL, "Mirror sync failed");
        } else if (repository.getForkSource() != null) {
            return syncUpstream(repository, false) ? ok() : result(RES_REMOTE_FAIL, "Fork sync failed");
        }
        return result(RES_INVALID_REQ, "Repository is neither fork nor mirror");
    }

    /**
     * {@code __sync_mirror} / {@code __sync_fork}. A mirror follows the
     * upstream's HEAD; a fork keeps the HEAD its maintainer chose.
     */
    boolean syncUpstream(Repository repository, boolean mirror) {
        String source = mirror ? repository.getMirrorSource() : repository.getForkSource();
        String label = repository.getGroup() + "/" + repository.getName();
        log.info("Syncing {} {} from {}", mirror ? "mirror" : "fork", label, source);

        synchronized (repository) {
            RngitUpstream.Fetched fetched = RngitUpstream.fetch(repository.getPath(), source, clientIdentity);
            if (!fetched.ok) {
                log.error("Failed to sync {} from {}: {}", label, source, fetched.error);
                return false;
            }
            if (mirror && !RngitGit.updateHead(repository.getPath(), fetched.headBranch)) {
                log.warn("Failed to update HEAD while syncing mirror {}", label);
            }
            if (!RngitGit.setUpstreamSynced(repository.getPath())) {
                log.warn("Synced {} but could not update its sync timestamp", label);
            }
        }
        log.info("{} synced successfully from {}", label, source);
        return true;
    }

    /** {@code __sync_mirrors}: every mirror whose last sync is older than the mirror interval. */
    void syncMirrors() {
        if (!syncLock.tryLock()) return;
        try {
            long nowSeconds = System.currentTimeMillis() / 1000;
            for (Group group : repositories.getGroups().values()) {
                for (Repository repository : group.getRepositories().values()) {
                    if (repository.getMirrorSource() != null
                            && nowSeconds > RngitGit.upstreamSynced(repository.getPath()) + mirrorIntervalMillis / 1000) {
                        syncUpstream(repository, true);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Could not sync mirrors", e);
        } finally {
            syncLock.unlock();
        }
    }

    // ------------------------------------------------------------------
    // Releases

    /**
     * {@code handle_release}: reading needs {@code read}; creating, deleting and
     * choosing the latest release need {@code release} as well.
     */
    Response handleRelease(Request request) {
        Map<Object, Object> data = requestMap(request);
        Response refused = precheck("Release", request, data);
        if (refused != null) return refused;

        Object operation = data.get("operation");
        if (operation == null || "".equals(operation)) return result(RES_INVALID_REQ, "Invalid request");

        String[] path = parseRepositoryPath(intKey(data, IDX_REPOSITORY));
        String remote = remoteHash(request);
        boolean readAccess = repositories.resolvePermission(remote, path[0], path[1], Permission.READ);
        boolean releaseAccess = repositories.resolvePermission(remote, path[0], path[1], Permission.RELEASE);
        if (!readAccess) return result(RES_NOT_FOUND, "Not found");

        boolean writing = List.of("create", "delete", "latest").contains(operation);
        boolean reading = List.of("list", "view", "fetch").contains(operation);
        if (!(reading || writing && releaseAccess)) return result(RES_DISALLOWED, "Not allowed");

        RngitReleases releases = new RngitReleases(repositories.getRepository(path[0], path[1]).getPath());
        switch ((String) operation) {
            case "list": return releases.list();
            case "view": return releases.view(data);
            case "fetch": return releases.fetch(data);
            case "create": return releases.create(data, remote);
            case "delete": return releases.delete(data);
            case "latest": return releases.latest(data);
            default: return result(RES_INVALID_REQ, "Invalid request");
        }
    }

    // ------------------------------------------------------------------
    // Work documents

    /**
     * {@code handle_work}: repository permissions, refined per document where
     * the operation names one, decide access:
     * <ul>
     *   <li>list, view: read (a document's own rules or repository admin)</li>
     *   <li>comment: interact plus read or write</li>
     *   <li>propose: propose</li>
     *   <li>create, edit, delete, complete, activate: interact and write</li>
     *   <li>perms: admin</li>
     * </ul>
     */
    Response handleWork(Request request) {
        Map<Object, Object> data = requestMap(request);
        Response refused = precheck("Work", request, data);
        if (refused != null) return refused;

        Object operation = data.get("operation");
        if (!RngitWork.truthy(operation)) return result(RES_INVALID_REQ, "Invalid request");

        String[] path = parseRepositoryPath(intKey(data, IDX_REPOSITORY));
        String remote = remoteHash(request);
        boolean read = repositories.resolvePermission(remote, path[0], path[1], Permission.READ);
        boolean write = repositories.resolvePermission(remote, path[0], path[1], Permission.WRITE);
        boolean interact = repositories.resolvePermission(remote, path[0], path[1], Permission.INTERACT);
        boolean propose = repositories.resolvePermission(remote, path[0], path[1], Permission.PROPOSE);
        boolean admin = repositories.resolvePermission(remote, path[0], path[1], Permission.ADMIN);
        if (!read) return result(RES_NOT_FOUND, "Not found");

        // A named document's own rules refine read, interact and write
        Object docIdValue = data.get("doc_id");
        if (RngitWork.truthy(docIdValue) && List.of("read", "view", "comment", "edit", "delete", "perms").contains(operation)) {
            Long docId = RngitWork.parseDocId(docIdValue);
            if (docId == null) return result(RES_INVALID_REQ, "Invalid request");
            read = repositories.resolveDocumentPermission(remote, path[0], path[1], docId, Permission.READ) || admin;
            if (!read) return result(RES_NOT_FOUND, "Document not found");
            if (List.of("comment", "edit").contains(operation)) {
                interact |= repositories.resolveDocumentPermission(remote, path[0], path[1], docId, Permission.INTERACT);
            }
            if ("edit".equals(operation)) {
                write |= repositories.resolveDocumentPermission(remote, path[0], path[1], docId, Permission.WRITE);
            }
        }

        boolean comment = interact && (read || write);
        boolean manage = interact && write;
        boolean access;
        switch (String.valueOf(operation)) {
            case "list": case "view": access = read; break;
            case "comment": access = comment; break;
            case "propose": access = propose; break;
            case "create": case "edit": case "delete": case "complete": case "activate": access = manage; break;
            case "perms": access = admin; break;
            default: access = false;
        }
        if (!access) return result(RES_DISALLOWED, "Not allowed");

        RngitWork work = new RngitWork(repositories.getRepository(path[0], path[1]).getPath(), repositories, path[0], path[1]);
        Identity identity = request.getRemoteIdentity();
        switch ((String) operation) {
            case "list": return work.list(data, remote);
            case "view": return work.view(data);
            case "comment": return work.comment(data, identity);
            case "create": return work.create(data, identity, false);
            case "propose": return work.create(data, identity, true);
            case "edit": return work.edit(data, identity);
            case "delete": return work.delete(data, identity);
            case "complete": return work.move(data, identity, true);
            case "activate": return work.move(data, identity, false);
            case "perms": return work.perms(data, identity);
            default: return result(RES_INVALID_REQ, "Invalid request");
        }
    }

    // ------------------------------------------------------------------
    // Remote permission management

    /** Serialises permission file writes and reloads ({@code perms_lock}). */
    private final Object permsLock = new Object();

    /** {@code handle_perms}: get or set a group's or repository's {@code .allowed} file. */
    Response handlePerms(Request request) {
        if (request.getRemoteIdentity() == null) return result(RES_DISALLOWED, "Not identified");
        String remote = remoteHash(request);
        if (repositories.isBlocked(remote)) return result(RES_NOT_FOUND, "Not found");
        Map<Object, Object> data = requestMap(request);
        if (data == null) return result(RES_INVALID_REQ, "Invalid request");
        log.debug("Permissions request from remote {}", remote);

        Object operation = data.get("operation");
        if ("gperms".equals(operation)) {
            if (!data.containsKey((long) IDX_GROUP)) return result(RES_INVALID_REQ, "No group specified");
            String groupName = parseGroupPath(intKey(data, IDX_GROUP));
            if (!repositories.resolveGroupPermission(remote, groupName, Permission.READ)) return result(RES_NOT_FOUND, "Not found");
            if (!repositories.resolveGroupPermission(remote, groupName, Permission.ADMIN)) return result(RES_DISALLOWED, "Not allowed");
            if (isQdn(groupName)) return result(RES_DISALLOWED, "Rules for a Qortal name's group are set in the node's config");

            Group group = repositories.getGroup(groupName);
            Path allowed = Path.of(group.getPath() + ".allowed");
            return permissionsStep(data, allowed, () -> repositories.updateGroupPermissions(group), "group " + groupName, remote);
        }
        if ("rperms".equals(operation)) {
            if (!data.containsKey((long) IDX_REPOSITORY)) return result(RES_INVALID_REQ, "No repository specified");
            String[] path = parseRepositoryPath(intKey(data, IDX_REPOSITORY));
            boolean readAccess = repositories.resolvePermission(remote, path[0], path[1], Permission.READ);
            boolean adminAccess = repositories.resolvePermission(remote, path[0], path[1], Permission.ADMIN);
            if (!adminAccess) return readAccess ? result(RES_DISALLOWED, "Not allowed") : result(RES_NOT_FOUND, "Not found");

            if (isQdn(path[0])) return qdnPermissionsStep(data, path[0], path[1], remote);

            Repository repository = repositories.getRepository(path[0], path[1]);
            Path allowed = Path.of(repository.getPath() + ".allowed");
            return permissionsStep(data, allowed, () -> repositories.updateRepositoryPermissions(repository),
                    "repository " + path[0] + "/" + path[1], remote);
        }
        return result(RES_INVALID_REQ, "Invalid request");
    }

    /** The get and set steps for a QDN repository, whose rules live in its descriptor. */
    private Response qdnPermissionsStep(Map<Object, Object> data, String name, String repositoryName, String remote) {
        RngitQdnGateway gateway = repositories.getQdnGateway();
        Object step = data.get("step");
        if (step == null || "".equals(step)) return result(RES_INVALID_REQ, "Invalid request");

        if ("get".equals(step)) {
            RngitQdn.Descriptor descriptor = gateway.descriptor(name, repositoryName);
            if (descriptor == null) return result(RES_REMOTE_FAIL, "Error getting permissions");
            return RngitReleases.packed(Map.of("content", descriptor.allowed == null ? "" : descriptor.allowed));
        }
        if ("set".equals(step)) {
            Object contentValue = data.getOrDefault("content", "");
            if (!(contentValue instanceof String)) return result(RES_INVALID_REQ, "Invalid request");
            String invalid = repositories.validateAllowedContent((String) contentValue);
            if (invalid != null) return result(RES_INVALID_REQ, invalid);
            if (!gateway.isWritableHere(name)) return result(RES_DISALLOWED, "Rules are published by the node holding the name owner's key");
            try {
                gateway.getPublisher().setAllowed(name, repositoryName, (String) contentValue);
                log.info("Permissions for QDN repository {}/{} updated by {}", name, repositoryName, remote);
                return ok();
            } catch (Exception e) {
                log.error("Could not publish permissions for {}/{}", name, repositoryName, e);
                return result(RES_REMOTE_FAIL, "Error setting permissions");
            }
        }
        return result(RES_INVALID_REQ, "Invalid step");
    }

    /** The get and set steps, shared by group and repository permissions. */
    private Response permissionsStep(Map<Object, Object> data, Path allowedPath, Runnable reload, String what, String remote) {
        Object step = data.get("step");
        if (step == null || "".equals(step)) return result(RES_INVALID_REQ, "Invalid request");

        if ("get".equals(step)) {
            try {
                String content = Files.isRegularFile(allowedPath) ? Files.readString(allowedPath, StandardCharsets.UTF_8) : "";
                byte[] packed = MsgPackUtils.packObject(Map.of("content", content));
                byte[] out = new byte[1 + packed.length];
                out[0] = RES_OK;
                System.arraycopy(packed, 0, out, 1, packed.length);
                return Response.of(out);
            } catch (Exception e) {
                log.error("Error getting permissions for {}", what, e);
                return result(RES_REMOTE_FAIL, "Error getting permissions");
            }
        }

        if ("set".equals(step)) {
            Object contentValue = data.getOrDefault("content", "");
            if (!(contentValue instanceof String)) return result(RES_INVALID_REQ, "Invalid request");
            String content = (String) contentValue;
            String invalid = repositories.validateAllowedContent(content);
            if (invalid != null) return result(RES_INVALID_REQ, invalid);

            try {
                if (Files.isExecutable(allowedPath)) {
                    return result(RES_DISALLOWED, "Executable permission resolvers can only be modified node-side");
                }
                synchronized (permsLock) {
                    Path tmp = Path.of(allowedPath + ".tmp");
                    Files.writeString(tmp, content, StandardCharsets.UTF_8);
                    Files.move(tmp, allowedPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    try {
                        reload.run();
                    } catch (Exception e) {
                        log.error("Error while refreshing permissions for {}", what, e);
                    }
                }
                log.info("Permissions for {} updated by {}", what, remote);
                return ok();
            } catch (Exception e) {
                log.error("Error setting permissions for {}", what, e);
                return result(RES_REMOTE_FAIL, "Error setting permissions");
            }
        }

        return result(RES_INVALID_REQ, "Invalid step");
    }

    /** {@code parse_request_group_path}: a single path component, or null. */
    static String parseGroupPath(Object path) {
        if (!(path instanceof String)) return null;
        String group = (String) path;
        if (group.contains("/") || group.length() > NAME_LIMIT) return null;
        return group;
    }

    private static void writeCreatorPermissions(Path repositoryPath, String creatorHashHex) throws IOException {
        Path allowed = Path.of(repositoryPath + ".allowed");
        Path tmpAllowed = Path.of(allowed + ".tmp");
        Files.writeString(tmpAllowed, REPO_CREATE_PERMS_TEMPLATE.replace("{IDENTITY_HASH}", creatorHashHex), StandardCharsets.UTF_8);
        Files.move(tmpAllowed, allowed, StandardCopyOption.ATOMIC_MOVE);
    }

    /** {@code shutil.move}: a rename, or a copy and delete across file systems. */
    private static void moveDirectory(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            return;
        } catch (IOException e) {
            log.debug("Rename of {} to {} failed, copying instead: {}", source, target, e.getMessage());
        }
        try (var walk = Files.walk(source)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                Path dest = target.resolve(source.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(dest);
                else Files.copy(p, dest, StandardCopyOption.COPY_ATTRIBUTES);
            }
        } catch (IOException e) {
            deleteRecursively(target);
            throw e;
        }
        deleteRecursively(source);
    }

    private static void deleteRecursively(Path path) {
        if (!Files.exists(path)) return;
        try (var walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    log.error("Could not clean up {}", p, e);
                }
            });
        } catch (IOException e) {
            log.error("Could not clean up failed repository creation at {}", path, e);
        }
    }
}
