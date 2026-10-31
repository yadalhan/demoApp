#!/usr/bin/env python3
"""
demoApp column verification tool.

Classifies and (optionally) decrypts values stored in demoApp's encrypted columns:

  - ebiz.users.password     BCrypt hash (one-way; never decryptable)
  - ebiz.users.id_no        AES-256-GCM envelope, domain "user-pii"
  - ebiz.users.phone        AES-256-GCM envelope, domain "user-pii"
  - ebiz.board.password     AES-256-GCM envelope, domain "board" (new rows)
                            or the legacy pre-KEK-DEK single-key format (old rows)

Current formats (see VAULT_AND_ENCRYPTION.md and vault-crypto/README.md):

  column value : Base64URL( domainCode(1B) | keyVersion(1B) | IV(12B) | ct | tag(16B) )
  wrapped DEK  : kekVersion(1B) | IV(12B) | ct(32B) | tag(16B)          (Vault dek-v{n})

The KEK/DEK material is read from Vault at runtime - no key is embedded in this file.
The legacy single-key format (IV | ct | tag, no header) cannot be decrypted with the
current keys; those rows are reported as LEGACY (the app itself returns `false` when
validating them - see IMPROVEMENTS.md).

Usage:
  # crypto self-test only - no Vault, no database
  python3 decrypt_passwords.py --self-test

  # inspect rows (needs Vault + database; plaintext is masked unless --show)
  export VAULT_ADDR=http://192.168.2.57:8200
  export VAULT_TOKEN=...
  export DB_PASSWORD=...
  python3 decrypt_passwords.py --show

Environment:
  VAULT_ADDR   (default http://192.168.2.57:8200)
  VAULT_TOKEN  (required unless only --self-test is used)
  DB_HOST, DB_PORT, DB_NAME, DB_USER, DB_PASSWORD, DB_SCHEMA

Requires: pycryptodome (or pycryptodomex), psycopg2-binary, hvac
  pip install pycryptodome psycopg2-binary hvac
"""
import argparse
import base64
import os
import sys

try:  # pycryptodomex installs as Cryptodome, pycryptodome as Crypto
    from Cryptodome.Cipher import AES
except ImportError:
    from Crypto.Cipher import AES

DOMAIN_CODES = {"board": 1, "user-pii": 2}
DOMAIN_NAMES = {v: k for k, v in DOMAIN_CODES.items()}
IV_BYTES = 12
TAG_BYTES = 16

VAULT_DEFAULT_ADDR = "http://192.168.2.57:8200"
VAULT_MOUNT = "ebiz_service"
KEK_PATH = "ebiz_db/kek"
DEK_BASE_PATH = "ebiz_db/dek"


# --------------------------------------------------------------------------- #
# crypto helpers (mirror vault-crypto's AesGcmCodec / KekService / EnvelopeCryptoService)
# --------------------------------------------------------------------------- #

def b64d(value: str) -> bytes:
    return base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))


def b64e(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")


def wrap_dek(kek: bytes, kek_version: int, dek: bytes) -> bytes:
    iv = os.urandom(IV_BYTES)
    cipher = AES.new(kek, AES.MODE_GCM, nonce=iv)
    ct, tag = cipher.encrypt_and_digest(dek)
    return bytes([kek_version]) + iv + ct + tag


def unwrap_dek(keks: dict, wrapped: bytes) -> bytes:
    if len(wrapped) < 1 + IV_BYTES + TAG_BYTES:
        raise ValueError("wrapped DEK too short")
    version = wrapped[0]
    kek = keks.get(version)
    if kek is None:
        raise ValueError(f"KEK version {version} not loaded")
    iv, body = wrapped[1:1 + IV_BYTES], wrapped[1 + IV_BYTES:]
    cipher = AES.new(kek, AES.MODE_GCM, nonce=iv)
    return cipher.decrypt_and_verify(body[:-TAG_BYTES], body[-TAG_BYTES:])


def envelope_encrypt(dek: bytes, domain_code: int, version: int, plaintext: str) -> str:
    iv = os.urandom(IV_BYTES)
    cipher = AES.new(dek, AES.MODE_GCM, nonce=iv)
    ct, tag = cipher.encrypt_and_digest(plaintext.encode("utf-8"))
    return b64e(bytes([domain_code, version]) + iv + ct + tag)


def envelope_header(raw: bytes):
    if len(raw) < 2:
        raise ValueError("envelope too short")
    return raw[0], raw[1], raw[2:]


def envelope_decrypt(deks: dict, domain_code: int, value: str) -> str:
    raw = b64d(value)
    message_domain, version, body = envelope_header(raw)
    if message_domain != domain_code:
        raise ValueError(
            f"domain mismatch: expected {DOMAIN_NAMES.get(domain_code, domain_code)} "
            f"({domain_code}) but got {message_domain}")
    if len(body) < IV_BYTES + TAG_BYTES:
        raise ValueError("envelope body too short")
    key = deks.get(version)
    if key is None:
        raise ValueError(f"DEK version {version} not loaded for domain {DOMAIN_NAMES.get(domain_code)}")
    iv, rest = body[:IV_BYTES], body[IV_BYTES:]
    cipher = AES.new(key, AES.MODE_GCM, nonce=iv)
    return cipher.decrypt_and_verify(rest[:-TAG_BYTES], rest[-TAG_BYTES:]).decode("utf-8")


# --------------------------------------------------------------------------- #
# classification
# --------------------------------------------------------------------------- #

def is_bcrypt(value: str) -> bool:
    return bool(value) and value.startswith(("$2a$", "$2b$", "$2y$"))


def classify(domain_code: int, value: str, deks: dict) -> str:
    """Return 'bcrypt' | 'envelope' | 'legacy' | 'empty' | 'unknown'."""
    if not value:
        return "empty"
    if is_bcrypt(value):
        return "bcrypt"
    try:
        raw = b64d(value)
    except Exception:
        return "unknown"
    if len(raw) < 2:
        return "unknown"
    # An envelope header is only valid if the domain byte and version byte are sane
    # and the key for that version is actually loaded.
    if raw[0] == domain_code and raw[1] in deks and len(raw) >= 2 + IV_BYTES + TAG_BYTES:
        return "envelope"
    # Legacy single-key rows: IV(12) | ciphertext | tag(16), no header. Their first
    # bytes are random, so only use a length check - never claim a false envelope match.
    if len(raw) >= IV_BYTES + TAG_BYTES + 1:
        return "legacy"
    return "unknown"


# --------------------------------------------------------------------------- #
# Vault loading
# --------------------------------------------------------------------------- #

def load_from_vault():
    import hvac

    addr = os.environ.get("VAULT_ADDR", VAULT_DEFAULT_ADDR)
    token = os.environ.get("VAULT_TOKEN")
    if not token:
        print("[ERROR] VAULT_TOKEN is required to read KEK/DEK material", file=sys.stderr)
        sys.exit(2)

    client = hvac.Client(url=addr, token=token)
    if not client.is_authenticated():
        print(f"[ERROR] Vault authentication failed at {addr}", file=sys.stderr)
        sys.exit(2)

    def read(path):
        response = client.secrets.kv.v2.read_secret_version(
            path=path, mount_point=VAULT_MOUNT)
        return response["data"]["data"]

    kek_data = read(KEK_PATH)
    keks = {int(k[len("kek-v"):]): b64d(v)
            for k, v in kek_data.items() if k.startswith("kek-v")}
    if not keks:
        raise SystemExit(f"No kek-v* fields at {KEK_PATH}")

    deks = {}
    current = {}
    for domain in DOMAIN_CODES:
        data = read(f"{DEK_BASE_PATH}/{domain}")
        domain_keys = {}
        for k, v in data.items():
            if k.startswith("dek-v"):
                domain_keys[int(k[len("dek-v"):])] = unwrap_dek(keks, b64d(v))
        if not domain_keys:
            raise SystemExit(f"No dek-v* fields for domain '{domain}'")
        deks[domain] = domain_keys
        current[domain] = int(data["current-version"])

    print(f"✓ Vault {addr}: loaded {len(keks)} KEK version(s), "
          f"deks={ {d: sorted(k) for d, k in deks.items()} }, current={current}")
    return deks, current


# --------------------------------------------------------------------------- #
# database inspection
# --------------------------------------------------------------------------- #

def db_config():
    return {
        "host": os.environ.get("DB_HOST", "192.168.2.57"),
        "port": os.environ.get("DB_PORT", "21716"),
        "database": os.environ.get("DB_NAME", "limadb"),
        "user": os.environ.get("DB_USER", "postgres"),
        "password": os.environ.get("DB_PASSWORD"),
        "options": f"-c search_path={os.environ.get('DB_SCHEMA', 'ebiz')}",
    }


def connect():
    try:
        import psycopg2
    except ImportError:
        print("[ERROR] psycopg2 is not installed (pip install psycopg2-binary)", file=sys.stderr)
        sys.exit(2)

    config = db_config()
    if not config["password"]:
        print("[ERROR] DB_PASSWORD is required", file=sys.stderr)
        sys.exit(2)
    conn = psycopg2.connect(**config)
    print(f"✓ Connected to {config['database']} at {config['host']}:{config['port']}")
    return conn


def reveal(value: str, show: bool) -> str:
    if show:
        return value
    if len(value) <= 4:
        return "*" * len(value)
    return value[:2] + "*" * (len(value) - 4) + value[-2:]


def inspect(conn, deks, current, show):
    stats = {"bcrypt": 0, "envelope": 0, "legacy": 0, "empty": 0, "unknown": 0, "failed": 0}

    def note(kind):
        stats[kind] = stats.get(kind, 0) + 1

    def try_decrypt(domain, value, kind):
        if kind != "envelope":
            return None
        try:
            note("envelope")
            return envelope_decrypt(deks[domain], DOMAIN_CODES[domain], value)
        except Exception as e:
            stats["failed"] += 1
            print(f"    ! decrypt failed: {e}")
            return None

    cursor = conn.cursor()

    # --- board.password (new rows: envelope / old rows: legacy / possibly BCrypt)
    cursor.execute(
        "select id, password from board "
        "where password is not null and password <> '' order by id desc limit 20")
    rows = cursor.fetchall()
    print(f"\n[board.password] showing {len(rows)} newest protected posts")
    for board_id, value in rows:
        kind = classify(DOMAIN_CODES["board"], value, deks["board"])
        if kind == "envelope":
            stats["envelope"] += 1
            try:
                plain = envelope_decrypt(deks["board"], DOMAIN_CODES["board"], value)
                shown = reveal(plain, show)
                print(f"  id={board_id:<10} envelope(v{envelope_header(b64d(value))[1]})  {shown}")
            except Exception as e:
                stats["failed"] += 1
                print(f"  id={board_id:<10} envelope  ! {e}")
        else:
            note(kind)
            detail = {
                "bcrypt": "BCrypt hash (unexpected for board.password)",
                "legacy": "LEGACY single-key format - cannot decrypt with current keys",
                "unknown": "unknown format",
                "empty": "empty",
            }[kind]
            print(f"  id={board_id:<10} {detail}")

    # --- users: password (BCrypt), id_no/phone (envelope)
    cursor.execute(
        "select id, user_id, password, id_no, phone from users order by id desc limit 20")
    rows = cursor.fetchall()
    print(f"\n[users] showing {len(rows)} newest users")
    for uid, user_id, password, id_no, phone in rows:
        pwd_kind = classify(DOMAIN_CODES["user-pii"], password, deks["user-pii"])
        note("bcrypt" if pwd_kind == "bcrypt" else "unknown" if pwd_kind not in ("empty",) else "empty")
        print(f"  id={uid:<6} user_id={user_id:<20} password={'BCrypt' if pwd_kind == 'bcrypt' else pwd_kind}")
        for column, value in (("id_no", id_no), ("phone", phone)):
            kind = classify(DOMAIN_CODES["user-pii"], value, deks["user-pii"])
            note(kind)
            if kind == "envelope":
                try:
                    plain = envelope_decrypt(deks["user-pii"], DOMAIN_CODES["user-pii"], value)
                    print(f"      {column}: envelope  {reveal(plain, show)}")
                except Exception as e:
                    stats["failed"] += 1
                    print(f"      {column}: envelope  ! {e}")
            else:
                print(f"      {column}: {kind}")

    # Current-version distribution for the two searchable PII columns
    print("\n[version distribution]")
    for domain, table, column in (("board", "board", "password"),
                                  ("user-pii", "users", "id_no")):
        cursor.execute(f"select count(*) from {table} where {column} is not null and {column} <> ''")
        total = cursor.fetchone()[0]
        print(f"  {table}.{column}: {total} non-empty row(s), current {domain} DEK v{current[domain]}")

    cursor.close()
    print("\nSummary:", ", ".join(f"{k}={v}" for k, v in sorted(stats.items())))
    return stats


# --------------------------------------------------------------------------- #
# self-test (no Vault, no DB)
# --------------------------------------------------------------------------- #

def self_test():
    print("=" * 70)
    print("vault-crypto envelope format self-test (no Vault, no DB)")
    print("=" * 70)

    kek_v1 = os.urandom(32)
    kek_v2 = os.urandom(32)
    keks = {1: kek_v1, 2: kek_v2}

    dek_plain = os.urandom(32)
    wrapped = wrap_dek(kek_v1, 1, dek_plain)
    assert unwrap_dek(keks, wrapped) == dek_plain
    print("✓ wrapped-DEK kekVersion header round-trips")

    # Re-wrap under KEK v2 without changing the DEK (what rewrapDomainDeks does)
    rewrapped = wrap_dek(kek_v2, 2, dek_plain)
    assert unwrap_dek(keks, rewrapped) == dek_plain
    print("✓ DEK re-wrapped under a new KEK version still unwraps")

    ciphertext = envelope_encrypt(dek_plain, DOMAIN_CODES["board"], 1, "900101123456")
    assert envelope_decrypt({1: dek_plain}, DOMAIN_CODES["board"], ciphertext) == "900101123456"
    print("✓ envelope encrypt/decrypt round-trips (board domain)")

    # Domain isolation
    try:
        envelope_decrypt({1: dek_plain}, DOMAIN_CODES["user-pii"], ciphertext)
        raise AssertionError("cross-domain decryption should have failed")
    except ValueError as e:
        assert "domain mismatch" in str(e)
    print("✓ cross-domain decryption is rejected")

    # Unknown version
    other = envelope_encrypt(dek_plain, DOMAIN_CODES["board"], 9, "x")
    try:
        envelope_decrypt({1: dek_plain}, DOMAIN_CODES["board"], other)
        raise AssertionError("unknown key version should have failed")
    except ValueError as e:
        assert "DEK version 9" in str(e)
    print("✓ unloaded key version is rejected")

    # Classification
    assert classify(DOMAIN_CODES["board"], ciphertext, {1: dek_plain}) == "envelope"
    assert classify(DOMAIN_CODES["board"], "$2a$10$abcdefghijklmnopqrstuv", {}) == "bcrypt"
    legacy = b64e(os.urandom(12) + b"0123456789abcdef" + os.urandom(16))
    assert classify(DOMAIN_CODES["board"], legacy, {1: dek_plain}) == "legacy"
    print("✓ classification: envelope / bcrypt / legacy")

    print("\nAll self-tests passed.")
    return 0


def main():
    parser = argparse.ArgumentParser(description="demoApp encrypted-column verification tool")
    parser.add_argument("--self-test", action="store_true",
                        help="run crypto self-tests only (no Vault, no database)")
    parser.add_argument("--show", action="store_true",
                        help="print decrypted plaintext (default: masked)")
    args = parser.parse_args()

    if args.self_test:
        return self_test()

    deks, current = load_from_vault()
    conn = connect()
    try:
        inspect(conn, deks, current, args.show)
    finally:
        conn.close()
        print("\nDatabase connection closed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
