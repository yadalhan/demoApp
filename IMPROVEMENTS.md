# Improvement Backlog — demoApp & vault-crypto

> **Last updated: 2026-10-31** (demoApp `0.0.19`, vault-crypto `0.0.11`).
> 이 문서는 2026-05-08의 단일 키 시절 권고안을 **현행 구조 기준으로 재정리**한 것이다.
> 이미 해결된 항목은 §3에 이력으로 남겨둔다.

## 1. 현재 상태 요약

| 영역 | 상태 |
|---|---|
| 양방향 암호화 | AES-256-GCM **KEK-DEK 봉투**(도메인별 DEK, 기동 시 1회 unwrap 후 메모리 캐시) |
| 로그인 비밀번호 | BCrypt 단방향 (`PasswordHasher`) |
| 암호화 컬럼 검색 | 필드별 HMAC-SHA256 blind index (정확 일치) |
| 키 로테이션 | DEK: 운영 검증 완료 / KEK: 라이브러리 API만 존재, 트리거 없음 |
| 레거시 데이터 | `board.password` 약 46,266행 구 포맷 — **정책 미정(복호화 불가)** |
| 자격증명 | `application.properties` gitignored, 문서/스크립트에 실제 키 제거(2026-10-31) |

## 2. 미해결 항목

### P0 — 보안/운영 안전

1. **Vault TLS + AppRole 인증**
   - 현재 `http://192.168.2.57:8200` **평문 HTTP** + 정적 Token.
   - 권고: TLS 활성화, AppRole(role_id/secret_id)로 전환, Token TTL/갱신 정책 수립.
   - 영향: 토큰은 `.gitignore`로 분리되어 있으나 네트워크 구간이 암호화되지 않음.

2. **구 단일 키(`fernet-key`) 폐기**
   - 2026-10-31에 문서·스크립트에서 실제 키 값을 제거했지만, Vault의
     `ebiz_db/data-enc-key` secret 자체가 남아 있을 수 있다.
   - 앱은 더 이상 이 경로를 읽지 않는다 → **Vault에서 삭제하고, 키를 폐기(rotate/폐기 처리)할 것.**
   - 공개 저장소 이력에 남아 있던 값이므로 "이미 노출된 키"로 간주하는 것이 안전하다.

3. **레거시 `board.password` 정책 확정** (별도 검토 항목)
   - 약 46,266행이 구 포맷이라 비밀번호 검증이 항상 실패한다.
   - 선택지: (a) 영구 무시(현행), (b) 해당 글 비밀번호 초기화/재설정 안내, (c) 운영자 도구로
     구 키를 이용한 일회성 복호화 후 재암호화(구 키가 Vault에 남아 있어야 가능).
   - 결정 전까지는 "비밀번호가 있는 오래된 글은 열 수 없다"는 운영 공지가 필요하다.

4. **기동 시 Vault 의존성 문서/운영 절차 명확화**
   - `fail-fast=false`여도 KEK/DEK/blind-index 빈이 eager로 Vault를 읽으므로 **sealed/미도달 시 기동 실패**.
   - 배포 전 `vault status` 확인을 체크리스트에 포함(과거 sealed 상태에서 재기동해 장애가 발생한 이력 있음).

### P1 — 키 관리 완결성

5. **KEK 로테이션 운영 트리거 부재**
   - `KekRotationSupport.issueNewKekVersion()` / `rewrapDomainDeks()`는 라이브러리에 있으나
     이를 실행할 CLI/엔드포인트/배치가 없다(현재는 코드 작성 또는 임시 러너 필요).
   - `retire()` 역시 수동 코드 호출만 가능 → 운영 도구화 필요.

6. **Blind index 키 로테이션 도구 부재**
   - 키를 바꾸려면 전 행 재인덱싱이 필요하나 이를 수행할 배치가 없다.
   - `id_no_blind_idx`는 기존 사용자 행에 대해 **백필 배치도 없다**(검색이 기존 행을 못 찾음).

7. **재암호화 배치의 확장성**
   - `DekReencryptionService`는 전체 행을 한 트랜잭션으로 처리한다(`findAllByOrderByIdDesc`).
   - 대량 데이터에서 메모리/락 이슈가 될 수 있으므로 PK 범위 기반 청크 처리로 전환 권장.

### P2 — 코드/테스트

8. **REST API의 비밀번호 검사 부재**
   - `GET /api/v1/posts/{id}`는 비밀번호 보호 글도 본문을 그대로 반환한다(웹 UI와 다른 경로).
   - 인증/권한 정책을 정하고 최소한 상세 조회는 웹과 동일한 규칙 적용 필요.

9. **테스트 커버리지 확대**
   - 현재 단위 테스트: `PasswordServiceTest`(6), `UserResponseDtoTest`(4), vault-crypto 21개.
   - `contextLoads()`는 Vault/DB가 필요해 로컬에서 실행 불가 → Testcontainers 또는 Vault dev server
     기반 통합 테스트 도입 검토.
   - Redis 캐시(`/users2`) 동작, TypeHandler 등록 범위 회귀 테스트는 라이브러리 쪽에만 존재.

10. **암호화된 컬럼용 복호화 도구 현행화**
    - 기존 `decrypt_passwords.py`는 구 단일 키 포맷 전용이었다.
    - 2026-10-31에 KEK/DEK unwrap + 봉투 포맷을 지원하도록 갱신(Vault 키 로드, `--self-test` 포함).
    - `users.phone`/`id_no`의 봉투 포맷 검증에도 사용 가능.

11. **관측성(metrics/logging)**
    - 암/복호화 실패 건수, 재암호화 배치 진행률, 키 버전 분포를 메트릭으로 노출 권장.
    - 현재는 로그 기반(`decryptUserPiiForDisplay` warn, 배치 결과 로그)만 존재.

### P3 — 저장소 위생

12. **vault-crypto의 커밋된 빌드 산출물 제거** — ✅ 2026-10-31 완료
    - `build/`, `.gradle/` 26개 파일이 추적되고 있었다. `git rm --cached`로 제거(`.gitignore`는 이미 커버).
13. **문서 드리프트 해소** — ✅ 2026-10-31 완료
    - `VAULT_AND_ENCRYPTION.md`, `VAULT_INTEGRATION_DIAGRAM.md`를 현행 구조로 재작성.
    - `PASSWORD_ENCRYPTION_MIGRATION.md`, `VAULT_INTEGRATION_PRESENTATION.md`는 역사 기록으로 명시.
    - `decrypt_passwords.py`에서 하드코딩된 키 제거, `regenerate_markdown.py`(구 경로로 옛 문서를
      덮어쓰는 일회성 생성기) 삭제.

## 3. 해결된 항목 (이력)

| 항목 | 해결 시점 | 내용 |
|---|---|---|
| 하드코딩 Vault 토큰 | 2026-08-20 | `.gitignore` 분리, 환경변수화, git history 스크럽, 토큰/DB 비밀번호 회전 |
| ECB → GCM | 2026-05-08 | `AES/GCM/NoPadding`, 랜덤 IV, 태그 검증 |
| 커스텀 예외 계층 | 2026-05-08 | `CryptoException` / `KeyLoadingException` |
| Vault 경로 설정화 | 2026-05-08 | `vault.kek.path`, `vault.dek.base-path`, `vault.blind-index.base-path` |
| 상수 시간 비교 | 2026-05-08 | `MessageDigest.isEqual` |
| Vault health indicator | 2026-05-08 | `VaultHealthIndicator` (actuator `/actuator/health`) |
| 키 버전 관리 | 2026-08-20 | KEK/DEK 모두 `*-v{n}` + `current-version`, 암호문 헤더에 버전 기록 |
| KEK/DEK 로테이션 지원 | 2026-08-20 | `KekRotationSupport`, `DekRotationSupport`, 재암호화 배치, `dek_ops_batch.sh/.bat` |
| MyBatis TypeHandler 전역 오염 | 2026-08-29 | `TypeHandler<String>` 직접 구현으로 수정(vault-crypto `0.0.10`), 회귀 테스트 추가 |
| 평문 PII의 Redis 유입 | 2026-08-29 | raw ciphertext만 캐싱(`UserSearchCacheService`), 복호화는 캐시 이후 |
| 레거시 단일 키 사용 중단 | 2026-08-19 | `VaultCryptoService` 삭제, `data-enc-key` 경로 미사용 |

## 4. 참고

- 설계/이력: [KEK_DEK_ENCRYPTION_PLAN.md](KEK_DEK_ENCRYPTION_PLAN.md)
- 운영 절차: [KEY_ROTATION_RUNBOOK.md](KEY_ROTATION_RUNBOOK.md)
- 조회 규칙: [ENCRYPTED_COLUMN_QUERY_GUIDE.md](ENCRYPTED_COLUMN_QUERY_GUIDE.md)
- 현행 구조: [VAULT_AND_ENCRYPTION.md](VAULT_AND_ENCRYPTION.md), [VAULT_INTEGRATION_DIAGRAM.md](VAULT_INTEGRATION_DIAGRAM.md)
