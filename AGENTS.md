# demoApp - Project Knowledge

## Critical Information (Last Updated: 2026-08-19)

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

### KEK-DEK Envelope Encryption (In progress, started 2026-08-15)
- **Goal**: Replace the single flat Vault key above with a KEK-DEK envelope model — a Vault-held KEK wraps one DEK per service domain (`board`, `user-pii`), and each domain's DEK is unwrapped once at startup and cached in memory so request-time encrypt/decrypt never calls Vault.
- **Design + phased plan**: See `KEK_DEK_ENCRYPTION_PLAN.md` (analysis of the old single-key structure, target architecture, ciphertext format, phase table with status)
- **Migration abandoned (2026-08-19)**: The `users` table will be dropped entirely, and existing `board.password` ciphertext is being left as-is rather than migrated. All legacy-format decrypt fallback and the P3 backfill migration code have been removed — `PasswordService` and `CryptoConfig` no longer reference the old `VaultCryptoService` at all. Decrypting/validating pre-existing ciphertext with the new code now fails cleanly (`CryptoException` / `false`) instead of falling back.
- **`VaultCryptoService` deleted (2026-08-19, vault-crypto v0.0.3)**: the old single-key class itself was removed from the vault-crypto library (not just deprecated) — any other project still using it is being ignored per the user's decision. vault-crypto's only public API now is the `envelope` package.
- **Code status (2026-08-19)**: P0-P2 all done. **P0 confirmed complete**: the user ran `bootstrap_kek_dek.py`'s output against the production Vault (`ebiz_service/data/ebiz_db/kek`, `.../dek/board`, `.../dek/user-pii`) with no errors; a subsequent `gradle test` run showed `DemoApplicationTests.contextLoads()` failing on a Postgres connection error rather than `KeyLoadingException`, confirming the KEK/DEK load successfully now. P1 (vault-crypto `envelope` package, single-key class removed) and P2 (demoApp wiring, legacy-free) are implemented, build, and pass tests.
- **Package/version**: `com.xaan:vault-crypto:0.0.5` (bumped `0.0.1 → 0.0.2 → 0.0.3 → 0.0.5`, published to `mavenLocal`; demoApp itself bumped `0.0.4 → 0.0.5` to match)

### Production Deployment
- **Server**: `192.168.2.57`
- **User**: `xaan`
- **App Path**: `/home/xaan/ws/demoBBS/app`
- **Log Path**: `/home/xaan/ws/demoBBS/log`
- **Deploy Script**: `./deploy.sh` (builds with Java 17, deploys JAR, restarts app)
- **JAR Name**: `xaandemo-0.0.5.jar`
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

### Recent Changes (2026-08-19)
1. Analyzed the existing encryption structure (single flat Vault key shared by `board.password` and `users.id_no`) and wrote `KEK_DEK_ENCRYPTION_PLAN.md` — a KEK-DEK envelope encryption design + phased implementation plan (P0-P5).
2. **vault-crypto (P1, v0.0.1 → v0.0.2)**: added the `com.xaan.vault.crypto.envelope` package — `AesGcmCodec` (shared AES-256-GCM byte codec), `KekService` (loads/holds the KEK, wraps/unwraps DEKs), `WrappedDek`, `DekProvider` + `VaultDekProvider` (per-domain wrapped-DEK storage in Vault KV-v2, versioned), `DomainKeyRing` (in-memory cache of unwrapped DEKs per domain), `EnvelopeCryptoService` (domain-scoped encrypt/decrypt/validate with a `domainCode+keyVersion` header), `DekRotationSupport`. Added `EnvelopeCryptoServiceTest` (4 tests, all passing). Old `VaultCryptoService` marked `@Deprecated`. Built and published to `mavenLocal`.
3. **demoApp (P2)**: added `CryptoConfig` (KEK/DEK/domain `EnvelopeCryptoService` beans). Refactored `PasswordService` to use the `board`/`user-pii` domain services. Fixed `UserService.register()` reusing `encryptBoardPassword()` for the RRN — now calls the new `encryptUserPii()`. Added KEK/DEK path properties to `application.properties`.
4. **P0 (not run against production Vault)**: wrote `bootstrap_kek_dek.py`, which generates a KEK and per-domain DEKs locally and prints the `vault kv put` commands to create them — it does not call Vault itself. Per the user's preference, production Vault changes are executed by the user, not by Claude directly.
5. **Migration abandoned, legacy compatibility removed**: the user decided to drop the `users` table entirely and ignore existing `board.password` ciphertext rather than migrate it. Deleted `EnvelopeMigrationService.java` and `EnvelopeMigrationRunner.java` (the P3 backfill code) and removed `app.migration.envelope-encryption.enabled` from `application.properties`. Removed the legacy `VaultCryptoService` bean from `CryptoConfig` and the legacy-fallback logic from `PasswordService` (`decryptWithLegacyFallback`/`validateWithLegacyFallback` and the `legacyCryptoService` field are gone) — `vault.secret.path` was also removed since nothing reads it anymore. `decryptBoardPassword`/`decryptUserPii`/`validateBoardPassword` now call the domain `EnvelopeCryptoService` directly; pre-existing ciphertext throws `CryptoException` on decrypt and returns `false` on validate instead of falling back. Updated `PasswordServiceTest` accordingly (constructor now takes only the two domain services; added a test asserting old-format ciphertext fails cleanly).
6. **`VaultCryptoService` deleted from vault-crypto (v0.0.2 → v0.0.3)**: the user decided existing projects still using the old single-key `VaultCryptoService` should be ignored, so the class was deleted outright (not just left `@Deprecated`) — vault-crypto's only remaining public API is the `envelope` package. Rewrote `vault-crypto/README.md` to document `EnvelopeCryptoService`/`KekService`/`DekProvider` usage instead, with a Release History entry for the breaking change. Rebuilt and republished to `mavenLocal`; bumped demoApp's dependency to `0.0.3` and confirmed it still builds and passes tests.
7. **P0 executed by the user**: ran `bootstrap_kek_dek.py`'s output (`vault kv put` for `kek`, `dek/board`, `dek/user-pii`) against the production Vault directly — no errors. Confirmed indirectly via a `gradle test` run: `DemoApplicationTests.contextLoads()` now fails on a Postgres connection error instead of `KeyLoadingException`, meaning the KEK/DEK now load successfully.
8. Confirmed `deploy.sh` builds the Spring Boot fat jar locally (via `bootJar`) and only `scp`s that single jar to the production server — the server itself never rebuilds, so it doesn't need its own vault-crypto publish step. Verified `BOOT-INF/lib/` inside the built jar contains the current vault-crypto jar.
9. **Version bump to 0.0.5 (both projects)**: at the user's request, bumped `vault-crypto` `0.0.3 → 0.0.5` and demoApp `0.0.4 → 0.0.5` (versions intentionally aligned; `0.0.4` skipped for vault-crypto), rebuilt both, republished vault-crypto to `mavenLocal`, and updated every file referencing the old version numbers: both `build.gradle` files, `deploy.sh`, `deploy.bat`, `README.md` (both projects, including new Release History entries: `v0.0.5` in each), and this file. Rebuilt demoApp's jar and confirmed `BOOT-INF/lib/vault-crypto-0.0.5.jar` is bundled; `PasswordServiceTest` still passes.
10. Nothing was committed to git this session; `build.gradle` (demoApp) now depends on `com.xaan:vault-crypto:0.0.5`.

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
- **KEK-DEK Envelope Encryption Plan**: `KEK_DEK_ENCRYPTION_PLAN.md` (design + phased plan; P3 data migration cancelled 2026-08-19)
- **Main Config**: `src/main/resources/application.properties`
- **Crypto Wiring**: `src/main/java/com/xaan/demo/config/CryptoConfig.java` (KEK/DEK/domain `EnvelopeCryptoService` beans only, no legacy bean)
- **Password Service**: `src/main/java/com/xaan/demo/service/PasswordService.java` (uses vault-crypto envelope services only, no legacy fallback)
- **Board Service**: `src/main/java/com/xaan/demo/service/BoardService.java`
- **Deploy Script**: `deploy.sh`
- **Python Decryption Script**: `decrypt_passwords.py` (tested 2026-05-08; note it still decodes the old single-key format, now unrelated to how the app encrypts)
- **P0 Bootstrap Script**: `bootstrap_kek_dek.py` (generates KEK/DEK and prints `vault kv put` commands; does not touch Vault itself)
- **vault-crypto Package**: `/c/Temp/ag-projects/vault-crypto/` (now at v0.0.5; envelope package added 2026-08-15, `VaultCryptoService` deleted 2026-08-19 — `envelope` is now the only public API)
