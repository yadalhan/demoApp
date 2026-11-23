#!/usr/bin/env python3
"""Migrate demoApp's wrapped DEKs from the KV KEK to OpenBao Transit (vault-crypto 2.1.0).

The data (ciphertext) is left untouched; only the wrapped DEKs in KV are converted to Transit ciphertext.
The pre-migration values are saved to a backup file (default ./dek_transit_backup.json, mode 600).

  BAO_TOKEN=<admin> python3 migrate_dek_to_transit.py --backup ./dek_transit_backup.json

After the migration:
  1) Add transit/decrypt/kek to the demoapp-read policy
  2) Deploy demoApp 0.0.21 (TransitKeyWrapper)
  3) After verification, delete the KV kek path and remove kek read from the policy
"""
from __future__ import annotations

import argparse
import base64
import json
import os
import sys
import urllib.error
import urllib.request

from cryptography.hazmat.primitives.ciphers.aead import AESGCM


def b64u(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).decode().rstrip("=")


def b64u_decode(text: str) -> bytes:
    return base64.urlsafe_b64decode(text + "=" * ((4 - len(text) % 4) % 4))


def call(addr: str, token: str, method: str, path: str, payload: dict | None = None) -> dict:
    url = f"{addr.rstrip('/')}/v1/{path.lstrip('/')}"
    data = json.dumps(payload).encode() if payload is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("X-Vault-Token", token)
    if data:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            body = resp.read().decode() or "{}"
            return json.loads(body)
    except urllib.error.HTTPError as e:
        raise SystemExit(f"{method} {path} -> HTTP {e.code}: {e.read().decode(errors='replace')[:300]}")


def kv_read(addr, token, mount, path) -> dict:
    return call(addr, token, "GET", f"{mount}/data/{path}")["data"]["data"]


def kv_write(addr, token, mount, path, data) -> None:
    call(addr, token, "POST", f"{mount}/data/{path}", {"data": data})


def unwrap_dek(wrapped: bytes, kek: bytes) -> bytes:
    """KV KEK format: kekVersion(1) | IV(12) | ciphertext+tag(48)."""
    if len(wrapped) != 61:
        raise SystemExit(f"unexpected wrapped DEK length {len(wrapped)} (expected 61)")
    if wrapped[0] != 1:
        raise SystemExit(f"unsupported KEK version byte {wrapped[0]}")
    return AESGCM(kek).decrypt(wrapped[1:13], wrapped[13:], None)


def transit_encrypt(addr, token, mount, key, dek: bytes) -> str:
    return call(addr, token, "POST", f"{mount}/encrypt/{key}",
                {"plaintext": base64.b64encode(dek).decode()})["data"]["ciphertext"]


def transit_decrypt(addr, token, mount, key, ciphertext: str) -> bytes:
    return base64.b64decode(
        call(addr, token, "POST", f"{mount}/decrypt/{key}", {"ciphertext": ciphertext})["data"]["plaintext"])


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--addr", default="http://192.168.2.57:28200")
    ap.add_argument("--token", default=os.environ.get("BAO_TOKEN", ""))
    ap.add_argument("--mount", default="ebiz_service")
    ap.add_argument("--base", default="ebiz_db")
    ap.add_argument("--domains", default="board,user-pii")
    ap.add_argument("--transit-mount", default="transit")
    ap.add_argument("--transit-key", default="kek")
    ap.add_argument("--backup", default="./dek_transit_backup.json")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    if not args.token:
        raise SystemExit("BAO_TOKEN is required")
    try:
        call(args.addr, args.token, "GET", f"{args.transit_mount}/keys/{args.transit_key}")
    except SystemExit as e:
        raise SystemExit(f"transit key not reachable ({args.transit_mount}/keys/{args.transit_key}): {e}")

    kek_rec = kv_read(args.addr, args.token, args.mount, f"{args.base}/kek")
    kek = b64u_decode(kek_rec[f"kek-v{kek_rec['current-version']}"])
    print(f"-- KEK v{kek_rec['current-version']} loaded ({len(kek)} bytes)")

    backup: dict[str, dict] = {}
    for domain in [d.strip() for d in args.domains.split(",") if d.strip()]:
        rec = kv_read(args.addr, args.token, args.mount, f"{args.base}/dek/{domain}")
        backup[domain] = rec
        new_fields: dict[str, str] = {}
        for field, value in rec.items():
            if not field.startswith("dek-v"):
                continue
            plain = unwrap_dek(b64u_decode(value), kek)
            ciphertext = transit_encrypt(args.addr, args.token, args.transit_mount, args.transit_key, plain)
            if transit_decrypt(args.addr, args.token, args.transit_mount, args.transit_key, ciphertext) != plain:
                raise SystemExit(f"FAIL: transit roundtrip mismatch for {domain}/{field}")
            new_fields[field] = b64u(ciphertext.encode())
            print(f"-- {domain}/{field}: {value[:12]}... -> {ciphertext[:16]}...")
        if not new_fields:
            raise SystemExit(f"FAIL: no dek-v fields for {domain}")
        new_fields["current-version"] = rec["current-version"]
        if not args.dry_run:
            kv_write(args.addr, args.token, args.mount, f"{args.base}/dek/{domain}", new_fields)
            print(f"-- {domain}: {len(new_fields) - 1} wrapped DEK(s) migrated")

    if not args.dry_run:
        with open(args.backup, "w", encoding="utf-8") as fh:
            json.dump(backup, fh, indent=2)
        os.chmod(args.backup, 0o600)
        print(f"-- backup written: {args.backup} (600)")
    else:
        print("-- dry-run: nothing written")
    return 0


if __name__ == "__main__":
    sys.exit(main())
