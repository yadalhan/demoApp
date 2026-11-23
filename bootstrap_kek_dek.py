#!/usr/bin/env python3
"""
KEK-DEK bootstrap helper for demoApp (see KEK_DEK_ENCRYPTION_PLAN.md phase P0,
KEY_ROTATION_RUNBOOK.md for the ongoing rotation procedure).

Generates locally (no network calls, no Vault access):
  - one KEK, version 1 (32 random bytes) - the master key, used only to wrap/unwrap DEKs
  - one DEK per service domain (board, user-pii), version 1, wrapped with the KEK

Both KEK and DEK are stored versioned (kek-v1/dek-v1 + current-version), matching
vault-crypto 0.0.6's KekProvider/DekProvider format. The wrapped-DEK bytes carry a
1-byte kekVersion header (matching KekService.wrap()):
    kekVersion(1B) | IV(12B) | ciphertext(32B) | tag(16B)

It then prints ready-to-run `vault kv put` commands. The current KMS is OpenBao 2.6.2
at http://192.168.2.57:28200 (Vault-compatible API), so point VAULT_ADDR/BAO_ADDR there;
the same commands work with either the `vault` or `bao` CLI. This script does NOT talk
to Vault itself - review the printed commands, then run them yourself against
the target Vault server (with VAULT_ADDR / VAULT_TOKEN set in your shell).

Usage:
  my_env/Scripts/python.exe bootstrap_kek_dek.py   (Windows)
  my_env/bin/python3 bootstrap_kek_dek.py          (Linux/Mac)

Requires: pycryptodome (already used by decrypt_passwords.py / installed in my_env)
"""
import base64
import os

from Cryptodome.Cipher import AES

DOMAINS = ["board", "user-pii"]
MOUNT = "ebiz_service"
BASE_PATH = "ebiz_db"
KEK_VERSION = 1


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).decode("ascii").rstrip("=")


def wrap(kek: bytes, kek_version: int, plaintext: bytes) -> bytes:
    """AES-256-GCM wrap: kekVersion(1B) + IV(12B) + ciphertext + tag(16B), matching KekService.wrap()."""
    iv = os.urandom(12)
    cipher = AES.new(kek, AES.MODE_GCM, nonce=iv)
    ciphertext, tag = cipher.encrypt_and_digest(plaintext)
    return bytes([kek_version]) + iv + ciphertext + tag


def main():
    kek = os.urandom(32)

    print("=" * 78)
    print("1) Store the KEK (master key, version=1 - used only to wrap DEKs)")
    print("=" * 78)
    print(f"vault kv put -mount={MOUNT} {BASE_PATH}/kek \\\n"
          f"  kek-v{KEK_VERSION}=\"{b64url(kek)}\" \\\n"
          f"  current-version=\"{KEK_VERSION}\"\n")

    print("=" * 78)
    print("2) Generate and store one DEK per domain (version=1, wrapped with KEK v1)")
    print("=" * 78)
    for domain in DOMAINS:
        dek = os.urandom(32)
        wrapped = wrap(kek, KEK_VERSION, dek)
        print(f"# domain: {domain}")
        print(f"vault kv put -mount={MOUNT} {BASE_PATH}/dek/{domain} \\\n"
              f"  dek-v1=\"{b64url(wrapped)}\" \\\n"
              f"  current-version=\"1\"\n")

    print("=" * 78)
    print("Verification commands")
    print("=" * 78)
    print(f"vault kv get -mount={MOUNT} {BASE_PATH}/kek")
    for domain in DOMAINS:
        print(f"vault kv get -mount={MOUNT} {BASE_PATH}/dek/{domain}")

    print("\n[WARNING] This output contains the plaintext KEK and the wrapped DEKs.")
    print("          Do not leave it in terminal history/logs; run the commands above and then clear the screen immediately.")
    print("          The generated keys differ every time this script runs, so apply only the result of the last run.")
    print("\n[NOTE] If kek/dek secrets created with vault-crypto 0.0.5 or earlier already exist, overwrite them with this output")
    print("       so they match 0.0.6's version-aware format (which includes the kekVersion header). See KEY_ROTATION_RUNBOOK.md.")


if __name__ == "__main__":
    main()
