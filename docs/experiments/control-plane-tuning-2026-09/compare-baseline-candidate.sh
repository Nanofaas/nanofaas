#!/bin/bash
# Section 8: baseline against candidate, same matrix and same infrastructure.
#
#   ./compare-baseline-candidate.sh [repetitions]
#
# Two nanoFaaS revisions, the same scenario, the same machine, ALTERNATED order
# (A,B,A,B,...) so that a drift of the machine hits both arms equally instead of
# concentrating on whichever ran second.
#
# Each cell archives its own artefacts before the next one starts: NanoLab always
# writes to runs/latest, and the following run overwrites it.
#
# A cell that PRODUCES a k6 summary is valid data even when the scenario failed
# its own assertions. In a baseline-versus-candidate comparison the baseline may
# legitimately miss a threshold calibrated for the candidate: that is the result,
# not a fault, and stopping there would make the very threshold crossings this
# campaign exists to find unmeasurable. Thresholds are a CI gate; section 8
# compares the numbers.
#
# It does stop when a cell produces no data: that is an infrastructure failure,
# and half a matrix that looks complete is worse than none.
set -uo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
NANOFAAS=$(cd "$HERE/../../.." && pwd)
NANOLAB=${NANOLAB:-/home/michele/Documenti/nanolab}
SCENARIO=${SCENARIO:-concurrency-cycle-container}
REPS=${1:-3}
OUT="$HERE/raw/compare-$SCENARIO"
WORKTREES=$(mktemp -d)

BASELINE_REV=${BASELINE_REV:-e35405ee}
CANDIDATE_REV=${CANDIDATE_REV:-HEAD}

mkdir -p "$OUT"

cleanup() {
    for arm in baseline candidate; do
        [ -d "$WORKTREES/$arm" ] && git -C "$NANOFAAS" worktree remove --force "$WORKTREES/$arm" 2>/dev/null
    done
    rm -rf "$WORKTREES"
}
trap cleanup EXIT

echo "[compare] baseline=$BASELINE_REV candidate=$CANDIDATE_REV scenario=$SCENARIO repetitions=$REPS"
git -C "$NANOFAAS" worktree add "$WORKTREES/baseline" "$BASELINE_REV" --detach >/dev/null || exit 1
git -C "$NANOFAAS" worktree add "$WORKTREES/candidate" "$CANDIDATE_REV" --detach >/dev/null || exit 1

cell() {
    # Separate assignments: bash expands ALL the words of `local` before running
    # it, so "$OUT/$arm/..." on the same line would read an $arm that is not yet
    # defined - and under `set -u` the queue dies before the first cell.
    local arm=$1
    local rep=$2
    local dir="$OUT/$arm/rep-$rep"
    if [ -f "$dir/k6-summary.json" ]; then
        echo "[compare] $arm rep $rep already archived, skipping"
        return 0
    fi
    echo "[compare] $arm rep $rep ..."
    mkdir -p "$dir"
    ( cd "$NANOLAB" && NANOFAAS_ROOT="$WORKTREES/$arm" ./nanolab.sh run \
        "packages/nanolab/scenarios-v2/$SCENARIO.yaml" \
        --environment packages/nanolab/environments/local.yaml ) > "$dir/run.log" 2>&1
    local status=$?
    echo "$status" > "$dir/exit-code"
    for f in k6-summary.json concurrency-series.json summary.json run-metadata.json; do
        cp "$NANOLAB/packages/nanolab/runs/latest/$f" "$dir/" 2>/dev/null
    done
    if [ ! -f "$dir/k6-summary.json" ]; then
        echo "[compare] $arm rep $rep produced no data (exit $status); queue stopped"
        return 1
    fi
    if [ "$status" -ne 0 ]; then
        echo "[compare] $arm rep $rep: assertions not met (exit $status), data archived"
    fi
}

for rep in $(seq 1 "$REPS"); do
    # Alternated within the repetition, not two blocks: if the machine slows down
    # mid-campaign, it slows for both arms and not only for the second.
    cell baseline "$rep" || exit 1
    # The compose teardown releases the registry asynchronously; starting at once
    # has already produced a "connection refused" on the next cell's push.
    sleep 10
    cell candidate "$rep" || exit 1
    sleep 10
done

echo "[compare] complete: $OUT"
