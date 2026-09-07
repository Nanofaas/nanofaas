#!/bin/bash
# Esegue un banco della campagna contro il classpath del control-plane.
#
#   ./run.sh T1PoolBench [args...]
#
# Il classpath viene chiesto a Gradle una volta sola e messo in cache: ricavarlo
# a ogni esecuzione costa piu' del banco. Cancellare .classpath per rigenerarlo.
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../../.." && pwd)
CP_CACHE="$HERE/.classpath"

if [ ! -s "$CP_CACHE" ]; then
    echo "[run] risolvo il classpath (una volta sola)..." >&2
    (cd "$ROOT" && ./gradlew -q :control-plane:printTestClasspath) > "$CP_CACHE"
fi

BENCH=${1:?uso: ./run.sh <NomeBanco> [args...]}
shift || true

mkdir -p "$HERE/raw"
exec java -cp "$(cat "$CP_CACHE"):$HERE/bench" \
     --enable-native-access=ALL-UNNAMED \
     "$HERE/bench/$BENCH.java" "$@"
