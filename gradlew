#!/bin/sh

set -eu

APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

if [ -z "${GRADLE_USER_HOME:-}" ] ; then
    GRADLE_USER_HOME="$APP_HOME/.gradle-user-home"
    export GRADLE_USER_HOME
fi

if [ -n "${JAVA_HOME:-}" ] ; then
    JAVACMD="$JAVA_HOME/bin/java"
else
    JAVACMD=java
fi

if [ "$JAVACMD" = "java" ] ; then
    if ! command -v java >/dev/null 2>&1 ; then
        echo "ERROR: Java not found. Set JAVA_HOME or install java." >&2
        exit 1
    fi
elif [ ! -x "$JAVACMD" ] ; then
    echo "ERROR: Java not found at $JAVACMD. Set JAVA_HOME correctly." >&2
    exit 1
fi

exec "$JAVACMD" -Dorg.gradle.appname=gradlew -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
