#!/usr/bin/env python3
#
# Bind Reticulum identities to a Qortal account, for rngit access rules that
# name Qortal accounts (name:<name>, group:<id>, owner) on Qortal Core nodes.
#
# A binding record is a small JSON document that the account publishes on QDN,
# under a name it owns, as service JSON with identifier "rns-identity". Each
# entry is signed by the RNS identity it lists, over
#     qortal-rns-identity-binding/1:<qortal address>
# which proves the account holder controls that identity. The account's side is
# proven by QDN: only the name's owner can publish under it.
#
# Commands:
#   create   build the record from one or more RNS identity files
#   verify   check a record's signatures against an address
#   publish  publish a record through a Qortal Core node's API
#
# Examples:
#   rns-identity-binding.py create --address QXXXX -i ~/.rngit/client_identity > binding.json
#   rns-identity-binding.py verify --address QXXXX binding.json
#   rns-identity-binding.py publish --name myname binding.json
#
# Needs the Reticulum Python package (pip install rns), as rngit itself does.

import argparse
import getpass
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

import RNS

FORMAT = "rns-identity/1"
MESSAGE_PREFIX = "qortal-rns-identity-binding/1:"
IDENTIFIER = "rns-identity"
SERVICE = "JSON"
DEFAULT_API = "http://127.0.0.1:12391"
DEFAULT_FEE = 1000000  # 0.01 QORT in atomic units: the current fee per transaction


def binding_message(address):
    return (MESSAGE_PREFIX + address).encode("utf-8")


def create_record(address, identities):
    return {
        "format": FORMAT,
        "address": address,
        "identities": [{"public_key": identity.get_public_key().hex(),
                        "signature": identity.sign(binding_message(address)).hex()}
                       for identity in identities],
    }


def verified_hashes(record, address):
    """Identity hashes the record validly binds to address, as Qortal Core checks them."""
    hashes = []
    if record.get("format") != FORMAT or record.get("address") != address:
        return hashes
    for entry in record.get("identities", []):
        try:
            identity = RNS.Identity(create_keys=False)
            identity.load_public_key(bytes.fromhex(entry["public_key"]))
            if identity.validate(bytes.fromhex(entry["signature"]), binding_message(address)):
                hashes.append(identity.hash.hex())
        except Exception:
            continue
    return hashes


def load_identity(path):
    identity = RNS.Identity.from_file(os.path.expanduser(path))
    if not identity:
        sys.exit(f"Could not load an RNS identity from {path}")
    return identity


def api_key(args):
    if args.api_key:
        return args.api_key
    if args.api_key_file and os.path.isfile(args.api_key_file):
        with open(args.api_key_file) as fh:
            return fh.read().strip()
    return None


def call(api, path, body, key, content_type="text/plain"):
    request = urllib.request.Request(api + path, data=body.encode("utf-8"), method="POST",
                                     headers={"Content-Type": content_type, "Accept": "text/plain"})
    if key:
        request.add_header("X-API-KEY", key)
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            return response.read().decode("utf-8").strip()
    except urllib.error.HTTPError as e:
        sys.exit(f"{path} failed: HTTP {e.code} {e.read().decode('utf-8', errors='replace')}")


def cmd_create(args):
    record = create_record(args.address, [load_identity(p) for p in args.identity])
    print(json.dumps(record, indent=2))
    for entry_hash in verified_hashes(record, args.address):
        print(f"bound <{entry_hash}>", file=sys.stderr)


def cmd_verify(args):
    with open(args.record) as fh:
        record = json.load(fh)
    hashes = verified_hashes(record, args.address)
    total = len(record.get("identities", []))
    for h in hashes:
        print(f"valid  <{h}>")
    if len(hashes) != total:
        print(f"{total - len(hashes)} of {total} entries do not verify for {args.address}")
        sys.exit(1)


def cmd_publish(args):
    with open(args.record) as fh:
        record = json.load(fh)
    if not verified_hashes(record, record.get("address", "")):
        sys.exit("The record has no entry that verifies; refusing to publish it")

    key = api_key(args)
    name = urllib.parse.quote(args.name, safe="")
    query = urllib.parse.urlencode({"fee": args.fee, "filename": "rns-identity.json"})
    unsigned = call(args.api, f"/arbitrary/{SERVICE}/{name}/{IDENTIFIER}/string?{query}", json.dumps(record), key)

    private_key = getpass.getpass(f"Base58 private key of the account owning {args.name}: ").strip()
    signed = call(args.api, "/transactions/sign",
                  json.dumps({"privateKey": private_key, "transactionBytes": unsigned}), key, "application/json")
    result = call(args.api, "/transactions/process", signed, key)
    print(f"Submitted: {result}")
    print(f"The record is published as {SERVICE}/{args.name}/{IDENTIFIER} once the transaction is accepted.")


def main():
    parser = argparse.ArgumentParser(description="Bind Reticulum identities to a Qortal account for rngit")
    sub = parser.add_subparsers(dest="command", required=True)

    p = sub.add_parser("create", help="build a binding record")
    p.add_argument("--address", required=True, help="the Qortal account address")
    p.add_argument("-i", "--identity", action="append", required=True,
                   help="RNS identity file to bind (repeatable), e.g. ~/.rngit/client_identity")
    p.set_defaults(func=cmd_create)

    p = sub.add_parser("verify", help="verify a binding record")
    p.add_argument("--address", required=True)
    p.add_argument("record")
    p.set_defaults(func=cmd_verify)

    p = sub.add_parser("publish", help="publish a binding record through a Core node")
    p.add_argument("--name", required=True, help="a Qortal name the account owns")
    p.add_argument("--api", default=DEFAULT_API, help=f"Core API base URL (default {DEFAULT_API})")
    p.add_argument("--api-key", help="Core API key (default: read --api-key-file)")
    p.add_argument("--api-key-file", default="apikey.txt", help="file holding the API key (default ./apikey.txt)")
    p.add_argument("--fee", type=int, default=DEFAULT_FEE, help=f"fee in atomic units (default {DEFAULT_FEE} = 0.01 QORT)")
    p.add_argument("record")
    p.set_defaults(func=cmd_publish)

    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
