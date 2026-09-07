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
# PROJECT=<gradle project> seleziona il modulo di cui prendere il classpath.
PROJECT=${PROJECT:-control-plane}
CP_CACHE="$HERE/.classpath-$PROJECT"

if [ ! -s "$CP_CACHE" ]; then
    echo "[run] risolvo il classpath di $PROJECT (una volta sola)..." >&2
    if [ "$PROJECT" = "control-plane" ]; then
        (cd "$ROOT" && ./gradlew -q :control-plane:printTestClasspath) > "$CP_CACHE"
    else
        (cd "$ROOT" && ./gradlew -q ":control-plane-modules:$PROJECT:printTestClasspath" \
            -PcontrolPlaneModules="$PROJECT") > "$CP_CACHE"
    fi
fi

BENCH=${1:?uso: ./run.sh <NomeBanco> [args...]}
shift || true

mkdir -p "$HERE/raw"
# Un banco che deve toccare API package-private vive sotto il suo package dentro bench/.
SRC="$HERE/bench/$BENCH.java"
if [ ! -f "$SRC" ]; then
    SRC=$(find "$HERE/bench" -name "$BENCH.java" | head -1)
fi
[ -n "$SRC" ] || { echo "banco non trovato: $BENCH" >&2; exit 1; }

# Compilare invece di usare il source launcher: quello carica il banco in un
# classloader separato, e l'accesso package-private fallisce a runtime.
OUT="$HERE/.classes"
rm -rf "$OUT" && mkdir -p "$OUT"
javac -nowarn -cp "$(cat "$CP_CACHE")" -d "$OUT" "$SRC"

PKG=$(sed -n 's/^package \(.*\);/\1/p' "$SRC" | head -1)
if [ -n "$PKG" ]; then FQCN="$PKG.$BENCH"; else FQCN="$BENCH"; fi

exec java -cp "$(cat "$CP_CACHE"):$OUT" \
     --enable-native-access=ALL-UNNAMED \
     "$FQCN" "$@"
