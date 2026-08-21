#!/bin/bash
# Runs DEK rotation/reencryption as a standalone, one-shot manual batch process -
# separate from the always-running web server. Launches the jar with
# --spring.main.web-application-type=none (the JVM never opens the web port, so it
# can never overlap with live HTTP traffic) and app.dek-ops.batch-mode=true (the
# process exits itself once the requested op(s) finish instead of staying up).
# See DekOpsRunner.java and KEY_ROTATION_RUNBOOK.md §2.
#
# Always runs against the stable xaandemo-prod.jar symlink - there's only ever one
# jar to point at (whatever's currently deployed), so it's fixed here rather than
# taken as an argument.
#
# Usage: dek_ops_batch.sh [rotate-domain] [reencrypt-domains]
# Pass "-" (or "") for an argument you're not using:
#   ./dek_ops_batch.sh board -
#   ./dek_ops_batch.sh - board,user-pii
set -e

JAR="/home/xaan/ws/demoBBS/xaandemo-prod.jar"
ROTATE_DOMAIN="$1"
REENCRYPT_DOMAINS="$2"

# "-" means "not set". Prefer this over passing "" when invoked over ssh with
# multiple separate arguments (ssh host cmd a b c) - ssh rejoins those into a
# single remote command string before the remote shell parses it, and a
# genuinely empty argument in the middle vanishes in that rejoin, shifting
# every argument after it by one position. See dek_ops_batch.bat.
[ "$ROTATE_DOMAIN" = "-" ] && ROTATE_DOMAIN=""
[ "$REENCRYPT_DOMAINS" = "-" ] && REENCRYPT_DOMAINS=""

if [ ! -f "$JAR" ]; then
    echo "Jar not found: $JAR" >&2
    exit 1
fi
if [ -z "$ROTATE_DOMAIN" ] && [ -z "$REENCRYPT_DOMAINS" ]; then
    echo "Usage: dek_ops_batch.sh [rotate-domain] [reencrypt-domains] (pass '-' for the one you're not using)" >&2
    exit 1
fi

java -jar "$JAR" \
    --spring.main.web-application-type=none \
    --spring.main.banner-mode=off \
    --app.dek-ops.batch-mode=true \
    --app.dek-ops.rotate-domain="$ROTATE_DOMAIN" \
    --app.dek-ops.reencrypt-domains="$REENCRYPT_DOMAINS"
