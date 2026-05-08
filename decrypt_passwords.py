#!/usr/bin/env python3
"""
Password Decryption Script for demoApp
Decrypts AES encrypted passwords stored in ebiz.board table
"""

import base64
import os
import sys

import psycopg2
import hvac
from Crypto.Cipher import AES
from Crypto.Util.Padding import unpad

DB_CONFIG = {
    "host": "192.168.2.57",
    "port": "21716",
    "database": "limadb",
    "user": "postgres",
    "password": "REDACTED_DB_PASSWORD",
    "options": "-c search_path=ebiz"
}

VAULT_CONFIG = {
    "url": "http://192.168.2.57:8200",
    "token": os.environ.get("VAULT_TOKEN", "hvs.YOUR_TOKEN_HERE"),
}


def get_vault_key():
    client = hvac.Client(url=VAULT_CONFIG['url'], token=VAULT_CONFIG['token'])

    try:
        response = client.secrets.kv.v2.read_secret_version(
            path='ebiz_db/data-enc-key',
            mount_point='ebiz_service'
        )

        secret_data = response['data']['data']
        fernet_key_base64 = secret_data['fernet-key']

        encryption_key = base64.urlsafe_b64decode(fernet_key_base64)

        print(f"✓ Retrieved encryption key from Vault")
        print(f"  Key length: {len(encryption_key)} bytes")

        return encryption_key

    except Exception as e:
        print(f"✗ Failed to connect to Vault: {e}", file=sys.stderr)
        sys.exit(1)


def decrypt_password(encrypted_password, encryption_key):
    try:
        encrypted_bytes = base64.b64decode(encrypted_password)

        cipher = AES.new(encryption_key, AES.MODE_ECB)
        decrypted = cipher.decrypt(encrypted_bytes)

        unpadded = unpad(decrypted, AES.block_size)

        return unpadded.decode('utf-8')

    except Exception as e:
        return f"[DECRYPTION ERROR: {str(e)}]"


def main():
    print("=" * 70)
    print("Password Decryption Script - demoApp")
    print("=" * 70)
    print()

    print("[1/2] Connecting to Vault...")
    encryption_key = get_vault_key()
    print()

    print("[2/2] Connecting to PostgreSQL database...")
    try:
        conn = psycopg2.connect(**DB_CONFIG)
        cursor = conn.cursor()
        print(f"✓ Connected to {DB_CONFIG['database']} database")
        print()
    except psycopg2.Error as e:
        print(f"✗ Database connection failed: {e}", file=sys.stderr)
        sys.exit(1)

    print("Retrieving and decrypting passwords...")
    print()
    print("-" * 70)
    print(f"{'ID':<12} {'Title':<30} {'Encrypted':<25} {'Decrypted'}")
    print("-" * 70)

    try:
        cursor.execute("""
            SELECT id, title, password
            FROM ebiz.board
            WHERE password IS NOT NULL
            ORDER BY id
            fetch next 10 rows only
        """)

        row_count = 0
        for row in cursor.fetchall():
            row_count += 1
            post_id, title, encrypted_password = row

            if encrypted_password:
                decrypted = decrypt_password(encrypted_password, encryption_key)
            else:
                decrypted = "[NULL]"

            display_title = title[:28] + ".." if len(title) > 30 else title
            display_enc = encrypted_password[:20] + ".." if len(encrypted_password) > 22 else encrypted_password

            print(f"{post_id:<12} {display_title:<30} {display_enc:<25} {decrypted}")

        print("-" * 70)
        print(f"\nTotal records processed: {row_count}")

    except psycopg2.Error as e:
        print(f"✗ Query failed: {e}", file=sys.stderr)
        sys.exit(1)
    finally:
        cursor.close()
        conn.close()

    print("\n✓ Done!")


if __name__ == "__main__":
    try:
        from Crypto.Cipher import AES
    except ImportError:
        print("✗ Missing required package: pycryptodome")
        print("  Install with: pip install pycryptodome psycopg2-binary hvac")
        sys.exit(1)

    main()
