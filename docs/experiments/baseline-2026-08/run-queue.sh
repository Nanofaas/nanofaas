#!/bin/bash
# Esegue la coda degli esperimenti senza bisogno di nessuno.
#
# Per ogni riga di queue.tsv: lancia la matrice, archivia, calcola le tabelle,
# le scrive nel documento, committa, e distrugge le VM. Aggiorna STATO.md dopo
# ogni passo, cosi' guardare quel file basta per sapere dov'e' arrivato.
#
# Tre proprieta' volute, in ordine di importanza:
#
#   * FALLISCE CHIUDENDO. Qualunque errore -> teardown e stop. Un run rotto che
#     lascia due VM accese per otto ore costa piu' del run.
#   * RIPRENDE. Un run gia' archiviato viene saltato, quindi rilanciare lo
#     script dopo un'interruzione non ripete ore di celle corrette.
#   * NON DIPENDE DA ME. E' un processo solo: se la sessione che lo ha lanciato
#     sparisce, la coda va avanti lo stesso.
set -uo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
NANOLAB=/Users/micheleciavotta/Downloads/nanolab
MCFAAS=/Users/micheleciavotta/Downloads/mcFaas
RUNS="$NANOLAB/packages/nanolab/runs/baseline-2026-08"
GROUP=maurinoRicerca-rg
STATO="$HERE/STATO.md"

stato() {
    printf '%s  %s\n' "$(date '+%Y-%m-%d %H:%M')" "$1" >> "$STATO"
    echo "[coda] $1"
}

teardown() {
    stato "teardown in corso"
    az vm delete -g "$GROUP" -n nanofaas-comparison --yes --no-wait >/dev/null 2>&1
    az vm delete -g "$GROUP" -n nanofaas-comparison-loadgen --yes --no-wait >/dev/null 2>&1
    az vm wait -g "$GROUP" -n nanofaas-comparison --deleted >/dev/null 2>&1
    az vm wait -g "$GROUP" -n nanofaas-comparison-loadgen --deleted >/dev/null 2>&1
    for n in nanofaas-comparison-nic nanofaas-comparison-loadgen-nic; do
        az network nic delete -g "$GROUP" -n "$n" >/dev/null 2>&1
    done
    for _pass in 1 2 3; do
        for r in $(az resource list -g "$GROUP" --query "[?contains(name,'nanofaas')].id" -o tsv 2>/dev/null); do
            az resource delete --ids "$r" >/dev/null 2>&1
        done
    done
    local left
    left=$(az resource list -g "$GROUP" --query "[?contains(name,'nanofaas')].name" -o tsv 2>/dev/null)
    if [ -n "$left" ]; then
        stato "**RESIDUO AZURE DA CANCELLARE A MANO**: $left"
        return 1
    fi
    stato "teardown completo, nessuna risorsa residua"
}

# Non partire sopra un'altra matrice: due run insieme si contendono gli stessi
# nomi di VM e si distruggerebbero a vicenda.
while pgrep -f "nanolab compare" >/dev/null 2>&1; do
    stato "attendo la matrice gia' in corso"
    sleep 120
done

# Il ciclo legge da una sostituzione di processo, non da una pipe: una pipe lo
# metterebbe in una subshell, dove `exit 1` chiude solo quella e la coda
# stamperebbe "esaurita" subito dopo aver fallito.
while IFS=$'\t' read -r NAME SCENARIO VARIANTS KEEP; do
    if [ -d "$HERE/raw/$NAME" ] && [ -n "$(ls "$HERE/raw/$NAME" 2>/dev/null)" ]; then
        stato "$NAME: gia' archiviato, salto"
        continue
    fi
    stato "$NAME: avvio ($SCENARIO, varianti $VARIANTS)"
    (cd "$NANOLAB" && NANOFAAS_ROOT="$MCFAAS" caffeinate -dimsu ./nanolab.sh compare \
        "packages/nanolab/scenarios-v2/$SCENARIO" \
        --environment packages/nanolab/environments/azure-comparison.yaml \
        --repetitions 3 --variants "$VARIANTS" \
        --run-dir "packages/nanolab/runs/baseline-2026-08/$NAME" \
        > "$RUNS/$NAME.log" 2>&1)
    if [ $? -ne 0 ]; then
        stato "$NAME: **FALLITO** (vedi $RUNS/$NAME.log)"
        teardown
        stato "coda interrotta"
        exit 1
    fi
    stato "$NAME: matrice finita"

    IP=$(az vm list-ip-addresses -g "$GROUP" -n nanofaas-comparison \
         --query "[0].virtualMachine.network.publicIpAddresses[0].ipAddress" -o tsv 2>/dev/null)
    "$HERE/collect.sh" "$RUNS/$NAME" "$NAME" "$IP" >> "$STATO" 2>&1
    cp "$NANOLAB/packages/nanolab/scenarios-v2/$SCENARIO" "$HERE/raw/$NAME/scenario.yaml" 2>/dev/null
    stato "$NAME: archiviato"

    (cd "$NANOLAB" && uv run --project packages/sonata-tasks python3 "$HERE/tables.py" \
        "$HERE/raw/$NAME" --doc "$HERE/README.md" --marker "$NAME" > "$HERE/raw/$NAME/tabella.md" 2>&1)
    stato "$NAME: tabelle calcolate e scritte nel documento"

    (cd "$MCFAAS" && git add docs/experiments/baseline-2026-08 \
        && git commit -q -m "experiment: $NAME ($SCENARIO, $VARIANTS)" \
        && stato "$NAME: committato $(cd "$MCFAAS" && git rev-parse --short HEAD)")

    if [ "$KEEP" = "1" ]; then
        stato "$NAME: VM tenuta in piedi per il run successivo"
    else
        teardown || { stato "coda interrotta sul teardown"; exit 1; }
    fi
done < <(grep -v '^#' "$HERE/queue.tsv" | grep -v '^[[:space:]]*$')

stato "coda esaurita"
