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

It then prints ready-to-run `vault kv put` commands. This script does NOT talk
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
    print("1) KEK 저장 (마스터 키, version=1 - DEK를 wrap하는 용도로만 사용)")
    print("=" * 78)
    print(f"vault kv put -mount={MOUNT} {BASE_PATH}/kek \\\n"
          f"  kek-v{KEK_VERSION}=\"{b64url(kek)}\" \\\n"
          f"  current-version=\"{KEK_VERSION}\"\n")

    print("=" * 78)
    print("2) 도메인별 DEK 생성 및 저장 (version=1, KEK v1로 wrap됨)")
    print("=" * 78)
    for domain in DOMAINS:
        dek = os.urandom(32)
        wrapped = wrap(kek, KEK_VERSION, dek)
        print(f"# domain: {domain}")
        print(f"vault kv put -mount={MOUNT} {BASE_PATH}/dek/{domain} \\\n"
              f"  dek-v1=\"{b64url(wrapped)}\" \\\n"
              f"  current-version=\"1\"\n")

    print("=" * 78)
    print("확인용 명령어")
    print("=" * 78)
    print(f"vault kv get -mount={MOUNT} {BASE_PATH}/kek")
    for domain in DOMAINS:
        print(f"vault kv get -mount={MOUNT} {BASE_PATH}/dek/{domain}")

    print("\n[주의] 이 출력에는 평문 KEK와 wrap된 DEK가 포함되어 있습니다.")
    print("       터미널 기록/로그에 남기지 말고, 위 명령을 실행한 후 즉시 화면을 지우세요.")
    print("       생성된 키는 이 스크립트를 다시 실행할 때마다 달라지므로, 마지막에 실행한 결과만 적용하세요.")
    print("\n[참고] vault-crypto 0.0.5 이하로 만든 kek/dek 시크릿이 이미 있다면, 이 출력으로 덮어써야")
    print("       0.0.6의 버전 인식 포맷(kekVersion 헤더 포함)과 맞습니다. KEY_ROTATION_RUNBOOK.md 참고.")


if __name__ == "__main__":
    main()
