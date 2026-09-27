#!/bin/sh
set -eu
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$APP_HOME"
JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
if [ ! -s "$JAR" ] && [ -x "$APP_HOME/tools/ensure-gradle-wrapper.sh" ]; then
  "$APP_HOME/tools/ensure-gradle-wrapper.sh" || true
fi
if [ -s "$JAR" ]; then
  if ! command -v java >/dev/null 2>&1; then
    echo "Java is required to run Gradle Wrapper." >&2
    exit 1
  fi
  exec java -jar "$JAR" "$@"
fi
LOCAL_GRADLE="$APP_HOME/.tools/gradle-9.7.1/bin/gradle"
if [ -x "$LOCAL_GRADLE" ]; then exec "$LOCAL_GRADLE" --project-dir "$APP_HOME" "$@"; fi
if command -v gradle >/dev/null 2>&1; then exec gradle --project-dir "$APP_HOME" "$@"; fi
echo "Gradle Wrapper JAR is unavailable. With network access run tools/fetch-gradle-wrapper.sh; otherwise install Gradle 9.7.1 or run tools/bootstrap-linux.sh." >&2
exit 127
