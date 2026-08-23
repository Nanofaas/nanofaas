#!/bin/bash
# Porta una run di NanoLab dentro raw/, nella forma che build_tables.py si aspetta.
#
# Fatto a mano quattro volte, e ogni volta con il rischio di dimenticare un file:
# quello che serve per rileggere una cella e' il manifest (che dice il regime),
# k6-summary.json (che ha piu' di summary.json - dropped_iterations e le VU
# vivono solo li'), summary.json, e lo snapshot Prometheus compresso.
#
#   ./archive_run.sh <run-dir-di-nanolab> [nome-in-raw]
set -eu
SRC=${1:?serve la run-dir di NanoLab}
NAME=${2:-$(basename "$SRC")}
HERE=$(cd "$(dirname "$0")" && pwd)
DEST="$HERE/raw/$NAME"

[ -d "$SRC" ] || { echo "non esiste: $SRC" >&2; exit 1; }
mkdir -p "$DEST"
[ -f "$SRC/comparison-manifest.json" ] && cp "$SRC/comparison-manifest.json" "$DEST/"

n=0
for run in "$SRC"/*/run-*; do
    [ -d "$run" ] || continue
    rel=${run#"$SRC/"}
    mkdir -p "$DEST/$rel/metrics"
    for f in k6-summary.json summary.json; do
        [ -f "$run/$f" ] && cp "$run/$f" "$DEST/$rel/"
    done
    snap="$run/metrics/prometheus-snapshot.json"
    # Compresso: 48 MB diventano 2,8, e build_tables legge i due indifferentemente.
    [ -f "$snap" ] && gzip -c "$snap" > "$DEST/$rel/metrics/prometheus-snapshot.json.gz"
    n=$((n + 1))
done

echo "archiviate $n celle in raw/$NAME  ($(du -sh "$DEST" | cut -f1))"
cd "$HERE" && find raw -type f | sort | xargs shasum -a 256 > SHA256SUMS
echo "SHA256SUMS rigenerato: $(wc -l < SHA256SUMS) file"
