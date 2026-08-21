@echo off
setlocal EnableDelayedExpansion

REM Runs DEK rotation/reencryption as a standalone manual batch job against the
REM currently-deployed xaandemo-prod.jar on the production server - separate from
REM the always-running server process. Uploads dek_ops_batch.sh and invokes it
REM with plain, separately-quoted ssh arguments (same pattern as relink_prod_jar.sh
REM - no bash syntax embedded in a cmd.exe string).
REM
REM Usage:
REM   dek_ops_batch.bat rotate board
REM   dek_ops_batch.bat reencrypt board,user-pii
REM
REM No "both" mode: rotate and reencrypt must always be two separate runs. The
REM EnvelopeCryptoService bean reads Vault's current DEK version once at JVM
REM startup and caches it, so a reencrypt done in the same run as a rotate would
REM still see the pre-rotation version and never touch the just-created one -
REM run rotate, let it finish, then run reencrypt as its own invocation.

set PROD_SERVER=192.168.2.57
set PROD_USER=xaan
set PROD_BASE_DIR=/home/xaan/ws/demoBBS
REM Jar path is no longer passed as an argument - dek_ops_batch.sh always
REM targets the stable xaandemo-prod.jar symlink itself, hardcoded there.

REM "-" means "not set" - never pass an empty "" argument through to ssh below.
REM ssh host cmd a b c rejoins separate arguments into one remote command string
REM before the remote shell parses it, and an empty argument in the middle
REM vanishes in that rejoin, shifting every argument after it by one position
REM (this bit demoApp - a "reencrypt" run silently became a "rotate" run because
REM the empty ROTATE_DOMAIN vanished and REENCRYPT_DOMAINS shifted into its slot).
set MODE=%1
set ROTATE_DOMAIN=-
set REENCRYPT_DOMAINS=-

if "%MODE%"=="rotate" (
    set ROTATE_DOMAIN=%2
) else if "%MODE%"=="reencrypt" (
    set REENCRYPT_DOMAINS=%2
) else (
    echo Usage: dek_ops_batch.bat rotate ^<domain^>
    echo    or: dek_ops_batch.bat reencrypt ^<domain1,domain2,...^>
    exit /b 1
)

echo Uploading dek_ops_batch.sh to %PROD_SERVER%...
scp "dek_ops_batch.sh" "%PROD_USER%@%PROD_SERVER%:%PROD_BASE_DIR%/dek_ops_batch.sh"
if %ERRORLEVEL% NEQ 0 (
    echo Failed to distribute dek_ops_batch.sh.
    exit /b 1
)

echo Running batch job on %PROD_SERVER% (rotate=!ROTATE_DOMAIN!, reencrypt=!REENCRYPT_DOMAINS!)...
ssh "%PROD_USER%@%PROD_SERVER%" bash "%PROD_BASE_DIR%/dek_ops_batch.sh" "!ROTATE_DOMAIN!" "!REENCRYPT_DOMAINS!"
if %ERRORLEVEL% NEQ 0 (
    echo Batch job failed - check the output above.
    exit /b 1
)
echo Batch job finished.
