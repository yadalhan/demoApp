#!/bin/bash
# Runs DEK rotation/reencryption as a standalone, one-shot manual batch process -
# separate from the always-running web server. Launches the jar with
# --spring.main.web-application-type=none (the JVM never opens the web port, so it
# can never overlap with live HTTP traffic) and app.dek-ops.batch-mode=true (the
# process exits itself once the requested op(s) finish instead of staying up).
# See DekOpsRunner.java and KEY_ROTATION_RUNBOOK.md §2.
#
# Usage: dek_ops_batch.sh <jar-path> [rotate-domain] [reencrypt-domains]
# Pass "-" (or "") for an argument you're not using:
#   ./dek_ops_batch.sh build/libs/xaandemo-0.0.7.jar board -
#   ./dek_ops_batch.sh build/libs/xaandemo-0.0.7.jar - board,user-pii
#   ./dek_ops_batch.sh build/libs/xaandemo-0.0.7.jar board board
set -e

JAR="$1"
ROTATE_DOMAIN="$2"
REENCRYPT_DOMAINS="$3"

# "-" means "not set". Prefer this over passing "" when invoked over ssh with
# multiple separate arguments (ssh host cmd a b c) - ssh rejoins those into a
# single remote command string before the remote shell parses it, and a
# genuinely empty argument in the middle vanishes in that rejoin, shifting
# every argument after it by one position. See dek_ops_batch.bat.
[ "$ROTATE_DOMAIN" = "-" ] && ROTATE_DOMAIN=""
[ "$REENCRYPT_DOMAINS" = "-" ] && REENCRYPT_DOMAINS=""

if [ -z "$JAR" ]; then
    echo "Usage: dek_ops_batch.sh <jar-path> [rotate-domain] [reencrypt-domains]" >&2
    exit 1
fi
if [ ! -f "$JAR" ]; then
    echo "Jar not found: $JAR" >&2
    exit 1
fi
if [ -z "$ROTATE_DOMAIN" ] && [ -z "$REENCRYPT_DOMAINS" ]; then
    echo "Nothing to do: pass a rotate-domain and/or reencrypt-domains argument." >&2
    exit 1
fi

java -jar "$JAR" \
    --spring.main.web-application-type=none \
    --spring.main.banner-mode=off \
    --app.dek-ops.batch-mode=true \
    --app.dek-ops.rotate-domain="$ROTATE_DOMAIN" \
    --app.dek-ops.reencrypt-domains="$REENCRYPT_DOMAINS"
