# Vault Configuration and Encryption (Current Architecture)

> **Status: current as of 2026-11-08 (demoApp `0.0.19`, vault-crypto `0.0.11`, KMS = OpenBao `2.6.2`).**
>
> 이 문서는 **현행** KEK-DEK 봉투 암호화 구조를 설명한다. 2026-05 ~ 2026-08 초에 쓰인
> 단일 키(`fernet-key`) 방식 문서는 폐기되었다 — 해당 설계의 분석과 전환 이력은
> [KEK_DEK_ENCRYPTION_PLAN.md](KEK_DEK_ENCRYPTION_PLAN.md), [PASSWORD_ENCRYPTION_MIGRATION.md](PASSWORD_ENCRYPTION_MIGRATION.md),
> 그리고 git history를 참고하라. 구 `data-enc-key`는 암호화에는 쓰이지 않지만 Spring Cloud Vault의
> property source로 여전히 조회되므로 OpenBao로 함께 이관되어 있다(정리 예정).

## 1. 한눈에 보기

demoApp의 암호화는 전부 `com.xaan:vault-crypto:0.0.11` 라이브러리에 위임되어 있고, 앱 코드는
평문(plain Java `String`)만 다룬다. 세 가지 서로 다른 원시함수를 쓴다.

| 대상 | 방식 | 키 | 적용 지점 |
|---|---|---|---|
| `users.password` (로그인 비밀번호) | **BCrypt** 단방향 해시 (복호화 불가) | 없음 (salt 내장) | `PasswordHasher` (`PasswordService.hashUserPassword/validateUserPassword`) |
| `board.password`, `users.id_no`, `users.phone` | **AES-256-GCM KEK-DEK 봉투 암호화** (양방향) | OpenBao의 KEK + 도메인별 DEK | MyBatis `TypeHandler` |
| `users.phone_blind_idx`, `users.id_no_blind_idx` | **HMAC-SHA256 blind index** (결정적, 검색용) | 필드별 독립 HMAC 키 (OpenBao) | `BlindIndexService` (`PasswordService.compute*BlindIndex`) |

핵심 성질:

- **요청 처리 중 KMS(OpenBao) 호출은 0회.** KEK/DEK는 앱 기동 시 한 번 로드·unwrap되어 `DomainKeyRing`에
  캐시되고, `EnvelopeCryptoService`가 그 캐시를 참조한다.
- **도메인 격리.** 암호문 헤더의 `domainCode` 덕분에 `board` 암호문을 `user-pii` DEK로 복호화할 수 없다.
- **버전 인식.** 암호문의 `keyVersion`과 wrapped-DEK의 `kekVersion` 덕분에 로테이션 후에도 구 데이터가
  계속 복호화된다.
- **AES-GCM은 확률적(random IV)** 이므로 같은 평문도 매번 다른 암호문이 된다. 따라서 암호문 컬럼은
  `=`/`LIKE` 검색이 불가능하고, 검색이 필요한 값은 blind index를 함께 저장한다.

## 2. KMS(OpenBao) 구성

- **Server**: `http://192.168.2.57:28200` (OpenBao 2.6.2, raft, unsealed), kv-v2 mount `ebiz_service`
- **이전 KMS**: HashiCorp Vault 1.19.5 `http://192.168.2.57:8200` — 2026-11-08 컷오버 후 중지(롤백/soak용 보존)
- **암호화에 쓰는 경로는 5개다.** (+ 레거시 `data-enc-key` 1개는 property source 용도로만 읽는다.)

| Secret path (mount 기준) | 필드 | 용도 |
|---|---|---|
| `ebiz_db/kek` | `kek-v{n}`, `current-version` | 마스터 KEK. DEK를 wrap/unwrap하는 데만 사용 |
| `ebiz_db/dek/board` | `dek-v{n}`, `current-version` | `board` 도메인 DEK (wrapped 상태로 저장) |
| `ebiz_db/dek/user-pii` | `dek-v{n}`, `current-version` | `user-pii` 도메인 DEK (`users.id_no`/`phone`) |
| `ebiz_db/blind-index/user-phone` | `key` | 전화번호 blind index HMAC 키 (버전 없음) |
| `ebiz_db/blind-index/user-rrn` | `key` | 주민등록번호 blind index HMAC 키 (버전 없음) |

키를 만들 때는 스크립트가 출력하는 `vault kv put` 명령을 **사용자가 직접** 실행한다
(스크립트는 Vault에 접속하지 않는다).

```bash
# KEK + 도메인별 wrapped DEK (kek-v1 / dek-v1, current-version=1)
python3 bootstrap_kek_dek.py

# 필드별 blind index HMAC 키 (user-phone, user-rrn)
python3 bootstrap_blind_index_keys.py

# 확인
vault kv get -mount=ebiz_service ebiz_db/kek
vault kv get -mount=ebiz_service ebiz_db/dek/board
vault kv get -mount=ebiz_service ebiz_db/dek/user-pii
vault kv get -mount=ebiz_service ebiz_db/blind-index/user-phone
vault kv get -mount=ebiz_service ebiz_db/blind-index/user-rrn
```

> 출력에는 평문 KEK/HMAC 키가 포함된다. 터미널 기록·로그에 남기지 말 것.
> blind index 키는 **최초 1회만** 생성한다 — 데이터가 쌓인 뒤 키를 바꾸면 기존 `*_blind_idx` 값이
> 전부 무효화되어 재인덱싱 전까지 검색이 아무것도 찾지 못한다.

### application.properties (예시)

`application.properties`는 `.gitignore` 대상이며 저장소에 없다. 운영 서버에서는
`/home/xaan/ws/demoBBS/app/application.properties`에 두고 `startapp.sh`가
`--spring.config.additional-location=file:...`로 주입한다.

```properties
spring.cloud.vault.uri=http://192.168.2.57:28200
spring.cloud.vault.token=${VAULT_TOKEN}   # OpenBao demoapp-read 토큰
spring.cloud.vault.fail-fast=false

# 선택 - 생략하면 아래 기본값이 쓰인다 (CryptoConfig 참고)
vault.kek.path=ebiz_service/data/ebiz_db/kek
vault.dek.base-path=ebiz_service/data/ebiz_db/dek
vault.blind-index.base-path=ebiz_service/data/ebiz_db/blind-index

# 필수 - 없으면 snake_case 컬럼이 조용히 null로 매핑된다
mybatis.configuration.map-underscore-to-camel-case=true
```

> **기동 시점 주의**: `CryptoConfig`의 KEK/DEK/blind-index 빈은 eager로 생성되며 생성 과정에서
> Vault를 읽는다. 따라서 Vault가 **sealed/도달 불가**이거나 secret이 없으면 `KeyLoadingException`으로
> **앱 기동 자체가 실패**한다(`fail-fast=false`여도 마찬가지 — 그 설정은 Vault 세션 생성 정책일 뿐,
> 이 커스텀 빈들의 읽기 실패를 삼키지 않는다). 이미 기동된 프로세스는 DEK를 메모리에 캐시하고 있어
> Vault가 sealed 되어도 계속 동작한다. 배포(재기동) 전에는 `vault status`로 unseal 상태를 확인하라.

## 3. 코드 구조

### 3.1 빈 배선 — `config/CryptoConfig.java`

```java
@Bean KekProvider kekProvider(VaultOperations v, @Value("${vault.kek.path:...}") String path)
@Bean KekService kekService(KekProvider p)                       // 기동 시 KEK 전체 로드
@Bean DekProvider dekProvider(VaultOperations v, @Value("${vault.dek.base-path:...}") String path)
@Bean EnvelopeCryptoService boardCryptoService(KekService k, DekProvider d)   // domainCode=1
@Bean EnvelopeCryptoService userPiiCryptoService(KekService k, DekProvider d) // domainCode=2
@Bean DekRotationSupport / KekRotationSupport                    // 운영 도구
@Bean BlindIndexKeyProvider blindIndexKeyProvider(VaultOperations v, ...)
@Bean BlindIndexService phoneBlindIndexService(BlindIndexKeyProvider p)      // "user-phone"
@Bean BlindIndexService rrnBlindIndexService(BlindIndexKeyProvider p)        // "user-rrn"
```

`EnvelopeCryptoService.forDomain(...)`가 호출되는 순간 해당 도메인의 모든 DEK 버전을 unwrap해
`DomainKeyRing`에 담는다. 이후 `encrypt()`는 `current-version` 키를, `decrypt()`는 암호문 헤더의
`keyVersion` 키를 메모리에서 찾는다.

### 3.2 암호화 적용 — MyBatis `TypeHandler`

서비스 코드는 평문만 넘기고, 암/복호화는 매퍼가 지정한 TypeHandler가 수행한다.

```java
// BoardMapper - 쓰기 경로에만 (레거시 행 보호)
@Insert("insert into board (... password ...) values (..., " +
        "#{password,typeHandler=com.xaan.demo.config.mybatis.BoardPasswordTypeHandler}, ...)")
int insert(Board board);

// UserMapper - 읽기/쓰기 양쪽 (users 테이블은 레거시 없음)
@Result(column = "id_no", property = "residentRegistrationNumber", typeHandler = UserPiiTypeHandler.class)
@Result(column = "phone", property = "phone", typeHandler = UserPiiTypeHandler.class)
```

`BoardPasswordTypeHandler`/`UserPiiTypeHandler`는 `@Component`이고 `@MappedTypes`를 **붙이지 않는다**.
vault-crypto의 베이스 클래스가 `BaseTypeHandler`가 아니라 `TypeHandler<String>`을 직접 구현하는 것도
같은 이유다 — `BaseTypeHandler`의 `TypeReference` 자동탐색 때문에 Spring Boot의 MyBatis 자동구성이
이 핸들러를 **앱 전체 `String` 컬럼의 기본 핸들러**로 등록해버린 사고(v0.0.10에서 수정)가 있었다.
새 TypeHandler를 추가할 때는 반드시 "명시적으로 지목한 컬럼에서만 동작하는지"를 실제
`TypeHandlerRegistry`로 검증하라 (회귀 테스트: `registeringTheHandlerDoesNotMakeItTheDefaultHandlerForString`).

### 3.3 서비스 계층에 남은 것 — `service/PasswordService.java`

- `hashUserPassword` / `validateUserPassword` → `PasswordHasher` (BCrypt)
- `validateBoardPassword(input, storedCiphertext)` → `boardCryptoService.validate(...)` (상수 시간 비교)
- `computePhoneBlindIndex` / `computeRrnBlindIndex` → `BlindIndexService` (호출 전 정규화 필수)
- `decryptUserPiiForDisplay(ciphertext)` → 목록 화면 전용 fail-soft 복호화. 실패 시 `"(복호화 실패)"`를
  반환해 한 행의 문제로 목록 전체가 죽지 않게 한다.
- `isLegacyPassword(stored)` → BCrypt 해시(`$2a$`/`$2b$`/`$2y$`) 여부 판별

## 4. 저장 형식

### 4.1 컬럼 값 (봉투 암호문)

```
Base64URL( domainCode(1B) | keyVersion(1B) | IV(12B) | ciphertext(N) | GCM tag(16B) )
```

- `board` = domainCode `1`, `user-pii` = domainCode `2`
- IV는 매 암호화마다 `SecureRandom`
- 최소 길이 검증: IV + tag = 28바이트
- `domainCode` 불일치 → `CryptoException("Envelope domain mismatch")`
- 로드되지 않은 `keyVersion` → `CryptoException("No DEK version ... loaded")`

### 4.2 Vault에 저장되는 wrapped DEK (`dek-v{n}`)

```
kekVersion(1B) | IV(12B) | ciphertext(32B = DEK) | GCM tag(16B)
```

self-describing이므로 `unwrap()`은 별도 버전 필드 없이 바이트만 보고 올바른 KEK 버전을 고른다.

### 4.3 레거시 (구 단일 키) — 2026-11-08 기준 **0건**

KEK-DEK 도입 이전 `board.password`는 2026-08-19 P3 취소 당시 약 46,266행이 구 포맷
(`IV | ciphertext | tag`, 헤더 없음)이었으나, 2026-11-08 운영 DB 확인 결과 비밀번호가 있는
15행 전부 envelope 포맷(v5~v7)이며 **구 포맷은 0건**이다.

- 과거 구 포맷 글은 비밀번호 값이 남아 있지 않아 검증 대상이 아니다
- 재암호화 배치는 만약 구 포맷이 발견되면 `notEnvelopeFormat`으로 집계하고 건드리지 않는다
- 배경은 [KEK_DEK_ENCRYPTION_PLAN.md](KEK_DEK_ENCRYPTION_PLAN.md) P3 참고

## 5. 조회 패턴 (반드시 지킬 것)

| 상황 | 방법 |
|---|---|
| 단건 조회 | TypeHandler가 자동 복호화 (`findByUserId`) |
| 비밀번호 일치 확인 | `EnvelopeCryptoService.validate(input, ciphertext)` — 복호화 후 상수 시간 비교 |
| **로그인** | `findAuthByUserId()` — `id_no`/`phone`을 select조차 하지 않아 PII 문제가 로그인을 막지 않음 |
| 목록/검색 | `search()`는 **raw ciphertext**로 읽고, 행별 `decryptUserPiiForDisplay()`로 fail-soft 복호화 |
| 암호화 컬럼 검색 | 평문 정규화 → HMAC → `WHERE *_blind_idx = ?` (정확 일치만, 부분 검색 불가) |
| Redis 캐시 | **raw ciphertext만** 캐싱(`UserSearchCacheService`), 복호화는 캐시 조회 이후 |
| 재암호화 배치 | `*Raw` 매퍼 메서드 사용 (TypeHandler를 걸면 이중 암호화됨) |
| 비밀번호 미변경 수정 | `updateTitleContent()` — `password` 컬럼을 건드리지 않음 |

자세한 규칙과 실수 사례는 [ENCRYPTED_COLUMN_QUERY_GUIDE.md](ENCRYPTED_COLUMN_QUERY_GUIDE.md) 참고.

## 6. 키 로테이션

전체 절차는 [KEY_ROTATION_RUNBOOK.md](KEY_ROTATION_RUNBOOK.md)에 있다. 요약:

- **DEK**: `rotate(domain)` → 새 버전 발급 → **별도 실행**으로 재암호화 배치
  (`dek_ops_batch.sh|.bat reencrypt <domain>`) → `failed=0` 확인 → `retire(domain, oldVersion)`
- **KEK**: `issueNewKekVersion()` → 새 KEK로 모든 도메인 DEK 재wrap(`rewrapDomainDeks`) → 검증 →
  전 인스턴스 재기동 → `retire(oldVersion)`

주의:

- `rotate`와 `reencrypt`를 **같은 실행에서** 하지 말 것 (`DekOpsRunner`가 `IllegalStateException`으로 거부).
  `EnvelopeCryptoService`는 기동 시 현재 버전을 한 번만 읽기 때문이다.
- `retire()`는 **비가역**이다. retire 전까지는 언제든 롤백 가능.
- `DekProvider.store()`/`KekProvider.store()`는 read-modify-write이므로 동시 실행 금지.
- KEK 로테이션은 현재 **라이브러리 API만** 있고 운영 트리거(CLI/엔드포인트)는 없다 → `IMPROVEMENTS.md` 참고.

## 7. 보안 고려사항

1. **저장소에 실제 키/토큰/DB 비밀번호를 쓰지 않는다.** 이 문서와 스크립트에는 플레이스홀더만 둔다.
   과거 커밋된 구 단일 키(`fernet-key`) 값은 2026-10-31에 문서/스크립트에서 제거했다. 이 시크릿은
   마이그레이션 시 OpenBao로 복사되었으므로, property source 설정을 제거한 뒤 양쪽 KMS에서 삭제할 것.
2. **OpenBao 통신은 현재 HTTP(비 TLS) + Token 인증**이다. 앱 토큰은 `demoapp-read`로 최소권한화되어 있으나,
   운영에서는 TLS와 토큰 주기 교체를 권장한다.
3. **KEK는 데이터를 직접 암/복호화하지 않는다.** 오직 DEK wrap/unwrap 전용.
4. **도메인 분리**를 유지한다. 새 도메인을 추가하면 고유한 `domainCode`를 부여한다.
5. **평문을 로그/캐시/응답에 남기지 않는다.** 화면 마스킹(전화 앞 3자리, RRN 3~5번째 자리) 유지.
6. **BCrypt는 복호화 불가**이며, 조건 검색도 불가능하다(다른 키로 조회 후 `matches()`).
7. **`fail-fast`와 무관하게 기동 시 OpenBao 읽기 실패는 치명적**이다 (2절 주의 참고).

## 8. 관련 문서

| 문서 | 내용 |
|---|---|
| [README.md](README.md) | 앱 개요, 빌드/배포, 릴리스 이력 |
| [KEK_DEK_ENCRYPTION_PLAN.md](KEK_DEK_ENCRYPTION_PLAN.md) | 전환 설계·P0~P5 이력 (P3 취소) |
| [KEY_ROTATION_RUNBOOK.md](KEY_ROTATION_RUNBOOK.md) | KEK/DEK 로테이션 운영 절차 |
| [ENCRYPTED_COLUMN_QUERY_GUIDE.md](ENCRYPTED_COLUMN_QUERY_GUIDE.md) | 암호화 컬럼 조회/수정 규칙 |
| [IMPROVEMENTS.md](IMPROVEMENTS.md) | 현재 개선 백로그 |
| [VAULT_TO_OPENBAO_MIGRATION_PLAN.md](VAULT_TO_OPENBAO_MIGRATION_PLAN.md) | Vault → OpenBao 전환 계획 및 실행 결과 |
| `vault-crypto/README.md` (별도 저장소) | 라이브러리 API·설정·포맷 전체 |
