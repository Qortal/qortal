package org.qortal.rngit;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.qortal.rngit.RngitProtocol.IDENTITY_HASH_HEX_LENGTH;

/**
 * rngit's permission model ({@code server.py} {@code parse_permission},
 * {@code permissions_from_allowed_input}, {@code load_allowed_permissions},
 * {@code resolve_permission} and {@code resolve_group_permission}).
 * <p>
 * A rule is {@code permission:target}. Targets are {@code all}/{@code a}/{@code everyone},
 * {@code none}/{@code n}/{@code nobody}, an identity hash, or an alias of one.
 * Target names are matched case-sensitively, as the reference does.
 */
@Slf4j
public final class RngitPermissions {

    /** The eight permission lists a group, repository or document carries. */
    public enum Permission {
        READ("r", "read"),
        WRITE("w", "write"),
        CREATE("c", "create"),
        STATS("s", "stats"),
        RELEASE("rel", "release"),
        INTERACT("i", "interact"),
        PROPOSE("p", "propose"),
        ADMIN("adm", "admin");

        private final List<String> names;

        Permission(String... names) {
            this.names = List.of(names);
        }
    }

    private static final List<String> READWRITE_NAMES = List.of("rw", "readwrite");

    /** Target markers; identity targets are stored as lowercase hex. */
    public static final String TARGET_NONE = "*none";
    public static final String TARGET_ALL = "*all";

    private static final List<String> TGT_NONE_NAMES = List.of("n", "none", "nobody");
    private static final List<String> TGT_ALL_NAMES = List.of("a", "all", "everyone");

    /** Longest a dynamic {@code .allowed} script may run before its output is discarded. */
    private static final long DYNAMIC_ALLOWED_TIMEOUT_SECONDS = 10;

    private RngitPermissions() {
    }

    /** One permission list per {@link Permission}; each a set of targets in rule order. */
    public static final class PermissionSet {
        private final Map<Permission, Set<String>> lists = new EnumMap<>(Permission.class);
        private final boolean dynamic;

        PermissionSet(boolean dynamic) {
            for (Permission p : Permission.values()) lists.put(p, new LinkedHashSet<>());
            this.dynamic = dynamic;
        }

        public static PermissionSet empty() {
            return new PermissionSet(false);
        }

        public Set<String> get(Permission permission) {
            return Collections.unmodifiableSet(lists.get(permission));
        }

        /** Whether these rules came from an executable {@code .allowed} file. */
        public boolean isDynamic() {
            return dynamic;
        }

        void add(Set<Permission> permissions, String target) {
            for (Permission p : permissions) lists.get(p).add(target);
        }

        void addAll(PermissionSet other) {
            for (Permission p : Permission.values()) lists.get(p).addAll(other.lists.get(p));
        }
    }

    /** A parsed rule: the permission lists it adds to, and its target. Null fields mean invalid. */
    static final class Rule {
        final Set<Permission> permissions;
        final String target;

        Rule(Set<Permission> permissions, String target) {
            this.permissions = permissions;
            this.target = target;
        }
    }

    static String resolveAlias(String alias, Map<String, String> aliases) {
        if (TGT_NONE_NAMES.contains(alias.toLowerCase(Locale.ROOT)) || TGT_ALL_NAMES.contains(alias.toLowerCase(Locale.ROOT))) {
            return alias;
        }
        if (isIdentityHashHex(alias)) {
            return alias;
        }
        return aliases.getOrDefault(alias, alias);
    }

    static boolean isIdentityHashHex(String s) {
        return s.length() == IDENTITY_HASH_HEX_LENGTH && s.chars().allMatch(c -> Character.digit(c, 16) >= 0);
    }

    /**
     * Qortal targets, an extension resolved by this node only:
     * {@code name:<name>} (identities bound to the name's current owner),
     * {@code group:<id>} (identities bound to members of a Qortal group) and
     * {@code owner} (identities bound to the current owner of the Qortal name a
     * QDN repository lives under). Stock clients only ever see them as text.
     */
    public static final String TARGET_OWNER = "owner";
    static final String TARGET_NAME_PREFIX = "name:";
    static final String TARGET_GROUP_PREFIX = "group:";

    /** Whether a target needs Qortal state to resolve, rather than matching a hash. */
    static boolean isQortalTarget(String target) {
        return TARGET_OWNER.equals(target) || target.startsWith(TARGET_NAME_PREFIX) || target.startsWith(TARGET_GROUP_PREFIX);
    }

    /** Resolves Qortal targets for a remote identity; null where Qortal state is unavailable. */
    @FunctionalInterface
    public interface QortalTargets {
        boolean matches(String target, String remoteHashHex);
    }

    private static String qortalTarget(String target) {
        if (TARGET_OWNER.equals(target)) return target;
        if (target.startsWith(TARGET_NAME_PREFIX)) {
            String name = target.substring(TARGET_NAME_PREFIX.length());
            return name.isEmpty() || name.contains(":") ? null : target;
        }
        if (target.startsWith(TARGET_GROUP_PREFIX)) {
            String id = target.substring(TARGET_GROUP_PREFIX.length());
            return !id.isEmpty() && id.chars().allMatch(Character::isDigit) && id.length() < 10 ? target : null;
        }
        return null;
    }

    /** {@code parse_permission}: a {@code permission:target} string. */
    static Rule parseRule(String rule, Map<String, String> aliases) {
        int colon = rule.indexOf(':');
        if (colon < 0) {
            return new Rule(null, null);
        }
        String rest = rule.substring(colon + 1);
        String qortal = qortalTarget(rest);
        if (qortal == null && rest.contains(":")) {
            return new Rule(null, null);
        }

        String perm = rule.substring(0, colon).toLowerCase(Locale.ROOT);
        String target = qortal != null ? qortal : resolveAlias(rest, aliases);

        Set<Permission> permissions = null;
        if (READWRITE_NAMES.contains(perm)) {
            permissions = EnumSet.of(Permission.READ, Permission.WRITE);
        } else {
            for (Permission p : Permission.values()) {
                if (p.names.contains(perm)) {
                    permissions = EnumSet.of(p);
                    break;
                }
            }
        }

        String resolvedTarget;
        if (qortal != null) {
            resolvedTarget = qortal;
        } else if (TGT_NONE_NAMES.contains(target)) {
            resolvedTarget = TARGET_NONE;
        } else if (TGT_ALL_NAMES.contains(target)) {
            resolvedTarget = TARGET_ALL;
        } else if (isIdentityHashHex(target)) {
            resolvedTarget = target.toLowerCase(Locale.ROOT);
        } else {
            if (target.length() == IDENTITY_HASH_HEX_LENGTH) {
                log.error("Invalid identity hash \"{}\" in access permissions", target);
            }
            resolvedTarget = null;
        }

        return new Rule(permissions, resolvedTarget);
    }

    /** Adds a list of rules to a permission set, skipping invalid ones. */
    static void addRules(PermissionSet set, Iterable<String> rules, Map<String, String> aliases) {
        for (String entry : rules) {
            String input = entry.strip();
            if (input.startsWith("#")) continue;
            Rule rule = parseRule(input, aliases);
            if (rule.permissions == null || rule.target == null) continue;
            set.add(rule.permissions, rule.target);
        }
    }

    /** {@code permissions_from_allowed_input}: the text of an {@code .allowed} file, one rule per line. */
    public static PermissionSet fromAllowedInput(String input, Map<String, String> aliases, boolean dynamic) {
        PermissionSet set = new PermissionSet(dynamic);
        if (input != null) {
            addRules(set, input.lines()::iterator, aliases);
        }
        return set;
    }

    /**
     * {@code load_allowed_permissions}: reads an {@code .allowed} file, or runs it
     * and parses its stdout when it is executable. A missing file is no rules.
     */
    public static PermissionSet loadAllowed(Path allowedPath, Map<String, String> aliases) throws IOException {
        if (!Files.isRegularFile(allowedPath)) {
            return fromAllowedInput("", aliases, false);
        }

        if (Files.isExecutable(allowedPath)) {
            Process process = new ProcessBuilder(allowedPath.toAbsolutePath().toString())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            byte[] out = process.getInputStream().readAllBytes();
            try {
                if (!process.waitFor(DYNAMIC_ALLOWED_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    throw new IOException("Dynamic permissions " + allowedPath + " did not finish in time");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted running dynamic permissions " + allowedPath, e);
            }
            return fromAllowedInput(new String(out, StandardCharsets.UTF_8), aliases, true);
        }

        return fromAllowedInput(Files.readString(allowedPath, StandardCharsets.UTF_8), aliases, false);
    }

    /**
     * {@code resolve_permission} for an existing repository: repository rules
     * first; group rules only when the repository has none for that permission;
     * admins of either level always pass, except that an explicit {@code none}
     * at the deciding level denies.
     */
    static boolean resolve(String remoteHashHex, PermissionSet repository, PermissionSet group, Permission permission) {
        return resolve(remoteHashHex, repository, group, permission, null);
    }

    static boolean resolve(String remoteHashHex, PermissionSet repository, PermissionSet group, Permission permission,
                           QortalTargets qortal) {
        Set<String> repositoryPermissions = repository.get(permission);
        Set<String> repositoryAdmins = repository.get(Permission.ADMIN);

        if (repositoryPermissions.contains(TARGET_NONE)) return false;
        if (repositoryPermissions.contains(TARGET_ALL)) return true;
        if (matches(repositoryPermissions, remoteHashHex, qortal)) return true;
        if (matches(repositoryAdmins, remoteHashHex, qortal)) return true;
        if (!repositoryPermissions.isEmpty()) return false;

        return resolveGroup(remoteHashHex, group, permission, qortal);
    }

    /** {@code resolve_group_permission}. */
    static boolean resolveGroup(String remoteHashHex, PermissionSet group, Permission permission) {
        return resolveGroup(remoteHashHex, group, permission, null);
    }

    static boolean resolveGroup(String remoteHashHex, PermissionSet group, Permission permission, QortalTargets qortal) {
        Set<String> groupPermissions = group.get(permission);

        if (groupPermissions.contains(TARGET_NONE)) return false;
        if (groupPermissions.contains(TARGET_ALL)) return true;
        if (matches(groupPermissions, remoteHashHex, qortal)) return true;
        return matches(group.get(Permission.ADMIN), remoteHashHex, qortal);
    }

    /** The identity is listed, or a Qortal target in the list resolves to it. */
    static boolean matches(Set<String> targets, String remoteHashHex, QortalTargets qortal) {
        if (targets.contains(remoteHashHex)) return true;
        if (qortal == null) return false;
        for (String target : targets) {
            if (isQortalTarget(target) && qortal.matches(target, remoteHashHex)) return true;
        }
        return false;
    }
}
