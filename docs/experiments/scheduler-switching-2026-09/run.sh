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

# The cache holds the implementer machine's absolute paths, so a cache committed to the repository
# is a cache that cannot be right on another host. It is validated on every run rather than trusted:
# empty, missing a path, or missing this module's own output all mean "re-resolve", loudly.
cache_is_valid() {
    [ -s "$CP_CACHE" ] || return 1
    # The compiled output of this module must be there...
    grep -q 'execution-runtime/build/classes/java/main' "$CP_CACHE" || return 1
    grep -q 'execution-runtime/build/classes/java/test' "$CP_CACHE" || return 1
    # ...and every jar it names must exist. Resource directories are deliberately not required:
    # Gradle emits one whether or not a module has resources, when it does.
    local entry
    while IFS= read -r entry; do
        case "$entry" in
            *.jar) [ -e "$entry" ] || return 1 ;;
        esac
    done < <(tr ':' '\n' < "$CP_CACHE")
    return 0
}

if ! cache_is_valid; then
    echo "[run] classpath cache is absent, incomplete or built for another host: re-resolving" >&2
    (cd "$ROOT" && ./gradlew -q :execution-runtime:printTestClasspath) > "$CP_CACHE"
    cache_is_valid || { echo "[run] resolved classpath still fails validation" >&2; exit 1; }
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
# The harness source is data that changes independently of the repository revision, so its own
# digest travels in the artifact beside the SHA that produced it.
HARNESS_SHA=$(sha256sum "$SRC" | cut -d' ' -f1)

# A fixed, pre-touched heap: post-GC heap and the pause figures are only comparable across arms
# when the heap the arms run in is the same one.
java -Xms1g -Xmx1g -XX:+AlwaysPreTouch \
     -cp "$(cat "$CP_CACHE"):$OUT" \
     --enable-native-access=ALL-UNNAMED \
     -Dnanofaas.sha="$SHA" -Dnanofaas.harnessSha="$HARNESS_SHA" -Dnanofaas.artifact=jvm \
     it.unimib.datai.nanofaas.SchedulerSwitchBenchmark \
     --budgets="$HERE/budgets.json" "$@" \
     2> >(tee "$HERE/raw/$LABEL.err" >&2) | tee "$HERE/raw/$LABEL.jsonl"
