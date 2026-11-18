#!/bin/sh
# =====================================================================
# demoApp OpenBao 키 구성/이전 스크립트 (POSIX sh + bao CLI, python/jq 불필요)
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
#     --old-mount/--old-base/--old-transit-key   원본 경로가 새 서버와 다를 때 지정
#                                                (기본값은 각각 --mount/--base/--transit-key)
#     --policy NAME     create 시 정책 이름 (기본 demoapp-read, 빈 값이면 생략)
#     --token-name      create 시 토큰 display_name (기본 token-demoapp)
#     --token-ttl       (기본 768h)
#     --token-out PATH  create 시 앱 토큰을 저장할 파일 (기본 ./demoapp-app-token, 600)
#
#   예)
#     sh ./openbao_keys.sh show
#     sh ./openbao_keys.sh create  --addr http://10.0.0.5:28200 --token <root> --apply
#     OLD_BAO_ADDR=http://192.168.2.57:28200 OLD_BAO_TOKEN=<old-root> \
#       sh ./openbao_keys.sh migrate --addr http://10.0.0.5:28200 --token <new-root> --apply
# =====================================================================
set -eu

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

usage() { sed -n '2,45p' "$0" | sed 's/^# \{0,1\}//'; exit 1; }

CMD="${1:-}"
[ -n "$CMD" ] || usage
shift

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

[ -n "$OLD_MOUNT" ] || OLD_MOUNT="$MOUNT"
[ -n "$OLD_BASE" ] || OLD_BASE="$BASE"
[ -n "$OLD_TRANSIT_MOUNT" ] || OLD_TRANSIT_MOUNT="$TRANSIT_MOUNT"
[ -n "$OLD_TRANSIT_KEY" ] || OLD_TRANSIT_KEY="$TRANSIT_KEY"

# ---------------------------------------------------------------- helpers
# 32바이트 난수 (raw bytes)
random32() { dd if=/dev/urandom bs=32 count=1 2>/dev/null; }
# stdin 바이트 -> 표준 base64(한 줄)
b64std() { base64 | tr -d '\n'; }
# stdin 바이트 -> Base64URL(패딩 없음)
b64u() { base64 | tr -d '\n' | tr '+/' '-_' | tr -d '='; }
# Base64URL -> 원본 바이트
b64u_dec() {
  _u=$(tr '_-' '/+')
  _u=$(printf '%s' "$_u" | tr -d '\n')
  _pad=$(( (4 - ${#_u} % 4) % 4 ))
  while [ "$_pad" -gt 0 ]; do _u="${_u}="; _pad=$((_pad - 1)); done
  printf '%s' "$_u" | base64 -d
}
b64len() { base64 -d | wc -c | tr -d ' '; }
b64u_len() { b64u_dec | wc -c | tr -d ' '; }

new_bao() { BAO_ADDR="$ADDR" BAO_TOKEN="$TOKEN" bao "$@"; }
old_bao() { BAO_ADDR="$OLD_ADDR" BAO_TOKEN="$OLD_TOKEN" bao "$@"; }

kek_path() { echo "$TRANSIT_MOUNT/keys/$TRANSIT_KEY"; }
dek_path() { echo "$BASE/dek/$1"; }
bi_path() { echo "$BASE/blind-index/$1"; }

kv_exists() { new_bao kv get -mount="$MOUNT" "$1" >/dev/null 2>&1; }
kek_exists() { new_bao read -field=latest_version "$(kek_path)" >/dev/null 2>&1; }

require_new_conn() {
  [ -n "$ADDR" ] || die "--addr 또는 BAO_ADDR 가 필요합니다"
  [ -n "$TOKEN" ] || die "--token 또는 BAO_TOKEN 이 필요합니다"
  new_bao token lookup >/dev/null 2>&1 || die "새 OpenBao 토큰/주소 확인 실패: $ADDR"
  info "새 OpenBao: $ADDR (mount=$MOUNT, base=$BASE, transit=$(kek_path))"
}

require_old_conn() {
  [ -n "$OLD_ADDR" ] || die "migrate 에는 --old-addr 또는 OLD_BAO_ADDR 이 필요합니다"
  [ -n "$OLD_TOKEN" ] || die "migrate 에는 --old-token 또는 OLD_BAO_TOKEN 이 필요합니다"
  old_bao token lookup >/dev/null 2>&1 || die "원본 OpenBao 토큰/주소 확인 실패: $OLD_ADDR"
  info "원본 OpenBao: $OLD_ADDR (mount=$OLD_MOUNT, base=$OLD_BASE, transit=$OLD_TRANSIT_MOUNT/keys/$OLD_TRANSIT_KEY)"
}

show_remote() {
  _label=$1; _addr=$2; _tok=$3
  echo "== $_label ($_addr) =="
  if BAO_ADDR="$_addr" BAO_TOKEN="$_tok" bao read -field=latest_version \
        "$TRANSIT_MOUNT/keys/$TRANSIT_KEY" >/dev/null 2>&1; then
    _lk=$(BAO_ADDR="$_addr" BAO_TOKEN="$_tok" bao read -field=latest_version "$TRANSIT_MOUNT/keys/$TRANSIT_KEY")
    _md=$(BAO_ADDR="$_addr" BAO_TOKEN="$_tok" bao read -field=min_decryption_version "$TRANSIT_MOUNT/keys/$TRANSIT_KEY")
    echo "  KEK $(kek_path): latest_version=$_lk min_decryption=$_md"
  else
    echo "  KEK $(kek_path): 없음"
  fi
  for _d in $(echo "$DOMAINS" | tr ',' ' '); do
    if BAO_ADDR="$_addr" BAO_TOKEN="$_tok" bao kv get -mount="$MOUNT" "$(dek_path "$_d")" >/dev/null 2>&1; then
      _cur=$(BAO_ADDR="$_addr" BAO_TOKEN="$_tok" bao kv get -field=current-version -mount="$MOUNT" "$(dek_path "$_d")")
      printf '  dek/%s: current-version=%s, fields=' "$_d" "$_cur"
      _v=1
      while [ "$_v" -le "$_cur" ]; do
        _f=$(BAO_ADDR="$_addr" BAO_TOKEN="$_tok" bao kv get -field="dek-v$_v" -mount="$MOUNT" "$(dek_path "$_d")")
        printf 'dek-v%s(%sB wrapped) ' "$_v" "${#_f}"
        _v=$((_v + 1))
      done
      echo
    else
      echo "  dek/$_d: 없음"
    fi
  done
  for _d in $(echo "$BLIND_INDEXES" | tr ',' ' '); do
    if BAO_ADDR="$_addr" BAO_TOKEN="$_tok" bao kv get -mount="$MOUNT" "$(bi_path "$_d")" >/dev/null 2>&1; then
      _len=$(BAO_ADDR="$_addr" BAO_TOKEN="$_tok" bao kv get -field=key -mount="$MOUNT" "$(bi_path "$_d")" | b64u_len)
      echo "  blind-index/$_d: key ${_len}B"
    else
      echo "  blind-index/$_d: 없음"
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

# 평문 DEK(base64)를 새 KEK로 wrap해서 KV 필드 형식(Base64URL)으로 반환
wrap_field() {
  _ct=$(new_bao write -field=ciphertext "transit/encrypt/$TRANSIT_KEY" plaintext="$1")
  [ -n "$_ct" ] || die "transit/encrypt 응답이 비었습니다"
  printf '%s' "$_ct" | b64u
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
  [ "$APPLY" = 1 ] || echo "*** dry-run: 실제 반영은 --apply ***"

  if [ "$FORCE" != 1 ]; then
    for _d in $(echo "$DOMAINS" | tr ',' ' '); do
      if kv_exists "$(dek_path "$_d")"; then
        die "이미 dek 키가 있습니다: $(dek_path "$_d") (덮어쓰려면 --force)"
      fi
    done
  fi

  mkdir_kv_mount_if_absent
  mkdir_transit_mount_if_absent
  ensure_kek

  for _d in $(echo "$DOMAINS" | tr ',' ' '); do
    if [ "$APPLY" != 1 ]; then
      info "[dry-run] dek/$_d: 새 DEK 32B 생성 → transit/encrypt → Base64URL → dek-v1 + current-version=1"
      continue
    fi
    _field=$(wrap_field "$(random32 | b64std)")
    new_bao kv put -mount="$MOUNT" "$(dek_path "$_d")" "dek-v1=$_field" current-version=1 >/dev/null
    ok "DEK 생성: $(dek_path "$_d") (dek-v1, current-version=1)"
  done

  for _d in $(echo "$BLIND_INDEXES" | tr ',' ' '); do
    if [ "$APPLY" != 1 ]; then
      info "[dry-run] blind-index/$_d: 랜덤 32B → Base64URL 43자 저장"
      continue
    fi
    new_bao kv put -mount="$MOUNT" "$(bi_path "$_d")" key="$(random32 | b64u)" >/dev/null
    ok "blind-index 키 생성: $(bi_path "$_d")"
  done

  if [ -n "$POLICY" ]; then
    if [ "$APPLY" != 1 ]; then
      info "[dry-run] policy write $POLICY (dek/BI read + transit/decrypt update)"
      return 0
    fi
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
  [ "$APPLY" = 1 ] || echo "*** dry-run: 실제 반영은 --apply ***"

  if [ "$FORCE" != 1 ]; then
    for _d in $(echo "$DOMAINS" | tr ',' ' '); do
      if kv_exists "$(dek_path "$_d")"; then
        die "새 서버에 이미 dek 키가 있습니다: $(dek_path "$_d") (덮어쓰려면 --force)"
      fi
    done
  fi

  mkdir_kv_mount_if_absent
  mkdir_transit_mount_if_absent
  ensure_kek

  # 1) DEK: 옛 서버 ciphertext → 복호화(평문) → 새 KEK로 wrap → 같은 버전으로 저장
  for _d in $(echo "$DOMAINS" | tr ',' ' '); do
    if ! _cur=$(old_bao kv get -field=current-version -mount="$OLD_MOUNT" "$OLD_BASE/dek/$_d"); then
      die "원본에 $OLD_BASE/dek/$_d 가 없습니다"
    fi
    set --
    _v=1
    while [ "$_v" -le "$_cur" ]; do
      _field=$(old_bao kv get -field="dek-v$_v" -mount="$OLD_MOUNT" "$OLD_BASE/dek/$_d")
      [ -n "$_field" ] || die "원본 dek-v$_v 값이 비었습니다 ($_d)"
      _ct=$(field_to_ciphertext "$_field")
      _plain=$(old_bao write -field=plaintext "$OLD_TRANSIT_MOUNT/decrypt/$OLD_TRANSIT_KEY" ciphertext="$_ct")
      [ -n "$_plain" ] || die "원본 dek-v$_v 복호화 실패 ($_d)"
      _oldlen=$(printf '%s' "$_plain" | b64len)
      [ "$_oldlen" = 32 ] || die "원본 DEK 길이가 32B가 아닙니다: $_d v$_v ($_oldlen)"
      _newfield=$(wrap_field "$_plain")
      _newct=$(field_to_ciphertext "$_newfield")
      _newplain=$(new_bao write -field=plaintext "$TRANSIT_MOUNT/decrypt/$TRANSIT_KEY" ciphertext="$_newct")
      _newlen=$(printf '%s' "$_newplain" | b64len)
      [ "$_newlen" = 32 ] || die "새 KEK wrap 검증 실패: $_d v$_v"
      set -- "$@" "dek-v$_v=$_newfield"
      info "dek/$_d: dek-v$_v 이전 (32B round-trip OK)"
      _v=$((_v + 1))
    done
    if [ "$APPLY" = 1 ]; then
      new_bao kv put -mount="$MOUNT" "$(dek_path "$_d")" "$@" "current-version=$_cur" >/dev/null
      ok "dek/$_d 저장 완료 (dek-v1..dek-v$_cur, current-version=$_cur)"
    else
      info "[dry-run] kv put $(dek_path "$_d") dek-v1..dek-v$_cur current-version=$_cur"
    fi
  done

  # 2) blind index: 새로 만들지 않고 값만 복사 (기존 인덱스 컬럼과 일치해야 함)
  for _i in $(echo "$BLIND_INDEXES" | tr ',' ' '); do
    if new_bao kv get -mount="$MOUNT" "$(bi_path "$_i")" >/dev/null 2>&1 && [ "$FORCE" != 1 ]; then
      info "blind-index/$_i: 이미 있음 (건너뜀, 덮어쓰려면 --force)"
      continue
    fi
    if ! _k=$(old_bao kv get -field=key -mount="$OLD_MOUNT" "$OLD_BASE/blind-index/$_i"); then
      die "원본에 $OLD_BASE/blind-index/$_i 가 없습니다"
    fi
    [ "${#_k}" = 43 ] || die "원본 blind-index/$_i 키 길이가 43자가 아닙니다 (${#_k})"
    if [ "$APPLY" = 1 ]; then
      new_bao kv put -mount="$MOUNT" "$(bi_path "$_i")" key="$_k" >/dev/null
      ok "blind-index/$_i 복사 완료 (값 동일)"
    else
      info "[dry-run] kv put $(bi_path "$_i") key=<원본과 동일>"
    fi
  done

  if [ "$APPLY" = 1 ]; then cmd_verify; fi
}

cmd_verify() {
  require_new_conn
  _okcnt=0
  _failcnt=0
  for _d in $(echo "$DOMAINS" | tr ',' ' '); do
    if ! _cur=$(new_bao kv get -field=current-version -mount="$MOUNT" "$(dek_path "$_d")"); then
      die "$(dek_path "$_d") 를 읽을 수 없습니다"
    fi
    _v=1
    while [ "$_v" -le "$_cur" ]; do
      _field=$(new_bao kv get -field="dek-v$_v" -mount="$MOUNT" "$(dek_path "$_d")")
      if _ct=$(field_to_ciphertext "$_field" 2>/dev/null); then :; else _ct=""; fi
      case "$_ct" in
        vault:*)
          _plain=$(new_bao write -field=plaintext "$TRANSIT_MOUNT/decrypt/$TRANSIT_KEY" ciphertext="$_ct" 2>/dev/null || true)
          _len=$(printf '%s' "$_plain" | base64 -d 2>/dev/null | wc -c | tr -d ' ')
          if [ "$_len" = 32 ]; then
            ok "dek/$_d dek-v$_v: Base64URL(transit ciphertext) + decrypt 32B"
            _okcnt=$((_okcnt + 1))
          else
            echo "  FAIL: dek/$_d dek-v$_v decrypt 길이=${_len:-0}"
            _failcnt=$((_failcnt + 1))
          fi
          ;;
        *)
          echo "  FAIL: dek/$_d dek-v$_v 값이 Base64URL(transit ciphertext) 형식이 아님"
          _failcnt=$((_failcnt + 1))
          ;;
      esac
      _v=$((_v + 1))
    done
  done
  for _d in $(echo "$BLIND_INDEXES" | tr ',' ' '); do
    if new_bao kv get -mount="$MOUNT" "$(bi_path "$_d")" >/dev/null 2>&1; then
      _len=$(new_bao kv get -field=key -mount="$MOUNT" "$(bi_path "$_d")" | b64u_len)
      if [ "$_len" = 32 ]; then
        ok "blind-index/$_d: 32B 키"
        _okcnt=$((_okcnt + 1))
      else
        echo "  FAIL: blind-index/$_d 키 길이 ${_len}B"
        _failcnt=$((_failcnt + 1))
      fi
    else
      echo "  FAIL: blind-index/$_d 없음"
      _failcnt=$((_failcnt + 1))
    fi
  done
  echo "== 검증 결과: OK $_okcnt / FAIL $_failcnt"
  [ "$_failcnt" = 0 ] || exit 1
}

case "$CMD" in
  show) cmd_show ;;
  create) cmd_create ;;
  migrate) cmd_migrate ;;
  verify) cmd_verify ;;
  *) usage ;;
esac
