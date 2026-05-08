# Vault Integration Architecture - demoApp

## Current Architecture (Updated 2026-05-08)

**Note**: Encryption now uses `vault-crypto` package (separate JAR).
See `/home/xaan/opencode/projects/vault-crypto/README.md` for details.

```
┌─────────────────────────────────────────────────────────────┐
│                    demoApp (Spring Boot)                    │
│                                                             │
│  ┌─────────────────────────┐    ┌─────────────────────────┐ │
│  │  application.properties │    │  PasswordService.java   │ │
│  │  - Vault URI           │───▶│  - VaultOperations       │ │
│  │  - Token (env var)     │    │  - loadEncryptionKey()   │ │
│  └───────────┬───────────┘    │  - Fernet key from Vault  │ │
│              │                └───────────┬───────────────┘ │
│              │                            │                 │
│  ┌───────────▼────────────────────────────▼──────────────┐  │
│  │     Spring Cloud Vault (spring-cloud-starter-vault)   │  │
│  └────────────────────────────┬──────────────────────────┘  │
└───────────────────────────────┼─────────────────────────────┘
                                │
                                │ HTTP REST API (Token Auth)
                                │
                    ┌───────────▼───────────────────┐
                    │   Vault Server (kv-v2)        │
                    │   192.168.2.57:8200           │
                    │                               │
                    │   Mount: ebiz_service         │
                    │   Path: ebiz_db/data-enc-key  │
                    │                               │
                    │  ┌────────────────────┐       │
                    │  │ Key: fernet-key    │       │
                    │  │ (32 bytes)         │       │
                    │  │ - 16 bytes: AES-128│       │
                    │  │ - 16 bytes: HMAC   │       │
                    │  └────────────────────┘       │
                    └───────────────────────────────┘
                                │
                                │ VaultResponse
                                ▼
                    ┌─────────────────────────┐
                    │   Fernet Key Retrieved  │
                    │   Base64URL decoded     │
                    │   → byte[32]            │
                    └────────────┬────────────┘
                                 │
                    ┌────────────▼──────────────┐
                    │   PasswordService         │
                    │   - encryptionKey = byte[]│
                    │   - AES-128 encryption    │
                    │   - Base64 output         │
                    └────────────┬──────────────┘
                                 │
                                 ▼
                    ┌────────────────────────────┐
                    │   Database (ebiz.board)    │
                    │   password = "pU4nAaB..."  │
                    │   (AES encrypted)          │
                    └────────────────────────────┘
```

## Password Encryption Flow (Vault kv-v2 Fernet Key)

```
┌─────────────────────────────────────────────────────────────┐
│                 Password Handling Flow                      │
│                                                             │
│    User Input (Password: "vaulttest123")                    │
│         │                                                   │
│         ▼                                                   │
│    ┌──────────────┐      ┌──────────────────────────┐       │
│    │BoardService  │      │ PasswordService          │       │
│    │.java         │─────▶│ .java                    │       │
│    │ - save()     │      │ - encryptPassword()      │       │
│    │ - update()   │      │ - Uses Vault Fernet key  │       │
│    └──────────────┘      └───────────┬──────────────┘       │
│                                       │                     │
│                          ┌────────────▼──────────────┐       │
│                          │   VaultCryptoService      │       │
│                          │   (from vault-crypto)    │       │
│                          │   ↓                       │       │
│                          │   AES-256 Encryption      │       │
│                          │   (ECB mode, 32 bytes) │       │
│                          └────────────┬──────────────┘       │
│                                       │                     │
│                          ┌────────────▼──────────────┐      │
│                          │   AES Encryption           │       │
│                          │   → Base64 Encoded        │       │
│                          │   Output: JrwIlNN9...==    │       │
│                          └────────────┬──────────────┘      │
│                                       │                     │
│                                       ▼                     │
│                          ┌──────────────────────────┐       │
│                          │   Database (ebiz.board)  │       │
│                          │   password = "pU4nAaB..."│       │
│                          │   (AES encrypted)        │       │
│                          └──────────────────────────┘       │
└─────────────────────────────────────────────────────────────┘
```

─## Vault kv-2 Integration Architecture
 
``` 
┌────────────────────────────────────────────────────────────────────┐
│                    demoApp (Spring Boot)                           │
│                                                                    │
│  ┌───────────────────────────────────────────────────────────┐     │
│  │  @Service                                                 │     │
│  │  public class PasswordService {                           │     │
│  │      private final VaultOperations vaultOperations;       │     │
│  │      private byte[] encryptionKey;                        │     │
│  │                                                           │     │
│  │      public PasswordService(VaultOperations vaultOps) {   │     │
│  │          this.vaultOperations = vaultOps;                 │     │
│  │          loadEncryptionKey();                             │     │
│  │      }                                                    │     │
│  │                                                           │     │
│  │      private void loadEncryptionKey() {                   │     │
│  │          VaultResponse response =                         │     │
│  │              vaultOperations.read("ebiz_service/          │     │
│  │                        data/ebiz_db/data-enc-key");       │     │
│  │          // kv-v2 response structure:                     │     │
│  │          // {"data": {"data": {...}, "metadata": {...}}   │     │
│  │          Map<String, Object> outerData =                  │     │
│  │              response.getData();                          │     │
│  │          Map<String, Object> secretData =                 │     │
│  │              (Map<String, Object>) outerData.get("data"); │     │
│  │          String fernetKeyBase64 =                         │     │
│  │              (String) secretData.get("fernet-key");       │     │
│  │          // Decode URL-safe Base64 (uses '-' and '_')     │     │
│  │          this.encryptionKey =                             │     │
│  │              Base64.getUrlDecoder().decode(fernetKeyBase64│     │
│  │      }                                                    │     │
│  │  }                                                        │     │
│  └───────────────────────────────────────────────────────────┘     │
└────────────────────────────────────────────────────────────────────┘
                            │
                            │ VaultOperations.read()
                            │ HTTP REST API (Token Auth)
                            ▼
┌─────────────────────────────────────────────────────────────┐
│                HashiCorp Vault Server (kv-v2)               │
│                    192.168.2.57:8200                        │
│                                                             │
│  ┌───────────────────────────────────────────────────────┐  │
│  │  Mount: ebiz_service (kv-v2 versioned backend)        │  │
│  │                                                       │  │
│  │  Path: ebiz_db/data-enc-key                           │  │
│  │  Full API Path: /v1/ebiz_service/data/ebiz_db/        │  │
│  │                data-enc-key                           │  │
│  │                                                       │  │
│  │  ┌─────────────────────────────────────────────────┐  │  │
│  │  │  Vault read response:                           │  │  │
│  │  │  {                                              │  │  │
│  │  │    "data": {                                    │  │  │
│  │  │      "data": {                                  │  │  │
│  │  │        "description": "encryption key for...",  │  │  │
│  │  │        "fernet-key": "NgqO...=="                │  │  │
│  │  │      },                                         │  │  │
│  │  │      "metadata": { ... }                        │  │  │
│  │  │    }                                            │  │  │
│  │  │  }                                              │  │  │
│  │  └─────────────────────────────────────────────────┘  │  │
│  └───────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

## Vault Secret Structure (kv-v2)

```
# Enable kv-v2 backend
vault secrets enable -path=ebiz_service kv-v2

# Store Fernet key
vault kv put -mount=ebiz_service ebiz_db/data-enc-key \
    fernet-key="NgqOBievnB9500cQOnSQ-cmbBx38KnOiKx5ooQ_e97Y=" \
    description="encryption key for ebiz db column"

# Read secret (kv-v2 adds 'data/' to path)
vault kv get -mount=ebiz_service ebiz_db/data-enc-key

        │
        ▼
┌─────────────────────────────────────────────────────────────┐
│  Vault Path: ebiz_service/data/ebiz_db/data-enc-key         │
│                                                             │
│  ======== Secret Path =============                         │
│  ebiz_service/data/ebiz_db/data-enc-key                     │
│                                                             │
│  ======= Metadata =======                                   │
│  Key                Value                                   │
│  ---                -----                                   │
│  created_time       2026-05-06T11:32:48...                  │
│  version            1                                       │
│                                                             │
│  ======= Data =======                                       │
│  Key            Value                                       │
│  ---            -----                                       │
│  description    encryption key for ebiz db column           │
│  fernet-key     NgqOBievnB9500cQOnSQ-...                    │
└─────────────────────────────────────────────────────────────┘
        │
        ▼
┌─────────────────────────────────────────────────────────────┐
│  Fernet Key Structure (32 bytes total)                      │
│                                                             │
│  ┌──────────────────┬──────────────────┐                    │
│  │  Bytes 0-15      │  Bytes 16-31     │                    │
│  │  AES-128 Key     │  HMAC-SHA256     │                    │
│  │  (16 bytes)      │  (16 bytes)      │                    │
│  └──────────────────┴──────────────────┘                    │
│                                                             │
│  Format: URL-safe Base64 (uses '-' and '_')                 │
│  Example: NgqOBievnB9500cQOnSQ-cmbBx38KnOiKx5ooQ_e97Y=      │
└─────────────────────────────────────────────────────────────┘
        │
        ▼ (Base64.getUrlDecoder().decode())
┌─────────────────────────────────────────────────────────────┐
│  encryptionKey = byte[32]                                   │
│  - encryptionKey[0..15] → AES-128 secret key                │
│  - encryptionKey[16..31] → HMAC-SHA256 key (unused)         │
└─────────────────────────────────────────────────────────────┘
```

## Configuration Files

### application.properties
```properties
# Vault Configuration
spring.cloud.vault.uri=http://192.168.2.57:8200
spring.cloud.vault.token=VAULT_TOKEN_PLACEHOLDER
spring.cloud.vault.fail-fast=false
```

### Vault Server
```bash
# Vault Address
VAULT_ADDR='http://192.168.2.57:8200'
VAULT_TOKEN='VAULT_TOKEN_PLACEHOLDER'

# Check Vault status
curl http://192.168.2.57:8200/v1/sys/health

# Read Fernet key
vault kv get -mount=ebiz_service ebiz_db/data-enc-key
```

## How It Works (Step by Step)

```
START Application
  │
  ▼
┌───────────────────┐
│ PasswordService   │
│ Constructor       │
└────────┬──────────┘
         │
         ▼
┌─────────────────────┐
│ loadEncryptionKey() │
└────────┬────────────┘
         │
         ▼
┌────────────────────────────────────────────┐
│ vaultOperations.read(                      │
│   "ebiz_service/data/ebiz_db/data-enc-key")│
└────────┬───────────────────────────────────┘
         │ HTTP Request to Vault
         ▼
┌─────────────────────────────────────────┐
│ Vault Response (kv-v2):                 │
│ {                                       │
│   "data": {                             │
│     "data": {                           │
│       "fernet-key": "NgqO...==",        │
│       "description": "..."              │
│     },                                  │
│     "metadata": {...}                   │
│   }                                     │
│ }                                       │
└────────┬────────────────────────────────┘
         │
         ▼
┌────────────────────────────────────────────────┐
│ outerData = response.getData()                 │
│ secretData = outerData.get("data")             │
│ fernetKeyBase64 = secretData.get("fernet-key") │
└────────┬───────────────────────────────────────┘
         │
         ▼
┌────────────────────────────────────────────────┐
│ Base64.getUrlDecoder().decode(fernetKeyBase64) │
│ → byte[32] (URL-safe Base64 decoding)          │
└────────┬───────────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────────────┐
│ this.encryptionKey = decoded bytes      │
│ (ready for AES encryption)              │
└─────────────────────────────────────────┘
         │
         ▼
Application Ready ✅
```

## Verification (Tested 2026-05-08)

### vault-crypto Package Integration Test

**Test Date**: 2026-05-08
**Environment**: Production server 192.168.2.57

| Test Case | Result | Details |
|-----------|--------|---------|
| App Startup | ✅ Success | Reads Fernet key from Vault |
| POST /api/v1/posts | ✅ Success | Post ID 2017588 created |
| Password Encryption | ✅ Success | `JrwIlNN9YVMIxpqWvYhlNGfd7CUf1wjOgXAHLRIf0io=` stored |
| Password Decryption | ✅ Success | Python script verified (`decrypt_passwords.py`) |
| vault-crypto Package | ✅ Success | Separate JAR integrated and working |
| Vault Unavailable | ⚠️ Graceful | fail-fast=false, app continues |
| Fernet Key Invalid | ❌ Error | RuntimeException with details |

### Test Details

**Test 1: Password Encryption**
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
# Output: 2017588 | Vault-Crypto Test | JrwIlNN9... | testVaultCrypto123
```

✅ **Result**: Encryption and decryption working correctly with vault-crypto package.

### Database Verification
```sql
SELECT id, title, password FROM ebiz.board WHERE id = 2017587;

   id    |     title      |         password         
---------+----------------+--------------------------
 2017587 | Test Vault Key | pU4nAaBrwqPKLoV1Waa/tw==
```
## Security Notes

✅ **What's Implemented:**
1. Fernet key read from Vault kv-v2 at startup
2. URL-safe Base64 decoding for Fernet key format
3. AES-256 encryption with ECB mode (PKCS5 padding)
4. Base64 output for database storage
5. Python decryption script for password verification
6. **vault-crypto package** - Separate JAR for encryption (reusable)

⚠️ **Security Considerations:**
1. Vault token in `application.properties` (file is in `.gitignore`)
2. Fernet key is 32 bytes used as AES-256 key (ECB mode)
3. Vault communication uses HTTP (not HTTPS) - enable TLS for production
4. Consider using AppRole authentication instead of Token for production
5. **vault-crypto package** - Can be reused by other Spring Boot applications

🔒 **Best Practices:**
- Rotate Vault tokens regularly
- Enable Vault audit logging
- Restrict network access to Vault (firewall rules)
- Use TLS/HTTPS for Vault communication
- Consider implementing key rotation for encryption keys
- Keep vault-crypto package updated

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
## Status: vault-crypto package integrated, tested, and deployed to production ✅
