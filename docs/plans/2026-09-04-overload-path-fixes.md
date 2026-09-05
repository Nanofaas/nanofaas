# Percorso di sovraccarico: tre modifiche e tre esperimenti

**Data:** 2026-09-04, rivisto lo stesso giorno dopo revisione critica pre-esecuzione
**Stato:** da eseguire
**Continua:** `2026-08-21-dispatch-bottleneck-and-comparison-rerun.md`, `2026-08-26-execution-store-outcome.md`

**Revisione:** la Parte I.1 e l'Esperimento C erano scritti come se il costo
del rifiuto ingresso fosse ancora ignoto. `docs/experiments/archive/
refusal-cost.md` (2026-08-22) lo aveva già misurato per lo stesso percorso —
0,226% di un core, "pulizia non prestazioni" — e ne aveva già escluso la
misurabilità su Azure per rumore di fondo. L'Esperimento C ora porta un passo
0 locale che riusa quel banco prima di impegnare una corsa Azure, il filtro è
scoperto sui soli suffissi di invocazione, e viene testato in modalità sync
*e* async (il caso forte, l'handoff `boundedElastic`, è async, ma il default
di k6 è sync).

**Revisione 2:** `../nanolab` (checkout separato che questo progetto usa per
l'orchestrazione, vedi `CLAUDE.md`) possiede già gran parte di ciò che il
Prerequisito e l'Esperimento B chiedevano di costruire. La prima revisione
aveva corretto il prerequisito da "costruire un collettore nel pod" a
"interrogare la Prometheus del cluster" — ma quella Prometheus, con la sua
raccolta di RSS e CFS, è **già cablata end-to-end** dentro il workflow
`compare` di NanoLab (`packages/nanolab/src/nanolab/metrics/
catalogue.py::container_queries`), non solo scrapata e disponibile. La stessa
infrastruttura ha già prodotto i numeri che questo piano cita
(`azure-jvm-2x2-cpu{1,2}`, `azure-matrix-cpu1…4`) tramite scenari e uno script
di sweep che esistono tuttora. Il Prerequisito, l'Esperimento A e
l'Esperimento B sotto sono riscritti per riusare quell'infrastruttura invece
di estendere `experiments/e2e-memory-ab.sh`, che resta lo strumento giusto
solo per la sua domanda originale (epoch-millis on/off) e non era mai stato
pensato per la matrice JIT×loop o per il tetto di memoria. La Parte I e
l'Esperimento C restano come nella prima revisione: sono codice applicativo
nanofaas, non infrastruttura di misura.

## Il problema comune

Tutto ciò che segue riguarda cosa succede quando il control plane riceve più di
quanto può servire. Le tre modifiche sono indipendenti fra loro; gli esperimenti
esistono per stabilire quali valgono davvero e quanto.

## Il vincolo che governa il disegno

La §23.1 del piano 2026-08-21 documenta che **lo stesso identico build, a parità
di core, ha dato p95 di 3,7 ms in una matrice e 21,1 ms in un'altra** — fattore
5,7, VM Azure diverse a ore diverse. Da cui la regola operativa: *i confronti
valgono dentro una matrice, mai fra matrici.*

Ne discende il disegno di tutto ciò che segue: **ogni domanda porta i suoi bracci
dentro un'unica corsa.** Non si confronta un run di oggi con un numero di agosto.

## Lo stato dei dati

Ogni corsa in `docs/experiments/archive/` è del **2026-08-24 o prima**. Lo split
`inFlight`/`outcomes` è del **2026-08-26**. Quindi l'intero archivio misura un
programma che non esiste più: RSS 904,6 → 356,7 MiB, promozione 2,64 → 0,26 MB/s,
full GC 2 → 0 (`2026-08-26-execution-store-outcome.md:167`).

Nessuna conclusione di agosto va data per valida senza rimisurarla.

---

# Parte I — Le modifiche

## 1. Rate limit in un `WebFilter`, prima del body

**Stato:** fatto — `RateLimitWebFilter` (`platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/api/RateLimitWebFilter.java`), scoped a `:invoke`/`:enqueue`, corpo drenato prima del 429. `RateLimitException` e il controllo in `InvocationService` sono stati rimossi. Vedi `docs/superpowers/plans/2026-09-04-rate-limit-webfilter.md`. Poiché il filtro corto-circuita prima che giri l'handler mapping di Spring, un 429 da rate limit viene registrato nella metrica `http.server.requests` con `uri=UNKNOWN` invece della rotta di invocazione — innocuo per gli strumenti esistenti (che filtrano solo su `status="429"`, non su `uri`), ma da tenere presente per chi in futuro volesse scomporre i rifiuti per rotta.

`RateLimiter.allow()` gira a `InvocationService.java:99,126` (chiamato da
`invokeSyncReactive` e `invokeAsync`), dietro decode HTTP, deserializzazione
Jackson del body e dispatch del controller. Ogni richiesta che finirà in 429
paga tutto questo prima di essere rifiutata, esattamente quando la piattaforma
ha meno da spendere. (I numeri di riga del piano originale, `:217`, sono il
corpo del metodo privato `enforceRateLimit()`; i due call site sono `:99` e
`:126`.)

Su `:enqueue` è peggio: `InvocationController.java:148` avvolge la chiamata in
`subscribeOn(Schedulers.boundedElastic())`, quindi un rifiuto costa un thread
handoff — lo stesso scambio 6,1 µs contro 0,085 µs che il percorso sync ha già
eliminato (`InvocationService.java:106-116`). Il ragionamento non è mai stato
applicato al percorso async.

### Quanto vale — misurato, non stimato (aggiornato dopo il codice)

**Il conto qui sotto era analitico e si è rivelato sbagliato di segno sul
sync.** Prima che il filtro esistesse, `docs/experiments/archive/
refusal-cost.md` (2026-08-22) aveva misurato solo i pezzi che il filtro
avrebbe evitato — deserializzazione JSON (0,99 µs andata-e-ritorno),
handoff a `boundedElastic` (6,1 µs) — e da lì un conto per scala aveva
stimato ~0,06% di un core sul sync e ~0,4% sull'`:enqueue`, entrambi
positivi. Una volta scritto il filtro, `docs/experiments/archive/
webfilter-refusal-cost.md` (2026-09-04, passo 0 dell'Esperimento C) lo ha
misurato per davvero, includendo il costo che il conto analitico non poteva
vedere: la pipeline Reactor del filtro stesso (`getBody()` → `doOnNext` →
`then(Mono.defer(...))` → `block()`).

**Sul sync il filtro perde**, di poco: −300...−600 ns per rifiuto su quattro
corse indipendenti (segno stabile, ampiezza no), circa −0,02%...−0,04% di un
core a 590 rifiuti/s — la pipeline reattiva costa più di quanto la
deserializzazione JSON e il throw/catch che sostituisce costassero. **Sull'
`:enqueue` il filtro vince**, e di molto: 7,3–13,1 µs per rifiuto,
0,43%–0,77% di un core, perché evita per intero l'handoff a
`boundedElastic` (rimisurato a 7,9–13,3 µs, non i 6,1 µs della sessione di
agosto — stesso ordine di grandezza, macchina diversa, sessione diversa).

Questo non cambia la decisione — il filtro resta pulizia legittima sul sync
(la perdita è irrilevante in assoluto, sotto la soglia a cui la sessione di
agosto giudicava "pulizia, non prestazioni" un effetto *positivo* venti
volte più grande) — ma cambia dove guardare: l'Esperimento C, se gira su
Azure, deve girare in `INVOCATION_MODE=async`. Un run in sync (il default di
k6) non vedrebbe niente di distinguibile dal rumore, e quel niente sarebbe
la risposta corretta, non un fallimento della misura.

**Cosa fare:** un `WebFilter` che consulta `RateLimiter` **solo sui due
suffissi di invocazione** (`:invoke`, `:enqueue`) — non `/v1/functions/**`
intero, che includerebbe registrazione, listing e cancellazione, oggi non
sottoposti a rate limit — e chiude con 429 prima che il body venga letto. Poi
togliere il controllo da `InvocationService.java:99,126` e i due
`onErrorResume(RateLimitException.class, ...)` a
`InvocationController.java:92,155`.

**Effetto collaterale da accettare consapevolmente:** il filtro gira prima di
`@Valid` e prima della lookup della funzione, quindi sotto overload un payload
malformato torna 429 invece di 400, e una funzione inesistente torna 429
invece di 404. È intrinseco a spostare il controllo prima del parsing.
`InvocationControllerTest.java` e `RejectionExceptionsTest.java` sono i posti
più probabili in cui questo cambia un'asserzione esistente — verificare prima
di aprire la PR.

**Cosa NON risolve:** i 668 ms di attesa pre-applicativa annotati in
`NettyServerMetricsConfig`. Quel tempo si consuma nella accept queue e nelle
letture pendenti, prima che qualunque filtro giri — un `ChannelInboundHandler`
Netty girerebbe nello stesso punto, sullo stesso thread di event loop, e non
risparmierebbe altro. Rifiuti più economici drenano la coda più in fretta; non la
accorciano.

## 2. Event loop pari alla quota CPU, non al pavimento di 4

**Stato:** da fare.

`LoopResources.DEFAULT_IO_WORKER_COUNT` è `max(4, availableProcessors())`
(verificato nel bytecode di reactor-netty 1.3.6). Con `limits.cpu: 1` vince il
pavimento: quattro event loop condividono una quota da un core, bruciano il budget
di 100 ms in circa 25 ms di orologio, e il container resta congelato per il resto
di ogni periodo. È il 68,9% di periodi CFS strozzati registrato in
`NettyServerMetricsConfig`, e gli 863 task pendenti per loop sono thread pronti ma
non schedulati.

**Il throughput non è ciò che questo costa.** Una quota di un core-secondo al
secondo produce lo stesso lavoro sia che lo consumi un thread o quattro. Costa
latenza: il servizio arriva a raffiche di 25 ms separate da 75 ms di nulla, quindi
p99 e liveness probe finiscono dentro un congelamento. È il guasto realmente
accaduto — un pod ucciso da una probe con tutto lo stato in memoria.

Un event loop solo non può superare una quota da un core, quindi non viene mai
strozzato. Stesso throughput, servizio continuo, e niente context switching fra
quattro thread su una CPU.

**Cosa fare:** impostare `-Dreactor.netty.ioWorkerCount` alla parte intera della
quota CPU. È una system property: nessun codice.

Prima sistemare `deploy/k8s/control-plane-deployment.yaml:33`, o saltarlo lì:
concede `500m`, e un singolo thread che gira continuo ne vuole uno intero —
strozzato al ~50% a qualunque numero di loop. **Sotto una quota di `1` il numero
di thread smette di essere una leva.**

**Da tenere d'occhio:**
- La quota del cgroup è condivisa con i due loop di management, `boundedElastic`,
  GC e JIT. Meno event loop riduce il throttling; non lo elimina.
- `availableProcessors() == 1` fa anche scegliere SerialGC alla JVM.
- Con un solo loop, qualunque chiamata bloccante nel request path ferma tutto il
  traffico invece di un quarto. Verificare il percorso sync: client Fabric8,
  Jackson su payload grandi, risoluzione DNS.

## 3. Allineare il default JIT del Dockerfile alla conclusione già presa

**Stato:** rivalidata su Esperimento B (`B-loop-cpu1`, 2026-09-05, 3
ripetizioni per braccio, tutti e quattro vivi a fine cella) — **la conclusione
regge sul codice di oggi**, dopo il fix dell'ExecutionStore. A 4 event loop
(configurazione spedita), `jvm-c2` contro `jvm`: p99 19,7 contro 176,5 ms
(9×), scarti 0,46% contro 26,63% (58×), CPU strozzata 5,5% contro 22,6%.
Il dubbio che C2 vincesse solo per la GC pressure del vecchio
`ExecutionStore` (sotto) è chiuso: la pressione non c'è più, e il divario
resta enorme. **Applicare.**

`2026-08-21-dispatch-bottleneck-and-comparison-rerun.md:1588`:

> **Conseguenza operativa:** togliere `-XX:TieredStopAtLevel=1` e **tenere**
> `-XX:+UseSerialGC`. Una riga, e a 1 core vale il 40% di dispatch in più.

`platform/control-plane/Dockerfile:26` spedisce ancora
`-XX:+UseSerialGC -XX:TieredStopAtLevel=1`, mentre `deploy/compose/Dockerfile:49`
ha già solo `-XX:+UseSerialGC`. **I due default sono divergenti.**

### Perché rivalidare invece di applicare e basta

I numeri del 2026-08-23, matrice `azure-jvm-2x2-cpu1`, 3 ripetizioni, 435 rps:

| variante | p50 | p95 | p99 | scarti |
|---|---:|---:|---:|---:|
| `jvm` — seriale + C1 ← default attuale | 2,7 | 127,4 | 182,8 | **27,55%** |
| `jvm-g1` — G1 + C1 | 12,8 | 155,9 | 293,2 | 34,57% |
| `jvm-c2` — seriale + C2 | 1,2 | **17,9** | **46,1** | **1,62%** |
| `jvm-g1-c2` — G1 + C2 | 1,2 | 57,7 | 90,2 | 7,10% |

A 2 core lo stesso ordinamento con distanze molto minori (p99 33,1 → 5,0).

**Ma quelle corse precedono il fix dell'ExecutionStore.** In quel momento il
control plane stava annegando nella GC: 2 full GC da 512 ms, promozione a 2,64
MB/s, live set 250,7 MB dopo una full. C2 potrebbe aver vinto perché compilava
meglio il codice *della garbage collection*, non perché serviva meglio le
richieste. Tolta la pressione, il vantaggio può essersi ridotto di molto.

Applicare senza rimisurare significherebbe portare in produzione una conclusione
che potrebbe essere un artefatto di un bug già corretto.

### Fatto, 2026-09-05

`platform/control-plane/Dockerfile:26` allineato a
`deploy/compose/Dockerfile:49`: `-XX:+UseSerialGC` senza
`-XX:TieredStopAtLevel=1`. (Le build native non hanno un default JIT da
allineare — sono AOT, `-XX:TieredStopAtLevel` non le riguarda.)

---

# Parte II — Gli esperimenti

## Prerequisito — strumentare ciò che stiamo misurando

**Molto più piccolo di quanto sembrava alla prima stesura: la maggior parte
esiste già, in NanoLab, non nel repo nanofaas.**

`experiments/e2e-memory-ab.sh` campiona `/actuator/prometheus` ogni 5 s e ne
estrae heap usato, heap max, pause GC e thread vivi
(`sample_prometheus_text_to_jsonl`, righe 96-155): **non raccoglie né l'RSS del
container né il throttling CFS**. Questo è vero e resta vero — ma quello script
non è il vettore giusto per l'Esperimento A o B: è lo strumento che confronta
`CONTROL_PLANE_EPOCH_MILLIS_ENABLED` acceso/spento, una domanda diversa. Il
vettore giusto per A e B è il workflow `compare` di NanoLab, ed *è già
strumentato*:

`packages/nanolab/src/nanolab/metrics/catalogue.py::container_queries()`
interroga, per ogni run k8s, esattamente le due metriche che qui mancavano —
`container_memory_bytes@control-plane` (da
`container_memory_working_set_bytes`, il working-set del container: la
funzione la preferisce esplicitamente a un gauge JVM di heap perché "un
control plane JVM con un heap gauge piccolo aveva un RSS di 1002 MiB") e
`container_cpu_throttled_periods@control-plane` /
`container_cpu_periods@control-plane` /
`container_cpu_throttled_seconds@control-plane` (dai contatori CFS). Queste
query alimentano `comparison.json`/`comparison.md` di ogni corsa `nanolab.sh
compare`, e la stessa serie di RSS alimenta anche la pipeline di regressione
release (`packages/nanolab/src/nanolab/release/metrics.py:216`) — è il
percorso che ha prodotto il commit "Record the v0.20.0 performance release"
in questo repo. Non c'è nulla da costruire per ottenere RSS e CFS: c'è solo da
usare `nanolab.sh compare` invece di `e2e-memory-ab.sh` per queste due
domande.

**Correzione, 2026-09-04.** La riga sotto diceva che il gauge dei task
pendenti per event loop andava ancora costruito. Falso, e il modo in cui
l'avevo verificato aveva un buco: `grep -r "reactor.netty.eventloop.pending"
platform/` cerca una stringa nel codice sorgente di nanofaas, ma il gauge
non vive lì — vive nel bytecode di reactor-netty stesso
(`reactor.netty.transport.EventLoopMeters`/`MicrometerEventLoopMeterRegistrar`,
verificato nel jar `reactor-netty-core-1.3.6`), e si attiva automaticamente
ogni volta che le metriche del server sono accese — cosa che
`NettyServerMetricsConfig` fa già (`server.metrics(true, ...)`). Verificato
avviando davvero l'app e leggendo `/actuator/prometheus`: con venti
richieste concorrenti compaiono venti serie
`reactor_netty_eventloop_pending_tasks{name="reactor-http-nio-N"}`, una per
loop. E NanoLab lo sa già da agosto: `packages/nanolab/src/nanolab/metrics/
catalogue.py:338-344` ha sia `netty_eventloop_pending` (sommato) sia
`netty_eventloop_pending_per_loop` (per thread) — è il numero che nel 2026-08-23
aveva già rivelato gli 863 task in coda. Non c'è niente da costruire su
questo fronte.

Quello che resta effettivamente da costruire:

| cosa manca | dove va | nota |
|---|---|---|
| il conteggio degli event loop come asse della matrice | `packages/nanolab/src/nanolab/images/control_plane_variants.py` | oggi `ControlPlaneVariant.build_env` porta solo `JVM_TUNING` (GC/tiering) per le build JVM; `-Dreactor.netty.ioWorkerCount=N` è una system property e può entrare nello stesso `JVM_TUNING` — vedi Esperimento B |

L'impalcatura di `e2e-memory-ab.sh` — provisioning VM, deploy A/B, k6,
campionamento durante la corsa — resta la scelta giusta per la sua domanda
originale (epoch-millis) e non va toccata per questo piano.

## Esperimento A — Quanta RAM serve adesso

**Domanda:** `limits.memory: 2Gi` è ancora giusto?

Non è un confronto: è una misura assoluta sul codice di oggi, sotto carico. La
prova locale post-fix dice **356,7 MiB con un limite di 1 GiB**. Se regge anche su
Azure, il chart riserva quattro volte la memoria che usa — memoria che il cluster
non può dare ad altri.

**Forma:** un braccio, `jvm-c2`, codice corrente, profilo di carico standard.
**Esito:** un valore di `limits.memory` giustificato da una misura.
**Costo:** il più basso dei tre. Da fare per primo dopo la strumentazione.

**Dove:** `docs/experiments/overload-path-2026-09/`, celle `A-mem1024` e
`A-mem512` in `queue.tsv`, sugli scenari NanoLab già esistenti
`runtime-comparison-mem{1024,512}.yaml`. Non lanciato da questa revisione del
piano — richiede `az login` e un resource group Azure, vedi il README di
quella campagna per i prerequisiti prima di eseguire `run-queue.sh`.

## Esperimento B — Il JIT vale ancora, e quanti event loop

**Domanda 1:** dopo il fix dell'ExecutionStore, C2 batte ancora C1?
**Domanda 2:** a 1 core, un event loop batte quattro?

Le due stanno nella stessa corsa perché attaccano lo stesso fenomeno — la fame di
CPU a 1 core — e perché la §23.1 vieta di confrontarle fra corse diverse.

| braccio | JIT | event loop |
|---|---|---|
| A | C1 (default attuale) | 4 (default) |
| B | C2 | 4 |
| C | C1 | 1 |
| D | C2 | 1 |

**Regime:** 1 core, carico che satura, 3 ripetizioni. Stessa forma di
`azure-jvm-2x2-cpu1`, quindi lo scenario NanoLab si riusa quasi tale e quale.

**Dove, e cosa manca prima di poter lanciare:**
`docs/experiments/overload-path-2026-09/`, cella `B-loop-cpu1` in
`queue.tsv`, sullo scenario esistente `runtime-comparison-cpu1.yaml`. La riga
è presente ma commentata: i bracci C e D (`jvm-loop1`, `jvm-c2-loop1`) non
esistono ancora come `ControlPlaneVariant` — vanno aggiunti a
`control_plane_variants.py` appendendo `-Dreactor.netty.ioWorkerCount=1` al
`JVM_TUNING` dei due bracci esistenti (`jvm`, `jvm-c2`), il meccanismo già
supporta system property arbitrarie oltre a GC/tiering. **Questo è l'unico
prerequisito rimasto** — il gauge dei task pendenti per loop esiste già
(vedi Prerequisito), NanoLab lo interroga già come `netty_eventloop_pending_per_loop`.

**Metriche decisive:** `nr_throttled/nr_periods`, p50/p95/p99, tasso di scarti,
task pendenti per loop, riavvii da liveness.

**Cosa decide:** se cambiare `platform/control-plane/Dockerfile:26`, e quale
valore di `reactor.netty.ioWorkerCount` mettere nel chart.

**Esito possibile e interessante:** se il vantaggio di C2 è crollato, la
conclusione della §23 era un artefatto della ritenzione illimitata. Sarebbe una
scoperta migliore di una conferma.

## Esperimento C — Il rifiuto costa meno se arriva prima

**Domanda:** il `WebFilter` della Parte I.1 produce una differenza misurabile?

**Passo 0 — fatto, 2026-09-04.** Risultato completo in
`docs/experiments/archive/webfilter-refusal-cost.md`. Quattro corse
indipendenti del banco locale (stesso metodo di `refusal-cost.md`:
`deep(n, make)`, 50k di riscaldamento, 5 tornate da 50k, minimo — più una
correzione necessaria: la costruzione di `MockServerWebExchange` va isolata
e sottratta, altrimenti domina la misura e ne inverte il segno):

- **Sync (`:invoke`): il filtro perde**, −300...−600 ns per rifiuto
  (segno stabile sulle quattro corse), −0,02%...−0,04% di un core a 590
  rifiuti/s. La pipeline Reactor del filtro costa più della
  deserializzazione JSON + throw/catch che sostituisce. Irrilevante in
  assoluto — sotto la soglia a cui la sessione di agosto giudicava "pulizia,
  non prestazioni" un effetto *positivo* venti volte più grande — ma il
  segno è opposto a quanto stimato in "Quanto vale" nella Parte I.1 prima
  che il filtro esistesse davvero.
- **`:enqueue`: il filtro vince**, 7,3–13,1 µs per rifiuto, 0,43%–0,77% di
  un core, evitando per intero l'handoff a `boundedElastic` (rimisurato a
  7,9–13,3 µs, non i 6,1 µs di agosto — stessa scala, sessione diversa).

**La corsa Azure, se si fa, va in entrambe le modalità — sync e async —
non solo in quella dove il passo 0 prevede un segnale.** Il passo 0 è un
banco locale: prevede che il sync sia sotto il rumore CFS (~5% a 1 core
contro un effetto ormai noto essere sotto lo 0,04%), ma è una previsione,
non una misura su Azure — e questo intero piano poggia sul principio che
nessuna previsione va data per valida senza misurarla (§ "Il vincolo che
governa il disegno"). Saltare il sync perché il locale lo prevede
irrilevante sarebbe esattamente l'errore che il passo 0 doveva prevenire,
solo spostato di un livello. Due esiti sono entrambi informativi: se il
sync su Azure conferma "nessun segnale sopra il rumore", il passo 0 è
convalidato come previsione affidabile per le prossime domande di questo
tipo; se mostra qualcosa, il banco locale ha un limite da capire prima di
fidarsene ancora.

Il segnale su `:enqueue` (0,43%–0,77%) resta comunque il braccio che
giustifica la spesa nel senso stretto — un ordine di grandezza sopra quanto
la sessione di agosto aveva già giudicato invisibile su Azure, e
potenzialmente sopra il rumore se la corsa satura davvero un core — ma non
è un motivo per non misurare anche il sync nella stessa corsa.

**Forma della corsa Azure, se si fa:** due bracci — `main` contro il branch
col filtro — a carico di sovraccarico, **in entrambe le modalità di
invocazione**. Il riferimento di carico resta `azure-load3x` (**23,12% di
scarti** a 2 core in sync); la sua controparte async va misurata nella
stessa corsa, non assunta.

**Rischio da escludere prima di fidarsi del numero:** chiudere un 429 senza
aver consumato il body della richiesta può far chiudere la connessione HTTP
invece di riusarla in keep-alive (comportamento noto di reactor-netty quando
il body non viene drenato). Sotto overload questo aggiungerebbe un handshake
TCP per rifiuto — un costo di ordini di grandezza superiore a quanto il filtro
fa risparmiare, che lo renderebbe una regressione mascherata da
ottimizzazione. Il filtro deve drenare o scartare esplicitamente il body prima
di chiudere; verificare `connections.active` (già esposto da
`NettyServerMetricsConfig`) prima/dopo per escludere un aumento delle
riconnessioni.

**Metriche decisive:** CPU per richiesta rifiutata, p99 delle richieste
**accettate** (è lì che deve vedersi il guadagno), throughput a parità di carico
offerto, `connections.active`.

**Stato:** entrambe le precondizioni sono soddisfatte — il codice esiste
(`RateLimitWebFilter`, fatto 2026-09-04) e il passo 0 lo giustifica per
`:enqueue` (per il sync prevede assenza di segnale, da verificare, non da
assumere). Non lanciata da questa revisione del piano: richiede Azure e va
eseguita deliberatamente, in entrambe le modalità di invocazione.

## Ciò che ho deliberatamente rimandato: la matrice a 4 core

Chiesta in conversazione, e la lascio fuori con una ragione.

Il fattoriale JIT × GC esiste solo a 1 e 2 core. A 3 e 4 core esistono solo
confronti JVM contro native image, dove la riga `jvm` è il build di default. Ma il
dato che c'è dice perché rifarlo così non servirebbe — `azure-matrix-cpu1…4`, riga
`jvm`, carico fisso 435 rps:

| core | p95 | p99 | scarti |
|---|---:|---:|---:|
| 1 | 113,6 | 180,6 | 25,92% |
| 2 | 3,7 | 7,5 | 0,03% |
| 3 | 3,3 | 6,2 | **0,00%** |
| 4 | 3,3 | 6,1 | **0,00%** |

**La penalità di C1 sparisce già a 2 core.** Da 3 in su il sistema non è sotto
pressione, e le varianti si separano solo in saturazione. Un 2×2 a 4 core con
questo carico darebbe quattro righe indistinguibili; per renderlo informativo
andrebbe quadruplicato anche il carico, il che lo rende la corsa più lunga e cara
per rispondere a una domanda che si ha solo distribuendo su 4 core. Il chart oggi
dice `1`.

**Da riprendere quando** `limits.cpu` sale stabilmente sopra 2.

## Una previsione da rileggere

La §24 del piano 2026-08-21 prevede il muro di concorrenza a **~3.799 rps** a 2
core. Le corse effettive: `azure-load2x` 843,7 rps con 6,02% di scarti,
`azure-load3x` 1.135,4 rps con 23,12%. Il tetto sembra arrivare molto prima del
previsto. La previsione era dichiarata falsificabile; vale la pena rileggerla
accanto a questi numeri prima di costruirci sopra altro.

---

## RAM per variante, per riferimento

`azure-jvm-2x2-cpu{1,2}`, RSS max e heap max, mediana di 3 corse. **Pre-fix
ExecutionStore: l'ordinamento regge, i valori assoluti no.**

| variante | RSS 1 core | heap 1 core | RSS 2 core | heap 2 core |
|---|---:|---:|---:|---:|
| `jvm` (seriale + C1) | 704 | 538 | 878 | 709 |
| `jvm-g1` (G1 + C1) | 840 | 622 | 1129 | 834 |
| `jvm-c2` (seriale + C2) | 958 | 713 | 967 | 723 |
| `jvm-g1-c2` (G1 + C2) | 1220 | 848 | 1288 | 934 |

MiB. **G1 costa fra 250 e 320 MiB** rispetto al seriale a parità di JIT, coerente
con la §23 (880 → 1130 → 1287), e perde anche in latenza: nessuna ragione per
riconsiderarlo. C2 costa memoria a 1 core (704 → 958) per code cache e metadata,
differenza che quasi sparisce a 2 core.

## Ordine di esecuzione

Raccolta grezza e tabelle di A e B vivono in
`docs/experiments/overload-path-2026-09/` (README, `queue.tsv`,
`run-queue.sh`, `raw/`), sul modello di `../baseline-2026-08/`. Nessuna cella
è stata eseguita da questa revisione del piano — richiede Azure e va lanciato
deliberatamente.

1. Esperimento A — memoria. Nessun prerequisito di codice: le celle
   `A-mem1024`/`A-mem512` in `queue.tsv` sono pronte, RSS e CFS arrivano già
   da NanoLab (`container_queries`). Lanciabile subito dopo `az login`.
2. ~~Prerequisito per B~~ — **fatto, 2026-09-04**: le due varianti
   `jvm-loop1`/`jvm-c2-loop1` aggiunte a `control_plane_variants.py` sul
   branch NanoLab `feature/loop-count-variants` (14 test, tutti verdi;
   `ruff check` pulito) — non ancora unito a `main` di NanoLab. Il gauge dei
   task pendenti per loop non serve costruirlo — esiste già, vedi
   Prerequisito e nota di correzione del 2026-09-04.
3. Esperimento B — JIT × event loop. La riga `B-loop-cpu1` in `queue.tsv` è
   scommentata; richiede solo che `feature/loop-count-variants` sia unito o
   estratto nel checkout NanoLab della corsa prima di eseguire.
4. ~~Banco locale (Esperimento C, passo 0)~~ — **fatto, 2026-09-04**, risultato
   in `docs/experiments/archive/webfilter-refusal-cost.md`: perdita
   irrilevante sul sync, vittoria netta su `:enqueue` (0,43%–0,77% di un
   core). Eseguito dopo il codice, non prima come pianificato in origine —
   ha misurato il filtro vero, non un'ipotesi.
5. ~~Codice `WebFilter` (Parte I.1)~~ — **fatto, 2026-09-04**:
   `RateLimitWebFilter`, scoperto sui soli suffissi `:invoke`/`:enqueue`,
   piano di implementazione in
   `docs/superpowers/plans/2026-09-04-rate-limit-webfilter.md`.
6. Corsa Azure dell'Esperimento C, **in entrambe le modalità di
   invocazione** — il passo 0 prevede assenza di segnale in sync, ma è una
   previsione locale da verificare su Azure, non da dare per scontata.
   Priorità più bassa di A e B, dato che il guadagno misurato del braccio
   che conta (`:enqueue`) è reale ma piccolo in assoluto (sotto l'1% di un
   core).
7. Applicare, o non applicare, la Parte I.2 e I.3 secondo B
