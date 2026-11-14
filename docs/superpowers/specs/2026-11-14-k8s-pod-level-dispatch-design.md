# Dispatch per pod su Kubernetes con Service headless

Data: 2026-11-14. Stato: proposta, da approvare.

## Intento

Su Kubernetes il control plane oggi invia ogni invocazione all'URL del Service
`ClusterIP` della funzione (`http://fn-<nome>.<ns>.svc.cluster.local:8080/invoke`).
kube-proxy bilancia **per connessione TCP**, e il client HTTP del control plane
tiene le connessioni aperte (keep-alive, `max-life-time-ms: 0`). Dopo uno
scale-up i pod nuovi possono quindi ricevere poco o nessun traffico finché le
connessioni esistenti restano vive.

Il control plane deve scegliere lui il pod di ogni richiesta, come già fa per i
container locali (container-local e containerd) con `ManagedReplicaPool`. Il
Service della funzione diventa headless: resta la fonte della lista dei pod
pronti, ma non c'è più un IP virtuale in mezzo.

## Obiettivi

- Ogni invocazione verso una funzione `DEPLOYMENT` su Kubernetes va a un pod
  scelto dal control plane, a turno fra i pod pronti (lo stesso pool dei
  container).
- Un pod diventato pronto riceve traffico entro pochi secondi; un pod che
  termina non riceve nuove richieste e completa quelle in volo.
- Funziona con tutti i modi di scalare: autoscaler interno (`setReplicas`), HPA
  e scalature manuali.

## Non obiettivi

- Controllo fisico delle repliche one-shot (slot fisici, prova di rilascio) su
  Kubernetes: resta non supportato (`supportsPhysicalReplicaControl` = false).
  Il pool lo renderebbe possibile in seguito, ma non fa parte di questo lavoro.
- Cambiare come si crea o scala il Deployment, o l'HPA.
- Compatibilità all'indietro: niente doppio percorso (Service `ClusterIP` più
  pool), niente flag per tornare al comportamento vecchio.

## Anti-obiettivi

- Scoprire i pod via DNS del Service headless: la cache di CoreDNS (30 s di
  default) e quella della JVM ritardano i pod nuovi e mantengono quelli rimossi,
  cioè proprio il problema da togliere.
- Un watch per funzione che apre una connessione all'API server per ciascuna:
  con molte funzioni diventa un carico inutile.

## Vincoli

- Il control plane resta un singolo pod con stato in memoria.
- Gira anche come immagine native (GraalVM): i tipi Fabric8 letti per
  riflessione vanno registrati in `Fabric8RuntimeHints`.
- Il modulo `k8s-deployment-provider` può dipendere solo da contratti e librerie
  condivise (`ModuleBoundaryArchitectureTest`); `container-deployment-runtime` è
  una libreria condivisa, già usata dai provider container e containerd.
- Il control plane può leggere `pods`, `services`, `deployments`, ma non gli
  EndpointSlice: il permesso va aggiunto al Role del chart Helm.

## Approcci considerati

- **A. Seguire gli EndpointSlice del Service headless (scelto).** Un watch
  sull'API (`discovery.k8s.io/v1`) consegna in meno di un secondo ogni
  cambiamento, con le condizioni che Kubernetes calcola già per ogni endpoint
  (`ready`, `serving`, `terminating`). Costa un permesso RBAC in più e i metadati
  per la build native.
- **B. Risolvere il DNS del Service headless a intervalli.** Nessun permesso
  nuovo, ma le cache DNS ritardano i pod nuovi di decine di secondi e lasciano in
  lista quelli rimossi; nessun segnale di "terminating". Scartato perché va
  contro lo scopo.
- **C. Seguire i Pod per etichetta.** Richiede di ricalcolare readiness e
  terminazione che gli EndpointSlice danno già. Scartato.

Scelto A dall'utente il 2026-11-14.

## Design

### Service headless

`KubernetesDeploymentBuilder.buildService` crea il Service con
`clusterIP: None`. Restano uguali nome (`fn-<nome>`), selettore, porta 8080 ed
annotazioni Prometheus: lo scraping usa `kubernetes_sd_configs` con
`role: endpoints`, che funziona anche con un Service headless.
`publishNotReadyAddresses` resta falso.

### Scoperta dei pod

Un solo informer Fabric8 sugli `EndpointSlice` del namespace delle funzioni,
avviato con il modulo e chiuso allo spegnimento del contesto. Ogni slice porta
l'etichetta `kubernetes.io/service-name`; dal nome del Service si risale alla
funzione (stessa convenzione `fn-<nome>` di `KubernetesDeploymentBuilder`).

A ogni evento (aggiunta, modifica, cancellazione) di uno slice di una funzione
nota, il provider ricalcola la lista dei backend di quella funzione da **tutti**
i suoi slice (un Service può averne più di uno): un backend
`http://<indirizzo>:8080` per ogni endpoint con `conditions.ready == true`.
Gli endpoint che terminano hanno `ready == false` e quindi escono dalla lista.
La lista, ordinata in modo stabile, va al pool con `updateBackends`.

### Pool per funzione

Come in `LocalManagedDeploymentProvider`, ogni funzione ha un
`ManagedReplicaSet` creato da `ManagedReplicaPoolFactory`. Alla ricezione dei
backend il provider aggiorna anche i limiti:
`updateLimits(max(1, backend × perPod), perPod, timeout)`, con
`perPod = spec.concurrency` (default 4, come per i container) e
`timeout = spec.timeoutMs`. `updateSpec` riapplica i limiti quando cambia la
specifica della funzione.

Il limite che conta resta quello di oggi: `concurrency` limita le invocazioni in
volo della **funzione intera** (`AttemptCoordinator` con
`tryAcquireLease(…, spec.concurrency())`, e `FunctionCapacityState` vi limita
anche il governor). Con `perPod = spec.concurrency` il limite per pod del pool
non può essere raggiunto: il pool serve a scegliere il pod, come per i
container, non ad aggiungere un limite. (Scelta di design: stessa
configurazione dei provider locali.)

Il pool tratta già i backend rimossi come il caso serve: uno slot che esce dalla
lista non riceve più richieste e viene eliminato quando le sue invocazioni in
volo finiscono. Quindi lo scale-down e la terminazione dei pod non richiedono
codice di drenaggio in più.

### Dispatch

`KubernetesManagedDeploymentProvider` implementa `acquireInvocationTarget` come
il provider locale: cerca lo stato della funzione, chiama `pool.acquire` e
restituisce `reserved(target)` oppure `UNAVAILABLE` (funzione sconosciuta, nessun
pod pronto, tutti i pod al limite). L'URL di destinazione è
`http://<pod-ip>:8080/invoke`.

### Ciclo di vita

- `provision`: crea Deployment, Service headless ed eventuale HPA come oggi, poi
  lo stato della funzione con il suo pool. `ProvisionResult.endpointUrl` diventa
  `null`, come per container-local: non esiste più un URL unico della funzione.
- `reconcile` (riavvio del control plane): se il Service esistente non è
  headless (`clusterIP` diverso da `None`) lo cancella e lo ricrea, perché
  `clusterIP` non si può modificare. Poi crea lo stato della funzione; l'informer
  popola il pool alla prima sincronizzazione.
- `deprovision`: chiude il pool, toglie lo stato e cancella le risorse come oggi.
- `setReplicas`: invariato (scala il Deployment). Il pool segue comunque gli
  EndpointSlice, chiunque scali.
- `getReplicaStatus`: `desiredReplicas` resta quello del Deployment, ma
  `readyReplicas` diventa il numero di backend nel pool, cioè di endpoint pronti
  visti negli EndpointSlice. È lo stesso principio del provider locale, che
  riporta solo le repliche che il dispatch può usare. Serve perché
  `DeploymentWakeUpGate` trattiene il primo dispatch di una funzione nuova o
  risvegliata da zero finché `readyReplicas > 0`: se leggesse il Deployment, che
  può diventare pronto prima che lo slice arrivi al pool, quel dispatch
  troverebbe il pool vuoto e riceverebbe un 503. Lo leggono anche autoscaler e
  governor della concorrenza, che così contano i pod effettivamente raggiungibili.
  (Scelta di design.)

### Permessi, build native, dipendenze

- `deploy/helm/nanofaas/templates/rbac.yaml`: regola `discovery.k8s.io`,
  `endpointslices`, verbi `get`, `list`, `watch`. Lo stesso per gli eventuali
  manifest statici sotto `deploy/k8s/` che concedono permessi al control plane.
- `Fabric8RuntimeHints`: aggiungere `io.fabric8.kubernetes.api.model.discovery.v1`
  ai pacchetti registrati, più quanto serve all'informer nella build native.
- `k8s-deployment-provider/build.gradle`: dipendenza da
  `:container-deployment-runtime`.

## Cambiamenti di comportamento visibili

1. Il limite di invocazioni contemporanee non cambia: resta `concurrency` per
   funzione, applicato prima del dispatch come oggi.
2. L'API delle funzioni non mostra più un `endpointUrl` per le funzioni
   Kubernetes `DEPLOYMENT`.
3. Al primo riavvio dopo l'aggiornamento, i Service `ClusterIP` esistenti
   vengono ricreati headless; per un istante la funzione non ha pod in lista e
   il dispatch risponde come per "nessun pod pronto".

## Verifica

- Test unitari con il mock server Fabric8: slice aggiunti/modificati/cancellati
  aggiornano il pool; endpoint non pronti o in terminazione esclusi; più slice per
  Service uniti; `acquire` distribuisce a turno e rispetta `concurrency`;
  `reconcile` ricrea un Service non headless; `provision` crea un Service con
  `clusterIP: None`.
- Test dei limiti e del ciclo di vita esistenti del modulo k8s aggiornati al
  nuovo `endpointUrl` nullo.
- `getReplicaStatus` riporta pronti solo i backend del pool: con Deployment
  pronto ma slice non ancora arrivato, `readyReplicas` è 0.
- Build native del control plane con il modulo k8s e avvio reale con
  l'informer attivo.
- End-to-end con NanoLab su una VM Multipass nuova (k3s):
  `deployment-lifecycle-k8s`, `autoscaling-cycle-k8s`, `autoscaling-cycle-k8s-hpa`
  (scala l'HPA, non il control plane), `persistent-recovery-k8s` (riavvio: il
  `reconcile` ricrea il Service) e `retry-backoff-k8s`
  (quest'ultimo richiede che tutte le 300 chiamate di una raffica subito dopo la
  registrazione riescano, quindi controlla proprio il punto di `getReplicaStatus`). In più la misura
  che motiva il lavoro: con carico costante, scale-up da 1 a N pod e conteggio
  delle richieste per pod. Successo: dopo che un pod è pronto, entro pochi
  secondi riceve una quota vicina a 1/N del traffico (oggi è vicino a zero se le
  connessioni restano aperte), senza 503 sotto la capacità. La misura si fa una
  volta, con gli scenari esistenti e uno script di conteggio, senza aggiungere
  uno scenario permanente a NanoLab. (Scelta di design: NanoLab cambia solo se
  uno scenario esistente si rompe.)

## Lasciato a chi costruisce

- Periodo di resync dell'informer e come gestire la prima sincronizzazione.
- Come mappare slice e funzioni (indice dell'informer o mappa propria).
- Se e come esporre una metrica del numero di backend per funzione.
- Struttura delle classi, purché `acquireInvocationTarget` non prenda lock di
  ciclo di vita (lezione dal provider locale: il dispatch non deve aspettare
  operazioni lente).
