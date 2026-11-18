#!/usr/bin/env bash
# =====================================================================
# demoApp OpenBao 키 구성/이전 스크립트 (bao CLI 전용, python/jq 불필요)
#
#   demoApp이 쓰는 키 세트를 새 OpenBao 서버에 만든다.
#     transit/keys/<KEK>                          : KEK (aes256-gcm96, 비반출)
#     <MOUNT>/data/<BASE>/dek/{board,user-pii}    : wrapped DEK (dek-v{n}, current-version)
#     <MOUNT>/data/<BASE>/blind-index/{user-phone,user-rrn} : HMAC 키 (Base64URL 32B)
#     policy/token (선택)
#
#   서브커맨드
#     show     현재 키 구성 조회 (읽기 전용)
#     create   새 키 생성 (빈 데이터/신규 배포용)
#     migrate  기존 서버의 DEK/BI 키를 새 서버로 이전 (데이터 유지)
#              - 옛 서버에서 DEK 평문을 꺼내 새 KEK로 다시 wrap (버전 번호 유지)
#              - blind index 키는 값을 그대로 복사 (새로 만들면 기존 인덱스와 불일치)
#     verify   모든 DEK 버전의 wrap/unwrap round-trip 검증
#
#   공통 옵션
#     --addr URL        새 OpenBao 주소 (기본 $BAO_ADDR)
#     --token TOKEN     새 OpenBao 토큰 (기본 $BAO_TOKEN)
#     --mount NAME      kv-v2 마운트 (기본 ebiz_service)
#     --base PATH       기본 경로 (기본 ebiz_db)
#     --transit-mount   (기본 transit)
#     --transit-key     KEK 이름 (기본 kek)
#     --domains         (기본 board,user-pii)
#     --blind-indexes   (기본 user-phone,user-rrn)
#     --apply           실제 반영 (없으면 계획만 출력하는 dry-run)
#     --force           이미 키가 있어도 덮어쓴다 (데이터 유실 위험)
#     --old-addr/--old-token   migrate 원본 서버 (기본 $OLD_BAO_ADDR/$OLD_BAO_TOKEN)
#     --old-mount/--old-base/--old-transit-key   원본의 경로가 새 서버와 다를 때 지정
#                                                (기본값은 각각 --mount/--base/--transit-key)
#     --policy NAME     create 시 정책 이름 (기본 demoapp-read, 빈 값이면 생략)
#     --token-name      create 시 토큰 display_name (기본 token-demoapp)
#     --token-ttl       (기본 768h)
#     --token-out PATH  create 시 앱 토큰을 저장할 파일 (기본 ./demoapp-app-token, 600)
#
#   예)
#     ./openbao_keys.sh show
#     ./openbao_keys.sh create  --addr http://10.0.0.5:28200 --token <root> --apply
#     OLD_BAO_ADDR=http://192.168.2.57:28200 OLD_BAO_TOKEN=<old-root> \
#       ./openbao_keys.sh migrate --addr http://10.0.0.5:28200 --token <new-root> --apply
# =====================================================================
set -euo pipefail

MOUNT="ebiz_service"
BASE="ebiz_db"
TRANSIT_MOUNT="transit"
TRANSIT_KEY="kek"
DOMAINS="board,user-pii"
BLIND_INDEXES="user-phone,user-rrn"
ADDR="${BAO_ADDR:-}"
TOKEN="${BAO_TOKEN:-}"
OLD_ADDR="${OLD_BAO_ADDR:-}"
OLD_TOKEN="${OLD_BAO_TOKEN:-}"
OLD_MOUNT=""
OLD_BASE=""
OLD_TRANSIT_MOUNT=""
OLD_TRANSIT_KEY=""
APPLY=0
FORCE=0
POLICY="demoapp-read"
TOKEN_NAME="token-demoapp"
TOKEN_TTL="768h"
TOKEN_OUT="./demoapp-app-token"

die() { echo "FAIL: $*" >&2; exit 1; }
info() { echo "-- $*"; }
ok() { echo "OK  : $*"; }

usage() { sed -n '2,40p' "$0" | sed 's/^# \{0,1\}//'; exit 1; }

CMD="${1:-}"
[ -n "$CMD" ] || usage
shift || true

while [ $# -gt 0 ]; do
  case "$1" in
    --addr) ADDR="$2"; shift 2 ;;
    --token) TOKEN="$2"; shift 2 ;;
    --mount) MOUNT="$2"; shift 2 ;;
    --base) BASE="$2"; shift 2 ;;
    --transit-mount) TRANSIT_MOUNT="$2"; shift 2 ;;
    --transit-key) TRANSIT_KEY="$2"; shift 2 ;;
    --domains) DOMAINS="$2"; shift 2 ;;
    --blind-indexes) BLIND_INDEXES="$2"; shift 2 ;;
    --old-addr) OLD_ADDR="$2"; shift 2 ;;
    --old-token) OLD_TOKEN="$2"; shift 2 ;;
    --old-mount) OLD_MOUNT="$2"; shift 2 ;;
    --old-base) OLD_BASE="$2"; shift 2 ;;
    --old-transit-mount) OLD_TRANSIT_MOUNT="$2"; shift 2 ;;
    --old-transit-key) OLD_TRANSIT_KEY="$2"; shift 2 ;;
    --policy) POLICY="$2"; shift 2 ;;
    --token-name) TOKEN_NAME="$2"; shift 2 ;;
    --token-ttl) TOKEN_TTL="$2"; shift 2 ;;
    --token-out) TOKEN_OUT="$2"; shift 2 ;;
    --apply) APPLY=1; shift ;;
    --force) FORCE=1; shift ;;
    -h|--help) usage ;;
    *) die "알 수 없는 옵션: $1" ;;
  esac
done

# ---------------------------------------------------------------- helpers
b64u() { base64 -w0 | tr '+/' '-_' | tr -d '='; }
b64u_dec() {
  local s pad
  s="$(tr '_-' '/+')"
  s="${s//$'\n'/}"
  pad=$(( (4 - ${#s} % 4) % 4 ))
  while [ "$pad" -gt 0 ]; do s="${s}="; pad=$((pad - 1)); done
  printf '%s' "$s" | base64 -d
}
b64len() { base64 -d | wc -c | tr -d ' '; }          # 표준 base64(패딩 있음)
b64u_len() { b64u_dec | wc -c | tr -d ' '; }         # Base64URL(패딩 없음)

new_bao() { BAO_ADDR="$ADDR" BAO_TOKEN="$TOKEN" bao "$@"; }
old_bao() { BAO_ADDR="$OLD_ADDR" BAO_TOKEN="$OLD_TOKEN" bao "$@"; }

OLD_MOUNT="${OLD_MOUNT:-$MOUNT}"
OLD_BASE="${OLD_BASE:-$BASE}"
OLD_TRANSIT_MOUNT="${OLD_TRANSIT_MOUNT:-$TRANSIT_MOUNT}"
OLD_TRANSIT_KEY="${OLD_TRANSIT_KEY:-$TRANSIT_KEY}"

kek_path() { echo "$TRANSIT_MOUNT/keys/$TRANSIT_KEY"; }
dek_path() { echo "$BASE/dek/$1"; }
bi_path() { echo "$BASE/blind-index/$1"; }

kv_exists() { new_bao kv get -mount="$MOUNT" "$1" >/dev/null 2>&1; }
kek_exists() { new_bao read -field=latest_version "$(kek_path)" >/dev/null 2>&1; }

require_new_conn() {
  [ -n "$ADDR" ] || die "--addr 또는 BAO_ADDR 가 필요합니다"
  [ -n "$TOKEN" ] || die "--token 또는 BAO_TOKEN 이 필요합니다"
  new_bao token lookup >/dev/null 2>&1 \
    || die "새 OpenBao 토큰/주소 확인 실패: $ADDR"
  info "새 OpenBao: $ADDR (mount=$MOUNT, base=$BASE, transit=$(kek_path))"
}

require_old_conn() {
  [ -n "$OLD_ADDR" ] || die "migrate 에는 --old-addr 또는 OLD_BAO_ADDR 이 필요합니다"
  [ -n "$OLD_TOKEN" ] || die "migrate 에는 --old-token 또는 OLD_BAO_TOKEN 이 필요합니다"
  old_bao token lookup >/dev/null 2>&1 \
    || die "원본 OpenBao 토큰/주소 확인 실패: $OLD_ADDR"
  info "원본 OpenBao: $OLD_ADDR (mount=$OLD_MOUNT, base=$OLD_BASE, transit=$OLD_TRANSIT_MOUNT/keys/$OLD_TRANSIT_KEY)"
}

# 연결된 서버의 (도메인/인덱스/버전) 목록을 출력한다.
show_remote() {
  local label=$1 addr=$2 token=$3
  local d cur v f len
  echo "== $label ($addr) =="
  if BAO_ADDR="$addr" BAO_TOKEN="$token" bao read -field=latest_version \
        "$TRANSIT_MOUNT/keys/$TRANSIT_KEY" >/dev/null 2>&1; then
    echo "  KEK $(kek_path): latest_version=$(BAO_ADDR="$addr" BAO_TOKEN="$token" bao read -field=latest_version "$TRANSIT_MOUNT/keys/$TRANSIT_KEY")" \
         "min_decryption=$(BAO_ADDR="$addr" BAO_TOKEN="$token" bao read -field=min_decryption_version "$TRANSIT_MOUNT/keys/$TRANSIT_KEY")"
  else
    echo "  KEK $(kek_path): 없음"
  fi
  for d in $(echo "$DOMAINS" | tr ',' ' '); do
    if BAO_ADDR="$addr" BAO_TOKEN="$token" bao kv get -mount="$MOUNT" "$(dek_path "$d")" >/dev/null 2>&1; then
      cur=$(BAO_ADDR="$addr" BAO_TOKEN="$token" bao kv get -field=current-version -mount="$MOUNT" "$(dek_path "$d")")
      printf '  dek/%s: current-version=%s, fields=' "$d" "$cur"
      for v in $(seq 1 "$cur"); do
        f=$(BAO_ADDR="$addr" BAO_TOKEN="$token" bao kv get -field="dek-v$v" -mount="$MOUNT" "$(dek_path "$d")")
        printf 'dek-v%s(%sB wrapped) ' "$v" "${#f}"
      done
      echo
    else
      echo "  dek/$d: 없음"
    fi
  done
  for d in $(echo "$BLIND_INDEXES" | tr ',' ' '); do
    if BAO_ADDR="$addr" BAO_TOKEN="$token" bao kv get -mount="$MOUNT" "$(bi_path "$d")" >/dev/null 2>&1; then
      len=$(BAO_ADDR="$addr" BAO_TOKEN="$token" bao kv get -field=key -mount="$MOUNT" "$(bi_path "$d")" | b64u_len)
      echo "  blind-index/$d: key ${len}B"
    else
      echo "  blind-index/$d: 없음"
    fi
  done
}

mkdir_kv_mount_if_absent() {
  if new_bao secrets list | awk '{print $1}' | grep -qx "$MOUNT/"; then
    info "kv-v2 마운트 있음: $MOUNT/"
  else
    [ "$APPLY" = 1 ] || { info "[dry-run] bao secrets enable -path=$MOUNT kv-v2"; return 0; }
    new_bao secrets enable -path="$MOUNT" kv-v2 >/dev/null
    ok "kv-v2 마운트 생성: $MOUNT/"
  fi
}

mkdir_transit_mount_if_absent() {
  if new_bao secrets list | awk '{print $1}' | grep -qx "$TRANSIT_MOUNT/"; then
    info "transit 마운트 있음: $TRANSIT_MOUNT/"
  else
    [ "$APPLY" = 1 ] || { info "[dry-run] bao secrets enable -path=$TRANSIT_MOUNT transit"; return 0; }
    new_bao secrets enable -path="$TRANSIT_MOUNT" transit >/dev/null
    ok "transit 마운트 생성: $TRANSIT_MOUNT/"
  fi
}

ensure_kek() {
  if kek_exists; then
    info "KEK 있음: $(kek_path) (latest_version=$(new_bao read -field=latest_version "$(kek_path)"))"
    return 0
  fi
  [ "$APPLY" = 1 ] || { info "[dry-run] bao write $(kek_path) type=aes256-gcm96 exportable=false allow_plaintext_backup=false"; return 0; }
  new_bao write "$(kek_path)" type=aes256-gcm96 exportable=false allow_plaintext_backup=false >/dev/null
  ok "KEK 생성: $(kek_path) (latest_version=$(new_bao read -field=latest_version "$(kek_path)"))"
}

# 주어진 평문 DEK(base64)를 새 KEK로 wrap해서 KV 필드 형식(Base64URL)으로 반환
wrap_field() {
  local plain=$1 ct
  ct=$(new_bao write -field=ciphertext "transit/encrypt/$TRANSIT_KEY" plaintext="$plain")
  [ -n "$ct" ] || die "transit/encrypt 응답이 비었습니다"
  printf '%s' "$ct" | b64u
}

# KV 필드 값 -> ciphertext 문자열
field_to_ciphertext() { printf '%s' "$1" | b64u_dec; }

# ---------------------------------------------------------------- commands
cmd_show() {
  [ -n "$ADDR" ] || die "--addr 또는 BAO_ADDR 가 필요합니다"
  [ -n "$TOKEN" ] || die "--token 또는 BAO_TOKEN 이 필요합니다"
  show_remote "새 OpenBao" "$ADDR" "$TOKEN"
}

cmd_create() {
  require_new_conn
  local d v plain field
  [ "$APPLY" = 1 ] || echo "*** dry-run: 실제 반영은 --apply ***"

  if [ "$FORCE" != 1 ]; then
    for d in $(echo "$DOMAINS" | tr ',' ' '); do
      kv_exists "$(dek_path "$d")" && die "이미 dek 키가 있습니다: $(dek_path "$d") (덮어쓰려면 --force)"
    done
  fi

  mkdir_kv_mount_if_absent
  mkdir_transit_mount_if_absent
  ensure_kek

  for d in $(echo "$DOMAINS" | tr ',' ' '); do
    [ "$APPLY" = 1 ] || { info "[dry-run] dek/$d: 새 DEK 32B 생성 → transit/encrypt → Base64URL → dek-v1 + current-version=1"; continue; }
    plain=$(head -c32 /dev/urandom | base64 -w0)
    field=$(wrap_field "$plain")
    new_bao kv put -mount="$MOUNT" "$(dek_path "$d")" "dek-v1=$field" current-version=1 >/dev/null
    ok "DEK 생성: $(dek_path "$d") (dek-v1, current-version=1)"
  done

  for d in $(echo "$BLIND_INDEXES" | tr ',' ' '); do
    [ "$APPLY" = 1 ] || { info "[dry-run] blind-index/$d: 랜덤 32B → Base64URL 43자 저장"; continue; }
    new_bao kv put -mount="$MOUNT" "$(bi_path "$d")" key="$(head -c32 /dev/urandom | b64u)" >/dev/null
    ok "blind-index 키 생성: $(bi_path "$d")"
  done

  if [ -n "$POLICY" ]; then
    [ "$APPLY" = 1 ] || { info "[dry-run] policy write $POLICY (dek/BI read + transit/decrypt update)"; return 0; }
    new_bao policy write "$POLICY" - >/dev/null <<HCL
path "$MOUNT/data/$BASE/dek/*"         { capabilities = ["read"] }
path "$MOUNT/data/$BASE/blind-index/*" { capabilities = ["read"] }
path "$TRANSIT_MOUNT/decrypt/$TRANSIT_KEY" { capabilities = ["update"] }
path "auth/token/lookup-self"          { capabilities = ["read"] }
HCL
    ok "정책 등록: $POLICY"
    ( umask 077; new_bao token create -policy="$POLICY" -display-name="$TOKEN_NAME" \
        -ttl="$TOKEN_TTL" -renewable=true -field=token > "$TOKEN_OUT" )
    ok "앱 토큰 발급 → $TOKEN_OUT (권한 600, 값은 화면에 출력하지 않음)"
  fi

  cmd_verify
}

cmd_migrate() {
  require_new_conn
  require_old_conn
  local d i v cur field ct plain newfield args k oldlen newlen
  [ "$APPLY" = 1 ] || echo "*** dry-run: 실제 반영은 --apply ***"

  if [ "$FORCE" != 1 ]; then
    for d in $(echo "$DOMAINS" | tr ',' ' '); do
      kv_exists "$(dek_path "$d")" && die "새 서버에 이미 dek 키가 있습니다: $(dek_path "$d") (덮어쓰려면 --force)"
    done
  fi

  mkdir_kv_mount_if_absent
  mkdir_transit_mount_if_absent
  ensure_kek

  # 1) DEK: 옛 서버 ciphertext → 복호화(평문) → 새 KEK로 wrap → 같은 버전으로 저장
  for d in $(echo "$DOMAINS" | tr ',' ' '); do
    cur=$(old_bao kv get -field=current-version -mount="$OLD_MOUNT" "$OLD_BASE/dek/$d") \
      || die "원본에 $OLD_BASE/dek/$d 가 없습니다"
    args=()
    for v in $(seq 1 "$cur"); do
      field=$(old_bao kv get -field="dek-v$v" -mount="$OLD_MOUNT" "$OLD_BASE/dek/$d")
      [ -n "$field" ] || die "원본 dek-v$v 값이 비었습니다 ($d)"
      ct=$(field_to_ciphertext "$field")
      plain=$(old_bao write -field=plaintext "$OLD_TRANSIT_MOUNT/decrypt/$OLD_TRANSIT_KEY" ciphertext="$ct")
      [ -n "$plain" ] || die "원본 dek-v$v 복호화 실패 ($d)"
      oldlen=$(printf '%s' "$plain" | b64len)
      [ "$oldlen" = 32 ] || die "원본 DEK 길이가 32B가 아닙니다: $d v$v ($oldlen)"
      newfield=$(wrap_field "$plain")
      # 새 서버에서 실제로 풀리는지 즉시 확인
      newlen=$(field_to_ciphertext "$newfield" | { read -r c; new_bao write -field=plaintext "$TRANSIT_MOUNT/decrypt/$TRANSIT_KEY" ciphertext="$c"; } | b64len)
      [ "$newlen" = 32 ] || die "새 KEK wrap 검증 실패: $d v$v"
      args+=("dek-v$v=$newfield")
      info "dek/$d: dek-v$v 이전 (32B round-trip OK)"
    done
    if [ "$APPLY" = 1 ]; then
      new_bao kv put -mount="$MOUNT" "$(dek_path "$d")" "${args[@]}" "current-version=$cur" >/dev/null
      ok "dek/$d 저장 완료 (dek-v1..dek-v$cur, current-version=$cur)"
    else
      info "[dry-run] kv put $(dek_path "$d") dek-v1..dek-v$cur current-version=$cur"
    fi
  done

  # 2) blind index: 새로 만들지 않고 값만 복사 (기존 인덱스 컬럼과 일치해야 함)
  for i in $(echo "$BLIND_INDEXES" | tr ',' ' '); do
    if new_bao kv get -mount="$MOUNT" "$(bi_path "$i")" >/dev/null 2>&1 && [ "$FORCE" != 1 ]; then
      info "blind-index/$i: 이미 있음 (건너뜀, 덮어쓰려면 --force)"
      continue
    fi
    k=$(old_bao kv get -field=key -mount="$OLD_MOUNT" "$OLD_BASE/blind-index/$i") \
      || die "원본에 $OLD_BASE/blind-index/$i 가 없습니다"
    [ ${#k} = 43 ] || die "원본 blind-index/$i 키 길이가 43자가 아닙니다 (${#k})"
    if [ "$APPLY" = 1 ]; then
      new_bao kv put -mount="$MOUNT" "$(bi_path "$i")" key="$k" >/dev/null
      ok "blind-index/$i 복사 완료 (값 동일)"
    else
      info "[dry-run] kv put $(bi_path "$i") key=<원본과 동일>"
    fi
  done

  if [ "$APPLY" = 1 ]; then cmd_verify; fi
}

cmd_verify() {
  require_new_conn
  local d v cur field ct plain len okcnt=0 failcnt=0
  for d in $(echo "$DOMAINS" | tr ',' ' '); do
    cur=$(new_bao kv get -field=current-version -mount="$MOUNT" "$(dek_path "$d")") \
      || die "$(dek_path "$d") 를 읽을 수 없습니다"
    for v in $(seq 1 "$cur"); do
      field=$(new_bao kv get -field="dek-v$v" -mount="$MOUNT" "$(dek_path "$d")")
      if printf '%s' "$(field_to_ciphertext "$field")" | grep -q '^vault:'; then
        ct=$(field_to_ciphertext "$field")
        plain=$(new_bao write -field=plaintext "$TRANSIT_MOUNT/decrypt/$TRANSIT_KEY" ciphertext="$ct" 2>/dev/null || true)
        len=$(printf '%s' "$plain" | base64 -d 2>/dev/null | wc -c | tr -d ' ')
        if [ "$len" = 32 ]; then
          ok "dek/$d dek-v$v: Base64URL(transit ciphertext) + decrypt 32B"
          okcnt=$((okcnt + 1))
        else
          echo "  FAIL: dek/$d dek-v$v decrypt 길이=${len:-0}"; failcnt=$((failcnt + 1))
        fi
      else
        echo "  FAIL: dek/$d dek-v$v 값이 Base64URL(transit ciphertext) 형식이 아님"; failcnt=$((failcnt + 1))
      fi
    done
  done
  for d in $(echo "$BLIND_INDEXES" | tr ',' ' '); do
    if new_bao kv get -mount="$MOUNT" "$(bi_path "$d")" >/dev/null 2>&1; then
      len=$(new_bao kv get -field=key -mount="$MOUNT" "$(bi_path "$d")" | b64u_len)
      if [ "$len" = 32 ]; then ok "blind-index/$d: 32B 키"; okcnt=$((okcnt + 1));
      else echo "  FAIL: blind-index/$d 키 길이 ${len}B"; failcnt=$((failcnt + 1)); fi
    else
      echo "  FAIL: blind-index/$d 없음"; failcnt=$((failcnt + 1))
    fi
  done
  echo "== 검증 결과: OK $okcnt / FAIL $failcnt"
  [ "$failcnt" = 0 ] || exit 1
}

case "$CMD" in
  show) cmd_show ;;
  create) cmd_create ;;
  migrate) cmd_migrate ;;
  verify) cmd_verify ;;
  *) usage ;;
esac
