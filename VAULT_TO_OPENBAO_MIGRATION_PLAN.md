# HashiCorp Vault → OpenBao 2.6.2 전환 계획

> **상태: 완료 (실행 2026-11-08).** 계획 작성 2026-10-31, 운영자가 실행 완료.
> 대상: demoApp 운영 KMS. 애플리케이션 코드/DB 스키마 변경 없이 **키 저장소만 교체**했다.
> 실행 결과와 잔여 항목은 **§12** 참고.

## 0. 한 줄 요약

Vault(`192.168.2.57:8200`, kv-v2 `ebiz_service`)의 KEK/DEK/blind-index 키를
**바이트 그대로** OpenBao(`192.168.2.57:28200`, kv-v2 `ebiz_service`)로 복사하고,
demoApp 두 인스턴스(`.56` → `.57` 순서)의 `spring.cloud.vault.uri`/`token`만 바꿔 재기동했다.
DB의 암호문은 그대로 두었고, **soak 기간에는 키 로테이션을 금지**한다(롤백 보장).

---

## 1. 현황

### 1.1 Source — HashiCorp Vault (전환 전)

| 항목 | 값 |
|---|---|
| 주소 | `http://192.168.2.57:8200` (HTTP, TLS 없음) |
| 버전 / 스토리지 | Vault `1.19.5`, `file` storage, Shamir, unsealed |
| 마운트 | `ebiz_service/` (kv-v2) — 그 외 `kv/`, `identity/`, `cubbyhole/`, `sys/` |
| 사용 시크릿 | `ebiz_db/kek`, `ebiz_db/dek/board`, `ebiz_db/dek/user-pii`, `ebiz_db/blind-index/user-phone`, `ebiz_db/blind-index/user-rrn` |
| 레거시 시크릿 | `ebiz_db/data-enc-key` — 앱 코드는 더 이상 읽지 않지만 **Spring Cloud Vault property source로 여전히 조회됨**(§3.3) |
| 앱 토큰 | 현재 **root 정책, TTL 0** (개선 필요) |

시크릿 필드 구조(복사 시 그대로 보존해야 함):

```
ebiz_db/kek                      : kek-v{n}, current-version
ebiz_db/dek/{board,user-pii}     : dek-v{n}, current-version
ebiz_db/blind-index/user-phone   : key
ebiz_db/blind-index/user-rrn     : key
ebiz_db/data-enc-key             : fernet-key (레거시, property source 용)
```

### 1.2 Consumer — demoApp

| 인스턴스 | 주소 | 현재 |
|---|---|---|
| 운영 A | `192.168.2.56:8080` | 0.0.19, Vault 사용 중 |
| 운영 B | `192.168.2.57:8080` | 0.0.19, Vault 사용 중 |

- 라이브러리: Spring Cloud Vault `2024.0.0` / `spring-vault-core 3.1.0`
- 설정(서버별 `app/application.properties`, gitignored):
  ```properties
  spring.cloud.vault.uri=http://192.168.2.57:8200
  spring.cloud.vault.token=<token>
  spring.cloud.vault.fail-fast=true
  spring.cloud.vault.kv.enabled=true
  spring.cloud.vault.kv.backend=ebiz_service
  spring.cloud.vault.kv.application-name=ebiz_db/data-enc-key
  spring.cloud.vault.kv.version=2
  vault.kek.path=${VAULT_KEK_PATH:ebiz_service/data/ebiz_db/kek}
  vault.dek.base-path=${VAULT_DEK_BASE_PATH:.../dek}
  vault.blind-index.base-path=${VAULT_BLIND_INDEX_BASE_PATH:.../blind-index}
  ```
- 기동 시 KEK/DEK/blind-index를 **eager**로 읽는다 → KMS가 unsealed/도달 불가면 **기동 실패**.
  이미 기동된 프로세스는 DEK를 메모리에 캐시하고 있어 KMS 장애에도 계속 동작한다.

### 1.3 Target — OpenBao (준비됨)

| 항목 | 값 |
|---|---|
| 주소(호스트) | `http://192.168.2.57:28200` → 컨테이너 `8200` (cluster `28201`) |
| 버전 | `2.6.2` (OpenBao, Vault 1.x API 호환) |
| 상태 | initialized, **unsealed**, Shamir `3/5`, raft 단일 노드 |
| 스토리지 | named volume `openbao_data` → `/openbao/file` (영속), `openbao_logs` |
| 설정 | `/home/xaan/ws/openbao/config/openbao.hcl` — `tls_disable=true`, `ui=true`, `api_addr=http://127.0.0.1:8200` |
| 컨테이너 | `openbao-openbao-1`, `restart: unless-stopped`, `mem_limit 512m`, healthcheck `/v1/sys/health` |
| 현재 마운트 | 로그상 builtin(`cubbyhole`, `token`)만 확인됨 → **`ebiz_service` kv-v2 마운트 없음(추정, §3.4에서 확인)** |

> OpenBao API(`/v1/sys/health`, `/v1/sys/seal-status`)는 정상 응답하며, `vault` CLI(1.19.5)로도
> 대부분의 kv-v2 API를 사용할 수 있다. 다만 **공식 작업은 컨테이너 내 `bao` CLI**를 기준으로 한다.

---

## 2. 전환 원칙

1. **키 재생성 금지.** KEK/DEK/HMAC 키를 새로 만들면 기존 DB 암호문(board 약 206만 행 중 비밀번호 보유 13건, users 4행)을
   복호화할 수 없다. Vault의 시크릿 **필드 값을 그대로** OpenBao에 복사한다.
2. **DB 변경 없음.** 암호문 포맷/버전 헤더/`current-version`을 그대로 유지한다.
3. **2단계 컷오버.** 위험도가 낮은 `.56`을 먼저 전환·검증한 뒤 `.57`을 전환한다.
4. **즉시 롤백 가능 상태 유지.** Vault는 soak 기간 동안 그대로 운영(읽기 전용 취급)한다.
5. **키 로테이션 동결.** 마이그레이션 시작 ~ soak 종료까지 `rotate`/`reencrypt`/`retire` 금지.
   (OpenBao에만 새 DEK가 생기면 Vault로 롤백했을 때 신규 데이터 복호화 불가)
6. **최소 권한 토큰.** app용 토큰은 read-only, 운영자(회전)용은 별도 write 토큰.
7. **시크릿 평문 노출 금지.** 복사/검증 로그에 키 값을 출력하지 않는다(체크섬만 비교).

---

## 3. 사전 준비 (P0)

### 3.1 OpenBao 운영 전제 확인

- [ ] **Unseal 키 3/5 보관 위치/책임자** 확정. 컨테이너 재시작·호스트 재부팅 후 **수동 unseal**이 필요하다
      (`restart: unless-stopped`는 자동 unseal을 해주지 않음).
- [ ] unseal 절차 리허설(스크래치 컨테이너 또는 유지보수 창) 및 문서화.
- [ ] `docker.service` 자동 시작 + 컨테이너 재기동 정책 확인.
- [ ] raft 스냅샷 저장 위치/주기 결정(로컬 + 오프호스트).
- [ ] 디스크/메모리 모니터링. 현재 `mem_limit 512m` — 부하 테스트 후 상향 검토.
- [ ] NTP/시간 동기화 확인(토큰/lease/raft).
- [ ] (권장, 후속) TLS 활성화 및 방화벽 제한. 현재 Vault/OpenBao 모두 HTTP.
- [ ] (권장) file audit device 활성화 + 로그 로테이션.
- [ ] OpenBao root 토큰 보관(초기화 산출물). **앱에 root 토큰을 쓰지 않는다.**

### 3.2 백업

```bash
# 1) Vault 시크릿 논리 백업(값 포함 → 암호화 보관, 예: age/gpg)
export VAULT_ADDR=http://192.168.2.57:8200
export VAULT_TOKEN=<vault-root-token>
mkdir -p /secure/kms-backup/$(date +%F)
for p in ebiz_db/kek ebiz_db/data-enc-key ebiz_db/dek/board ebiz_db/dek/user-pii \
         ebiz_db/blind-index/user-phone ebiz_db/blind-index/user-rrn; do
  vault kv get -format=json -mount=ebiz_service "$p" \
    | jq -S '.data.data' > "/secure/kms-backup/$(date +%F)/$(echo "$p" | tr / _).json"
done

# 2) Vault file storage 백업(선택, 전체 복구용)
sudo tar czf /secure/kms-backup/vault-file-$(date +%F).tgz <vault-data-dir>

# 3) OpenBao raft 스냅샷(전환 직전 + 전환 후)
docker exec -e BAO_TOKEN=<openbao-root-token> openbao-openbao-1 \
  bao operator raft snapshot save /openbao/file/pre-migration.snap
docker cp openbao-openbao-1:/openbao/file/pre-migration.snap /secure/kms-backup/
```

> 백업 파일에는 평문 KEK/DEK/HMAC 키가 들어 있다. 접근 제어·암호화·보관 기간을 반드시 정한다.

### 3.3 Spring Cloud Vault 호환성 스모크 테스트 (중요)

현재 `spring.cloud.vault.kv.enabled=true` + `application-name=ebiz_db/data-enc-key` 설정 때문에
앱은 기동 시 이 경로도 property source로 읽는다. 따라서 **`data-enc-key`도 복사**하거나,
전환과 동시에 해당 설정을 정리해야 한다(§6.2).

OpenBao에서 아래를 먼저 확인한다.

```bash
export BAO_ADDR=http://192.168.2.57:28200
export BAO_TOKEN=<openbao-root-token>

# health / seal
curl -s $BAO_ADDR/v1/sys/health
curl -s $BAO_ADDR/v1/sys/seal-status

# kv-v2 쓰기/읽기
bao kv put -mount=ebiz_service tmp-compat foo=bar
bao kv get -mount=ebiz_service tmp-compat
bao kv delete -mount=ebiz_service tmp-compat

# Spring Cloud Vault가 참조할 수 있는 엔드포인트
curl -s -H "X-Vault-Token: $BAO_TOKEN" $BAO_ADDR/v1/auth/token/lookup-self | head -c 200; echo
curl -s -H "X-Vault-Token: $BAO_TOKEN" $BAO_ADDR/v1/sys/internal/ui/mounts/ebiz_service/data/ebiz_db/kek; echo
```

- [ ] `sys/internal/ui/mounts`가 정상 응답하거나, 최소한 `spring.cloud.vault.kv.version=2`로
      버전 자동탐지 없이 동작함을 확인.
- [ ] `auth/token/lookup-self` 정상(Spring Vault 세션 관리).
- [ ] 애플리케이션과 동일한 클라이언트 버전으로 `.56`에서 **사전 리허설**(§5.1) 수행.

### 3.4 kv-v2 마운트 및 정책/토큰 생성

```bash
export BAO_ADDR=http://192.168.2.57:28200
export BAO_TOKEN=<openbao-root-token>

# 마운트가 없으면 생성 (이미 있으면 skip)
bao secrets enable -path=ebiz_service kv-v2

# 앱용 read-only 정책
cat > /tmp/demoapp-read.hcl <<'HCL'
path "ebiz_service/data/ebiz_db/kek"              { capabilities = ["read"] }
path "ebiz_service/data/ebiz_db/data-enc-key"     { capabilities = ["read"] }
path "ebiz_service/data/ebiz_db/dek/*"            { capabilities = ["read"] }
path "ebiz_service/data/ebiz_db/blind-index/*"    { capabilities = ["read"] }
path "auth/token/lookup-self"                     { capabilities = ["read"] }
HCL
bao policy write demoapp-read /tmp/demoapp-read.hcl
bao token create -policy=demoapp-read -display-name=demoapp -ttl=0 -field=token
```

- [ ] app 토큰 발급(위 출력). **root 토큰 사용 금지.**
- [ ] 운영자(회전)용 write 정책/토큰은 별도 발급(앱에 주입하지 않음).

---

## 4. 데이터 이관 (P1)

### 4.1 대상

| # | 경로 | 필드 | 비고 |
|---|---|---|---|
| 1 | `ebiz_db/kek` | `kek-v*`, `current-version` | 마스터 키 |
| 2 | `ebiz_db/dek/board` | `dek-v*`, `current-version` | board 도메인 |
| 3 | `ebiz_db/dek/user-pii` | `dek-v*`, `current-version` | users PII 도메인 |
| 4 | `ebiz_db/blind-index/user-phone` | `key` | 검색용 HMAC |
| 5 | `ebiz_db/blind-index/user-rrn` | `key` | 검색용 HMAC |
| 6 | `ebiz_db/data-enc-key` | `fernet-key` | **레거시**. property source 호환용으로 우선 복사 |

### 4.2 복사 절차 (값 그대로)

```bash
SRC=http://192.168.2.57:8200
DST=http://192.168.2.57:28200
SRC_TOKEN=<vault-root-token>
DST_TOKEN=<openbao-root-token>
WORK=/secure/kms-migrate; mkdir -p "$WORK"

PATHS="ebiz_db/kek ebiz_db/data-enc-key ebiz_db/dek/board ebiz_db/dek/user-pii \
       ebiz_db/blind-index/user-phone ebiz_db/blind-index/user-rrn"

for p in $PATHS; do
  f="$WORK/$(echo "$p" | tr / _).json"

  # 1) source → JSON (data.data만)
  VAULT_ADDR=$SRC VAULT_TOKEN=$SRC_TOKEN \
    vault kv get -format=json -mount=ebiz_service "$p" | jq -S '.data.data' > "$f"

  # 2) 필드 단위 인자로 변환해 target에 기록 (base64url/숫자 문자열이라 공백 없음)
  mapfile -t args < <(jq -r 'to_entries[] | "\(.key)=\(.value)"' "$f")
  VAULT_ADDR=$DST VAULT_TOKEN=$DST_TOKEN \
    vault kv put -mount=ebiz_service "$p" "${args[@]}"

  # 3) 체크섬 비교
  a=$(sha256sum <(jq -S -c . "$f") | cut -d' ' -f1)
  b=$(VAULT_ADDR=$DST VAULT_TOKEN=$DST_TOKEN \
      vault kv get -format=json -mount=ebiz_service "$p" \
      | jq -S -c '.data.data' | sha256sum | cut -d' ' -f1)
  [ "$a" = "$b" ] && echo "OK   $p" || echo "FAIL $p"
done
```

> 값(키)은 출력하지 않는다. 필요 시 `vault kv metadata get`으로 `max_versions` 등 마운트 설정도 맞춘다.

### 4.3 검증 (Go/No-Go 1차)

- [ ] 6개 경로 모두 `OK` (필드/값 완전 일치)
- [ ] `current-version`이 Vault와 동일 (`board` v7, `user-pii` v2 등 실제 값 기준)
- [ ] `kek-v*`/`dek-v*` 필드 개수 일치
- [ ] OpenBao raft 스냅샷 저장 완료
- [ ] 복사 로그/파일에 키 평문이 남지 않았는지 확인 후 `$WORK` 삭제 또는 암호화 보관

---

## 5. 애플리케이션 전환 (P2)

### 5.1 1차: `.56` (운영 A)

1. 설정 백업: `cp application.properties application.properties.bak-preopenbao-$(date +%F)`
2. 아래 2줄 변경:
   ```properties
   spring.cloud.vault.uri=http://192.168.2.57:28200
   spring.cloud.vault.token=<openbao demoapp-read 토큰>
   ```
3. 재기동 및 검증(아래 매트릭스).
4. **30분 이상** 관찰. 오류/지연 이상 없으면 `.57`로 진행.

### 5.2 2차: `.57` (운영 B)

`.56`과 동일 절차. `.56`/`.57`은 동일 DB를 보므로, 한쪽에서 쓴 데이터는 다른 쪽에서도
같은 키로 복호화되어야 한다(교차 확인).

### 5.3 검증 매트릭스

| # | 항목 | 방법 | 기대 |
|---|---|---|---|
| 1 | 기동 | 로그 | `Started DemoApplication` + `PasswordService initialized … KEK-DEK …` |
| 2 | 키 로딩 | 로그 | `KeyLoadingException` 없음 |
| 3 | 헬스 | `GET /actuator/health` | `vault: UP`, `db: UP`, `redis: UP` |
| 4 | 기본 페이지 | `/hello`, `/login`, `/last100` | 200 |
| 5 | 사용자 PII 복호화 | 로그인 후 `/users`, `/users2` | 마스킹된 전화/RRN 정상 표시, `(복호화 실패)` 없음 |
| 6 | 게시글 비밀번호 | `/posts/{id}` → popup → 정답 | 본문 표시 |
| 7 | Blind index 검색 | `/users?phone=...`, `?residentRegistrationNumber=...` | 정확 일치 1건 |
| 8 | 신규 암호화 | 게시글 1건 생성 후 DB 확인 | `board.password`가 `AQ…` envelope, 평문 아님 |
| 9 | 교차 인스턴스 | `.56`에서 만든 글을 `.57`에서 열기 | 정상 |
| 10 | 재기동 내성 | 앱 재시작 | OpenBao에서 키 재로딩 성공 |
| 11 | KMS 장애 내성(선택) | OpenBao 컨테이너 정지 후 **이미 기동된** 앱 | 기존 데이터 조회/복호화 지속(캐시), 신규 기동은 실패(예상) |

### 5.4 전환 명령 예시 (`.56`)

```bash
ssh xaan@192.168.2.56
cd /home/xaan/ws/demoBBS
cp app/application.properties app/application.properties.bak-preopenbao-$(date +%F)
sed -i 's#^spring.cloud.vault.uri=.*#spring.cloud.vault.uri=http://192.168.2.57:28200#' app/application.properties
# token은 편집기로 교체(셸 히스토리에 남기지 않도록)
vi app/application.properties
bash stopapp.sh && bash startapp.sh
tail -f log/demoBBS-$(date +%F).log
```

---

## 6. 롤백 계획

### 6.1 조건

- 기동 실패, 5xx/복호화 오류, 검색/로그인 이상, 성능 저하가 지속될 때 **즉시** 롤백.

### 6.2 절차

1. 백업한 `application.properties.bak-preopenbao-*` 복원(URI/토큰 원복).
2. `stopapp.sh` → `startapp.sh`.
3. `/actuator/health`, 로그, 핵심 기능 재확인.
4. 원인 분석 후 재시도 여부 결정.

> **전제**: OpenBao에서 키 로테이션을 하지 않았어야 한다. 로테이션을 했다면 Vault에 없는
> 새 `dek-v*`가 생겨 신규 데이터가 Vault로 복호화되지 않는다 → soak 종료 전 로테이션 금지.

### 6.3 롤백 후 정리

- OpenBao 쪽에 쓴 신규 데이터가 있는지 확인(없어야 정상).
- 원인(호환성/토큰/네트워크/설정) 기록.

---

## 7. 컷오버 후 안정화 (P3)

- [ ] **Soak 1~2주**: 두 인스턴스 모두 OpenBao 사용, 오류/지연/재기동 모니터링.
- [ ] 일일 raft 스냅샷 + 오프호스트 보관, 복구 리허설 1회.
- [ ] OpenBao 재시작/호스트 재부팅 시 **unseal 절차** 실제 훈련.
- [x] app 토큰 최소권한 — `demoapp-read`(root 아님) 적용. 주기적 교체 절차는 잔여 항목.
- [ ] audit device 활성화(권장), 로그 로테이션.
- [ ] `data-enc-key` 정리(아래 6.2) — 앱 설정에서 kv property source 제거 후 양쪽 KMS에서 삭제.
- [x] 문서/스크립트의 KMS 주소 현행화 — 2026-11-08 완료
      (`AGENTS.md`, `README.md`, `VAULT_AND_ENCRYPTION.md`, `VAULT_INTEGRATION_DIAGRAM.md`, `KEY_ROTATION_RUNBOOK.md`, `decrypt_passwords.py` 기본값).
- [ ] (선택) TLS 적용: OpenBao `tls_disable=false` + 인증서, 앱 URI `https://`.

### 7.1 레거시 `data-enc-key` 정리 (별도 승인 후)

현재 앱은 이 시크릿을 property source로만 읽고 실제 암호화에는 쓰지 않는다.

1. 두 서버 `application.properties`에서 아래 3줄 제거(또는 `spring.cloud.vault.kv.enabled=false`):
   ```properties
   spring.cloud.vault.kv.enabled=true
   spring.cloud.vault.kv.backend=ebiz_service
   spring.cloud.vault.kv.application-name=ebiz_db/data-enc-key
   spring.cloud.vault.kv.version=2
   ```
2. 재기동/검증.
3. Vault/OpenBao 양쪽에서 `ebiz_db/data-enc-key` 삭제:
   ```bash
   vault kv metadata delete -mount=ebiz_service ebiz_db/data-enc-key   # 양쪽 각각
   ```
   (이 키는 과거 공개 저장소 이력에 노출된 값이므로 삭제 권장)

---

## 8. Vault 폐기 (P4)

Soak 종료 + Go 판정 후:

1. Vault를 **읽기 전용**으로 전환(쓰기 차단) 후 1주일 관찰.
2. 최종 백업(§3.2) 및 보관 기간 확정(예: 90일, 암호화/오프호스트).
3. Vault 프로세스/컨테이너 중지, 자동 시작 해제.
4. root 토큰 및 기존 app 토큰 폐기(`vault token revoke`).
5. `data-enc-key` 등 레거시 시크릿 삭제 확인.
6. 문서/대시보드/모니터링에서 Vault 참조 제거.

---

## 9. 리스크 및 대응

| # | 리스크 | 영향 | 대응 |
|---|---|---|---|
| 1 | Unseal 키 분실/미보관 | 재기동 불가 → 전면 장애 | 3/5 키 분산 보관, 리허설, 접근 통제 |
| 2 | 단일 노드 raft (HA 없음) | OpenBao 다운 시 신규 기동 불가 | 스냅샷, 유지보수 창, 추후 3-node 검토 |
| 3 | HTTP(비 TLS) + 토큰 파일 저장 | 토큰/키 노출 | 네트워크 제한, TLS 도입, 파일 권한 |
| 4 | `mem_limit 512m` | OOM/재시작 | 모니터링, 상향, 부하 테스트 |
| 5 | Spring Vault ↔ OpenBao API 차이 | 기동 실패 | §3.3 스모크 + `.56` 선행 리허설, 롤백 |
| 6 | `data-enc-key` 미복사 | property source 조회 실패로 기동 실패 가능 | 우선 복사(§4), 이후 정리(§7.1) |
| 7 | soak 중 키 로테이션 | 롤백 시 신규 데이터 복호화 불가 | 로테이션 동결 공지/권한 분리 |
| 8 | 복사 누락/필드 변형 | 일부 데이터 복호화 불가 | 6개 경로 체크섬 비교(§4.3) |
| 9 | root 토큰 사용 | 권한 과다 | app 토큰 read-only, 운영자 토큰 분리 |
| 10 | 컨테이너 재시작 후 unseal 미수행 | 기동 실패 | 재부팅 절차/체크리스트, 알림 |
| 11 | 시간 오차 | 토큰/lease 오류 | NTP 확인 |
| 12 | 감사 로그 부재 | 침해 추적 불가 | audit device 활성화 |

---

## 10. 일정(예시) · 역할

| 단계 | 예상 소요 | 담당 |
|---|---|---|
| P0 사전점검/백업/정책 | 1시간 | 운영자(+검토) |
| P1 시크릿 복사/검증 | 30분 | 운영자 |
| P2 `.56` 전환/검증 | 30분 | 운영자 |
| P2 `.57` 전환/검증 | 30분 | 운영자 |
| P3 soak/모니터링 | 1~2주 | 운영자 |
| P4 Vault 폐기 | 1시간 | 운영자 |

권장 시간대: 사용량이 적은 시간. Unseal 키 보유자와 롤백 결정권자를 동일 창에 대기.

---

## 11. 실행 체크리스트 (복붙용)

```
[ ] OpenBao unsealed / raft 정상 / unseal 키 3개 접근 가능
[ ] Vault 시크릿 논리 백업 + Vault file storage 백업
[ ] OpenBao raft 스냅샷 저장
[ ] Spring Cloud Vault 호환성 스모크(health, token lookup, kv read/write)
[ ] ebiz_service kv-v2 마운트 확인/생성
[ ] demoapp-read 정책 + app 토큰 발급
[ ] 6개 경로 복사 + 경로별 체크섬 OK
[ ] current-version/필드 개수 일치
[ ] .56 application.properties 백업/URI·토큰 변경/재기동
[ ] .56 검증 매트릭스 통과(로그/health/login/users/board 검증/검색)
[ ] .56 30분 관찰
[ ] .57 동일 절차/검증
[ ] 교차 인스턴스 검증
[ ] 롤백 절차/백업 설정 재확인
[ ] 로테이션 동결 공지
[ ] soak 모니터링 시작
```

---

## 12. 실행 결과 및 잔여 항목

### 12.1 확인된 최종 상태 (2026-11-08)

| 항목 | 결과 |
|---|---|
| OpenBao | `2.6.2`, raft, Shamir 3/5, **unsealed**, `http://192.168.2.57:28200` (컨테이너 `openbao-openbao-1`) |
| kv-v2 마운트 | `ebiz_service` 생성/사용 |
| 이관 시크릿 | `kek`(`kek-v1`, current 1), `dek/board`(`dek-v1..v7`, current 7), `dek/user-pii`(`dek-v1..v2`, current 2), `blind-index/user-phone`(`key`), `blind-index/user-rrn`(`key`), `data-enc-key`(`fernet-key`, 레거시 property source용) |
| 앱 토큰 | `token-demoapp`, policies `[default demoapp-read]`, TTL `768h`, renewable — root 토큰 미사용 |
| 앱 설정 | 두 인스턴스 모두 `spring.cloud.vault.uri=http://192.168.2.57:28200` |
| 재기동 | `.56`: 2026-11-07 23:56, `.57`: 2026-11-08 00:09 — 양쪽 모두 `Started DemoApplication` + `PasswordService initialized …` |
| 헬스 | 두 인스턴스 `/actuator/health` = `UP`, `vault: UP`, `db: UP` |
| 이전 KMS | Vault `1.19.5` `:8200` — **중지됨**(soak/롤백용 보존) |
| DB 암호문 | 2026-11-08 기준: `board.password` 비어있지 않은 15건 전부 envelope(v5~v7), `users` 5건 BCrypt + `id_no`/`phone` envelope v2, **구 포맷 0건** |
| 컷오버 후 쓰기 | 컷오버 이후 생성된 게시글 2건·사용자 1건이 OpenBao 키로 정상 암호화됨 |

### 12.2 잔여 항목

1. **Soak + Vault 폐기**: 1~2주 관찰 후 Vault 최종 백업 → 중지/토큰 폐기(§8).
   soak 종료 전 **키 로테이션 금지**.
2. **레거시 `data-enc-key` 정리**: `spring.cloud.vault.kv.*` 설정 제거 → 재기동/검증 →
   OpenBao(및 보관 중인 Vault)에서 시크릿 삭제(§7.1).
3. **TLS**: OpenBao `tls_disable=false` + 인증서, 앱 URI `https://` 전환.
4. **Audit device** 활성화 및 로그 로테이션.
5. **Unseal 키 3/5 보관·책임자 지정**, 재시작/재부팅 unseal 리허설 문서화.
6. **raft 스냅샷** 주기/오프호스트 보관/복구 리허설.
7. **토큰 TTL/교체 정책** 확정(현재 768h renewable).
8. 문서/스크립트의 KMS 주소 현행화 — 이번 커밋에서 반영(AGENTS/README/VAULT_* 문서/`decrypt_passwords.py`).

### 12.3 실행 전 미확정이었던 항목의 결정

| 항목 | 결정/현황 |
|---|---|
| 토큰 TTL | 768h renewable (`demoapp-read`) |
| TLS | 이번 전환에는 미포함 — 잔여 항목 |
| 마운트 존재 | 신규 생성/사용 (`ebiz_service`) |
| 백업/보관 | 운영자 수행 — 보관 위치·기간은 잔여 확인 항목 |
| soak 기간 | 미정 — Vault 폐기 전 결정 필요 |
