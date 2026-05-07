# Vault Configuration and Password Encryption

## Overview
This document describes the Vault server configuration and password encryption implementation for the demoApp project.

## Vault Configuration (Updated 2026-05-07)

### kv-v2 Backend Configuration
The application now uses HashiCorp Vault kv-v2 (versioned key-value) backend:

**Vault Path**: `ebiz_service/data/ebiz_db/data-enc-key`
- **Mount**: `ebiz_service` (kv-v2)
- **Secret Path**: `ebiz_db/data-enc-key`
- **Key**: `fernet-key` (32 bytes: 16 for AES-128 + 16 for HMAC-SHA256)
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
3. `PasswordService` uses the decoded key for AES-128 encryption/decryption
4. Vault connection details in `application.properties`:
   ```properties
   spring.cloud.vault.uri=http://192.168.2.57:8200
   spring.cloud.vault.token=VAULT_TOKEN_PLACEHOLDER
   spring.cloud.vault.fail-fast=false
   ```

## Password Encryption

### Implementation
`PasswordService.java` uses Vault-sourced Fernet key for AES encryption:

- **Algorithm**: AES-128 (CBC mode)
- **Key Source**: Vault kv-v2 (`ebiz_service/data/ebiz_db/data-enc-key`, field: `fernet-key`)
- **Key Format**: Fernet key (32 bytes: 16 for AES + 16 for HMAC-SHA256)
- **Output**: Base64 encoded string (e.g., `pU4nAaBrwqPKLoV1Waa/tw==`)

**Key Implementation:**
```java
@Service
public class PasswordService {
    private byte[] encryptionKey;
    
    public PasswordService(VaultOperations vaultOperations) {
        // Load Fernet key from Vault kv-v2
        VaultResponse response = vaultOperations.read("ebiz_service/data/ebiz_db/data-enc-key");
        Map<String, Object> outerData = response.getData();
        Map<String, Object> secretData = (Map<String, Object>) outerData.get("data");
        String fernetKeyBase64 = (String) secretData.get("fernet-key");
        this.encryptionKey = Base64.getUrlDecoder().decode(fernetKeyBase64);
    }
    
    public String encryptPassword(String password) {
        SecretKeySpec secretKey = new SecretKeySpec(encryptionKey, "AES");
        Cipher cipher = Cipher.getInstance("AES");
        cipher.init(Cipher.ENCRYPT_MODE, secretKey);
        return Base64.getEncoder().encodeToString(cipher.doFinal(password.getBytes()));
    }
}
```

### Database Storage
Passwords are stored as Base64-encoded AES encrypted strings in `ebiz.board.password`:
```sql
SELECT id, title, password FROM ebiz.board WHERE id = 2017587;
-- Returns: pU4nAaBrwqPKLoV1Waa/tw== (encrypted)
```

## Deployment

### Verified Working (2026-05-07))
- ✅ Vault kv-v2 integration working
- ✅ Fernet key loaded from `ebiz_service/data/ebiz_db/data-enc-key`
- ✅ Password encryption successful (test post ID: 2017587)
- ✅ Application startup successful with Vault integration

## Security Notes

1. **Vault Token**: Stored in `application.properties` (which is in `.gitignore`)
2. **Fernet Key**: 32 bytes total (16 for AES-128 + 16 for HMAC-SHA256)
3. **Base64URL**: Fernet key uses URL-safe Base64 (`-` and `_` instead of `+` and `/`)
4. **Fail-Fast**: `false` (app starts even if Vault is unavailable, but encryption will fail)

## Files Modified
1. `src/main/java/com/xaan/demo/service/PasswordService.java` - Vault-based encryption key loading
2. `src/main/resources/application.properties` - Vault connection settings

## Date: 2026-05-07
## Status: Vault kv-v2 Fernet key integration complete
