# Budget e limiti di one-shot — 13 ottobre 2026

La preparazione fisica del prossimo run funzionale ORACLE è stata portata da
1 a **10 secondi**, su richiesta dell'utente. L'asta resta a 4 secondi;
l'anticipo diventa **14 secondi** perché il contratto richiede
`auctionBudget + preparationBudget <= leadTime`. Protocollo e manifest
condividono questo valore. Il periodo resta 360 secondi, per nove intervalli.

La modifica è in `scripts/one-shot-observe.py` nel checkout NanoLab
`/tmp/nanolab-one-shot-host-placement`: l'override avviene durante il binding
dello scenario, prima del nuovo fingerprint. Non modifica la distribuzione Java
né i preset storici: la ricetta conserva il valore iniziale di 1 s, sostituito
per questa campagna dalla configurazione amministrativa a 10 s.
Non è stata avviata una campagna. Il freeze04 precedente conserva il suo SHA
`a143445a7e88c53f28db7fc82de75804f44bdcd9d50b58aa67b44aacdad54a66`:
tutti i 451 file sono stati verificati invariati.

Questo è un censimento di **one-shot, del workflow NanoLab e dei limiti della
piattaforma che possono incidere sulla campagna**. Non tutti i valori chiamati
budget sono soglie prestazionali: alcuni limitano memoria, messaggi o richieste
simultanee. Le righe di codice dell'intero ambito produttivo cercato sono
conservate in [code-limit-matches.jsonl](https://github.com/Nanofaas/nanofaas/blob/550d8f7a/docs/experiments/one-shot-budgets-2026-10-13/code-limit-matches.jsonl), con repository,
file e numero di riga; i match testuali non sono automaticamente budget applicati.
[inventory.json](inventory.json) conserva i valori effettivi derivati dallo
scenario del run e distingue il prossimo protocollo dal freeze precedente.

## Scadenze e limiti del protocollo

| Parametro | Prossima osservazione | Dove / conseguenza |
|---|---:|---|
| `preparationBudget` / `preparationSeconds` | **10 s** | `ReplicaPlanActuator`: limite locale monotono dell'intera transizione fisica; timeout produce `FAILED`. |
| `auctionBudget` / `auctionSeconds` | 4 s | `EpochCoordinator`: limite dell'intera negoziazione, non di ogni round; scadenza produce `DEADLINE`. |
| `solverBudget` / `solverSeconds` | 150 ms | Ogni solve dispone del minimo tra questo limite e il residuo dell'asta. |
| `peerTimeout` / `peerSeconds` | 150 ms | Ogni RPC della negoziazione, ulteriormente limitato dal tempo residuo dell'asta. |
| `maxRounds` | 8 | Numero prefissato di round; massimo accettato dal contratto 1.000. |
| `parallelism` | 4 | Parallelismo della negoziazione; massimo accettato 64. |
| `queueCapacity` | 64 | Coda/semaforo della negoziazione; massimo 256, almeno quattro volte `maxPeers`. Non è la coda delle invocazioni. |
| `maxPeers` | 16 | Massimo numero di vicini ammessi dal protocollo; massimo configurabile 64. La topologia reale ha gradi 2/2/4/2/2. |
| `maxSolverStates` | 2.000.000 | Limite del prodotto stati/livelli del solver; massimo accettato dalla configurazione 10.000.000. |
| `maxSolverBytes` | 64 MiB | Memoria di lavoro del solver; massimo accettato 256 MiB. Non è la RAM assegnata alle funzioni. |
| `maxOperationalFraction` | 0,20 | `OneShotOperations.validateBounds`: asta più preparazione devono stare entro il 20% del periodo, qui 72 s. Il limite massimo 0,20 è nel contratto Java. |
| `maxAuctionFraction` | 0,20 | `EpochCoordinator`: il budget dell'asta deve stare entro la frazione del periodo. |
| `leadTime` / `leadSeconds` | **14 s** | Anticipo dell'asta rispetto all'attivazione; validazione di coerenza con la somma dei budget. |
| `period` | 360 s | Durata/validità dell'allocazione; massimo accettato 24 ore. L'expiry v2 è monotona locale. |
| Invio READY/READY_ACK | massimo 500 ms per invio | `ReplicaPlanActuator.send`: limite hardcoded, anche dopo aver portato la preparazione a 10 s. |
| RPC READY/READY_ACK | massimo 200 ms per tentativo | Limitato anche dal residuo del relativo invio. |
| Riserva finale READY | `min(100 ms, preparationBudget/10)` | Qui 100 ms; riservata prima della scadenza della preparazione. |
| Poll readiness / pausa tra tentativi READY | 20 ms | Cadenza hardcoded; non è un criterio di esclusione del run. |
| `drain-grace` | 30 s, default Spring | Grace della proprietà/allocazione precedente; distinto dal timeout di preparazione. |
| Proposte automatiche v2 | 2 retry, pausa 20 ms | `LogicalEpochInitiator`; solo il percorso automatico, non l'attuale driver manuale degli intervalli. Nessun rilancio automatico della campagna. |
| Ritardo iniziale automatico | almeno asta + preparazione, massimo 24 h | `LogicalEpochInitiator.validateDelay`. |
| Qualificazione per avvio automatico | almeno 20 campioni; `2 × p99` sotto anticipo e frazione del periodo | `OneShotOperations.qualified`; mantiene fino a 128 campioni. Non usato dal driver manuale dell'osservazione attuale. |

Fonti: `platform/modules/offload/src/main/java/.../oneshot/coordination/`
(`EpochSettings`, `EpochTiming`, `EpochCoordinator`), `api/OneShotSettings.java`,
`api/OneShotOperations.java`, `api/LogicalEpochInitiator.java`,
`actuation/ReplicaPlanActuator.java` e NanoLab `tasks/one_shot/qualification.py`.

Valori diversi ancora presenti in altri percorsi: `ProtocolSettings` generico
usa asta **0,6 s**, preparazione **0,3 s**, peer/solver **0,15 s**; i preset di
qualificazione dichiarano preparazione **1 s**. Le ricette local JVM/native
usano asta/preparazione **5 s**, peer **0,5 s**, solver **1 s**. Il costruttore
Java compatibile senza negotiation esplicita usa asta **5 s**, peer/solver
**1 s**, **100** round e frazione **0,1**. Il motore d'asta standalone usa
solver **2 s**. La campagna corrente passa valori espliciti e non usa questi
fallback. I tre timeout in `EpochSettings` e il budget dell'attuatore ammettono
al massimo **1 ora**; il periodo è comunque al massimo **24 ore**, e la
preparazione deve essere inferiore all'anticipo. La RAM del pool deve entrare
in un intero positivo Java, al massimo `Integer.MAX_VALUE` MiB.

## Trasporto, quote di richieste e disponibilità

| Limite | Valore | Effetto / origine |
|---|---:|---|
| Richieste P2P simultanee | 8 nella ricetta NanoLab, default piattaforma 4 | Semaforo globale del trasporto; esaurimento rifiuta il tentativo P2P. |
| Ping vicini | timeout 1 s, cadenza 250 ms | Override di `one_shot_common.py`. |
| Annuncio P2P | timeout 2 s, validità 15 s | `DefaultPeerTransport`, hardcoded. |
| Payload P2P / batch asta | 1 MiB | Limite del trasporto e del codec. |
| Messaggi per batch | 1.000 | `AuctionCodec.Batch`. |
| Messaggi/bid del motore | 10.000 | `OneShotAuctionEngine.MAX_MESSAGES`; deduplica fino a 40.001 identità. |
| Messaggio di avvio/status v2 | 8 KiB | `LogicalEpochInitiator`. |
| Quota di assegnazione | rate del piano; burst **1** | `AssignmentAdmission`: token bucket per flusso locale e assegnazioni in ingresso/uscita. Burst massimo ammesso 1.000. Può incidere sui reject/offload. |
| Handler per replica | **1** | Profilo SDK / `STATIC_PER_POD`; requisito del modello sperimentale. |
| Coda configurata per funzione | 100 richieste | `one_shot_common.py`, `queue_size=100`. |
| Coda sync condivisa | profondità 200, attesa stimata 2 s, attesa in coda 2 s | Default `application.yml`; il runner abilita la sync-queue terminale sul cloud. La coda sync sugli edge è disabilitata dal runner, distinta dalla coda locale one-shot. |
| Stima throughput della sync-queue | finestra 30 s, almeno 50 campioni | Default `application.yml`; `Retry-After` 2 s. |
| Timeout invocazione | 30 s | Funzione registrata e k6. Gli errori delle singole richieste sono risultati, non guasti della campagna. |
| Retry delle invocazioni | **0** nella campagna | Default generale NanoFaaS 3; backoff generale 100 ms–2 s. |
| Readiness backend container | 20 s, polling 250 ms | Default `ContainerLocalProperties`. |
| Docker Java | connessione 5 s, risposta 30 s | `ContainerDeploymentProviderConfiguration`. |
| Comandi Docker CLI | 120 s | Default `ProcessCliCommandExecutor`; adapter alternativo rispetto al Docker Java della campagna. |
| Cache stato repliche | TTL 5 s, massimo stale 15 s | `ReplicaStatusSnapshot`. La preparazione one-shot richiede uno stato FRESH. |
| Refresh stato repliche | deadline 5 s; pool periodico 2 worker/64 attese; pool freshness 4 worker/32 attese | `ReplicaStatusSnapshot.RefreshLimits.defaults`. |
| Wakeup deployment | 30 s, polling 250 ms; osservazione pronta fino a 5 s | Default `application.yml`. |
| Previsione ORACLE | validità **4.110 s** | Derivata da warmup 720 + setup 90 + misura 3.240 + allowance generatore 60. La ricetta di base dichiara 5 min, sostituiti dal runner all'avvio. |
| Archivio ORACLE / EWMA | 100.000 entry ORACLE; default 1.000 funzioni EWMA | `OracleForecastStore`, `ForecastingProperties`. |
| Clock-health legacy | offset 100 ms, anzianità 5 min | Ricetta. **Non autorizza né blocca il protocollo v2**; i probe restano evidenza diagnostica. |

## Supervisione, servizi e cleanup

| Operazione | Limite / cadenza | Effetto |
|---|---:|---|
| Attesa cloud-init | 600 s | Timeout del sottoprocesso; guasto operativo se non conclude correttamente. |
| Avvio control-plane / Loki | 90 s ciascuno; probe HTTP 2 s | Attesa readiness. Comandi Compose del backend logging hanno anch'essi timeout 90 s. |
| Avvio Fluent Bit / sampler VM | 30 s ciascuno | Verifica disponibilità iniziale. Probe collector 2–3 s. |
| Bootstrap SDK del runner | 120 s per replica; HTTP e prova di rilascio 30 s | `WarmInstances`; i controlli degli altri workflow usano spesso readiness 30 s / HTTP 2 s. |
| API one-shot NanoLab | 30 s | Timeout client; nessuna ripetizione di trigger ambiguo. |
| Barriera prepare parallelo | 10 s | Timeout barriera thread Python, prima dell'asta; distinto dalla nuova preparazione fisica 10 s. |
| Raccolta metriche/proof | HTTP 2 s; metrics di qualificazione 5 s; inventario vicini 3 s | Errori del collector sono guasti della procedura. |
| Sampler VM | campione ogni 1 s; chiamata Docker HTTP 2 s | CPU/RAM/swap VM e cgroup container, throttling incluso. |
| Attesa di finalizzazione locale del sampler | 5 s | `vm_sampler.py`. |
| Silenzio sampler legacy | 10 s | Controllo esistente in `VmTelemetrySession.require_healthy`; **disabilitato se presente la supervisione autonoma**. Campioni mancanti restano segnalati. |
| Clock probe | HTTP 1 s; pubblicazione clock-health 2 s; cadenza 10 s | Protocollo v2 conserva i probe senza gate sullo skew. |
| Supervisore | health/heartbeat ogni 5 s | Cadenza, **nessuna deadline prestazionale** sul progresso; nessun timeout globale della campagna. |
| Attesa setup generatore | 90 s | Valore usato nel calcolo dell'anchor/horizon, non un nuovo gate di throughput. |
| Warmup | 720 s | Durata della campagna; precheck osservabilità 60 s. |
| Misure | 9 × 360 s = 3.240 s | Durata totale della fase misurata, contemporanea per tutti i nodi. |
| k6 `maxDuration` | intero orizzonte più allowance di 60 s | `GENERATOR_DRAIN_SECONDS`; non un timeout del singolo round. |
| Ritardo arrivo richieste | 3.600 s nell'osservazione; default qualificato 0,25 s | Verifica deficit/lateness del generatore; niente modifica in questo task. |
| Arresto generatore | wait 5 s dopo TERM, poi KILL e wait 5 s | Cleanup delle sole risorse possedute. |
| Arresto sampler/processi e reader | drain 10 s; escalation/wait/join 5 s | `vm_resources.py`; conserva raw parziale. |
| Arresto collector / clock monitor | join 40 s | Fallisce cleanup se il thread non conclude. |
| Archivio finale | nessuna soglia prestazionale | Scrittori chiusi prima della copia, confronto SHA, poi esito terminale. |
| Export Loki | HTTP 10 s, massimo 5.000 righe/query | Export diagnostico limitato; truncation dichiarata. Non sostituisce i raw della campagna. |
| Eventi nativi | retention 10.000; pagine di 100, massimo 100 pagine/client | API ammette fino a 1.000 eventi/pagina; superamento del page budget client segnala evidenza troncata. |

## Budget fisici

| Nodo | vCPU VM | RAM VM MiB | Pool funzioni MiB | CPU per replica |
|---|---:|---:|---:|---:|
| edge-1 | 1 | 2.048 | 832 | 0,10 |
| edge-2 | 1 | 3.072 | 832 | 0,10 |
| edge-3 | 2 | 3.072 | 1.664 | 0,14 |
| edge-4 | 2 | 4.096 | 1.664 | 0,14 |
| edge-5 | 3 | 6.144 | 2.496 | 0,16 |
| cloud | 8 | 12.288 | 5.824 | 0,21 |

Disco: 20 GiB per VM. Questi valori non sono stati modificati in questo task.
Quote CPU/RAM per replica sono imposte dai cgroup; il solver alloca sul pool RAM.

| Funzione | RAM/replica MiB | Cap repliche E1/E2 | E3/E4 | E5 | Cloud |
|---|---:|---:|---:|---:|---:|
| arithmetic | 64 | 13 | 26 | 39 | 91 |
| sort | 128 | 6 | 13 | 19 | 45 |
| memory | 256 | 3 | 6 | 9 | 22 |
| matrix | 128 | 6 | 13 | 19 | 45 |
| mixed | 256 | 3 | 6 | 9 | 22 |

Il cap di ciascuna funzione è distinto dal numero di repliche presenti e dalla
fattibilità simultanea del vettore nel pool. Il campo `concurrency` registrato
è il suo cap; la capacità pronta effettiva viene aggiornata dall'attuatore.

- Collector Fluent Bit per VM: **0,10 CPU / 64 MiB RAM / 256 MiB buffer disco**.
- Backend logging host posseduto: **1,5 CPU / 768 MiB RAM** nel dimensionamento.
- Riserva host del percorso scientifico: **1 CPU / 1.024 MiB**, più VM esterne e logging.
- Riserva control-plane del percorso scientifico: **0,35 CPU / 512 MiB**; l'osservazione `workflow-validation` non impone quei due cap. La ricetta JVM dichiara heap massimo 256 MiB.
- Validazioni strict: una replica per funzione deve entrare nel pool, il pool lascia RAM al runtime; nel percorso scientifico riserva piattaforma/OS 512 + 128 MiB, con ulteriori verifiche delle risorse del collector.
- Log Docker control-plane: 10 MiB × 3 file; collector: 10 MiB × 2 file.

## Altri limiti generali NanoFaaS che possono produrre reject o attese

Valori di `platform/control-plane/src/main/resources/application.yml`; sono
default di piattaforma, non nuovi gate dell'esperimento.

| Ambito | Valore |
|---|---|
| Ingress body | 1 MiB |
| Execution admission | globale 4.096, per funzione 512 |
| Input canonici trattenuti | globale 128 MiB, per funzione 32 MiB |
| Copie fisiche input | globale 64 MiB, per funzione 16 MiB |
| Waiter | globale 8.192, per funzione 1.024 |
| Input references / depth / container entries / visited nodes | 64 / 32 / 16.384 / 65.536 |
| Input trattenuto per execution | 1 MiB |
| Proxy container | request 2 MiB, response 4 MiB, buffer aggregato 32 MiB |
| Proxy read / write | 5 s / 5 s |
| HTTP pool per destinazione | 500 connessioni, 1.000 acquisizioni pendenti derivate |
| Acquisizione HTTP pool | 45 s, ulteriormente limitata dal timeout dell'invocazione |
| Retention pool | idle 30 s, inattività 30 s, eviction/dispose ogni 5 s; max lifetime disabilitata |
| Execution store | TTL 5 min, sync TTL 30 s, lifetime massimo 30 min |
| Idempotency keys | 100.000 |
| Outcome byte budget | derivato da `max-outcomes=100.000` × 116 byte quando `max-outcome-bytes=0`; il count non è un cap indipendente |
| Rate generale | `maxPerSecond=1.000.000` |
| Profili one-shot | 8 MiB/body, 32 MiB retention, 128 profili; massimo 128 funzioni/profilo e configurazione |
| Configurazione amministrativa | 8 MiB/body; stringhe/identità massimo 256 caratteri |
| Parser JSON | depth 12, numeri 64 caratteri; stringhe profili/API 256, codec asta 1.024 |
| Valori di flusso | massimo `2^53−1`, griglia ORACLE esatta; nella campagna quantum 0,05 |
| Revisioni di piano trattenute | 4 |
| Scheduler switch | budget preparazione hardcoded 50 ms; non usato dalla campagna che non cambia scheduler |

## Soglie di qualificazione ancora presenti, separate dall'osservazione

- Config di calibrazione ereditata: minimo **50**, massimo **200** campioni,
  **3** ripetizioni, **3** warmup invocation, CI relativo **15%**, confidence
  **95%**, errore capacità **15%**, trial **60 s**, HTTP/release **30 s**.
- Profilo adattato dell'osservazione: `maxRelativeCapacityError=0.35` e scope
  sintetico/non qualificato dichiarato; non è una misura di accuratezza riuscita.
- Timing preset: minimo **50** campioni, quantile **0,95**, margine asta
  **0,2 s**, margine readiness **0,3 s**, epsilon **0,05**, censura ammessa **0**;
  il runner funzionale usa frazioni **0,20**, margine **0** e disattiva il gate
  numerico `enforce_timing_budget`. I default generici differiscono e sono
  enumerati in `config/one_shot.py` e nei match sorgente.
- Default NanoLab di calibrazione: **24–96** campioni, **2** ripetizioni,
  **3** warmup invocation, CI **0,15**, confidence **0,95**, errore capacità
  **0,35**, trial **4 s**, timeout **10 s**. Default timing: **10** campioni,
  quantile **0,9**, margini **0,2/0,3 s**, epsilon **0,1**, anticipo **2 s**,
  censura **0**. Default generatore: **64** VU, warmup **5 s**, lateness
  **0,25 s**. L'osservazione usa **512** VU; il suo warmup e le quote sono
  espliciti. I limiti di schema includono **10.000** campioni, **100**
  ripetizioni di calibrazione, **20** di esperimento, **720 s** di warmup,
  **512** VU e **1.000** epoche per finestra.
- Qualificazione overhead VM: **3** coppie complete, perdita goodput e aumento
  p95 ciascuno **≤5%**. L'osservazione corrente misura il costo nel precheck,
  senza richiedere quell'artefatto di qualificazione.
- Protocollo di confronto congelato: vincolo lateness **0,25 s**; non è il
  limite **3.600 s** del runner funzionale.
- Copertura della traccia: tutti i **9** intervalli con offload peer e almeno
  **2** con più vicini. Questo requisito è stato richiesto dall'utente.
- Il runner conserva un controllo **PREPARED e desired==ready** per dichiarare
  il campione completo. Un esito `DEGRADED`, anche con piano valido e cloud
  fallback, viene censurato in `prepare_parallel_epoch`; `RunOneShotLoadTask`
  arresta il run se il campione è censurato o manca una durata, **anche con
  `enforce_timing_budget=False`**. È un blocco distinto dal timeout di 1 s
  ora portato a 10 s. Non è stato corretto in questo task.

## Verifica della modifica

52 test passati senza VM, comprendenti preparazione reale degli input salvati,
contratto Java effettivo con preparazione 10 s e anticipo 14 s, compilazione ed
esecuzione Sonata con risorse simulate, supervisione/interruzione/cleanup.
Controllo Ruff `F` sui due file Python modificati. Il lint esteso applicato con
configurazioni differenti rileva regole preesistenti su import/docstring/file
non eseguibile; non sono state incluse correzioni di stile estranee al task.

Script riproducibili ed esiti:
`/tmp/oneshot-first-experiment-20261012/preparation-budget-10/verify.py`,
`inventory.py`, `tests.xml` e `check-*.json`.


Disposizioni dell’implementazione: [tabella completa](dispositions.md), con valori storici in [JSON](dispositions.json). Le CPU/RAM e i parametri dell’esperimento restano fissi; le passate diagnostiche best effort riusano il drain 30 s, dichiarando dati incompleti senza escludere il run.
