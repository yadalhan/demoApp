#!/usr/bin/env python3
"""
Blind index key bootstrap helper for demoApp (see vault-crypto README.md
"4. Searching Encrypted Columns - Blind Index" for background).

Generates locally (no network calls, no Vault access) one 32-byte random HMAC-SHA256
key per searchable field (user-phone, user-rrn) - independent of the KEK/DEK used for
envelope encryption, and unversioned (see BlindIndexKeyProvider's Javadoc for why:
rotating a blind index key requires reindexing every row in one pass, unlike DEK
rotation, so there's no "current version" concept to track here).

It then prints ready-to-run `vault kv put` commands. The current KMS is OpenBao 2.6.2
at http://192.168.2.57:28200 (Vault-compatible API), so point VAULT_ADDR/BAO_ADDR there;
the same commands work with either the `vault` or `bao` CLI. This script does NOT talk to
Vault itself - review the printed commands, then run them yourself against the target
Vault server (with VAULT_ADDR / VAULT_TOKEN set in your shell).

Usage:
  my_env/Scripts/python.exe bootstrap_blind_index_keys.py   (Windows)
  my_env/bin/python3 bootstrap_blind_index_keys.py          (Linux/Mac)

Requires: nothing beyond the standard library.
"""
import base64
import os

INDEX_NAMES = ["user-phone", "user-rrn"]
MOUNT = "ebiz_service"
BASE_PATH = "ebiz_db/blind-index"


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).decode("ascii").rstrip("=")


def main():
    print("=" * 78)
    print("Generate and store one blind index HMAC key per field (unversioned)")
    print("=" * 78)
    for index_name in INDEX_NAMES:
        key = os.urandom(32)
        print(f"# index: {index_name}")
        print(f"vault kv put -mount={MOUNT} {BASE_PATH}/{index_name} \\\n"
              f"  key=\"{b64url(key)}\"\n")

    print("=" * 78)
    print("Verification commands")
    print("=" * 78)
    for index_name in INDEX_NAMES:
        print(f"vault kv get -mount={MOUNT} {BASE_PATH}/{index_name}")

    print("\n[WARNING] This output contains the plaintext HMAC keys.")
    print("          Do not leave it in terminal history/logs; run the commands above and then clear the screen immediately.")
    print("          The generated keys differ every time this script runs - changing a key after data has")
    print("          already been stored invalidates every existing phone_blind_idx/id_no_blind_idx value")
    print("          (searches on those columns find nothing until everything is reindexed), so run this only once.")


if __name__ == "__main__":
    main()
