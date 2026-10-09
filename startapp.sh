#!/usr/bin/bash
#nohup java -jar app/*.jar > log/demoBBS-`date '+%Y%m%d_%H%M%S'`.log 2>&1 &

JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
APP_HOME=/home/xaan/ws/demoBBS
SCOUTER_HOME=/home/xaan/ws/scouter
nohup ${JAVA_HOME}/bin/java \
	-javaagent:${SCOUTER_HOME}/agent.java/scouter.agent.jar \
        -Dscouter.config=${SCOUTER_HOME}/agent.java/conf/scouter.conf \
        -Dobj_name=demoBBS \
	-jar ${APP_HOME}/xaandemo-prod.jar --spring.config.additional-location=file:${APP_HOME}/app/application.properties >> ${APP_HOME}/log/demoBBS-`date '+%Y-%m-%d'`.log 2>&1 &
