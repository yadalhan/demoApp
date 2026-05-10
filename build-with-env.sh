#!/bin/bash

# Set environment variables
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export GRADLE_HOME=/opt/gradle/gradle-8.14.5
export PATH=$GRADLE_HOME/bin:$PATH

echo "Java version:"
$JAVA_HOME/bin/java -version

echo -e "\nGradle version:"
gradle --version

echo -e "\nBuilding project..."
./gradlew clean build