#!/bin/bash
# §8: baseline contro candidato, stessa matrice e stessa infrastruttura.
#
#   ./compare-baseline-candidate.sh [ripetizioni]
#
# Due revisioni di nanoFaaS, lo stesso scenario, la stessa macchina, ordine
# ALTERNATO (A,B,A,B,...) perche' una deriva della macchina colpisca i due bracci
# allo stesso modo invece di concentrarsi su quello eseguito per secondo.
#
# Ogni cella archivia i propri artefatti prima della successiva: NanoLab scrive
# sempre in runs/latest e il run seguente lo sovrascrive.
#
# Una cella che PRODUCE un sommario k6 e' un dato valido anche se lo scenario ha
# fallito le proprie asserzioni. In un confronto baseline-contro-candidato la
# baseline puo' legittimamente non superare una soglia tarata sul candidato: e' il
# risultato, non un guasto, e fermarsi li' renderebbe impossibile misurare proprio
# gli attraversamenti di soglia che questa campagna esiste per trovare. Le soglie
# sono un cancello CI; §8 confronta i numeri.
#
# Si ferma invece quando una cella non produce dati: quello e' un guasto
# infrastrutturale, e mezza matrice che sembra completa e' peggio di nessuna.
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

echo "[compare] baseline=$BASELINE_REV candidato=$CANDIDATE_REV scenario=$SCENARIO ripetizioni=$REPS"
git -C "$NANOFAAS" worktree add "$WORKTREES/baseline" "$BASELINE_REV" --detach >/dev/null || exit 1
git -C "$NANOFAAS" worktree add "$WORKTREES/candidate" "$CANDIDATE_REV" --detach >/dev/null || exit 1

cell() {
    # Assegnazioni separate: bash espande TUTTE le parole di `local` prima di
    # eseguirlo, quindi "$OUT/$arm/..." sulla stessa riga leggerebbe un $arm ancora
    # non definito - e con `set -u` la coda muore prima della prima cella.
    local arm=$1
    local rep=$2
    local dir="$OUT/$arm/rep-$rep"
    if [ -f "$dir/k6-summary.json" ]; then
        echo "[compare] $arm rep $rep gia' archiviato, salto"
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
        echo "[compare] $arm rep $rep senza dati (exit $status); coda interrotta"
        return 1
    fi
    if [ "$status" -ne 0 ]; then
        echo "[compare] $arm rep $rep: asserzioni non superate (exit $status), dati archiviati"
    fi
}

for rep in $(seq 1 "$REPS"); do
    # Alternato dentro la ripetizione, non due blocchi: se la macchina rallenta a
    # meta' campagna, rallenta per entrambi i bracci e non solo per il secondo.
    cell baseline "$rep" || exit 1
    # Il teardown del compose rilascia il registry in modo asincrono; partire subito
    # ha gia' prodotto un "connection refused" sul push della cella successiva.
    sleep 10
    cell candidate "$rep" || exit 1
    sleep 10
done

echo "[compare] completata: $OUT"
