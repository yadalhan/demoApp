#!/usr/bin/env python3
"""Bulk-import sample JSON (board/users) into the myapp database.

Encrypted columns in the JSON hold plaintext, so this script applies the same envelope
encryption vault-crypto uses before loading the rows.

  board.password                         -> envelope encryption (domain "board", domainCode=1)
  users.id_no, users.phone               -> envelope encryption (domain "user-pii", domainCode=2)
  users.phone_blind_idx, id_no_blind_idx -> HMAC-SHA256 blind index (Base64URL, 43 chars)
  users.password                         -> kept if already BCrypt, otherwise hashed with BCrypt(rounds=10)

Keys used for encryption (all read from OpenBao):
  - wrapped DEK: {mount}/data/{base}/dek/{domain}  (dek-v{n} = Base64URL(UTF-8("vault:vN:...")))
  - plaintext DEK: only via transit/decrypt/{transit_key} (the KEK never leaves Transit)
  - blind index: {mount}/data/{base}/blind-index/{user-phone,user-rrn}

Transport: loaded in a single transaction through the psql client (\\copy CSV), so psycopg2 is not needed.
The `bcrypt` module is imported only when a plaintext password actually needs hashing.

Examples)
  # preview only (writes nothing)
  ./import_sample_data.py --board board.json --users users.json --dry-run

  # load (only when the tables are empty)
  ./import_sample_data.py --board board.json --users users.json

  # wipe existing rows and load again
  ./import_sample_data.py --board board.json --users users.json --truncate

  # explicit database/token
  ./import_sample_data.py --db-host 192.168.2.57 --db-password-file ~/.myapp/dbpw \
      --vault-token-file ~/.myapp/token

  # https + private CA + namespace (current myapp setup: namespace=serice_a, transit key=kek)
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

# Domain codes - must match the ones stored in existing ciphertext headers
DOMAIN_CODES = {"board": 1, "user-pii": 2}

BOARD_COLUMNS = ("id", "created_date", "modified_date", "author", "content", "title", "password")
USER_COLUMNS = ("id", "password", "user_id", "username", "id_no", "phone",
                "phone_blind_idx", "id_no_blind_idx")


# ---------------------------------------------------------------- encoding helpers
def b64u(raw: bytes) -> str:
    """Base64URL without padding (same encoding vault-crypto uses)."""
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
    """Verification helper: decrypt an imported value to prove the app can read the format."""
    combined = b64u_decode(encrypted)
    if len(combined) < 2 + IV_LENGTH_BYTES + TAG_LENGTH_BYTES:
        raise ValueError(f"envelope too short: {len(combined)} bytes")
    iv = combined[2:2 + IV_LENGTH_BYTES]
    return AESGCM(dek).decrypt(iv, combined[2 + IV_LENGTH_BYTES:], None).decode("utf-8")


def envelope_version(encrypted: str) -> int:
    return b64u_decode(encrypted)[1]


def blind_index(key: bytes, value: str) -> str:
    """HMAC-SHA256 -> Base64URL (43 chars). Normalization is the caller's job, as in the app."""
    return b64u(hmac.new(key, value.encode("utf-8"), hashlib.sha256).digest())


def normalize_phone(phone: str) -> str:
    """Same rule as UserService.normalizePhone(): keep digits only."""
    return re.sub(r"[^0-9]", "", phone or "")


def normalize_rrn(rrn: str) -> str:
    """The app does not normalize the RRN; front+back (13 digits) is used as-is."""
    return (rrn or "").strip()


def bcrypt_value(raw: str) -> tuple[str, bool]:
    """Return the value unchanged if it is already a BCrypt hash, else hash it. -> (value, hashed)."""
    if BCRYPT_PATTERN.match(raw or ""):
        return raw, False
    import bcrypt  # only needed when a plaintext password shows up

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
            raise SystemExit(f"OpenBao connection failed ({self.addr}): {e.reason}")

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
        raise SystemExit(f"{base}/dek/{domain}: dek-v{version} field is missing")
    try:
        wrapped = b64u_decode(field).decode("utf-8")
    except (ValueError, UnicodeDecodeError) as e:
        raise SystemExit(
            f"{base}/dek/{domain}: dek-v{version} is not Base64URL(UTF-8(ciphertext)) - {e}")
    if not wrapped.startswith("vault:"):
        raise SystemExit(f"{base}/dek/{domain}: not a Transit ciphertext: {wrapped[:12]}...")
    dek = bao.transit_decrypt(transit_mount, transit_key, wrapped)
    if len(dek) != DEK_LENGTH_BYTES:
        raise SystemExit(f"{base}/dek/{domain}: DEK is {len(dek)} bytes (expected 32)")
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
    """psql meta commands such as COPY only work from -f (a file), not from -c."""
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
        raise SystemExit(f"psql failed (rolled back):\n{proc.stderr.strip()[:2000]}")
    return proc.stdout


def query_scalar(args, sql: str) -> str:
    env = dict(os.environ)
    if args.db_password:
        env["PGPASSWORD"] = args.db_password
    cmd = [args.psql, "-h", args.db_host, "-p", str(args.db_port), "-U", args.db_user,
           "-d", args.db_name, "-v", "ON_ERROR_STOP=1", "--no-psqlrc", "-At", "-c", sql]
    proc = subprocess.run(cmd, env=env, capture_output=True, text=True)
    if proc.returncode != 0:
        raise SystemExit(f"psql failed:\n{proc.stderr.strip()[:2000]}")
    return proc.stdout.strip()


def write_csv(path: Path, header: tuple[str, ...], rows: list[tuple]) -> None:
    """COPY ... (format csv) does not consume the header line, so write data rows only."""
    del header  # the column order is specified by the COPY statement
    with open(path, "w", newline="", encoding="utf-8") as fh:
        writer = csv.writer(fh, quoting=csv.QUOTE_MINIMAL)
        for row in rows:
            writer.writerow(["" if v is None else v for v in row])


def copy_from(path: Path, table: str, columns: tuple[str, ...], null_marker: bool) -> str:
    opts = "format csv"
    if null_marker:
        opts += ", null ''"          # write empty strings as NULL
    return f"\\copy {table} ({', '.join(columns)}) from '{path}' with ({opts})"


# ---------------------------------------------------------------- main
def parse_args(argv=None):
    p = argparse.ArgumentParser(description="Import sample JSON into the myapp database (with encryption)")
    p.add_argument("--board", default="board.json", help="board JSON path (default board.json)")
    p.add_argument("--users", default="users.json", help="users JSON path (default users.json)")

    p.add_argument("--vault-addr",
                   default=os.environ.get("BAO_ADDR") or os.environ.get("VAULT_ADDR")
                   or "http://192.168.2.57:28200")
    p.add_argument("--vault-token", default=os.environ.get("VAULT_TOKEN") or os.environ.get("BAO_TOKEN"))
    p.add_argument("--vault-token-file", default="~/.myapp/token")
    p.add_argument("--vault-cacert",
                   default=os.environ.get("BAO_CACERT") or os.environ.get("VAULT_CACERT"),
                   help="CA bundle used to verify the https certificate (required for a private CA)")
    p.add_argument("--namespace",
                   default=os.environ.get("BAO_NAMESPACE") or os.environ.get("VAULT_NAMESPACE"),
                   help="OpenBao namespace (sent as X-Vault-Namespace)")
    p.add_argument("--mount", default="myapp_service", help="kv-v2 mount")
    p.add_argument("--base", default="kms", help="base path inside the mount")
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
    p.add_argument("--psql", default="psql", help="path to the psql binary")

    p.add_argument("--truncate", action="store_true", help="truncate users/board before loading")
    p.add_argument("--append", action="store_true",
                   help="keep existing rows and append (rows with an existing id/user_id are skipped)")
    p.add_argument("--dry-run", action="store_true", help="convert and verify without writing to the database")
    p.add_argument("--limit", type=int, default=0, help="limit the number of rows (testing only, 0=all)")
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
        raise SystemExit("an OpenBao token is required (--vault-token, VAULT_TOKEN, or ~/.myapp/token)")
    args.db_password = resolve_secret(args.db_password, args.db_password_file)
    if not args.db_password:
        raise SystemExit("a database password is required (--db-password, PGPASSWORD, or ~/.myapp/dbpw)")

    board_rows = json.load(open(Path(args.board), encoding="utf-8"))["board"]
    user_rows = json.load(open(Path(args.users), encoding="utf-8"))["users"]
    if args.limit:
        board_rows = board_rows[:args.limit]
        user_rows = user_rows[:args.limit]

    print(f"input: board {len(board_rows)} rows, users {len(user_rows)} rows")
    print(f"target DB: {args.db_user}@{args.db_host}:{args.db_port}/{args.db_name}")
    print(f"OpenBao: {args.vault_addr} (namespace={args.namespace or 'root'}, mount={args.mount}, base={args.base}, "
          f"transit={args.transit_mount}/keys/{args.transit_key}, cacert={args.vault_cacert or '-'})")

    # 1) load keys
    bao = OpenBao(args.vault_addr, args.vault_token, namespace=args.namespace, cacert=args.vault_cacert)
    board_key = load_domain_key(bao, args.mount, args.base, args.transit_mount, args.transit_key, "board")
    user_key = load_domain_key(bao, args.mount, args.base, args.transit_mount, args.transit_key, "user-pii")
    phone_key = bao.kv_read(args.mount, f"{args.base}/blind-index/user-phone")["key"]
    rrn_key = bao.kv_read(args.mount, f"{args.base}/blind-index/user-rrn")["key"]
    phone_key, rrn_key = b64u_decode(phone_key), b64u_decode(rrn_key)
    print(f"keys loaded: board DEK v{board_key.version} (domainCode={board_key.domain_code}), "
          f"user-pii DEK v{user_key.version}(domainCode={user_key.domain_code}), "
          f"blind-index {len(phone_key)}/{len(rrn_key)} bytes")

    # 2) transform
    report = {"bcrypt_hashed": 0, "board_blank_password": 0}
    users_out = transform_users(user_rows, user_key, phone_key, rrn_key, report)
    board_out = transform_board(board_rows, board_key, report)
    print(f"transformed: users {len(users_out)} rows, board {len(board_out)} rows"
          + (f", {report['bcrypt_hashed']} password(s) newly hashed" if report["bcrypt_hashed"] else "")
          + (f", {report['board_blank_password']} board row(s) without a password" if report["board_blank_password"] else ""))

    # 3) self-check: round-trip one value to prove the app-side format matches
    sample = next((r for r in user_rows if r.get("phone")), None)
    if sample:
        stored = users_out[user_rows.index(sample)][5]
        plain = envelope_decrypt(user_key.dek, stored)
        expected = normalize_phone(str(sample["phone"]))
        if plain != expected:
            raise SystemExit(f"round-trip failed: {plain!r} != {expected!r}")
        if envelope_version(stored) != user_key.version:
            raise SystemExit("round-trip failed: keyVersion header mismatch")
        print(f"round-trip OK: users.phone ciphertext {len(stored)} chars -> decrypts to the original, keyVersion={user_key.version}")

    if args.dry_run:
        print("\n[dry-run] nothing written. sample of the transformed rows:")
        if users_out:
            u = users_out[0]
            print(f"  users[0] id={u[0]} user_id={u[2]} pw={u[1][:7]}... "
                  f"id_no={u[4][:12]}...({len(u[4])} chars) phone={u[5][:12]}...({len(u[5])} chars) "
                  f"phone_idx={u[6]} rrn_idx={u[7]}")
        if board_out:
            b = board_out[0]
            print(f"  board[0] id={b[0]} title={b[5]!r} password={b[6][:12]}...({len(b[6])} chars)")
        return 0

    # 4) refuse to load over existing data unless --truncate/--append was explicit
    existing_b = int(query_scalar(args, "select count(*) from ebiz.board") or 0)
    existing_u = int(query_scalar(args, "select count(*) from ebiz.users") or 0)
    skipped_b = skipped_u = 0
    if (existing_b or existing_u) and args.append:
        # Skip samples whose id/user_id already exists, otherwise one UNIQUE violation would roll back everything
        ids_u = {int(x) for x in query_scalar(args, "select id from ebiz.users").split() if x}
        uids_u = set(query_scalar(args, "select user_id from ebiz.users").splitlines())
        ids_b = {int(x) for x in query_scalar(args, "select id from ebiz.board").split() if x}
        filtered_u = [r for r in users_out if r[0] not in ids_u and r[2] not in uids_u]
        skipped_u = len(users_out) - len(filtered_u)
        filtered_b = [r for r in board_out if r[0] not in ids_b]
        skipped_b = len(board_out) - len(filtered_b)
        users_out, board_out = filtered_u, filtered_b
        print(f"append mode: skipped samples that collide with existing rows (users {skipped_u}, board {skipped_b})")
    if (existing_b or existing_u) and not (args.truncate or args.append):
        raise SystemExit(
            f"aborted: the tables already hold data (users {existing_u}, board {existing_b}). "
            "Pass --truncate to replace it or --append to add to it.")

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

    print(f"\nloading (single transaction, psql \\copy): {args.db_name}.ebiz")
    run_psql_file(args, script)

    # 5) verify the result
    u_cnt = query_scalar(args, "select count(*) from ebiz.users")
    b_cnt = query_scalar(args, "select count(*) from ebiz.board")
    null_pii = query_scalar(args, "select count(*) from ebiz.users where phone_blind_idx is null")
    print(f"loaded: users {u_cnt} rows, board {b_cnt} rows ({null_pii} row(s) with a missing blind index)")
    print(f"sequences: users -> {query_scalar(args, 'select last_value from ebiz.users_id_seq')}, "
          f"board → {query_scalar(args, 'select last_value from ebiz.board_id_seq')}")

    for f in (users_csv, board_csv):
        f.unlink(missing_ok=True)
    script.unlink(missing_ok=True)
    tmpdir.rmdir()
    return 0


if __name__ == "__main__":
    sys.exit(main())
