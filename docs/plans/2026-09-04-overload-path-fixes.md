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

**Stato:** da fare, ma ridimensionato — vedi "Quanto vale" sotto.

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

### Quanto vale, secondo il dato che già abbiamo

`docs/experiments/archive/refusal-cost.md` (2026-08-22) ha già misurato il
costo di ciò che questo filtro eviterebbe: l'andata-e-ritorno JSON completa
costa **0,99 µs**, e l'intera pulizia in ingresso di quella sessione valeva
**0,226% di un core** a 590 rifiuti/s — con la conclusione esplicita "sono
pulizia, non prestazioni". Scalando quel conto: sul percorso sync, saltare
solo la deserializzazione vale una frazione di quel totale, circa **0,06% di
un core**; sul percorso `:enqueue`, dove si evita anche l'handoff a
`boundedElastic` (6,1 µs contro 0,085 µs di lavoro utile), il conto sale a
circa **0,4%**.

Questo non è un argomento contro il filtro — è pulizia legittima, e il codice
async non ha mai ricevuto l'ottimizzazione che il sync ha già — ma ridimensiona
l'aspettativa: non ci si deve aspettare che l'Esperimento C mostri un effetto
sopra il rumore di una corsa Azure. Vedi il passo 0 aggiunto a quell'esperimento.

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

**Stato:** decisione presa il 2026-08-23, mai applicata. **Da rivalidare prima di
applicarla** (vedi Esperimento B).

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

Quello che resta effettivamente da costruire, verificato assente sia nel
codice sorgente (`grep -r "reactor.netty.eventloop.pending" platform/` non
trova nulla) sia nel catalogo di NanoLab:

| cosa manca | dove va | nota |
|---|---|---|
| gauge Micrometer dei task pendenti per event loop | `platform/control-plane` | il design esiste già: `2026-08-21-dispatch-bottleneck-and-comparison-rerun.md:2267-2269` — `Gauge.builder(..., singleThreadEventExecutor::pendingTasks)`, discusso e mai spedito. È il numero che ha rivelato gli 863; senza, l'Esperimento B non vede la coda dei loop, solo il suo sintomo (CFS e latenza) |
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
supporta system property arbitrarie oltre a GC/tiering. Il gauge dei task
pendenti per loop (vedi Prerequisito) va anche lui aggiunto prima, o la
metrica decisiva sotto resta vuota.

**Metriche decisive:** `nr_throttled/nr_periods`, p50/p95/p99, tasso di scarti,
task pendenti per loop, riavvii da liveness.

**Cosa decide:** se cambiare `platform/control-plane/Dockerfile:26`, e quale
valore di `reactor.netty.ioWorkerCount` mettere nel chart.

**Esito possibile e interessante:** se il vantaggio di C2 è crollato, la
conclusione della §23 era un artefatto della ritenzione illimitata. Sarebbe una
scoperta migliore di una conferma.

## Esperimento C — Il rifiuto costa meno se arriva prima

**Domanda:** il `WebFilter` della Parte I.1 produce una differenza misurabile?

**Passo 0 — banco locale prima di Azure.** `docs/experiments/archive/
refusal-cost.md` ha già stabilito che questo genere di segnale è sotto il
rumore delle corse Azure: a 1 core il throttling CFS oscilla dell'ordine del
5% fra ripetizioni contro un effetto atteso sotto l'1%; a 4 core il sistema non
è CPU-bound e non mostra nulla. Prima di spendere una corsa Azure, riprodurre
lo stesso banco locale di quella nota — profondità di stack realistica via
`deep(n, make)`, 50k giri di riscaldamento, 5 tornate da 50k, minimo — per
confrontare il costo di un 429 con e senza il filtro, **su entrambi `:invoke`
e `:enqueue`**. Se il numero non supera il rumore di fondo misurato in quella
sessione, **non eseguire la corsa Azure**: la differenza non sarebbe
distinguibile, e la conclusione corretta sarebbe la stessa di allora — "sono
pulizia, non prestazioni" — non "non ha funzionato".

**Se il banco locale mostra un effetto sopra rumore, forma della corsa Azure:**
due bracci — `main` contro il branch col filtro — a carico di sovraccarico. Il
riferimento è `azure-load3x`: **23,12% di scarti** a 2 core, cioè un regime in
cui quasi un quarto delle richieste percorre il cammino del rifiuto. **Va corsa
in entrambe le modalità di invocazione**, non solo `INVOCATION_MODE=sync` (il
default di `experiments/k6/common.js:10`, e quindi implicito in
`azure-load3x`): il caso più forte per il filtro è l'handoff a
`boundedElastic` su `:enqueue`, che un regime sync non esercita affatto — vedi
il conto in "Quanto vale" nella Parte I.1.

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

**Viene dopo:** richiede che il codice esista, e che il passo 0 l'abbia
giustificato.

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
2. Prerequisito di codice per B: gauge dei task pendenti per event loop in
   `platform/control-plane` (design in
   `2026-08-21-dispatch-bottleneck-and-comparison-rerun.md:2267-2269`) e le
   due varianti `jvm-loop1`/`jvm-c2-loop1` in
   `control_plane_variants.py` (`JVM_TUNING` + `-Dreactor.netty.ioWorkerCount=1`)
3. Esperimento B — JIT × event loop. Scommentare `B-loop-cpu1` in `queue.tsv`
   dopo il passo 2 ed eseguire.
4. Banco locale — costo del rifiuto con/senza filtro, `:invoke` e `:enqueue`
   (Esperimento C, passo 0). Non passa da NanoLab: microbenchmark Java nel
   repo nanofaas, risultato in `docs/experiments/archive/refusal-cost.md` o
   un file gemello.
5. Solo se il passo 4 supera il rumore di fondo: codice `WebFilter`
   (Parte I.1) scoperto sui soli suffissi `:invoke`/`:enqueue`, poi corsa
   Azure dell'Esperimento C in entrambe le modalità di invocazione
6. Applicare, o non applicare, la Parte I.2 e I.3 secondo B
