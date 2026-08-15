#!/usr/bin/env python3
"""
Password Verification Script for demoApp (BCrypt)
Verifies BCrypt-hashed passwords stored in the ebiz.board and ebiz.users tables.
Also checks for old AES-GCM encrypted passwords that need migration.
"""

import os
import sys
import base64

import bcrypt
from Cryptodome.Cipher import AES


# Add my_env site-packages to path
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), 'my_env', 'lib', 'python3.12', 'site-packages'))

key_b64="NgqOBievnB9500cQOnSQ-cmbBx38KnOiKx5ooQ_e97Y="
# Helper to obtain AES‑GCM key from environment variable
def get_aes_gcm_key():
    import os, base64, sys
    #key_b64 = os.getenv('AES_GCM_KEY')
    if not key_b64:
        print('[ERROR] AES_GCM_KEY environment variable not set', file=sys.stderr)
        sys.exit(1)
    try:
        return base64.urlsafe_b64decode(key_b64)
    except Exception as e:
        print(f'[ERROR] Failed to decode AES_GCM_KEY: {e}', file=sys.stderr)
        sys.exit(1)

# AES‑GCM decryption helper
def decrypt_aes_gcm(ciphertext_b64: str, key_bytes: bytes) -> str:
    try:
        data = base64.urlsafe_b64decode(ciphertext_b64)
        iv = data[:12]
        tag = data[-16:]
        ciphertext = data[12:-16]
        cipher = AES.new(key_bytes, AES.MODE_GCM, nonce=iv)
        plaintext = cipher.decrypt_and_verify(ciphertext, tag)
        return plaintext.decode('utf-8')
    except Exception as e:
        print(f'[ERROR] AES‑GCM decryption failed: {e}', file=sys.stderr)
        return "<decryption error>"
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), 'my_env', 'lib', 'python3.12', 'site-packages'))

DB_CONFIG = {
    "host": "192.168.2.57",
    "port": "21716",
    "database": "limadb",
    "user": "postgres",
    "password": "REDACTED_DB_PASSWORD",
    "options": "-c search_path=ebiz"
}

# Old AES-GCM parameters (for detecting legacy encrypted passwords)
GCM_IV_LENGTH_BYTES = 12
GCM_TAG_LENGTH_BYTES = 16


def is_bcrypt_hash(password):
    """Check if a password string is a valid BCrypt hash."""
    try:
        if isinstance(password, str):
            password = password.encode('utf-8')
        # BCrypt hashes start with $2a$, $2b$, or $2y$ and are 60 chars
        return password.startswith(b'$2a$') or password.startswith(b'$2b$') or password.startswith(b'$2y$')
    except Exception:
        return False


def verify_password(plain_password, hashed_password):
    """
    Verify a plain-text password against a BCrypt hash.
    Returns True if the password matches, False otherwise.
    """
    try:
        plain_bytes = plain_password.encode('utf-8')
        hash_bytes = hashed_password.encode('utf-8') if isinstance(hashed_password, str) else hashed_password
        return bcrypt.checkpw(plain_bytes, hash_bytes)
    except Exception as e:
        print(f"[ERROR] Verification failed: {e}", file=sys.stderr)
        return False


def is_old_aes_gcm_encrypted(password):
    """Check if password appears to be old AES-GCM encrypted (base64url encoded)."""
    try:
        if not isinstance(password, str):
            return False
        decoded = base64.urlsafe_b64decode(password)
        # AES-GCM encrypted data: 12 bytes IV + ciphertext + 16 bytes tag
        # Minimum length would be 12 + 1 + 16 = 29 bytes for a very short password
        return len(decoded) >= 29
    except Exception:
        return False


def get_connection():
    """Establish a database connection using psycopg2."""
    try:
        import psycopg2
        conn = psycopg2.connect(
            host=DB_CONFIG["host"],
            port=DB_CONFIG["port"],
            database=DB_CONFIG["database"],
            user=DB_CONFIG["user"],
            password=DB_CONFIG["password"],
            options=DB_CONFIG["options"]
        )
        print(f"✓ Connected to database {DB_CONFIG['database']} at {DB_CONFIG['host']}:{DB_CONFIG['port']}")
        return conn
    except ImportError:
        print("[ERROR] psycopg2 is not installed.", file=sys.stderr)
        print("Install with: pip install psycopg2-binary", file=sys.stderr)
        sys.exit(1)
    except Exception as e:
        print(f"[ERROR] Database connection failed: {e}", file=sys.stderr)
        sys.exit(1)


def check_board_passwords(conn):
    """Check all board entries with passwords and classify them."""
    cursor = conn.cursor()
    
    cursor.execute("SELECT id, password FROM board WHERE password IS NOT NULL AND password != '' ORDER BY id desc fetch next 10 rows only")
    rows = cursor.fetchall()
    
    print(f"\nFound {len(rows)} board entries with passwords")
    print("=" * 70)
    
    bcrypt_count = 0
    aes_gcm_count = 0
    unknown_count = 0
    
    for board_id, password in rows:
        if is_bcrypt_hash(password):
            bcrypt_count += 1
            status = "✓ BCrypt hash"
        elif is_old_aes_gcm_encrypted(password):
            aes_gcm_count += 1
            # Decrypt and show plaintext
            key = get_aes_gcm_key()
            plaintext = decrypt_aes_gcm(password, key)
            status = f"⚠ AES-GCM encrypted (decrypted: {plaintext})"
        else:
            unknown_count += 1
            status = "? Unknown format"
        print(f"  Board ID {board_id}: {status}")
    
    cursor.close()
    
    print(f"\nSummary:")
    print(f"  BCrypt hashes:       {bcrypt_count}")
    print(f"  AES-GCM encrypted:   {aes_gcm_count}")
    print(f"  Unknown format:      {unknown_count}")
    
    return {"bcrypt": bcrypt_count, "aes_gcm": aes_gcm_count, "unknown": unknown_count}


def check_user_passwords(conn):
    """Check all user entries and classify their password format."""
    cursor = conn.cursor()
    
    # Use correct column name: user_id (not userid)
    cursor.execute("SELECT id, user_id, password, username FROM users WHERE password IS NOT NULL ORDER BY id desc fetch next 10 rows only")
    rows = cursor.fetchall()
    
    print(f"\nFound {len(rows)} user entries with passwords")
    print("=" * 70)
    
    bcrypt_count = 0
    aes_gcm_count = 0
    unknown_count = 0
    
    for user_id, user_id_val, password, username in rows:
        if is_bcrypt_hash(password):
            bcrypt_count += 1
            status = "✓ BCrypt hash"
        elif is_old_aes_gcm_encrypted(password):
            aes_gcm_count += 1
            status = "⚠ AES-GCM encrypted (needs migration)"
        else:
            unknown_count += 1
            status = "? Unknown format"
        print(f"  User '{user_id_val}' ({username}): {status}")
    
    cursor.close()
    
    print(f"\nSummary:")
    print(f"  BCrypt hashes:       {bcrypt_count}")
    print(f"  AES-GCM encrypted:   {aes_gcm_count}")
    print(f"  Unknown format:      {unknown_count}")
    
    return {"bcrypt": bcrypt_count, "aes_gcm": aes_gcm_count, "unknown": unknown_count}


def test_bcrypt_functionality():
    """Test that BCrypt is working correctly locally."""
    print("\n--- Local BCrypt Functionality Test ---")
    
    # Test 1: Basic hash and verify
    test_password = "mySecurePassword123"
    hashed = bcrypt.hashpw(test_password.encode('utf-8'), bcrypt.gensalt(rounds=10))
    assert bcrypt.checkpw(test_password.encode('utf-8'), hashed), "Hash/verify roundtrip failed"
    print("✓ Test 1: Basic hash and verify - PASSED")
    
    # Test 2: Wrong password should fail
    wrong_password = "wrongPassword"
    result = bcrypt.checkpw(wrong_password.encode('utf-8'), hashed)
    assert result == False, "Wrong password should not match"
    print("✓ Test 2: Wrong password rejected - PASSED")
    
    # Test 3: Hash is salted (same password produces different hashes)
    hash1 = bcrypt.hashpw("samePassword".encode('utf-8'), bcrypt.gensalt(rounds=10))
    hash2 = bcrypt.hashpw("samePassword".encode('utf-8'), bcrypt.gensalt(rounds=10))
    assert hash1 != hash2, "Salts should produce different hashes"
    assert bcrypt.checkpw("samePassword".encode('utf-8'), hash1), "First hash should still verify"
    assert bcrypt.checkpw("samePassword".encode('utf-8'), hash2), "Second hash should still verify"
    print("✓ Test 3: Salted hashes - PASSED")
    
    # Test 4: One-way (cannot decrypt)
    assert not hasattr(bcrypt, 'decrypt'), "BCrypt should not have decrypt"
    print("✓ Test 4: One-way hash (no decrypt) - PASSED")
    
    # Test 5: is_bcrypt_hash detection
    assert is_bcrypt_hash(hashed.decode('utf-8')), "Should detect BCrypt hash"
    assert not is_bcrypt_hash("notAHash"), "Should reject non-BCrypt strings"
    print("✓ Test 5: BCrypt hash detection - PASSED")
    
    print("✓ All local BCrypt tests passed!")


def main():
    print("=" * 70)
    print("demoApp Password Verification Script (BCrypt)")
    print("=" * 70)
    
    # Run local functionality tests first
    test_bcrypt_functionality()
    
    # Connect to database
    conn = get_connection()
    # Perform user ID number decryption check
    def check_user_idnos(conn):
        """Check latest 10 user id_no entries encrypted with AES‑GCM"""
        cursor = conn.cursor()
        cursor.execute("SELECT id, user_id, id_no, username FROM users WHERE id_no IS NOT NULL AND id_no != '' ORDER BY id DESC FETCH NEXT 10 ROWS ONLY")
        rows = cursor.fetchall()
        print(f"\nFound {len(rows)} user ID numbers")
        print("=" * 70)
        bcrypt_cnt = aes_cnt = unk_cnt = 0
        for uid, user_id, id_no_enc, username in rows:
            if is_old_aes_gcm_encrypted(id_no_enc):
                aes_cnt += 1
                key = get_aes_gcm_key()
                plaintext = decrypt_aes_gcm(id_no_enc, key)
                status = f"⚠ AES‑GCM (decrypted: {plaintext})"
            else:
                unk_cnt += 1
                status = "? Unknown format"
            print(f"  User ID {uid} ({username}): {status}")
        cursor.close()
        print("\nID No Summary:")
        print(f"  AES‑GCM encrypted: {aes_cnt}")
        print(f"  Unknown format: {unk_cnt}")
        return {"aes_gcm": aes_cnt, "unknown": unk_cnt}

    # Run additional checks
    user_idno_results = check_user_idnos(conn)
    
    try:
        # Check board passwords
        board_results = check_board_passwords(conn)
        
        # Check user passwords
        user_results = check_user_passwords(conn)
        
        # Summary
        print("\n" + "=" * 70)
        print("FINAL REPORT")
        print("=" * 70)
        
        total_bcrypt = board_results["bcrypt"] + user_results["bcrypt"]
        total_aes = board_results["aes_gcm"] + user_results["aes_gcm"]
        total_unknown = board_results["unknown"] + user_results["unknown"]
        
        print(f"BCrypt hashes (ready):     {total_bcrypt}")
        print(f"AES-GCM encrypted (migrate): {total_aes}")
        print(f"Unknown format:             {total_unknown}")
        
        if total_bcrypt > 0 and total_aes == 0:
            print("\n✓ All passwords are BCrypt hashed. Migration complete!")
            sys.exit(0)
        elif total_aes > 0:
            print("\n⚠ Legacy AES-GCM passwords detected. These need migration:")
            print("  - Users must log in to trigger automatic re-hashing with BCrypt")
            print("  - Or run a bulk migration script to re-encrypt with BCrypt")
            sys.exit(0)  # Not an error - just informational
        else:
            print("\n⚠ No passwords found or all in unknown format.")
            sys.exit(0)
            
    finally:
        conn.close()
        print("\nDatabase connection closed.")


if __name__ == "__main__":
    main()
