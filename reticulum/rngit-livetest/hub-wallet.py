#!/usr/bin/env python3
#
# Qortal Hub wallet files from the command line, for devnet accounts.
#
#   hub-wallet.py create --keys devnet-keys.json --account devuser [-o devuser.json]
#   hub-wallet.py create --private-key <Base58> [-o wallet.json]
#   hub-wallet.py decrypt wallet.json
#
# A Hub wallet file is the JSON that Hub's "Download account" saves and its
# "Import account" loads: {address0, encryptedSeed, salt, iv, version, mac,
# kdfThreads}. Hub encrypts a 32-byte seed with AES-256-CBC under a key from
# its bcrypt-based KDF and authenticates it with HMAC-SHA512 (src/utils/
# generateWallet/storeWallet.ts, src/utils/decryptWallet.ts, src/encryption/
# kdf.ts in Qortal-Hub).
#
# create writes a version 1 wallet, whose seed Hub uses directly as the
# account's Ed25519 private key (phrase-wallet.ts genAddress), so the file opens
# exactly the account in devnet-keys.json. Hub's own new accounts are version 2,
# which derive the key from the seed instead; decrypt reads both.
#
# The password is prompted for, or taken from HUB_WALLET_PASSWORD.
#
# Needs the Python modules bcrypt and cryptography.

import argparse
import base64
import getpass
import hashlib
import hmac
import json
import os
import sys

import bcrypt
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat

# Hub's src/constants/decryptWallet.ts
KDF_THREADS = 16
STATIC_SALT = "4ghkVQExoneGqZqHTMMhhFfxXsVg2A75QeS1HCM5KAih"
STATIC_BCRYPT_SALT = b"$2a$11$IxVE941tXVUD4cW0TNVm.O"
ADDRESS_VERSION = 58

B58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"


def b58encode(data):
    n = int.from_bytes(data, "big")
    out = ""
    while n:
        n, r = divmod(n, 58)
        out = B58[r] + out
    return "1" * (len(data) - len(data.lstrip(b"\0"))) + out


def b58decode(text):
    n = 0
    for c in text:
        n = n * 58 + B58.index(c)
    body = n.to_bytes((n.bit_length() + 7) // 8, "big") if n else b""
    return b"\0" * (len(text) - len(text.lstrip("1"))) + body


def kdf(password):
    """Hub's kdf(): 16 bcrypt rounds over salted SHA-512s of the password, then SHA-512.
    The wallet's random salt is stored but, as in Hub, not part of the key."""
    parts = []
    for nonce in range(KDF_THREADS):
        digest = hashlib.sha512(f"{STATIC_SALT}{password}{nonce}".encode("utf-8")).digest()
        parts.append(bcrypt.hashpw(base64.b64encode(digest)[:72], STATIC_BCRYPT_SALT).decode())
    return hashlib.sha512((STATIC_SALT + "".join(parts)).encode("utf-8")).digest()


def keys(password):
    key = kdf(password)
    return key[:32], key[32:63]  # Hub's macKey is key.slice(32, 63): 31 bytes


def aes_cbc(key, iv, data, encrypt):
    cipher = Cipher(algorithms.AES(key), modes.CBC(iv))
    op = cipher.encryptor() if encrypt else cipher.decryptor()
    return op.update(data) + op.finalize()


def address_of(public_key):
    body = bytes([ADDRESS_VERSION]) + hashlib.new("ripemd160", hashlib.sha256(public_key).digest()).digest()
    checksum = hashlib.sha256(hashlib.sha256(body).digest()).digest()[:4]
    return b58encode(body + checksum)


def account_of(private_key):
    public_key = Ed25519PrivateKey.from_private_bytes(private_key).public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)
    return public_key, address_of(public_key)


def private_key_from_seed(seed, version):
    """phrase-wallet.ts genAddress(0)."""
    if version == 1:
        return seed
    nonce = (0).to_bytes(4, "big")
    material = nonce + seed + nonce
    first = hashlib.sha512(material).digest()
    return hashlib.sha512(first + material).digest()[:32]


def password_from_user(confirm):
    password = os.environ.get("HUB_WALLET_PASSWORD")
    if password is not None:
        return password
    password = getpass.getpass("Wallet password: ")
    if confirm and getpass.getpass("Repeat: ") != password:
        sys.exit("Passwords differ")
    return password


def create(args):
    if args.keys:
        with open(args.keys) as fh:
            accounts = json.load(fh)
        if args.account not in accounts:
            sys.exit(f"No account {args.account} in {args.keys}; it has: {', '.join(accounts)}")
        private_key = b58decode(accounts[args.account]["privateKey"])
    else:
        private_key = b58decode(args.private_key)
    if len(private_key) != 32:
        sys.exit("A Qortal private key is 32 bytes")

    _, address = account_of(private_key)
    if args.keys and accounts[args.account]["address"] != address:
        sys.exit(f"{args.keys} lists {accounts[args.account]['address']}, the key gives {address}")

    encryption_key, mac_key = keys(password_from_user(confirm=True))
    iv, salt = os.urandom(16), os.urandom(32)
    encrypted = aes_cbc(encryption_key, iv, private_key, encrypt=True)  # 32 bytes: no padding, as Hub
    wallet = {"address0": address, "encryptedSeed": b58encode(encrypted), "salt": b58encode(salt),
              "iv": b58encode(iv), "version": 1, "mac": b58encode(hmac.new(mac_key, encrypted, hashlib.sha512).digest()),
              "kdfThreads": KDF_THREADS}

    out = args.output or f"qortal_backup_{address}.json"
    with open(out, "w") as fh:
        json.dump(wallet, fh)
    os.chmod(out, 0o600)
    print(f"Wrote {out} for {address}")


def decrypt(args):
    with open(args.wallet) as fh:
        wallet = json.load(fh)
    encrypted = b58decode(wallet["encryptedSeed"])
    encryption_key, mac_key = keys(password_from_user(confirm=False))
    if b58encode(hmac.new(mac_key, encrypted, hashlib.sha512).digest()) != wallet["mac"]:
        sys.exit("Incorrect password")
    seed = aes_cbc(encryption_key, b58decode(wallet["iv"]), encrypted, encrypt=False)
    version = wallet.get("version", 2)
    private_key = private_key_from_seed(seed, version)
    public_key, address = account_of(private_key)
    if address != wallet.get("address0"):
        sys.exit(f"Decrypted, but the key gives {address}, not the file's address0 {wallet.get('address0')}")
    print(json.dumps({"address": address, "version": version, "publicKey": b58encode(public_key),
                      "privateKey": b58encode(private_key)}, indent=2))


def main():
    parser = argparse.ArgumentParser(description="Qortal Hub wallet files for devnet accounts")
    sub = parser.add_subparsers(dest="command", required=True)
    c = sub.add_parser("create", help="write a Hub wallet file for an account")
    source = c.add_mutually_exclusive_group(required=True)
    source.add_argument("--keys", help="devnet-keys.json from make-devnet.sh (with --account)")
    source.add_argument("--private-key", help="a Base58 private key")
    c.add_argument("--account", help="account name in --keys, e.g. devuser")
    c.add_argument("-o", "--output", help="output file (default qortal_backup_<address>.json)")
    d = sub.add_parser("decrypt", help="print a Hub wallet file's address and keys")
    d.add_argument("wallet")
    args = parser.parse_args()
    if args.command == "create":
        if args.keys and not args.account:
            parser.error("--keys needs --account")
        create(args)
    else:
        decrypt(args)


if __name__ == "__main__":
    main()
