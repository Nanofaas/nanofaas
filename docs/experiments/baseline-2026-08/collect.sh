#!/bin/bash
# Porta una run di NanoLab dentro raw/, nella forma che tables.py si aspetta.
#
#   ./collect.sh <run-dir-di-nanolab> <nome-in-raw> [<ip-della-vm>]
#
# Se l'IP e' dato, prima di archiviare chiede alla VM la dimensione delle
# immagini del control plane: e' l'unico momento in cui esistono, e sparisce
# con il teardown. Va fatto PRIMA di distruggere la VM, non dopo.
set -eu
SRC=${1:?serve la run-dir di NanoLab}
NAME=${2:?serve il nome in raw/}
VM=${3:-}
HERE=$(cd "$(dirname "$0")" && pwd)
DEST="$HERE/raw/$NAME"

[ -d "$SRC" ] || { echo "non esiste: $SRC" >&2; exit 1; }
mkdir -p "$DEST"

if [ -n "$VM" ]; then
    echo "dimensioni immagini da $VM..."
    ssh -i ~/.ssh/id_rsa -o StrictHostKeyChecking=no -o BatchMode=yes -o ConnectTimeout=10 \
        "azureuser@$VM" 'docker images --format "{{.Repository}}:{{.Tag}} {{.Size}}"' \
        | python3 -c '
import json, re, sys
# docker stampa una riga per immagine, "repo:tag 244MB" oppure "repo:tag 1.02GB".
# Normalizzo a MB: una tabella con due unita mescolate e una tabella che
# qualcuno leggera male. Niente apostrofi qui dentro, lo script vive fra apici.
scale = {"B": 1e-6, "kB": 1e-3, "MB": 1.0, "GB": 1e3}
pattern = re.compile(r"control-plane:(\S+)\s+([0-9.]+)([kMGB]+)")
out = {}
for line in sys.stdin:
    found = pattern.search(line)
    if found:
        out[found.group(1)] = float(found.group(2)) * scale.get(found.group(3), float("nan"))
print(json.dumps(out, indent=1))' > "$DEST/image-sizes.json" || echo "  (non riuscito: continuo senza)"
fi

[ -f "$SRC/comparison-manifest.json" ] && cp "$SRC/comparison-manifest.json" "$DEST/"
# Il log e" l"unico posto dove vivono i tempi di compilazione: la fase di build
# non lascia nessun altro record, e con il teardown sparisce anche la VM.
[ -f "$SRC.log" ] && cp "$SRC.log" "$DEST/run.log"
for extra in "$SRC"/*.html; do
    [ -f "$extra" ] && cp "$extra" "$DEST/"
done

n=0
for run in "$SRC"/*/run-*; do
    [ -d "$run" ] || continue
    rel=${run#"$SRC/"}
    mkdir -p "$DEST/$rel/metrics"
    for f in k6-summary.json summary.json; do
        [ -f "$run/$f" ] && cp "$run/$f" "$DEST/$rel/"
    done
    snap="$run/metrics/prometheus-snapshot.json"
    # Compresso: 48 MB diventano 2,8, e tables.py legge i due indifferentemente.
    [ -f "$snap" ] && gzip -c "$snap" > "$DEST/$rel/metrics/prometheus-snapshot.json.gz"
    n=$((n + 1))
done

echo "archiviate $n celle in raw/$NAME  ($(du -sh "$DEST" | cut -f1))"
