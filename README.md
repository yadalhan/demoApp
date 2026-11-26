# demoApp - Spring Boot Application

A Spring Boot demo application with comprehensive features for board/article management.

## Features

- **Spring Boot 3.4.0** with Java 21
- **PostgreSQL** database integration
- **MyBatis** for data persistence (XML-mapped mappers, SQL fully separated from Java, no JPA/Hibernate)
- **Thymeleaf** templating engine for server-side rendering
- **Board/Article management system** with CRUD operations
- **User directory with search** - name (부분 일치), phone/RRN (정확 일치 via blind index)
- **Vault configuration** for secrets management
- **Gradle build system** with wrapper
- **Spring Cloud Vault** integration
- **Password/PII encryption** - all delegated to `vault-crypto`, applied transparently via MyBatis `TypeHandler`s so `BoardService`/`UserService` never call crypto directly: BCrypt (로그인 비밀번호), KEK-DEK 봉투 암호화 (게시글 비밀번호/주민등록번호/전화번호, AES-GCM), blind index (전화번호/주민등록번호 검색용 HMAC)
- **Pagination support** for board listings

## Project Structure

```
src/main/java/com/xaan/demo/
├── DemoApplication.java          # Main application entry point
├── config/
│   ├── CryptoConfig.java         # vault-crypto beans: KEK/DEK/EnvelopeCryptoService, BlindIndexService
│   └── mybatis/
│       ├── BoardPasswordTypeHandler.java  # board.password 암호화 (쓰기 전용, 레거시 데이터 때문)
│       └── UserPiiTypeHandler.java        # users.id_no/phone 암/복호화 (읽기+쓰기)
├── controller/
│   ├── AuthController.java       # Login/register/logout
│   ├── BoardApiController.java   # REST API controller
│   ├── IndexController.java      # Main page controller
│   ├── Top100IndexController.java # Top 100 listings controller
│   └── UserAdminController.java  # 사용자 목록/검색 페이지 (/users 실시간, /users2 Redis 캐시)
├── domain/
│   ├── entity/
│   │   ├── Board.java            # Board entity
│   │   └── User.java             # User entity (phone, blind index 필드 포함)
│   └── mapper/                   # MyBatis mapper 인터페이스 (SQL 없음)
│       ├── BoardMapper.java
│       ├── UserMapper.java
│       ├── StatsMapper.java
│       └── TestMapper.java
├── dto/
│   ├── BoardResponseDto.java, BoardSaveRequestDto.java, BoardUpdateRequestDto.java
│   ├── UserRegisterRequestDto.java, UserResponseDto.java
└── service/
    ├── BoardService.java         # Board business logic (crypto는 TypeHandler에 위임)
    ├── PasswordService.java      # BCrypt 해시 + blind index 계산 (vault-crypto 위임)
    ├── UserService.java          # User business logic + 검색
    └── UserSearchCacheService.java # /users2용 @Cacheable 전담 빈 (self-invocation 회피 목적으로 분리)

src/main/resources/
├── mapper/                       # MyBatis XML (SQL 전부 여기)
│   ├── BoardMapper.xml
│   ├── UserMapper.xml
│   ├── StatsMapper.xml
│   └── TestMapper.xml
├── sql/test-queries.sql          # 성능 테스트 페이지용 쿼리 목록
├── application.properties        # Application configuration
└── templates/                    # Thymeleaf templates
    ├── auth/                    # 로그인/회원가입
    ├── last100.html, list1st.html, list1stonly.html  # 게시글 목록
    ├── users/list.html          # 사용자 목록/검색 (실시간 조회)
    ├── users/list2.html         # 사용자 목록2 (Redis 캐시, /users와 동일한 화면)
    └── posts/                   # Post-related templates

migrations/
└── 001_add_user_phone_and_blind_index.sql  # users.phone/phone_blind_idx/id_no_blind_idx 컬럼 추가

bootstrap_blind_index_keys.py     # blind index HMAC 키 생성 helper (bootstrap_kek_dek.py와 동일한 패턴)
import_sample_data.py             # 샘플 JSON(board/users) 일괄 import - 평문 PII를 봉투 암호화 + blind index 계산해 적재
openbao_keys.sh                   # OpenBao 키 세트 생성/이전/검증 (POSIX sh + bao CLI, python·jq 불필요)
```

## Prerequisites

- Java 21 or higher
- Gradle 8.7 or higher
- PostgreSQL database (optional - can use H2 for development)
- OpenBao 2.6.2 (or a Vault-compatible KMS) - for production secrets management

## Configuration

The application uses the following configuration in `application.properties`:

```properties
spring.application.name=demo
spring.datasource.url=jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/limadb?currentSchema=${DB_SCHEMA:ebiz}
spring.datasource.username=${DB_USERNAME:postgres}
spring.datasource.password=${DB_PASSWORD:changeme}

# Required - without this, any snake_case DB column with no explicit @Result mapping in a
# mapper (user_id, created_date, modified_date, ...) silently comes back null instead of
# mapping to its camelCase Java property. No error, just a blank field - found the hard way
# via a blank "사용자 ID" column on /users and a blank "최종수정일" on the board lists.
mybatis.configuration.map-underscore-to-camel-case=true
```

### KMS Configuration — OpenBao (Updated 2026-11-08)

The application uses **OpenBao 2.6.2** (Vault-compatible kv-v2) as its KMS:

```properties
# KMS configuration (connection only; OpenBao is API-compatible with Vault)
spring.cloud.vault.uri=http://192.168.2.57:28200
spring.cloud.vault.token=${VAULT_TOKEN}
spring.cloud.vault.fail-fast=false
```

> **Startup dependency**: the KEK/DEK/blind-index beans are eager and read the KMS while the context
> is starting, so a sealed/unreachable OpenBao (or a missing secret) fails startup with
> `KeyLoadingException` even though `fail-fast=false`. An already-running process is unaffected
> because DEKs are cached in memory — check OpenBao seal status before restarting/redeploying.

### KMS (OpenBao) Integration — Transit KEK + wrapped DEK (2026-11-13~)

KEK는 **OpenBao Transit 엔진**(`transit/keys/kek`)에 있고 앱으로 내려오지 않는다. 앱은 KV의 wrapped DEK를
`transit/decrypt`로 풀어 DEK만 메모리에 캐시한다(vault-crypto 2.2.0의 `TransitKeyWrapper`).
KEK를 KV에 두던 이전 방식은 레거시이며, 이관 스크립트(`migrate_dek_to_transit.py`)로 데이터 재암호화 없이 전환했다.

- **KEK**: `transit/keys/kek` (aes256-gcm96, exportable=false)
- **Wrapped DEK**: `ebiz_service/data/ebiz_db/dek/{board,user-pii}` (값이 `vault:v1:...`)
- **Blind index**: `ebiz_service/data/ebiz_db/blind-index/{user-phone,user-rrn}`
- 앱 정책: `dek/*`, `blind-index/*` read + `transit/decrypt/kek` (KEK 경로 read 없음)

이관/롤백:

```bash
# 이관 전 값은 600 권한의 백업 파일로 남는다(기본 ./dek_transit_backup.json)
BAO_TOKEN=<admin> python3 migrate_dek_to_transit.py --backup ./dek_transit_backup.json

# 롤백이 필요하면 백업의 dek-v{n} 값을 그대로 다시 kv put 한다(앱 0.0.20 + KV KEK 필요)

> 2026-11-13: 이관 검증 후 KV의 `kek` 시크릿을 삭제했다(앱 토큰은 원래부터 403). 이 시점부터 KV KEK 방식으로의
> 롤백은 불가능하며, 복구 수단은 OpenBao **Raft 스냅샷 + unseal 키**뿐이다.
```

### (레거시) KV KEK 방식 — since 2026-08-19
- **Mount**: `ebiz_service` (kv-v2)
- **KEK path**: `ebiz_db/kek` — master key, only used to wrap/unwrap DEKs
- **DEK paths**: `ebiz_db/dek/board`, `ebiz_db/dek/user-pii` — one wrapped DEK per service domain, versioned
- **Blind index paths**: `ebiz_db/blind-index/user-phone`, `ebiz_db/blind-index/user-rrn` — one HMAC key per searchable field, unversioned (see `vault-crypto/README.md`의 "4. Blind Index")
- **Server**: `http://192.168.2.57:28200` (OpenBao 2.6.2; the previous Vault at `:8200` is stopped)
- See [KEK_DEK_ENCRYPTION_PLAN.md](KEK_DEK_ENCRYPTION_PLAN.md) for the full design, `bootstrap_kek_dek.py` for KEK/DEK secrets, and `bootstrap_blind_index_keys.py` for blind index secrets — both scripts only print `vault kv put` commands, they don't touch the KMS themselves

### How It Works
1. **vault-crypto package** (`com.xaan:vault-crypto:2.2.0`) provides KEK-DEK envelope encryption, BCrypt password hashing (`PasswordHasher`), blind index search (`BlindIndexService`), and a MyBatis `TypeHandler` base class (`EnvelopeCryptoTypeHandler`) - all password/PII-related crypto lives in the library, not in demoApp
2. `CryptoConfig` builds one `EnvelopeCryptoService` per domain (`board`, `user-pii`) and one `BlindIndexService` per searchable field (`user-phone`, `user-rrn`); each loads its key from OpenBao once at startup and caches it in memory
3. Encryption is applied via MyBatis `TypeHandler`s registered as Spring beans (`config/mybatis/BoardPasswordTypeHandler`, `UserPiiTypeHandler`) and referenced explicitly per column in `BoardMapper`/`UserMapper`'s SQL - `BoardService`/`UserService` pass and receive plain Java strings, never touching `EnvelopeCryptoService` directly. `board.password` is only wired write-side (encrypt on insert/update) because legacy rows predated the envelope format and would break ordinary list/view reads if decrypted on every `SELECT` (0 such rows remain as of 2026-11-08, but the write-only wiring is retained); `users.id_no`/`phone` are wired both ways since that table has no legacy data
4. `PasswordService` keeps only what's left for the service layer to call explicitly: BCrypt hash/validate, board-password `validate()` (needs the plaintext input compared against stored ciphertext, not just a blind write/read), and blind index computation for search
5. Passwords/PII stored as Base64-encoded encrypted strings (with a `domainCode`+`keyVersion` header) in DB; phone/RRN additionally get a deterministic HMAC in a companion `*_blind_idx` column for exact-match search
6. Python verification script (`decrypt_passwords.py`) supports the current envelope format (loads
   KEK/DEK from Vault and unwraps them) as well as classifying legacy/BCrypt values
   (`python3 decrypt_passwords.py --self-test` runs the crypto checks without Vault/DB)

### vault-crypto Package
Encryption functionality is extracted into a separate package:
- **Repository**: `vault-crypto/` (별도 프로젝트)
- **Build**: `gradle.bat clean build publishToMavenLocal` (Windows) 또는 `./gradlew clean build publishToMavenLocal` (Linux)
- **Usage**: See `vault-crypto/README.md` for details
- **Build order**: demoApp depends on the published `com.xaan:vault-crypto:2.2.0`, so publish the
  library first (`publishToMavenLocal`); otherwise dependency resolution fails when `~/.m2` only has
  an older version

### Setting up Vault Secrets
Both bootstrap scripts **only print** ready-to-run `vault kv put` commands — they never connect to
Vault, so the operator reviews and runs them.

```bash
# Enable kv-v2 backend (if not already enabled)
vault secrets enable -path=ebiz_service kv-v2

# 1) KEK + per-domain wrapped DEKs (kek-v1 / dek-v1 / current-version)
python3 bootstrap_kek_dek.py

# 2) Blind-index HMAC keys (user-phone, user-rrn)
python3 bootstrap_blind_index_keys.py

# Verify
vault kv get -mount=ebiz_service ebiz_db/kek
vault kv get -mount=ebiz_service ebiz_db/dek/board
vault kv get -mount=ebiz_service ebiz_db/dek/user-pii
vault kv get -mount=ebiz_service ebiz_db/blind-index/user-phone
vault kv get -mount=ebiz_service ebiz_db/blind-index/user-rrn
```

> The legacy single-key secret `ebiz_db/data-enc-key` (`fernet-key`) is **no longer read** by the
> application. See [VAULT_AND_ENCRYPTION.md](VAULT_AND_ENCRYPTION.md) for the current architecture.

### Importing sample data (`import_sample_data.py`)

JSON 백업(`board.json`, `users.json`)을 **암호화 대상 컬럼만 암호화해서** DB에 적재하는 배치 스크립트다.
JSON의 `board.password`, `users.id_no`/`phone`은 평문이므로, 스크립트가 OpenBao에서 DEK·blind-index 키를
읽어 앱과 동일한 형식으로 변환한다(`users.password`가 이미 BCrypt면 그대로 사용).

```bash
# 미리보기 (DB에 쓰지 않음)
./import_sample_data.py --board board.json --users users.json --dry-run

# 적재 (테이블이 비어 있을 때만) / 기존 데이터 유지하며 추가 / 초기화 후 적재
./import_sample_data.py --board board.json --users users.json
./import_sample_data.py --board board.json --users users.json --append
./import_sample_data.py --board board.json --users users.json --truncate
```

- 키·접속 정보는 `VAULT_TOKEN`/`BAO_TOKEN`, `PGPASSWORD` 또는 `~/.myapp/token`, `~/.myapp/dbpw`에서 읽는다
  (`--vault-token-file`, `--db-password-file`로 경로 지정 가능)
- 봉투 암호화 포맷은 `Base64URL(domainCode|keyVersion|IV(12)|AES-256-GCM ct+tag)`, blind index는
  HMAC-SHA256 Base64URL(43자) — 앱(`EnvelopeCryptoService`/`BlindIndexService`)과 바이트 단위로 동일
- `psql \copy`(CSV)로 **한 트랜잭션**에 적재하고, 완료 후 identity 시퀀스를 `max(id)`로 맞춘다
- 기존 행과 `id`/`user_id`가 겹치면 `--append` 모드에서 해당 샘플만 건너뛴다

## Local Development without Vault (개인 PC)

Vault/OpenBao 없이 로컬에서 앱을 띄우려면 `local` 프로필을 사용합니다. 키는 파일(`.local-crypto/keys.json`)로
대체되고, Spring Cloud Vault 자동 구성은 꺼집니다(그래서 vault-crypto 자동 구성도 물러나고
`LocalCryptoConfig`가 도메인 서비스를 등록합니다).

```bash
# 1) 로컬 DB 준비 (예: 같은 서버의 별도 database) — 운영 DB를 쓰지 마세요
createdb demoapp_local && psql -d demoapp_local -c 'create schema ebiz'
psql -d demoapp_local -f migrations/002_local_schema.sql   # board/users DDL (아래 참고)

# 2) 설정 파일 준비 (gitignore 대상)
mkdir -p local-dev
cp src/main/resources/application-local.properties.example local-dev/application-local.properties
#   DB 접속 정보와 local.crypto.keys-file 경로를 채웁니다

# 3) 실행
./gradlew bootJar -x test
java -jar build/libs/xaandemo-0.0.25.jar \
     --spring.profiles.active=local \
     --spring.config.additional-location=file:./local-dev/ \
     --server.port=18080
```

- `.local-crypto/keys.json`은 첫 기동 시 자동 생성됩니다(32바이트 키 4개). **커밋 금지**, 삭제하면 기존 암호문을
  복호화할 수 없습니다.
- 이 키로 만든 데이터는 운영(Vault 기반) 환경에서 복호화되지 않고 그 반대도 마찬가지입니다. 반드시 **별도 로컬 DB**를 사용하세요.
- Redis가 없으면 `spring.cache.type=none`으로 캐시가 no-op이 되고, `/users2`도 정상 동작합니다.
- `local` 프로필은 절대 배포 환경에서 사용하지 마세요(KMS를 우회합니다).

## Building the Project

> **⚠️ Build Environment Notes:**
> - **Windows**: `JAVA_HOME=C:\SW\jdk-21.0.8`, `GRADLE_HOME=C:\SW\gradle-8.14.5\bin`
> - **Linux**: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64`, `GRADLE_HOME=/opt/gradle/gradle-8.7`
> - Always set JAVA_HOME before building, or use the provided scripts.

### Windows (Recommended):
```bat
set JAVA_HOME=C:\SW\jdk-21.0.8
set PATH=%JAVA_HOME%\bin;C:\SW\gradle-8.14.5\bin;%PATH%

REM Build (deploy.bat handles build + deploy)
deploy.bat

REM Or build only
gradle.bat clean build
```

### Linux:
```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH=$JAVA_HOME/bin:/opt/gradle/gradle-8.7/bin:$PATH

./gradlew clean build
```

## Running the Application

1. **Build the JAR:**
   ```bash
   export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
   export PATH=$JAVA_HOME/bin:$PATH
   ./gradlew clean build
   ```

2. **Run the JAR:**
   ```bash
   java -jar build/libs/xaandemo-0.0.21.jar
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

1. **Password/PII Encryption**:
   - **로그인 비밀번호**: BCrypt 단방향 해시 (`vault-crypto`의 `PasswordHasher`)
   - **게시글 비밀번호 / 주민등록번호 / 전화번호**: AES-256 GCM KEK-DEK 봉투 암호화 (`vault-crypto`의 `EnvelopeCryptoService`), `board`/`user-pii` 도메인별로 독립된 DEK 사용
   - Implementation: 암/복호화는 MyBatis `TypeHandler`(`config/mybatis/BoardPasswordTypeHandler`, `UserPiiTypeHandler`)가 Mapper 컬럼 단위로 투명하게 처리 - `BoardService`/`UserService`는 평문만 다루고 `vault-crypto`를 직접 호출하지 않음. `PasswordService.java`는 BCrypt 해시/검증, board 비밀번호 `validate()`, blind index 계산만 남아 있음
   - DEK는 Vault의 KEK로 wrap되어 저장되고, 앱 기동 시 1회 unwrap되어 메모리에 캐시됨 (요청 시점엔 Vault 호출 없음). BCrypt는 외부 키가 필요 없어 Vault와 무관
   - Package: `com.xaan:vault-crypto:2.2.0`
   - See [KEK_DEK_ENCRYPTION_PLAN.md](KEK_DEK_ENCRYPTION_PLAN.md) for details
   - ✅ **P0 완료** (2026-08-19): 운영 Vault에 KEK/DEK 시크릿 생성 완료, 앱이 정상적으로 키를 로드함을 확인
   - 암호화 컬럼을 다루는 조회/조회 코드를 새로 짤 때는 [ENCRYPTED_COLUMN_QUERY_GUIDE.md](ENCRYPTED_COLUMN_QUERY_GUIDE.md) 참고 - 단건/목록/검색/캐싱 상황별 패턴과, 실제로 겪었던 실수(이중 암호화, 캐시에 평문 노출, self-invocation 등) 정리
   - vault-crypto로 DB 컬럼 암호화를 처음부터 구축할 때는 [vault-crypto 개발자 가이드](../vault-crypto/devguide/README.md) 참고 - 의존성/KMS(Transit KEK)/설정/스키마 준비부터 CRUD, 양방향 컬럼의 Blind Index 검색, 단방향 컬럼 조회, Redis 캐싱, 테스트 작성법, SQL XML 분리까지 이 저장소(demoApp)의 users 테이블 예제로 전체 정리(Word 8종 + PPT 6종, vault-crypto 저장소에 있음). 2.0.x 시점 단일 파일 가이드는 `vault-crypto/docs/legacy/`에 보관

2. **암호화된 컬럼 검색 (Blind Index)**: 전화번호/주민등록번호는 AES-GCM이라 등호 검색이 불가능하므로, HMAC-SHA256 기반 결정적 인덱스(`phone_blind_idx`/`id_no_blind_idx`)를 별도 컬럼에 저장 - `/users` 검색이 이 컬럼을 조회한다. 정확히 일치하는 값만 찾을 수 있고(부분 검색 불가), DEK/KEK와 무관한 별도 키를 씀(`vault-crypto`의 `BlindIndexService`)

3. **KMS Integration**: External secrets management with Spring Cloud Vault
   - Connected to OpenBao 2.6.2 at `http://192.168.2.57:28200` (Vault-compatible API)
   - Scoped `demoapp-read` token; fail-fast enabled for production safety
4. **Input Validation**: Server-side validation (주민등록번호 체크섬 검증, 전화번호 숫자만 허용 포함)
5. **SQL Injection Protection**: Using MyBatis prepared statement bind parameters (`#{...}`)

## Deployment

### Using deploy.bat (Windows - Recommended)
The project includes a deployment script for production server (192.168.2.56):

```bat
deploy.bat
```

This script will:
1. Build the application with Java 21
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
FROM openjdk:21-jdk-slim
COPY build/libs/xaandemo-0.0.21.jar app.jar
ENTRYPOINT ["java", "-jar", "/app.jar"]
```

### Traditional Deployment
1. Build the JAR: `gradle.bat clean build` (Windows) 또는 `./gradlew clean build` (Linux)
2. Copy JAR to server: `scp build/libs/xaandemo-0.0.21.jar user@server:/app/`
3. Run with: `java -jar xaandemo-0.0.21.jar`

### Production Server Details
- **Host**: 192.168.2.56
- **User**: xaan
- **App Directory**: `/home/xaan/ws/demoBBS/app`
- **Application URL**: http://192.168.2.56:8080

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

### 0.0.25 (2026-11-22)

**vault-crypto 2.3.0 자동 구성 도입 — 앱에서 `CryptoConfig`·`VaultHealthIndicator` 삭제.**

- `CryptoConfig.java` 제거: 도메인/블라인드 인덱스 빈을 `vault.crypto.*` 설정으로 대체
  (`boardCryptoService`, `userPiiCryptoService`, `phoneBlindIndexService`, `rrnBlindIndexService` 이름 유지)
- `VaultHealthIndicator.java` 제거: vault-crypto의 `vaultToken` 지표(`auth/token/lookup-self`) 사용
- `build.gradle`: `com.xaan:vault-crypto:2.2.0` → `2.3.0`
- 검증(myapp, `namespace=serice_a`): health **UP(200)**, `vaultToken: UP {ttlSeconds, policies, namespace: serice_a/}`,
  게시글 복호화 200, 회원가입/로그인/`/users` 200, 신규 행 `domain=2/ver=1`, 로그 WARN/ERROR 0

### 0.0.24 (2026-11-22)

**Vault health indicator가 네임스페이스에서도 동작하도록 수정 (health DOWN/503 해소).**

기존 `com.xaan.demo.config.VaultHealthIndicator`는 `sys/health`를 호출했다. 이 엔드포인트는 노드 수준이라
네임스페이스 헤더와 함께 호출하면 `400 operation unavailable in namespaces`가 되고, 그 결과
`/actuator/health`가 **DOWN(503)** 으로 표시됐다(암호화는 정상). `auth/token/lookup-self` 기반으로 교체했다.

- 프로브 변경: `sys/health` → `auth/token/lookup-self` (네임스페이스 범위, 읽기 전용, 토큰 자체를 검증)
- 상태 규칙: 토큰 무효/만료/네임스페이스 불일치 → **DOWN**, `ttl <= vault.health.token-warn-ttl`(기본 6h) → **OUT_OF_SERVICE**,
  그 외 **UP**(상세: `ttlSeconds`, `policies`, `renewable`, `namespace`)
- `vault.health.enabled=false`로 지표를 끌 수 있음(`@ConditionalOnProperty`) — 배포 없이 비활성화 가능
- `management.health.vault.enabled=false`(Spring Cloud Vault 기본 지표)는 이 커스텀 컴포넌트에는 적용되지 않음
- 검증: `namespace=serice_a` myapp에서 health **UP(200)**, `vault: UP {ttlSeconds, policies:[default, myapp-app-read], namespace: serice_a/}`,
  지표 비활성(`--vault.health.enabled=false`) 시 컴포넌트 제거, `--vault.health.token-warn-ttl=1000h` 시 **OUT_OF_SERVICE(503)** 전이 확인

### 0.0.23 (2026-11-19)

**vault-crypto 2.2.0 적용(OpenBao 네임스페이스 지원 릴리스).** 애플리케이션 코드/설정 변경은 없고 의존성만 올렸다.
네임스페이스를 쓰는 환경에서는 `spring.cloud.vault.namespace=<NS>` 한 줄로 KV/Transit 호출이 그 네임스페이스로 향한다.

- `com.xaan:vault-crypto:2.1.0` → `2.2.0` (기존 API/암호문 포맷 호환)
- `build.gradle`의 `jar/bootJar archiveVersion = '0.0.22'` 고정값 제거(버전 변경 시 산출물 이름이 따라가도록)
- `deploy.bat`의 `JAR_VERSION`이 0.0.20으로 남아 있던 것 수정
- `.56`/`.57` 두 인스턴스에 배포: JAR `xaandemo-0.0.23.jar`(vault-crypto 2.2.0 번들), health UP, `/last100` 200

### 0.0.22 (2026-11-15)

**MyBatis SQL을 Java 코드에서 완전히 분리해 XML로 이관.**

- `domain/mapper/*.java`는 인터페이스만 남기고 `@Insert`/`@Select`/`@Update`/`@Results`를 제거
- `src/main/resources/mapper/{Board,User,Stats,Test}Mapper.xml` 추가
  - `BoardMapper`: insert(useGeneratedKeys), 조회, title/content 수정, password 교체, raw 저장, 요약 집계
  - `UserMapper`: insert(TypeHandler), 업무 조회(resultMap+TypeHandler), 로그인 전용 조회, exists, 동적 검색(`<where>`/`<if>`), 재암호화 배치용 raw
  - `StatsMapper`: `/summary` 집계 (기존 JdbcTemplate 문자열 제거)
  - `TestMapper`: `/test/slowpage`의 느린 쿼리
- 성능 테스트용 일반 쿼리 목록은 `src/main/resources/sql/test-queries.sql`로 이동(Java에 SQL 문자열 없음)
- 서버 설정에 `mybatis.mapper-locations=classpath:mapper/*.xml` 추가
- 동작 변경 없음. .56/.57 배포 후 health, 가입/로그인, 사용자 검색, 게시글 생성/비밀번호 확인, `/summary`, `/test/slowpage` 확인

### v0.0.20 (2026-11-08)

**vault-crypto `2.0.1` 적용.** 라이브러리 버전을 `0.0.11 → 2.0.1`로 재정렬하고(OpenBao 전환 문서 반영,
API/동작 변경 없음) demoApp 의존성을 맞췄다. 애플리케이션 코드 변경은 없다. `xaandemo-0.0.21.jar`로 빌드/배포.

### KMS cutover: HashiCorp Vault → OpenBao 2.6.2 (2026-11-08)

**인프라 전환**: 두 인스턴스(`.56`, `.57`)의 `spring.cloud.vault.uri`를
`http://192.168.2.57:28200`(OpenBao)로 변경하고 재기동했다. KEK/DEK/blind-index 시크릿은 값 변형 없이
OpenBao kv-v2(`ebiz_service`)로 복사했으며, DB 암호문/애플리케이션 코드는 변경하지 않았다.
앱 토큰은 root에서 `demoapp-read`(TTL 768h, renewable)로 축소했다. 이전 Vault `1.19.5`(`:8200`)는 중지했다.
검증: 두 인스턴스 health UP(`vault: UP`), 기동 로그 정상, 게시글/사용자 신규 데이터가 OpenBao 키로
암호화됨. 레거시 포맷 행은 0건. 상세: [VAULT_TO_OPENBAO_MIGRATION_PLAN.md](VAULT_TO_OPENBAO_MIGRATION_PLAN.md).

### Docs & repository hygiene (2026-10-31, no version bump)

**문서 현행화**: 단일 키(`fernet-key`) 시절 내용이 남아 있던 `VAULT_AND_ENCRYPTION.md`와
`VAULT_INTEGRATION_DIAGRAM.md`를 현재 KEK-DEK 봉투 암호화 구조로 전면 재작성했다. `IMPROVEMENTS.md`는
현재 백로그 기준으로 재정리(해결/미해결 구분), `PASSWORD_ENCRYPTION_MIGRATION.md`와
`VAULT_INTEGRATION_PRESENTATION.md`는 역사 기록임을 명시했다.

**보안 정리**: 문서·README·`decrypt_passwords.py`에 남아 있던 구 단일 키(`fernet-key`) 실제 값을
제거했다. `decrypt_passwords.py`는 하드코딩 키 대신 Vault에서 KEK/DEK를 읽어 봉투 포맷을 복호화하도록
갱신하고 `--self-test`를 추가했다. 구 경로(`/home/xaan/opencode/...`)로 옛 문서를 덮어쓰던 일회성
생성기 `regenerate_markdown.py`는 삭제했다. (vault-crypto 저장소에서는 커밋되어 있던 `build/`·`.gradle/`
산출물 26개를 추적 해제했다.)

### v0.0.19 (2026-09-03)

**JDK 21 upgrade.** Gradle toolchain `languageVersion` bumped `17 → 21`; the dev environment now builds with `C:\SW\jdk-21.0.8` (Windows) / `/usr/lib/jvm/java-21-openjdk-amd64` (Linux). `vault-crypto` bumped in lockstep (`0.0.10 → 0.0.11`, `sourceCompatibility`/`targetCompatibility` also `17 → 21`) since demoApp depends on it. No application code changes - both projects rebuilt clean against JDK 21 (`xaandemo-0.0.19.jar` built successfully). Production deploy still pending - run `deploy.bat`/`./deploy.sh` to ship it.

### v0.0.18 (2026-08-29)

**사용자 목록2 (`/users2`) - Redis 캐시를 쓰면서도 캐시에는 절대 복호화된 개인정보를 올리지 않는 예제.** `/users`와 화면·검색 조건은 동일하지만, DB 조회 결과를 Redis에 캐싱한다.

**설계**: Redis(`spring.cache.type=redis`)에 올라가는 값은 `UserMapper.search()`가 반환하는 **암호문 그대로의** `User` 목록뿐이다 - `UserSearchCacheService.search(...)`가 `@Cacheable("userSearchRaw")`로 이 raw 조회만 캐싱한다. 복호화(`PasswordService.decryptUserPiiForDisplay(...)`)는 캐시 적중 여부와 무관하게 `UserService.searchCached(...)`가 **캐시에서 꺼낸 뒤 매번** 수행하므로, Redis 서버/네트워크 어디에도 평문 주민등록번호·전화번호가 노출되지 않는다.

**Changes:**
- `User` 엔티티에 `Serializable` 추가 (Redis 캐시 값 직렬화에 필요 - 캐시에 담기는 필드는 항상 ciphertext뿐)
- 신규 빈 `UserSearchCacheService.search(name, phoneBlindIndex, rrnBlindIndex)` 추가 (`@Cacheable(value = "userSearchRaw")`, raw ciphertext만 반환) - `UserService`가 아닌 **별도 빈**으로 분리했다. 같은 클래스 안에서 `@Cacheable` 메서드를 `this`로 직접 호출(self-invocation)하면 Spring 프록시를 우회해 캐싱이 조용히 동작하지 않는데(실제로 처음엔 이렇게 구현했다가 배포 직후 발견해 고쳤다), 별도 빈을 거치면 항상 프록시를 통해 호출되므로 이 문제가 원천적으로 없다. `UserService.searchCached(...)`가 이 빈을 호출한 뒤 행별 복호화
- `UserService.register()`에 `@CacheEvict(value = "userSearchRaw", allEntries = true)` 추가 - 안 그러면 방금 가입한 사용자가 TTL(5분) 동안 검색 결과에 안 보임
- `UserAdminController`에 `GET /users2` 추가, 신규 템플릿 `users/list2.html`은 `/users`와 완전히 동일한 레이아웃/검색 조건/마스킹 - 폼 action만 `/users2`
- 두 목록 페이지 상단에 서로를 오가는 버튼 추가
- 캐싱이 실제로 동작하는지 `redis-cli` 없이도 검증: `pg_stat_user_tables.seq_scan`(이름 검색은 `EXPLAIN`으로 `Seq Scan`임을 확인)을 기준으로, 새 검색어 최초 조회는 DB를 실제로 타고(+1) 동일 검색어 재조회는 타지 않음(+0)을 직접 확인

### v0.0.17 (2026-08-29) — critical fix for v0.0.16

**Login/registration broken in production: `EnvelopeCryptoTypeHandler` (from vault-crypto 0.0.9, added in v0.0.16) was silently encrypting every plain `String` column in the app, not just the ones it was assigned to.** Reported as "login이 안됨"; reproduced via a fresh `/register` call, which threw `PSQLException: value too long for type character varying(50)` on `users.user_id` - the plain `#{userId}` parameter (no `typeHandler=` attribute) had been AES-GCM encrypted. Same silent behavior would have applied to `board.title`/`content`/`author` on every new post (no visible error there, since those columns aren't length-constrained enough to overflow - meaning it could have corrupted data with no error at all).

**Root cause**: `BaseTypeHandler<T>` (which `EnvelopeCryptoTypeHandler` extended) also extends `TypeReference<T>`, which MyBatis's `TypeHandlerRegistry.register(TypeHandler)` - the exact method Spring Boot's MyBatis auto-configuration calls for every `TypeHandler` bean - uses to auto-discover a mapped Java type when no `@MappedTypes` is present. That made `BoardPasswordTypeHandler`/`UserPiiTypeHandler` MyBatis's default handler for the entire `String` type app-wide, not scoped to their intended columns.

**Fix**: vault-crypto `0.0.9 → 0.0.10` - `EnvelopeCryptoTypeHandler` now implements the bare `TypeHandler<String>` interface directly instead of extending `BaseTypeHandler`, so the auto-discovery path never fires. See vault-crypto's own Release History for the full writeup and the new regression test. demoApp's `BoardPasswordTypeHandler`/`UserPiiTypeHandler` needed no code changes beyond the dependency bump.

**Verified live**: confirmed `/register` failed before the fix; after deploying v0.0.17, confirmed `/register` succeeds, `/login` with the new account reaches the authenticated page, and a REST-created board post's `title`/`content`/`author` are stored as plaintext (checked directly via `psql`). No real user/board data was created during the ~20-minute window the bug was live in production - only this session's own test rows, which were deleted.

### v0.0.16 (2026-08-26)

**Encryption moved onto MyBatis TypeHandlers + phone number field with blind index search.** Four related changes, all building on the JPA→MyBatis migration:

**Changes:**
- **`vault-crypto` bumped to `0.0.9`** - adds `EnvelopeCryptoTypeHandler` (MyBatis) and `BlindIndexService`/`BlindIndexKeyProvider` (blind index). See vault-crypto's own Release History for details
- **TypeHandler adoption**: `config/mybatis/BoardPasswordTypeHandler`/`UserPiiTypeHandler` now handle encryption at the Mapper boundary. `BoardService`/`UserService` no longer call `PasswordService.encrypt*()`/`decrypt*()` at all - they pass/receive plain strings and `BoardMapper`/`UserMapper`'s SQL does the rest. `board.password` stays write-side-only (see `BoardMapper`'s comment - ~46k legacy rows would break every ordinary read otherwise); `users.id_no`/`phone` are wired both ways (no legacy data in that table)
- **`users.phone` added** (AES-GCM encrypted, `user-pii` domain - same DEK as RRN): collected at registration (`auth/register.html` gained a phone field, digits-only enforced client-side via `oninput` stripping non-digits, `\d{9,11}` pattern) and validated server-side in `UserService.register()`
- **Blind index search**: `users.phone_blind_idx`/`id_no_blind_idx` columns hold a deterministic HMAC-SHA256 of the phone/RRN, computed by `PasswordService.computePhoneBlindIndex()`/`computeRrnBlindIndex()`. New `/users` page (`UserAdminController`, `users/list.html`) lets you search by 이름 (LIKE, plaintext), 전화번호/주민등록번호 (exact match against the blind index column) - the search inputs also strip non-digits client-side
- New `migrations/001_add_user_phone_and_blind_index.sql` (not run automatically - MyBatis has no `ddl-auto`, apply by hand) and `bootstrap_blind_index_keys.py` (prints `vault kv put` commands for the two blind-index HMAC keys, same non-executing pattern as `bootstrap_kek_dek.py`)
- `DekReencryptionService`/`UserMapper`/`BoardMapper` gained `*Raw` variants (`findAllRaw`, `updatePasswordRaw`, `updateResidentRegistrationNumberRaw`) so the DEK-rotation reencryption batch can still read/write raw ciphertext directly, bypassing the now-auto-encrypting TypeHandler (which would otherwise double-encrypt an already-encrypted value read back through it)
- `PasswordServiceTest` rewritten to match the narrower `PasswordService` API (dropped `encryptBoardPassword`/`decryptBoardPassword`/`encryptUserPii`/`decryptUserPii`, all now unused since the TypeHandlers took over; added blind index coverage)
- **Not yet deployed**: needs `migrations/001_...sql` applied to the production DB and `bootstrap_blind_index_keys.py`'s output run against Vault first - the blind index beans fail fast at startup (same as KEK/DEK) if their Vault secrets don't exist yet

### v0.0.15 (2026-08-26)

**Persistence layer migrated from Spring Data JPA to MyBatis 3.5.16.** All database access now goes through annotation-based mapper interfaces instead of Spring Data repositories. No behavior change for users - same endpoints, same data, same encryption flows; only the DB wiring underneath changed.

**Changes:**
- `build.gradle`: replaced `spring-boot-starter-data-jpa` with `mybatis-spring-boot-starter:3.0.4` + `org.mybatis:mybatis:3.5.16`
- New `domain/mapper/BoardMapper.java` / `UserMapper.java`: annotation-based SQL (`@Insert`/`@Select`/`@Update`) replacing the deleted `BoardRepository`/`UserRepository`
- Entities (`Board`, `User`, `BaseTimeEntity`): stripped all `jakarta.persistence` annotations/mappings; `BoardSummaryDto` converted from a projection interface to a plain class
- Services (`BoardService`, `UserService`, `DekReencryptionService`) switched from repositories to mappers; `IndexController` dropped `Pageable`; `DemoApplication` removed `@EnableJpaAuditing`
- `application.properties`: JPA settings replaced with MyBatis configuration (`map-underscore-to-camel-case=true`)
- Verified end-to-end on production: build, deploy, and an 8-step smoke test (board save/find/update, /last100, register, login, authed list, native-summary query) all passed against the real PostgreSQL

### v0.0.14 (2026-08-21)

**All password-related crypto now lives in vault-crypto, not demoApp.** Previously `PasswordService` instantiated Spring Security's `BCryptPasswordEncoder` directly for login-password hashing, while only the AES-GCM envelope encryption (board password, PII) went through `vault-crypto`. Moved BCrypt hashing into vault-crypto too (`PasswordHasher`), so demoApp's own code no longer references any crypto library directly - it just calls `vault-crypto` and stays focused on business logic (registration flow, session handling, DB wiring). No behavior change for users.

**Changes:**
- `vault-crypto` `0.0.7 → 0.0.8`: added `PasswordHasher` (`com.xaan.vault.crypto`, `hash()`/`matches()`, wraps `BCryptPasswordEncoder` - needs no external key, so still no Vault dependency)
- `PasswordService.java`: `hashUserPassword()`/`validateUserPassword()` now delegate to `new PasswordHasher()` instead of constructing `BCryptPasswordEncoder` locally
- demoApp's `build.gradle`: dropped the direct `spring-security-crypto` dependency (now pulled in transitively through `vault-crypto`) and bumped `vault-crypto` to `0.0.8`

### v0.0.13 (2026-08-21)

**List click now opens the password popup directly.** Removed the remaining intermediate step from v0.0.12: previously a click navigated to the main-window detail page first, which then required clicking a "비밀번호 확인" button to open the popup. Now, for a password-protected post the row hasn't been verified for this session, clicking its title in any list opens the popup immediately - no detail page, no extra button click. Un-protected or already-verified posts still navigate straight to `/posts/{id}` as before.

**Changes:**
- `IndexController`/`Top100IndexController`: each list-rendering endpoint (`/`, `/last100`, `/list1st`, `/list1stonly`) now also adds a `needsPopupIds` model attribute - the set of post ids that are password-protected and not yet verified in this session
- `last100.html`/`list1st.html`/`list1stonly.html`: each title cell renders either a popup-opening link (`th:if="${needsPopupIds.contains(post.id)}"`) or a normal navigation link (`th:unless`), decided server-side per row
- `posts/verify-popup.html`: simplified to a bare, dependency-free form (no Bootstrap) so the popup window itself can be sized down to a minimal `300x180`
- `posts/verify-popup-success.html`: on a correct password, the popup now sends its opener straight to `/posts/{id}` (the now-verified detail page) instead of just reloading whatever the opener happened to be showing
- `posts/view.html`'s inline "비밀번호 확인" button is kept only as a fallback for the edge case of navigating to `/posts/{id}` directly (e.g. a bookmarked link) without having come from a list - browsers block a script-triggered `window.open()` that isn't a direct result of a user click, so an unprompted auto-popup on page load isn't reliable there

### v0.0.12 (2026-08-21)

**Popup scope narrowed to password entry only.** Reworked the v0.0.11 popup UX per feedback: opening the whole detail view in a popup was more than needed. Now only the password prompt for a password-protected post is a popup; the detail view and edit screen are back to normal main-window pages.

**Changes:**
- `last100.html`/`list1st.html`/`list1stonly.html`: title links reverted to plain navigation (`th:href`) to `/posts/{id}`, opening in the main window as before v0.0.11
- `posts/view.html`: reverted to a full main-window page (app header/logout restored). For an unverified password-protected post, a "비밀번호 확인" button opens a small popup instead of showing an inline password form
- New `GET`/`POST /posts/{id}/verify-popup` (replacing the old `/posts/{id}/verify`) renders/handles a minimal `posts/verify-popup.html` form scoped to the popup. On success, a small `posts/verify-popup-success.html` response has the popup reload its opener (the main-window detail page, which now reads as verified) and close itself; on failure, the popup re-shows itself with an error and stays open for retry

### v0.0.11 (2026-08-21)

**Post detail view opens as a popup window.** Front-end-only change: clicking a title in any list now opens `/posts/{id}` via `window.open()` (a real small browser window, not a modal) instead of navigating the current tab. No server-side changes - the same password-gated view/verify logic from v0.0.10 runs inside the popup as-is.

**Changes:**
- `last100.html`/`list1st.html`/`list1stonly.html`: title links now call a small `openPostPopup(url)` helper (`window.open(url, 'postViewPopup', 'width=650,height=600,scrollbars=yes,resizable=yes')`) instead of navigating directly
- `posts/view.html`: trimmed for a compact popup (no app header/logout button); both the password-cancel and content "close" actions now call `window.close()` instead of navigating back to the list, since the list page is still open behind the popup

### v0.0.10 (2026-08-21)

**Password-gated post detail view.** Previously, clicking a title in any board list went straight to the edit form, which rendered the full title/content server-side with no password check at all - so anyone could read a "password-protected" post's content without ever entering the password. Added a real read-only detail view that only reveals content after the password is verified.

**Changes:**
- `IndexController`: added `GET /posts/{id}` (detail view) and `POST /posts/{id}/verify` (password check via the existing, previously-unused `BoardService.verifyPassword`). Once verified, the session remembers it for that post (no re-prompt until logout) - posts without a password skip the prompt entirely.
- `BoardResponseDto`: added a `passwordProtected` flag (computed from whether `Board.password` is set) - never exposes the actual encrypted value
- New `posts/view.html` template: shows a password form until verified, then the read-only content, with buttons back to the list and to the (still-unchanged) edit form
- `last100.html`/`list1st.html`/`list1stonly.html`: title links now point to `/posts/{id}` instead of `/posts/update/{id}`
- Deployed and verified: clean boot, `/last100` responds 200, no errors in logs
- Known follow-up (not yet done): `GET /api/v1/posts/{id}` (REST API) still returns full content with no password check - a separate code path from the web UI fixed here

### v0.0.9 (2026-08-20)

**Removed the `both` DEK-ops mode; guarded against combining rotate+reencrypt in one run.** A real production test hit this exact footgun: `EnvelopeCryptoService` reads Vault's current DEK version once when the bean is built, before `DekOpsRunner` ever runs, so a reencrypt requested in the same JVM run as a rotate would still see the pre-rotation version - it would silently do nothing useful instead of migrating rows to the version just created.

**Changes:**
- `dek_ops_batch.bat`: removed the `both` mode entirely (only `rotate <domain>` and `reencrypt <domains>` remain)
- `DekOpsRunner.run()` now throws `IllegalStateException` immediately if both `app.dek-ops.rotate-domain` and `app.dek-ops.reencrypt-domains` are set at once, regardless of invocation path (restart-based env vars or batch mode) - fails loudly instead of running something that looks like it worked but didn't
- Rotate and reencrypt must always be two separate runs: rotate, let it finish, then start a fresh run for reencrypt

### v0.0.8 (2026-08-20)

**Fix: DEK reencryption batch mis-reported legacy-format rows as failures** - a real production run against ~2M board rows logged 46,266 `ERROR`s (`Envelope domain mismatch`, `No DEK version ... loaded`), all from pre-KEK-DEK legacy-format ciphertext that has no `domainCode`/`keyVersion` header at all (deliberately left unmigrated per the 2026-08-19 decision) - reading one produces effectively random header bytes, so `decrypt()` correctly rejects them, but `DekReencryptionService` was counting that as `failed` instead of recognizing it as "not our format, leave alone."

**Changes:**
- `DekReencryptionService` now catches `CryptoException` specifically and counts it under a new `notEnvelopeFormat` result field instead of `failed`, without logging one line per row (avoids tens of thousands of log lines for a known, permanent, expected condition)
- `failed` is now reserved for genuinely unexpected `RuntimeException`s, still logged per-row for investigation
- `DekOpsRunner`'s log line now reports all four counts: `migrated`, `skipped`, `notEnvelopeFormat`, `failed`

### v0.0.7 (2026-08-20)

**DEK Rotation Tooling** - the command-level piece the rotation runbook's step 4 ("점진적 재암호화") previously only described in prose.

**Changes:**
- `vault-crypto`: added `EnvelopeCryptoService.currentVersion()` and `versionOf(String)` (reads a ciphertext's `keyVersion` header without decrypting), so a reencryption batch can cheaply skip rows already on the current DEK version instead of rewriting every row unconditionally
- `CryptoConfig`: added `DekRotationSupport` and `KekRotationSupport` beans (previously unused library classes with no wiring in the app)
- Added `DekReencryptionService` (`reencryptBoardPasswords()` / `reencryptUserPii()`) and `DekOpsRunner`, a one-shot `ApplicationRunner` gated by two env vars:
  - `ROTATE_DEK_DOMAIN=board` — issue a new DEK version for that domain (old version stays valid for decrypt)
  - `REENCRYPT_DEK_DOMAINS=board,user-pii` — after confirming the app picked up the new version, backfill existing rows onto it
- Both are meant to be set for a single deploy, confirm the logged migrated/skipped/failed counts, then unset before the next deploy - same one-shot pattern as the earlier (now-removed) legacy-format migration switch
- **Standalone manual batch mode**: the switches above run inline during a normal server startup, which the user pointed out isn't fully isolated from live traffic. Added `app.dek-ops.batch-mode` - when true, `DekOpsRunner` calls `System.exit(SpringApplication.exit(...))` after finishing instead of continuing on to start the server. `dek_ops_batch.sh` launches the jar with this plus `--spring.main.web-application-type=none` (the JVM never opens the web port at all), and `dek_ops_batch.bat` uploads/runs it against the production server's `xaandemo-prod.jar` over ssh - a genuinely separate, on-demand batch run rather than something tied to a server restart

### v0.0.6 (2026-08-20)

**KEK Rotation Support** - the KEK itself is now versioned, closing the gap where rotating it would have broken every domain's DEK at once.

**Changes:**
- `vault-crypto`: `KekService` rewritten to hold every loaded KEK version (like `DomainKeyRing` does for DEKs); wrapped-DEK bytes are now self-describing (`kekVersion(1B) | IV | ciphertext+tag`), so `unwrap()` picks the right KEK version even after a rotation
- Added `KekProvider`/`VaultKekProvider` (KEK storage in Vault, versioned exactly like `DekProvider`/`VaultDekProvider`) and `KekRotationSupport` (issue a new KEK version, then re-wrap a domain's DEKs under it)
- Added `retire(...)` to `DekProvider`/`VaultDekProvider` and `KekProvider`/`VaultKekProvider` for permanently removing a version once nothing depends on it
- `bootstrap_kek_dek.py` updated to write the new versioned KEK format
- Detailed KEK/DEK rotation runbook with procedure diagrams: [KEY_ROTATION_RUNBOOK.md](KEY_ROTATION_RUNBOOK.md) (myapp 8082 인스턴스 적용 노트·실측: §2.4, 셸/Python 경로 포함)
- **Breaking**: the wrapped-DEK format changed (adds a version-prefix byte) - the KEK/DEK secrets created for v0.0.5 must be regenerated via the updated `bootstrap_kek_dek.py` before the app can start
- `vault-crypto` `0.0.5 → 0.0.6`, demoApp `0.0.5 → 0.0.6`

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
implementation 'com.xaan:vault-crypto:0.0.7'          // KEK-DEK 봉투 암호화
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

### OpenBao 키 구성/이전 (`openbao_keys.sh`)

demoApp이 쓰는 키 세트(Transit KEK + wrapped DEK + blind index 키 + 정책/토큰)를 **`bao` CLI만으로** 만들거나,
기존 서버에서 새 서버로 옮기는 **POSIX sh** 스크립트다(python/jq 불필요, `dash`/`busybox ash`에서도 동작).
기본은 dry-run이며 `--apply`로 반영한다.

```sh
# 0) 현재 구성 조회 (읽기 전용)
BAO_ADDR=http://<HOST>:28200 BAO_TOKEN=<token> sh ./openbao_keys.sh show

# 1) 새 서버에 신규 키 세트 생성 (빈 데이터/신규 배포)
sh ./openbao_keys.sh create --addr http://<NEW_HOST>:28200 --token <root> \
    --policy demoapp-read --token-out ./demoapp-app-token --apply

# 2) 기존 데이터 유지한 채 새 서버로 이전
#    - 옛 서버에서 DEK 평문을 꺼내 새 KEK로 rewrap (버전 번호·current-version 유지)
#    - blind index 키는 값을 그대로 복사 (새로 만들면 기존 인덱스 컬럼과 불일치)
OLD_BAO_ADDR=http://<OLD_HOST>:28200 OLD_BAO_TOKEN=<old-root> \
sh ./openbao_keys.sh migrate --addr http://<NEW_HOST>:28200 --token <new-root> --apply

# 3) 모든 DEK 버전 round-trip 검증
sh ./openbao_keys.sh verify --addr http://<NEW_HOST>:28200 --token <root>
```

- 경로 기본값: `--mount ebiz_service --base ebiz_db --transit-mount transit --transit-key kek`
- 원본 경로가 다르면 `--old-mount/--old-base/--old-transit-key`로 지정
- `dek-v{n}` 값은 `Base64URL(UTF-8("vault:vN:..."))` 형식이어야 한다(raw ciphertext를 넣으면 앱이
  `Illegal base64 character 3a`로 기동 실패) — 스크립트가 이 형식으로 저장·검증한다
- 기존 키가 있을 때 `create`/`migrate`는 기본적으로 거부하며, 덮어쓰려면 `--force`가 필요하다
