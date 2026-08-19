# demoApp - Spring Boot Application

A Spring Boot demo application with comprehensive features for board/article management.

## Features

- **Spring Boot 3.4.0** with Java 17
- **PostgreSQL** database integration
- **JPA with Hibernate** for data persistence
- **Thymeleaf** templating engine for server-side rendering
- **Board/Article management system** with CRUD operations
- **Vault configuration** for secrets management
- **Gradle build system** with wrapper
- **Spring Cloud Vault** integration
- **Password encryption service** - BCrypt (사용자 비밀번호) + `vault-crypto` (게시글/개인정보 KEK-DEK 봉투 암호화, AES-GCM)
- **Pagination support** for board listings

## Project Structure

```
src/main/java/com/xaan/demo/
├── DemoApplication.java          # Main application entry point
├── config/
│   └── VaultConfig.java          # Vault configuration
├── controller/
│   ├── BoardApiController.java   # REST API controller
│   ├── IndexController.java      # Main page controller
│   ├── Top100IndexController.java # Top 100 listings controller
│   └── UserController.java       # User registration/login controller
├── domain/
│   ├── entity/
│   │   ├── BaseTimeEntity.java   # Base entity with timestamps
│   │   ├── Board.java            # Board entity
│   │   └── User.java             # User entity
│   └── repository/
│       ├── BoardRepository.java  # Board JPA repository
│       └── UserRepository.java   # User JPA repository
├── dto/
│   ├── BoardResponseDto.java     # Response DTO
│   ├── BoardSaveRequestDto.java  # Save request DTO
│   ├── BoardUpdateRequestDto.java # Update request DTO
│   └── UserRegisterRequestDto.java # User registration DTO
└── service/
    ├── BoardService.java         # Board business logic
    ├── PasswordService.java      # Password encryption (BCrypt + vault-crypto)
    └── UserService.java          # User business logic

src/main/resources/
├── application.properties        # Application configuration
└── templates/                    # Thymeleaf templates
    ├── index.html               # Main page
    ├── list1st.html             # First page listing
    ├── list1stonly.html         # First page only listing
    └── posts/                   # Post-related templates
        ├── save.html            # Save post form
        └── update.html          # Update post form
```

## Prerequisites

- Java 17 or higher
- Gradle 8.7 or higher
- PostgreSQL database (optional - can use H2 for development)
- HashiCorp Vault (optional - for production secrets management)

## Configuration

The application uses the following configuration in `application.properties`:

```properties
spring.application.name=demo
spring.jpa.database=postgresql
spring.jpa.hibernate.ddl-auto=update
spring.jpa.show-sql=true
spring.datasource.url=jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/limadb?currentSchema=${DB_SCHEMA:ebiz}
spring.datasource.username=${DB_USERNAME:postgres}
spring.datasource.password=${DB_PASSWORD:changeme}
```

### Vault Configuration (Updated 2026-05-07)

The application now uses **HashiCorp Vault kv-v2** (versioned key-value) backend:

```properties
# Vault Configuration (connection only)
spring.cloud.vault.uri=http://192.168.2.57:8200
spring.cloud.vault.token=${VAULT_TOKEN}
spring.cloud.vault.fail-fast=false
```

### Vault kv-v2 Integration (KEK-DEK envelope encryption, since 2026-08-19)
- **Mount**: `ebiz_service` (kv-v2)
- **KEK path**: `ebiz_db/kek` — master key, only used to wrap/unwrap DEKs
- **DEK paths**: `ebiz_db/dek/board`, `ebiz_db/dek/user-pii` — one wrapped DEK per service domain, versioned
- **Server**: 192.168.2.57:8200
- See [KEK_DEK_ENCRYPTION_PLAN.md](KEK_DEK_ENCRYPTION_PLAN.md) for the full design and `bootstrap_kek_dek.py` for how these secrets are generated

### How It Works
1. **vault-crypto package** (`com.xaan:vault-crypto:0.0.5`) provides KEK-DEK envelope encryption
2. `CryptoConfig` builds one `EnvelopeCryptoService` per domain (`board`, `user-pii`); each unwraps its DEK from Vault once at startup and caches it in memory
3. `PasswordService` delegates to the domain-specific `EnvelopeCryptoService` for encrypt/decrypt/validate — no legacy fallback, no Vault call per request
4. Passwords/PII stored as Base64-encoded encrypted strings (with a `domainCode`+`keyVersion` header) in DB
5. Python decryption script (`decrypt_passwords.py`) only understands the old single-key format now, unrelated to how the app currently encrypts

### vault-crypto Package
Encryption functionality is extracted into a separate package:
- **Repository**: `vault-crypto/` (별도 프로젝트)
- **Build**: `gradle.bat clean build publishToMavenLocal` (Windows) 또는 `./gradlew clean build publishToMavenLocal` (Linux)
- **Usage**: See `vault-crypto/README.md` for details

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

> **Note**: Vault secret path `ebiz_service/data/ebiz_db/data-enc-key` needs to be configured. 
> See [VAULT_AND_ENCRYPTION.md](VAULT_AND_ENCRYPTION.md) for implementation details.

## Building the Project

> **⚠️ Build Environment Notes:**
> - **Windows**: `JAVA_HOME=C:\SW\jdk-17.0.15`, `GRADLE_HOME=C:\SW\gradle-8.14.5\bin`
> - **Linux**: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64`, `GRADLE_HOME=/opt/gradle/gradle-8.7`
> - Always set JAVA_HOME before building, or use the provided scripts.

### Windows (Recommended):
```bat
set JAVA_HOME=C:\SW\jdk-17.0.15
set PATH=%JAVA_HOME%\bin;C:\SW\gradle-8.14.5\bin;%PATH%

REM Build (deploy.bat handles build + deploy)
deploy.bat

REM Or build only
gradle.bat clean build
```

### Linux:
```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export PATH=$JAVA_HOME/bin:/opt/gradle/gradle-8.7/bin:$PATH

./gradlew clean build
```

## Running the Application

1. **Build the JAR:**
   ```bash
   export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
   export PATH=$JAVA_HOME/bin:$PATH
   ./gradlew clean build
   ```

2. **Run the JAR:**
   ```bash
   java -jar build/libs/xaandemo-0.0.5.jar
   ```

3. **Access the application:**
   - Main page: http://localhost:8080
   - API endpoints: http://localhost:8080/api/v1/posts
   - Last 100: http://localhost:8080/last100

## API Endpoints

### Board Management (REST API)
- `GET /api/v1/posts` - Get all posts (405 for GET, use POST to create)
- `GET /api/v1/posts/{id}` - Get post by ID
- `POST /api/v1/posts` - Create new post (with optional password)
- `PUT /api/v1/posts/{id}` - Update post (with optional password)
- `DELETE /api/v1/posts/{id}` - Delete post

### Web Pages
- `GET /` - Main page (index)
- `GET /posts/save` - Post creation form
- `GET /posts/update/{id}` - Post update form
- `GET /last100` - Last 100 posts listing
- `GET /list1st` - First page listing
- `GET /list1stonly` - First page only listing

### Pagination
- `GET /api/boards/page` - Get paginated boards (first page)
- `GET /api/boards/page/first-only` - Get first page only

## Database Schema

The application uses the following main entity in schema `ebiz`:

```sql
CREATE TABLE ebiz.board (
    id BIGSERIAL PRIMARY KEY,
    title VARCHAR(500) NOT NULL,
    content TEXT NOT NULL,
    author VARCHAR(100),
    password VARCHAR(255), -- AES encrypted (Base64 encoded)
    created_date TIMESTAMP,
    modified_date TIMESTAMP
);
```

> **Password Storage**: Passwords are encrypted using `vault-crypto` package (AES-256/GCM/NoPadding, authenticated encryption).
> See [VAULT_AND_ENCRYPTION.md](VAULT_AND_ENCRYPTION.md) for implementation details.
> Python decryption script (`decrypt_passwords.py`) available for verification.

## Security Features

1. **Password Encryption**:
   - **사용자 비밀번호**: BCrypt 단방향 해시 (`spring-security-crypto`)
   - **게시글 비밀번호 / 개인정보**: AES-256 GCM KEK-DEK 봉투 암호화 (`vault-crypto`), `board`/`user-pii` 도메인별로 독립된 DEK 사용
   - Implementation: `PasswordService.java` uses domain-scoped `EnvelopeCryptoService` (via `CryptoConfig`) + `BCryptPasswordEncoder`
   - DEK는 Vault의 KEK로 wrap되어 저장되고, 앱 기동 시 1회 unwrap되어 메모리에 캐시됨 (요청 시점엔 Vault 호출 없음)
   - Package: `com.xaan:vault-crypto:0.0.5`
   - See [KEK_DEK_ENCRYPTION_PLAN.md](KEK_DEK_ENCRYPTION_PLAN.md) for details
   - ✅ **P0 완료** (2026-08-19): 운영 Vault에 KEK/DEK 시크릿 생성 완료, 앱이 정상적으로 키를 로드함을 확인

2. **Vault Integration**: External secrets management with Spring Cloud Vault
   - Configured to connect to Vault server at `http://192.168.2.57:8200`
   - Fail-fast enabled for production safety
3. **Input Validation**: Server-side validation (주민등록번호 체크섬 검증 포함)
4. **SQL Injection Protection**: Using JPA prepared statements

## Deployment

### Using deploy.bat (Windows - Recommended)
The project includes a deployment script for production server (192.168.2.57):

```bat
deploy.bat
```

This script will:
1. Build the application with Java 17
2. Distribute the JAR to production server via SCP
3. Stop the running application
4. Start the new version
5. Wait for readiness and verify the deployment

### Using deploy.sh (Linux)
```bash
./deploy.sh
```

### Docker (Example)
```dockerfile
FROM openjdk:17-jdk-slim
COPY build/libs/xaandemo-0.0.5.jar app.jar
ENTRYPOINT ["java", "-jar", "/app.jar"]
```

### Traditional Deployment
1. Build the JAR: `gradle.bat clean build` (Windows) 또는 `./gradlew clean build` (Linux)
2. Copy JAR to server: `scp build/libs/xaandemo-0.0.5.jar user@server:/app/`
3. Run with: `java -jar xaandemo-0.0.5.jar`

### Production Server Details
- **Host**: 192.168.2.57
- **User**: xaan
- **App Directory**: `/home/xaan/ws/demoBBS/app`
- **Application URL**: http://192.168.2.57:8080

## Development

### Code Style
- Follows Spring Boot conventions
- Uses Lombok for boilerplate reduction
- JPA auditing with `@EnableJpaAuditing`
- DTO pattern for API requests/responses

### Testing
```bash
# Run all tests
./gradlew test

# Run specific test class
./gradlew test --tests "*DemoApplicationTests"
```

## Release History

### v0.0.5 (2026-08-19)

**KEK-DEK Envelope Encryption** - `vault-crypto`의 단일 평문 키 방식을 Vault KEK가 도메인별 DEK를 wrap하는 봉투 암호화 모델로 전환.

**Changes:**
- `vault-crypto`에 `envelope` 패키지 추가: `KekService`, `DekProvider`/`VaultDekProvider`, `DomainKeyRing`, `EnvelopeCryptoService`, `DekRotationSupport` — 도메인(`board`, `user-pii`)별로 독립된 DEK를 앱 기동 시 1회 unwrap해 메모리에 캐시, 이후 암/복호화는 Vault 호출 없이 로컬에서 수행
- 암호문 포맷에 `domainCode`+`keyVersion` 헤더 추가 (도메인 격리 + 향후 키 로테이션 지원)
- `CryptoConfig` 신설, `PasswordService`/`UserService`를 도메인별 서비스로 전환. `UserService.register()`가 RRN 암호화에 `encryptBoardPassword()`를 재사용하던 네이밍 문제를 `encryptUserPii()`로 정리
- **기존 암호화 데이터 마이그레이션 포기**: `users` 테이블은 전체 삭제 예정, `board.password`의 기존 암호화 데이터는 무시하기로 결정 — 레거시 복호화 폴백과 백필 마이그레이션 코드를 모두 제거
- **`VaultCryptoService`(단일 키 방식) 완전 삭제**: 이를 사용하던 기존 소비 프로젝트는 무시하기로 결정하고 vault-crypto에서 클래스 자체를 제거(breaking change)
- P0(Vault에 KEK/DEK 시크릿 생성) 완료 확인 — 앱이 Vault에서 KEK/DEK를 정상적으로 로드함
- `vault-crypto` `0.0.1 → 0.0.5`, demoApp `0.0.4 → 0.0.5`로 버전 정렬
- 상세 설계/이력: [KEK_DEK_ENCRYPTION_PLAN.md](KEK_DEK_ENCRYPTION_PLAN.md), `vault-crypto/README.md`

**Dependencies:**
```groovy
implementation 'com.xaan:vault-crypto:0.0.5'          // KEK-DEK 봉투 암호화
implementation 'org.springframework.security:spring-security-crypto' // BCrypt
implementation 'org.springframework.boot:spring-boot-starter-data-redis'
```

### v0.0.4 (2026-05-18)

**vault-crypto Re-integration & BCrypt Migration** - 암호화 전략 이원화 및 vault-crypto 라이브러리 재통합.

**Changes:**
- `PasswordService.java` 리팩토링: 인라인 AES-GCM 코드(~120줄) 제거 → `vault-crypto` 라이브러리 위임 (75줄)
- 사용자 비밀번호: BCrypt 단방향 해시 (`spring-security-crypto`)
- 게시글 비밀번호 / 주민등록번호: AES-256 GCM 양방향 암호화 (`vault-crypto`)
- `PasswordServiceTest.java`: VaultOperations mock 방식으로 테스트 개선
- 빌드/배포 환경을 Windows로 전환 (`deploy.bat`)
- Redis 캐싱 통합 (`spring-boot-starter-data-redis`)
- Connection pool size 30으로 설정
- ✅ Production tested (2026-05-18): Post 2064044 암호화/복호화 검증 완료

**Dependencies:**
```groovy
implementation 'com.xaan:vault-crypto:0.0.1'         // AES-GCM 암호화
implementation 'org.springframework.security:spring-security-crypto' // BCrypt
implementation 'org.springframework.boot:spring-boot-starter-data-redis'
```

### v0.0.3 (2026-05-08)

**Security & Refactoring Release** - Password encryption service refactored to use `vault-crypto` package.

**Security Improvements:**
- Password encryption implemented using `vault-crypto` package (AES-256 GCM, NoPadding)
- Encryption key loaded from HashiCorp Vault kv-v2 backend at startup
- Old plain-text passwords migrated to encrypted format
- Python decryption script (`decrypt_passwords.py`) for verification
- ✅ Production tested (2026-05-08) with post ID 2017588

**Architecture:**
- `PasswordService.java` refactored to delegate to `VaultCryptoService`
- `vault-crypto:0.0.1` dependency introduced
- `PasswordService` now uses `@Service` annotation with `@Autowired VaultOperations`
- Vault connection configured with fail-fast disabled

**Bug Fixes:**
- Fixed Vault kv-v2 integration with proper Fernet key field (`fernet-key`)
- Fixed Vault key name: `fernet-key` instead of `data-enc-key`

**Documentation:**
- `VAULT_AND_ENCRYPTION.md` - Full implementation details
- `VAULT_INTEGRATION_DIAGRAM.md` - System architecture diagram
- Python decryption script for password verification

### v0.0.2 (2026-05-06)

**Vault Integration Release** - Initial integration with HashiCorp Vault.

### v0.0.1 (2026-05-06)

**Initial release** - Spring Boot demo application with board/article management.

This project is available for use under the MIT License.

## Contributing

1. Fork the repository
2. Create a feature branch
3. Commit your changes
4. Push to the branch
5. Create a Pull Request

## Support

For issues and feature requests, please use the GitHub Issues page.