# Qortal Auto-Update Publisher Scripts

This toolkit modernizes and automates the Qortal auto-update process. It includes:

- A Bash script (`build-auto-update.sh`) to build and push the update
- A Python script (`publish-auto-update.py`) to publish the auto-update transaction
- Full support for dry-run mode, interactive or scripted use, and secure key input

---

## 🧰 Prerequisites

- You must be a **non-admin member** of the Qortal `dev` group
- A Qortal core node must be running locally (default API port: `12391`)
- You need the latest version of the `qortal` repo cloned locally

---

## 🚀 Workflow Overview

### 1. Run the Build Script

This script:
- Auto-increments the version in `pom.xml`
- Rebuilds the JAR file
- XORs it into a `.update` file
- Creates a new `auto-update-<hash>` branch with only the update
- Pushes it to the repo

```bash
./tools/auto-update-scripts/build-auto-update.sh
```

You'll be prompted to:
- Confirm or modify the version number
- Push the version tag and update branch, and final commit.
- Optionally run the publisher script at the end

> ✅ Dry-run mode is supported to preview the full process.

---

### 2. Publish the Auto-Update

You can either:
- Let the build script call it for you
- Or run it manually:

```bash
# Run manually with interactive key prompt and auto-detected latest update:
python3 tools/auto-update-scripts/publish-auto-update.py

# Or specify a commit hash:
python3 tools/auto-update-scripts/publish-auto-update.py 0b37666d

# Or pass both from another script:
python3 tools/auto-update-scripts/publish-auto-update.py <privkey> <commit_hash>
```

> 🔐 Private key is always prompted securely unless passed explicitly (e.g. from automation).

This script will:
- Detect the latest `auto-update-<hash>` branch (or use the one you specify)
- Validate that the commit exists
- Require the matching `origin/auto-update-<hash>` commit
- Compute SHA256 from that commit's `.update` blob, ignoring unrelated local files
- Build and sign the transaction
- Submit it to your local node

> ✅ `--dry-run` is supported to show what would happen without sending anything.

---

## 🛠 Advanced Options

- Log files are created in `~/qortal-auto-update-logs` by default
- You can override the log directory interactively
- Branch naming is standardized: `auto-update-<short-commit-hash>`
- The `.update` file is XOR-obfuscated using Qortal’s built-in logic
- Your commit must already exist on the main repo (e.g. via push or PR merge)

---

## 📌 Notes

- **Do not use Git LFS** — Qortal nodes download `.update` files using raw HTTP from GitHub
We may build LFS support in the future, but for now it is NOT utilized, and will NOT work. 
(Other locations for the publish of the .update file will be utilized in the future, 
preferably utilizing QDN via gateway nodes, until auto-update setup can be re-written to
leverage QDN directly.) 
- GitHub will warn if `.update` files exceed 50MB, but auto-update still works.
(In the past there HAVE been issues with accounts getting banned due to publish of .update file,
however, as of recently (April 2025) it seems they are only warning, and not banning. But we 
will be modifying the need for this in the future anyway.) 
- Update mirrors will be added in the future, and others can be added in settings as well.

---

## ✅ Example End-to-End (Manual)

```bash
cd ~/git-repos/qortal
./tools/auto-update-scripts/build-auto-update.sh
# follow prompts...

# then manually publish:
python3 tools/auto-update-scripts/publish-auto-update.py
```

---

## 🧪 Test Without Sending

```bash
./build-auto-update.sh   # enable dry-run when prompted
# OR
python3 publish-auto-update.py 0b37666d --dry-run
```

---

## 🙌 Contributors

Modernization by [@crowetic](https://github.com/crowetic) 
Based on original Perl scripts by Qortal core devs, specifically @catbref.

---

Questions or issues? Drop into the Qortal Dev group on Discord, Q-Chat,  or reach out directly via Q-Mail to 'crowetic'.

## Script review (2026-10-28)

Both build helpers create the orphan update commit using
`tools/create-auto-update-branch.sh`. It uses a temporary Git index, keeps the
source checkout and staged files intact, refuses to overwrite an existing update
branch, and uses a normal push. A failed push leaves the local update branch for
inspection and retry. The interactive builder writes Maven output beside its
main log and does not push release refs until the build succeeds. Its global
dry run never invokes the publisher. The Python publisher's dry run needs no
private key and never signs or broadcasts; requests during a real publication
use a 30-second timeout and the repository's `apikey.txt` when present.

`generate-release-notes.sh <version>` uses the specified release tag for source,
downloaded scripts and timestamps. It packages a temporary directory containing
only the JAR, logging configuration, start/stop scripts, `qort`, and a default
`{}` settings file. It does not recursively archive or timestamp the supplied
Core directory. Existing JARs and scripts supplied by the operator still need to
be checked against the intended release. `tools/build-zip.sh` likewise stages
only committed runtime files and one built JAR, includes `qort`, and retains the
previous ZIP if packaging fails.

The review covered all tracked shell scripts, executable helpers, Python and
Perl scripts, both Windows NTP batch files, Docker entrypoints and their build
configuration. Lifecycle fixes include PID validation, a bounded shutdown wait,
correct testnet defaults and protection against ambiguous JAR selection. Use
`./stop.sh --port <port>` for a custom API port and
`./stop.sh --timeout <seconds>` to override the default 120-second process wait.
A timed-out shutdown retains `run.pid` for investigation; it does not escalate
to SIGKILL. On Linux, signal fallback also checks the process working directory.
On other platforms it checks the PID and Java command line; directory identity
cannot be established with `/proc` there.

Offline verification:

```bash
python3 -m unittest discover -s tools/tests -p 'test_*.py' -v
```

The tests use disposable Git repositories, fake process/API responses and local
HTTP fixtures. Packaging tests require `7z` and `jq`; publisher tests require
Python `requests`. No running Core, wallet or public transaction is used.

Further improvements identified by static review, outside this maintenance pass:

- `tools/qdn`: encode names and metadata as URL components, validate every
  response and propagate HTTP/broadcast failures. Its existing error-substring
  checks and unescaped query construction are fragile.
- `tools/approve-*.sh`: add API-key authentication, parse JSON structurally and
  consistently fail on rejected transactions. These older signing flows need
  dedicated fixtures before changing transaction behavior.
- `tools/tx.pl` and `tools/publish-auto-update.pl`: replace shell-built commands,
  validate responses and serialize JSON safely. Prefer the updated Python
  auto-update publisher for that workflow. Perl runtime checks require the
  optional JSON, Crypt::RIPEMD160 and File::Slurp modules.
- `tools/block-timings.sh`: validate numeric inputs and malformed/failed API
  responses before arithmetic. It remains a diagnostic helper.
- The Windows NTP helpers need validation on Windows, including elevation and
  service-command failures; their behavior was not changed or executed here.

The Pirate staging helper retains its pinned archive checksum. Pirate QDN
preflight now rejects nested directories, symlinks and special files, so extra
content cannot hide below the expected bundle root. Historical restore
acceptance retains its isolated native storage/log roots and was reviewed
without executing it. A real Docker image build and live shutdown/publication
remain operator checks, separate from these offline regressions.
