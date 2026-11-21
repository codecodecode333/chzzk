#!/bin/sh
# PID 1 of the backend container: runs the app and passes SIGTERM/SIGINT on to it. A JVM stopped by SIGTERM exits with
# 143 (128 + 15) even after a clean graceful shutdown, which Kubernetes shows as "Error" on every scale-in; when the
# stop came from here, that 143 is reported as 0. Any other exit status (a crash, a failed start) passes through.
java -jar /app/app.jar "$@" &
app=$!
stopping=0
trap 'stopping=1; kill -TERM "$app" 2>/dev/null' TERM INT
while :; do
  wait "$app"; status=$?
  kill -0 "$app" 2>/dev/null || break   # still running: the wait was cut short by the signal, wait again
done
if [ "$stopping" = 1 ] && [ "$status" = 143 ]; then status=0; fi
exit "$status"
