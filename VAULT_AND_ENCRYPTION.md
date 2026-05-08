# Vault Configuration and Password Encryption

## Overview
This document describes the Vault server configuration and password encryption implementation for the demoApp project.

**Note**: Encryption is now provided by the `vault-crypto` package (separate JAR).
See `/home/xaan/opencode/projects/vault-crypto/README.md` for usage details.

## vault-crypto Package (Updated 2026-05-08)

The encryption functionality has been extracted into a separate package `vault-crypto`.

### Package Details
- **Group ID**: `com.xaan`
- **Artifact ID**: `vault-crypto`
- **Version**: `0.0.1-SNAPSHOT`
- **Build**: Gradle 8.7, Java 17
- **Output**: `vault-crypto-0.0.1-SNAPSHOT.jar`

### Building vault-crypto
```bash
cd /home/xaan/opencode/projects/vault-crypto
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
./gradlew clean build publishToMavenLocal
```

This installs the JAR to `~/.m2/repository/com/xaan/vault-crypto/0.0.1-SNAPSHOT/`.

### Using vault-crypto in demoApp
**build.gradle:**
```groovy
repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation 'com.xaan:vault-crypto:0.0.1-SNAPSHOT'
}
```

**PasswordService.java (refactored):**
```java
@Service
public class PasswordService {
    private final VaultCryptoService vaultCryptoService;

    public PasswordService(VaultOperations vaultOperations) {
        this.vaultCryptoService = new VaultCryptoService(vaultOperations);
    }

    public String encryptPassword(String password) {
        return vaultCryptoService.encrypt(password);
    }

    public String decryptPassword(String encryptedPassword) {
        return vaultCryptoService.decrypt(encryptedPassword);
    }

    public boolean validatePassword(String inputPassword, String storedEncryptedPassword) {
        return vaultCryptoService.validate(inputPassword, storedEncryptedPassword);
    }
}
```

## Vault Configuration (Updated 2026-05-08)

### kv-v2 Backend Configuration
The application now uses HashiCorp Vault kv-v2 (versioned key-value) backend:

**Vault Path**: `ebiz_service/data/ebiz_db/data-enc-key`
- **Mount**: `ebiz_service` (kv-v2)
- **Secret Path**: `ebiz_db/data-enc-key`
- **Key**: `fernet-key` (32 bytes, used as AES-256 key)
- **Server**: 192.168.2.57:8200

### Setting up Vault Secrets

```bash
# Enable kv-v2 backend (if not already enabled)
vault secrets enable -path=ebiz_service kv-v2

# Store the Fernet encryption key
vault kv put -mount=ebiz_service ebiz_db/data-enc-key \
  fernet-key="NgqOBievnB9500cQOnSQ-cmbBx38KnOiKx5ooQ_e97Y=" \
  description="encryption key for ebiz db column"

# Verify the secret
vault kv get -mount=ebiz_service ebiz_db/data-enc-key
```

### How It Works

1. **Spring Vault** (`VaultOperations`) reads from `ebiz_service/data/ebiz_db/data-enc-key`
2. The Fernet key is Base64URL decoded (32 bytes total)
3. `PasswordService` uses the full 32-byte key for AES-256 encryption/decryption (ECB mode)
4. Vault connection details in `application.properties`:
   ```properties
   spring.cloud.vault.uri=http://192.168.2.57:8200
   spring.cloud.vault.token=VAULT_TOKEN_PLACE_HOLDER
   spring.cloud.vault.fail-fast=false
   ```

## Password Encryption

### Implementation
demoApp uses the `vault-crypto` package for encryption:

- **Package**: `com.xaan:vault-crypto:0.0.1-SNAPSHOT` (separate JAR)
- **Algorithm**: AES-256 (ECB mode, PKCS5 padding)
- **Key Source**: Vault kv-v2 (`ebiz_service/data/ebiz_db/data-enc-key`, field: `fernet-key`)
- **Key Format**: Fernet key (32 bytes, used directly as AES-256 key)
- **Output**: Base64 encoded string (e.g., `pU4nAaBrwqPKLoV1Waa/tw==`)

**Usage in demoApp:**
```java
// PasswordService.java (demoApp)
@Service
public class PasswordService {
    private final VaultCryptoService vaultCryptoService;

    public PasswordService(VaultOperations vaultOperations) {
        this.vaultCryptoService = new VaultCryptoService(vaultOperations);
    }

    public String encryptPassword(String password) {
        return vaultCryptoService.encrypt(password);
    }

    public String decryptPassword(String encryptedPassword) {
        return vaultCryptoService.decrypt(encryptedPassword);
    }
}
```

**vault-crypto Package Details:**
See `/home/xaan/opencode/projects/vault-crypto/README.md` for full documentation.

### Database Storage
Passwords are stored as Base64-encoded AES encrypted strings in `ebiz.board.password`:
```sql
SELECT id, title, password FROM ebiz.board WHERE id = 2017587;
-- Returns: pU4nAaBrwqPKLoV1Waa/tw== (encrypted)
```

## Deployment

### Verified Working (2026-05-08)
- ✅ Vault kv-v2 integration working
- ✅ Fernet key loaded from `ebiz_service/data/ebiz_db/data-enc-key`
- ✅ Password encryption/decryption successful (test post ID: 2017587)
- ✅ Application startup successful with Vault integration
- ✅ Python decryption script tested and verified (`decrypt_passwords.py`)
- ✅ **vault-crypto package** - separated JAR, successfully integrated
- ✅ **Production deployment** - tested on 192.168.2.57

### Production Test Results (2026-05-08)
Tested vault-crypto integration in production:

**Test Case 1: Password Encryption**
```bash
curl -X POST http://192.168.2.57:8080/api/v1/posts \
  -H "Content-Type: application/json" \
  -d '{"title":"Vault-Crypto Test","content":"Testing vault-crypto package","password":"testVaultCrypto123"}'
# Returns: 2017588
```

**Database Verification:**
```sql
SELECT id, title, password FROM ebiz.board WHERE id = 2017588;
-- id: 2017588
-- title: Vault-Crypto Test
-- password: JrwIlNN9YVMIxpqWvYhlNGfd7CUf1wjOgXAHLRIf0io= (encrypted)
```

**Decryption Verification:**
```bash
export VAULT_TOKEN="hvs.YOUR_TOKEN_HERE"
python3 decrypt_passwords.py
# Output: 2017588 | Vault-Crypto Test | JrwIlNN9YVMIxpqWvYhl... | testVaultCrypto123
```

✅ Encryption and decryption working correctly with vault-crypto package.

## Security Notes

1. **Vault Token**: Stored in `application.properties` (which is in `.gitignore`)
2. **Fernet Key**: 32 bytes used as AES-256 key (ECB mode)
3. **Base64URL**: Fernet key uses URL-safe Base64 (`-` and `_` instead of `+` and `/`)
4. **Fail-Fast**: `false` (app starts even if Vault is unavailable, but encryption will fail)
5. **ECB Mode**: No IV used - simpler but less secure than CBC mode

## Password Decryption (Testing)

### Python Decryption Script
A Python script (`decrypt_passwords.py`) is provided for verifying encrypted passwords:

**Requirements:**
```bash
pip install pycryptodome psycopg2-binary hvac
```

**Usage:**
```bash
python3 decrypt_passwords.py
```

**What it does:**
1. Connects to Vault (kv-v2) to retrieve the Fernet key
2. Connects to PostgreSQL database
3. Decrypts all encrypted passwords in `ebiz.board` table
4. Displays ID, title, encrypted password, and decrypted plaintext

**Verified Working:** Tested on 2026-05-08 with hvac library

## Files Modified
1. `vault-crypto/` - Separate package for encryption (NEW)
   - `VaultCryptoService.java` - Encryption/decryption service
   - `build.gradle` - Build configuration
   - `README.md` - Usage documentation
2. `src/main/java/com/xaan/demo/service/PasswordService.java` - Refactored to use vault-crypto
3. `build.gradle` (demoApp) - Added vault-crypto dependency
4. `src/main/resources/application.properties` - Vault connection settings
5. `decrypt_passwords.py` - Python script for password verification (added 2026-05-08)

## Date: 2026-05-08
## Status: vault-crypto package integrated and tested in production
