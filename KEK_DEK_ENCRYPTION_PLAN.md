# KEK-DEK 봉투 암호화(Envelope Encryption) 전환 계획

## 0. 문서 목적
현재 demoApp + vault-crypto는 Vault에 저장된 **단일 평문 키 1개**를 모든 양방향 암호화 컬럼(`board.password`, `users.id_no`)에 그대로 사용하고 있다. 이 문서는 이를 **KEK(Key Encryption Key) - DEK(Data Encryption Key) 모델**로 전환하기 위한 현황 분석과 구현 계획을 정리한다. DEK는 DB 조회/DML 성능을 위해 **서비스 도메인 단위로 메모리에 캐시되는 형태**로 구현한다.

> **2026-08-19 결정: 기존 암호화 데이터 마이그레이션 포기.** `users` 테이블은 전체 삭제 예정이라 RRN 마이그레이션 대상 자체가 사라지고, `board.password`의 기존 암호화 데이터도 새 KEK-DEK 포맷으로 전환하지 않고 그대로 둔 채(=사실상 무시) 새로 암호화되는 데이터부터 KEK-DEK 포맷을 적용하기로 했다. 이에 따라 구 포맷과의 하위호환(레거시 복호화 폴백)과 백필 마이그레이션 코드는 모두 제거했다 — 아래 P3는 취소되었고, 관련 서술은 이력 참고용으로만 남겨둔다.
>
> **추가 결정: `VaultCryptoService` 완전 삭제.** 단일 키 방식이던 `VaultCryptoService`를 사용하던 기존 소비 프로젝트는 모두 무시하기로 하고, vault-crypto `v0.0.3`에서 클래스 자체를 라이브러리에서 삭제했다. 이제 vault-crypto의 유일한 공개 API는 `envelope` 패키지(KEK-DEK 봉투 암호화)뿐이다.

---

## 1. 현재 구조 분석 (As-Is)

### 1.1 키 계층 구조 — 사실상 계층이 없음
```
Vault KV-v2: ebiz_service/data/ebiz_db/data-enc-key  (field: fernet-key, 32 bytes)
        │
        ▼  (앱 기동 시 1회 로드, 평문 그대로 보관)
VaultCryptoService.encryptionKey
        │
        ├── board.password   (AES-256-GCM 직접 암복호화)
        └── users.id_no(RRN)  (AES-256-GCM 직접 암복호화, PasswordService.encryptBoardPassword() 재사용)
```
- `VaultCryptoService`(vault-crypto)는 생성자에서 Vault 키를 1회 로드해 인스턴스 필드에 평문으로 들고 있다가, 이후 모든 `encrypt()/decrypt()` 호출에 그대로 재사용한다. 즉 **"Vault 호출은 앱 기동 시 1번만" 이라는 성능 특성은 이미 확보되어 있다** — 이번 작업은 이 특성을 유지한 채 키를 분리하는 것이 핵심이다.
- 이 키는 이름은 `fernet-key`이지만 실제로는 Fernet 포맷이 아니라 32바이트를 그대로 AES-256 키로 사용한다(`IMPROVEMENTS.md` #3에 이미 기록됨).
- `board.password`와 `users.id_no`(주민등록번호)가 **동일한 키 1개**를 공유한다. `UserService.register()`가 `passwordService.encryptBoardPassword(dto.getResidentRegistrationNumber())`를 호출하는데, 메서드 이름은 "board password"지만 실제로는 RRN을 암호화하는 데 쓰이고 있다 — 이름만 봐서는 오해하기 쉬운 부분이고, 현재는 키가 하나뿐이라 동작상 문제는 없지만 도메인별 키 분리 시 반드시 정리해야 한다.
- 암호문 포맷에 **키 식별자/버전 정보가 없다** (`IV(12B) + ciphertext + tag`만 저장). 키를 교체(rotate)하면 기존 데이터를 어떤 키로 복호화해야 할지 판단할 방법이 없다.

### 1.2 정리하면
| 항목 | 현재 상태 | KEK-DEK 관점에서의 의미 |
|---|---|---|
| 키 개수 | 1개 (전역) | KEK/DEK 구분 없음 — 이 키가 사실상 DEK 역할도, 마스터키 역할도 동시에 함 |
| 키 저장 위치 | Vault KV-v2, 평문 | KEK(마스터)는 Vault에 두는 것이 맞으나, DEK는 "KEK로 감싼(wrapped) 형태"로 저장되어야 함 — 현재는 그 계층이 없음 |
| 도메인 분리 | 없음 (board, user-pii 공유) | 한 도메인 유출 시 전체 유출로 이어짐 (blast radius 최대) |
| 성능 특성 | 기동 시 1회 로드 후 메모리 재사용 | ✅ 이미 "네트워크 호출 없는 로컬 암복호화" 구조 — 이번 설계에서 반드시 유지해야 할 성질 |
| 키 로테이션 | 불가 (버전 정보 없음, 재기동 필요) | DEK 교체 시 기존 데이터 복호화 불가 → 마이그레이션 설계 필요 |

---

## 2. 목표 아키텍처 (To-Be)

### 2.1 키 계층
```
                         Vault KV-v2 (신뢰의 뿌리, 최상위)
                         ebiz_service/data/ebiz_db/kek   (마스터 키, KEK)
                                       │
                    KEK로 wrap/unwrap (AES-GCM key-wrap)
                                       │
        ┌──────────────────────────────┴───────────────────────────────┐
        ▼                                                               ▼
Vault KV-v2                                                    Vault KV-v2
ebiz_service/data/ebiz_db/dek/board                  ebiz_service/data/ebiz_db/dek/user-pii
(wrapped DEK, version=1)                             (wrapped DEK, version=1)
        │                                                               │
   앱 기동 시 1회: KEK로 unwrap → 평문 DEK를 도메인별로 메모리 캐시
        │                                                               │
        ▼                                                               ▼
BoardCryptoService                                        UserPiiCryptoService
  - board.password 암/복호화                                 - users.id_no 암/복호화
```

- **KEK**: Vault에만 존재하는 마스터 키. 데이터를 직접 암호화하지 않고, 오직 DEK를 감싸는(wrap) 용도로만 사용. 로테이션 빈도가 가장 낮음.
- **DEK**: 서비스 도메인(예: `board`, `user-pii`) 단위로 1개씩 존재. Vault에는 "KEK로 wrap된 형태"로만 저장되고, 앱이 기동할 때 KEK로 한 번 unwrap해서 **평문 DEK를 도메인별 Spring 싱글턴 빈(메모리)에 캐시**한다. 이후 모든 DML 시점의 암/복호화는 순수 로컬 연산이며 Vault 호출이 없다 — 요청하신 "성능을 위한 서비스 도메인 형태" 요구사항이 정확히 이 지점에서 충족된다.
- 도메인 경계는 최소 단위(컬럼)가 아니라 **민감도/업무 단위**로 묶는다. 초기 제안:
  - `board` 도메인: `board.password` (게시글 비밀글 비밀번호)
  - `user-pii` 도메인: `users.id_no` (주민등록번호) — 향후 연락처 등 개인정보 컬럼 추가 시 이 도메인에 편입
  - 새 도메인이 필요하면 Vault에 `dek/{domain}` 경로만 추가하면 되므로 확장이 쉬움.

### 2.2 암호문 포맷 (버전 정보 포함, 로테이션 대비)
현재: `Base64URL( IV(12B) + ciphertext + tag(16B) )`
변경 후:
```
Base64URL( domainCode(1B) | keyVersion(1B) | IV(12B) | ciphertext + tag(16B) )
```
- `keyVersion`을 헤더에 넣어야, DEK를 교체(로테이션)한 뒤에도 **옛 버전으로 암호화된 기존 데이터를 여전히 복호화**할 수 있다. 로테이션 직후에는 신규/기존 버전 DEK를 둘 다 메모리에 들고 있다가, 백필(재암호화) 마이그레이션이 끝나면 구버전을 폐기한다.
- `domainCode`는 방어적 무결성 체크용(다른 도메인 DEK로 잘못 복호화 시도하는 것을 즉시 차단)이며 필수는 아니지만 저비용으로 안전성을 높여준다.

### 2.3 DEK 저장/래핑 방식
- DEK는 32바이트 랜덤 값(`SecureRandom`)으로 최초 1회 생성한다.
- KEK로 DEK를 wrap: `AES/GCM/NoPadding`으로 DEK 자체를 암호화(현재 `VaultCryptoService.encrypt()`와 동일한 방식 재사용 가능) → 그 결과(wrapped DEK)를 Vault KV-v2에 저장한다.
- Vault 저장 값 예시(`ebiz_service/data/ebiz_db/dek/board`):
  ```
  wrapped-dek = "<Base64URL(IV+ciphertext+tag)>"
  version     = "1"
  algo        = "AES-256-GCM"
  created-at  = "2026-08-19T00:00:00Z"
  ```
- DB에 평문 DEK나 KEK가 저장되는 일은 없다 — Vault가 유일한 키 저장소라는 현재의 신뢰 모델을 그대로 유지한다.

---

## 3. 컴포넌트 설계

### 3.1 vault-crypto 라이브러리 (재사용 가능한 공용 모듈)
새 패키지 `com.xaan.vault.crypto.envelope` 아래에 추가:

| 클래스 | 역할 |
|---|---|
| `KekService` | Vault에서 KEK 원문 바이트를 로드 (현재 `VaultCryptoService.loadEncryptionKey()` 로직 이관) |
| `WrappedDek` | `(domain, version, wrappedBytes)` — Vault에 저장된 값의 역직렬화 표현 |
| `DekProvider` (interface) / `VaultDekProvider` | 도메인별 wrapped DEK를 Vault KV에서 읽고, 신규 버전 발급 시 씀 |
| `DomainKeyRing` | 도메인 1개에 대해 `Map<version, SecretKey>` 형태로 unwrap된 평문 DEK를 메모리에 보관. `currentVersion()` / `keyFor(version)` 제공 |
| `EnvelopeCryptoService` | 도메인 1개당 인스턴스 1개. `encrypt()`는 항상 `currentVersion` DEK 사용, `decrypt()`는 암호문 헤더의 `keyVersion`으로 `DomainKeyRing`에서 알맞은 DEK를 찾아 사용. `validate()`는 기존과 동일한 constant-time 비교 유지 |
| `DekRotationSupport` | 신규 DEK 생성 → KEK로 wrap → Vault에 새 버전 저장 (운영자가 호출하는 관리용 유틸) |

기존 `VaultCryptoService`는 vault-crypto `v0.0.3`에서 완전히 삭제했다(기존 소비 프로젝트는 무시하기로 결정). `CryptoException`, `KeyLoadingException`은 그대로 재사용.

### 3.2 demoApp 쪽 변경
- `CryptoConfig`(신규 `@Configuration`): 앱 기동 시 도메인별 `EnvelopeCryptoService` 빈을 생성한다.
  ```java
  @Bean
  public EnvelopeCryptoService boardCryptoService(KekService kek, DekProvider dekProvider) {
      return EnvelopeCryptoService.forDomain("board", kek, dekProvider);
  }

  @Bean
  public EnvelopeCryptoService userPiiCryptoService(KekService kek, DekProvider dekProvider) {
      return EnvelopeCryptoService.forDomain("user-pii", kek, dekProvider);
  }
  ```
  → 이 두 빈이 기동 시 각각 1회씩 unwrap을 수행하고, 이후에는 순수 로컬 캐시로 동작(요구사항인 "성능을 위한 도메인별 DEK" 충족).
- `PasswordService`: 내부적으로 단일 `VaultCryptoService` 대신 `boardCryptoService`, `userPiiCryptoService` 두 개만 주입받도록 변경(레거시 `VaultCryptoService` 의존성 없음). 메서드는 그대로 유지하되(`encryptBoardPassword` 등) 내부 위임 대상만 도메인별로 분리. `decrypt*`/`validate*`는 구 포맷 폴백 없이 도메인 서비스 결과를 그대로 반환하므로, 구 포맷 암호문을 넣으면 `decrypt*`는 예외를, `validate*`는 `false`를 반환한다(의도된 동작).
- `UserService.register()`: RRN 암호화 호출을 `passwordService.encryptBoardPassword(...)` 대신 신설할 `passwordService.encryptUserPii(...)`로 교체 — 앞서 지적한 네이밍 불일치(1.1절)를 이번 기회에 함께 정리.
- `VaultHealthIndicator`: KEK 경로뿐 아니라 두 도메인 DEK 경로도 health check에 포함할지 검토(선택 사항).

---

## 4. 단계별 구현 계획

| Phase | 상태 | 작업 내용 | 산출물 |
|---|---|---|---|
| **P0. Vault 준비** | ✅ **완료, v0.0.6 포맷으로 재실행 완료 (2026-08-20)** | KEK용 신규 경로 생성(`.../kek`), 도메인별 DEK 생성 후 KEK로 wrap하여 `.../dek/board`, `.../dek/user-pii`에 저장. 기존 `data-enc-key` 경로는 더 이상 필요 없음(마이그레이션 자체를 포기했으므로 유지할 이유 없음 — 삭제는 선택 사항) | 최초 실행은 2026-08-19(구 포맷, `kek` 단일 필드). v0.0.6에서 wrapped DEK 포맷이 바뀌면서(`kekVersion` 헤더 추가, KEK도 `kek-v1`+`current-version` 구조로 변경) 옛 시크릿이 호환되지 않게 되어, 갱신된 `bootstrap_kek_dek.py`로 2026-08-20에 재실행 — 사용자가 직접 프로덕션 Vault에 반영, 문제없이 완료 확인 |
| **P1. vault-crypto 라이브러리 확장** | ✅ 완료 | `envelope` 패키지 신설(3.1절 클래스들), 암호문 포맷에 `domainCode`+`keyVersion` 헤더 추가, 유닛테스트(wrap/unwrap 라운드트립, 도메인 격리, 버전 디스패치) | `vault-crypto:0.0.2`로 버전업 후 `mavenLocal`에 publish 완료. `AesGcmCodec`, `KekService`, `WrappedDek`, `DekProvider`/`VaultDekProvider`, `DomainKeyRing`, `EnvelopeCryptoService`, `DekRotationSupport` 및 `EnvelopeCryptoServiceTest`(4개 테스트, 전부 통과) |
| **P2. demoApp 배선** | ✅ 완료 | `CryptoConfig` 추가, `PasswordService`/`UserService`를 도메인별 서비스로 전환, RRN 메서드 네이밍 정리(`encryptBoardPassword` 재사용 → `encryptUserPii` 신설) | `CryptoConfig.java`(KEK/DEK/도메인별 `EnvelopeCryptoService` 빈만 존재, 레거시 빈 없음), `PasswordService`가 board/user-pii 도메인 서비스만 사용하도록 리팩터링(레거시 폴백 없음). `PasswordServiceTest` 갱신(구 포맷 암호문은 예외/`false`로 처리됨을 검증하는 테스트 포함, 3개 전부 통과) |
| ~~P3. 기존 데이터 마이그레이션~~ | ❌ **취소 (2026-08-19)** | ~~기존 `board.password`, `users.id_no` 값을 레거시 포맷으로 복호화 → 새 봉투 포맷으로 재암호화~~ — `users` 테이블은 통째로 삭제 예정이고 `board.password`의 기존 암호화 데이터는 그대로 무시하기로 결정. `EnvelopeMigrationService`/`EnvelopeMigrationRunner`와 관련 설정(`app.migration.envelope-encryption.enabled`)을 모두 삭제함 | (삭제됨) |
| **P4. 정리** | ✅ 완료 | 레거시 호환 코드 제거(`PasswordService`/`CryptoConfig`에서 `VaultCryptoService` 참조 제거) + vault-crypto 라이브러리에서 `VaultCryptoService` 클래스 자체 삭제(v0.0.3, breaking change, 기존 소비 프로젝트 무시하기로 결정). 남은 것: `VAULT_AND_ENCRYPTION.md` 등 구 문서 갱신 | vault-crypto `v0.0.3` — `VaultCryptoService.java` 삭제, README를 `EnvelopeCryptoService` 중심으로 재작성 |
| **P5. 로테이션 지원** | ✅ **핵심 기능 완료 (2026-08-20)**, 관리자 트리거 UI는 대기 | KEK 자체를 버전 관리(이전에는 KEK가 단일 값이라 로테이션 시 전체 장애 위험이 있었음 — 5절 참고) + DEK/KEK 재wrap 유틸 + 절차 문서화 | vault-crypto `v0.0.6` — `KekService` 버전 인식형으로 재작성, `KekProvider`/`VaultKekProvider`/`KekRotationSupport` 추가, `DekProvider`/`KekProvider`에 `retire(...)` 추가. 상세 절차 + 절차도: `KEY_ROTATION_RUNBOOK.md`. 관리자 엔드포인트/CLI로 이 유틸들을 트리거하는 부분만 아직 미구현(필요 시 런북의 "사전 준비" 절 참고해 임시 러너로 실행) |

> **버전 정렬 (2026-08-19)**: 위 표의 `vault-crypto:0.0.2`/`v0.0.3`은 각 단계가 실제로 완료된 시점의 버전을 기록한 것이다. 이후 사용자 요청으로 demoApp(`0.0.4 → 0.0.5`)과 vault-crypto(`0.0.3 → 0.0.5`, `0.0.4`는 건너뜀)의 버전 번호를 `0.0.5`로 통일했다 — 기능 변경은 없으며, 두 프로젝트 릴리스 번호를 맞추기 위한 순수 버전업이다. `build.gradle`(양쪽), `deploy.sh`/`deploy.bat`, 각 프로젝트 `README.md`에 반영 완료.

### 4.1 로테이션 동작 흐름 (P5, 2026-08-20 구현 완료)
1. 신규 DEK(32바이트) 생성 → 현재 KEK로 wrap → Vault에 `version+1`로 저장
2. 앱은 재기동(또는 갱신 트리거) 시 신규 버전을 `DomainKeyRing`에 추가 로드 — **기존 버전은 유지**(복호화용)
3. 신규 `encrypt()` 호출부터는 최신 버전 DEK 사용
4. 백그라운드 배치가 구버전으로 암호화된 행을 읽어 재암호화 → 최신 버전으로 수렴
5. 구버전 데이터가 0건이 되면 `DekProvider.retire(domain, oldVersion)`으로 Vault에서 제거
6. KEK 로테이션은 DEK 재wrap만 필요(데이터 재암호화 불필요) — 이것이 봉투 암호화의 핵심 이점이며, 지금처럼 데이터 컬럼이 늘어나도 KEK 로테이션 비용은 "도메인 수"에만 비례한다.

**KEK 로테이션 시 중요한 전제(2026-08-19에 발견한 갭, 2026-08-20에 해소)**: 원래 `KekService`는 KEK 하나만 평문으로 들고 있어서 버전 개념이 없었다 — 이 상태로 Vault의 `kek` 값을 바꾸면 모든 도메인의 모든 DEK가 동시에 unwrap 실패해 전체 장애로 이어졌다. `KekService`를 `DomainKeyRing`과 동일한 패턴(여러 버전을 메모리에 보관, wrapped DEK에 `kekVersion` 헤더를 내장)으로 재작성하고, `KekProvider`/`VaultKekProvider`/`KekRotationSupport`를 추가해 "신규 KEK 발급 → 도메인별 DEK 재wrap → 검증 → 구버전 폐기"를 안전하게 나눠서 수행할 수 있게 했다. 두 로테이션의 상세 절차와 절차도(시퀀스 다이어그램)는 `KEY_ROTATION_RUNBOOK.md`에 정리했다.

---

## 5. 리스크 및 검증 계획
- **기존 데이터 처리(마이그레이션 포기에 따른 영향)**: `board.password`의 구 포맷 암호문은 새 코드에서 더 이상 복호화되지 않는다 — `decryptBoardPassword()`는 `CryptoException`을, `validateBoardPassword()`는 `false`를 반환한다(둘 다 앱을 죽이지 않고 "검증 실패"로만 처리됨을 `PasswordServiceTest`로 확인함). 즉 기존 비밀글의 비밀번호 검증은 더 이상 통과하지 못한다 — 운영 관점에서 이 데이터를 어떻게 다룰지(비밀번호 재설정 안내, 강제 초기화 등)는 별도 결정이 필요하면 그때 진행한다. `users` 테이블은 삭제되므로 RRN 쪽은 해당 없음.
- **성능 회귀 방지**: 도메인 빈 생성 시 Vault 호출은 "KEK 1회 + 도메인당 DEK 1회"로 고정(도메인 수만큼만 증가) — 요청/DML 트래픽과 무관. 부하 테스트로 기존 대비 지연 변화 없음을 확인.
- **키 유출 범위 축소 검증**: `board` DEK로 `user-pii` 암호문을 복호화 시도 시 반드시 실패(인증 태그 불일치 또는 domainCode 불일치)하는지 유닛테스트로 고정.
- **Vault ACL**: 가능하면 KEK 경로와 DEK 경로에 대한 read 정책을 분리해, 앱 토큰 탈취 시에도 "wrap/unwrap 조합 없이는 평문 DEK를 못 얻는" 구조를 Vault Policy 레벨에서도 강제할 것을 권장(선택 사항, 별도 운영 작업).

---

## 6. 요약
- 현재는 KEK/DEK 구분이 없는 **단일 평문 키** 구조이며, 도메인 간 키 공유 + 로테이션 불가 + 버전 정보 부재가 핵심 갭이다.
- 목표는 **Vault의 KEK가 도메인별 DEK를 wrap**하고, **각 도메인 DEK는 앱 기동 시 1회 unwrap되어 메모리에 캐시**되는 구조로, 지금과 동일하게 "DML 시점에는 Vault 호출 없음"이라는 성능 특성을 유지하면서 키를 분리한다.
- 구현은 vault-crypto 라이브러리 확장(P1) → demoApp 배선(P2) 순으로 진행했고, **기존 암호화 데이터 마이그레이션(P3)과 구 포맷 하위호환은 2026-08-19 결정으로 포기**했다(`users` 테이블 삭제 예정, `board`의 기존 암호화 데이터는 무시). P0(Vault에 KEK/DEK 실제 생성)와 P5의 핵심 로테이션 기능(KEK 버전 관리, `KekRotationSupport`, 런북)까지 2026-08-20 기준 완료됐다. 남은 건 로테이션을 트리거하는 관리자 UI/CLI(선택 사항)뿐이다.
