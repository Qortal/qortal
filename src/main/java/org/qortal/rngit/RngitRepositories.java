package org.qortal.rngit;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.qortal.rngit.RngitPermissions.Permission;
import org.qortal.rngit.RngitPermissions.PermissionSet;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * The groups and repositories an rngit node serves, with their permissions
 * ({@code server.py} {@code load_repository_group}, {@code load_repository},
 * {@code update_group_permissions}, {@code update_repository_permissions}).
 * <p>
 * A group is a configured directory of bare repositories. Group rules come from
 * {@code <group>.allowed} next to the directory plus the group's {@code [access]}
 * entry in the config; repository rules from {@code <repo>.allowed} next to the
 * repository.
 */
@Slf4j
public final class RngitRepositories {

    @Getter
    public static final class Group {
        private final String name;
        private final Path path;
        private volatile PermissionSet permissions = PermissionSet.empty();
        private final Map<String, Repository> repositories = new ConcurrentHashMap<>();

        Group(String name, Path path) {
            this.name = name;
            this.path = path;
        }
    }

    @Getter
    public static final class Repository {
        private final String name;
        private final String group;
        private final Path path;
        private volatile PermissionSet permissions;
        /** Upstream URL when this repository is a fork, else null ({@code __is_fork}). */
        private final String forkSource;
        /** Upstream URL when this repository is a mirror, else null ({@code __is_mirror}). */
        private final String mirrorSource;

        Repository(String name, String group, Path path, PermissionSet permissions) {
            this.name = name;
            this.group = group;
            this.path = path;
            this.permissions = permissions;

            String type = RngitGit.rngitType(path);
            String source = type == null ? null : RngitGit.upstreamSource(path);
            this.forkSource = "fork".equals(type) ? source : null;
            this.mirrorSource = "mirror".equals(type) ? source : null;
        }
    }

    private final Map<String, Group> groups = new ConcurrentHashMap<>();
    /** Serves Qortal names as groups when set; local groups take precedence. */
    private volatile RngitQdnGateway qdn;
    /** Resolves name:, group: and owner rule targets when set (needs Qortal state). */
    private volatile RngitIdentityBindings bindings;
    private final Map<String, String> aliases;
    private final Map<String, List<String>> accessConfig;
    private final Set<String> blockedIdentities;

    /**
     * @param aliases           identity aliases from {@code [aliases]}, alias to hex hash
     * @param accessConfig      group rules from {@code [access]}, group name to rule list
     * @param blockedIdentities identity hashes (lowercase hex) denied everything
     */
    public RngitRepositories(Map<String, String> aliases, Map<String, List<String>> accessConfig,
                             Set<String> blockedIdentities) {
        this.aliases = Map.copyOf(aliases);
        this.accessConfig = Map.copyOf(accessConfig);
        this.blockedIdentities = Set.copyOf(blockedIdentities);
    }

    void setQdnGateway(RngitQdnGateway qdn) {
        this.qdn = qdn;
        if (qdn != null) qdn.attach(this);
    }

    RngitQdnGateway getQdnGateway() {
        return qdn;
    }

    void setIdentityBindings(RngitIdentityBindings bindings) {
        this.bindings = bindings;
    }

    /** The account to RNS identity lookups, or null without Qortal state. */
    public RngitIdentityBindings getIdentityBindings() {
        return bindings;
    }

    /** Staged changes awaiting the owner, as plain maps, or null if this is no QDN repository. */
    public List<Map<String, Object>> stagedChanges(String groupName, String repositoryName) throws IOException {
        RngitQdnGateway gateway = this.qdn;
        if (gateway == null || gateway.getStaging() == null || !isQdnGroup(groupName)) return null;
        List<Map<String, Object>> out = new ArrayList<>();
        for (RngitQdnStaging.Change change : gateway.getStaging().list(groupName, repositoryName)) {
            Map<String, Object> entry = new java.util.LinkedHashMap<>();
            entry.put("id", change.id);
            entry.put("kind", change.kind);
            entry.put("ref", change.ref);
            entry.put("base", change.base);
            entry.put("sha", change.sha);
            entry.put("force", change.force);
            entry.put("pusher", change.pusher);
            entry.put("created", change.created);
            entry.put("applies", gateway.getStaging().applies(groupName, repositoryName, change));
            out.add(entry);
        }
        return out;
    }

    /**
     * The resources that publish a staged change, for Hub's
     * PUBLISH_MULTIPLE_QDN_RESOURCES, or null if there is no such change.
     *
     * @throws Exception if the change no longer applies to the current state
     */
    public Map<String, Object> prepareStagedChange(String groupName, String repositoryName, long id) throws Exception {
        RngitQdnGateway gateway = this.qdn;
        if (gateway == null || gateway.getStaging() == null || !isQdnGroup(groupName)) return null;
        return gateway.getStaging().prepare(groupName, repositoryName, id);
    }

    public boolean removeStagedChange(String groupName, String repositoryName, long id) {
        RngitQdnGateway gateway = this.qdn;
        return gateway != null && gateway.getStaging() != null && gateway.getStaging().remove(groupName, repositoryName, id);
    }

    /** The descriptor a QDN repository's cache reflects, or null. */
    public RngitQdn.Descriptor qdnDescriptor(String groupName, String repositoryName) {
        RngitQdnGateway gateway = this.qdn;
        return gateway == null || groups.containsKey(groupName) ? null : gateway.descriptor(groupName, repositoryName);
    }

    /** Like {@link #qdnDescriptor} but never fetches from QDN: null unless already materialised. */
    public RngitQdn.Descriptor cachedQdnDescriptor(String groupName, String repositoryName) {
        RngitQdnGateway gateway = this.qdn;
        return gateway == null || groups.containsKey(groupName) ? null : gateway.cachedDescriptor(groupName, repositoryName);
    }

    /**
     * Resolves Qortal rule targets for rules belonging to {@code groupName}:
     * {@code owner} means the current owner of that group's Qortal name, so it
     * only applies to QDN groups.
     */
    RngitPermissions.QortalTargets qortalTargets(String groupName) {
        RngitIdentityBindings b = this.bindings;
        if (b == null) return null;
        return (target, remote) -> {
            if (RngitPermissions.TARGET_OWNER.equals(target)) {
                return isQdnGroup(groupName) && b.boundIdentities(RngitQdn.nameOwner(groupName)).contains(remote);
            }
            if (target.startsWith(RngitPermissions.TARGET_NAME_PREFIX)) {
                String owner = RngitQdn.nameOwner(target.substring(RngitPermissions.TARGET_NAME_PREFIX.length()));
                return owner != null && b.boundIdentities(owner).contains(remote);
            }
            if (target.startsWith(RngitPermissions.TARGET_GROUP_PREFIX)) {
                return b.isBoundToGroupMember(Integer.parseInt(target.substring(RngitPermissions.TARGET_GROUP_PREFIX.length())), remote);
            }
            return false;
        };
    }

    /** {@code r:all}: everyone may read public QDN data. */
    static PermissionSet readAllPermissions() {
        return RngitPermissions.fromAllowedInput("r:all", Map.of(), false);
    }

    /**
     * Rules for a Qortal name's group: read for all, plus, where this node can
     * publish for the name, the name's {@code [access]} entry from the config.
     */
    PermissionSet qdnGroupPermissions(String name, boolean writable) {
        PermissionSet set = readAllPermissions();
        List<String> configured = accessConfig.get(name);
        if (writable && configured != null) RngitPermissions.addRules(set, configured, aliases);
        return set;
    }

    /** Rules for a QDN repository: read for all, plus its descriptor's rules where writable. */
    PermissionSet qdnRepositoryPermissions(String allowed, boolean writable) {
        PermissionSet set = readAllPermissions();
        if (writable && allowed != null) set.addAll(RngitPermissions.fromAllowedInput(allowed, aliases, false));
        return set;
    }

    /** A configured group, else the QDN group of a registered name, else null. */
    public Group getGroup(String name) {
        if (name == null) return null;
        Group local = groups.get(name);
        if (local != null) return local;
        RngitQdnGateway gateway = this.qdn;
        return gateway == null ? null : gateway.group(name);
    }

    public Repository getRepository(String groupName, String repositoryName) {
        if (groupName == null || repositoryName == null) return null;
        Group local = groups.get(groupName);
        if (local != null) return local.repositories.get(repositoryName);
        RngitQdnGateway gateway = this.qdn;
        return gateway == null ? null : gateway.repository(groupName, repositoryName);
    }

    /** No identity: what the anonymous REST API may see is what {@code all} rules grant. */
    static final String ANONYMOUS = "";

    /** Whether anyone may read the repository: always for QDN, by {@code r:all} for configured ones. */
    public boolean isPubliclyReadable(String groupName, String repositoryName) {
        return resolvePermission(ANONYMOUS, groupName, repositoryName, Permission.READ);
    }

    /** Repository names in a group that anyone may read. */
    public List<String> publicRepositories(String groupName) {
        Group local = groups.get(groupName);
        if (local != null) {
            List<String> out = new ArrayList<>();
            for (String name : local.repositories.keySet()) {
                if (isPubliclyReadable(groupName, name)) out.add(name);
            }
            out.sort(String::compareTo);
            return out;
        }
        return isQdnGroup(groupName) ? RngitQdn.listRepositories(groupName) : List.of();
    }

    /** Whether a group comes from QDN rather than the config. */
    public boolean isQdnGroup(String groupName) {
        return groupName != null && !groups.containsKey(groupName) && qdn != null && getGroup(groupName) != null;
    }

    static Group qdnGroup(String name, Path path, PermissionSet permissions) {
        Group group = new Group(name, path);
        group.permissions = permissions;
        return group;
    }

    static Repository qdnRepository(String name, String group, Path path, PermissionSet permissions) {
        return new Repository(name, group, path, permissions);
    }

    public Map<String, Group> getGroups() {
        return Map.copyOf(groups);
    }

    public boolean isBlocked(String remoteHashHex) {
        return blockedIdentities.contains(remoteHashHex);
    }

    /** {@code load_repository_group}. */
    public void loadGroup(String name, Path path) {
        Group existing = groups.get(name);
        if (existing != null && !existing.path.equals(path)) {
            log.error("Repository group path did not match existing entry while loading {}, aborting load", name);
            return;
        }
        Group group = groups.computeIfAbsent(name, n -> new Group(n, path));
        updateGroupPermissions(group);

        int loaded = 0;
        try (Stream<Path> entries = Files.list(path)) {
            for (Path entry : (Iterable<Path>) entries::iterator) {
                if (loadRepository(group, entry)) loaded++;
            }
        } catch (IOException e) {
            log.error("Could not list repository group {} at {}", name, path, e);
        }
        log.info("Loaded {} repositor{} for rngit group \"{}\"", loaded, loaded == 1 ? "y" : "ies", name);
    }

    /** {@code update_group_permissions}: the group's {@code .allowed} file, then its {@code [access]} entry. */
    public void updateGroupPermissions(Group group) {
        PermissionSet permissions;
        Path allowedPath = Path.of(group.path + ".allowed");
        try {
            permissions = RngitPermissions.loadAllowed(allowedPath, aliases);
        } catch (IOException e) {
            log.error("Could not load group permissions from {}", allowedPath, e);
            permissions = PermissionSet.empty();
        }

        List<String> configured = accessConfig.get(group.name);
        if (configured != null) {
            RngitPermissions.addRules(permissions, configured, aliases);
        }
        group.permissions = permissions;
    }

    /** {@code update_repository_permissions}. */
    public void updateRepositoryPermissions(Repository repository) {
        Path allowedPath = Path.of(repository.path + ".allowed");
        try {
            repository.permissions = RngitPermissions.loadAllowed(allowedPath, aliases);
        } catch (IOException e) {
            log.error("Could not update repository permissions for {}/{} from {}",
                    repository.group, repository.name, allowedPath, e);
        }
    }

    /** {@code load_repository}: registers a bare repository directory in a group. */
    public boolean loadRepository(Group group, Path path) {
        String fileName = path.getFileName().toString();
        if (!Files.isDirectory(path) || fileName.endsWith(".work") || fileName.endsWith(".releases")) {
            return false;
        }
        if (!RngitGit.isGitRepository(path)) {
            log.warn("The directory \"{}\" is not a git repository, skipping", path);
            return false;
        }
        if (!RngitGit.isBareRepository(path)) {
            log.warn("The directory \"{}\" is not a bare git repository, skipping. "
                    + "You can change it to a bare repository using \"git config --bool core.bare true\".", path);
            return false;
        }

        PermissionSet permissions;
        Path allowedPath = Path.of(path + ".allowed");
        try {
            permissions = RngitPermissions.loadAllowed(allowedPath, aliases);
        } catch (IOException e) {
            log.error("Could not load repository permissions from {}", allowedPath, e);
            permissions = PermissionSet.empty();
        }
        group.repositories.put(fileName, new Repository(fileName, group.name, path, permissions));
        return true;
    }

    /**
     * {@code resolve_doc_permission}: a work document's own rules
     * ({@code <repo>.work/<id>.allowed}, never executed) are checked first and
     * only add to the repository's, except that {@code none} there denies; the
     * repository and group rules then apply as in {@link #resolvePermission}.
     * Stats and release are not document permissions.
     */
    public boolean resolveDocumentPermission(String remoteHashHex, String groupName, String repositoryName, long docId,
                                             Permission permission) {
        Group group = getGroup(groupName);
        Repository repository = getRepository(groupName, repositoryName);
        if (group == null || repository == null) return false;
        if (permission == Permission.STATS || permission == Permission.RELEASE) return false;

        PermissionSet doc = PermissionSet.empty();
        Path allowedPath = Path.of(repository.path + ".work").resolve(docId + ".allowed");
        if (Files.isRegularFile(allowedPath)) {
            try {
                doc = RngitPermissions.fromAllowedInput(Files.readString(allowedPath, java.nio.charset.StandardCharsets.UTF_8), aliases, false);
            } catch (IOException e) {
                log.error("Error while resolving document permission for {}/{}/{}", groupName, repositoryName, docId, e);
            }
        }

        RngitPermissions.QortalTargets qortal = qortalTargets(groupName);
        Set<String> docPermissions = doc.get(permission);
        if (docPermissions.contains(RngitPermissions.TARGET_NONE)) return false;
        if (docPermissions.contains(RngitPermissions.TARGET_ALL)) return true;
        if (RngitPermissions.matches(docPermissions, remoteHashHex, qortal)) return true;
        if (RngitPermissions.matches(doc.get(Permission.ADMIN), remoteHashHex, qortal)) return true;

        return RngitPermissions.resolve(remoteHashHex, repository.permissions, group.permissions, permission, qortal);
    }

    /**
     * Checks {@code .allowed} content as the reference's set-permissions step
     * does: every non-empty, non-comment line must parse as a rule.
     *
     * @return null if valid, else the reference's error message
     */
    public String validateAllowedContent(String content) {
        String[] lines = content.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            String stripped = lines[i].strip();
            if (stripped.isEmpty() || stripped.startsWith("#")) continue;
            RngitPermissions.Rule rule = RngitPermissions.parseRule(stripped, aliases);
            if (rule.permissions == null || rule.target == null) {
                return "Invalid permission \"" + stripped + "\" on line " + (i + 1);
            }
        }
        return null;
    }

    /** {@code resolve_permission}: false for a blocked identity or an unknown group or repository. */
    public boolean resolvePermission(String remoteHashHex, String groupName, String repositoryName, Permission permission) {
        if (isBlocked(remoteHashHex)) return false;
        Group group = getGroup(groupName);
        Repository repository = getRepository(groupName, repositoryName);
        if (group == null || repository == null) return false;

        return RngitPermissions.resolve(remoteHashHex, repository.permissions, group.permissions, permission,
                qortalTargets(groupName));
    }

    /**
     * {@code resolve_group_permission}. Unlike {@link #resolvePermission} the
     * reference does not check the blocklist here; requests from blocked
     * identities are refused before any handler resolves permissions.
     */
    public boolean resolveGroupPermission(String remoteHashHex, String groupName, Permission permission) {
        Group group = getGroup(groupName);
        if (group == null) return false;

        return RngitPermissions.resolveGroup(remoteHashHex, group.permissions, permission, qortalTargets(groupName));
    }
}
