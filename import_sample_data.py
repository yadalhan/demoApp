#!/usr/bin/env python3
"""샘플 JSON(board/users)을 myapp DB로 일괄 import한다.

JSON의 암호화 대상 컬럼은 **암호화 전 평문**이므로, 이 스크립트가 vault-crypto와 동일한 방식으로
암호화해서 넣는다.

  board.password                        → 봉투 암호화(도메인 board, domainCode=1)
  users.id_no, users.phone              → 봉투 암호화(도메인 user-pii, domainCode=2)
  users.phone_blind_idx, id_no_blind_idx → HMAC-SHA256 blind index (Base64URL, 43자)
  users.password                        → 이미 BCrypt 해시면 그대로, 평문이면 BCrypt(rounds=10)로 해시

암호화에 쓰는 키(모두 OpenBao에서 읽는다):
  - wrapped DEK: {mount}/data/{base}/dek/{domain}  (dek-v{n} = Base64URL(UTF-8("vault:vN:...")))
  - DEK 평문:    transit/decrypt/{transit_key} 로만 획득 (KEK는 반출되지 않음)
  - blind index: {mount}/data/{base}/blind-index/{user-phone,user-rrn}

전송 방식: psql 클라이언트의 \\copy(CSV)로 한 트랜잭션에 적재한다(psycopg2 불필요).
BCrypt가 필요한 경우에만 `bcrypt` 모듈을 사용한다(이미 해시면 불필요).

사용 예)
  # 미리보기(아무것도 쓰지 않음)
  ./import_sample_data.py --board board.json --users users.json --dry-run

  # 적재 (테이블이 비어 있을 때만)
  ./import_sample_data.py --board board.json --users users.json

  # 기존 데이터를 지우고 다시 적재
  ./import_sample_data.py --board board.json --users users.json --truncate

  # DB/토큰을 직접 지정
  ./import_sample_data.py --db-host 192.168.2.57 --db-password-file ~/.myapp/dbpw \
      --vault-token-file ~/.myapp/token

  # https + 사설 CA + 네임스페이스 (myapp 현재 구성: namespace=serice_a, transit key=kek)
  set -a; . ~/.openbao/ops.env; set +a          # VAULT_ADDR / VAULT_CACERT / VAULT_TOKEN
  ./import_sample_data.py --board board.json --users users.json \
      --namespace serice_a --transit-key kek --truncate
"""
from __future__ import annotations

import argparse
import base64
import csv
import hashlib
import hmac
import json
import os
import re
import ssl
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

# ---------------------------------------------------------------- constants
BCRYPT_PATTERN = re.compile(r"^\$2[aby]\$\d{2}\$[./A-Za-z0-9]{53}$")
DEK_LENGTH_BYTES = 32
IV_LENGTH_BYTES = 12
TAG_LENGTH_BYTES = 16

# demoApp CryptoConfig와 동일한 도메인 코드
DOMAIN_CODES = {"board": 1, "user-pii": 2}

BOARD_COLUMNS = ("id", "created_date", "modified_date", "author", "content", "title", "password")
USER_COLUMNS = ("id", "password", "user_id", "username", "id_no", "phone",
                "phone_blind_idx", "id_no_blind_idx")


# ---------------------------------------------------------------- encoding helpers
def b64u(raw: bytes) -> str:
    """Base64URL, 패딩 없음 (vault-crypto와 동일)."""
    return base64.urlsafe_b64encode(raw).decode().rstrip("=")


def b64u_decode(text: str) -> bytes:
    return base64.urlsafe_b64decode(text + "=" * ((4 - len(text) % 4) % 4))


def envelope_encrypt(dek: bytes, domain_code: int, key_version: int, plaintext: str) -> str:
    """domainCode(1) | keyVersion(1) | IV(12) | AES-256-GCM ciphertext | tag(16) → Base64URL."""
    iv = os.urandom(IV_LENGTH_BYTES)
    ciphertext_and_tag = AESGCM(dek).encrypt(iv, plaintext.encode("utf-8"), None)
    assert len(ciphertext_and_tag) >= TAG_LENGTH_BYTES
    return b64u(bytes([domain_code, key_version]) + iv + ciphertext_and_tag)


def envelope_decrypt(dek: bytes, encrypted: str) -> str:
    """검증용 - import 결과를 되돌려 평문과 비교한다(앱과 동일 포맷 확인)."""
    combined = b64u_decode(encrypted)
    if len(combined) < 2 + IV_LENGTH_BYTES + TAG_LENGTH_BYTES:
        raise ValueError(f"envelope too short: {len(combined)} bytes")
    iv = combined[2:2 + IV_LENGTH_BYTES]
    return AESGCM(dek).decrypt(iv, combined[2 + IV_LENGTH_BYTES:], None).decode("utf-8")


def envelope_version(encrypted: str) -> int:
    return b64u_decode(encrypted)[1]


def blind_index(key: bytes, value: str) -> str:
    """HMAC-SHA256 → Base64URL(43자). 호출 전 정규화는 호출자 책임(앱과 동일 규칙)."""
    return b64u(hmac.new(key, value.encode("utf-8"), hashlib.sha256).digest())


def normalize_phone(phone: str) -> str:
    """UserService.normalizePhone()과 동일: 숫자만 남긴다."""
    return re.sub(r"[^0-9]", "", phone or "")


def normalize_rrn(rrn: str) -> str:
    """앱은 주민번호를 정규화하지 않고 front+back 13자리를 그대로 쓴다."""
    return (rrn or "").strip()


def bcrypt_value(raw: str) -> tuple[str, bool]:
    """이미 BCrypt 해시면 그대로, 평문이면 해시한다. (값, 해시여부) 반환."""
    if BCRYPT_PATTERN.match(raw or ""):
        return raw, False
    import bcrypt  # 평문이 섞여 있을 때만 필요

    return bcrypt.hashpw(raw.encode("utf-8"), bcrypt.gensalt(rounds=10)).decode(), True


# ---------------------------------------------------------------- OpenBao client
class OpenBao:
    def __init__(self, addr: str, token: str, namespace: str | None = None,
                 cacert: str | None = None, timeout: int = 20):
        self.addr = addr.rstrip("/")
        self.token = token
        self.namespace = (namespace or "").strip()
        self.timeout = timeout
        # https with a private CA needs an explicit trust anchor (urllib does not read
        # VAULT_CACERT on its own)
        self.ssl_context = ssl.create_default_context(cafile=cacert) if cacert else None

    def call(self, method: str, path: str, payload: dict | None = None) -> dict:
        data = json.dumps(payload).encode() if payload is not None else None
        req = urllib.request.Request(f"{self.addr}/v1/{path.lstrip('/')}", data=data, method=method)
        req.add_header("X-Vault-Token", self.token)
        if self.namespace:
            # a namespace token is only valid inside its namespace; -orphan tokens issued there
            # still need this header on every request
            req.add_header("X-Vault-Namespace", self.namespace)
        if data:
            req.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(req, timeout=self.timeout, context=self.ssl_context) as resp:
                body = resp.read().decode() or "{}"
                return json.loads(body) if body.strip() else {}
        except urllib.error.HTTPError as e:
            detail = e.read().decode(errors="replace")[:300]
            raise SystemExit(f"OpenBao {method} {path} -> HTTP {e.code}: {detail}")
        except urllib.error.URLError as e:
            raise SystemExit(f"OpenBao 연결 실패({self.addr}): {e.reason}")

    def kv_read(self, mount: str, path: str) -> dict:
        return self.call("GET", f"{mount}/data/{path}")["data"]["data"]

    def transit_decrypt(self, transit_mount: str, key: str, ciphertext: str) -> bytes:
        resp = self.call("POST", f"{transit_mount}/decrypt/{key}", {"ciphertext": ciphertext})
        return base64.b64decode(resp["data"]["plaintext"])


@dataclass
class DomainKey:
    domain: str
    domain_code: int
    version: int
    dek: bytes


def load_domain_key(bao: OpenBao, mount: str, base: str, transit_mount: str, transit_key: str,
                    domain: str) -> DomainKey:
    secret = bao.kv_read(mount, f"{base}/dek/{domain}")
    version = int(secret.get("current-version", 1))
    field = secret.get(f"dek-v{version}")
    if not field:
        raise SystemExit(f"{base}/dek/{domain}: dek-v{version} 필드가 없습니다")
    try:
        wrapped = b64u_decode(field).decode("utf-8")
    except (ValueError, UnicodeDecodeError) as e:
        raise SystemExit(
            f"{base}/dek/{domain}: dek-v{version} 값이 Base64URL(UTF-8(ciphertext)) 형식이 아닙니다 - {e}")
    if not wrapped.startswith("vault:"):
        raise SystemExit(f"{base}/dek/{domain}: Transit ciphertext가 아닙니다: {wrapped[:12]}...")
    dek = bao.transit_decrypt(transit_mount, transit_key, wrapped)
    if len(dek) != DEK_LENGTH_BYTES:
        raise SystemExit(f"{base}/dek/{domain}: DEK 길이 {len(dek)} (기대 32)")
    return DomainKey(domain=domain, domain_code=DOMAIN_CODES[domain], version=version, dek=dek)


# ---------------------------------------------------------------- data transform
def transform_users(rows: list[dict], user_pii: DomainKey, phone_key: bytes, rrn_key: bytes,
                    report: dict) -> list[tuple]:
    out = []
    for row in rows:
        rrn = normalize_rrn(str(row.get("id_no", "")))
        phone = normalize_phone(str(row.get("phone", "")))
        password, hashed_now = bcrypt_value(str(row.get("password", "")))
        if hashed_now:
            report["bcrypt_hashed"] += 1
        out.append((
            row["id"],
            password,
            row["user_id"],
            row["username"],
            envelope_encrypt(user_pii.dek, user_pii.domain_code, user_pii.version, rrn) if rrn else None,
            envelope_encrypt(user_pii.dek, user_pii.domain_code, user_pii.version, phone) if phone else None,
            blind_index(phone_key, phone) if phone else None,
            blind_index(rrn_key, rrn) if rrn else None,
        ))
    return out


def transform_board(rows: list[dict], board_key: DomainKey, report: dict) -> list[tuple]:
    out = []
    for row in rows:
        password = row.get("password")
        if password in (None, ""):
            encrypted = None
            report["board_blank_password"] += 1
        else:
            encrypted = envelope_encrypt(board_key.dek, board_key.domain_code, board_key.version,
                                         str(password))
        out.append((
            row["id"], row.get("created_date"), row.get("modified_date"), row.get("author"),
            row.get("content"), row.get("title"), encrypted,
        ))
    return out


# ---------------------------------------------------------------- psql (COPY) writer
def run_psql_file(args, sql_path: Path) -> str:
    """psql COPY 메타 명령은 -c 가 아니라 -f(파일)로만 실행된다."""
    env = dict(os.environ)
    if args.db_password:
        env["PGPASSWORD"] = args.db_password
    cmd = [
        args.psql,
        "-h", args.db_host, "-p", str(args.db_port), "-U", args.db_user, "-d", args.db_name,
        "-v", "ON_ERROR_STOP=1", "--no-psqlrc", "-q",
        "-f", str(sql_path),
    ]
    proc = subprocess.run(cmd, env=env, capture_output=True, text=True)
    if proc.returncode != 0:
        raise SystemExit(f"psql 실패(롤백됨):\n{proc.stderr.strip()[:2000]}")
    return proc.stdout


def query_scalar(args, sql: str) -> str:
    env = dict(os.environ)
    if args.db_password:
        env["PGPASSWORD"] = args.db_password
    cmd = [args.psql, "-h", args.db_host, "-p", str(args.db_port), "-U", args.db_user,
           "-d", args.db_name, "-v", "ON_ERROR_STOP=1", "--no-psqlrc", "-At", "-c", sql]
    proc = subprocess.run(cmd, env=env, capture_output=True, text=True)
    if proc.returncode != 0:
        raise SystemExit(f"psql 실패:\n{proc.stderr.strip()[:2000]}")
    return proc.stdout.strip()


def write_csv(path: Path, header: tuple[str, ...], rows: list[tuple]) -> None:
    """COPY ... (format csv) 는 header 라인을 읽지 않으므로 데이터만 쓴다."""
    del header  # 컬럼 순서는 COPY 문에서 지정한다
    with open(path, "w", newline="", encoding="utf-8") as fh:
        writer = csv.writer(fh, quoting=csv.QUOTE_MINIMAL)
        for row in rows:
            writer.writerow(["" if v is None else v for v in row])


def copy_from(path: Path, table: str, columns: tuple[str, ...], null_marker: bool) -> str:
    opts = "format csv"
    if null_marker:
        opts += ", null ''"          # 빈 문자열은 NULL로
    return f"\\copy {table} ({', '.join(columns)}) from '{path}' with ({opts})"


# ---------------------------------------------------------------- main
def parse_args(argv=None):
    p = argparse.ArgumentParser(description="샘플 JSON을 myapp DB로 import (암호화 포함)")
    p.add_argument("--board", default="board.json", help="board JSON 경로 (기본 board.json)")
    p.add_argument("--users", default="users.json", help="users JSON 경로 (기본 users.json)")

    p.add_argument("--vault-addr",
                   default=os.environ.get("BAO_ADDR") or os.environ.get("VAULT_ADDR")
                   or "http://192.168.2.57:28200")
    p.add_argument("--vault-token", default=os.environ.get("VAULT_TOKEN") or os.environ.get("BAO_TOKEN"))
    p.add_argument("--vault-token-file", default="~/.myapp/token")
    p.add_argument("--vault-cacert",
                   default=os.environ.get("BAO_CACERT") or os.environ.get("VAULT_CACERT"),
                   help="https 인증서 검증용 CA 파일(사설 CA일 때 필요)")
    p.add_argument("--namespace",
                   default=os.environ.get("BAO_NAMESPACE") or os.environ.get("VAULT_NAMESPACE"),
                   help="OpenBao 네임스페이스 (X-Vault-Namespace)")
    p.add_argument("--mount", default="myapp_service", help="kv-v2 mount")
    p.add_argument("--base", default="kms", help="기본 경로(base)")
    p.add_argument("--transit-mount", default="transit")
    p.add_argument("--transit-key",
                   default=os.environ.get("BAO_TRANSIT_KEY") or os.environ.get("VAULT_TRANSIT_KEY")
                   or "myapp-kek2")

    p.add_argument("--db-host", default=os.environ.get("DB_HOST", "192.168.2.57"))
    p.add_argument("--db-port", default=int(os.environ.get("DB_PORT", "21716")), type=int)
    p.add_argument("--db-name", default=os.environ.get("DB_NAME", "myappdb"))
    p.add_argument("--db-user", default=os.environ.get("DB_USERNAME", "myapp"))
    p.add_argument("--db-password", default=os.environ.get("PGPASSWORD"))
    p.add_argument("--db-password-file", default="~/.myapp/dbpw")
    p.add_argument("--psql", default="psql", help="psql 실행 경로")

    p.add_argument("--truncate", action="store_true", help="적재 전에 users/board 를 비운다")
    p.add_argument("--append", action="store_true",
                   help="기존 데이터를 유지하고 추가한다(이미 있는 id/user_id 행은 건너뜀)")
    p.add_argument("--dry-run", action="store_true", help="DB에 쓰지 않고 변환 결과만 확인")
    p.add_argument("--limit", type=int, default=0, help="행 수 제한(테스트용, 0=전체)")
    return p.parse_args(argv)


def resolve_secret(value: str | None, file_path: str | None) -> str | None:
    if value:
        return value.strip()
    if file_path:
        path = Path(file_path).expanduser()
        if path.is_file():
            return path.read_text(encoding="utf-8").strip()
    return None


def main(argv=None) -> int:
    args = parse_args(argv)

    args.vault_token = resolve_secret(args.vault_token, args.vault_token_file)
    if not args.vault_token:
        raise SystemExit("OpenBao 토큰이 필요합니다 (--vault-token, VAULT_TOKEN, 또는 ~/.myapp/token)")
    args.db_password = resolve_secret(args.db_password, args.db_password_file)
    if not args.db_password:
        raise SystemExit("DB 비밀번호가 필요합니다 (--db-password, PGPASSWORD, 또는 ~/.myapp/dbpw)")

    board_rows = json.load(open(Path(args.board), encoding="utf-8"))["board"]
    user_rows = json.load(open(Path(args.users), encoding="utf-8"))["users"]
    if args.limit:
        board_rows = board_rows[:args.limit]
        user_rows = user_rows[:args.limit]

    print(f"입력: board {len(board_rows)}건, users {len(user_rows)}건")
    print(f"대상 DB: {args.db_user}@{args.db_host}:{args.db_port}/{args.db_name}")
    print(f"OpenBao: {args.vault_addr} (namespace={args.namespace or 'root'}, mount={args.mount}, base={args.base}, "
          f"transit={args.transit_mount}/keys/{args.transit_key}, cacert={args.vault_cacert or '-'})")

    # 1) 키 로드
    bao = OpenBao(args.vault_addr, args.vault_token, namespace=args.namespace, cacert=args.vault_cacert)
    board_key = load_domain_key(bao, args.mount, args.base, args.transit_mount, args.transit_key, "board")
    user_key = load_domain_key(bao, args.mount, args.base, args.transit_mount, args.transit_key, "user-pii")
    phone_key = bao.kv_read(args.mount, f"{args.base}/blind-index/user-phone")["key"]
    rrn_key = bao.kv_read(args.mount, f"{args.base}/blind-index/user-rrn")["key"]
    phone_key, rrn_key = b64u_decode(phone_key), b64u_decode(rrn_key)
    print(f"키 로드 완료: board DEK v{board_key.version}(domainCode={board_key.domain_code}), "
          f"user-pii DEK v{user_key.version}(domainCode={user_key.domain_code}), "
          f"blind-index {len(phone_key)}/{len(rrn_key)} bytes")

    # 2) 변환
    report = {"bcrypt_hashed": 0, "board_blank_password": 0}
    users_out = transform_users(user_rows, user_key, phone_key, rrn_key, report)
    board_out = transform_board(board_rows, board_key, report)
    print(f"변환 완료: users {len(users_out)}건, board {len(board_out)}건"
          + (f", BCrypt 신규 해시 {report['bcrypt_hashed']}건" if report["bcrypt_hashed"] else "")
          + (f", password 없음 {report['board_blank_password']}건" if report["board_blank_password"] else ""))

    # 3) 자체 검증(암호화 round-trip) - 앱과 같은 포맷인지 확인
    sample = next((r for r in user_rows if r.get("phone")), None)
    if sample:
        stored = users_out[user_rows.index(sample)][5]
        plain = envelope_decrypt(user_key.dek, stored)
        expected = normalize_phone(str(sample["phone"]))
        if plain != expected:
            raise SystemExit(f"round-trip 실패: {plain!r} != {expected!r}")
        if envelope_version(stored) != user_key.version:
            raise SystemExit("round-trip 실패: keyVersion 헤더 불일치")
        print(f"round-trip 확인: users.phone 암호문 {len(stored)}자 → 복호화 일치, keyVersion={user_key.version}")

    if args.dry_run:
        print("\n[dry-run] DB에 쓰지 않았습니다. 변환 샘플:")
        if users_out:
            u = users_out[0]
            print(f"  users[0] id={u[0]} user_id={u[2]} pw={u[1][:7]}... "
                  f"id_no={u[4][:12]}...({len(u[4])}자) phone={u[5][:12]}...({len(u[5])}자) "
                  f"phone_idx={u[6]} rrn_idx={u[7]}")
        if board_out:
            b = board_out[0]
            print(f"  board[0] id={b[0]} title={b[5]!r} password={b[6][:12]}...({len(b[6])}자)")
        return 0

    # 4) 적재 전 확인 - 비어 있지 않으면 거부(실수 방지)
    existing_b = int(query_scalar(args, "select count(*) from ebiz.board") or 0)
    existing_u = int(query_scalar(args, "select count(*) from ebiz.users") or 0)
    skipped_b = skipped_u = 0
    if (existing_b or existing_u) and args.append:
        # 기존 행과 id/user_id가 겹치는 샘플은 건너뛴다(UNIQUE 위반으로 전체가 롤백되는 것을 방지)
        ids_u = {int(x) for x in query_scalar(args, "select id from ebiz.users").split() if x}
        uids_u = set(query_scalar(args, "select user_id from ebiz.users").splitlines())
        ids_b = {int(x) for x in query_scalar(args, "select id from ebiz.board").split() if x}
        filtered_u = [r for r in users_out if r[0] not in ids_u and r[2] not in uids_u]
        skipped_u = len(users_out) - len(filtered_u)
        filtered_b = [r for r in board_out if r[0] not in ids_b]
        skipped_b = len(board_out) - len(filtered_b)
        users_out, board_out = filtered_u, filtered_b
        print(f"추가 모드: 기존 행과 충돌하는 샘플 건너뜀 (users {skipped_u}건, board {skipped_b}건)")
    if (existing_b or existing_u) and not (args.truncate or args.append):
        raise SystemExit(
            f"중단: 이미 데이터가 있습니다(users {existing_u}건, board {existing_b}건). "
            "덮어쓰려면 --truncate 를 명시하세요.")

    tmpdir = Path(tempfile.mkdtemp(prefix="myapp-import-"))
    os.chmod(tmpdir, 0o700)
    users_csv, board_csv = tmpdir / "users.csv", tmpdir / "board.csv"
    write_csv(users_csv, USER_COLUMNS, users_out)
    write_csv(board_csv, BOARD_COLUMNS, board_out)

    sql = ["BEGIN;"]
    if args.truncate:
        sql.append("TRUNCATE TABLE ebiz.users, ebiz.board RESTART IDENTITY;")
    sql.append(copy_from(users_csv, "ebiz.users", USER_COLUMNS, null_marker=True) + ";")
    sql.append(copy_from(board_csv, "ebiz.board", BOARD_COLUMNS, null_marker=True) + ";")
    sql.append("SELECT setval(pg_get_serial_sequence('ebiz.users','id'), "
               "COALESCE((SELECT MAX(id) FROM ebiz.users), 1));")
    sql.append("SELECT setval(pg_get_serial_sequence('ebiz.board','id'), "
               "COALESCE((SELECT MAX(id) FROM ebiz.board), 1));")
    sql.append("COMMIT;")
    script = tmpdir / "import.sql"
    script.write_text("\n".join(sql) + "\n", encoding="utf-8")

    print(f"\n적재 실행 (psql \\copy, 트랜잭션 1개): {args.db_name}.ebiz")
    run_psql_file(args, script)

    # 5) 결과 확인
    u_cnt = query_scalar(args, "select count(*) from ebiz.users")
    b_cnt = query_scalar(args, "select count(*) from ebiz.board")
    null_pii = query_scalar(args, "select count(*) from ebiz.users where phone_blind_idx is null")
    print(f"적재 완료: users {u_cnt}건, board {b_cnt}건 (blind index 누락 {null_pii}건)")
    print(f"시퀀스: users → {query_scalar(args, 'select last_value from ebiz.users_id_seq')}, "
          f"board → {query_scalar(args, 'select last_value from ebiz.board_id_seq')}")

    for f in (users_csv, board_csv):
        f.unlink(missing_ok=True)
    script.unlink(missing_ok=True)
    tmpdir.rmdir()
    return 0


if __name__ == "__main__":
    sys.exit(main())
