# Vault Configuration and Password Encryption

## Overview
This document describes the Vault server configuration and password encryption implementation for the demoApp project.

## Vault Configuration (Updated 2026-05-07)

### kv-v2 Backend Configuration
The application now uses HashiCorp Vault kv-v2 (versioned key-value) backend:

```properties
# Vault Configuration
spring.cloud.vault.uri=http://192.168.2.57:8200
spring.cloud.vault.token=VAULT_TOKEN_PLACEHOLDER
spring.cloud.vault.fail-fast=false

# Vault KV Version 2 Backend Configuration
spring.cloud.vault.kv.enabled=true
spring.cloud.vault.kv.backend=ebiz_service
spring.cloud.vault.kv.application-name=ebiz_db
```

### Vault Server Details
- **Server**: 192.168.2.57:8200
- **KV Version**: 2 (versioned)
- **Mount Path**: `ebiz_service`
- **Secret Path**: `ebiz_db`
- **Encryption Key**: `data-enc-key` (stored in Vault)
- **Token**: VAULT_TOKEN_PLACEHOLDER

### Setting up Vault Secrets

To configure the encryption key in Vault:

```bash
# Enable kv-v2 backend (if not already enabled)
vault secrets enable -path=ebiz_service kv-v2

# Store the encryption key
vault kv put -mount=ebiz_service ebiz_db data-enc-key="MySecretKey12345"

# Verify the secret
vault kv get -mount=ebiz_service ebiz_db
```

### How It Works

1. **Spring Cloud Vault** reads from `ebiz_service/data/ebiz_db` (kv-v2 adds `data/` automatically)
2. The key `data-enc-key` is injected into `PasswordService` via `@Value("${data-enc-key}")`
3. `PasswordService` uses this key for AES encryption/decryption of passwords

## Password Encryption

### Issue Identified
Passwords were being stored in plain text in the database (ebiz.board table).

### Fix Implemented (2026-05-06)

#### 1. Modified BoardService.java
- Injected `PasswordService` into `BoardService`
- Modified `save()` method to encrypt password before saving
- Modified `update()` method to encrypt password if provided

**Key Changes:**
```java
// BoardService.java
private final PasswordService passwordService;

@Transactional
public Long save(BoardSaveRequestDto requestDto) {
    Board board = requestDto.toEntity();
    if (board.getPassword() != null && !board.getPassword().isEmpty()) {
        board.updatePassword(passwordService.encryptPassword(board.getPassword()));
    }
    return boardRepository.save(board).getId();
}

@Transactional
public Long update(Long id, BoardUpdateRequestDto requestDto) {
    Board board = boardRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("no article for id=" + id));
    board.update(requestDto.getTitle(), requestDto.getContent());
    if (requestDto.getPassword() != null && !requestDto.getPassword().isEmpty()) {
        board.updatePassword(passwordService.encryptPassword(requestDto.getPassword()));
    }
    return id;
}
```

#### 2. Encryption Implementation
`PasswordService.java` uses AES encryption with Base64 encoding:
- **Algorithm**: AES
- **Key Source**: Injected from Vault kv-v2 (`ebiz_service/ebiz_db/data-enc-key`)
- **Output**: Base64 encoded string (e.g., `IdKBPP2oSDzXjgCMfMtO+Q==`)

**Key Implementation:**
```java
@Value("${data-enc-key}")
private String encryptionKey; // Injected from Vault kv-v2

public String encryptPassword(String password) {
    SecretKeySpec secretKey = new SecretKeySpec(encryptionKey.getBytes(), "AES");
    Cipher cipher = Cipher.getInstance("AES");
    cipher.init(Cipher.ENCRYPT_MODE, secretKey);
    return Base64.getEncoder().encodeToString(cipher.doFinal(password.getBytes()));
}
```
Old plain-text passwords were migrated to encrypted format:
```sql
-- Example migration
UPDATE ebiz.board SET password = 'IdKBPP2oSDzXjgCMfMtO+Q==' 
WHERE id = 2017585 AND password = 'mypassword123';
```

### Verification
After deployment, verification showed:
- New posts (ID 2017586): Password stored as encrypted `IdKBPP2oSDzXjgCMfMtO+Q==`
- Migrated posts (ID 2017585): Successfully converted from plain text to encrypted
- Encryption status query:
  ```sql
  SELECT id, title, 
         CASE WHEN password ~ '^[A-Za-z0-9+/]+=*$' AND length(password) > 10 
              THEN 'ENCRYPTED' 
              ELSE 'PLAIN_OR_EMPTY' 
         END as enc_status 
  FROM ebiz.board 
  WHERE password IS NOT NULL AND password != '';
  ```

## Deployment

### Deployment Script
Used `deploy.sh` for deployment to production server (192.168.2.57):
```bash
./deploy.sh
```

### Deployment Steps
1. Build with Java 17: `export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 && ./gradlew clean build`
2. Distribute JAR to production: `scp build/libs/xaandemo-0.0.3-SNAPSHOT.jar`
3. Stop running application
4. Start new instance
5. Verify endpoints: `curl http://localhost:8080/last100`

### Production Server
- **Host**: 192.168.2.57
- **User**: xaan
- **App Directory**: `/home/xaan/ws/demoBBS/app`
- **Log Directory**: `/home/xaan/ws/demoBBS/log`
- **Application URL**: http://192.168.2.57:8080

## Test Results
- ✅ Main page: http://192.168.2.57:8080/ (200)
- ✅ Last 100: http://192.168.2.57:8080/last100 (200)
- ✅ API POST: `/api/v1/posts` (201)
- ✅ Password encryption working for new posts
- ✅ Old passwords migrated

## Security Recommendations
1. **Vault Secret Setup**: Ensure `data-enc-key` is configured in Vault at `ebiz_service/ebiz_db`
2. **Token Rotation**: Rotate Vault token regularly for security
3. **AppRole Authentication**: Use AppRole instead of Token auth for production
4. **Password Validation**: Implement password validation endpoint using `PasswordService.validatePassword()`
5. **HTTPS**: Enable HTTPS in production
6. **Database Access**: Restrict direct database access
7. **Vault TLS**: Enable TLS for Vault communication (currently using http)

## Files Modified
1. `src/main/resources/application.properties` - Vault kv-v2 config (ebiz_service/ebiz_db)
2. `src/main/java/com/xaan/demo/service/PasswordService.java` - Encryption key from Vault
3. `src/main/java/com/xaan/demo/config/VaultConfig.java` - Removed (using properties now)
4. `src/main/java/com/xaan/demo/service/BoardService.java` - Password encryption logic

## Date: 2026-05-07
## Status: Vault kv-v2 integration complete
