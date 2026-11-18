#!/usr/bin/env bash
#
# Live test of Core's rngit node (org.qortal.rngit) against the stock rngit
# and git-remote-rns clients from Python RNS.
#
# The server runs as RngitLiveNode: an RngitServer on its own Reticulum
# instance, without the rest of Core. Two client identities:
#   alice  may create repositories in group "public" (c:alice) and becomes
#          admin of what she creates
#   bob    may only read (r:all)
#
#   1  alice: rngit create public/repo            -> created, .allowed = adm:alice
#   2  alice: push a local repo (3 MiB blob)       -> server ref updated
#   3  bob:   clone                                -> same HEAD and blob
#   4  bob:   push                                 -> refused, server unchanged
#   5  alice: push a branch, then delete it        -> ref created, then removed
#   6  alice: rngit create public/repo again       -> "already exists"
#   7  bob:   rngit create public/other            -> refused, nothing created
#   8  alice: rngit mirror http://.../upstream.git   -> mirror, HEAD follows upstream (main)
#   9  alice: rngit fork rns://<python node>/...     -> fork from a stock Python rngit node
#  10  upstream advances; alice: rngit sync          -> fork picks up the new commit
#  11  bob:   rngit sync                             -> refused
#  12  alice: rngit fork file:///...                 -> "Prohibited source URL"
#  13  alice: rngit perms public/repo, adds w:bob    -> saved; bob can now push
#  14  bob:   rngit perms public/repo                -> refused
#  15  alice: rngit perms with an invalid rule       -> rejected with its line, file unchanged
#  16  alice: rngit perms public (group)             -> refused, alice is not group admin
#  17  alice: push tag v1.0, rngit release create    -> published, signed artifacts + manifest stored
#  18  bob:   rngit release list                     -> v1.0 listed as latest
#  19  bob:   rngit release fetch latest:all         -> files fetched and verified against alice
#  20  bob:   rngit release create                   -> refused, bob has no release permission
#  21  node-side tampering, bob fetches again        -> client rejects the altered artifact
#  22  alice: rngit release delete v1.0              -> release removed
#  23  alice: rngit work create (after i:all, p:all) -> document #1, signed
#  24  bob:   rngit work list / view -d 1            -> listed; signature valid, author alice
#  25  bob:   rngit work update -d 1                 -> comment stored, view shows it
#  26  bob:   rngit work propose                     -> proposal #2, bob owns its rules
#  27  bob:   rngit work edit -d 1                   -> refused, not the author
#  28  alice: rngit work complete -d 1               -> moved to completed
#  29  alice: rngit work delete -d 1                 -> removed (the reference fails here)
#  30  anonymous Nomad Network visitor: front page     -> group "public" listed
#  31  anonymous visitor: public/repo page            -> clone URL, commit count, README
#  32  anonymous visitor: download README.md          -> file response named README.md
#  33  bob, identified: /media for the 3 MiB blob     -> same bytes as the repository
#
# Usage:  ./run.sh       (RNS_SRC defaults to ~/git/Reticulum)
#         RETICULUM_CLASSES=~/git/reticulum-network-stack-own/target/classes ./run.sh
#                        runs against a local library build instead of the
#                        jitpack release in Core's pom
#
# Everything runs on 127.0.0.1:42508. No external network is touched.

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CORE="$(cd "$HERE/../.." && pwd)"
RNS_SRC="${RNS_SRC:-$HOME/git/Reticulum}"
WORK="$HERE/.work"
PIDS=()

cleanup() {
    for p in "${PIDS[@]:-}"; do
        [[ -n "$p" ]] && kill "$p" 2>/dev/null
    done
}
trap cleanup EXIT

say()  { printf '\n=== %s ===\n' "$1"; }
pass() { echo "  PASS: $1"; }
fail() { echo "  FAIL: $1"; FAILURES=$((FAILURES+1)); }
FAILURES=0

rm -rf "$WORK" "$HERE/java_config/storage" "$HERE/python_client_config/storage"
mkdir -p "$WORK/bin" "$WORK/groups/public" "$WORK/rngit" "$WORK/alice" "$WORK/bob"

say "Preparing"
[[ -d "$RNS_SRC/RNS" ]] || { echo "FAIL: no Reticulum checkout at $RNS_SRC"; exit 1; }
export PYTHONPATH="$RNS_SRC"
echo "Reference RNS: $(python3 -c 'import RNS._version as v; print(v.__version__)')"

(cd "$CORE" && mvn -o -q test-compile -DskipJUnitTests=true) || { echo "FAIL: test-compile"; exit 1; }
(cd "$CORE" && mvn -o -q dependency:build-classpath -Dmdep.outputFile="$WORK/classpath.txt") || { echo "FAIL: classpath"; exit 1; }
CP="$(cat "$WORK/classpath.txt"):$CORE/target/classes:$CORE/target/test-classes"
[[ -n "${RETICULUM_CLASSES:-}" ]] && CP="$RETICULUM_CLASSES:$CP" && echo "Reticulum library: $RETICULUM_CLASSES"

# The stock client entry points, exactly as RNS installs them
for cmd in rngit git-remote-rns; do
    module=$([[ $cmd == rngit ]] && echo server || echo client)
    cat > "$WORK/bin/$cmd" <<EOF
#!/usr/bin/env bash
export PYTHONPATH="$RNS_SRC"
exec python3 -c 'import sys; from RNS.Utilities.rngit.$module import main; sys.argv[0] = "$cmd"; sys.exit(main())' "\$@"
EOF
    chmod +x "$WORK/bin/$cmd"
done
export PATH="$WORK/bin:$PATH"
export RNS_CONFIG="$HERE/python_client_config"
export GIT_AUTHOR_NAME=livetest GIT_AUTHOR_EMAIL=livetest@example.invalid
export GIT_COMMITTER_NAME=livetest GIT_COMMITTER_EMAIL=livetest@example.invalid

# Client identities, created up front so the server config can name them
ident() {
    python3 -c "import RNS; i = RNS.Identity(); i.to_file('$1'); print(i.hash.hex())"
}
ALICE="$(ident "$WORK/alice/client_identity")"
BOB="$(ident "$WORK/bob/client_identity")"
echo "alice $ALICE"
echo "bob   $BOB"

cat > "$WORK/rngit/config" <<EOF
[rngit]
  node_name = Core rngit live test
  announce_interval = 1

[repositories]
  public = $WORK/groups/public

[aliases]
  alice = $ALICE

[access]
  public = r:all, c:alice

[pages]
  serve_nomadnet = yes
EOF

# --- Upstreams for the fork and mirror steps --------------------------------
# An http upstream: a bare repository served with git's dumb-http protocol
UP="$WORK/upstream/upstream.git"
git init -q -b main "$WORK/upstream/src"
echo "upstream" > "$WORK/upstream/src/UP.md"
git -C "$WORK/upstream/src" add . && git -C "$WORK/upstream/src" commit -q -m "upstream commit"
git -C "$WORK/upstream/src" branch side
git clone -q --bare "$WORK/upstream/src" "$UP"
git -C "$UP" update-server-info
HTTP_PORT=42518
(cd "$WORK/upstream" && exec python3 -m http.server "$HTTP_PORT" --bind 127.0.0.1) > "$WORK/http.log" 2>&1 &
PIDS+=($!)

# An rns:// upstream: a stock Python rngit node, connected to the Java node
mkdir -p "$WORK/pynode/groups/native" "$WORK/pynode/rns" "$WORK/pynode/rngit"
git clone -q --bare "$WORK/upstream/src" "$WORK/pynode/groups/native/src"
printf '%s\n' "[reticulum]" "  enable_transport = False" "  share_instance = No" \
    "[logging]" "  loglevel = 4" "[interfaces]" "  [[to java node]]" \
    "    type = TCPClientInterface" "    interface_enabled = True" \
    "    target_host = 127.0.0.1" "    target_port = 42508" > "$WORK/pynode/rns/config"
printf '%s\n' "[rngit]" "  announce_interval = 1" "[repositories]" \
    "  native = $WORK/pynode/groups/native" "[access]" "  native = r:all" > "$WORK/pynode/rngit/config"

as() {  # as <who> <command...>: run a client command with that identity
    local who="$1"; shift
    RNGIT_CONFIG="$WORK/$who" "$@"
}

timeout 900 java -cp "$CP" org.qortal.rngit.RngitLiveNode "$HERE/java_config" "$WORK/rngit" \
    > "$WORK/server.log" 2>&1 &
PIDS+=($!)

DEST=""
for _ in $(seq 1 60); do
    DEST="$(grep -a '\[rngit-live\] destination <' "$WORK/server.log" 2>/dev/null \
        | grep -oE '<[0-9a-f]{32}>' | head -1 | tr -d '<>')"
    [[ -n "$DEST" ]] && break
    sleep 0.5
done
[[ -n "$DEST" ]] || { echo "FAIL: server did not start"; tail -30 "$WORK/server.log"; exit 1; }
echo "Java rngit destination: $DEST"

PYDEST="$(rngit --config "$WORK/pynode/rngit" --rnsconfig "$WORK/pynode/rns" -p 2>/dev/null \
    | grep "Repositories Destination" | grep -oE '[0-9a-f]{32}')"
timeout 900 rngit --config "$WORK/pynode/rngit" --rnsconfig "$WORK/pynode/rns" > "$WORK/pynode.log" 2>&1 &
PIDS+=($!)
echo "Python rngit destination: $PYDEST"
sleep 8
URL="rns://$DEST/public/repo"
REPO="$WORK/groups/public/repo"

# ---------------------------------------------------------------------------
say "1: alice creates public/repo"
as alice rngit create --config "$WORK/alice" --rnsconfig "$RNS_CONFIG" "$URL" > "$WORK/1.log" 2>&1
if [[ -f "$REPO/HEAD" && "$(cat "$REPO.allowed" 2>/dev/null)" == "adm:$ALICE" ]] && grep -q "created" "$WORK/1.log"; then
    pass "bare repository created, creator is admin"
else
    sed 's/^/  | /' "$WORK/1.log" | tail -5; fail "create"
fi

# ---------------------------------------------------------------------------
say "2: alice pushes a new local repository"
# Cloning a freshly created, empty repository fails against the reference
# rngit node too (it bundles a ref that does not exist yet), so this follows the
# documented workflow: an existing local repository, a remote added, a push.
C="$WORK/alice/clone"
git init -q -b master "$C"
echo "hello" > "$C/README.md"
head -c $((3 * 1024 * 1024)) /dev/urandom > "$C/blob.bin"
git -C "$C" add . && git -C "$C" commit -q -m "first commit"
git -C "$C" remote add origin "$URL"
as alice git -C "$C" push -q origin master > "$WORK/2.log" 2>&1
PUSHED="$(git -C "$C" rev-parse HEAD)"
if [[ "$(git -C "$REPO" rev-parse refs/heads/master 2>/dev/null)" == "$PUSHED" ]]; then
    pass "server master is ${PUSHED:0:12}"
else
    sed 's/^/  | /' "$WORK/2.log" | tail -5; fail "push"
fi

# ---------------------------------------------------------------------------
say "3: bob clones"
as bob git clone -q "$URL" "$WORK/bob/clone" > "$WORK/3.log" 2>&1
if [[ "$(git -C "$WORK/bob/clone" rev-parse HEAD 2>/dev/null)" == "${PUSHED:-x}" ]] \
   && cmp -s "$WORK/alice/clone/blob.bin" "$WORK/bob/clone/blob.bin"; then
    pass "bob has alice's commit and blob"
else
    sed 's/^/  | /' "$WORK/3.log" | tail -5; fail "read-only clone"
fi

# ---------------------------------------------------------------------------
say "4: bob tries to push"
if [[ -d "$WORK/bob/clone/.git" ]]; then
    echo "bob was here" >> "$WORK/bob/clone/README.md"
    git -C "$WORK/bob/clone" commit -q -am "bob's change"
    as bob git -C "$WORK/bob/clone" push -q origin master > "$WORK/4.log" 2>&1
    RC=$?
    if [[ $RC -ne 0 && "$(git -C "$REPO" rev-parse refs/heads/master)" == "${PUSHED:-x}" ]]; then
        pass "refused ($(grep -oE 'refused list: .*|Not allowed|Not found' "$WORK/4.log" | head -1)), server unchanged"
    else
        sed 's/^/  | /' "$WORK/4.log" | tail -5; fail "unauthorised push was not refused"
    fi
else
    fail "no clone for bob"
fi

# ---------------------------------------------------------------------------
say "5: alice pushes a branch and deletes it"
if [[ -d "$WORK/alice/clone/.git" ]]; then
    git -C "$WORK/alice/clone" branch feature
    as alice git -C "$WORK/alice/clone" push -q origin feature > "$WORK/5a.log" 2>&1
    CREATED="$(git -C "$REPO" rev-parse --verify -q refs/heads/feature)"
    as alice git -C "$WORK/alice/clone" push -q origin :feature > "$WORK/5b.log" 2>&1
    GONE="$(git -C "$REPO" rev-parse --verify -q refs/heads/feature)"
    if [[ "$CREATED" == "${PUSHED:-x}" && -z "$GONE" ]]; then
        pass "feature created via update_ref, then deleted"
    else
        sed 's/^/  | /' "$WORK/5a.log" "$WORK/5b.log" | tail -8; fail "branch create/delete"
    fi
else
    fail "no clone for alice"
fi

# ---------------------------------------------------------------------------
say "6: alice creates public/repo again"
as alice rngit create --config "$WORK/alice" --rnsconfig "$RNS_CONFIG" "$URL" > "$WORK/6.log" 2>&1
if grep -qi "already exists" "$WORK/6.log"; then
    pass "refused: already exists"
else
    sed 's/^/  | /' "$WORK/6.log" | tail -5; fail "duplicate create"
fi

# ---------------------------------------------------------------------------
say "7: bob tries to create public/other"
as bob rngit create --config "$WORK/bob" --rnsconfig "$RNS_CONFIG" "rns://$DEST/public/other" > "$WORK/7.log" 2>&1
if [[ ! -e "$WORK/groups/public/other" ]] && grep -qiE "not allowed|not found" "$WORK/7.log"; then
    pass "refused ($(grep -oiE 'not allowed|not found' "$WORK/7.log" | head -1)), nothing created"
else
    sed 's/^/  | /' "$WORK/7.log" | tail -5; fail "unauthorised create"
fi

# ---------------------------------------------------------------------------
say "8: alice mirrors an http upstream"
MIRROR="$WORK/groups/public/httpmirror"
as alice rngit mirror --config "$WORK/alice" --rnsconfig "$RNS_CONFIG" \
    "http://127.0.0.1:$HTTP_PORT/upstream.git" "rns://$DEST/public/httpmirror" > "$WORK/8.log" 2>&1
if [[ "$(git -C "$MIRROR" rev-parse refs/heads/main 2>/dev/null)" == "$(git -C "$UP" rev-parse refs/heads/main)" \
      && -n "$(git -C "$MIRROR" rev-parse --verify -q refs/heads/side)" \
      && "$(git -C "$MIRROR" symbolic-ref HEAD)" == "refs/heads/main" \
      && "$(git -C "$MIRROR" config repository.rngit.type)" == "mirror" ]]; then
    pass "all branches mirrored, HEAD -> main, recorded as mirror"
else
    sed 's/^/  | /' "$WORK/8.log" | tail -5; fail "http mirror"
fi

# ---------------------------------------------------------------------------
say "9: alice forks from the Python rngit node"
FORK="$WORK/groups/public/rnsfork"
as alice rngit fork --config "$WORK/alice" --rnsconfig "$RNS_CONFIG" \
    "rns://$PYDEST/native/src" "rns://$DEST/public/rnsfork" > "$WORK/9.log" 2>&1
if [[ "$(git -C "$FORK" rev-parse refs/heads/main 2>/dev/null)" == "$(git -C "$WORK/pynode/groups/native/src" rev-parse refs/heads/main)" \
      && "$(git -C "$FORK" config repository.rngit.upstream.source)" == "rns://$PYDEST/native/src" ]]; then
    pass "forked over Reticulum from a stock rngit node"
else
    sed 's/^/  | /' "$WORK/9.log" | tail -5
    grep -aE "Failed to fetch|rngit" "$WORK/server.log" | tail -3 | sed 's/^/  s| /'
    fail "rns fork"
fi

# ---------------------------------------------------------------------------
say "10: upstream advances, alice syncs the fork"
echo "more" >> "$WORK/upstream/src/UP.md"
git -C "$WORK/upstream/src" commit -q -am "second upstream commit"
git -C "$WORK/upstream/src" push -q "$WORK/pynode/groups/native/src" main
NEW_UP="$(git -C "$WORK/upstream/src" rev-parse main)"
as alice rngit sync --config "$WORK/alice" --rnsconfig "$RNS_CONFIG" "rns://$DEST/public/rnsfork" > "$WORK/10.log" 2>&1
if [[ "$(git -C "$FORK" rev-parse refs/heads/main 2>/dev/null)" == "$NEW_UP" ]] && grep -q "synced" "$WORK/10.log"; then
    pass "fork now at ${NEW_UP:0:12}"
else
    sed 's/^/  | /' "$WORK/10.log" | tail -5; fail "fork sync"
fi

# ---------------------------------------------------------------------------
say "11: bob tries to sync the fork"
as bob rngit sync --config "$WORK/bob" --rnsconfig "$RNS_CONFIG" "rns://$DEST/public/rnsfork" > "$WORK/11.log" 2>&1
if grep -qiE "not allowed|not found" "$WORK/11.log"; then
    pass "refused ($(grep -oiE 'not allowed|not found' "$WORK/11.log" | head -1))"
else
    sed 's/^/  | /' "$WORK/11.log" | tail -5; fail "unauthorised sync"
fi

# ---------------------------------------------------------------------------
say "12: alice forks a file:// source"
as alice rngit fork --config "$WORK/alice" --rnsconfig "$RNS_CONFIG" \
    "file://$UP" "rns://$DEST/public/filefork" > "$WORK/12.log" 2>&1
if grep -q "Prohibited source URL" "$WORK/12.log" && [[ ! -e "$WORK/groups/public/filefork" ]]; then
    pass "refused: Prohibited source URL"
else
    sed 's/^/  | /' "$WORK/12.log" | tail -5; fail "file:// source"
fi

# ---------------------------------------------------------------------------
# rngit perms opens $EDITOR on the current rules; this one replaces them with
# $PERMS_CONTENT, so each step states the rules it saves.
printf '%s\n' '#!/usr/bin/env bash' 'printf "%s\n" "$PERMS_CONTENT" > "$1"' > "$WORK/bin/perms-editor"
chmod +x "$WORK/bin/perms-editor"
export EDITOR="$WORK/bin/perms-editor"

say "13: alice grants bob write on public/repo"
PERMS_CONTENT="$(printf 'adm:%s\nw:%s' "$ALICE" "$BOB")" \
    as alice rngit perms --config "$WORK/alice" --rnsconfig "$RNS_CONFIG" "$URL" > "$WORK/13a.log" 2>&1
if [[ -d "$WORK/bob/clone/.git" ]]; then
    as bob git -C "$WORK/bob/clone" push -q origin master > "$WORK/13b.log" 2>&1
fi
if grep -q "Permissions updated" "$WORK/13a.log" && grep -q "^w:$BOB" "$REPO.allowed" \
      && [[ "$(git -C "$REPO" rev-parse refs/heads/master)" == "$(git -C "$WORK/bob/clone" rev-parse HEAD 2>/dev/null)" ]]; then
    pass "rules saved by the repository admin; bob's push now accepted"
else
    sed 's/^/  | /' "$WORK/13a.log" "$WORK/13b.log" 2>/dev/null | tail -6; fail "repository perms set"
fi

say "14: bob tries to edit public/repo permissions"
BEFORE="$(cat "$REPO.allowed")"
PERMS_CONTENT="adm:$BOB" as bob rngit perms --config "$WORK/bob" --rnsconfig "$RNS_CONFIG" "$URL" > "$WORK/14.log" 2>&1
if grep -qiE "not allowed|not found" "$WORK/14.log" && [[ "$(cat "$REPO.allowed")" == "$BEFORE" ]]; then
    pass "refused ($(grep -oiE 'not allowed|not found' "$WORK/14.log" | head -1)), rules unchanged"
else
    sed 's/^/  | /' "$WORK/14.log" | tail -5; fail "unauthorised perms edit"
fi

say "15: alice saves an invalid rule"
PERMS_CONTENT="$(printf 'adm:%s\nw:mallory' "$ALICE")" \
    as alice rngit perms --config "$WORK/alice" --rnsconfig "$RNS_CONFIG" "$URL" > "$WORK/15.log" 2>&1
if grep -q 'Invalid permission "w:mallory" on line 2' "$WORK/15.log" && [[ "$(cat "$REPO.allowed")" == "$BEFORE" ]]; then
    pass "rejected with its line number, rules unchanged"
else
    sed 's/^/  | /' "$WORK/15.log" | tail -5; fail "invalid rule"
fi

say "16: alice tries to edit the group's permissions"
PERMS_CONTENT="adm:$ALICE" as alice rngit perms --config "$WORK/alice" --rnsconfig "$RNS_CONFIG" "rns://$DEST/public" > "$WORK/16.log" 2>&1
if grep -qiE "not allowed" "$WORK/16.log" && [[ ! -e "$WORK/groups/public.allowed" ]]; then
    pass "refused: not a group admin"
else
    sed 's/^/  | /' "$WORK/16.log" | tail -5; fail "group perms"
fi

# ---------------------------------------------------------------------------
REL="$REPO.releases"
say "17: alice tags v1.0 and creates a release"
A="$WORK/alice/clone"
git -C "$A" tag v1.0
as alice git -C "$A" push -q origin v1.0 > "$WORK/17a.log" 2>&1
mkdir -p "$WORK/dist"
head -c 200000 /dev/urandom > "$WORK/dist/app-1.0.tar.gz"
echo "release checksum file" > "$WORK/dist/checksums.txt"
(cd "$A" && PERMS_CONTENT="Release 1.0 notes" \
    as alice rngit release --config "$WORK/alice" --rnsconfig "$RNS_CONFIG" "$URL" create "v1.0:$WORK/dist") > "$WORK/17b.log" 2>&1
STORED="$(ls "$REL/v1.0/artifacts" 2>/dev/null | sort | tr '\n' ' ')"
if [[ "$STORED" == "app-1.0.tar.gz app-1.0.tar.gz.rsg checksums.txt checksums.txt.rsg manifest.rsm " ]] \
      && grep -q "^status = published" "$REL/v1.0/META" && [[ "$(cat "$REL/latest")" == "v1.0" ]]; then
    pass "published with $STORED"
else
    sed 's/^/  | /' "$WORK/17a.log" "$WORK/17b.log" | tail -8; echo "  stored: $STORED"; fail "release create"
fi

say "18: bob lists releases"
as bob rngit release --config "$WORK/bob" --rnsconfig "$RNS_CONFIG" "$URL" list > "$WORK/18.log" 2>&1
as bob rngit release --config "$WORK/bob" --rnsconfig "$RNS_CONFIG" "$URL" view v1.0 > "$WORK/18v.log" 2>&1
if grep -qE "v1\.0.*published" "$WORK/18.log" && grep -q "Release 1.0 notes" "$WORK/18v.log" \
      && grep -q "Artifacts (5)" "$WORK/18v.log"; then
    pass "listed ($(grep -E 'v1\.0' "$WORK/18.log" | head -1 | tr -s ' ')); view shows notes and 5 artifacts"
else
    sed 's/^/  | /' "$WORK/18.log" | tail -6; fail "release list"
fi

say "19: bob fetches and verifies the latest release"
mkdir -p "$WORK/bob/fetch"
(cd "$WORK/bob/fetch" && as bob rngit release --config "$WORK/bob" --rnsconfig "$RNS_CONFIG" "$URL" fetch latest:all --signer "$ALICE") \
    > "$WORK/19.log" 2>&1
if cmp -s "$WORK/dist/app-1.0.tar.gz" "$WORK/bob/fetch/app-1.0.tar.gz" && cmp -s "$WORK/dist/checksums.txt" "$WORK/bob/fetch/checksums.txt" \
      && grep -q "validated" "$WORK/19.log"; then
    pass "artifacts verified against alice's signatures"
else
    sed 's/^/  | /' "$WORK/19.log" | tail -6; fail "release fetch"
fi

say "20: bob tries to create a release"
mkdir -p "$WORK/bobdist" && echo x > "$WORK/bobdist/x.bin"
(cd "$WORK/bob/clone" && git tag -f v1.0 >/dev/null 2>&1; PERMS_CONTENT="bob's notes" \
    as bob rngit release --config "$WORK/bob" --rnsconfig "$RNS_CONFIG" "$URL" create "v1.0:$WORK/bobdist") > "$WORK/20.log" 2>&1
if grep -qiE "not allowed" "$WORK/20.log" && [[ ! -e "$REL/v1.0/artifacts/x.bin" ]]; then
    pass "refused: Not allowed"
else
    sed 's/^/  | /' "$WORK/20.log" | tail -6; fail "unauthorised release"
fi

say "21: an artifact is altered on the node, bob fetches again"
printf 'tampered' >> "$REL/v1.0/artifacts/app-1.0.tar.gz"
mkdir -p "$WORK/bob/fetch2"
(cd "$WORK/bob/fetch2" && as bob rngit release --config "$WORK/bob" --rnsconfig "$RNS_CONFIG" "$URL" fetch latest:all --signer "$ALICE") \
    > "$WORK/21.log" 2>&1
if grep -q "does not match manifest" "$WORK/21.log" && [[ ! -e "$WORK/bob/fetch2/app-1.0.tar.gz" ]]; then
    pass "client rejected the altered artifact"
else
    sed 's/^/  | /' "$WORK/21.log" | tail -6; fail "tamper detection"
fi

say "22: alice deletes the release"
echo y | as alice rngit release --config "$WORK/alice" --rnsconfig "$RNS_CONFIG" "$URL" delete v1.0 > "$WORK/22.log" 2>&1
if [[ ! -e "$REL/v1.0" ]] && grep -q "deleted" "$WORK/22.log"; then
    pass "release removed"
else
    sed 's/^/  | /' "$WORK/22.log" | tail -6; fail "release delete"
fi

# ---------------------------------------------------------------------------
WORKDIR="$REPO.work"
work() {  # work <who> <rngit work arguments...>
    local who="$1"; shift
    as "$who" rngit work --config "$WORK/$who" --rnsconfig "$RNS_CONFIG" "$URL" "$@"
}

say "23: alice opens up discussion and creates a work document"
PERMS_CONTENT="$(printf 'adm:%s\nw:%s\ni:all\np:all' "$ALICE" "$BOB")" \
    as alice rngit perms --config "$WORK/alice" --rnsconfig "$RNS_CONFIG" "$URL" > "$WORK/23a.log" 2>&1
PERMS_CONTENT="The parser drops trailing fields." work alice create -t "Fix the parser" > "$WORK/23b.log" 2>&1
if [[ -f "$WORKDIR/active/1/root" ]] && grep -q "#1" "$WORK/23b.log"; then
    pass "document #1 created"
else
    sed 's/^/  | /' "$WORK/23a.log" "$WORK/23b.log" | tail -6; fail "work create"
fi

say "24: bob lists and views it"
work bob list > "$WORK/24a.log" 2>&1
work bob view -d 1 > "$WORK/24b.log" 2>&1
if grep -q "Fix the parser" "$WORK/24a.log" && grep -qE "Signature : Valid" "$WORK/24b.log" \
      && grep -q "Author    : <$ALICE>" "$WORK/24b.log" && grep -q "drops trailing fields" "$WORK/24b.log"; then
    pass "listed; signature valid, author alice"
else
    sed 's/^/  | /' "$WORK/24a.log" "$WORK/24b.log" | tail -12; fail "work list/view"
fi

say "25: bob comments"
PERMS_CONTENT="Reproduced with a 3-field row." work bob update -d 1 > "$WORK/25a.log" 2>&1
work bob view -d 1 > "$WORK/25b.log" 2>&1
if grep -q "Updates   : 1" "$WORK/25b.log" && grep -q "Reproduced with a 3-field row." "$WORK/25b.log"; then
    pass "comment stored and shown"
else
    sed 's/^/  | /' "$WORK/25a.log" "$WORK/25b.log" | tail -8; fail "work comment"
fi

say "26: bob proposes a document"
PERMS_CONTENT="Add a strict mode." work bob propose -t "Strict mode" > "$WORK/26.log" 2>&1
if [[ -f "$WORKDIR/proposed/2/root" ]] && grep -q "^w:$BOB" "$WORKDIR/2.allowed"; then
    pass "proposal #2, bob owns its rules"
else
    sed 's/^/  | /' "$WORK/26.log" | tail -5; fail "work propose"
fi

say "27: bob tries to edit alice's document"
PERMS_CONTENT="Hijacked." work bob edit -d 1 > "$WORK/27.log" 2>&1
if grep -q "not author" "$WORK/27.log" && grep -aq "drops trailing fields" "$WORKDIR/active/1/root"; then
    pass "refused: not author"
else
    sed 's/^/  | /' "$WORK/27.log" | tail -5; fail "foreign edit"
fi

say "28: alice completes it"
work alice complete -d 1 > "$WORK/28a.log" 2>&1
work alice list --scope completed > "$WORK/28b.log" 2>&1
if [[ -d "$WORKDIR/completed/1" && ! -e "$WORKDIR/active/1" ]] && grep -q "Fix the parser" "$WORK/28b.log"; then
    pass "moved to completed"
else
    sed 's/^/  | /' "$WORK/28a.log" "$WORK/28b.log" | tail -6; fail "work complete"
fi

say "29: alice deletes it"
echo y | work alice delete -d 1 > "$WORK/29.log" 2>&1
if [[ ! -e "$WORKDIR/completed/1" ]] && grep -q "deleted" "$WORK/29.log"; then
    pass "removed"
else
    sed 's/^/  | /' "$WORK/29.log" | tail -5; fail "work delete"
fi

# ---------------------------------------------------------------------------
# The Nomad Network page node, as Nomad Network's browser uses it
pages() {  # pages <name> <requests-json> [identity-file]
    printf '%s' "$2" > "$WORK/$1.json"
    timeout 300 python3 "$HERE/pages_client.py" "$RNS_CONFIG" "$DEST" "$WORK/$1.json" ${3:-} > "$WORK/$1.out" 2> "$WORK/$1.err"
}
response() {  # response <name> <n>: the n-th response line (1-based) of a pages run
    grep -v '"page_node"' "$WORK/$1.out" | sed -n "${2}p"
}

say "30: an anonymous visitor opens the front page"
pages p30 '[{"path": "/page/index.mu"}]'
if response p30 1 | grep -q 'group.mu`g=public\]' && response p30 1 | grep -q "Served by rngit"; then
    pass "group public listed"
else
    sed 's/^/  | /' "$WORK/p30.out" "$WORK/p30.err" | tail -5; fail "front page"
fi

say "31: the visitor opens public/repo"
pages p31 '[{"path": "/page/repo.mu", "data": {"var_g": "public", "var_r": "repo"}}]'
COUNT="$(git -C "$REPO" rev-list --count HEAD 2>/dev/null)"
if response p31 1 | grep -q "rns://$DEST/public/repo" && response p31 1 | grep -q "Commits ($COUNT)"; then
    pass "clone URL and $COUNT commits shown"
else
    sed 's/^/  | /' "$WORK/p31.out" "$WORK/p31.err" | tail -5; fail "repository page"
fi

say "32: the visitor downloads README.md"
pages p32 '[{"path": "/file/download", "data": {"var_g": "public", "var_r": "repo", "var_ref": "HEAD", "var_path": "README.md"}}]'
WANT="$(git -C "$REPO" show HEAD:README.md | xxd -p | tr -d '\n')"
if response p32 1 | python3 -c "import json,sys; r = json.load(sys.stdin); sys.exit(0 if r.get('name') == 'README.md' and r['file'] == '$WANT' else 1)"; then
    pass "file response named README.md with the repository's content"
else
    sed 's/^/  | /' "$WORK/p32.out" "$WORK/p32.err" | tail -5; fail "download"
fi

say "33: bob, identified, fetches the 3 MiB blob as media"
pages p33 '[{"path": "/media", "data": {"key": "k", "path": "/media/public/repo/HEAD/blob.bin"}}]' "$WORK/bob/client_identity"
git -C "$REPO" show HEAD:blob.bin > "$WORK/p33.want"
if response p33 1 | python3 -c "
import json, sys
r = json.load(sys.stdin)
sys.exit(0 if r.get('name') == 'blob.bin' and bytes.fromhex(r['file']) == open('$WORK/p33.want', 'rb').read() else 1)"; then
    pass "3 MiB delivered intact"
else
    head -c 300 "$WORK/p33.out" | sed 's/^/  | /'; sed 's/^/  | /' "$WORK/p33.err" | tail -5; fail "media"
fi

# ---------------------------------------------------------------------------
say "Result"
if [[ $FAILURES -eq 0 ]]; then
    echo "PASS — stock rngit and git-remote-rns against Core's rngit node: 33 of 33."
else
    echo "FAIL — $FAILURES check(s) failed. Logs in $WORK/"
fi
exit $FAILURES
