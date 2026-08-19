#!/bin/bash

# Deploys the latest JAR to production server (hactus57) and restarts the application
set -e

PROD_SERVER="192.168.2.57"
PROD_USER="xaan"
PROD_APP_DIR="/home/xaan/ws/demoBBS/app"
PROD_BASE_DIR="/home/xaan/ws/demoBBS"
JAR_FILE="build/libs/xaandemo-0.0.5.jar"
STOP_SCRIPT="${PROD_BASE_DIR}/stopapp.sh"
START_SCRIPT="${PROD_BASE_DIR}/startapp.sh"
LOG_DIR="${PROD_BASE_DIR}/log"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

echo -e "${GREEN}========================================"
echo -e "demoApp Deployment Script"
echo -e "========================================${NC}"

echo -e "\n${YELLOW}[Step 0/4] Building application...${NC}"
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export PATH=$JAVA_HOME/bin:$PATH

if ! gradle clean build -x test; then
    echo -e "${RED}Build failed! Aborting deployment.${NC}"
    exit 1
fi
echo -e "${GREEN}Build successful.${NC}"

echo -e "\n${YELLOW}[Step 1/4] Distributing JAR file to ${PROD_SERVER}...${NC}"

if [ ! -f "$JAR_FILE" ]; then
    echo -e "${RED}JAR file not found: ${JAR_FILE}${NC}"
    exit 1
fi

if scp "$JAR_FILE" "${PROD_USER}@${PROD_SERVER}:${PROD_APP_DIR}/"; then
    echo -e "${GREEN}JAR file distributed successfully.${NC}"
else
    echo -e "${RED}Failed to distribute JAR file.${NC}"
    exit 1
fi

echo -e "\n${YELLOW}[Step 2/4] Stopping application on ${PROD_SERVER}...${NC}"

if ssh "${PROD_USER}@${PROD_SERVER}" "bash ${STOP_SCRIPT}"; then
    echo -e "${GREEN}Stop script executed.${NC}"
else
    echo -e "${YELLOW}Warning: Stop script returned non-zero exit code. Continuing...${NC}"
fi

echo -e "\n${YELLOW}[Step 3/4] Waiting for process to stop and starting...${NC}"

ssh "${PROD_USER}@${PROD_SERVER}" bash << 'EOF'
while pgrep -f "xaandemo-0.0.5-SNAPSHOT.jar" > /dev/null 2>&1; do
    echo "Process still running, waiting 1 second..."
    sleep 1
done
echo "Process is down. Starting application..."
# Copy new JAR to production jar name if different
if ! cmp -s /home/xaan/ws/demoBBS/app/xaandemo-0.0.5.jar /home/xaan/ws/demoBBS/xaandemo-prod.jar 2>/dev/null; then
    cp /home/xaan/ws/demoBBS/app/xaandemo-0.0.5.jar /home/xaan/ws/demoBBS/xaandemo-prod.jar
fi
bash /home/xaan/ws/demoBBS/startapp.sh
echo "Start script executed."
EOF

if [ $? -eq 0 ]; then
    echo -e "${GREEN}Application start command executed.${NC}"
else
    echo -e "${RED}Failed to start application.${NC}"
    exit 1
fi

for i in $(seq 1 30); do
    if ssh "${PROD_USER}@${PROD_SERVER}" "curl -s -o /dev/null -w '%{http_code}' http://localhost:8080/last100" 2>/dev/null | grep -q "200"; then
        echo -e "${GREEN}Application is ready!${NC}"
        break
    fi
    if [ $i -eq 30 ]; then
        echo -e "${RED}Timeout: Application did not become ready in time.${NC}"
        exit 1
    fi
    echo "Waiting... ($i/30)"
    sleep 1
done

echo -e "\n${YELLOW}[Step 4/4] Checking logs and testing /last100...${NC}"

TODAY=$(date +"%Y-%m-%d")
LOG_FILE="${LOG_DIR}/demoBBS-${TODAY}.log"

echo -e "Checking log file: ${LOG_FILE}"
echo -e "${YELLOW}Last 50 lines of log:${NC}"
echo "----------------------------------------"

ssh "${PROD_USER}@${PROD_SERVER}" "tail -50 ${LOG_FILE}"

echo -e "\n${YELLOW}Testing /last100 endpoint...${NC}"
ssh "${PROD_USER}@${PROD_SERVER}" "curl -s -o /dev/null -w '%{http_code}' http://localhost:8080/last100 && echo ' - /last100 responded'"

echo -e "\n----------------------------------------"
echo -e "${GREEN}========================================"
echo -e "Deployment completed!"
echo -e "========================================${NC}"
