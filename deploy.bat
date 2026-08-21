@echo off
setlocal EnableDelayedExpansion

REM Deploys the latest JAR to production server (192.168.2.57) and restarts the application

set PROD_SERVER=192.168.2.57
set PROD_USER=xaan
set PROD_APP_DIR=/home/xaan/ws/demoBBS/app
set PROD_BASE_DIR=/home/xaan/ws/demoBBS
set JAR_VERSION=0.0.10
set JAR_BASENAME=xaandemo-%JAR_VERSION%.jar
set JAR_FILE=build\libs\%JAR_BASENAME%
set PROD_LINK_NAME=xaandemo-prod.jar
set STOP_SCRIPT=%PROD_BASE_DIR%/stopapp.sh
set START_SCRIPT=%PROD_BASE_DIR%/startapp.sh
set LOG_DIR=%PROD_BASE_DIR%/log

echo ========================================
echo demoApp Deployment Script (Windows)
echo ========================================

echo.
echo [Step 0/4] Building application...

set JAVA_HOME=C:\SW\jdk-17.0.15
set GRADLE_HOME=C:\SW\gradle-8.14.5\bin
set PATH=%JAVA_HOME%\bin;%GRADLE_HOME%;%PATH%

call gradle.bat clean build -x test
if %ERRORLEVEL% NEQ 0 (
    echo Build failed! Aborting deployment.
    exit /b 1
)
echo Build successful.

echo.
echo [Step 1/4] Distributing JAR file to %PROD_SERVER%...

if not exist "%JAR_FILE%" (
    echo JAR file not found: %JAR_FILE%
    exit /b 1
)

scp "%JAR_FILE%" "%PROD_USER%@%PROD_SERVER%:%PROD_APP_DIR%/"
if %ERRORLEVEL% NEQ 0 (
    echo Failed to distribute JAR file.
    exit /b 1
)
echo JAR file distributed successfully.

scp "relink_prod_jar.sh" "%PROD_USER%@%PROD_SERVER%:%PROD_BASE_DIR%/relink_prod_jar.sh"
if %ERRORLEVEL% NEQ 0 (
    echo Failed to distribute relink_prod_jar.sh.
    exit /b 1
)

echo.
echo [Step 2/4] Stopping application on %PROD_SERVER%...

ssh "%PROD_USER%@%PROD_SERVER%" "bash %STOP_SCRIPT%"
if %ERRORLEVEL% EQU 0 (
    echo Stop script executed.
) else (
    echo Warning: Stop script returned non-zero exit code. Continuing...
)

echo.
echo [Step 3/4] Waiting for process to stop and starting...

REM relink_prod_jar.sh does the wait/relink/start on the remote side. Passed as
REM separate, plainly-quoted ssh arguments - no bash syntax embedded in a single
REM cmd.exe string this time. An earlier version inlined the wait/relink/start
REM logic directly into one quoted ssh argument; that silently failed to relink
REM in practice (cmd.exe -> ssh -> bash quoting of $(...), [ ... ], redirects
REM together in one string was too fragile to get right), so the logic was moved
REM into relink_prod_jar.sh instead.
ssh "%PROD_USER%@%PROD_SERVER%" bash "%PROD_BASE_DIR%/relink_prod_jar.sh" "%JAR_BASENAME%" "%PROD_APP_DIR%" "%PROD_BASE_DIR%" "%PROD_LINK_NAME%"
if %ERRORLEVEL% EQU 0 (
    echo Application start command executed.
) else (
    echo Failed to start application.
    exit /b 1
)

echo.
echo Waiting for application to be ready...
for /L %%i in (1, 1, 30) do (
    ssh "%PROD_USER%@%PROD_SERVER%" "curl -s -o /dev/null -w '%%{http_code}' http://localhost:8080/last100" > "%TEMP%\curl_out.txt" 2>nul
    set /p HTTP_CODE=<"%TEMP%\curl_out.txt"
    if "!HTTP_CODE!"=="200" (
        echo Application is ready^^!
        goto AppReady
    )
    if %%i==30 (
        echo Timeout: Application did not become ready in time.
        exit /b 1
    )
    echo Waiting... (%%i/30^)
    ping 127.0.0.1 -n 2 > nul
)

:AppReady

echo.
echo [Step 4/4] Checking logs and testing /last100...

ssh "%PROD_USER%@%PROD_SERVER%" "date +\"%%Y-%%m-%%d\"" > "%TEMP%\remote_date.txt"
set /p TODAY=<"%TEMP%\remote_date.txt"

set LOG_FILE=%LOG_DIR%/demoBBS-!TODAY!.log

echo Checking log file: !LOG_FILE!
echo Last 50 lines of log:
echo ----------------------------------------

ssh "%PROD_USER%@%PROD_SERVER%" "tail -50 !LOG_FILE!"

echo.
echo Testing /last100 endpoint...
ssh "%PROD_USER%@%PROD_SERVER%" "curl -s -o /dev/null -w '%%{http_code}' http://localhost:8080/last100 && echo ' - /last100 responded'"

echo.
echo ----------------------------------------
echo ========================================
echo Deployment completed!
echo ========================================
