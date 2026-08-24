# Registro degli esperimenti sul dispatch

Questo registro conserva protocollo, risultati e dati grezzi dell'indagine del
2026-08-21. Il contesto e le ipotesi sono in
[`../../plans/2026-08-21-dispatch-bottleneck-and-comparison-rerun.md`](../../plans/2026-08-21-dispatch-bottleneck-and-comparison-rerun.md).

## Protocollo preregistrato: validazione delle probe corrette

Preregistrato il `2026-08-24T10:10:34Z`, prima della run, su mcFaas
`ddf835957dd58ca89199b3013802498f01ca10ef` e NanoLab
`d59c0bd8a6c5a8962898661c214154dc1aec3748`.

- Una sola cella `native-o3-g1`, ripetizione 1, `concurrency=2`,
  `queueSize=20`, profilo metriche `advanced`; la politica di scheduling resta
  invariata.
- Tutte le 12 fasi del profilo fisso (`warm40`, `climb200`, `hold200`,
  `ramp600`, `spike600`, `rampDown200`, `recover200`, `climb350`, `hold350`,
  `ramp900`, `peak900`, `drain40`) devono contenere traffico osservato, cioè
  `Δfunction_dispatch_total > 0`. Una fase senza dispatch invalida la run di
  strumentazione e blocca il Task 10. In ciascuna fase l'accounting wall-clock
  deve essere compreso fra 98,0% e 100,5%, estremi inclusi.
- Sull'intera run, usando il primo e l'ultimo campione dello snapshot, le release
  attese sono
  `Δfunction_scheduler_dispatch_submit_duration_count + inFlight_iniziale - inFlight_finale`.
  `Δfunction_dispatch_slot_hold_events_total` deve essere uguale a questo valore;
  `function_dispatch_slot_hold_seconds_total` deve restare monotono e non
  negativo. La query live dedicata deve inoltre riportare zero serie slot-hold
  max, bucket o quantile.
- `process_uptime_seconds` deve essere monotono non decrescente.
- In `k6-summary.json`, `metrics.checks.fails` deve essere zero; nello snapshot,
  `Δfunction_timeout_total` deve essere zero. NanoLab non produce un campo o un
  file di stato per i timeout infrastrutturali: per questo protocollo sono un
  exit non-zero di `nanolab compare` dovuto a timeout oppure l'assenza di uno dei
  due marker che NanoLab usa per una cella completa, `k6-summary.json` e
  `metrics/prometheus-snapshot.json`. Entrambi devono essere assenti come eventi,
  cioè il comando deve terminare con zero e i due file devono esistere.

L'exit code 0 di `caffeinate -dimsu ./nanolab.sh compare ...`, osservato solo
dopo il teardown automatico, è un gate esterno antecedente al validator. Se è
non-zero il validator non parte e il Task 10 resta bloccato; la presenza dei
marker o di `summary.json` non sostituisce questo gate.

Il fallimento di un solo criterio blocca la matrice del Task 10 e riporta il
lavoro al relativo test locale. Questa singola ripetizione valida gli strumenti,
non autorizza conclusioni prestazionali.

### Esito della validazione delle probe

La cella `azure-dispatch-probe-validation-c2` è terminata con exit code 0 il
2026-08-24. Poiché `compare` usava `keep=True`, il teardown di loadgen e stack è
stato eseguito esplicitamente dopo il compare ed entrambi sono terminati con
exit code 0. Comandi, esiti osservati e inventario finale sono conservati in
[`validation-gate.txt`](raw/azure-dispatch-probe-validation-c2/validation-gate.txt).
Il run usa mcFaas
`d5cae55862da4fc4a6a426d1d8bccbac422c50c4` e NanoLab
`39e0b95d3984fe8604c0707df920160793b75d06`. Il validator ha verificato:

- accounting delle 12 fasi compreso fra 99,816% e 100,383%;
- 97 campioni monotoni di `process_uptime_seconds`;
- 122.395 release slot-hold misurate e 122.395 attese, con 155,932 secondi
  totali non negativi e zero serie distributive;
- zero check k6 falliti e zero timeout funzione.

L'inventario Azure dopo il teardown era vuoto. Questa singola replica convalida
la raccolta e i gate automatici delle probe; non costituisce una misura
prestazionale né un confronto fra build.

### Emendamento post-run del criterio accounting — 2026-08-24

Il criterio preregistrato per fase, conservato sopra come record storico, è
risultato non identificabile dalle serie Timer completion-based: il delta a un
confine può attribuire alla fase un intervallo idle iniziato nella fase
precedente. Il validator applica quindi il gate 98,0%–100,5% all'accounting
aggregato sull'intera finestra (`Σ(Δvisit + Δidle) / Σdurata`), dove i confini
interni si cancellano. Continua a richiedere traffico e contatori monotoni in
ogni fase e riporta le dodici percentuali per-fase come diagnostica, senza usarle
come gate indipendenti.

Con questo emendamento sono stati rivalidati i dodici raw della matrice del
2026-08-24 e il raw di validazione delle probe: tutti i gate risultano PASS.
L'accounting whole-run è compreso fra 99,979% e 100,094% nella matrice ed è
100,016% nella cella di validazione delle probe.

## Protocollo comune

- Azure `westeurope`: stack `Standard_D8s_v5` (8 vCPU, 32 GiB), load generator
  `Standard_D2s_v5`; k3s `v1.36.3+k3s1`.
- Profilo k6 open-loop `runtime-comparison.js`, 450 s, due funzioni concorrenti:
  Java al 100% del profilo e JavaScript al 35%.
- Build di riferimento: `native-o3-g1`, Oracle GraalVM, `-O3`, G1.
- Moduli control plane: `k8s-deployment-provider,async-queue`; un pod funzione;
  nessun autoscaler o governor.
- Salvo diversa indicazione, `concurrency=2`, `queueSize=20`.
- Le sonde a una ripetizione falsificano ipotesi meccanicistiche ma non sono
  confronti statistici fra build.

## Esperimenti conservati

| directory raw | configurazione | codice rilevante | risultato |
|---|---|---|---|
| `azure-nosync` | 4 build × 3 ripetizioni, 2/20 | mcFaas `9387c60d`; NanoLab `6012bf3` | G1 è la build migliore: 435,1 rps, p95 92,2 ms, p99 147,7 ms, 17,52% scarti, 553,6 MiB RSS. A parità di `-O3`, il serial GC porta il p95 a 695,6 ms. |
| `azure-conc8-probe` | G1, una ripetizione, 8 slot | NanoLab `ea01127` | 119.892 dispatch contro 115.630 medi a 2 slot (+3,69%, non 4×); p95 121,6 ms e scarti 14,64%. L'ipotesi che un park ogni due dispatch imponesse il tetto è falsa. |
| `azure-dispatch-instrumentation-c2` | G1, una ripetizione, 2/20 | mcFaas `8af8c314`; NanoLab `553b7a5` | `offer` 365 ns, `poll` 238 ns, wake-up 222 µs medi; p95 93,7 ms, 114.797 dispatch, 18,02% scarti. Lock e park/unpark non spiegano da soli attese di decine di ms. |
| `azure-dispatch-probe-validation-c2` | G1, una ripetizione, 2/20 | mcFaas `d5cae558`; NanoLab `39e0b95d` | Tutti i gate strumentali PASS; run usata soltanto per validare raccolta e controlli automatici, non per conclusioni prestazionali. |
| `azure-dispatch-slot-hold-c2` | G1, una ripetizione, 2/20 | mcFaas `df5efda1`; NanoLab `07bbbf7` | slot 1,416 ms e `function_latency` 1,491 ms sull'intera run; p95 92,5 ms, 115.859 dispatch, 17,43% scarti. Nessun callback lag millisecond-level nascosto. |
| `azure-dispatch-reacquisition-c2` | G1, una ripetizione, 2/20 | mcFaas `50e7d87a`; NanoLab `2b7f01c` | Risultato numerico archiviato, ma sonda invalidata dal capacity-idle bound e da una race nel pairing dei timestamp. |
| `azure-dispatch-reacquisition-segments-c2` | G1, una ripetizione, 2/20 | mcFaas `3365c590`; NanoLab `8e9674e` | 4,743/4,788 ms pre-active osservati, ma attribuzione invalidata: il timer supera il massimo fisico di 2,081 ms al `peak900`. |
| `azure-dispatch-scheduler-direct-probes-c2` | G1, una ripetizione, 2/20 | mcFaas `7ed1e010`; NanoLab `ce45fae` | Al `peak900`, submit sincrono 7,0% del thread, 2,14 visite senza slot e 0,44 segnali coalesced per dispatch Java. Il submit non è il collo; il churn di visite non dispatchable è il candidato causale. |
| `azure-dispatch-saturation-guard-c2` | G1, una ripetizione, 2/20, guardie `canDispatch()` | mcFaas `7e532fc9`; NanoLab `ce45fae` | Al `peak900`, 342,6 dispatch Java/s, 32,105 ms di queue wait e p95 90,72 ms: nessun miglioramento misurabile. Il churn non è causa dominante del limite. |
| `azure-dispatch-wakeup-localization-c2` | G1, una ripetizione, 2/20, timer dell'`activeFunctions.add` | mcFaas `0d924d08`; NanoLab `c77cfba` | Al `peak900`, enqueue 61,5 µs contro 995,2 µs segnale→scheduler: l'`add` non è il collo; 933,7 µs restano nel percorso esterno all'`add` fino all'attivazione scheduler. |
| `azure-dispatch-wakeup-split-c2` | G1, una ripetizione, 2/20, split `poll`/bookkeeping | mcFaas `5247e2ff`; NanoLab `635908e` | Al `peak900`, `poll` 1.078,2 µs, bookkeeping 5,6 µs, enqueue 39,7 µs: il costo è nel risveglio/scheduling fino al ritorno di `poll()`. |

La compilazione G1 dell'ultima sonda ha richiesto 872,8 s; il push 5,0 s. IP
dell'operatore verificato prima del run: `79.53.75.238`; l'ambiente usava
`operator_source_cidr: auto`.

## Sonda slot-hold per fase

La misura parte dopo l'acquisizione CAS dello slot e termina nel rilascio comune
di `QueueManager`. Pubblica soltanto gli aggregati monotoni Prometheus
`function_dispatch_slot_hold_seconds_total` e
`function_dispatch_slot_hold_events_total`: per ogni fase l'analisi calcola i
delta e usa `seconds / events` come media. Il FIFO interno non attribuisce una
durata a una specifica invocazione quando due completamenti si invertono, ma
preserva esattamente somma, conteggio e quindi media; non viene più esposta una
distribuzione che suggerisca percentili per-request. `analyze_snapshot.py`
mantiene il fallback alle serie Timer storiche
`function_dispatch_slot_hold_duration_seconds_{sum,count}` per rendere
riproducibili i raw archiviati.

| fase | dispatch/s | slot ms | latency ms | slot util. | idle/ciclo ms | queue wait ms | coda media | wake µs |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| hold200 | 199,3 | 0,596 | 0,702 | 5,9% | 9,440 | 0,047 | 0,00 | 27,7 |
| spike600 | 522,4 | 1,079 | 1,148 | 28,2% | 2,750 | 3,134 | 1,17 | 189,1 |
| recover200 | 210,6 | 0,602 | 0,695 | 6,3% | 8,897 | 0,077 | 0,00 | 35,1 |
| hold350 | 345,1 | 0,915 | 0,986 | 15,8% | 4,880 | 1,584 | 0,11 | 105,8 |
| **peak900** | **347,6** | **4,047** | **4,083** | **70,3%** | **1,707** | **32,125** | **17,17** | **484,6** |
| drain40 | 331,5 | 3,023 | 3,073 | 50,1% | 3,010 | 17,890 | 7,78 | 404,5 |

Al picco la durata slot coincide con la latenza dispatch→completamento entro
35,7 µs medi (la seconda è quantizzata al millisecondo e termina appena dopo il
rilascio). L'ipotesi «la callback trattiene lo slot oltre il tempo già misurato»
è quindi falsificata. Con due slot, 4,047 ms consentirebbero circa 494 dispatch/s;
ne arrivano 347,6. Mentre la coda resta quasi piena, ogni slot passa in media
1,707 ms fuori dal timer. La prossima misura utile è direttamente
rilascio→successiva acquisizione; non serve altra strumentazione del callback.

## Sonda rilascio→reacquisizione per fase

Il timer `function_dispatch_slot_reacquisition_delay` registra il tempo fra un
rilascio che vede ancora backlog e la successiva acquisizione riuscita. Un FIFO
per funzione conserva somma e media aggregate; non attribuisce la misura a uno
slot o a una richiesta specifici e non va usato per percentili per-request.

| fase | dispatch/s | slot ms | idle stimato ms | reacquisizione ms | copertura | queue wait ms | coda media | wake µs |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| hold200 | 199,7 | 0,711 | 9,305 | 0,081 | 1% | 0,055 | 0,00 | 30,9 |
| spike600 | 519,4 | 1,212 | 2,639 | 1,279 | 29% | 5,427 | 7,00 | 229,4 |
| recover200 | 204,7 | 0,674 | 9,098 | 0,199 | 2% | 0,069 | 0,00 | 35,6 |
| hold350 | 349,3 | 1,032 | 4,693 | 0,534 | 20% | 2,008 | 1,33 | 153,1 |
| **peak900** | **316,7** | **4,204** | **2,111** | **3,161** | **97%** | **41,621** | **17,33** | **615,5** |
| drain40 | 311,8 | 3,121 | 3,294 | 2,425 | 71% | 20,830 | 12,22 | 506,2 |

Il timer diretto conferma l'ipotesi: quando il backlog è stabile, il percorso
fra rilascio e nuovo CAS costa millisecondi. Al `peak900` copre il 97% dei
dispatch ed è 5,1 volte il timer segnale→scheduler medio. I due timer osservano
popolazioni diverse (`wake/dispatch=2,37` al picco), quindi non si possono
sottrarre; il dato localizza il problema nel percorso di scheduling successivo
al rilascio, ma non ancora in una singola istruzione. `idle stimato` deriva da
`2/rate - slot` su finestre non perfettamente stazionarie e non deve coincidere
numericamente col timer diretto.

Sull'intera run: 114.527 dispatch Java, 31.332 intervalli misurati, media
reacquisizione 2,038 ms; 435,06 richieste/s complessive, p95 93,21 ms, p99
158,03 ms, 18,36% scarti e 30.522 rifiuti della coda Java.

## Segmentazione della reacquisizione

Il timer `function_dispatch_slot_reacquisition_active_delay` usa lo stesso
timestamp di rilascio e lo stesso evento di acquisizione del timer totale. Il
punto di separazione è l'inizio della visita `processFunction`; se il rilascio
avviene a visita già iniziata, viene usato il rilascio. I conteggi dei due timer
sono quindi identici e `pre-active = totale - active` preserva la somma esatta.

| fase | dispatch/s | reacquisizione ms | pre-active ms | active→CAS ms | copertura | queue wait ms | coda media |
|---|---:|---:|---:|---:|---:|---:|---:|
| hold200 | 199,8 | 0,046 | 0,027 | 0,019 | 1% | 0,045 | 0,08 |
| spike600 | 527,2 | 1,670 | 1,656 | 0,013 | 30% | 6,866 | 6,17 |
| recover200 | 202,2 | 0,054 | 0,037 | 0,018 | 2% | 0,074 | 0,00 |
| hold350 | 349,8 | 0,410 | 0,392 | 0,018 | 18% | 1,126 | 0,00 |
| **peak900** | **316,7** | **4,788** | **4,743** | **0,045** | **100%** | **47,557** | **15,00** |
| drain40 | 312,1 | 3,355 | 3,321 | 0,034 | 73% | 23,679 | 7,00 |

Verdetto originario, ora invalidato: il CAS e il lavoro della visita utile non
sembravano essere il collo di bottiglia.
Al picco, il 99,1% del ritardo misurato trascorre prima che il singolo thread
scheduler torni sulla funzione; una volta entrato in `processFunction`, lo slot
viene acquisito in 45 µs medi. Questo localizza il problema nella coda/arbitraggio
delle visite fra funzioni, non nella callback, nei monitor di coda o nel CAS.

**Correzione del 2026-08-22:** con concurrency 2, 9.501 dispatch in 30 secondi
e slot hold 4,234 ms, l'inattività massima è
`2 * 30 / 9501 - 4,234 = 2,081 ms` per dispatch, inferiore ai 4,788 ms
misurati. Il codice rende visibile lo slot prima di accodare il timestamp di
rilascio; un'acquisizione concorrente può quindi precedere la pubblicazione e
lasciare un timestamp stale, poi associato a un'acquisizione successiva. Raw e
valori restano archiviati, ma non costituiscono evidenza sulla localizzazione
del collo di bottiglia.

## Sonde dirette dello scheduler

Run `azure-dispatch-scheduler-direct-probes-c2`, concurrency 2 e coda 20. Le
sonde non correlano eventi fra thread: misurano direttamente la durata della
chiamata sincrona a `InvocationService.dispatch`, i fallimenti di acquisizione
slot e i segnali coalesced.

| fase | dispatch Java/s | submit Java µs | submit tutte le funzioni | blocked/dispatch Java | coalesced/dispatch Java | CPU control plane core | queue wait ms |
|---|---:|---:|---:|---:|---:|---:|---:|
| spike600 | 519,8 | 88,5 | 6,4% | 0,63 | 0,15 | 0,60 | 2,953 |
| hold350 | 349,3 | 77,0 | 3,6% | 0,38 | 0,09 | 0,69 | 1,452 |
| **peak900** | **346,8** | **117,3** | **7,0%** | **2,14** | **0,44** | **0,91** | **31,040** |
| drain40 | 327,5 | 91,3 | 5,2% | 1,55 | 0,33 | 0,80 | 15,185 |

Al `peak900`, sulle due funzioni, 16.409 dispatch consumano 2,108 s di submit
sincrono in 30 s; nello stesso intervallo si osservano 37.590 visite senza slot
e 6.307 segnali coalesced. Il submit occupa quindi solo il 7,0% del thread e
non può spiegare il limite. Il segnale dominante è invece il churn: 2,29 visite
senza slot per dispatch complessivo. Questo identifica il prossimo intervento
falsificabile — evitare di accodare funzioni non dispatchable — ma non ne prova
ancora l'effetto causale; serve un'A/B con la stessa matrice.

Risultato complessivo: 115.986 dispatch Java, 435,06 richieste/s, p95 90,22 ms,
p99 151,21 ms, 17,33% scarti e 29.063 rifiuti della coda Java. Build nativa
878,9 s, push 5,0 s e k6 451,5 s. Le 12 risorse Azure sono state distrutte e
`caffeinate` è terminato.

Sull'intera run i due timer contano entrambi 32.025 eventi: reacquisizione
3,024 ms, pre-active 2,994 ms e active→CAS 0,030 ms. Risultato complessivo:
114.933 dispatch Java, 435,06 richieste/s, p95 93,63 ms, p99 142,23 ms,
18,00% scarti e 30.116 rifiuti della coda Java.

## A/B guardie di saturazione

Run `azure-dispatch-saturation-guard-c2`, una ripetizione con lo stesso profilo
e la stessa configurazione 2/20. Il commit `7e532fc9` sopprime i segnali di
enqueue e il self-requeue quando `canDispatch()` è falso; il rilascio slot
resta il wakeup che riattiva la funzione satura.

| fase | dispatch Java/s | queue wait ms | blocked/dispatch Java | coalesced/dispatch Java |
|---|---:|---:|---:|---:|
| spike600 | 531,9 | 2,063 | 0,18 | 0,04 |
| hold350 | 349,5 | 1,479 | 0,15 | 0,04 |
| **peak900** | **342,6** | **32,105** | **0,82** | **0,13** |
| drain40 | 330,6 | 15,919 | 0,61 | 0,12 |

Sull'intera run: 116.537 dispatch, 435,06 richieste/s, p95 90,72 ms,
p99 155,73 ms, 17,02% scarti e 28.512 rifiuti. Rispetto al controllo diretto
(346,8 dispatch Java/s e 31,040 ms al `peak900`; p95 90,22 ms), la differenza
è compatibile con il rumore di una sola ripetizione. Le guardie riducono i
contatori, ma non migliorano il limite: l'ipotesi che il churn delle visite non
dispatchable sia la causa dominante è invalidata. Non segue una nuova modifica
da questo A/B.

`caffeinate` è terminato. Le VM Azure trattenute da NanoLab sono state
rilasciate con il teardown del provider NanoLab; l'inventario Azure è vuoto.

## Localizzazione del wakeup

Run `azure-dispatch-wakeup-localization-c2`, una ripetizione con configurazione
2/20 e le guardie `canDispatch()`. Il nuovo timer sincrono misura soltanto
`activeFunctions.add` per i segnali accettati; il timer già esistente parte dal
timestamp pubblicato immediatamente prima dell'`add` e termina quando lo
scheduler ha estratto la funzione e rimosso il relativo bookkeeping.

| fase | dispatch Java/s | enqueue µs | segnale→scheduler µs | residuo fuori dall'`add` µs | wake/dispatch |
|---|---:|---:|---:|---:|---:|
| spike600 | 524,8 | 6,0 | 198,8 | 192,7 | 0,985 |
| hold350 | 349,6 | 6,3 | 177,4 | 171,1 | 0,988 |
| **peak900** | **308,6** | **61,5** | **995,2** | **933,7** | **0,956** |
| drain40 | 302,4 | 23,4 | 650,2 | 626,8 | 0,965 |

Al `peak900`, l'`add` vale solo il 6,2% del timer segnale→scheduler: è quindi
invalidata l'ipotesi che la contesa nell'inserimento della coda attiva sia il
collo dominante. Moltiplicando per 0,956 wake/dispatch, il percorso completo
contribuisce 0,952 ms per dispatch e il residuo esterno all'`add` 0,893 ms,
rispettivamente il 54,1% e il 50,8% dei 1,759 ms di idle fisico stimato. Il
wakeup è dunque localizzato fra il bookkeeping del segnale e l'attivazione del
thread scheduler (poll, rimozioni da map/set e scheduling del thread), non nel
`BlockingQueue.add`; resta circa metà dell'idle non spiegata e questa singola
ripetizione non dimostra ancora quale istruzione del residuo sia causale.

Risultato complessivo: 114.242 dispatch Java, 435,05 richieste/s, p95 94,46 ms,
p99 161,49 ms, 18,32% scarti e 30.807 rifiuti della coda Java. `caffeinate` è
terminato; il teardown dei provider NanoLab `loadgen` e `stack` è completato e
l'inventario Azure `nanofaas-comparison*` è vuoto.

## Split del percorso di wakeup

Run `azure-dispatch-wakeup-split-c2`, una ripetizione con configurazione 2/20.
`function_scheduler_poll_delay` termina subito al ritorno di
`activeFunctions.poll()`, mentre `function_scheduler_activation_bookkeeping_duration`
copre le rimozioni da map/set e l'attivazione fino a `processFunction`.

| fase | dispatch Java/s | enqueue µs | poll µs | bookkeeping µs | wake/dispatch |
|---|---:|---:|---:|---:|---:|
| spike600 | 521,7 | 15,9 | 264,3 | 2,2 | 0,99 |
| hold350 | 349,3 | 5,7 | 132,4 | 2,4 | 0,99 |
| **peak900** | **331,5** | **39,7** | **1.078,2** | **5,6** | **0,96** |
| drain40 | 319,9 | 30,7 | 790,4 | 2,4 | 0,96 |

Al `peak900`, il ritorno di `poll()` concentra il 99,5% del timer
segnale→scheduler; bookkeeping ed enqueue sono ordini di grandezza inferiori.
L'ipotesi del collo nel bookkeeping è invalidata: il percorso è localizzato nel
wakeup/scheduling del thread prima del ritorno di `poll()`.

Risultato complessivo: 115.209 dispatch Java, 435,06 richieste/s, p95 94,24 ms,
p99 141,11 ms, 17,67% scarti e 29.840 rifiuti della coda Java. `caffeinate` è
terminato; il teardown NanoLab è completato e l'inventario Azure è vuoto.

## Bilancio del thread scheduler

Run del 2026-08-22: `azure-scheduler-thread-accounting-c2`, `native-o3-g1`, una
ripetizione, concurrency 2 e coda 20. Commit mcFaas `8b90889e`; commit NanoLab
`e6fa60b`. Piano:
[`2026-08-22-dispatch-scheduler-thread-accounting.md`](../../plans/2026-08-22-dispatch-scheduler-thread-accounting.md).

`scheduler_visit_duration` e `scheduler_idle_duration` non hanno tag `function`:
il thread scheduler è uno solo e la `poll()` bloccante avviene prima che si
sappia per quale funzione si è svegliato. Insieme partizionano il wall clock del
ciclo, quindi su ogni finestra vale `Σvisit + Σidle ≤ finestra` — il primo
limite della serie che una sonda possa violare da sola.

| fase | dispatch/s | poll µs | visita µs | visite/dispatch | busy % | idle % | bilancio % | ≤100 |
|---|---:|---:|---:|---:|---:|---:|---:|:--:|
| hold200 | 199,8 | 29,7 | 83,3 | 1,35 | 2,2 | 97,6 | 99,9 | sì |
| spike600 | 517,5 | 321,6 | 89,9 | 1,38 | 6,4 | 93,4 | 99,8 | sì |
| hold350 | 349,7 | 152,5 | 85,2 | 1,33 | 4,0 | 95,9 | 99,8 | sì |
| **peak900** | **299,3** | **1.324,1** | **143,9** | **1,58** | **6,8** | **92,9** | **99,7** | **sì** |
| drain40 | 313,5 | 777,9 | 171,5 | 1,44 | 7,8 | 92,0 | 99,7 | sì |

Al `peak900` il bilancio chiude al 99,7% e il thread scheduler è occupato nelle
proprie visite per il **6,8%** del tempo. Non fa coda dietro sé stesso: dorme per
il **92,9%** mentre la coda sta a 16,67/20 e l'attesa in coda è 52,5 ms. Le
visite costano 143,9 µs e sono 1,58 per dispatch, quindi nemmeno il churn
riempie il thread.

**Verdetto: l'ipotesi «coda dietro le proprie visite» è falsificata.** I 1.324 µs
del timer segnale→`poll()` sono attesa reale, non tempo speso altrove. Il rimedio
indicato dalla tabella di decisione è la primitiva di attesa/scheduling del
thread, **non** lo sharding: shardare un thread occupato al 6,8% moltiplica i
dormienti, esattamente come §4.3 avvertiva.

Il meccanismo che resta da attribuire: con concurrency 2 e slot pieni,
`canDispatch()` è falso, quindi `processFunction` non si ri-segnala e il thread
si parcheggia; lo risveglia solo il `notifyWork` di `QueueManager.releaseSlot`.
La misura successiva deve separare il ritardo di *emissione* di quel segnale
dalla latenza di unpark del SO.

**Due finestre leggono `NO`** — `warm40` a 100,6% e `ramp900` a 100,1%. Sono
entrambe transitorie e lo sforamento è al massimo lo 0,6%: con scrape ogni 5 s su
finestre da 30 s, `nearest()` può prendere estremi fuori finestra e attribuirle
fino a qualche secondo di accumulo in più. Tutte le finestre stazionarie, inclusa
quella decisiva, stanno sotto 100. Non è la firma di una race come quella di §14,
che sforava del 130%.

Risultato complessivo: 435,06 richieste/s, p95 94,57 ms, p99 143,52 ms, 18,32%
scarti. La cella è a una ripetizione: i 299,3 dispatch/s al picco sono più bassi
dei 346,8 della sonda diretta, e con il thread al 6,8% non è l'overhead dei due
timer a spiegarlo. Le 12 risorse Azure sono state distrutte; inventario finale
vuoto.

## Il tetto era la quota di CPU del control plane

Run del 2026-08-22: `azure-cpu1-throttling-c2` e `azure-cpu4-c2`, `native-o3-g1`,
una ripetizione ciascuna, stesso profilo, 2/20. Commit mcFaas `3323982a`;
commit NanoLab `9f5ab50`. Unica variabile: `controlPlane.resources.limits.cpu`.

Il chart imposta 1 CPU per default (`values.yaml:24`) e nessuna delle quattordici
celle precedenti lo dichiarava. `process_cpu_usage` stava a 1,00 in tutte —
inclusa la build JVM, dove la metrica è affidabile — ma nessuno aveva letto il
denominatore: 0,91 core su otto sembrano margine, su uno sono il muro.

Le serie di throttling non erano raccoglibili: entrambi i job cAdvisor del chart
terminano con un `keep` che nomina tre metriche e scarta il resto, quindi la
domanda «quanta CPU è stata negata» tornava vuota allo scrape, non al catalogo.

| fase | — | disp/s | periodi strozzati | s strozzate | core | attesa coda | coda |
|---|---|---:|---:|---:|---:|---:|---:|
| hold200 | 1 CPU | 199,8 | 1,4% | 0,30 | 0,33 | 0,06 ms | 0,00 |
| | **4 CPU** | 199,9 | **0,0%** | 0,00 | 0,32 | 0,04 ms | 0,00 |
| spike600 | 1 CPU | 523,5 | 26,3% | 4,58 | 0,50 | 7,69 ms | 10,00 |
| | **4 CPU** | **598,4** | **0,0%** | 0,00 | 0,70 | **0,05 ms** | **0,00** |
| **peak900** | 1 CPU | **303,7** | **85,2%** | **44,74** | 0,82 | **50,27 ms** | **20,00** |
| | **4 CPU** | **843,4** | **0,0%** | 0,00 | 1,00 | **0,34 ms** | **3,33** |

Al `peak900` con 1 CPU l'**85,2% dei periodi CFS è strozzato** e il cgroup accumula
44,74 s di attesa forzata in 30 s di orologio (somma su tutti i thread). Alzando
il limite a 4 il throttling sparisce e il dispatch passa da 303,7 a **843,4/s**:
**2,8×**. L'attesa in coda crolla da 50,27 ms a 0,34 ms e la coda da 20/20 a
3,33.

Sull'intera run: dispatch 113.892 → 130.546 (+14,6%), rifiuti 31.157 → 14.503
(dimezzati), fallimenti k6 18,79% → 9,33%. Il guadagno complessivo è molto
inferiore al 2,8× perché solo le fasi di picco erano CPU-bound.

**La latenza peggiora, ed è coerente:** p95 96,78 → 157,65 ms, p99 150,32 →
254,87 ms. Con un core i rifiuti sono istantanei e abbassano i percentili; con
quattro, richieste che prima venivano respinte in 0 ms vengono servite in
decine. Il sistema converte fallimenti veloci in successi più lenti — un
confronto di percentili fra le due configurazioni misura popolazioni diverse.

**Verdetto: il tetto dei ~300 dispatch/s era la quota cgroup, non il percorso di
dispatch.** Le sonde da §10 a §19 restano valide come misure — il thread
*era* fermo il 93% del tempo — ma la causa non era la primitiva di attesa: era
un cgroup senza budget. Coerente anche con la sonda `azure-conc8-probe`, che
alzando la concorrenza a 8 rese +3,69% invece del ×4 atteso.

## Le ottimizzazioni in ingresso non si vedono a 1 CPU

Run del 2026-08-22: `azure-cpu1-inbound-opt-c2`, `native-o3-g1`, una ripetizione,
2/20, limite di CPU invariato a 1. Commit mcFaas `bb5c563a`. Il lotto misurato:
stack trace soppressi sulle tre eccezioni di rifiuto (`566ad711`), rifiuto della
coda piena prima di costruire l'esecuzione (`d79f0ccf`), sonde di reacquisizione
rimosse e header di risposta letti una volta invece di sei (`bb5c563a`).

| al `peak900` | baseline | + ottimizzazioni |
|---|---:|---:|
| dispatch/s | 303,7 | **287,9** |
| periodi CFS strozzati | 85,2% | 79,3% |
| attesa in coda | 50,27 ms | 48,39 ms |
| core control plane | 0,82 | 0,60 |
| p95 / p99 complessivi | 96,78 / 150,32 ms | 96,11 / 166,79 ms |
| rifiuti | 31.157 | 31.888 |

**Nessun miglioramento misurabile, e il dispatch è addirittura più basso del 5%.**

### Il difetto è nel disegno della misura, non nel risultato

Sotto throttling la CPU è il vincolo, quindi un risparmio di CPU per richiesta
*dovrebbe* tradursi in throughput. Non si vede, e la ragione è che l'assetto a
1 CPU è il peggiore possibile per misurarlo: con l'80% dei periodi strozzati la
varianza fra ripetizioni è dell'ordine del 5%, e queste ottimizzazioni valgono al
più qualche punto percentuale. Una ripetizione sola non può distinguerle da zero.

Le due misure che avrebbero senso, in ordine di costo:

1. **Un benchmark locale del percorso di rifiuto**, come quello di
   [`payload-passthrough.md`](../payload-passthrough.md): gratis, ripetibile,
   e misura direttamente i microsecondi risparmiati per rifiuto invece di
   cercarli dentro il rumore di una run da 25 minuti.
2. **Tre ripetizioni a 4 CPU**, dove il sistema non è strozzato e un risparmio di
   CPU si converte in throughput senza passare per la lotteria del quantum CFS.

### Cosa resta comunque vero

I quattro cambiamenti sono corretti per conto proprio e nessuno dipende da questa
misura: una sonda dimostrata sbagliata (§14) non deve restare a pubblicare numeri,
un'eccezione di controllo di flusso non ha bisogno di uno stack trace, un rifiuto
non deve costruire ciò che sta per abbandonare, e leggere sei volte gli stessi
header è lavoro ripetuto. Vanno tenuti come pulizia, **non** rivendicati come
guadagno di prestazioni finché qualcuno non li misura come si deve.

## Sweep sul budget di CPU (48 celle)

`azure-matrix-cpu4` · `cpu3` · `cpu2` · `cpu1`, notte del 2026-08-23. Quattro
matrici da 4 build × 3 ripetizioni, identiche tranne `limits.cpu`. Commit mcFaas
`bb5c563a`, NanoLab `9f5ab50`. Tabelle complete di latenza, throughput, CPU e RAM
nella §22 di
[`../../plans/2026-08-21-dispatch-bottleneck-and-comparison-rerun.md`](../../plans/2026-08-21-dispatch-bottleneck-and-comparison-rerun.md).

In sintesi, al variare del budget:

| cpu | migliore per latenza | p95 | JVM p95 | ginocchio |
|---:|---|---:|---:|---|
| 4 | JVM | 3,2 ms | 3,2 ms | — |
| 3 | JVM | 3,3 ms | 3,3 ms | — |
| **2** | **JVM** | **3,7 ms** | 3,7 ms | **qui** |
| 1 | native-o3-g1 | 93,8 ms | 113,7 ms | — |

L'ordinamento si inverte fra 1 e 2 core: la classifica pubblicata finora era una
proprietà del core singolo, non delle build. Il ginocchio è a 2: da lì in poi la
JVM chiede al più 2,8 core e il terzo non compra niente.

Il nativo mantiene un vantaggio non condizionato solo sulla memoria: 530–565 MiB
contro 880. Le build native seriali non tengono il passo nemmeno con quattro core
liberi — 16–19% di scarti, coda a ~10 su 20, **0% di throttling e 1,0 core usati
su 4** — che è una firma di collo di bottiglia ancora da spiegare.

## Fattoriale JVM: collettore x JIT (24 celle)

`azure-jvm-2x2-cpu2` e `azure-jvm-2x2-cpu1`, 2026-08-23. Quattro varianti — le
combinazioni di `-XX:+UseSerialGC`/`-XX:+UseG1GC` con C1-solo/tiering completo —
per 3 ripetizioni, a 2 e 1 core. mcFaas `e8bd580f`, NanoLab `ebd11ba`. Tabelle
generate in §23 del piano.

Esito: **restituire C2 è tutto il guadagno, G1 è una perdita**. A 2 core il p95
scende da 21,1 a 2,5 ms e la CPU **cala** da 0,91 a 0,53 core; a 1 core il p95
passa da 118,1 a 18,2 ms con il 40% di dispatch in più. G1 peggiora ogni asse a
entrambi i budget e costa 250–400 MiB di RSS. I due effetti **non si sommano**:
`G1+C2` è peggio di `seriale+C2`.

Azione: togliere `-XX:TieredStopAtLevel=1`, tenere `-XX:+UseSerialGC`.

E un avvertimento quantificato (§23.1): lo **stesso** build allo **stesso** budget
misura p95 3,7 ± 0,1 ms nella matrice dello sweep e 21,1 ± 3,6 in questa. Stretto
dentro ciascuna, diverso di **5,7×** fra le due. I confronti valgono dentro una
matrice, mai fra matrici.

## Raw e riproduzione

`raw/` contiene, per ogni cella, `comparison-manifest.json`, `k6-summary.json`,
`summary.json` e lo snapshot Prometheus completo. I report HTML non sono
versionati: contengono gli stessi JSON embedded e sono rigenerabili da NanoLab.
`SHA256SUMS` verifica che i raw non siano cambiati.

Ricalcolo della tabella completa per fase, solo con la standard library Python:

```bash
python3 docs/experiments/dispatch-bottleneck/analyze_snapshot.py \
  docs/experiments/dispatch-bottleneck/raw/azure-dispatch-reacquisition-segments-c2/\
native-o3-g1/run-1/metrics/prometheus-snapshot.json
```

```bash
cd docs/experiments/dispatch-bottleneck
shasum -a 256 -c SHA256SUMS
```

Riesecuzione dell'ultima sonda con i due commit indicati sopra:

```bash
export NANOFAAS_ROOT=/path/to/mcFaas
cd /path/to/nanolab
caffeinate -dimsu ./nanolab.sh compare \
  packages/nanolab/scenarios-v2/runtime-comparison-jvm.yaml \
  --environment packages/nanolab/environments/azure-comparison.yaml \
  --run-dir packages/nanolab/runs/azure-dispatch-wakeup-split-c2 \
  --variants native-o3-g1 --repetitions 1
```
