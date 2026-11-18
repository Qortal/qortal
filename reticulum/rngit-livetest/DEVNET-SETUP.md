# An isolated Qortal devnet

How to build a private devnet of three nodes, with its own chain, accounts,
names and QDN, that cannot exchange anything with mainnet or with other test
networks. Hubs can connect to it and load Q-Apps from it, and developers can
build Q-Apps against it. Once it runs, `DEVNET-RUNBOOK.md` tests rngit on it.

`make-devnet.sh` generates everything a node needs; the rest of this page is
how to deploy it.

## What keeps it isolated

| Layer | What it does |
|---|---|
| `"isTestNet": true` | Testnet message magic, so mainnet peers drop the handshake; Reticulum app name `qortaltest` |
| Its own genesis (`devchain.json`) | A chain nobody else has: other networks cannot sync it, nor it theirs |
| Its own ports (default 6339x) | Distinct from the shared test network on 6239x |
| `fixedNetwork` | Each node talks only to the other devnet nodes. The generator writes their IPv4 addresses: Core compares an inbound peer's IP with this list as text, so a hostname here rejects every inbound connection |
| `"bootstrap": false` | Never downloads a public bootstrap |
| Reticulum `network_name` and a random `passphrase` | Interface access codes: other meshes cannot join; nodes 1 and 2 are the gateways (`--gateways`), never the public ones; node 2 also connects to node 1, the others to both |
| Firewall | Peer ports open only between the devnet nodes; the API only to developers |

## 1. Generate the devnet

On any machine with a Core checkout (`reticulum-git` at or after the commit
that adds `make-devnet.sh`):

```bash
cd ~/git/qortal-claude/reticulum/rngit-livetest
# using defaults
./make-devnet.sh --out ~/devnet --hosts devnet-1.example,devnet-2.example,devnet-3.example --start-minutes 20

# other example using custom ports
./make-devnet.sh --out ~/devnet --api-port 43291 --listen-port 43292 --data-port 43294 \
  --hosts devnet-1.example,devnet-2.example,devnet-3.example --start-minutes 20
```

It writes to `~/devnet`:

| File | Content |
|---|---|
| `devchain.json` | The chain config, derived from Core's current mainnet `blockchain.json` |
| `devnet-keys.json` | Every genesis account's address and keys. Private: keep it out of git, mode 600 |
| `settings-node-1.json` … `-3.json` | One `settings.json` per node |
| `rngit-config` | Core's default rngit config with `announce_interval = 10`, the same for every node |

The chain follows mainnet's rules as they apply today:
- Every feature trigger mainnet has passed is active from genesis.
- Windows mainnet has closed (such as the reward-share pause) are empty.
- The two triggers mainnet keeps in the far future stay there.
- Fees and the reward-share limit are mainnet's current values: 0.01 QORT a
  transaction, 1.25 QORT a name.

Genesis contains:
- **Minters** `minter1`–`minter3`, one per node. Each is a founder at level 10
  with a self-share and an admin of the minting group `devnet-minters` (group 1),
  and holds 1,000 QORT.
- **Funded accounts:** `faucet` (1,000,000 QORT), `publisher` and `devuser`
  (10,000 each), `dev1`–`dev3` (1,000 each). Change them with `--account NAME:QORT`,
  repeated.

The genesis timestamp is `--start-minutes` from now, rounded to the minute.
Choose enough time to deploy all nodes and add their minting keys: **all nodes
must be running, peered and holding their minting key before genesis.** Core
mints only on a recent tip (at height 1, at most 90 seconds after genesis), and
the synchronizer never syncs from a peer whose tip isn't recent. A devnet that
misses the window stays at height 1 for good. Recovery mode
(`recoveryModeTimeout`) does not rescue it: it relaxes minting, not syncing, and
a node never mints right after its own key's block, so with one key per node
each node mints at most one block on its own fork. To restart, stop all nodes,
set a new `genesisInfo.timestamp` in `devchain.json` (same file on every node;
accounts and keys stay valid), move `db-devnet` away on every node and start
them all before the new time.

`--hosts` takes hostnames or IPs. The generator resolves each host to IPv4 for
`fixedNetwork`, so run it where the hosts resolve as the nodes see each other.
The Reticulum gateway address keeps the hostname.

Other options: `--api-port`, `--listen-port`, `--data-port` (defaults 63391,
63392, 63394), `--reticulum-port` (the gateways' port, default 4240; written as `reticulumPort`,
which needs a jar with that setting, else gateways listen on 4242), `--gateways N`
(the first N nodes are Reticulum gateways, default 2; 1 gives the old single
gateway), `--network-name`,
`--passphrase`, and `--port-step N` to give each node ports N higher, for
several nodes on one machine. See *Known issues* before doing that.

## 2. Deploy the nodes

On each node `N`, in its own directory (not an existing mainnet or testnet one):

```bash
mkdir -p ~/qortal-devnet && cd ~/qortal-devnet
cp /path/to/qortal.jar .                # built from the same checkout
cp ~/devnet/devchain.json .
cp ~/devnet/settings-node-N.json settings.json
mkdir -p rngit && cp ~/devnet/rngit-config rngit/config
java -Djava.net.preferIPv4Stack=false -jar qortal.jar settings.json > run.out 2>&1 &
```

**Check** in `qortal.log`: `Using blockchain config file: devchain.json`, then
`Syncing from genesis block!`, and no `Failed to render config file` from
`RNSConfigWriter`. That error means the node fell back to Core's packaged
testnet Reticulum config, which dials a public host; stop the node and fix the
settings (see *Known issues*).

`rngit/config` must be in place before the first start: Core writes its own
default there when the file is missing (with `announce_interval = 360`) and
never overwrites an existing one. On a node that already has one, set
`announce_interval = 10` under `[rngit]` by hand. Why 10 minutes: a restarted
gateway restores its paths from its saved path table and from the other
nodes' tunnels, but a path it lost anyway (e.g. after a hard kill) comes back
only with that node's next announce; with the default that is up to 6 hours.

Open the firewall between the nodes for the listen, data and Reticulum ports,
and the API port for whoever uses Hub.

## 3. Start minting

Once each node's API answers, give it an API key and its minter's minting key:

```bash
API=http://localhost:63391
KEY=$(curl -s -X POST $API/admin/apikey/generate)        # only works before a key exists
curl -s -X POST -H "X-API-KEY: $KEY" $API/admin/mintingaccounts -d '<mintingKey of minterN>'
```

The minting key is `minterN.mintingKey` in `devnet-keys.json`, not its
`privateKey`.

**Check:** after the genesis timestamp, `curl -s $API/blocks/height` rises by
about one a minute, on every node, and `GET /blocks/last` shows the same
signature on all of them. With all minters online, `minterAddress` alternates
between them.

If the height stays at 1 or 2, look at `GET /admin/status` (needs the API key):
- `numberOfConnections` 0: the nodes don't peer. Check that `fixedNetwork`
  holds IPs, not hostnames, and that the firewall passes the listen port.
- Connections but `isMintingPossible` false at height 1: genesis passed before
  the nodes were up and minting. Restart with a new genesis time (see
  section 1).
- `GET /admin/mintingaccounts` empty: the minting key was not added.
- `GET /admin/mintingaccounts` lists more than one key: each node must hold
  only its own minter's key. A node never mints right after a block signed by
  one of its own keys, so with every key on every node the chain stops after one
  block. Remove the others with `DELETE /admin/mintingaccounts` and the key
  (the public key from the listing works).

Don't set `singleNodeTestnet` on a devnet node: it lets a node mint with no
peers (with `minBlockchainPeers` 0) on a chain of its own.

## 4. Accounts and names

Transactions go through any node's API: build, sign with the account's
private key, process. To register `devgit` for the publisher:

```bash
read PUB PRIV ADDR < <(python3 -c "import json; a = json.load(open('$HOME/devnet/devnet-keys.json'))['publisher']; print(a['publicKey'], a['privateKey'], a['address'])")
REF=$(curl -s $API/addresses/lastreference/$ADDR)
RAW=$(curl -s -X POST -H "Content-Type: application/json" $API/names/register \
  -d "{\"timestamp\":$(date +%s%3N),\"reference\":\"$REF\",\"fee\":1.25,\"registrantPublicKey\":\"$PUB\",\"name\":\"devgit\",\"data\":\"\"}")
SIGNED=$(curl -s -X POST -H "Content-Type: application/json" -H "X-API-KEY: $KEY" $API/transactions/sign \
  -d "{\"privateKey\":\"$PRIV\",\"transactionBytes\":\"$RAW\"}")
curl -s -X POST -H "X-API-KEY: $KEY" $API/transactions/process -d "$SIGNED"    # true
```

**Check:** after the next block, `GET /names/devgit` on any node shows the
publisher as owner. Do the same for `devuser`, and pay new developers from
`faucet` with `POST /payments/pay`, built, signed and processed the same way.

## 5. rngit

Follow `DEVNET-RUNBOOK.md` from step 1, with these nodes as A, B and C. The
generated settings already enable rngit; P is `publisher`, U is `devuser`.
The runbook's laptop Reticulum config (step 2) uses node 1's gateway and node 2's,
with this devnet's `reticulumNetworkName` and `reticulumPassphrase` from the
settings files. Both are gateways already, so runbook step 1's *B as a second
Reticulum gateway* only needs its checks (and the firewall or NAT port forward).

## 6. Hub and Q-Apps

A stock Qortal Hub works with the devnet; no custom build is needed.

**Connect Hub to a node.** Core's API answers only localhost by default
(`apiWhitelist` is `127.0.0.1` and `::1`), so forward a node's API port over
SSH, to the port Hub's *Local node* uses:

```bash
ssh -N -L 12391:localhost:63391 devnet-1.example
```

In Hub, choose *Local node* (`http://127.0.0.1:12391`). Use *Import API key*
with a copy of that node's API key file, `apikey.txt` in the node directory.
The key was generated in step 3.

The alternative is a custom node `http://<node>:<apiPort>` with the API key.
But then the node's `apiWhitelist` must include the developer's address, and
the API travels unencrypted.

**Log in with a devnet account.** Hub imports an encrypted wallet file, the
same JSON its *Download account* saves. `hub-wallet.py` writes one for any
account in `devnet-keys.json`:

```bash
cd ~/git/qortal-claude/reticulum/rngit-livetest
./hub-wallet.py create --keys ~/devnet/devnet-keys.json --account devuser -o devuser.json
```

It prompts for the wallet password, or takes it from `HUB_WALLET_PASSWORD`.
In Hub, import `devuser.json` and log in with that password. The file is a
version 1 wallet: Hub uses its decrypted seed directly as the account's
private key, so it opens exactly the account in `devnet-keys.json`.
`./hub-wallet.py decrypt devuser.json` prints a wallet file's address and
keys, including wallets Hub created (version 2).

Use devnet accounts only. A wallet file holds the private key, protected only
by its password.

**Q-Apps.**
- **The rngit reference Q-App:** publish `reticulum/qapp/rngit` as an `APP`
  resource under a devnet name you own, then open it in Hub. Use Hub's
  publish screen with the directory zipped, or `POST /arbitrary/APP/<name>/zip`,
  signed and processed as in step 4.
- **A developer's own Q-App:** Hub's dev mode loads a Q-App from a local dev
  server (domain and port) or from a selected directory, against the
  connected devnet node.

**What does not follow the selected node.** Everything else in Hub uses the
selected node: `getBaseApi`/`createEndpoint` fall back to
`ext-node.qortal.link` only when no node is set. Two exceptions, in the Hub
checkout of October 2025:
- Cross-chain trading always asks `appnode.qortal.org`, a mainnet node.
  Don't trade on the devnet.
- The tutorials check `ext-node.qortal.link/admin/status`. This is harmless.

## 7. Reset

Stop every node, delete `db-devnet` (and `.reticulum_test`, `rngit`,
`qortal-backup`) in each node directory, regenerate with `make-devnet.sh`, and
start again. A new genesis means new keys: Hubs need the new accounts.

If the chain stalls (every minter was offline), start one node with
`"singleNodeTestnet": true` until it has minted up to now, then remove the
setting and restart it. The others catch up, or use
`POST /admin/forcesync <peer>` on a stuck one.

## Known issues

- **Fresh databases need a Core fix.** On a new repository without a bootstrap,
  Core stopped during startup with `statement is not in batch mode`
  (`ArbitraryDataCacheManager.populateLatestSignaturesIfNecessary` ran an
  empty batch). The node stays running without an API. Fixed in `reticulum-git`
  with the commit adding this page.
- **Empty lists in `settings.json` are `null`.** An empty JSON array for a
  Reticulum gateway list used to make the config render fail, and Core then
  wrote its packaged testnet config, which has an AutoInterface and a TCP
  client to the public host `phantom.mobilefabrik.com`. `RNSConfigWriter`
  now treats a missing list as empty, in the same commit. On an older jar, never
  write `[]` for those settings.
- **One node per machine (or container).** Several nodes on one machine share
  Reticulum's local instance; when one stops, another loses its link and
  exits. A local test with `--port-step` ran two nodes reliably, the third
  exited this way.
- **A node stopped during its first start can leave a half-migrated database.**
  The next start fails with `Unable to start repository` … `object name already
  exists in statement [ALTER TABLE ATs ADD sleep_until_message_timestamp …]`,
  and the JVM keeps running without an API. Stop it, move `db-devnet` away and
  start again. The fresh database imports the minting keys from
  `qortal-backup/MintingAccounts.json`, so check `/admin/mintingaccounts`
  afterwards.
- **`testnet/testchain.json` is out of date.** It predates `mintingGroupIds`
  and three feature triggers; generate the chain with `make-devnet.sh` instead.

## Verified so far

On one machine, 2026-10-03 (`--port-step 10`), with this page's Core fixes:
- The chain started from a generated genesis.
- Two nodes minted alternately and stayed on the same tip.
- A name registered through the API with the generated publisher key.

2026-10-10: `hub-wallet.py create` turns a generated key into a wallet file
that decrypts to the same account with Hub's own libraries and derivation
(bcryptjs, asmcrypto, Hub's `nacl-fast`), and Hub's MAC check rejects a wrong
password.

Not yet verified: three separate machines, logging in to a running Hub against
the devnet, and the rngit runbook on it.
