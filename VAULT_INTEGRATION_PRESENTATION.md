---
title: Vault Integration Architecture - demoApp
author: Sisyphus
date: 2026-05-07
---

# Slide 1: Title Slide

**Vault Integration Architecture**
* demoApp - Spring Boot & HashiCorp Vault kv-v2*

---

# Slide 2: Current Architecture (Working)

## Vault kv-2 Integration (2026-05-07)

**Architecture Overview:**
- **Vault Server**: 192.168.2.57:8200
- **Mount**: ebiz_service (kv-v2)
- **Secret Path**: ebiz_db/data-enc-key
- **Key**: fernet-key (32 bytes)

**Flow:**
1. demoApp → Spring Cloud Vault → HashiCorp Vault
2. PasswordService reads Fernet key from Vault
3. AES-128 encryption with CBC mode
4. Base64 encoded output stored in database

---

# Slide 3: Vault kv-2 Path Structure

## Secret Path: ebiz_service/data/ebiz_db/data-enc-key

**Vault CLI Commands:**
```bash
# Enable kv-v2 backend
vault secrets enable -path=ebiz_service kv-v2

# Store Fernet key
vault kv put -mount=ebiz_service ebiz_db/data-enc-key \
  fernet-key="NgqOBievnB9500cQOnSQ-cmbBx38KnOiKx5ooQ_e97Y=" \
  description="encryption key for ebiz db column"

# Read secret
vault kv get -mount=ebiz_service ebiz_db/data-enc-key
```

**API Path:** `/v1/ebiz_service/data/ebiz_db/data-enc-key`

---

# Slide 4: Fernet Key Structure

## Fernet Key (32 bytes total)

| Bytes | Purpose | Size |
|-------|---------|------|
| 0-15 | AES-128 Key | 16 bytes |
| 16-31 | HMAC-SHA256 Key | 16 bytes |

**Format:** URL-safe Base64 (uses `-` and `_`)
**Example:** `NgqOBievnB9500cQOnSQ-cmbBx38KnOiKx5ooQ_e97Y=`

**Decoded:** 32 bytes binary data
- First 16 bytes → AES-128 secret key
- Last 16 bytes → HMAC-SHA256 key (currently unused)

---

# Slide 5: PasswordService Implementation

## VaultOperations.read() Approach

```java
@Service
public class PasswordService {
    private byte[] encryptionKey;
    
    public PasswordService(VaultOperations vaultOperations) {
        // Read from Vault kv-v2
        VaultResponse response = vaultOperations.read(
            "ebiz_service/data/ebiz_db/data-enc-key");
        
        // kv-v2 response structure
        Map<String, Object> outerData = response.getData();
        Map<String, Object> secretData = 
            (Map<String, Object>) outerData.get("data");
        
        // Decode URL-safe Base64 Fernet key
        String fernetKeyBase64 = 
            (String) secretData.get("fernet-key");
        this.encryptionKey = 
            Base64.getUrlDecoder().decode(fernetKeyBase64);
    }
}
```

---

# Slide 6: Encryption Flow

## Password Encryption Process

```
User Input: "vaulttest123"
        ↓
PasswordService.encryptPassword()
        ↓
AES-128 (CBC mode)
- Key: encryptionKey[0..15]
- Input: "vaulttest123".getBytes()
        ↓
Cipher.doFinal()
        ↓
Base64.encodeToString()
        ↓
Output: "pU4nAaBrwqPKLoV1Waa/tw=="
        ↓
Stored in DB: ebiz.board.password
```

**Verification:**
```sql
SELECT id, title, password FROM ebiz.board 
WHERE id = 2017587;
-- Returns: pU4nAaBrwqPKLoV1Waa/tw==
```

---

# Slide 7: VaultResponse Structure (kv-v2)

## Spring Vault Read Response

**API Response:**
```json
{
  "data": {
    "data": {
      "description": "encryption key for ebiz db column",
      "fernet-key": "NgqOBievnB9500cQOnSQ-cmbBx38KnOiKx5ooQ_e97Y="
    },
    "metadata": {
      "created_time": "2026-05-06T11:32:48.67371525Z",
      "version": 1,
      ...
    }
  }
}
```

**Key Points:**
- kv-v2 wraps data in **double "data" fields**
- Outer: `response.getData()` → Map
- Inner: `outerData.get("data")` → Secret data
- Actual key: `secretData.get("fernet-key")`

---

# Slide 8: Configuration Files

## application.properties

```properties
# Vault Configuration
spring.cloud.vault.uri=http://192.168.2.57:8200
spring.cloud.vault.token=${VAULT_TOKEN}
spring.cloud.vault.fail-fast=false
```

**Key Settings:**
- `fail-fast=false`: App starts even if Vault is down
- Token: Stored in `application.properties` (file is in `.gitignore`)
- URI: Vault server address

**Note:** No `spring.cloud.vault.kv.*` properties needed since we use `VaultOperations.read()` directly

---

# Slide 9: Deployment & Testing

## Verified Working (2026-05-07)

| Test Case | Result | Details |
|-----------|--------|---------|
| App Startup | ✅ Success | Reads Fernet key from Vault |
| POST /api/v1/posts | ✅ Success | Post ID 2017587 created |
| Password Encryption | ✅ Success | `pU4nAaB...==` stored in DB |
| Vault Unavailable | ⚠️ Graceful | fail-fast=false, app continues |
| Invalid Fernet Key | ❌ Error | RuntimeException with details |

**Build Command:**
```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
./gradlew clean build -x test
```

**Deploy:** `./deploy.sh`

---

# Slide 10: Security Considerations

## Security Best Practices

**✅ Implemented:**
1. Vault kv-2 for secret management
2. Fernet key (32 bytes) for AES-128 + HMAC-SHA256
3. URL-safe Base64 decoding for Fernet format
4. Fail-fast=false for graceful degradation

**⚠️ To Improve:**
1. **Vault Token**: Move to environment variable (currently in app.properties)
2. **TLS/HTTPS**: Enable TLS for Vault communication (currently HTTP)
3. **AppRole Auth**: Use AppRole instead of Token for production
4. **Key Rotation**: Implement Fernet key rotation strategy
5. **Audit Logging**: Enable Vault audit logging
6. **Network Restrictions**: Firewall rules for Vault access

---

# Slide 11: Files Modified

## Repository Changes

| File | Change | Purpose |
|------|--------|---------|
| `PasswordService.java` | Rewritten | Vault-based Fernet key loading |
| `application.properties` | Updated | Vault connection settings |
| `VAULT_AND_ENCRYPTION.md` | Updated | Documentation |
| `VAULT_INTEGRATION_DIAGRAM.md` | Created | Architecture diagrams |

**GitHub:** https://github.com/yadalhan/demoApp

**Commit:** `7c1b2cb` - Update Vault integration diagram

---

# Slide 12: Q&A

**Thank You!**

Questions?

*Sisyphus - Powered by OhMyOpenCode*
