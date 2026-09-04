# Percorso di sovraccarico: tre modifiche e tre esperimenti

**Data:** 2026-09-04
**Stato:** da eseguire
**Continua:** `2026-08-21-dispatch-bottleneck-and-comparison-rerun.md`, `2026-08-26-execution-store-outcome.md`

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

**Stato:** da fare.

`RateLimiter.allow()` gira a `InvocationService.java:217`, dietro decode HTTP,
deserializzazione Jackson del body e dispatch del controller. Ogni richiesta che
finirà in 429 paga tutto questo prima di essere rifiutata, esattamente quando la
piattaforma ha meno da spendere.

Su `:enqueue` è peggio: `InvocationController.java:149` avvolge la chiamata in
`subscribeOn(Schedulers.boundedElastic())`, quindi un rifiuto costa un thread
handoff — lo stesso scambio 6,1 µs contro 0,085 µs che il percorso sync ha già
eliminato (`InvocationService.java:106-116`). Il ragionamento non è mai stato
applicato al percorso async.

**Cosa fare:** un `WebFilter` che consulta `RateLimiter` su `/v1/functions/**` e
chiude con 429 prima che il body venga letto. Poi togliere il controllo da
`InvocationService:217` e i due `onErrorResume(RateLimitException.class, ...)` a
`InvocationController.java:92,155`.

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

**Questo viene prima di tutto il resto.**

`experiments/e2e-memory-ab.sh` campiona `/actuator/prometheus` ogni 5 s e ne
estrae heap usato, heap max, pause GC e thread vivi
(`sample_prometheus_text_to_jsonl`, righe 96-155). **Non raccoglie né l'RSS del
container né il throttling CFS** — cioè le due metriche protagoniste di tutta
questa analisi. Senza di esse l'Esperimento B non può rispondere alla propria
domanda: non vedrebbe la variabile che sta cercando di ridurre.

Da aggiungere al campionatore:

| metrica | dove sta | nota |
|---|---|---|
| `nr_periods`, `nr_throttled`, `throttled_usec` | `/sys/fs/cgroup/cpu.stat` nel pod | **non** in `/actuator/prometheus`: serve una seconda sorgente |
| RSS del container | `memory.current` del cgroup, o cAdvisor | le corse Azure lo avevano come `container_memory_bytes@control-plane` |
| task pendenti per event loop | `reactor.netty` / Micrometer | è il numero che ha rivelato gli 863 |

Seconda lacuna, minore: il braccio è cablato. `e2e-memory-ab.sh` sa confrontare
solo `CONTROL_PLANE_EPOCH_MILLIS_ENABLED` acceso/spento (riga 200). Va
generalizzato a «un braccio è un insieme di variabili d'ambiente e build-arg»
invece che «un booleano». L'impalcatura riusabile — provisioning VM, deploy dei
due bracci, k6, campionamento durante la corsa, `comparison.md`/`comparison.json`
— resta intatta.

## Esperimento A — Quanta RAM serve adesso

**Domanda:** `limits.memory: 2Gi` è ancora giusto?

Non è un confronto: è una misura assoluta sul codice di oggi, sotto carico. La
prova locale post-fix dice **356,7 MiB con un limite di 1 GiB**. Se regge anche su
Azure, il chart riserva quattro volte la memoria che usa — memoria che il cluster
non può dare ad altri.

**Forma:** un braccio, `jvm-c2`, codice corrente, profilo di carico standard.
**Esito:** un valore di `limits.memory` giustificato da una misura.
**Costo:** il più basso dei tre. Da fare per primo dopo la strumentazione.

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

**Metriche decisive:** `nr_throttled/nr_periods`, p50/p95/p99, tasso di scarti,
task pendenti per loop, riavvii da liveness.

**Cosa decide:** se cambiare `platform/control-plane/Dockerfile:26`, e quale
valore di `reactor.netty.ioWorkerCount` mettere nel chart.

**Esito possibile e interessante:** se il vantaggio di C2 è crollato, la
conclusione della §23 era un artefatto della ritenzione illimitata. Sarebbe una
scoperta migliore di una conferma.

## Esperimento C — Il rifiuto costa meno se arriva prima

**Domanda:** il `WebFilter` della Parte I.1 produce una differenza misurabile?

**Forma:** due bracci — `main` contro il branch col filtro — a carico di
sovraccarico. Il riferimento è `azure-load3x`: **23,12% di scarti** a 2 core, cioè
un regime in cui quasi un quarto delle richieste percorre il cammino del rifiuto.

**Metriche decisive:** CPU per richiesta rifiutata, p99 delle richieste
**accettate** (è lì che deve vedersi il guadagno), throughput a parità di carico
offerto.

**Viene dopo:** richiede che il codice esista.

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

1. Strumentazione: RSS e throttling nel campionatore A/B
2. Esperimento A — memoria
3. Esperimento B — JIT × event loop
4. Codice: `WebFilter` (Parte I.1)
5. Esperimento C — costo del rifiuto
6. Applicare, o non applicare, la Parte I.2 e I.3 secondo B
