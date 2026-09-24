#!/bin/bash
set -euo pipefail
ROOT=/home/michele/Documenti/nanofaas
RAW="$ROOT/docs/experiments/retry-backoff-2026-09/raw"
for variant in baseline candidate; do
    if [ "$variant" = baseline ]; then
        SOURCE=/tmp/retry-backoff-baseline-a17289c2
    else
        SOURCE="$ROOT"
    fi
    HERE="$SOURCE/docs/experiments/scheduler-switching-2026-09"
    java -Xms1g -Xmx1g -XX:+AlwaysPreTouch \
        "-XX:StartFlightRecording=filename=$RAW/$variant-diagnostic.jfr,settings=profile" \
        -cp "$(cat "$HERE/.classpath-execution-runtime"):$HERE/.classes" \
        --enable-native-access=ALL-UNNAMED \
        -Dnanofaas.sha=a17289c2e974e280c1f299e2f871f034c0c632aa \
        it.unimib.datai.nanofaas.SchedulerSwitchBenchmark \
        --budgets="$HERE/budgets.json" --parts=profiles --profiles=low-load \
        --repetitions=1 --span-ms=2000 --delayed-percent=0 \
        > "$RAW/$variant-diagnostic.log" 2>&1
    jfr summary "$RAW/$variant-diagnostic.jfr" > "$RAW/$variant-jfr-summary.txt"
done
