#!/usr/bin/env python3
#
# A/B test of Core's Nomad Network page node (org.qortal.rngit.RngitPages)
# against the reference rngit pages.py, driven by pages-ab.sh.
#
#   pages_ab.py fixture <dir>                      build the fixture group <dir>/demo
#   pages_ab.py requests <fixture> <out.json>      the requests both sides answer
#   pages_ab.py dump <fixture> <requests> <outdir> answer them with the reference
#
# The reference runs as it does without pygments or a media backend, which is
# how Core renders: no syntax highlighting, no WebP conversion. Every
# repository but "secret" is readable by anyone; nobody has stats.

import hashlib
import json
import os
import struct
import subprocess
import sys
import time
from collections import deque

import RNS
from RNS.vendor import umsgpack as mp

DEST_HEX = "0123456789abcdef0123456789abcdef"
LINK_ID = bytes.fromhex("00112233445566778899aabbccddeeff")
SECRET_READER = "22222222222222222222222222222222"
ENV = dict(os.environ, GIT_CONFIG_GLOBAL="/dev/null", GIT_CONFIG_SYSTEM="/dev/null", TZ=os.environ.get("TZ", "UTC"))


def git(cwd, *args, env=None, input=None):
    e = dict(ENV)
    if env: e.update(env)
    return subprocess.run(["git", *args], cwd=cwd, env=e, check=True, capture_output=True,
                          input=input).stdout


def commit(work, message, author, when, files=None, remove=None):
    for path, content in (files or {}).items():
        full = os.path.join(work, path)
        os.makedirs(os.path.dirname(full), exist_ok=True)
        with open(full, "wb") as fh: fh.write(content if isinstance(content, bytes) else content.encode("utf-8"))
    for path in remove or []: git(work, "rm", "-q", path)
    git(work, "add", "-A")
    date = f"{when} +0200"
    git(work, "commit", "-q", "-m", message, env={"GIT_AUTHOR_NAME": author[0], "GIT_AUTHOR_EMAIL": author[1],
                                                  "GIT_COMMITTER_NAME": author[0], "GIT_COMMITTER_EMAIL": author[1],
                                                  "GIT_AUTHOR_DATE": date, "GIT_COMMITTER_DATE": date})


def signed_commit(work, identity, message, when, author_email=None):
    """A commit signed the way rngit signs: an RSG wrapped as an SSH signature in gpgsig."""
    from RNS.Utilities.rnid import create_rsg
    from RNS.Utilities.rngit.commitsigs import (create_ssh_signature, armor_ssh_signature, get_pubkey_wire_format,
                                                NAMESPACE_GIT, RESERVED_EMPTY, HASH_ALGORITHM)
    email = author_email or identity.hash.hex()
    tree = git(work, "write-tree").decode().strip()
    parent = git(work, "rev-parse", "HEAD").decode().strip()
    body = (f"tree {tree}\nparent {parent}\nauthor Signer <{email}> {when} +0000\n"
            f"committer Signer <{email}> {when} +0000\n\n{message}\n").encode()
    rsg = create_rsg(identity, body)
    armored = armor_ssh_signature(create_ssh_signature(get_pubkey_wire_format(identity), NAMESPACE_GIT,
                                                       RESERVED_EMPTY, HASH_ALGORITHM, rsg))
    lines = armored.strip().split("\n")
    header = "gpgsig " + lines[0] + "".join("\n " + l for l in lines[1:]) + "\n"
    head, rest = body.decode().split("\n\n", 1)
    signed = (head + "\n" + header + "\n" + rest).encode()
    sha = git(work, "hash-object", "-t", "commit", "-w", "--stdin", input=signed).decode().strip()
    git(work, "update-ref", "HEAD", sha)
    git(work, "reset", "-q", "--hard", sha)


README = """# hello

A sample repository for the **page node** A/B test, with *emphasis*, `code`,
a [guide](docs/guide.md), an [anchor](docs/guide.md#usage) and an
[external link](https://example.invalid/x#y).

## Lists

- one
- two with `inline code`
  * nested item
+ plus item
-not a list item
<angle at line start
A backslash \\ and a `tick` here.

> A quote that is long enough to need wrapping across more than one line when the maximum width of one hundred columns applies to it, so it wraps.
> Second line of the quote.

---

```python
def main():
    print("hello")
```

```
plain `block`
	with a tab
```

| Name | Value | Notes |
|:-----|------:|:-----:|
| alpha | 1 | **bold** |
| beta | 22 | a very long cell that will certainly be truncated because the table is much wider than allowed by the renderer and its width |

### Done
"""


def fixture(root):
    group = os.path.join(root, "demo")
    os.makedirs(group)
    work = os.path.join(root, "work")
    os.makedirs(work)
    git(work, "init", "-q", "-b", "master")

    alice = ("Alice", "alice@example.invalid")
    bob = ("Bob", "bob@example.invalid")
    commit(work, "Initial commit", alice, "1700000000", {
        "README.md": README,
        "docs/guide.md": "# Guide\n\n## Usage\n\nSee [the readme](../README.md).\n",
        "docs/page.mu": ">Micron page\n\n`!bold`! text\n",
        "src/main.py": "def main():\n\tprint(\"hello `world`\")\n",
        ".hidden": "hidden\n",
    })
    commit(work, "Add binaries\n\nA longer body.\n\n- with a dash line", bob, "1700100000", {
        "logo.png": b"\x89PNG\r\n\x1a\n\x00\x00\x00\x0dIHDR" + bytes(range(256)),
        "data.bin": bytes(range(256)) * 4,
        "big.txt": ("x" * 99 + "\n") * 3000,
        "latin1.txt": b"caf\xe9\n",
    })
    os.symlink("src/main.py", os.path.join(work, "link"))
    commit(work, "Add a symlink and change main", alice, "1700200000", {
        "src/main.py": "def main():\n\tprint(\"hello, Qortal\")\n\nmain()\n",
    })
    git(work, "tag", "v1.0")
    git(work, "checkout", "-q", "-b", "feature")
    commit(work, "Feature work", bob, "1700300000", {"feature.txt": "feature\n"})
    git(work, "checkout", "-q", "master")
    commit(work, "Remove hidden, change binary", alice, "1700400000",
           {"data.bin": bytes(range(255, -1, -1)) * 4}, remove=[".hidden"])
    git(work, "merge", "-q", "--no-ff", "-m", "Merge feature", "feature",
        env={"GIT_AUTHOR_DATE": "1700500000 +0200", "GIT_COMMITTER_DATE": "1700500000 +0200",
             "GIT_AUTHOR_NAME": "Alice", "GIT_AUTHOR_EMAIL": "alice@example.invalid",
             "GIT_COMMITTER_NAME": "Carol", "GIT_COMMITTER_EMAIL": "carol@example.invalid"})
    git(work, "tag", "-a", "v2.0", "-m", "Release two\n\nWith notes.", env={
        "GIT_COMMITTER_DATE": "1700600000 +0200", "GIT_COMMITTER_NAME": "Alice", "GIT_COMMITTER_EMAIL": "alice@example.invalid"})

    signer = RNS.Identity()
    with open(os.path.join(work, "signed.txt"), "w") as fh: fh.write("signed\n")
    git(work, "add", "-A")
    signed_commit(work, signer, "A signed commit", 1700700000)
    with open(os.path.join(work, "signed.txt"), "w") as fh: fh.write("signed again\n")
    git(work, "add", "-A")
    signed_commit(work, signer, "Signed by someone else", 1700800000, author_email="ff" * 16)

    hello = os.path.join(group, "hello")
    git(root, "clone", "-q", "--bare", work, hello)
    git(hello, "config", "repository.description", "Demo repository")
    with open(hello + ".allowed", "w") as fh: fh.write("r:all\n")

    for name, allowed in (("secret", "r:" + SECRET_READER), ("plain", "r:all")):
        path = os.path.join(group, name)
        git(root, "clone", "-q", "--bare", work, path)
        with open(path + ".allowed", "w") as fh: fh.write(allowed + "\n")
    with open(os.path.join(group, "plain.description"), "w") as fh: fh.write("Described by file\n")

    # A mirror synced two hours ago and a fork of an rns:// source, as rngit records them
    for name, kind, source in (("mirror", "mirror", "https://example.invalid/upstream.git"),
                               ("fork", "fork", "rns://" + "ab" * 16 + "/native/src")):
        path = os.path.join(group, name)
        git(root, "clone", "-q", "--bare", work, path)
        git(path, "config", "repository.rngit.type", kind)
        git(path, "config", "repository.rngit.upstream.source", source)
        if kind == "mirror": git(path, "config", "repository.rngit.upstream.sync", str(int(time.time()) - 7260))
        with open(path + ".allowed", "w") as fh: fh.write("r:all\n")

    # Paging: more commits than a commits page and more files than a tree page holds
    many = os.path.join(root, "many-work")
    os.makedirs(many)
    git(many, "init", "-q", "-b", "main")
    for i in range(105):
        commit(many, f"Commit {i}", alice, str(1700000000 + i * 60), {f"f{i % 7}.txt": f"{i}\n"})
    commit(many, "Many files", alice, "1700010000", {f"dir/file{i:04d}.txt": "x\n" for i in range(1010)})
    path = os.path.join(group, "many")
    git(root, "clone", "-q", "--bare", many, path)
    with open(path + ".allowed", "w") as fh: fh.write("r:all\n")

    # Releases: a published one with artifacts, the latest, and a draft
    releases = hello + ".releases"
    for tag, status, created, notes in (("v1.0", "published", 1700250000, ("RELEASE.md", "# v1.0\n\nFirst **release**.\nMore notes.\n")),
                                         ("v2.0", "published", 1700650000, ("RELEASE.mu", ">v2\n`!Micron`! notes\n")),
                                         ("v3.0", "draft", 1700900000, None)):
        d = os.path.join(releases, tag)
        os.makedirs(os.path.join(d, "artifacts"))
        with open(os.path.join(d, "META"), "w") as fh:
            fh.write(f"tag = {tag}\nhash = {'ab' * 20}\ncreated = {created}\nstatus = {status}\ncreated_by = {'cd' * 16}\n")
        if notes:
            with open(os.path.join(d, notes[0]), "w") as fh: fh.write(notes[1])
        if tag == "v1.0":
            for art, data in (("hello.tar.gz", b"\x1f\x8b" + b"tar" * 300), ("hello.tar.gz.rsg", b"sig")):
                with open(os.path.join(d, "artifacts", art), "wb") as fh: fh.write(data)
    with open(os.path.join(releases, "latest"), "w") as fh: fh.write("v1.0\n")

    # Work documents: a signed active one with an update, a completed micron one, a proposal
    author = RNS.Identity()
    work_dir = hello + ".work"
    content = "# Plan\n\nDo the **thing**.\n"
    docs = (("active", 1, {"content": content, "meta": {"title": "Make the thing work, a title long enough to be cut at ninety-two characters for the list",
                                                        "created": 1700000100.5, "edited": 1700000200.25, "author": author.hash,
                                                        "format": "markdown", "signature": author.sign(content.encode()),
                                                        "identity": author.get_public_key()}}),
            ("completed", 2, {"content": ">Done\n\nAll `!done`!.\n", "meta": {"title": "Finished task", "created": 1690000000.0,
                                                                         "edited": 1690000000.0, "author": author.hash, "format": "micron"}}),
            ("proposed", 3, {"content": "Proposal text\n", "meta": {"title": "A proposal", "created": 1695000000.0, "edited": 0,
                                                                   "author": b"", "format": "markdown",
                                                                   "signature": b"\x00" * 64, "identity": author.get_public_key()}}))
    for scope, doc_id, doc in docs:
        d = os.path.join(work_dir, scope, str(doc_id))
        os.makedirs(d)
        with open(os.path.join(d, "root"), "wb") as fh: fh.write(mp.packb(doc))
    with open(os.path.join(work_dir, "active", "1", "1"), "wb") as fh:
        fh.write(mp.packb({"content": "An *update* comment.", "meta": {"created": 1700000300.0, "author": author.hash, "format": "markdown"}}))
    with open(os.path.join(work_dir, "active", "1", "2"), "wb") as fh:
        fh.write(mp.packb({"content": "`!micron`! comment", "meta": {"created": 1700000400.0, "author": b"", "format": "micron"}}))


def requests(root):
    g = "demo"
    head = git(os.path.join(root, g, "hello"), "rev-parse", "HEAD").decode().strip()
    log = git(os.path.join(root, g, "hello"), "log", "--format=%H", "--all").decode().split()
    out = []

    def page(_path, **data): out.append({"path": _path, "data": {f"var_{k}": str(v) for k, v in data.items()}})

    page("/page/index.mu")
    page("/page/group.mu", g=g)
    page("/page/group.mu", g="nogroup")
    page("/page/group.mu")
    for repo in ("hello", "plain", "secret", "missing"):
        page("/page/repo.mu", g=g, r=repo)
    page("/page/repo.mu", g=g, r="hello", thanks="y")
    page("/page/repo.mu", g=g, r="hello", thanks="y")
    page("/page/repo.mu", g=g, r="hello", ref="v1.0")
    page("/page/repo.mu", g=g)
    for kw in ({}, {"ref": "HEAD"}, {"ref": "HEAD", "path": "src"}, {"ref": "v1.0", "path": "docs/"},
               {"ref": "nosuchref"}, {"ref": "HEAD", "path": "missing"}, {"ref": "HEAD", "path": "README.md"},
               {"ref": "HEAD", "path": "src", "page": "1"}, {"ref": "v2.0"}):
        page("/page/tree.mu", g=g, r="hello", **kw)
    page("/page/tree.mu", g=g, r="secret")
    for path in ("README.md", "docs/guide.md", "docs/page.mu", "src/main.py", "logo.png", "data.bin", "big.txt",
                 "latin1.txt", "link", "src", "missing", "./src/main.py", "../x"):
        page("/page/blob.mu", g=g, r="hello", ref="HEAD", path=path)
    page("/page/blob.mu", g=g, r="hello", ref="HEAD", path="README.md", raw="y")
    page("/page/blob.mu", g=g, r="hello", ref="HEAD", path="docs/page.mu", raw="y")
    page("/page/blob.mu", g=g, r="hello", ref="HEAD", path="src/main.py", render="y")
    page("/page/blob.mu", g=g, r="hello", ref="v1.0", path="src/main.py")
    page("/page/blob.mu", g=g, r="hello", ref="HEAD")
    for kw in ({"ref": "HEAD"}, {"ref": "feature"}, {"ref": "HEAD", "path": "src/main.py"}, {"ref": "HEAD", "page": "1"},
               {"ref": "bogus"}, {"ref": "v2.0"}):
        page("/page/commits.mu", g=g, r="hello", **kw)
    for sha in log + [head[:7], head[:6], "f" * 40]:
        page("/page/commit.mu", g=g, r="hello", ref="HEAD", h=sha)
    tag_object = git(os.path.join(root, g, "hello"), "rev-parse", "v2.0").decode().strip()
    page("/page/commit.mu", g=g, r="hello", ref="HEAD", h=tag_object)
    page("/page/commit.mu", g=g, r="hello")
    for kw in ({}, {"type": "heads"}, {"type": "tags"}):
        page("/page/refs.mu", g=g, r="hello", **kw)
    page("/page/refs.mu", g=g, r="secret")
    page("/page/stats.mu", g=g, r="hello")
    for repo in ("hello", "plain"):
        page("/page/releases.mu", g=g, r=repo)
    for tag in ("v1.0", "v2.0", "v3.0", "latest", "nope"):
        page("/page/release.mu", g=g, r="hello", t=tag)
    page("/page/release.mu", g=g, r="hello", t="v1.0", thanks="y")
    for scope in (None, "active", "completed", "proposed", "all", "bogus"):
        page("/page/work.mu", g=g, r="hello", **({"scope": scope} if scope else {}))
    for doc_id, scope in (("1", None), ("2", None), ("3", None), ("2", "active"), ("9", None), ("x", None), ("1", "completed")):
        page("/page/work_doc.mu", g=g, r="hello", id=doc_id, **({"scope": scope} if scope else {}))
    page("/file/artifact", g=g, r="hello", t="v1.0", a="hello.tar.gz")
    page("/file/artifact", g=g, r="hello", t="latest", a="hello.tar.gz")
    page("/file/artifact", g=g, r="hello", t="v3.0", a="x")
    page("/file/artifact", g=g, r="hello", t="v1.0", a="../META")
    for kw in ({"path": "README.md"}, {"path": "README.md", "fmt": "mu"}, {"path": "docs/guide.md", "fmt": "mu"},
               {"path": "src/main.py", "fmt": "mu"}, {"path": "logo.png"}, {"path": "missing"}):
        page("/file/download", g=g, r="hello", ref="HEAD", **kw)
    page("/file/download", g=g, r="secret", ref="HEAD", path="README.md")
    for doc_id in ("1", "2", "3", "9"):
        page("/file/workdoc", g=g, r="hello", id=doc_id)
    for repo in ("mirror", "fork"):
        page("/page/repo.mu", g=g, r=repo)
    for kw in ({}, {"page": "1"}, {"page": "2"}):
        page("/page/commits.mu", g=g, r="many", ref="HEAD", **kw)
    for kw in ({"path": "dir"}, {"path": "dir", "page": "1"}, {"path": "dir", "page": "x"}):
        page("/page/tree.mu", g=g, r="many", ref="HEAD", **kw)
    page("/page/commits.mu", g=g, r="many", ref="HEAD", path="f3.txt")
    out.append({"path": "/media", "data": {"key": "k", "path": "/media/demo/hello/HEAD/logo.png"}})
    out.append({"path": "/media", "data": {"key": "k", "path": "/media/demo/hello/HEAD"}})
    out.append({"path": "/media", "data": {"path": "/media/demo/hello/HEAD/logo.png"}})
    return out


class Owner:
    """The parts of ReticulumGitNode the page node uses, over the fixture group."""

    def __init__(self, root):
        from RNS.Utilities.rngit.server import ReticulumGitNode
        self.node_class = ReticulumGitNode
        self.PERM_READ = ReticulumGitNode.PERM_READ
        self.PERM_STATS = ReticulumGitNode.PERM_STATS
        group = os.path.join(root, "demo")
        repos = {}
        for name in sorted(os.listdir(group)):
            path = os.path.join(group, name)
            if os.path.isdir(path) and not name.endswith((".releases", ".work")):
                kind, source = self.config(path, "type"), self.config(path, "upstream.source")
                repos[name] = {"path": path, "fork": source if kind == "fork" else None,
                               "mirror": source if kind == "mirror" else None}
        self.groups = {"demo": {"path": group, "repositories": repos}}
        self.blocked_identities = []
        self.destination = type("D", (), {"hash": bytes.fromhex(DEST_HEX)})()

    def resolve_permission(self, remote_identity, group, repo, permission):
        return permission == self.PERM_READ and group in self.groups and repo in self.groups[group]["repositories"] and repo != "secret"

    def resolve_doc_permission(self, remote_identity, group, repo, doc_id, permission):
        return self.resolve_permission(remote_identity, group, repo, permission)

    def releases_list_data(self, path): return self.node_class.releases_list_data(self, path)
    def release_data(self, d, tag): return self.node_class.release_data(self, d, tag)
    def _work_load_document(self, path): return self.node_class._work_load_document(self, path)
    @staticmethod
    def config(path, key):
        result = subprocess.run(["git", "config", f"repository.rngit.{key}"], cwd=path, capture_output=True, text=True)
        return result.stdout.strip() if result.returncode == 0 else None

    def last_upstream_sync(self, path):
        synced = self.config(path, "upstream.sync")
        return int(synced) if synced else 0
    def view_succeeded(self, *a): pass
    def download_succeeded(self, *a): pass
    def release_download_succeeded(self, *a): pass


def reference_node(root, configdir):
    from RNS.Utilities.rngit import pages
    from RNS.Utilities.rngit.highlight import SyntaxHighlighter
    from RNS.Utilities.rngit.util import MarkdownToMicron
    node = pages.NomadNetworkNode.__new__(pages.NomadNetworkNode)
    node.owner = Owner(root)
    node.node_name = "Test Node"
    node.null_ident = RNS.Identity.from_bytes(bytes(64))
    node.templates = {"base": pages.DEFAULT_BASE_TEMPLATE, "front": pages.DEFAULT_FRONT_TEMPLATE,
                      "no_ident": pages.DEFAULT_NO_IDENT_TEMPLATE}
    for name in ("group", "repo", "releases", "release", "tree", "blob", "commits", "commit", "refs", "stats", "work", "work_doc"):
        node.templates[name] = "{PAGE_CONTENT}"
    node.templatesdir = os.path.join(configdir, "templates")
    os.makedirs(node.templatesdir, exist_ok=True)
    node.use_nerdfonts = True
    node.media_conversion = False
    node.highlight_syntax = True
    node.highlighter = SyntaxHighlighter()
    node.highlighter.pygments_available = False
    node.mdc = MarkdownToMicron(max_width=node.MAX_RENDER_WIDTH, syntax_highlighter=node.highlighter)
    node.thanks_deque = deque(maxlen=256)
    # The link the requests arrive on, which file conversions keep their temporary files with
    node.active_links = {LINK_ID: type("Link", (), {})()}
    return node


def dump(root, requests_file, outdir):
    node = reference_node(root, os.path.join(outdir, "config"))
    handlers = {"/page/index.mu": node.serve_front_page, "/page/group.mu": node.serve_group_page,
                "/page/repo.mu": node.serve_repo_page, "/page/tree.mu": node.serve_tree_page,
                "/page/blob.mu": node.serve_blob_page, "/page/commits.mu": node.serve_commits_page,
                "/page/commit.mu": node.serve_commit_page, "/page/refs.mu": node.serve_refs_page,
                "/page/stats.mu": node.serve_stats_page, "/page/releases.mu": node.serve_releases_page,
                "/page/release.mu": node.serve_release_page, "/page/work.mu": node.serve_work_page,
                "/page/work_doc.mu": node.serve_work_doc_page, "/media": node.serve_media,
                "/file/artifact": node.serve_artifact, "/file/download": node.serve_download,
                "/file/workdoc": node.serve_wd_download}
    with open(requests_file) as fh: reqs = json.load(fh)
    for i, req in enumerate(reqs):
        try:
            result = handlers[req["path"]](req["path"], req["data"], b"r" * 16, LINK_ID, None, time.time())
            if isinstance(result, (bytes, bytearray)):
                out = bytes(result)
            elif isinstance(result, list) and len(result) == 2 and hasattr(result[0], "read"):
                out = b"FILE " + result[1]["name"] + b"\n" + result[0].read()
            elif isinstance(result, list) and len(result) == 2 and isinstance(result[0], str):
                out = b"FILE " + result[0].encode() + b"\n" + result[1]
            else:
                out = b"NONE"  # None and False both mean no usable response
        except Exception as e:
            out = f"EXCEPTION {type(e).__name__}".encode()
        with open(os.path.join(outdir, f"{i:03d}.out"), "wb") as fh: fh.write(out)


if __name__ == "__main__":
    cmd = sys.argv[1]
    if cmd == "fixture": fixture(sys.argv[2])
    elif cmd == "requests":
        with open(sys.argv[3], "w") as fh: json.dump(requests(sys.argv[2]), fh, indent=1)
    elif cmd == "dump": dump(sys.argv[2], sys.argv[3], sys.argv[4])
    else: sys.exit(f"unknown command {cmd}")
