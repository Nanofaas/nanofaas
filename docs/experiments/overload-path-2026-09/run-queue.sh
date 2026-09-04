#!/bin/bash
# Esegue la coda degli esperimenti senza bisogno di nessuno.
#
# Adattato da docs/experiments/baseline-2026-08/run-queue.sh (2026-08-25) per
# la campagna 2026-09-04-overload-path-fixes: stessa logica, percorsi dedotti
# invece che scritti a mano (lo script originale aveva un path Mac che non
# esiste su questa macchina), e RUNS/nome campagna aggiornati.
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
#
# NON lanciare questo script senza aver letto il README della campagna: crea
# VM Azure reali, a pagamento, e le distrugge da solo a fine coda o in caso di
# errore. Richiede `az login` gia' fatto e GROUP (sotto) allineato al
# resource_group dentro packages/nanolab/environments/azure-comparison.yaml.
set -uo pipefail

# Tiene sveglia la macchina per tutta la vita della coda. Su macOS: caffeinate.
# Su Linux non c'e' un equivalente a riga singola equivalente per l'intera
# durata di uno script figlio; se questa coda gira su un laptop che si
# addormenta, avvolgerla a mano con `systemd-inhibit --what=sleep -- ./run-queue.sh`.
if command -v caffeinate >/dev/null 2>&1; then
    caffeinate -dimsu -w $$ &
fi

HERE=$(cd "$(dirname "$0")" && pwd)
MCFAAS=$(cd "$HERE/../../.." && pwd)
NANOLAB=$(cd "$MCFAAS/../nanolab" && pwd)
CAMPAIGN=overload-path-2026-09
RUNS="$NANOLAB/packages/nanolab/runs/$CAMPAIGN"
GROUP=${GROUP:-maurinoRicerca-rg}
STATO="$HERE/STATO.md"

mkdir -p "$RUNS"

stato() {
    printf '%s  %s\n' "$(date '+%Y-%m-%d %H:%M')" "$1" >> "$STATO"
    echo "[coda] $1"
}

stato "avvio coda: MCFAAS=$MCFAAS NANOLAB=$NANOLAB GROUP=$GROUP"

# La rete perde il DNS a intermittenza (osservato sulla macchina originale):
# `host` risolve e un minuto dopo `az` non trova management.azure.com. Una
# matrice ci e' gia' morta dentro dopo sette celle. Aspettare e' quasi sempre
# giusto, perche' l'attesa costa minuti e la ripartenza costa ore.
attendi_azure() {
    local minuti=${1:-60} i=0
    while ! az account show >/dev/null 2>&1; do
        i=$((i + 1))
        if [ "$i" -ge "$minuti" ]; then
            stato "Azure irraggiungibile da ${minuti} minuti: mi fermo senza lanciare altro"
            return 1
        fi
        [ $((i % 5)) -eq 1 ] && stato "Azure irraggiungibile, attendo (${i}/${minuti} min)"
        sleep 60
    done
    [ "$i" -gt 0 ] && stato "Azure tornato raggiungibile dopo ${i} min"
    return 0
}

# Un rilascio Helm lasciato in piedi da un run precedente verrebbe RIUSATO. Con
# un tag mutabile la cella misurerebbe l'immagine precedente; peggio, il pod
# resterebbe quello di prima, con la sua coda e il suo ExecutionStore dentro -
# che per un esperimento sulla coda e' contaminazione, non risparmio.
#
# Serve solo quando la VM viene ereditata dal run precedente: dopo un teardown
# non c'e' nulla da disinstallare.
pulisci_helm() {
    local ip
    ip=$(az network public-ip list -g "$GROUP" \
        --query "[?name == 'nanofaas-comparison-pip'].ipAddress | [0]" -o tsv 2>/dev/null)
    [ -n "$ip" ] && [ "$ip" != "None" ] || return 0
    stato "disinstallo il rilascio Helm ereditato su $ip"
    ssh -i ~/.ssh/id_rsa -o StrictHostKeyChecking=no -o BatchMode=yes -o ConnectTimeout=10 \
        "azureuser@$ip" 'export KUBECONFIG=/etc/rancher/k3s/k3s.yaml; sudo -E helm uninstall nanofaas -n nanofaas-e2e --ignore-not-found' \
        < /dev/null >> "$STATO" 2>&1 || {
            stato "**disinstallazione Helm non riuscita: mi fermo**"
            return 1
        }
}

teardown() {
    attendi_azure 120 || { stato "**teardown impossibile: VM ancora accese**"; return 1; }
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
    # La verifica deve distinguere "non c'e' niente" da "non ho potuto chiedere".
    # Senza questa distinzione un DNS caduto rendeva vuota la lista e il teardown
    # dichiarava successo lasciando accese due VM (successo il 2026-08-25 alle
    # 17:58 nella campagna precedente) - il modo peggiore di sbagliare, perche'
    # non chiede aiuto.
    local left rc
    left=$(az resource list -g "$GROUP" --query "[?contains(name,'nanofaas')].name" -o tsv 2>&1)
    rc=$?
    if [ $rc -ne 0 ] || [[ "$left" == *ERROR* ]]; then
        stato "**TEARDOWN NON VERIFICABILE, VM FORSE ACCESE**: $left"
        return 1
    fi
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
while IFS=$'\t' read -r -u 9 NAME SCENARIO VARIANTS KEEP MARKER; do
    MARKER=${MARKER:-$NAME}
    if [ -f "$HERE/raw/$NAME/.complete" ]; then
        stato "$NAME: gia' archiviato, salto"
        continue
    fi
    if [ -d "$HERE/raw/$NAME" ]; then
        stato "$NAME: elimino l'archivio parziale prima di ripartire"
        rm -rf "$HERE/raw/$NAME" || { stato "$NAME: **ARCHIVIO PARZIALE NON ELIMINABILE**"; exit 1; }
    fi
    attendi_azure 60 || exit 1
    pulisci_helm || { stato "coda interrotta sulla pulizia Helm"; exit 1; }
    stato "$NAME: avvio ($SCENARIO, varianti $VARIANTS)"
    (cd "$NANOLAB" && NANOFAAS_ROOT="$MCFAAS" ./nanolab.sh compare \
        "packages/nanolab/scenarios-v2/$SCENARIO" \
        --environment packages/nanolab/environments/azure-comparison.yaml \
        --repetitions 3 --variants "$VARIANTS" \
        --run-dir "packages/nanolab/runs/$CAMPAIGN/$NAME" \
        < /dev/null > "$RUNS/$NAME.log" 2>&1)
    if [ $? -ne 0 ]; then
        stato "$NAME: **FALLITO** (vedi $RUNS/$NAME.log)"
        teardown
        stato "coda interrotta"
        exit 1
    fi
    stato "$NAME: matrice finita"

    IP=$(az vm list-ip-addresses -g "$GROUP" -n nanofaas-comparison \
         --query "[0].virtualMachine.network.publicIpAddresses[0].ipAddress" -o tsv 2>/dev/null)
    if ! "$HERE/collect.sh" "$RUNS/$NAME" "$NAME" "$IP" < /dev/null >> "$STATO" 2>&1; then
        stato "$NAME: **ARCHIVIAZIONE FALLITA**"
        teardown
        stato "coda interrotta"
        exit 1
    fi
    if ! cp "$NANOLAB/packages/nanolab/scenarios-v2/$SCENARIO" "$HERE/raw/$NAME/scenario.yaml"; then
        stato "$NAME: **COPIA DELLO SCENARIO FALLITA**"
        teardown
        exit 1
    fi

    if ! (cd "$NANOLAB" && uv run --project packages/sonata-tasks python3 "$HERE/tables.py" \
        "$HERE/raw/$NAME" --doc "$HERE/README.md" --marker "$MARKER" > "$HERE/raw/$NAME/tabella.md" 2>&1); then
        stato "$NAME: **CALCOLO DELLE TABELLE FALLITO**"
        teardown
        exit 1
    fi
    stato "$NAME: tabelle calcolate e scritte nel documento"

    if ! (cd "$HERE" && find raw -type f ! -name SHA256SUMS ! -name .complete \
        | sort | xargs shasum -a 256 > SHA256SUMS); then
        stato "$NAME: **CALCOLO DEI CHECKSUM FALLITO**"
        teardown
        exit 1
    fi
    touch "$HERE/raw/$NAME/.complete" || { stato "$NAME: **MARKER DI COMPLETAMENTO FALLITO**"; teardown; exit 1; }
    stato "$NAME: archiviato e verificabile"

    (cd "$MCFAAS" && git add "docs/experiments/$CAMPAIGN" \
        && git commit -q -m "experiment: $NAME ($SCENARIO, $VARIANTS)" \
        && stato "$NAME: committato $(cd "$MCFAAS" && git rev-parse --short HEAD)")

    if [ "$KEEP" = "1" ]; then
        stato "$NAME: VM tenuta in piedi per il run successivo"
    else
        teardown || { stato "coda interrotta sul teardown"; exit 1; }
    fi
done 9< <(grep -v '^#' "$HERE/queue.tsv" | grep -v '^[[:space:]]*$')

stato "coda esaurita"
