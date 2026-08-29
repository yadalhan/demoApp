#!/usr/bin/env python3
"""
Blind index key bootstrap helper for demoApp (see vault-crypto README.md
"4. 암호화된 컬럼 검색 - Blind Index" for background).

Generates locally (no network calls, no Vault access) one 32-byte random HMAC-SHA256
key per searchable field (user-phone, user-rrn) - independent of the KEK/DEK used for
envelope encryption, and unversioned (see BlindIndexKeyProvider's Javadoc for why:
rotating a blind index key requires reindexing every row in one pass, unlike DEK
rotation, so there's no "current version" concept to track here).

It then prints ready-to-run `vault kv put` commands. This script does NOT talk to
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
    print("필드별 blind index HMAC 키 생성 및 저장 (버전 없음)")
    print("=" * 78)
    for index_name in INDEX_NAMES:
        key = os.urandom(32)
        print(f"# index: {index_name}")
        print(f"vault kv put -mount={MOUNT} {BASE_PATH}/{index_name} \\\n"
              f"  key=\"{b64url(key)}\"\n")

    print("=" * 78)
    print("확인용 명령어")
    print("=" * 78)
    for index_name in INDEX_NAMES:
        print(f"vault kv get -mount={MOUNT} {BASE_PATH}/{index_name}")

    print("\n[주의] 이 출력에는 평문 HMAC 키가 포함되어 있습니다.")
    print("       터미널 기록/로그에 남기지 말고, 위 명령을 실행한 후 즉시 화면을 지우세요.")
    print("       생성된 키는 이 스크립트를 다시 실행할 때마다 달라집니다 - 이미 데이터가 저장된")
    print("       뒤에 키를 바꾸면 기존 phone_blind_idx/id_no_blind_idx 값이 전부 무효화되므로")
    print("       (재인덱싱 전까지 해당 컬럼 검색이 아무것도 찾지 못하게 됨), 최초 1회만 실행하세요.")


if __name__ == "__main__":
    main()
