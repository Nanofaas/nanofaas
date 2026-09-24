#!/bin/bash
set -euo pipefail
ROOT=/home/michele/Documenti/nanofaas
RAW="$ROOT/docs/experiments/retry-backoff-2026-09/raw"
jfr configure --input profile.jfc --output /tmp/retry-task7-high.jfc \
    jdk.ExecutionSample#period=1ms jdk.ThreadPark#threshold=0ms > "$RAW/high-profile-settings.log"
for variant in baseline candidate; do
    if [ "$variant" = baseline ]; then
        SOURCE=/tmp/retry-backoff-baseline-a17289c2
    else
        SOURCE="$ROOT"
    fi
    HERE="$SOURCE/docs/experiments/scheduler-switching-2026-09"
    javac -cp "$(cat "$HERE/.classpath-execution-runtime"):$HERE/.classes" \
        -d "$HERE/.classes" /tmp/RetryBackoffProfile.java
    java -Xms1g -Xmx1g -XX:+AlwaysPreTouch \
        "-XX:StartFlightRecording=filename=$RAW/$variant-focused.jfr,settings=/tmp/retry-task7-high.jfc" \
        -cp "$(cat "$HERE/.classpath-execution-runtime"):$HERE/.classes" \
        --enable-native-access=ALL-UNNAMED it.unimib.datai.nanofaas.RetryBackoffProfile \
        > "$RAW/$variant-focused.log" 2>&1
    jfr summary "$RAW/$variant-focused.jfr" > "$RAW/$variant-focused-summary.txt"
done
