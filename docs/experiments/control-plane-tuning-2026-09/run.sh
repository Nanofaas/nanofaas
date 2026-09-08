#!/bin/bash
# Runs one of the campaign's benchmarks against the control-plane classpath.
#
#   ./run.sh T1PoolBench [args...]
#
# The classpath is asked of Gradle once and cached: resolving it on every run
# costs more than the benchmark itself. Delete .classpath-* to regenerate.
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../../.." && pwd)
# PROJECT=<gradle project> selects the module whose classpath to use.
PROJECT=${PROJECT:-control-plane}
CP_CACHE="$HERE/.classpath-$PROJECT"

if [ ! -s "$CP_CACHE" ]; then
    echo "[run] resolving the classpath for $PROJECT (once)..." >&2
    if [ "$PROJECT" = "control-plane" ]; then
        (cd "$ROOT" && ./gradlew -q :control-plane:printTestClasspath) > "$CP_CACHE"
    else
        (cd "$ROOT" && ./gradlew -q ":control-plane-modules:$PROJECT:printTestClasspath" \
            -PcontrolPlaneModules="$PROJECT") > "$CP_CACHE"
    fi
fi

BENCH=${1:?usage: ./run.sh <BenchName> [args...]}
shift || true

mkdir -p "$HERE/raw"
# A benchmark that needs package-private API lives under its package in bench/.
SRC="$HERE/bench/$BENCH.java"
if [ ! -f "$SRC" ]; then
    SRC=$(find "$HERE/bench" -name "$BENCH.java" | head -1)
fi
[ -n "$SRC" ] || { echo "benchmark not found: $BENCH" >&2; exit 1; }

# Compile rather than use the source launcher: that loads the benchmark in a
# separate classloader, and package-private access then fails at runtime.
OUT="$HERE/.classes"
rm -rf "$OUT" && mkdir -p "$OUT"
javac -nowarn -cp "$(cat "$CP_CACHE")" -d "$OUT" "$SRC"

PKG=$(sed -n 's/^package \(.*\);/\1/p' "$SRC" | head -1)
if [ -n "$PKG" ]; then FQCN="$PKG.$BENCH"; else FQCN="$BENCH"; fi

exec java -cp "$(cat "$CP_CACHE"):$OUT" \
     --enable-native-access=ALL-UNNAMED \
     "$FQCN" "$@"
