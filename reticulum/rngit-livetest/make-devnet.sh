#!/usr/bin/env bash
#
# Generates an isolated devnet: devchain.json, devnet-keys.json and one
# settings file per node, with org.qortal.devnet.DevnetGenerator. See
# DEVNET-SETUP.md.
#
# Usage:  ./make-devnet.sh --out DIR --hosts host1,host2,host3 [options]
#
#   --start-minutes N     genesis this many minutes from now (default 15)
#   --api-port P          API port (default 63391)
#   --listen-port P       blockchain P2P port (default 63392)
#   --data-port P         QDN port (default 63394)
#   --port-step N         add N per node to the ports, for nodes on one machine
#   --reticulum-port P    Reticulum gateway port (default 4240, testnet)
#   --gateways N          the first N nodes are Reticulum gateways (default 2)
#   --network-name NAME   Reticulum network name (default qortal-devnet)
#   --passphrase TEXT     Reticulum passphrase (default: random)
#   --account NAME:QORT   a funded genesis account (repeatable; default:
#                         faucet, publisher, devuser, dev1..dev3)
#   --base FILE           chain config to derive from (default: Core's blockchain.json)

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CORE="$(cd "$HERE/../.." && pwd)"
CP_FILE="$(mktemp)"
trap 'rm -f "$CP_FILE"' EXIT

(cd "$CORE" && mvn -o -q test-compile -DskipJUnitTests=true)
(cd "$CORE" && mvn -o -q dependency:build-classpath -Dmdep.includeScope=test -Dmdep.outputFile="$CP_FILE")
java -cp "$(cat "$CP_FILE"):$CORE/target/classes:$CORE/target/test-classes" org.qortal.devnet.DevnetGenerator "$@"
