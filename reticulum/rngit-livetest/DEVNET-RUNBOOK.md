# rngit on devnet: QDN-backed repositories end to end

Proves the QDN-backed rngit on real nodes:

- **Phase 3 exit test (steps 0–7):** a repository pushed through node **A** is
  cloned through node **B** while **A** is offline, with access granted through
  a published account ↔ RNS identity binding.
- **Push flow 2 (steps 8–10):** a push through **B**, which cannot publish for
  the name, is staged; the name's owner publishes it; every node then serves it.
- **Nomad Network pages (step 12):** the same repository browsed as Nomad
  Network pages through **B**, with **A** offline.
- **ssh upstream (step 13, optional):** **B** mirrors and forks a repository
  from an ssh URL into a local group and syncs the fork.

Paths are written as `~/qortal`, the node directory; on nodes made with
`DEVNET-SETUP.md` substitute that directory (e.g. `/qortal-devnet`), also in
the `ssh devnet-node-N cat qortal/apikey.txt` commands.

Everything here is manual. Each step says what to run and what to check;
stop at the first check that fails and see *Troubleshooting*.

## Roles

| Role | Where | What |
|---|---|---|
| **A** | `devnet-node-1` | rngit node with a `publisher_key`; publishes for the name `devgit`. Reticulum gateway (backbone server) |
| **B** | `devnet-node-2` | rngit node without a key: QDN gateway that stages permitted pushes. Second Reticulum gateway, so the laptop reaches B while A is offline |
| **C** (optional) | `devnet-node-3` | second read-only gateway, to show B alone serves the data |
| **Publisher account P** | key on A | owns the name `devgit`, pays the fees |
| **Developer account U** | your Hub/wallet | owns the name `devuser`; binds your RNS identity |
| **Laptop** | you | Python RNS, `git`, `rngit`, `git-remote-rns` |

P and U can be the same account if you only have one with QORT, but then
step 4 no longer tests that a *different* account's binding grants access.

You need: names `devgit` (owned by P) and `devuser` (owned by U) registered
on devnet, and a few QORT on P (each publish is a transaction at the unit
fee, currently 0.01 QORT: create = 1, each push = 2, publishing a staged push
= 2) and on U (binding = 1).

## 0. Build and deploy

```bash
cd ~/git/qortal-claude
./ideploy_jar_devnet.sh
```

**Check:** the jar on the nodes is built from `reticulum-git` at or after
commit `9d7a2c18` (`git log --oneline -1`), which adds the page node (step 12).
Steps 0–11 need only `7c47c1ff` (staging).

## 1. Enable rngit on A and B

On **A** and **B** (and **C**), add to `~/qortal/settings.json`:

```json
"rngitEnabled": true
```

(`rngitConfigPath` defaults to `rngit`, i.e. `~/qortal/rngit`.) Restart Core.

**Check** in `~/qortal/qortal.log` on each node:

```
rngit QDN gateway enabled: every registered name is a repository group
Reticulum Git Node "Anonymous Git Node" listening on <DEST>
```

Note each node's `<DEST>`; below they are `DEST_A`, `DEST_B`, `DEST_C`.
The first start also wrote `~/qortal/rngit/config`, `repositories_identity`
and `client_identity`.

In `~/qortal/rngit/config` on every node, set a short announce interval
(minutes) and restart Core again:

```ini
[rngit]
  announce_interval = 10
```

The default, 360, suits a large mesh but not a test with restarts. A gateway
that restarts gets its paths back from its saved path table and, for nodes
connected to it as backbone clients, from their tunnels when they reconnect.
It does not search for paths it lost anyway (neither does the reference), for
example ones learned after its last save before a hard kill, and relearns them
only from the next announce. A devnet made with `make-devnet.sh` already has
this if its `rngit-config` was copied in before the first start
(`DEVNET-SETUP.md`).

### B as a second Reticulum gateway

A is the gateway the other nodes and the laptop connect to. With only A, the
laptop has no route to B or C while A is offline (steps 7, 8 and 12). A devnet
made with `make-devnet.sh` already has B as a gateway and C connected to both;
then only do the check below. Otherwise make B a gateway as well: in B's
`~/qortal/settings.json` set

```json
"reticulumHasServerInterface": true,
"reticulumPort": 4232
```

with the same port as A's `reticulumPort`, and keep A in
`reticulumBackboneGatewayServers`, so B stays a client of A. Optionally add B to
C's `reticulumBackboneGatewayServers` (e.g. `"devnet-node-2:4232"`), so the
laptop also reaches C while A is offline (step 7, clone through C). Restart
Core on B (and C). With `reticulumRegenerateConfigOnRestart` (the default),
Core rewrites `~/qortal/.reticulum/config.yml`.

**Check** on B: `qortal.log` shows `BackboneServerInterface [...] listening on
0.0.0.0:4232`, and from the laptop `nc -vz devnet-node-2 4232` connects. If
`nc` fails while B listens, open the port in B's firewall; if B is a VM behind
NAT (a private address on its interface, `ip -br addr`), forward the port on
the NAT host instead, as is done for its ssh port.

## 2. Laptop: connect to the devnet mesh

Read A's `~/qortal/.reticulum/config.yml`. You need, from its
`BackboneServerInterface` section: `listen_port`, `network_name` and, if set,
`passphrase`. Below: `GW_PORT`, `NET_NAME`, `PASSPHRASE`. B's gateway (step 1)
uses the same values.

Create `~/rngit-devnet/rns/config` on the laptop, with one interface per
gateway:

```ini
[reticulum]
  enable_transport = False
  share_instance = No

[logging]
  loglevel = 4

[interfaces]
  [[devnet gateway A]]
    type = TCPClientInterface
    interface_enabled = True
    target_host = devnet-node-1
    target_port = GW_PORT
    network_name = NET_NAME
    passphrase = PASSPHRASE   # omit the line if the gateway has none

  [[devnet gateway B]]
    type = TCPClientInterface
    interface_enabled = True
    target_host = devnet-node-2
    target_port = GW_PORT
    network_name = NET_NAME
    passphrase = PASSPHRASE
```

```bash
export RNS_CONFIG=~/rngit-devnet/rns
export RNGIT_CONFIG=~/rngit-devnet/client
mkdir -p $RNGIT_CONFIG
rnstatus --config $RNS_CONFIG     # both interfaces should show as Up
rnpath --config $RNS_CONFIG DEST_A
```

With both gateways up, Reticulum uses the shorter path (B directly is one hop,
B through A two); when A is offline its interface goes down and everything
runs through B.

**Check:** `rnpath` finds a path to `DEST_A`, `DEST_B` and `DEST_C`. If it
times out for all of them, the interface access code (network name/passphrase)
or the port is wrong; see *Troubleshooting*.

`DEST_A` is on the gateway itself, which answers for it. `DEST_B` and `DEST_C`
are answered by the gateway from its path table, which needs a Reticulum
library with the path-request fix (Java library `eb31baa`, Core pom at or after it).
With an older jar on the gateway, only `DEST_A` resolves; as a workaround, set
`announce_interval = 1` under `[rngit]` in `~/qortal/rngit/config` on B and C
and restart them while the laptop is connected: the laptop then learns their
paths from their announces, which the gateway forwards.

### Your rngit client identity

`rngit` and `git-remote-rns` create `$RNGIT_CONFIG/client_identity` on their
first operation, but step 3 needs it before that. Create it now, in the shell
where `RNGIT_CONFIG` is exported (the command creates it if missing and prints
its hash either way):

```bash
python3 -c "import RNS,os; p=os.path.expanduser('$RNGIT_CONFIG/client_identity'); \
i=RNS.Identity.from_file(p) if os.path.exists(p) else RNS.Identity(); i.to_file(p); print(i.hash.hex())"
ls -l $RNGIT_CONFIG/client_identity
```

Below this hash is `MY_RNS`. Keep `RNS_CONFIG` and `RNGIT_CONFIG` exported in
every shell you use for the following steps: with `RNGIT_CONFIG` unset, step
3's `-i $RNGIT_CONFIG/client_identity` reads `/client_identity`, and the git
commands use `~/.rngit` with a different identity.

## 3. Publish your binding (developer account U)

On the laptop, bind your RNS identity to U:

```bash
cd ~/git/qortal-claude/reticulum/rngit
./rns-identity-binding.py create --address <U's address> -i $RNGIT_CONFIG/client_identity > ~/rngit-devnet/binding.json
./rns-identity-binding.py verify --address <U's address> ~/rngit-devnet/binding.json
```

**Check:** `verify` prints `valid <MY_RNS>`.

Publish it through any node's API, e.g. A through an SSH tunnel:

```bash
ssh -N -L 12391:localhost:12391 devnet-node-1 &
ssh devnet-node-1 cat qortal/apikey.txt > ~/rngit-devnet/apikey.txt
test -s ~/rngit-devnet/apikey.txt || echo 'API key file is empty: check the node directory path'
./rns-identity-binding.py publish --name devuser --api-key-file ~/rngit-devnet/apikey.txt ~/rngit-devnet/binding.json
# prompts for U's Base58 private key
```

**Check:** prints `Submitted: true` (or the transaction). After the next
block:

```bash
curl -s "http://localhost:12391/arbitrary/resource/status/JSON/devuser/rns-identity"
```

shows the resource as known (e.g. `PUBLISHED`/`READY`).

## 4. Make A the publisher for `devgit`

On **A**:

```bash
echo '<P Base58 private key>' > ~/qortal/rngit/publisher_key
chmod 600 ~/qortal/rngit/publisher_key
```

and in `~/qortal/rngit/config`, under `[access]`, let the owner of `devuser`
create repositories under `devgit`:

```ini
[access]
  devgit = c:name:devuser
```

Restart Core on A.

**Check** in A's `qortal.log`:

```
rngit QDN publishing enabled for names owned by <P's address>
```

## 5. Create a repository and push through A

```bash
rngit create --config $RNGIT_CONFIG --rnsconfig $RNS_CONFIG rns://DEST_A/devgit/hello
```

**Check:** `Repository devgit/hello created`. In A's `qortal.log`:
`Creating QDN repository devgit/hello` and `Published QDN resource devgit/hello`.

This step exercises the binding: you were allowed to create because
`c:name:devuser` resolved, through U's binding, to `MY_RNS`. If it says
*Not allowed*, see *Troubleshooting*.

Push a repository with a few commits:

```bash
mkdir -p ~/rngit-devnet/hello && cd ~/rngit-devnet/hello
git init -b master && echo hello > README.md && git add . && git commit -m "first"
head -c 2000000 /dev/urandom > blob.bin && git add . && git commit -m "2 MB blob"
git remote add origin rns://DEST_A/devgit/hello
git push origin master
```

**Check:** the push succeeds. Within a few seconds A's `qortal.log` shows
`Published devgit/hello to QDN: 1 refs, new bundle hello~b~1`. After the
next block:

```bash
curl -s "http://localhost:12391/arbitrary/resources/search?service=GIT_REPOSITORY&name=devgit"
```

lists `hello` (the descriptor) and `hello~b~1`.

## 6. Clone through B while A is online

```bash
cd ~/rngit-devnet && rm -rf clone-b1
git clone rns://DEST_B/devgit/hello clone-b1
```

**Check:** the clone matches (`git -C clone-b1 log --oneline` shows both
commits; `cmp clone-b1/blob.bin hello/blob.bin`). B's `qortal.log` shows
`Materialised QDN repository devgit/hello (1 bundles, 1 refs)`.

B now holds the data in its own QDN storage. Confirm on B:

```bash
curl -s "http://localhost:12391/arbitrary/resource/status/GIT_REPOSITORY/devgit/hello~b~1"
```

shows it as downloaded/ready on B.

## 7. Exit test: A offline

Stop Core on **A** (`./stop.sh`). The laptop now reaches B only through B's
gateway (step 1): `rnstatus --config $RNS_CONFIG` shows `devnet gateway A` down
and `devnet gateway B` up, and `rnpath --config $RNS_CONFIG DEST_B` still
resolves. On **B**, delete the rngit cache so the next clone rebuilds from QDN,
not from the cache:

```bash
rm -rf ~/qortal/rngit/qdn-cache
```

Then:

```bash
cd ~/rngit-devnet && rm -rf clone-b2
git clone rns://DEST_B/devgit/hello clone-b2
```

**Check (exit criterion):** the clone succeeds with A offline and matches
`hello/`. B's `qortal.log` shows a new `Materialised QDN repository devgit/hello`.

Optional, stronger: clone through **C**, which has never seen the repository,
so it must fetch the QDN data from B:

```bash
git clone rns://DEST_C/devgit/hello clone-c
```

This depends on B serving that data to C (B's storage policy) and can take a
minute while C fetches; the gateway waits up to 60 s per resource. With A
offline the laptop reaches `DEST_C` only if C is also a client of B's gateway
(step 1).

## 8. Push flow 2: a push through B is staged

B cannot publish for `devgit`, but the repository's rules (in its descriptor:
`adm:MY_RNS` from step 5) let you write, so B stages the push. With A still
offline:

```bash
cd ~/rngit-devnet/clone-b2 && echo more >> README.md && git commit -am "staged change"
git push origin master        # origin is DEST_B
```

**Check:** git reports the ref as rejected with
`Staged as #1, awaiting the owner's approval` and exits non-zero; this is
intended, as the repository has not changed yet. B's `qortal.log` shows
`Staged change #1 to devgit/hello by MY_RNS: bundle refs/heads/master`.

List it through B's API (a second tunnel, to B):

```bash
ssh -N -L 12491:localhost:12391 devnet-node-2 &
curl -s http://localhost:12491/git/devgit/hello/staged
```

**Check:** one entry, `"id":1`, `"kind":"bundle"`, `"applies":true`, `"base"`
= the current `master`, `"sha"` = your new commit (`git -C ~/rngit-devnet/clone-b2 rev-parse HEAD`).

A push by an identity the rules do not allow is refused outright, not
staged. Optional check with a second, unbound identity:

```bash
RNGIT_CONFIG=~/rngit-devnet/stranger git push origin master   # creates a new client identity
```

**Check:** `Not allowed` (or `refused list`), and the staged list is unchanged.

## 9. The owner publishes the staged push

Fetch the prepared resources from B:

```bash
curl -s http://localhost:12491/git/devgit/hello/staged/1/publish > ~/rngit-devnet/staged-1.json
python3 -c "import json; d=json.load(open('$HOME/rngit-devnet/staged-1.json')); print([r['identifier'] for r in d['resources']])"
```

**Check:** prints `['hello~b~2', 'hello']`: a bundle with your commit's
objects, then the updated descriptor. (`hello~b~N` with N = 1 + the bundles
already published; no bundle at all if the change brought no new objects.)

**With Hub (the intended path):** in a Q-App running as **P**, pass the
resources unchanged:

```js
const prepared = await (await fetch("/git/devgit/hello/staged/1/publish")).json();
await qortalRequest({ action: "PUBLISH_MULTIPLE_QDN_RESOURCES", resources: prepared.resources });
```

**Without a Q-App (for this test):** build, sign and process each resource
through B's API with P's key. B stores the data and keeps serving it while A
is offline:

```bash
ssh devnet-node-2 cat qortal/apikey.txt > ~/rngit-devnet/apikey-b.txt
test -s ~/rngit-devnet/apikey-b.txt || echo 'API key file is empty: check the node directory path'
python3 - ~/rngit-devnet/staged-1.json <<'PYEOF'
import getpass, json, os, sys, urllib.parse, urllib.request
api = "http://localhost:12491"
key = open(os.path.expanduser("~/rngit-devnet/apikey-b.txt")).read().strip()
if not key: sys.exit("~/rngit-devnet/apikey-b.txt is empty: B answers 403 without an API key")
prepared = json.load(open(sys.argv[1]))
private_key = getpass.getpass("Base58 private key of P (owner of devgit): ").strip()

def call(path, body, content_type="text/plain"):
    req = urllib.request.Request(api + path, data=body.encode(), method="POST",
                                 headers={"Content-Type": content_type, "X-API-KEY": key})
    with urllib.request.urlopen(req, timeout=300) as response:
        return response.read().decode().strip()

for r in prepared["resources"]:   # bundle first, then the descriptor
    query = urllib.parse.urlencode({"filename": r["filename"], "title": r["title"], "fee": 1000000})
    path = "/arbitrary/%s/%s/%s/base64?%s" % (r["service"], urllib.parse.quote(r["name"], safe=""),
                                             urllib.parse.quote(r["identifier"], safe=""), query)
    unsigned = call(path, r["data64"])
    signed = call("/transactions/sign", json.dumps({"privateKey": private_key, "transactionBytes": unsigned}),
                  "application/json")
    print(r["identifier"], "->", call("/transactions/process", signed))
PYEOF
```

**Check:** both lines end in `-> true`.

## 10. Every node serves the published change

After the next block:

```bash
curl -s http://localhost:12491/git/devgit/hello/staged      # []
cd ~/rngit-devnet && rm -rf clone-b3 && git clone rns://DEST_B/devgit/hello clone-b3
git -C clone-b3 log --oneline -1                             # "staged change"
```

**Check:** the staged list is empty (published changes are cleared), and the
new clone has your commit. With **C**: `git clone rns://DEST_C/devgit/hello`
shows it too. Start A again: a clone through A shows it as well (A rebuilds
its cache because the descriptor it published is no longer the latest).

Optional, conflicts: stage two pushes from two clones of the same commit
(each fast-forwards on its own); publish the first as in step 9. **Check:**
`/staged` now shows the second with `"applies":false`, and its `publish`
answers `Staged change #N no longer applies: ...`.

## 11. Optional: compaction

On A set `[qdn] compact_after = 2`, restart, and push three more commits
through A. **Check:** A's log shows
`Compacting devgit/hello: 2 bundles replaced by hello~b~N`; a fresh clone
through B (after deleting B's `qdn-cache`) still matches.

## 12. Nomad Network pages through B

The page node serves the repositories as Nomad Network pages, on the
`nomadnetwork.node` destination of B's rngit identity. It follows the same
permissions as git and reads QDN repositories from B's cache, so it works with
**A** still offline.

On **B**, add to `~/qortal/rngit/config`:

```ini
[pages]
  serve_nomadnet = yes
```

Restart Core on B.

**Check** in B's `qortal.lg`:

```
Git Nomad Network Node listening on <PAGE_B>
```

`PAGE_B` is the page node's destination; it differs from `DEST_B`. B
announces it about 5 s after start, and then every `announce_interval`
minutes, 360 by default.

**With Nomad Network (the intended path):** on the laptop, run
`nomadnet --rnsconfig $RNS_CONFIG`.

1. Open *[ Network ]* and press Ctrl-L to switch to the announce stream.
2. B shows up under *Nodes* as "Anonymous Git Node", or as its `node_name` if
   one is set. Select it and choose *Connect*.

To open a page by address instead, press Ctrl-U in the browser pane. The
dialog starts with the current address, so clear it with Ctrl-L before typing
`PAGE_B:/page/index.mu`. Downloads are saved to `~/Downloads`, or to
`downloads_path` in Nomad Network's config.

1. **Front page.** Configured groups are listed; there are none on B, so it
   says *No groups available*. Below that is the *Qortal Names* box. Enter
   `devgit` and choose *Open*.
   **Check:** the group page lists `hello`.
2. **Repository page.** Open `hello`.
   **Check:**
   - The page shows `rns://DEST_B/devgit/hello` in the breadcrumb.
   - It shows `Commits (3)`: two from step 5 and the staged change from step 10.
     If you ran step 11, it shows `Commits (6)`.
   - It shows the README, `hello` and `more`.
3. **Files and commits.** Open *Files* and then `README.md`. Open *Commits*
   and then the "staged change" commit.
   **Check:** the commit page shows the diff adding `more`.
4. **Download.** In the blob view of `blob.bin`, choose *Download*.
   **Check:** the download is a 2 MB file, identical to `hello/blob.bin`.

**Without Nomad Network (scripted):** use the client the live test uses. It
links to the page node of the rngit node with the given destination:

```bash
cd ~/git/qortal-claude/reticulum/rngit-livetest
cat > ~/rngit-devnet/pages.json <<'EOF'
[{"path": "/page/group.mu", "data": {"var_g": "devgit"}},
 {"path": "/page/repo.mu", "data": {"var_g": "devgit", "var_r": "hello"}},
 {"path": "/file/download", "data": {"var_g": "devgit", "var_r": "hello", "var_ref": "HEAD", "var_path": "README.md"}}]
EOF
python3 pages_client.py $RNS_CONFIG DEST_B ~/rngit-devnet/pages.json
```

**Check:** the output is one JSON line per request, after a `page_node` line
that shows `PAGE_B`:
- The group page contains `r=hello`.
- The repository page contains `Commits (3)`, or `Commits (6)` after step 11.
- The download is `"name": "README.md"`, with your README as hex.

The client takes a fourth argument, a client identity file, to identify on the
link before requesting. Only then do pages that need permissions beyond
`r:all` show anything; QDN repositories are public, so they don't.

Optional, requiring identification: add the null identity
`d7db22f63b453c23bb0688dde565b7c1` to `blocked_identities` under `[rngit]` on
B and restart.

**Check:** without identifying, every page says *No Identity*. Nomad Network
connects anonymously until told otherwise, per node:

1. In the browser, press Ctrl-S and choose *Save* to add B to *Known Nodes*.
2. Select B in *Saved Nodes* and press Ctrl-E.
3. Tick *Identify when connecting* and choose *Save*.
4. Disconnect (Ctrl-W) and connect to B again.

**Check:** the pages appear again. Remove the entry from `blocked_identities`
afterwards.

## 13. Optional: ssh upstream (mirror, fork, sync)

Forks and mirrors live in local groups (directories under `[repositories]`);
QDN groups refuse them. For an `ssh://` source, Core runs the `git` command as
its own user, so B needs `git` and a passphrase-less ssh key that reaches the
upstream host without a prompt. Here the upstream is a bare repository on A.

On **A**, create the upstream with two branches:

```bash
mkdir -p ~/rngit-upstream && rm -rf /tmp/up-src && git init -q -b main /tmp/up-src && cd /tmp/up-src
echo "ssh upstream on A" > README && git add README && git commit -qm "upstream: first commit"
git checkout -qb side && echo side > side.txt && git add side.txt && git commit -qm "upstream: side branch"
git checkout -q main && git clone -q --bare /tmp/up-src ~/rngit-upstream/hello.git && cd && rm -rf /tmp/up-src
```

On **B**, as the user Core runs as, check that ssh works non-interactively
(`BatchMode`: a host-key or password prompt fails instead of hanging):

```bash
git --version
GIT_SSH_COMMAND="ssh -o BatchMode=yes" git ls-remote --symref ssh://USER@devnet-node-1:SSH_PORT/home/USER/rngit-upstream/hello.git
```

**Check:** `ref: refs/heads/main HEAD`, then `main` and `side`. If not, add a
key (`ssh-keygen`, `ssh-copy-id`) and accept A's host key once by hand.

In B's `~/qortal/rngit/config`, add a local group that you may create
repositories in (`MY_RNS` from step 2), and restart Core on B:

```ini
[repositories]
  local = /home/USER/qortal/rngit/groups/local

[aliases]
  laptop = MY_RNS

[access]
  local = r:all, c:laptop
```

```bash
mkdir -p ~/qortal/rngit/groups/local
```

On the **laptop**:

```bash
SRC=ssh://USER@devnet-node-1:SSH_PORT/home/USER/rngit-upstream/hello.git
B=rns://DEST_B
RG="--config $RNGIT_CONFIG --rnsconfig $RNS_CONFIG"
rngit mirror $RG $SRC $B/local/hello-mirror      # Repository mirrored to local/hello-mirror
rngit fork $RG $SRC $B/local/hello-fork          # Repository forked to local/hello-fork
git clone $B/local/hello-mirror clone-mirror
git -C clone-mirror branch -a
```

**Check:** both commands report success; the clone has the `README` and
`origin/main` and `origin/side`, with `origin/HEAD -> origin/main`. On B,
`git -C ~/qortal/rngit/groups/local/hello-mirror config repository.rngit.type`
prints `mirror` (`fork` for `hello-fork`).

Advance the upstream on **A**:

```bash
cd /tmp && git clone -q ~/rngit-upstream/hello.git up && cd up && echo two >> README \
  && git commit -qam "upstream: second" && git push -q origin main && cd .. && rm -rf up
```

and sync the fork from the **laptop**:

```bash
rngit sync $RG $B/local/hello-fork               # Repository synced
```

**Check:** on B, `git -C ~/qortal/rngit/groups/local/hello-fork log --oneline -1`
shows `upstream: second`. Mirrors also sync by themselves every
`mirror_interval` hours (`[rngit]`, default 24); `rngit sync` on the mirror
does it now.

A failing source:

```bash
rngit mirror $RG ssh://USER@devnet-node-1:SSH_PORT/nonexistent.git $B/local/bad
```

**Check:** `Server error: Failed to fetch from source: fatal: '/nonexistent.git'
does not appear to be a git repository ...`, and no `local/bad` on B.

## Cleanup

- `rngitEnabled: false` (or remove it) in `settings.json` disables rngit.
- Remove `[pages]` (or set `serve_nomadnet = no`) to stop serving pages.
- `~/qortal/rngit/qdn-cache` can be deleted at any time.
- Remove `~/qortal/rngit/publisher_key` from A when done; it is a private key.
- Step 13: remove the `local` group, alias and access lines from B's
  `rngit/config`, `~/qortal/rngit/groups/local` on B and `~/rngit-upstream` on A.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| `rnpath` / `Requesting path...` times out | Laptop interface: wrong `target_port`, or `network_name`/`passphrase` differ from the gateway (interface access codes must match). |
| `rnpath` finds the others but times out for one rngit node, after the gateway restarted | The gateway lost that path: it was not in its saved path table or the node's tunnel (a hard kill skips the save on exit, and with a Reticulum library before `1dbe362` a restarted gateway restored no paths at all), and the gateway relearns it only from the next announce. Restart Core on that node (rngit announces about 5 s after start), and set a short `announce_interval` (step 1). |
| With A offline, `rnpath` times out for `DEST_B` | B is not a gateway, or the laptop config has no `devnet gateway B` interface, or B's firewall blocks the port (step 1, step 2). |
| With A offline, `rnpath` times out for `DEST_C` only | C is a client of A alone; add B to C's `reticulumBackboneGatewayServers` (step 1). |
| `rnpath` finds `DEST_A` but times out for `DEST_B`/`DEST_C` | The gateway's Reticulum library predates the path-request fix and never answers for destinations on other nodes; see the workaround in step 2. |
| `rns-identity-binding.py create` cannot load `client_identity` | Step 2's identity command was not run, or `RNGIT_CONFIG` is not exported in this shell. |
| `rngit mirror`/`fork` with an `ssh://` source fails with `Host key verification failed` or `Permission denied` | Core's user on B has no key for the upstream, or the host is not in its `known_hosts`; run step 13's `ls-remote` check on B as that user. `ssh sources need the git command` means `git` is not installed on B. |
| `rngit mirror`/`fork` answers `Not supported for QDN repositories yet` | The target group is a Qortal name; forks and mirrors need a local `[repositories]` group (step 13). |
| `rngit mirror`/`fork` answers `Not found` or `Not allowed` | The local group's directory is missing, or `[access]` gives your identity no `c` there. |
| `rngit QDN gateway disabled` in log | Core's repository was not available when rngit started; check the log above it. |
| No `QDN publishing enabled` line on A | `publisher_key` missing, unreadable, or not a Base58 32-byte key (see the error logged). |
| `create` says *Not allowed* | The binding does not resolve: check step 3's resource exists, was published by **U** (the owner of `devuser`), and lists `MY_RNS`; bindings are cached for 5 minutes, so restart A or wait. As a fallback, `devgit = c:MY_RNS` in `[access]` tests everything else. |
| `create` says *Could not publish repository* | P lacks QORT for the fee, or P does not own `devgit`; the reason is in A's log (`QDN publish ... invalid: ...`). |
| Push succeeds but no `Published ... to QDN` | Publishing runs after the ack; look for `Publishing devgit/hello to QDN failed` in A's log. The next push retries. |
| Push through B says `Not allowed` instead of staging | The descriptor's rules do not grant you `w`/`adm` (check `GET /git/devgit/hello` on any node and A's rules via `rngit perms`), or B's jar predates `7c47c1ff`. |
| `/staged/1/publish` says *no longer applies* | The repository moved on since staging and the change no longer fast-forwards; push again (rebased) through B. |
| Step 9 publish fails `INSUFFICIENT_FEE`/`INVALID_NAME_OWNER` | P lacks QORT, or the key is not the owner of `devgit`. |
| No `Git Nomad Network Node listening` line on B | `[pages]` is missing, or `serve_nomadnet` is misspelled in B's rngit config, or the jar predates `9d7a2c18`. |
| Nomad Network does not list B, or `pages_client.py` waits for a path | The page node announced before the laptop connected. Open `PAGE_B:/page/index.mu` directly; the client requests a path itself. Otherwise restart B, or set a short `announce_interval` under `[rngit]`. |
| Group page says *Group Not Found* for `devgit` | B cannot read the name's repositories from QDN: check that `GET /git/devgit` on B lists `hello`. |
| Clone through B hangs, then *Not found* | B could not fetch the QDN data within 60 s per resource; check B has peers and the resource status on B. Retry once the data has arrived. |
