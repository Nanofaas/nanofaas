#!/bin/bash
# Runs the scheduler-switch benchmark harness against :execution-runtime's test classpath
# (issue #208, Task 12c).
#
#   ./run.sh [--label=NAME] [--harness options...]
#
# Examples:
#   ./run.sh --label=smoke --parts=backlog --backlogs=100 --repetitions=1
#   ./run.sh --label=full
#
# The classpath is asked of Gradle once and cached: resolving it on every run costs more than the
# benchmark itself. Delete .classpath-execution-runtime to regenerate.
#
# Gradle's daemon is stopped before the measured JVM starts. Nothing of Gradle's — and nothing
# else CPU-bound — may be running while a sample is being taken: for a latency, CPU or post-GC
# heap figure a concurrent run does not corrupt a file, it corrupts the number, silently.
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../../.." && pwd)
CP_CACHE="$HERE/.classpath-execution-runtime"

if [ ! -s "$CP_CACHE" ]; then
    echo "[run] resolving the classpath for :execution-runtime (once)..." >&2
    (cd "$ROOT" && ./gradlew -q :execution-runtime:printTestClasspath) > "$CP_CACHE"
fi

LABEL=run
for arg in "$@"; do
    case "$arg" in
        --label=*) LABEL="${arg#--label=}" ;;
    esac
done
[ -n "$LABEL" ] || { echo "[run] --label must not be empty" >&2; exit 1; }

SRC="$HERE/SchedulerSwitchBenchmark.java"
[ -f "$SRC" ] || { echo "[run] harness not found: $SRC" >&2; exit 1; }

# Compile rather than use the source launcher: the launcher would run the harness in a child
# process with a different heap, and the heap configuration is part of the measurement.
OUT="$HERE/.classes"
rm -rf "$OUT" && mkdir -p "$OUT"
javac -nowarn -cp "$(cat "$CP_CACHE")" -d "$OUT" "$SRC"

mkdir -p "$HERE/raw"

# Nothing of Gradle's may be running while a sample is taken.
(cd "$ROOT" && ./gradlew --stop > /dev/null 2>&1) || true

SHA=$(cd "$ROOT" && git rev-parse HEAD)

# A fixed, pre-touched heap: post-GC heap and the pause figures are only comparable across arms
# when the heap the arms run in is the same one.
java -Xms1g -Xmx1g -XX:+AlwaysPreTouch \
     -cp "$(cat "$CP_CACHE"):$OUT" \
     --enable-native-access=ALL-UNNAMED \
     -Dnanofaas.sha="$SHA" -Dnanofaas.artifact=jvm \
     it.unimib.datai.nanofaas.SchedulerSwitchBenchmark \
     --budgets="$HERE/budgets.json" "$@" \
     2> >(tee "$HERE/raw/$LABEL.err" >&2) | tee "$HERE/raw/$LABEL.jsonl"
