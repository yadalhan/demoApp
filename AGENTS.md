# demoApp - Project Knowledge

## Critical Information (Last Updated: 2026-05-08)

### Vault Configuration (Updated 2026-05-08)
- **Server**: `http://192.168.2.57:8200`
- **Token**: `hvs.YOUR_TOKEN_HERE` (stored in `application.properties` which is in `.gitignore`)
- **Config File**: `src/main/resources/application.properties`
- **Fail-fast**: `false` (app starts even if Vault unavailable)
- **Secret Path**: `ebiz_service/data/ebiz_db/data-enc-key` (kv-v2 backend)
- **Fernet Key**: Read from Vault via `VaultOperations.read()` at startup
- **Key Structure**: 32 bytes (used as AES-256 key, ECB mode)

### Password Encryption (Updated 2026-05-08)
- **Issue Fixed**: Passwords were stored in plain text
- **Fix**: Using `vault-crypto` package (separate JAR) for encryption
- **Package**: `com.xaan:vault-crypto:0.0.1-SNAPSHOT` (built separately)
- **Algorithm**: AES-256 (ECB mode, PKCS5 padding) using Fernet key from Vault
- **Key Source**: Vault kv-2 path `ebiz_service/data/ebiz_db/data-enc-key`, field `fernet-key`
- **Status**: New posts encrypt passwords automatically; old plain-text passwords migrated
- **Verification**: Python decryption script (`decrypt_passwords.py`) tested and verified
- **Details**: See `VAULT_AND_ENCRYPTION.md` and `VAULT_INTEGRATION_DIAGRAM.md`
- **vault-crypto docs**: See `vault-crypto/README.md`

### Production Deployment
- **Server**: `192.168.2.57`
- **User**: `xaan`
- **App Path**: `/home/xaan/ws/demoBBS/app`
- **Log Path**: `/home/xaan/ws/demoBBS/log`
- **Deploy Script**: `./deploy.sh` (builds with Java 17, deploys JAR, restarts app)
- **JAR Name**: `xaandemo-0.0.3-SNAPSHOT.jar`
- **App URL**: `http://192.168.2.57:8080`

### Database
- **Host**: `192.168.2.57:21716`
- **Database**: `limadb`
- **Schema**: `ebiz`
- **Table**: `ebiz.board`
- **User**: `postgres`
- **Password**: `REDACTED_DB_PASSWORD`

### API Endpoints
- **REST API**: `/api/v1/posts` (GET by ID, POST create, PUT update)
- **Web Pages**: `/`, `/last100`, `/list1st`, `/posts/save`, `/posts/update/{id}`

### Java Version
- **Required**: Java 17
- **JAVA_HOME**: `/usr/lib/jvm/java-17-openjdk-amd64`
- **Default System Java**: Java 8 (must override with JAVA_HOME)

### Gradle Version
- **Gradle Version**: 8.7
- **GRADLE_HOME**: `/opt/gradle/gradle-8.7`
- **Project Uses**: Gradle wrapper (`./gradlew`) - downloads correct version automatically

### Build Commands
```bash
# Set environment variables before build
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export PATH=$JAVA_HOME/bin:$PATH
export PATH=/opt/gradle/gradle-8.7/bin:$PATH

# Build
./gradlew clean build
```

### Build Scripts
- **build-with-env.sh**: Sets JAVA_HOME automatically
- **deploy.sh**: Builds with Java 17 and deploys to production

### Recent Changes (2026-05-08)
1. Created `vault-crypto` package (separate JAR: `vault-crypto-0.0.1-SNAPSHOT.jar`)
2. Refactored `PasswordService.java` to use `VaultCryptoService` from vault-crypto
3. Updated `build.gradle` to depend on `com.xaan:vault-crypto:0.0.1-SNAPSHOT`
4. Built and published vault-crypto to Maven local (`~/.m2/repository`)
5. Deployed to production (192.168.2.57) - tested successfully
6. Verified encryption/decryption with test post ID 2017588
7. Updated documentation (VAULT_AND_ENCRYPTION.md, README.md, vault-crypto/README.md)

### Previous Changes (2026-05-06)
1. Updated Vault URI and token in `application.properties`
2. Fixed password encryption in `BoardService.java` (injected `PasswordService`)
3. Migrated old plain-text passwords to encrypted format
4. Deployed fixed version using `deploy.sh`
5. Verified encryption working on production

### Important Notes
- Password encryption uses `vault-crypto` package (AES-256, ECB mode)
- vault-crypto: `/home/xaan/opencode/projects/vault-crypto/` (separate JAR)
- Python decryption script (`decrypt_passwords.py`) available for password verification
- `spring.jpa.open-in-view` warning can be suppressed by setting `spring.jpa.open-in-view=false`
- Java 8 is default system Java - always use Java 17 for builds

### File References
- **Vault/Encryption Docs**: `VAULT_AND_ENCRYPTION.md`
- **Main Config**: `src/main/resources/application.properties`
- **Password Service**: `src/main/java/com/xaan/demo/service/PasswordService.java` (uses vault-crypto)
- **Board Service**: `src/main/java/com/xaan/demo/service/BoardService.java`
- **Deploy Script**: `deploy.sh`
- **Python Decryption Script**: `decrypt_passwords.py` (tested 2026-05-08)
- **vault-crypto Package**: `/home/xaan/opencode/projects/vault-crypto/`
