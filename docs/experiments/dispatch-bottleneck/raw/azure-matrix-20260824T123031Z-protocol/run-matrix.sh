#!/bin/bash
# La matrice: due composizioni di carico per due intensita', tre ripetizioni.
#
#   sync-baseline-load2x    async 0%,  idem 0%,  scala 2
#   mixed-workload-load2x   async 20%, idem 5%,  scala 2
#   sync-baseline-load3x    async 0%,  idem 0%,  scala 3
#   mixed-workload-load3x   async 20%, idem 5%,  scala 3
#
# Tutti e quattro guidati dallo STESSO generatore (mixed-workload.js), con il mix
# passato come parametro: cosi' la differenza fra un braccio e l'altro e' la
# composizione del carico e nient'altro. Due generatori diversi sui due lati di un
# confronto e' il difetto che questa serie ha gia' pagato due volte.
#
# Girata dopo tre fix, e senza i quali il 3x non era misurabile: la ritenzione per
# lettore in ExecutionStore (0ca4352e), gli event loop dedicati al management
# (aa6bd5f5) e il salto condizionale sul percorso sincrono. Prima di questi il
# control plane veniva ucciso dal proprio probe di liveness dopo 2-6 minuti a 3x,
# con la perdita di tutto lo stato in memoria.
#
# Il teardown parte da solo SOLO se ci sono tutte e dodici le celle: un braccio
# fallito va guardato prima di essere distrutto.
set -eu
export NANOFAAS_ROOT=${NANOFAAS_ROOT:-/Users/micheleciavotta/Downloads/mcFaas/.worktrees/dispatch-instrumentation}
NANOLAB_ROOT=${NANOLAB_ROOT:-/Users/micheleciavotta/Downloads/nanolab/.worktrees/dispatch-instrumentation}
cd "$NANOLAB_ROOT"
R=packages/nanolab/runs
E=packages/nanolab/environments/azure-comparison.yaml
MATRIX_ID=${MATRIX_ID:-$(date -u '+%Y%m%dT%H%M%SZ')}
RUN_PREFIX="$R/azure-matrix-$MATRIX_ID"

# Un rilascio Helm lasciato in piedi da un run interrotto verrebbe RIUSATO, e il
# tag :jvm-c2 e' mutabile: la cella misurerebbe l'immagine precedente.
stack_ip=$(az network public-ip list -g maurinoRicerca-rg \
  --query "[?name == 'nanofaas-comparison-pip'].ipAddress | [0]" -o tsv)
if [ -n "$stack_ip" ]; then
  ssh -o StrictHostKeyChecking=no "azureuser@$stack_ip" \
    'export KUBECONFIG=/etc/rancher/k3s/k3s.yaml; sudo -E helm uninstall nanofaas -n nanofaas-e2e --ignore-not-found'
fi

for s in sync-baseline-load2x mixed-workload-load2x sync-baseline-load3x mixed-workload-load3x; do
  echo "$(date '+%F %T') === $s, 3 ripetizioni ==="
  ./nanolab.sh compare "packages/nanolab/scenarios-v2/$s.yaml" --environment "$E" \
    --run-dir "$RUN_PREFIX-$s" --variants jvm-c2 --repetitions 3
  echo "$(date '+%F %T') $s rc=$?  celle=$(find "$RUN_PREFIX-$s" -name k6-summary.json 2>/dev/null | wc -l | tr -d ' ')"
done

celle=$(find "$R" -type f -path "$RUN_PREFIX-*/jvm-c2/run-*/k6-summary.json" 2>/dev/null | wc -l | tr -d ' ')
echo "$(date '+%F %T') === matrice finita: $celle/12 celle ==="
if [ "$celle" -eq 12 ]; then
  ./teardown.sh
else
  echo "$(date '+%F %T') === $celle celle su 12: le VM restano in piedi per essere guardate ==="
fi
