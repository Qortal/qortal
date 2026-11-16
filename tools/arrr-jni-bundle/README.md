# ARRR JNI build landing folder

This folder is the landing point for Stashi Qortal JNI builds used by Option A
and Option B QDN publishing. Built files are gitignored; download and copy them
here manually. The scripts do not download builds or update their SHA256 pins.

## Workflow

1. Trigger the Stashi-Wallet **Qortal JNI - Combined Native Bundle** build with
   **Run workflow** (manual dispatch of `qortal-jni.yml`):
   [GitHub Actions workflow](https://github.com/crowetic/Stashi-Wallet/actions/workflows/qortal-jni.yml).
   It produces one combined JNI artifact for all five supported desktop targets.
2. Download the combined artifact from that GitHub Actions run. Extract the
   downloaded Actions wrapper ZIP and copy its build files into **this folder**,
   `tools/arrr-jni-bundle/`:
   - `stashi-qortal-jni-<version>-<commit>.zip` (keep this inner ZIP intact)
   - `stashi-qortal-jni-<version>-<commit>.zip.sha256`
   - `qortal-jni-manifest.json`
   - `build-report-*.txt` (retain the build report alongside the downloaded files)
3. Verify the ZIP against its `.sha256` sidecar. From the repository root, for
   the currently staged build:

   ```bash
   (cd tools/arrr-jni-bundle && sha256sum -c stashi-qortal-jni-0.3.9-bd9b620194d1.zip.sha256)
   ```

   Use the new build's sidecar filename when staging a later build. Continue only
   if the ZIP reports `OK`. Update the script pins described below before
   publishing a new build.
4. Run one of the publish workflows below from the repository root.

## Option A: one-shot publish

```bash
./tools/prep-and-publish-arrr-wallet.sh
```

With no arguments, the script selects the newest `stashi-qortal-jni-*.zip` in
this folder by modification time, stages all five native libraries, prepares
the transaction, asks for the owner's key, signs, and submits it. It submits
after signing without a separate broadcast confirmation. It prints where it
saves the bundle, transaction files, and resulting signature; the default
parent is `${XDG_STATE_HOME:-$HOME/.local/state}/qortal/arrr-publications`,
overridable with `QDN_OUTPUT_DIR`.

The default folder is resolved from the script location regardless of the
current working directory. If it contains no matching ZIP, the script exits
with a message to drop the built artifact here first. Older ZIPs may remain,
but modification time determines which one is selected. An explicit ZIP path
overrides the default; an explicit directory must contain exactly one ZIP:

```bash
./tools/prep-and-publish-arrr-wallet.sh /path/to/combined-build.zip
```

`./tools/publish-pirate-unified-qdn.sh` with no arguments uses the same
one-shot workflow and default archive. Its explicit subcommands retain the
manual workflow below.

## Option B: manual prepare, sign, and submit

Stage the default ZIP as a five-library bundle without publishing:

```bash
./tools/stage-pirate-unified-qdn-bundle.sh
```

This creates `tools/arrr-jni-bundle/staged/` and refuses to overwrite it if it
already exists. For a later build, choose a fresh output directory instead:
`./tools/stage-pirate-unified-qdn-bundle.sh "" /path/to/new-bundle all`.
An explicit ZIP and output directory still work as before; with explicit
arguments, omitting the platform stages only the publishing host's library.

Then run each publishing step separately. Choose a registered QDN name you
control and a new immutable identifier for every publication. These example
commands prompt for both and use a fresh transaction output directory:

```bash
bundle="$PWD/tools/arrr-jni-bundle/staged"
./tools/publish-pirate-unified-qdn.sh validate release "$bundle"
read -r -p 'Registered QDN name: ' qdn_name
read -r -p 'New QDN identifier: ' identifier
publication_dir=$(mktemp -d "${TMPDIR:-/tmp}/arrr-qdn-publication.XXXXXX")
./tools/publish-pirate-unified-qdn.sh prepare-release "$bundle" "$qdn_name" "$identifier" "$publication_dir/unsigned.base58"
./tools/publish-pirate-unified-qdn.sh sign "$publication_dir/unsigned.base58" "$publication_dir/signed.base58"
./tools/publish-pirate-unified-qdn.sh submit "$publication_dir/signed.base58" "$publication_dir/signature.txt"
```

`prepare-release` creates an unsigned transaction, `sign` signs it, and
`submit` broadcasts it. Keep the transaction files and signature. None of the
explicit subcommands needs a ZIP argument because it uses the staged library
directory or transaction file supplied on its command line.

## Prompts and credentials

The no-argument one-shot flow asks for:

- **Registered QDN name**, default `PirateChainWallet`: a name registered to
  the owner account whose key you will use.
- **New QDN identifier**: accepts a generated timestamped default. Use a new
  identifier for each immutable publication.
- **Core API key**, hidden, when it is not supplied by `QORTAL_API_KEY` or
  read from `QORTAL_API_KEY_FILE` (default `~/qortal/apikey.txt`). This is the
  local Core instance's API key, not a wallet key.
- **QDN name owner's private key**, hidden: the Base58 private key for the
  Qortal account that owns the registered name. Obtain it from your owner
  account's wallet/key backup and enter it only at the trusted signer prompt.

The manual Option B subcommands take the name and identifier as arguments;
the example shell prompts collect them. They use `QORTAL_API_KEY`, or prompt
for it separately when absent; they do not load `QORTAL_API_KEY_FILE`
themselves. To use a key file for the manual steps, export it first:

```bash
export QORTAL_API_KEY="$(tr -d '\r\n' < "${QORTAL_API_KEY_FILE:-$HOME/qortal/apikey.txt}")"
```

The local Core API URL defaults to `http://127.0.0.1:12391`, configurable with
`QORTAL_API_URL`. The default fee is `1000000` atomic units (`0.01 QORT`),
configurable with `QDN_FEE`.

## Update the SHA256 pins for each new build

Whenever a new artifact is staged, manually update both pins to its verified
ZIP SHA256:

- `EXPECTED_SHA256` in `tools/stage-pirate-unified-qdn-bundle.sh`
- `EXPECTED_ARTIFACT_SHA256` in `tools/publish-pirate-unified-qdn.sh`

Also keep the stager's source commit and Actions run URL aligned with the
build. The artifact files are gitignored, and there is no automation to
update these values. A matching sidecar alone does not update the pins;
staging rejects a ZIP whose SHA256 differs from the pin.

## Publish to QDN and select in Core

After submission, wait for transaction confirmation and inspect the resulting
signature:

```bash
./tools/publish-pirate-unified-qdn.sh verify '<resulting-signature>'
```

Select that signature in Core's `pirateChainWalletQdnSignature` setting and
restart Core to use the new JNI bundle. Publishing alone does not change
Core's selected signature.
