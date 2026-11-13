#!/usr/bin/env python3

import argparse
import subprocess
import requests
import json
import os
import sys
import time
import hashlib
import re
import shlex
import xml.etree.ElementTree as ET
from pathlib import Path

API = requests.Session()
REQUEST_TIMEOUT = 30

def run(cmd, cwd=None, capture_output=True):
    result = subprocess.run(shlex.split(cmd) if isinstance(cmd, str) else cmd, cwd=cwd, capture_output=capture_output, text=True)
    if result.returncode != 0:
        print(f"Command failed: {cmd}\n{result.stderr}")
        sys.exit(1)
    return result.stdout.strip()

def get_project_name():
    pom = Path('pom.xml')
    if not pom.exists():
        sys.exit("pom.xml not found!")
    project = ET.parse(pom).getroot().findtext('{*}artifactId')
    if not project or not re.fullmatch(r'[\w.-]+', project):
        sys.exit("Valid project artifactId not found in pom.xml")
    return project

def get_commit_info(commit_hash=None, dry_run=False):
    if not commit_hash:
        print("No commit hash provided, detecting most recent auto-update branch...")
        run("git fetch origin")  # Ensure up-to-date

        # Get latest auto-update branch by commit date
        branches = run("git for-each-ref --sort=-committerdate --format='%(refname:short)' refs/remotes/origin/")
        for branch in branches.splitlines():
            branch = branch.strip().strip("'")
            if branch.startswith("origin/auto-update-"):
                commit_hash = branch.replace("origin/auto-update-", "")
                print(f"Found latest auto-update branch: {branch}")
                break

        if not commit_hash:
            sys.exit("No auto-update branches found.")

    # An update payload identifies a published orphan update commit, never the
    # source commit or an unrelated local .update file.
    if not re.fullmatch(r"[0-9a-fA-F]{4,40}", commit_hash):
        sys.exit("Commit hash must contain 4 to 40 hexadecimal characters.")
    source_hash = run(["git", "rev-parse", "--verify", f"{commit_hash}^{{commit}}"])
    timestamp = int(run(["git", "show", "--no-patch", "--format=%ct", source_hash])) * 1000
    update_hash = run(["git", "rev-parse", "--verify",
                       f"refs/remotes/origin/auto-update-{commit_hash}^{{commit}}"])

    return commit_hash, timestamp, update_hash

    
def commit_exists(commit_hash):
    try:
        run(f"git cat-file -t {commit_hash}")
        return True
    except SystemExit:
        return False

def get_sha256(update_file_path):
    sha256 = hashlib.sha256()
    with open(update_file_path, 'rb') as f:
        sha256.update(f.read())
    return sha256.hexdigest()

def get_public_key(base58_privkey, port):
    r = API.post(f"http://localhost:{port}/utils/publickey", data=base58_privkey, timeout=REQUEST_TIMEOUT)
    r.raise_for_status()
    return r.text

def get_hex_key(base58_key, port):
    r = API.post(f"http://localhost:{port}/utils/frombase58", data=base58_key, timeout=REQUEST_TIMEOUT)
    r.raise_for_status()
    return r.text

def get_address(pubkey, port):
    r = API.get(f"http://localhost:{port}/addresses/convert/{pubkey}", timeout=REQUEST_TIMEOUT)
    r.raise_for_status()
    return r.text

def get_reference(address, port):
    r = API.get(f"http://localhost:{port}/addresses/lastreference/{address}", timeout=REQUEST_TIMEOUT)
    r.raise_for_status()
    return r.text

def to_base58(hex_str, port):
    r = API.get(f"http://localhost:{port}/utils/tobase58/{hex_str}", timeout=REQUEST_TIMEOUT)
    r.raise_for_status()
    return r.text

def sign_transaction(privkey, tx_base58, port):
    payload = json.dumps({"privateKey": privkey, "transactionBytes": tx_base58})
    headers = {"Content-Type": "application/json"}
    r = API.post(f"http://localhost:{port}/transactions/sign", data=payload, headers=headers, timeout=REQUEST_TIMEOUT)
    r.raise_for_status()
    return r.text

def process_transaction(signed_tx, port):
    r = API.post(f"http://localhost:{port}/transactions/process", data=signed_tx, timeout=REQUEST_TIMEOUT)
    r.raise_for_status()
    return r.text == 'true'

def decode_transaction(signed_tx, port):
    r = API.post(f"http://localhost:{port}/transactions/decode", data=signed_tx, headers={"Content-Type": "application/json"}, timeout=REQUEST_TIMEOUT)
    r.raise_for_status()
    return r.text

def main():
    import getpass
    parser = argparse.ArgumentParser(description="Modern auto-update publisher for Qortal")
    parser.add_argument("arg1", nargs="?", help="Private key OR commit hash")
    parser.add_argument("arg2", nargs="?", help="Commit hash if arg1 was private key")
    parser.add_argument("--port", type=int, default=12391, help="API port")
    parser.add_argument("--dry-run", action="store_true", help="Simulate without submitting transaction")
    args = parser.parse_args()

    # Handle combinations
    if args.arg1 and args.arg2:
        privkey = args.arg1
        commit_hash = args.arg2
    elif args.arg1 and not args.arg2:
        commit_hash = args.arg1
        privkey = None
    else:
        commit_hash = None  # Will auto-resolve from HEAD
        privkey = None

    # Switch to repo root
    git_root = run("git rev-parse --show-toplevel")
    os.chdir(git_root)

    project = get_project_name()

    # Resolve and verify commit
    commit_hash, timestamp, update_hash = get_commit_info(commit_hash, args.dry_run)
    if not commit_exists(commit_hash):
        sys.exit(f"Commit hash '{commit_hash}' does not exist in this repo.")

    print(f"Commit: {commit_hash}, Timestamp: {timestamp}, Auto-update hash: {update_hash}")

    # Hash the exact blob downloadable from the commit written into the payload.
    # Existing local update files and local branches may belong to another build.
    try:
        update_bytes = subprocess.check_output(
            ["git", "show", f"{update_hash}:{project}.update"], stderr=subprocess.PIPE)
    except subprocess.CalledProcessError:
        sys.exit("Published update commit does not contain the expected .update file.")
    if not update_bytes:
        sys.exit("Published update file is empty.")
    sha256 = hashlib.sha256(update_bytes).hexdigest()
    print(f"Update SHA256: {sha256}")

    if args.dry_run:
        print("\n--- DRY RUN ---")
        print(f"Would use timestamp: {timestamp}")
        print(f"Would use update hash: {update_hash}")
        print(f"Would use SHA256: {sha256}")
        sys.exit(0)

    if not privkey:
        privkey = getpass.getpass("Enter your Base58 private key: ")
    if not re.fullmatch(r"[1-9A-HJ-NP-Za-km-z]{32,44}", privkey):
        sys.exit("Invalid Base58 private key.")
    key_file = Path("apikey.txt")
    if key_file.is_file():
        api_key = key_file.read_text().strip()
        if "\n" in api_key or "\r" in api_key:
            sys.exit("API key must contain one line.")
        if api_key:
            API.headers["X-API-KEY"] = api_key
    pubkey = get_public_key(privkey, args.port)
    pubkey_hex = get_hex_key(pubkey, args.port)
    address = get_address(pubkey, args.port)
    reference = get_reference(address, args.port)
    reference_hex = get_hex_key(reference, args.port)

    data_hex = f"{timestamp:016x}{update_hash}{sha256}"
    if len(data_hex) != 120:
        sys.exit("Data hex length invalid!")

    raw_tx_parts = [
    	"0000000a",                                  # type 10 ARBITRARY
   	 f"{int(time.time() * 1000):016x}",           # current timestamp
    	"00000001",                                  # dev group ID
    	reference_hex,                               # reference
    	pubkey_hex,                                  # pubkey
    	"00000000",                                  # nonce
    	"00000000",                                  # name length
    	"00000000",                                  # identifier length
    	"00000000",                                  # method (PUT)
    	"00000000",                                  # secret length
        "00000000",                                  # compression
        "00000000",                                  # number of payments
        "00000001",                                  # service ID
        "01",                                        # data type (RAW_DATA)
        f"{int(len(data_hex)//2):08x}",              # data length
        data_hex,                                    # payload
        f"{int(len(data_hex)//2):08x}",              # repeated data length
        "00000000",                                  # metadata hash length
        f"{int(0.01 * 1e8):016x}"                     # fee
    ]
    tx_hex = "".join(raw_tx_parts)


    tx_base58 = to_base58(tx_hex, args.port)
    signed_tx = sign_transaction(privkey, tx_base58, args.port)

    print("Submitting in 5 seconds... press CTRL+C to cancel")
    for i in range(5, 0, -1):
        print(f"{i}...", end='\r', flush=True)
        time.sleep(1)

    if not process_transaction(signed_tx, args.port):
        sys.exit("Transaction submission failed")

    decoded = decode_transaction(signed_tx, args.port)
    print("\nTransaction submitted successfully:")
    print(decoded)

if __name__ == "__main__":
    main()
