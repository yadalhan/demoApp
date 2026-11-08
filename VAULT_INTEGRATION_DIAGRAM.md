# Vault Integration & Crypto Flow (Current Architecture)

> **Status: current as of 2026-11-08 (demoApp `0.0.20`, vault-crypto `2.0.1`; KMS = OpenBao `2.6.2`).**
> 이 문서는 KEK-DEK 도입 이후의 실제 흐름을 도식화한다. 2026-05-08 단일 키(`fernet-key`) 시절의
> 다이어그램은 폐기되었다 — 배경은 [KEK_DEK_ENCRYPTION_PLAN.md](KEK_DEK_ENCRYPTION_PLAN.md) 참고.

## 1. 전체 구조

```
┌──────────────────────────────────────────────────────────────────────┐
│                         demoApp (Spring Boot 3.4.0)                  │
│                                                                      │
│  Controller / Service  ── 평문 String만 취급 ──┐                      │
│                                                ▼                     │
│                                     MyBatis Mapper (annotation SQL)  │
│                                                │                     │
│                          ┌─────────────────────┴──────────────┐      │
│                          ▼                                    ▼      │
│              BoardPasswordTypeHandler               UserPiiTypeHandler│
│              (board.password, 쓰기 전용)             (users.id_no/phone)│
│                          │                                    │      │
│                          └──────────────┬─────────────────────┘      │
│                                         ▼                            │
│                          EnvelopeCryptoService (도메인별 빈)          │
│                          board(1) / user-pii(2)                      │
│                                         │                            │
│                          DomainKeyRing (메모리 캐시, 버전별 DEK)      │
│                                         ▲                            │
│                          ┌──────────────┴──────────────┐             │
│                          │                             │             │
│                     KekService                    BlindIndexService   │
│                     (버전별 KEK)                  (필드별 HMAC 키)     │
│                          ▲                             ▲             │
│  ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ │ 기동 시 1회만 ─ ─ ─ ─ ─ ─ ─ │ ─ ─ ─ ─ ─    │
│                          │                             │             │
│                 VaultKekProvider / VaultDekProvider / VaultBlindIndexKeyProvider
└──────────────────────────┬───────────────────────────────────────────┘
                           │ HTTP REST (Token Auth, 현재 비 TLS)
                           ▼
┌──────────────────────────────────────────────────────────────────────┐
│                 OpenBao 2.6.2 (kv-v2), mount: ebiz_service           │
│                                                                      │
│  ebiz_db/kek                  kek-v{n}, current-version              │
│  ebiz_db/dek/board            dek-v{n}, current-version              │
│  ebiz_db/dek/user-pii         dek-v{n}, current-version              │
│  ebiz_db/blind-index/user-phone   key                                │
│  ebiz_db/blind-index/user-rrn     key                                │
└──────────────────────────────────────────────────────────────────────┘

요청 처리 경로에는 OpenBao 호출이 없다.
```

## 2. 기동 시퀀스 (키 로딩)

```
Application start
  │
  ├─▶ VaultOperations (spring-cloud-starter-vault-config)
  │
  ├─▶ kekProvider.read("ebiz_db/kek")
  │      └─ kek-v1..vN, current-version  ──▶ KekService
  │
  ├─▶ dekProvider.read("ebiz_db/dek/board")
  │      └─ 각 dek-v{n}을 KekService.unwrap()
  │         (wrapped = kekVersion(1B)|IV|ct|tag → 헤더의 KEK 버전으로 unwrap)
  │         └─▶ DomainKeyRing("board", currentVersion, keysByVersion)
  │
  ├─▶ dekProvider.read("ebiz_db/dek/user-pii")
  │      └─▶ DomainKeyRing("user-pii", ...)
  │
  ├─▶ blindIndexKeyProvider.read("ebiz_db/blind-index/user-phone") ──▶ BlindIndexService
  ├─▶ blindIndexKeyProvider.read("ebiz_db/blind-index/user-rrn")   ──▶ BlindIndexService
  │
  └─▶ Application ready (이후 암/복호화는 전부 메모리)

실패 시: KeyLoadingException → 컨텍스트 초기화 실패 (fail-fast=false여도 기동 불가)
```

## 3. 쓰기 흐름

### 3.1 게시글 저장/수정 (`board.password`)

```
사용자 입력: 평문 비밀번호 "vaulttest123"
   │
   ▼
BoardApiController / IndexController
   │
   ▼
BoardService.save()  ── boardMapper.insert(board)  (password는 평문 그대로)
   │
   ▼
BoardMapper.insert
   #{password,typeHandler=BoardPasswordTypeHandler}
   │
   ▼
BoardPasswordTypeHandler.setParameter
   │  null/"" → 그대로 통과
   │  그 외   → EnvelopeCryptoService.encrypt()
   ▼
AES-256-GCM (현재 DEK 버전)
   │
   ▼
Base64URL(domainCode=1 | keyVersion | IV | ct | tag)
   │
   ▼
DB: ebiz.board.password
```

비밀번호를 바꾸지 않는 수정은 `updateTitleContent()`만 호출한다 — `password` 컬럼을 아예 건드리지 않아
이미 암호문인 값을 다시 암호화(이중 암호화)하지 않는다. 비밀번호 교체는 `updatePassword()`로 분리되어 있다.

### 3.2 회원가입 (`users`)

```
UserRegisterRequestDto (평문 RRN/전화번호)
   │
   ├─ 비밀번호  ──▶ PasswordHasher.hash()        ──▶ users.password = "$2a$..." (BCrypt)
   │
   ├─ RRN      ──▶ 정규화/체크섬 검증
   │            ──▶ UserPiiTypeHandler.encrypt() ──▶ users.id_no   (user-pii DEK)
   │            ──▶ BlindIndexService("user-rrn").compute() ──▶ users.id_no_blind_idx
   │
   └─ 전화번호 ──▶ 숫자만 정규화 (9~11자리 검증)
                ──▶ UserPiiTypeHandler.encrypt() ──▶ users.phone   (user-pii DEK)
                ──▶ BlindIndexService("user-phone").compute() ──▶ users.phone_blind_idx
   │
   ▼
UserMapper.insert
```

## 4. 읽기/검증 흐름

### 4.1 단건 조회 — 자동 복호화

```
UserMapper.findByUserId(userId)
   @Result(column="id_no", typeHandler=UserPiiTypeHandler) ...
   │
   ▼
UserPiiTypeHandler.getResult → decrypt(도메인/버전 헤더 확인 → DEK 선택 → GCM 복호화)
   │
   ▼
Service: 평문 User
```

이 경로는 **한 행의 ciphertext가 깨져 있으면 조회 전체가 예외로 실패**한다. 그래서 로그인은 이 메서드를
쓰지 않고 `findAuthByUserId()`(password만 select)를 쓴다 — PII 문제가 로그인 자체를 막지 않게 하기 위함.

### 4.2 비밀번호 확인 — `validate()`

```
입력 평문 + DB ciphertext
   │
   ▼
PasswordService.validateBoardPassword()
   │
   ▼
EnvelopeCryptoService.validate(input, stored)
   ├─ decrypt(stored) 성공 → MessageDigest.isEqual(상수 시간 비교)
   └─ decode/도메인/버전/태그 실패 → dummy 비교 후 false (예외를 밖으로 던지지 않음)

레거시(비-봉투) ciphertext → 항상 false (복호화 폴백 없음, 의도된 동작)
```

### 4.3 목록 — raw 조회 + 행별 fail-soft

```
UserMapper.search(name, phoneBlindIndex, rrnBlindIndex)
   @Result(column="id_no")  ← typeHandler 없음: ciphertext 그대로 반환
   │
   ▼
List<User> (id_no/phone = 암호문)
   │
   ▼  (UserService.search)
행마다 PasswordService.decryptUserPiiForDisplay()
   ├─ 성공 → 평문
   └─ 실패 → "(복호화 실패)"  (로그 warn, 그 행만 대체)
   │
   ▼
UserResponseDto (마스킹: 전화 앞 3자리, RRN 3~5번째만 노출)
```

## 5. Blind Index 검색

```
검색 입력 "010-1234-5678"
   │
   ▼
정규화: 숫자만 (저장 시점과 동일해야 함)
   │
   ▼
BlindIndexService("user-phone").compute("01012345678")
   │  HMAC-SHA256(키, 평문) → Base64URL (결정적)
   ▼
UserMapper.search
   WHERE (name IS NULL OR username LIKE ...)
     AND phone_blind_idx = :phoneBlindIndex
   │
   ▼
DB (B-tree 인덱스: idx_users_phone_blind_idx)
```

- 정확 일치만 가능하다. 부분/접두 검색은 불가능(HMAC 출력은 입력 구조와 무관).
- 정규화를 저장/검색에서 다르게 하면 **조용히 0건**이 된다.
- 필드별 키가 독립이므로 한 필드 키가 노출/교체되어도 다른 필드는 영향 없다.
- 키 교체는 전체 재인덱싱이 필요한 one-shot 작업이다(버전 개념 없음).

## 6. Redis 캐시 (`/users2`)

```
UserService.searchCached()
   │
   ├─▶ UserSearchCacheService.search()      ← 별도 빈 (self-invocation 회피)
   │      @Cacheable("userSearchRaw")
   │      └─ UserMapper.search() → ciphertext 상태의 User 목록
   │
   ├─ cache MISS → DB 조회 → Redis 저장 (ciphertext만)
   └─ cache HIT  → Redis에서 ciphertext 목록
   │
   ▼
복호화는 캐시 조회 **이후** 매번 수행 (decryptUserPiiForDisplay)
   │
   ▼
응답 (평문 + 마스킹)

회원가입 시: @CacheEvict("userSearchRaw", allEntries=true) → 신규 사용자 즉시 반영
```

`UserService.searchCached()`가 `this.searchRawCached()`를 직접 호출하면 Spring 프록시를 우회해
`@Cacheable`이 **조용히 동작하지 않는다**(실제로 발생했던 버그). 캐싱 메서드는 반드시 별도 빈에 둔다.

## 7. 로테이션 흐름 (요약)

```
[DEK 로테이션]
운영자: dek_ops_batch rotate board
  └─▶ DekRotationSupport.rotate("board") → 새 DEK 생성 → KEK로 wrap → Vault dek-v{n+1} + current-version
      (이 실행에서는 재암호화 금지 - EnvelopeCryptoService가 구버전을 current로 캐시 중)

별도 실행: dek_ops_batch reencrypt board
  └─▶ 재기동된 앱이 새 버전을 로드
      각 행: versionOf(ct) == currentVersion? → skip
             아니면 decrypt(old) → encrypt(new) → update*Raw()
      결과: migrated / skipped / notEnvelopeFormat / failed

확인 후: DekProvider.retire("board", oldVersion)   ← 비가역, 마지막 단계
```

```
[KEK 로테이션]
KekRotationSupport.issueNewKekVersion()  → 새 KEK 저장, current-version 갱신
  └─▶ 앱 재기동 (구+신 KEK 모두 로드)
      rewrapDomainDeks(kek, dekProvider, "board")     → DEK 자체는 그대로, 감싼 KEK만 교체
      rewrapDomainDeks(kek, dekProvider, "user-pii")
  └─▶ 검증 후 KekProvider.retire(oldVersion)
```

상세 절차·체크리스트·롤백 시나리오는 [KEY_ROTATION_RUNBOOK.md](KEY_ROTATION_RUNBOOK.md).

## 8. 파일/설정 대응표

| 구성 요소 | 파일 |
|---|---|
| Vault 접속 | `application.properties` (gitignored, 운영은 서버의 `app/application.properties`) |
| 빈 배선 | `src/main/java/com/xaan/demo/config/CryptoConfig.java` |
| TypeHandler | `src/main/java/com/xaan/demo/config/mybatis/{BoardPasswordTypeHandler,UserPiiTypeHandler}.java` |
| 매퍼 | `src/main/java/com/xaan/demo/domain/mapper/{BoardMapper,UserMapper}.java` |
| 서비스 | `PasswordService`, `UserService`, `BoardService`, `UserSearchCacheService`, `DekReencryptionService` |
| 운영 도구 | `bootstrap_kek_dek.py`, `bootstrap_blind_index_keys.py`, `dek_ops_batch.sh/.bat`, `deploy.sh/.bat`, `relink_prod_jar.sh` |
| 헬스체크 | `src/main/java/com/xaan/demo/config/VaultHealthIndicator.java` (actuator) |
| DB 마이그레이션 | `migrations/001_add_user_phone_and_blind_index.sql` (수동 실행) |
