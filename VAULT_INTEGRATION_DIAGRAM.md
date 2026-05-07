# Vault Integration Architecture - demoApp

## Current Architecture (As-Is)

```
┌─────────────────────────────────────────────────────────────────────┐
│                        demoApp (Spring Boot)                       │
│                                                                     │
│  ┌──────────────────┐      ┌────────────────────────────────────┐  │
│  │  VaultConfig.java │      │  application.properties            │  │
│  │  (localhost:8200)│     │  - 192.168.2.57:8200              │  │
│  │  Token: hardcoded│     │  - Token: hvs.REDACTED_VAULT_TOKEN_FRAGMENT...       │  │
│  └────────┬─────────┘      │  - fail-fast: false               │  │
│           │                └──────────────┬───────────────────────┘  │
│           │                              │                          │
│  ┌────────▼──────────────────────────────▼──────────────────────┐  │
│  │         Spring Cloud Vault (spring-cloud-starter-vault)       │  │
│  └──────────────────────────────┬────────────────────────────────┘  │
└─────────────────────────────────┼────────────────────────────────────┘
                                  │
                                  │ HTTP (REST API)
                                  │
                    ┌─────────────▼─────────────┐
                    │   Vault Server              │
                    │   192.168.2.57:8200        │
                    │                             │
                    │   [Secret Path]             │
                    │   secret/demoApp            │
                    │   (NOT YET CONFIGURED)     │
                    └─────────────────────────────┘
                                  │
                                  │
                    ┌─────────────▼─────────────┐
                    │   Secret Retrieval:         │
                    │   - DB Password            │
                    │   - Encryption Key          │
                    │   (NOT IMPLEMENTED YET)    │
                    └─────────────────────────────┘
```

## Password Encryption Flow (Current Implementation)

```
┌─────────────────────────────────────────────────────────────────────┐
│                     Password Handling Flow                          │
│                                                                     │
│    User Input (Password)                                           │
│         │                                                          │
│         ▼                                                          │
│    ┌─────────────────┐      ┌─────────────────────────────┐       │
│    │ BoardService.java│      │ PasswordService.java         │       │
│    │ - save()         │─────▶│ - encryptPassword()          │       │
│    │ - update()       │      │ - AES Algorithm              │       │
│    └─────────────────┘      │ - Key: "MySecretKey12345"   │       │
│                              │   (HARDCODED - NOT FROM VAULT)│     │
│                              └──────────────┬──────────────┘       │
│                                            │                      │
│                                            ▼                      │
│                              ┌─────────────────────────────┐       │
│                              │   AES Encryption            │       │
│                              │   → Base64 Encoded         │       │
│                              └──────────────┬──────────────┘       │
│                                            │                      │
│                                            ▼                      │
│                              ┌─────────────────────────────┐       │
│                              │   Database (ebiz.board)     │       │
│                              │   password = "IdKBPP2o..."  │       │
│                              │   (Encrypted)               │       │
│                              └─────────────────────────────┘       │
└─────────────────────────────────────────────────────────────────────┘
```

## Intended Architecture (To-Be) - With Full Vault Integration

```
┌─────────────────────────────────────────────────────────────────────┐
│                        demoApp (Spring Boot)                       │
│                                                                     │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │  @Configuration                                               │   │
│  │  public class VaultConfig extends AbstractVaultConfiguration { │   │
│  │      vaultEndpoint() → 192.168.2.57:8200                    │   │
│  │      clientAuthentication() → Token (from app.properties)    │   │
│  │  }                                                           │   │
│  └───────────────────────┬──────────────────────────────────────┘   │
│                          │                                          │
│  ┌───────────────────────▼──────────────────────────────────────┐   │
│  │  Spring Cloud Vault Auto-Configuration                       │   │
│  │  - Reads spring.cloud.vault.* properties                     │   │
│  │  - Connects to Vault with token authentication              │   │
│  │  - fail-fast=false → App starts even if Vault is down       │   │
│  └───────────────────────┬──────────────────────────────────────┘   │
└──────────────────────────┼───────────────────────────────────────────┘
                           │
                           │ HTTP REST API
                           │ Token: VAULT_TOKEN_PLACEHOLDER
                           ▼
┌─────────────────────────────────────────────────────────────────────┐
│                    HashiCorp Vault Server                            │
│                    192.168.2.57:8200                                │
│                                                                     │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │  Secret Backend: kv-v2                                       │   │
│  │  Path: secret/demoApp                                         │   │
│  │                                                                │   │
│  │  ┌──────────────────┐  ┌──────────────────┐  ┌───────────┐ │   │
│  │  │ db.password      │  │ encryption.key   │  │ api.token  │ │   │
│  │  │ = "REDACTED_DB_PASSWORD"        │  │ = "MySecret..."  │  │ = "..."    │ │   │
│  │  └──────────────────┘  └──────────────────┘  └───────────┘ │   │
│  └──────────────────────────────────────────────────────────────┘   │
│                                                                     │
│  Vault CLI example:                                                  │
│  $ vault kv put secret/demoApp db.password="REDACTED_DB_PASSWORD"                   │
│  $ vault kv put secret/demoApp encryption.key="MySecretKey12345"    │
└─────────────────────────────────────────────────────────────────────┘
                           │
                           │ Secret Injection
                           ▼
┌─────────────────────────────────────────────────────────────────────┐
│                   Application Runtime                                │
│                                                                     │
│  ┌──────────────────┐      ┌─────────────────────────────┐        │
│  │ @Value("${db.password}")                                      │        │
│  │ or                                                           │        │
│  │ @VaultPropertySource("secret/demoApp")         │        │
│  └──────────────────┘      ┌─────────────────────────────┐        │
│                              │   Database Config            │        │
│                              │   - password from Vault      │        │
│                              └──────────────┬──────────────┘        │
│                                            │                      │
│                              ┌─────────────▼──────────────┐       │
│                              │   PasswordService           │       │
│                              │   - key from Vault         │       │
│                              │   (instead of hardcoded)   │       │
│                              └────────────────────────────┘       │
└─────────────────────────────────────────────────────────────────────┘
```

## Configuration Flow Diagram

```
START
  │
  ▼
┌─────────────────────┐
│ Application Startup  │
└─────────┬───────────┘
          │
          ▼
┌─────────────────────────────────────────┐
│ Read application.properties             │
│ - spring.cloud.vault.uri                │
│ - spring.cloud.vault.token              │
│ - spring.cloud.vault.fail-fast=false    │
└─────────┬───────────────────────────────┘
          │
          ▼
┌─────────────────────────────────────────┐
│ Spring Cloud Vault Auto-Config         │
│ - Initialize VaultTemplate             │
│ - Setup PropertySource for secrets     │
└─────────┬───────────────────────────────┘
          │
          ├───[Vault Available]───▶ Read secrets from secret/demoApp
          │                              │
          │                              ▼
          │                    Inject into @Value / @ConfigurationProperties
          │
          └───[Vault Unavailable]───▶ (fail-fast=false)
                                         │
                                         ▼
                                    App continues with:
                                    - Defaults
                                    - Environment variables
                                    - Hardcoded values (NOT SECURE)
```

## Secret Path Structure in Vault

```
vault secrets enable -path=secret kv-v2
        │
        ▼
vault kv put secret/demoApp \
    db.username="postgres" \
    db.password="REDACTED_DB_PASSWORD" \
    encryption.key="MySecretKey12345" \
    vault.token="hvs.newTokenIfNeeded"

        │
        ▼
┌────────────────────────────────────────────┐
│  vault kv get secret/demoApp               │
│                                            │
│  Key              Value                     │
│  ─────────────────────────────────────     │
│  db.username      postgres                  │
│  db.password      REDACTED_DB_PASSWORD                     │
│  encryption.key   MySecretKey12345         │
│  vault.token      hvs.newTokenIfNeeded     │
└────────────────────────────────────────────┘
```

## Current vs Target State

| Component              | Current State               | Target State                |
|------------------------|----------------------------|----------------------------|
| Vault Endpoint         | 192.168.2.57:8200         | 192.168.2.57:8200         |
| Authentication        | Token (in app.properties)  | Token (in app.properties)  |
| DB Password           | Hardcoded in app.properties| From Vault secret          |
| Encryption Key        | Hardcoded in Java          | From Vault secret          |
| Fail-fast             | false                      | false (dev) / true (prod) |
| Secret Path           | Not configured             | secret/demoApp            |
| Fallback              | App continues              | App continues (graceful)  |

## How to Complete Vault Integration

### Step 1: Configure Secrets in Vault
```bash
# On Vault server (192.168.2.57)
export VAULT_ADDR='http://192.168.2.57:8200'
export VAULT_TOKEN='VAULT_TOKEN_PLACEHOLDER'

# Store secrets
vault kv put secret/demoApp \
    db.username="postgres" \
    db.password="REDACTED_DB_PASSWORD" \
    encryption.key="MySecretKey12345"
```

### Step 2: Update application.properties
```properties
# Remove hardcoded values, let Vault inject them
spring.datasource.username=${db.username}
spring.datasource.password=${db.password}
```

### Step 3: Update PasswordService.java
```java
@Service
public class PasswordService {
    
    @Value("${encryption.key}")
    private String encryptionKey;  // Injected from Vault
    
    private byte[] getSecretKey() {
        return encryptionKey.getBytes();
    }
    // ... rest of the code
}
```

### Step 4: Verify
```bash
# Check Vault connection
curl http://192.168.2.57:8200/v1/sys/health

# Deploy and test
./deploy.sh

# Check logs for Vault integration
ssh xaan@192.168.2.57 "tail -f /home/xaan/ws/demoBBS/log/demoBBS-$(date +%Y-%m-%d).log | grep -i vault"
```

## Security Notes

⚠️ **Current Issues:**
1. Vault token is hardcoded in `application.properties` (should be env var)
2. Encryption key is hardcoded in `PasswordService.java` (should come from Vault)
3. DB password is in `application.properties` (should come from Vault)
4. `VaultConfig.java` has `localhost:8200` but `application.properties` overrides it

✅ **What's Good:**
1. `fail-fast=false` prevents app crash if Vault is down
2. Spring Cloud Vault is properly configured as dependency
3. Secret backend and path are defined

🔒 **Security Best Practices:**
- Rotate Vault tokens regularly
- Use AppRole authentication instead of Token for production
- Enable Vault audit logging
- Restrict network access to Vault (firewall rules)
- Use TLS/HTTPS for Vault communication
