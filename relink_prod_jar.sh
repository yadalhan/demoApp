#!/bin/bash
# Runs entirely on the production server. Waits for the currently running app to
# stop, then points the stable xaandemo-prod.jar symlink at the newly deployed
# versioned jar (only relinking if it actually changed) and starts the app.
#
# Kept as its own file (instead of inlined into deploy.sh/deploy.bat as an ssh
# argument) because embedding this much bash syntax - $(...), [ ... ], redirects -
# inside a single cmd.exe-quoted string was too fragile to verify from Windows and
# silently failed to relink in practice. deploy.sh/deploy.bat now just scp this
# file over and invoke it with plain arguments, which both platforms can do
# without any nested-quoting tricks.
#
# Usage: relink_prod_jar.sh <jar_basename> <app_dir> <base_dir> <link_name>
set -e

JAR_BASENAME="$1"
APP_DIR="$2"
BASE_DIR="$3"
LINK_NAME="$4"

if [ -z "$JAR_BASENAME" ] || [ -z "$APP_DIR" ] || [ -z "$BASE_DIR" ] || [ -z "$LINK_NAME" ]; then
    echo "Usage: relink_prod_jar.sh <jar_basename> <app_dir> <base_dir> <link_name>" >&2
    exit 1
fi

NEW_JAR="${APP_DIR}/${JAR_BASENAME}"
PROD_LINK="${BASE_DIR}/${LINK_NAME}"

if [ ! -f "$NEW_JAR" ]; then
    echo "Newly deployed jar not found: $NEW_JAR" >&2
    exit 1
fi

# Must not just be "pgrep -f $LINK_NAME" - this very script's own command line
# (bash relink_prod_jar.sh ... "$LINK_NAME") contains that string too, so a plain
# search always matches itself and the loop never exits. Requiring "java" ahead of
# it only matches the actual running JVM process, not this script's invocation.
while pgrep -f "java.*${LINK_NAME}" > /dev/null 2>&1; do
    echo "Process still running, waiting 1 second..."
    sleep 1
done
echo "Process is down."

# readlink -f also works when $PROD_LINK is a plain regular file left over from
# an older cp-based deploy (it just canonicalizes to the file's own path, which
# won't match $NEW_JAR) or doesn't exist yet (still canonicalizes, doesn't error).
CURRENT_TARGET=$(readlink -f "$PROD_LINK" 2>/dev/null || true)
if [ "$CURRENT_TARGET" != "$NEW_JAR" ]; then
    rm -f "$PROD_LINK"
    ln -s "$NEW_JAR" "$PROD_LINK"
    echo "Symlink updated: $PROD_LINK -> $NEW_JAR"
else
    echo "Symlink already points to $NEW_JAR, nothing to relink."
fi

bash "${BASE_DIR}/startapp.sh"
echo "Start script executed."
