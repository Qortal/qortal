package org.qortal.rngit;

import org.junit.jupiter.api.Test;
import org.qortal.rngit.RngitPermissions.Permission;
import org.qortal.rngit.RngitPermissions.PermissionSet;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.qortal.rngit.RngitPermissions.TARGET_ALL;
import static org.qortal.rngit.RngitPermissions.TARGET_NONE;

/**
 * Permission parsing and resolution against {@code server.py}: the documented
 * examples and the precedence rules of {@code resolve_permission}.
 */
class RngitPermissionsTest {

    private static final String ALICE = "9710b86ba12c42d1d8f30f74fe509286";
    private static final String BOB = "a1b2c3d4e5f686ba12c42d1ba12ef1aa";
    private static final String EVE = "00000000000000000000000000000001";
    private static final Map<String, String> NO_ALIASES = Map.of();

    private static PermissionSet rules(String text) {
        return RngitPermissions.fromAllowedInput(text, NO_ALIASES, false);
    }

    @Test
    void parsesLongShortAndReadwriteForms() {
        PermissionSet set = rules(String.join("\n",
                "# comment", "rw:" + ALICE, "release:" + BOB, "s:all", "adm:" + ALICE, "i:everyone", "p:a",
                "w:nobody", "bogus:all", "r:not-a-hash", "r:" + ALICE.toUpperCase()));

        assertEquals(Set.of(ALICE), set.get(Permission.READ), "rw grants read; an uppercase hash is the same identity");
        assertEquals(Set.of(ALICE, TARGET_NONE), set.get(Permission.WRITE));
        assertEquals(Set.of(BOB), set.get(Permission.RELEASE));
        assertEquals(Set.of(TARGET_ALL), set.get(Permission.STATS));
        assertEquals(Set.of(TARGET_ALL), set.get(Permission.INTERACT));
        assertEquals(Set.of(TARGET_ALL), set.get(Permission.PROPOSE));
        assertEquals(Set.of(ALICE), set.get(Permission.ADMIN));
        assertTrue(set.get(Permission.CREATE).isEmpty());
    }

    @Test
    void targetNamesAreCaseSensitiveAsInTheReference() {
        assertTrue(rules("r:All").get(Permission.READ).isEmpty());
        assertEquals(Set.of(TARGET_ALL), rules("R:all").get(Permission.READ), "permission names are not");
    }

    @Test
    void aliasesResolve() {
        PermissionSet set = RngitPermissions.fromAllowedInput("w:alice", Map.of("alice", ALICE), false);
        assertEquals(Set.of(ALICE), set.get(Permission.WRITE));
    }

    @Test
    void documentedExamplePublicReadRestrictedWrite() {
        PermissionSet repo = rules("r:all\nw:" + ALICE);
        PermissionSet group = PermissionSet.empty();

        assertTrue(RngitPermissions.resolve(EVE, repo, group, Permission.READ));
        assertFalse(RngitPermissions.resolve(EVE, repo, group, Permission.WRITE));
        assertTrue(RngitPermissions.resolve(ALICE, repo, group, Permission.WRITE));
    }

    @Test
    void repositoryRulesShadowGroupRulesPerPermission() {
        PermissionSet group = rules("rw:all");
        PermissionSet repo = rules("w:" + ALICE);

        assertFalse(RngitPermissions.resolve(EVE, repo, group, Permission.WRITE), "repo has write rules, eve not in them");
        assertTrue(RngitPermissions.resolve(EVE, repo, group, Permission.READ), "repo has no read rules: group decides");
    }

    @Test
    void adminsPassAndNoneDenies() {
        PermissionSet group = rules("adm:" + BOB);
        PermissionSet repo = rules("adm:" + ALICE + "\nr:none");

        assertFalse(RngitPermissions.resolve(ALICE, repo, group, Permission.READ), "explicit none wins even for an admin");
        assertTrue(RngitPermissions.resolve(ALICE, repo, group, Permission.WRITE), "repo admin");
        assertTrue(RngitPermissions.resolve(BOB, repo, group, Permission.WRITE), "group admin, repo has no write rules");
        assertFalse(RngitPermissions.resolve(EVE, repo, group, Permission.WRITE));
    }

    @Test
    void groupResolution() {
        PermissionSet group = rules("c:" + ALICE + "\nr:all");

        assertTrue(RngitPermissions.resolveGroup(ALICE, group, Permission.CREATE));
        assertFalse(RngitPermissions.resolveGroup(EVE, group, Permission.CREATE));
        assertTrue(RngitPermissions.resolveGroup(EVE, group, Permission.READ));
    }

    @Test
    void refAndShaSanitising() {
        assertEquals("refs/heads/master", RngitRefs.sanRef("refs/heads/master"));
        for (String bad : new String[]{"master", "refs/heads/a..b", "refs/heads/x.lock", "-refs/x", "refs/heads/a b",
                "refs/heads/a~1", "refs/heads/a:b", "refs//x", "refs/heads/x/", "refs/heads/@{x}", "refs/heads/a!b"}) {
            assertEquals(null, RngitRefs.sanRef(bad), bad);
        }
        assertEquals("a".repeat(40), RngitRefs.sanSha("a".repeat(40)));
        assertEquals(null, RngitRefs.sanSha("a".repeat(39)));
        assertEquals(null, RngitRefs.sanSha("g".repeat(40)));
    }

    @Test
    void uploadedAllowedContentIsValidatedLineByLine() {
        RngitRepositories repositories = new RngitRepositories(Map.of("alice", ALICE), Map.of(), Set.of());

        assertEquals(null, repositories.validateAllowedContent("# comment\n\nr:all\nw:alice\nadm:" + BOB + "\n"));
        assertEquals("Invalid permission \"w:mallory\" on line 3",
                repositories.validateAllowedContent("r:all\n# fine\nw:mallory\nw:" + BOB));
        assertEquals("Invalid permission \"read-all\" on line 1", repositories.validateAllowedContent("read-all"));
    }

    @Test
    void qortalTargetsParseAndResolveThroughTheNode() {
        PermissionSet repo = rules(String.join("\n",
                "w:name:alice", "r:group:42", "adm:owner",
                "w:name:", "w:group:x", "w:foo:bar", "w:name:a:b"));
        assertEquals(Set.of("name:alice"), repo.get(Permission.WRITE), "malformed Qortal targets are skipped");
        assertEquals(Set.of("group:42"), repo.get(Permission.READ));
        assertEquals(Set.of("owner"), repo.get(Permission.ADMIN));

        // A stand-in for the node's lookups: BOB is bound to alice's account, EVE to group 42
        RngitPermissions.QortalTargets qortal = (target, remote) ->
                target.equals("name:alice") && remote.equals(BOB) || target.equals("group:42") && remote.equals(EVE);
        PermissionSet group = PermissionSet.empty();

        assertTrue(RngitPermissions.resolve(BOB, repo, group, Permission.WRITE, qortal));
        assertFalse(RngitPermissions.resolve(EVE, repo, group, Permission.WRITE, qortal));
        assertTrue(RngitPermissions.resolve(EVE, repo, group, Permission.READ, qortal));
        assertFalse(RngitPermissions.resolve(BOB, repo, group, Permission.WRITE), "without Qortal state they match nothing");

        RngitRepositories repositories = new RngitRepositories(Map.of(), Map.of(), Set.of());
        assertEquals(null, repositories.validateAllowedContent("w:name:alice\nr:group:42\nadm:owner"));
        assertEquals("Invalid permission \"w:group:x\" on line 1", repositories.validateAllowedContent("w:group:x"));
    }
}
