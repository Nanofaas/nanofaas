#!/bin/bash
#
# ---------------------------------------------------------------------------------------------
# TOMBSTONE (Task 13b, issue #208, 2026-09-21): THIS SCRIPT CAN NO LONGER BUILD, for either arm.
#
# `OldLoopComparison.java` imports `it.unimib.datai.nanofaas.modules.asyncqueue.Scheduler` (line
# 27), and Task 13b deleted that class together with
# `modules.syncqueue.scheduler.SyncScheduler`: once Tasks 1-12 had composed one `SchedulerEngine`
# and 13a had made its strategy selectable and hot-switchable, the per-module loops had no consumer
# left. The old arm's subject does not exist at this commit or after it, so the harness cannot be
# compiled and 12e cannot be re-run from this tree.
#
# What this changes, and what it does not:
#   - The failure is fail-closed and loud. `set -euo pipefail` aborts at the `javac` below, before
#     any `raw/$LABEL.jsonl` is opened, so a re-run cannot leave a half-written or empty artifact
#     behind for someone to analyse. Non-zero exit, nothing written.
#   - 12e's RESULTS still stand: `raw/old-vs-new.jsonl` (+ `.err`, `-analysis.txt`), `smoke-old.*`,
#     `old-vs-new.py` and OLD-VS-NEW.md are committed artifacts of a build that DID compile, at
#     revision `c64da071`, with the harness digest the artifact's own header records. Re-reading or
#     re-analyzing them does not need the harness to compile.
#   - `OldLoopComparison.java` is deliberately NOT edited: its bytes are the digest OLD-VS-NEW.md
#     §7.1 pins (`761908913d93…`), so editing it would invalidate a provenance claim for no benefit.
#     It is kept as the record of what produced the committed artifact.
#
# To re-measure the old loop you need the loop: check out a revision before Task 13b (the last one
# that compiles this harness is 25388b1a) and run the script there.
# ---------------------------------------------------------------------------------------------
#
# Runs the old-loop-vs-new-engine comparison harness against :execution-runtime's test classpath
# (issue #208, Task 12e).
#
#   ./run-old.sh [--label=NAME] [--harness options...]
#
# Examples:
#   ./run-old.sh --label=smoke-old --profiles=low-load --repetitions=1
#   ./run-old.sh --label=old-vs-new
#
# The label is the raw/ artifact's name, so it must not collide with one of the committed
# harness's: raw/smoke.jsonl, raw/steady.jsonl, raw/full.jsonl and raw/baseline.jsonl are that
# campaign's and overwriting one would destroy the artifact a reviewed result is quoted from.
#
# Harness options: --profiles=NAME[,NAME], --repetitions=N, --arms=old,new, --out=PATH.
#
# This harness is compiled together with SchedulerSwitchBenchmark.java, so the corpus, the
# span/warm-up constants and the trailing-window grid it uses are the committed harness's own and
# the two cannot drift.
#
# The classpath is :control-plane-modules:async-queue's TEST classpath, not
# :execution-runtime's: the old loop under test lives in that module, and its test classpath is the
# one that carries it together with :execution-runtime's engine and Spring (Scheduler implements
# SmartLifecycle, so the type does not resolve without it). It is a superset — it carries
# :execution-runtime, :control-plane and both queue modules as jars.
#
# Those jars are rebuilt first: a stale queue jar would silently drive a different revision of the
# old loop than the one being reported. Gradle is incremental, so an up-to-date jar costs a second.
#
# Gradle's daemon is stopped before the measured JVM starts. Nothing of Gradle's — and nothing
# else CPU-bound — may be running while a sample is taken: for a latency, CPU or post-GC heap
# figure a concurrent run does not corrupt a file, it corrupts the number, silently.
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../../.." && pwd)
CP_CACHE="$HERE/.classpath-async-queue"

cache_is_valid() {
    [ -s "$CP_CACHE" ] || return 1
    # The compiled output of the module under test and of the engine must be there...
    grep -q 'async-queue' "$CP_CACHE" || return 1
    grep -q 'execution-runtime' "$CP_CACHE" || return 1
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

LABEL=run
for arg in "$@"; do
    case "$arg" in
        --label=*) LABEL="${arg#--label=}" ;;
    esac
done
[ -n "$LABEL" ] || { echo "[run-old] --label must not be empty" >&2; exit 1; }

# The old loop's own classes must come from this revision, not from whatever the last build left.
(cd "$ROOT" && ./gradlew -q :control-plane-modules:async-queue:jar > /dev/null)

if ! cache_is_valid; then
    echo "[run-old] classpath cache is absent, incomplete or built for another host: re-resolving" >&2
    (cd "$ROOT" && ./gradlew -q :control-plane-modules:async-queue:printTestClasspath) > "$CP_CACHE"
    cache_is_valid || { echo "[run-old] resolved classpath still fails validation" >&2; exit 1; }
fi

SRC="$HERE/OldLoopComparison.java"
BENCH="$HERE/SchedulerSwitchBenchmark.java"
[ -f "$SRC" ] || { echo "[run-old] harness not found: $SRC" >&2; exit 1; }
[ -f "$BENCH" ] || { echo "[run-old] committed harness not found: $BENCH" >&2; exit 1; }

# Compile rather than use the source launcher: the launcher would run the harness in a child
# process with a different heap, and the heap configuration is part of the measurement. Both files
# are compiled together because this harness shares SchedulerSwitchBenchmark's package to reuse its
# corpus definition.
OUT="$HERE/.classes-old"
rm -rf "$OUT" && mkdir -p "$OUT"
javac -nowarn -cp "$(cat "$CP_CACHE")" -d "$OUT" "$BENCH" "$SRC"

# The old loop logs through logback, whose default configuration writes to STDOUT — the stream the
# JSONL artifact is on. A @PostConstruct log line landing mid-line makes the artifact unparseable,
# silently. Routing the root logger to STDERR keeps the artifact pure and loses no diagnostic: the
# loop's own error path (`Scheduler`'s catch) still reaches raw/$LABEL.err.
cat > "$OUT/logback.xml" <<'LOGBACK'
<configuration>
  <appender name="STDERR" class="ch.qos.logback.core.ConsoleAppender">
    <target>System.err</target>
    <encoder><pattern>%d{HH:mm:ss.SSS} %-5level %logger{36} - %msg%n</pattern></encoder>
  </appender>
  <root level="INFO"><appender-ref ref="STDERR"/></root>
</configuration>
LOGBACK

mkdir -p "$HERE/raw"

# Nothing of Gradle's may be running while a sample is taken.
(cd "$ROOT" && ./gradlew --stop > /dev/null 2>&1) || true

SHA=$(cd "$ROOT" && git rev-parse HEAD)
HARNESS_SHA=$(sha256sum "$SRC" | cut -d' ' -f1)
BENCH_SHA=$(sha256sum "$BENCH" | cut -d' ' -f1)

# A fixed, pre-touched heap: post-GC heap and the CPU figures are comparable across arms only when
# the arms run in the same JVM, and this harness runs both arms in ONE JVM so that they do.
java -Xms1g -Xmx1g -XX:+AlwaysPreTouch \
     -cp "$(cat "$CP_CACHE"):$OUT" \
     --enable-native-access=ALL-UNNAMED \
     -Dlogback.configurationFile="$OUT/logback.xml" \
     -Dnanofaas.sha="$SHA" -Dnanofaas.harnessSha="$HARNESS_SHA" \
     -Dnanofaas.benchmarkSha="$BENCH_SHA" -Dnanofaas.artifact=jvm \
     it.unimib.datai.nanofaas.OldLoopComparison \
     "$@" \
     2> >(tee "$HERE/raw/$LABEL.err" >&2) | tee "$HERE/raw/$LABEL.jsonl"
