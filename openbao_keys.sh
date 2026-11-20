#!/bin/sh
# =====================================================================
# demoApp OpenBao key setup/migration tool (POSIX sh + bao CLI; no python/jq)
#
#   Creates the key set demoApp uses on a new OpenBao server.
#     transit/keys/<KEK>                                       : KEK (aes256-gcm96, non-exportable)
#     <MOUNT>/data/<BASE>/dek/{board,user-pii}                 : wrapped DEKs (dek-v{n},
#                                                                Base64URL(UTF-8("vault:vN:...")))
#     <MOUNT>/data/<BASE>/blind-index/{user-phone,user-rrn}    : HMAC keys (Base64URL 32B)
#     policy/token (optional)
#
#   Subcommands
#     show     print the current key layout (read-only)
#     create   create a fresh key set (empty data / new deployment)
#     migrate  copy DEKs/BI keys from an existing server (data preserved)
#              - unwrap each DEK on the old server, wrap it with the new KEK,
#                and keep the same version numbers and current-version
#              - blind index keys are copied as-is (creating new ones would break
#                the existing blind index columns)
#     verify   round-trip check of every DEK version + BI key length
#
#   Common options
#     --addr URL        new OpenBao address (default $BAO_ADDR)
#     --token TOKEN     new OpenBao token (default $BAO_TOKEN)
#     --mount NAME      kv-v2 mount (default ebiz_service)
#     --base PATH       base path (default ebiz_db)
#     --transit-mount   (default transit)
#     --transit-key     KEK name (default kek)
#     --domains         (default board,user-pii)
#     --blind-indexes   (default user-phone,user-rrn)
#     --apply           actually write (default is dry-run)
#     --force           overwrite existing keys (data loss risk)
#     --old-addr/--old-token   migrate source server (default $OLD_BAO_ADDR/$OLD_BAO_TOKEN)
#     --old-mount/--old-base/--old-transit-key   when the source paths differ from the
#                                                target (defaults: same as --mount/--base/--transit-key)
#     --policy NAME     policy name for create (default demoapp-read; empty to skip)
#     --token-name      token display name (default token-demoapp)
#     --token-ttl       (default 768h)
#     --token-out PATH  where to store the app token (default ./demoapp-app-token, mode 600)
#
#   Examples
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

die() { echo "ABORT: $*" >&2; exit 1; }
info() { echo "-- $*"; }
ok() { echo "OK  : $*"; }

usage() { sed -n '2,50p' "$0" | sed 's/^# \{0,1\}//'; exit 1; }

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
    *) die "unknown option: $1" ;;
  esac
done

[ -n "$OLD_MOUNT" ] || OLD_MOUNT="$MOUNT"
[ -n "$OLD_BASE" ] || OLD_BASE="$BASE"
[ -n "$OLD_TRANSIT_MOUNT" ] || OLD_TRANSIT_MOUNT="$TRANSIT_MOUNT"
[ -n "$OLD_TRANSIT_KEY" ] || OLD_TRANSIT_KEY="$TRANSIT_KEY"

# ---------------------------------------------------------------- helpers
# 32 random bytes (raw)
random32() { dd if=/dev/urandom bs=32 count=1 2>/dev/null; }
# stdin bytes -> standard base64 (single line)
b64std() { base64 | tr -d '\n'; }
# stdin bytes -> Base64URL (no padding)
b64u() { base64 | tr -d '\n' | tr '+/' '-_' | tr -d '='; }
# Base64URL -> raw bytes
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
  [ -n "$ADDR" ] || die "--addr or BAO_ADDR is required"
  [ -n "$TOKEN" ] || die "--token or BAO_TOKEN is required"
  new_bao token lookup >/dev/null 2>&1 || die "new OpenBao token/address check failed: $ADDR"
  info "new OpenBao: $ADDR (mount=$MOUNT, base=$BASE, transit=$(kek_path))"
}

require_old_conn() {
  [ -n "$OLD_ADDR" ] || die "migrate requires --old-addr or OLD_BAO_ADDR"
  [ -n "$OLD_TOKEN" ] || die "migrate requires --old-token or OLD_BAO_TOKEN"
  old_bao token lookup >/dev/null 2>&1 || die "source OpenBao token/address check failed: $OLD_ADDR"
  info "source OpenBao: $OLD_ADDR (mount=$OLD_MOUNT, base=$OLD_BASE, transit=$OLD_TRANSIT_MOUNT/keys/$OLD_TRANSIT_KEY)"
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
    echo "  KEK $(kek_path): missing"
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
      echo "  dek/$_d: missing"
    fi
  done
  for _d in $(echo "$BLIND_INDEXES" | tr ',' ' '); do
    if BAO_ADDR="$_addr" BAO_TOKEN="$_tok" bao kv get -mount="$MOUNT" "$(bi_path "$_d")" >/dev/null 2>&1; then
      _len=$(BAO_ADDR="$_addr" BAO_TOKEN="$_tok" bao kv get -field=key -mount="$MOUNT" "$(bi_path "$_d")" | b64u_len)
      echo "  blind-index/$_d: key ${_len}B"
    else
      echo "  blind-index/$_d: missing"
    fi
  done
}

mkdir_kv_mount_if_absent() {
  if new_bao secrets list | awk '{print $1}' | grep -qx "$MOUNT/"; then
    info "kv-v2 mount present: $MOUNT/"
  else
    [ "$APPLY" = 1 ] || { info "[dry-run] bao secrets enable -path=$MOUNT kv-v2"; return 0; }
    new_bao secrets enable -path="$MOUNT" kv-v2 >/dev/null
    ok "kv-v2 mount created: $MOUNT/"
  fi
}

mkdir_transit_mount_if_absent() {
  if new_bao secrets list | awk '{print $1}' | grep -qx "$TRANSIT_MOUNT/"; then
    info "transit mount present: $TRANSIT_MOUNT/"
  else
    [ "$APPLY" = 1 ] || { info "[dry-run] bao secrets enable -path=$TRANSIT_MOUNT transit"; return 0; }
    new_bao secrets enable -path="$TRANSIT_MOUNT" transit >/dev/null
    ok "transit mount created: $TRANSIT_MOUNT/"
  fi
}

ensure_kek() {
  if kek_exists; then
    info "KEK present: $(kek_path) (latest_version=$(new_bao read -field=latest_version "$(kek_path)"))"
    return 0
  fi
  [ "$APPLY" = 1 ] || { info "[dry-run] bao write $(kek_path) type=aes256-gcm96 exportable=false allow_plaintext_backup=false"; return 0; }
  new_bao write "$(kek_path)" type=aes256-gcm96 exportable=false allow_plaintext_backup=false >/dev/null
  ok "KEK created: $(kek_path) (latest_version=$(new_bao read -field=latest_version "$(kek_path)"))"
}

# wrap a plaintext DEK (standard base64) with the new KEK and return the KV field form
wrap_field() {
  _ct=$(new_bao write -field=ciphertext "transit/encrypt/$TRANSIT_KEY" plaintext="$1")
  [ -n "$_ct" ] || die "transit/encrypt returned no ciphertext"
  printf '%s' "$_ct" | b64u
}

# KV field value -> ciphertext string
field_to_ciphertext() { printf '%s' "$1" | b64u_dec; }

# ---------------------------------------------------------------- commands
cmd_show() {
  [ -n "$ADDR" ] || die "--addr or BAO_ADDR is required"
  [ -n "$TOKEN" ] || die "--token or BAO_TOKEN is required"
  show_remote "new OpenBao" "$ADDR" "$TOKEN"
}

cmd_create() {
  require_new_conn
  [ "$APPLY" = 1 ] || echo "*** dry-run: use --apply to write ***"

  if [ "$FORCE" != 1 ]; then
    for _d in $(echo "$DOMAINS" | tr ',' ' '); do
      if kv_exists "$(dek_path "$_d")"; then
        die "DEK keys already exist: $(dek_path "$_d") (use --force to overwrite)"
      fi
    done
  fi

  mkdir_kv_mount_if_absent
  mkdir_transit_mount_if_absent
  ensure_kek

  for _d in $(echo "$DOMAINS" | tr ',' ' '); do
    if [ "$APPLY" != 1 ]; then
      info "[dry-run] dek/$_d: generate 32B DEK -> transit/encrypt -> Base64URL -> dek-v1 + current-version=1"
      continue
    fi
    _field=$(wrap_field "$(random32 | b64std)")
    new_bao kv put -mount="$MOUNT" "$(dek_path "$_d")" "dek-v1=$_field" current-version=1 >/dev/null
    ok "DEK created: $(dek_path "$_d") (dek-v1, current-version=1)"
  done

  for _d in $(echo "$BLIND_INDEXES" | tr ',' ' '); do
    if [ "$APPLY" != 1 ]; then
      info "[dry-run] blind-index/$_d: random 32B -> Base64URL (43 chars)"
      continue
    fi
    new_bao kv put -mount="$MOUNT" "$(bi_path "$_d")" key="$(random32 | b64u)" >/dev/null
    ok "blind-index key created: $(bi_path "$_d")"
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
    ok "policy written: $POLICY"
    ( umask 077; new_bao token create -policy="$POLICY" -display-name="$TOKEN_NAME" \
        -ttl="$TOKEN_TTL" -renewable=true -field=token > "$TOKEN_OUT" )
    ok "app token issued -> $TOKEN_OUT (mode 600; value is not printed)"
  fi

  cmd_verify
}

cmd_migrate() {
  require_new_conn
  require_old_conn
  [ "$APPLY" = 1 ] || echo "*** dry-run: use --apply to write ***"

  if [ "$FORCE" != 1 ]; then
    for _d in $(echo "$DOMAINS" | tr ',' ' '); do
      if kv_exists "$(dek_path "$_d")"; then
        die "target already has DEK keys: $(dek_path "$_d") (use --force to overwrite)"
      fi
    done
  fi

  mkdir_kv_mount_if_absent
  mkdir_transit_mount_if_absent
  ensure_kek

  # 1) DEK: old ciphertext -> unwrap (plaintext) -> wrap with new KEK -> store under the same version
  for _d in $(echo "$DOMAINS" | tr ',' ' '); do
    if ! _cur=$(old_bao kv get -field=current-version -mount="$OLD_MOUNT" "$OLD_BASE/dek/$_d"); then
      die "source has no $OLD_BASE/dek/$_d"
    fi
    set --
    _v=1
    while [ "$_v" -le "$_cur" ]; do
      _field=$(old_bao kv get -field="dek-v$_v" -mount="$OLD_MOUNT" "$OLD_BASE/dek/$_d")
      [ -n "$_field" ] || die "source dek-v$_v is empty ($_d)"
      _ct=$(field_to_ciphertext "$_field")
      _plain=$(old_bao write -field=plaintext "$OLD_TRANSIT_MOUNT/decrypt/$OLD_TRANSIT_KEY" ciphertext="$_ct")
      [ -n "$_plain" ] || die "source dek-v$_v decryption failed ($_d)"
      _oldlen=$(printf '%s' "$_plain" | b64len)
      [ "$_oldlen" = 32 ] || die "source DEK is not 32 bytes: $_d v$_v ($_oldlen)"
      _newfield=$(wrap_field "$_plain")
      _newct=$(field_to_ciphertext "$_newfield")
      _newplain=$(new_bao write -field=plaintext "$TRANSIT_MOUNT/decrypt/$TRANSIT_KEY" ciphertext="$_newct")
      _newlen=$(printf '%s' "$_newplain" | b64len)
      [ "$_newlen" = 32 ] || die "new KEK wrap verification failed: $_d v$_v"
      set -- "$@" "dek-v$_v=$_newfield"
      info "dek/$_d: migrated dek-v$_v (32B round-trip OK)"
      _v=$((_v + 1))
    done
    if [ "$APPLY" = 1 ]; then
      new_bao kv put -mount="$MOUNT" "$(dek_path "$_d")" "$@" "current-version=$_cur" >/dev/null
      ok "dek/$_d stored (dek-v1..dek-v$_cur, current-version=$_cur)"
    else
      info "[dry-run] kv put $(dek_path "$_d") dek-v1..dek-v$_cur current-version=$_cur"
    fi
  done

  # 2) blind index: copy values as-is (they must match the existing index columns)
  for _i in $(echo "$BLIND_INDEXES" | tr ',' ' '); do
    if new_bao kv get -mount="$MOUNT" "$(bi_path "$_i")" >/dev/null 2>&1 && [ "$FORCE" != 1 ]; then
      info "blind-index/$_i: already present (skipped; use --force to overwrite)"
      continue
    fi
    if ! _k=$(old_bao kv get -field=key -mount="$OLD_MOUNT" "$OLD_BASE/blind-index/$_i"); then
      die "source has no $OLD_BASE/blind-index/$_i"
    fi
    [ "${#_k}" = 43 ] || die "source blind-index/$_i key is not 43 chars (${#_k})"
    if [ "$APPLY" = 1 ]; then
      new_bao kv put -mount="$MOUNT" "$(bi_path "$_i")" key="$_k" >/dev/null
      ok "blind-index/$_i copied (identical value)"
    else
      info "[dry-run] kv put $(bi_path "$_i") key=<same as source>"
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
      die "cannot read $(dek_path "$_d")"
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
            echo "  FAIL: dek/$_d dek-v$_v decrypted length=${_len:-0}"
            _failcnt=$((_failcnt + 1))
          fi
          ;;
        *)
          echo "  FAIL: dek/$_d dek-v$_v is not Base64URL(transit ciphertext)"
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
        ok "blind-index/$_d: 32B key"
        _okcnt=$((_okcnt + 1))
      else
        echo "  FAIL: blind-index/$_d key length ${_len}B"
        _failcnt=$((_failcnt + 1))
      fi
    else
      echo "  FAIL: blind-index/$_d missing"
      _failcnt=$((_failcnt + 1))
    fi
  done
  echo "== verify result: OK $_okcnt / FAIL $_failcnt"
  [ "$_failcnt" = 0 ] || exit 1
}

case "$CMD" in
  show) cmd_show ;;
  create) cmd_create ;;
  migrate) cmd_migrate ;;
  verify) cmd_verify ;;
  *) usage ;;
esac
