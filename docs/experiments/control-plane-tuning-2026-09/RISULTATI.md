# Esiti della fase 4 (attività T1–T4)

Metodo, e cosa questa campagna non copre: vedi `README.md`.

---

## T1 — Pool HTTP

**Bersaglio.** Proprietà per connessioni, acquisizioni pendenti e timeout di
acquisizione; provider condiviso con lifecycle esplicito.

**Punto di partenza.** `HttpClientConfig` usava `HttpClient.create()`, cioè il
`ConnectionProvider` **globale** di Reactor Netty: nessun budget dichiarato,
nessun proprietario che lo chiuda, e un `pendingAcquireTimeout` di 45 s.

### Misura 1 — senza cancellazione (`raw/T1-acquire-wait-no-cancel.json`)

Pool 8, concorrenza 64, backend che tiene la connessione 300 ms, budget 1000 ms.
Stessa capacità nei tre bracci: l'unica variabile è il timeout di acquisizione.

| Braccio | p95 | oltre budget | lavoro backend sprecato |
|---|---|---|---|
| 45 s (default Reactor Netty) | 2409 ms | 240 | 240 / 384 (62%) |
| 5 s | 2409 ms | 240 | 240 / 384 (62%) |
| 1 s (= budget) | 1204 ms | 48 | 48 / 192 (25%) |

Lettura immediata: legare l'acquisizione al budget dimezza la p95 e taglia
dell'80% il lavoro che il backend fa per richieste che nessuno aspetta più.
Sembrava un'adozione ovvia.

### Misura 2 — con la cancellazione del dispatcher (`raw/T1-acquire-wait-with-cancel.json`)

**La misura 1 non riproduceva la produzione.** `ExternalDispatcher:102` avvolge
la chiamata in `.timeout(functionTimeout)`: l'operatore Reactor **cancella**
l'upstream, e con esso l'acquisizione pendente. Il banco non cancellava mai.

Rifatta la misura riproducendo quel comportamento:

| Braccio | p95 | oltre budget | lavoro backend sprecato |
|---|---|---|---|
| 45 s | 1000 ms | 0 | 48 / 192 (25%) |
| 5 s | 1000 ms | 0 | 48 / 192 (25%) |
| 1 s | 1000 ms | 0 | 48 / 192 (25%) |

I tre bracci sono **indistinguibili**. La cancellazione fa già tutto il lavoro
che il timeout di acquisizione avrebbe fatto.

### Decisione

- **Tuning del timeout di acquisizione: NON adottato.** Il beneficio non emerge
  affatto una volta riprodotta la produzione, quindi vale §6: si documenta
  l'esito e si conserva il default precedente (45 s, quello di Reactor Netty).
- **Meccanismo: adottato**, ma per ragioni che non sono di prestazioni e che non
  vanno vendute come tali:
  - il provider globale non viene **mai** chiuso; ora è un bean con
    `destroyMethod`, quindi le connessioni non sopravvivono al context;
  - connessioni e coda di acquisizione diventano un budget **dichiarato e
    tunabile** (`nanofaas.http-client.*`) invece che un default ereditato,
    che è la parte «senza crescita incontrollata di connessioni e memoria»
    dell'accettazione;
  - la proprietà del timeout resta perché la cancellazione che la rende
    irrilevante appartiene al *chiamante*: un futuro percorso di dispatch che si
    dimenticasse di limitare la propria chiamata erediterebbe i 45 s.

### Limite di questa misura

In-JVM, macchina di sviluppo, un solo backend. Non copre molte destinazioni né
la crescita di connessioni/memoria in aggregato, che è l'altra metà
dell'accettazione di T1 e richiede la matrice §8 su NanoLab.

### Lezione di metodo

Il primo banco produceva numeri grandi, coerenti e sbagliati. A renderli
sbagliati non è stato un errore di misura ma un modello mancante del chiamante.
Vale la pena ripeterlo per T2–T4: **prima di misurare un intervento, riprodurre
ciò che il chiamante reale fa alla chiamata.**

---

## T2 — Code: monitor, scansioni, batch async

**Bersaglio.** Profilare monitor e scansioni; contatori per funzione ai punti di
mutazione della sync queue; batch async configurabile, confrontando 2/4/8/16;
ridurre i lock sovrapposti **solo se** il profilo ne dimostra il costo.

**Forma.** Una funzione molto attiva insieme a 50 poco attive, profondità 200 —
la forma che l'accettazione richiede.

### T2a — Scansione per funzione → contatori. **ADOTTATO**

`queuedItems(functionName)` era una scansione O(depth) sotto il monitor della
coda, e `SyncQueueWorkloadMetricsSource` la chiama **una volta per funzione** a
ogni scrape: O(funzioni × profondità) con il monitor preso ogni volta.

| Metrica | Prima | Dopo |
|---|---|---|
| scrape completo (51 funzioni) | 13.111 ns | **312 ns** (42×) |
| enqueue **sotto scrape** | 8.739 ns | **305 ns** (28×) |
| enqueue non conteso | 288 ns | 295 ns (invariato) |

L'ammissione sotto scrape era **30 volte** più lenta di quella non contesa;
adesso le due coincidono. Le scritture del contatore restano dentro i blocchi
`synchronized (queue)` già esistenti, quindi la relazione fra chiusura, offer e
contatori resta atomica; solo le letture escono dal monitor.

*Limite:* lo scraper del banco gira in un ciclo stretto, non a 1 Hz come in
produzione. Il 30× è un limite superiore sotto scraping patologico, non lo stato
stazionario. La direzione però non dipende dalla frequenza: un percorso di
ammissione non deve aspettare una lettura di metriche.

### T2b — Rotazione a monitor unico. **NON ADOTTATO**

`rotateReadyScanWindow` prende il monitor 1 + fino a 64 volte. Accorpare tutto
in una sola sezione critica sembrava ovvio, e in isolamento lo è:

| Metrica | Per-elemento (attuale) | Monitor unico |
|---|---|---|
| rotazione isolata | 1.108 ns | **148 ns** (7,5×) |
| enqueue concorrente | **2.274 ns** | 4.262 – 20.668 ns |

Ma l'enqueue concorrente peggiora di 2–9 volte: tenendo il monitor per tutti i
64 elementi, l'ammissione non può più infilarsi fra due sezioni critiche corte e
aspetta l'intero batch. La rotazione è manutenzione, l'ammissione è il percorso
del chiamante: le prese brevi sono la scelta giusta. §8 vieta un peggioramento
>5% sugli scenari di controllo, e questo è molto peggio. Ripristinato, con il
motivo scritto nel javadoc perché non venga "ottimizzato" di nuovo.

### T2c — Batch async 2 / 4 / 8 / 16. **NON ADOTTATO**

Dispatch simulato a 50 µs (con dispatch istantaneo i bracci sono
indistinguibili per costruzione, e la prima misura lo era).

| Batch | wall | attesa p99 funzione poco attiva |
|---|---|---|
| 2 | 114 ms | 5 µs |
| 4 | 114 ms | 6 µs |
| 8 | 113 ms | 5 µs |
| 16 | 113 ms | 6 µs |

Indistinguibili. Il costo di un giro di ciclo è trascurabile rispetto al
dispatch, quindi ampliare il batch non ammortizza nulla di misurabile — e
nemmeno peggiora l'equità, come si sarebbe temuto. **Default 2 conservato.**

Il parametro resta iniettabile (package-private): è ciò che ha reso possibile il
confronto, il banco in `bench/` lo usa, e una futura ri-misura non deve
ricominciare da capo.

### Da portare avanti

`POLL_READY_MATCHING_SCAN_LIMIT` è 64 e non è configurabile. Non l'ho toccato:
non ho una misura che dica che 64 sia il valore sbagliato, e cambiarlo senza
misura sarebbe esattamente ciò che questa fase esiste per evitare.

---

## T3 — Memoria: budget ponderato dei payload

**Bersaglio.** Un budget in byte oltre al tetto in numero; stimare il peso una
volta sola, senza riserializzare il payload a ogni accesso.

### Il rischio, misurato (`raw/T3-memory-before.json`)

`application.yml` documentava il costo così: *«un esito compatto misura 116 byte,
quindi questo tetto costa circa 12 MB»* con `max-outcomes: 100000`. È vero per un
esito compatto. Ma A5 trattiene il payload per gli esiti **leggibili** (ASYNC o
con chiave di idempotenza), e lì il payload è quello del chiamante.

20.000 esiti leggibili, heap trattenuto dopo GC:

| payload | heap | per esito |
|---|---|---|
| 128 B | 8 MB | 427 B |
| 4 KB | 86 MB | 4.518 B |
| 64 KB | **1.279 MB** | 67.098 B |

Al valore predefinito di 100.000 sarebbero ~6 GB. È la stessa forma del guasto
del 2026-08-23 che lo store cita nel proprio javadoc (1,05 GB, 50,6% del tempo in
GC): il tetto in numero non lo impedisce.

*Nota di metodo:* la prima versione di questa misura usava la **stessa** istanza
di `String` per tutti gli esiti, e l'heap ne tratteneva una sola — i payload
grandi sembravano gratis. Stesso genere di errore di T1: il banco non riproduceva
ciò che fa il chiamante reale.

### Dopo (`raw/T3-memory-after.json`). **ADOTTATO**

`maximumWeight` + weigher, budget predefinito `max-outcomes × 116 byte`.

| payload | prima | dopo | trattenuti |
|---|---|---|---|
| 128 B | 8 MB | 5 MB | 10.357 |
| 4 KB | 86 MB | 3 MB | 553 |
| 64 KB | **1.279 MB** | **6 MB** | 35 |

L'heap è piatto al variare della dimensione del payload: è il punto. Il costo è
che con payload grandi si trattengono meno esiti — chi vuole ritenzione più lunga
alza `max-outcome-bytes`.

### Costo del weigher

| forma | ns per chiamata |
|---|---|
| stringa 128 B | 10 |
| stringa 4 KB | 2 |
| stringa 64 KB | 2 |
| mappa, 64 voci | 105 |

Una stringa da 64 KB costa quanto una da 128 B, perché la stima è `length()`,
O(1): è esattamente il requisito «senza riserializzare il payload». La traversata
di mappe e liste è limitata in ampiezza (256) e profondità (4), così un payload
annidato in modo patologico costa come gli altri.

### Effetto collaterale sui test, e cosa insegna

Tre test esprimevano lo sfratto in *numero* (`max-outcomes: 1` = «uno slot»). Con
un budget ponderato Caffeine può **rifiutare il nuovo** invece di sfrattare
l'incumbent, quindi quei test dipendevano dalla scelta della vittima, che è
affare di Caffeine. Riscritti con un budget in byte esplicito che rende la
scena deterministica. La garanzia di A5 non cambia in nessuno dei due casi: o
l'esito c'è e il replay lo serve, o non c'è e il replay risponde 410 — la
funzione non rigira mai.

### Non fatto, e perché

L'accettazione di T3 nomina anche un *«budget di admission per richieste vive»*.
Non l'ho fatto: le richieste vive stanno in `inFlight`, già limitato da
`maxLifetime` e dagli slot di concorrenza, e non ho una misura che dica che
quella struttura sia il problema. Aggiungerne uno senza misura sarebbe
esattamente ciò che questa fase esiste per evitare. Il soak lungo che
attraverserebbe le finestre di ritenzione richiede §8 su NanoLab.

---

## T4 — Diagnostica: costo dei meter sul percorso caldo

**Bersaglio.** Profilare registrazione e lettura dei meter sul percorso caldo;
spostare fuori dai lock ciò che non serve all'atomicità. L'accettazione è
esplicita: **stessa osservabilità nei due bracci**, e non attribuire un guadagno
all'eliminazione di metriche che guidano governor o scaler.

Qui non è stata rimossa nessuna metrica: stessi meter, stessi valori.

### Il difetto

`Metrics.metersOrNull` prendeva un `synchronized` su un monitor **globale**,
condiviso da tutte le funzioni, a ogni chiamata. Un'invocazione lo attraversa sei
volte: `dispatch`, l'esito, e la `timers(fn)` che precede i tre campioni di
durata. Il lock serve a rendere atomica la rimozione rispetto alla registrazione
— cosa che riguarda il percorso lento, non quello comune.

### Misura (`raw/T4-metrics-before.json`, `raw/T4-metrics-after.json`)

ns per operazione del percorso caldo, 32 funzioni:

| thread | prima | dopo | |
|---|---|---|---|
| 1 | 223 | 202 | −9% |
| 2 | 885 | 268 | **−70%** |
| 4 | 2.058 | 928 | −55% |
| 8 | 2.638 | 786 | **−70%** |

Prima il costo *per operazione* cresceva con il numero di thread — 12× fra 1 e 8.
Non è un costo, è una serializzazione: il lavoro non aumenta, aumenta l'attesa.

### Decisione. **ADOTTATO**

Il caso comune (funzione registrata e viva) è una `ConcurrentHashMap.get` senza
lock. Il lock resta sul percorso lento: prima registrazione e corsa con
`removeFunction`. L'invariante che protegge — una funzione rimossa non
ri-registra i suoi meter — vale ancora, perché la registrazione avviene solo lì
dentro, ed è già coperta da
`MetricsTest.removedFunction_doesNotRecreateMetersUntilRegisteredAgain`.

Prezzo accettato e documentato: una lettura veloce che afferra i meter un istante
prima della rimozione incrementa un contatore che sta per essere deregistrato, e
quel campione si perde. Serializzare ogni invocazione della piattaforma per non
perderlo sarebbe un pessimo scambio.

La crescita residua (202 → 786 ns) è nei meter di Micrometer stessi, che
contendono quando più thread toccano la stessa funzione: è inerente allo
strumento, non al nostro lock.

---

## E2E (§7): `concurrency-cycle-container` fallisce da P1 in poi — ed è un miglioramento

Eseguibile solo dopo aver corretto NanoLab (vedi README: il load-test container
richiedeva un environment locale, e l'executor locale rifiutava la `remote_dir`
che lo step k6 chiedeva sempre).

### Il sintomo

```
'word-stats-java' never gave concurrency back under load:
floor while busy was 8, peak while idle was 8
```

Il verificatore classifica una lettura come *carica* quando `in_flight >=
effective`, e pretende `loaded_floor < idle_peak`.

### Bisezione

| Revisione | Esito | `busy floor` |
|---|---|---|
| `e35405ee` pre-branch | ✅ 22/0 | — |
| `06095a4d` A7 | ✅ | **4** (rerun: 5) |
| `711d619f` **P1** | ❌ | 8 |
| `72fee224` P2 | ❌ | 8 |
| `36d48600` M1 | ❌ | 8 |
| `cb22a4b0` M3 | ❌ | 8 |
| tip post-remediation | ❌ | 8 |

Il commit che ribalta l'esito è **P1**, «Make the container proxy concurrent and
bounded». Le metriche di M1 non c'entrano: erano il primo sospetto e la
bisezione le ha escluse.

### Perché non è una regressione (`raw/e2e-k6-*.json`)

| | A7 (pre-P1) | P1 |
|---|---|---|
| richieste | 334.270 | 1.320.957 |
| throughput | 857 req/s | **3.387 req/s** (+295%) |
| p50 | 44,00 ms | **9,69 ms** |
| p95 | 60,01 ms | **14,52 ms** |
| p99 | 76,29 ms | **35,83 ms** |

Quattro volte il throughput a un quarto della latenza. `RoundRobinFunctionProxy`
serializzava le invocazioni verso le repliche; P1 l'ha reso concorrente, ed era
**quella serializzazione** il collo di bottiglia che degradava il tempo di
servizio e dava al governor qualcosa a cui reagire.

Con 8 richieste simultanee la funzione ora risponde in 1–3 ms senza degradare.
Il controller ADAPTIVE osserva il degrado del *tempo di servizio*: non trovandone,
tiene il limite al massimo — che è la decisione giusta. L'eccedenza si accumula
nella sync queue, e l'attesa in coda non entra in `function_latency_ms`.

Da notare anche il rovescio: A7 falliva le soglie k6 (p95 60 ms) in uno dei due
run, mentre da P1 in poi l'SLO passa comodamente. Il governor regolava *perché*
la piattaforma era lenta.

### Decisione: nessuna modifica alla piattaforma

Lo scenario asserisce una proprietà del governor la cui premessa era la lentezza
del proxy. La premessa è caduta con il collo di bottiglia, di proposito.

La ritaratura appartiene a NanoLab e **non è stata fatta qui**: cambiare il
profilo di carico cambia cosa misura ogni confronto della campagna, inclusa la
baseline `overload-path-2026-09`. Le opzioni sono due — carico più pesante (o
funzione più lenta) perché lo scenario torni a saturare, oppure accettare che non
verifichi più il governor. È una scelta di copertura, non di correttezza.

**Finché non è ritarato, quello scenario non verifica più il governor su questa
macchina.** È una perdita di copertura reale, e va detta.

---

## §8 — Baseline contro candidato

Prima esecuzione del protocollo di §8 su questo branch. Driver:
`compare-baseline-candidate.sh`; dati grezzi per cella in
`raw/compare-concurrency-cycle-container/`.

**Bracci.** baseline `e35405ee` (il punto di rilascio v0.20.0, pre-branch)
contro candidato = tip del branch.

**Protocollo.** Tre ripetizioni per braccio, **ordine alternato** dentro la
ripetizione (A,B,A,B,A,B) e non due blocchi: se la macchina deriva a metà
campagna, deriva per entrambi i bracci invece di penalizzare il secondo. Stessa
macchina (la DGX Spark del README), stesso scenario, stesso harness, stesso
corpus — verificato che `performance-medium.json` esista anche alla baseline,
altrimenti i due bracci non avrebbero ricevuto lo stesso carico.

Scenario: `concurrency-cycle-container` nella versione ritarata (payload medium,
2 core, budget scalato). Attraversa proxy container, sync queue, governor di
concorrenza e le metriche di M1.

### Risultati (mediana, [min–max] su 3 ripetizioni)

| metrica | baseline `e35405ee` | candidato | delta |
|---|---|---|---|
| throughput | 350,1 req/s [346,3–366,7] | **849,9** [845,0–862,5] | **+142,7%** |
| p50 | 113,2 ms [107,2–113,7] | **39,9** [39,1–39,9] | **−64,7%** |
| p95 | 143,9 ms [142,1–145,2] | **68,6** [68,4–68,8] | **−52,4%** |
| p99 | 152,7 ms [152,5–154,1] | **77,6** [77,5–78,4] | **−49,1%** |
| richieste servite | 135–143k | 330–336k | |
| governor busy floor | 2, 2, 3 | 5, 5, 5 | |
| asserzioni scenario | fallite (3/3) | superate (3/3) | |

**La dispersione non si sovrappone su nessuna metrica**: il massimo del
candidato è sempre lontano dal minimo della baseline. Con tre ripetizioni per
braccio è quanto di più netto si possa chiedere a questa scala.

### Contro le soglie di §8

- *«regressioni funzionali ammesse zero»* — il candidato supera tutte le
  asserzioni dello scenario in 3 run su 3; la baseline in nessuno.
- *«almeno 10% sulla metrica bersaglio, nessun peggioramento oltre il 5% di
  throughput utile o p99»* — +142,7% di throughput e −49,1% di p99. Nessuna
  metrica peggiora.

### Come leggerlo, e come non leggerlo

**Il `fail` della baseline non significa «la baseline era rotta».** Le soglie di
questo scenario sono tarate sul candidato: la baseline le manca perché è più
lenta, non perché sbagli qualcosa. Il confronto valido sono i numeri, non i
verdetti — ed è il motivo per cui il driver archivia una cella che fallisce le
asserzioni invece di fermare la coda, come faceva nella sua prima versione.

**Il governor più basso della baseline non è un governor migliore.** Scende a 2
contro i 5 del candidato perché deve rinunciare a molta più concorrenza per
reggere lo stesso carico. Il limite più alto del candidato è il segno che
sostiene più lavoro in parallelo.

**Questo è il delta dell'INTERO branch**, non di un singolo intervento — che è
ciò che «baseline contro candidato» significa per una decisione di rilascio, ma
non soddisfa il *«variare un solo intervento per volta»* di §8. L'attribuzione
per intervento viene dalla bisezione della sezione precedente: P1 è la causa
dominante, misurata isolatamente a +295% di throughput.

### Limiti

Una sola macchina, un solo scenario, tre ripetizioni. Non copre i profili ASYNC,
il replay con chiavi, i payload piccoli, gli errori/retry, né il soak che
attraversa le finestre di ritenzione — tutte righe che §8 elenca e che restano
da fare.

---

## Esperimento A — quanta RAM serve adesso

Ripresa dell'esperimento A del piano precedente
(`docs/plans/2026-09-04-overload-path-fixes.md`), stavolta come confronto
baseline-contro-candidato sulla **stessa macchina**. Dati in `raw/expA-memory/`.

Scenari `runtime-comparison-mem{512,1024}`, variante `jvm-c2`, 3 ripetizioni per
cella, Multipass + k3s sulla DGX Spark. CPU fissata a 2 core dallo scenario per
isolare l'asse memoria dal ginocchio CPU.

### Risultato principale: 512 MB non bastano, e non è nuovo

A 512 MB il control plane viene **`OOMKilled`** — verificato direttamente sul
pod: `Last State: Terminated / Reason: OOMKilled / Exit Code: 137`,
`Restart Count: 6`, limite `512Mi`. Non un'inferenza dai tempi di uptime.

| | fallimenti k6 | uptime a fine run |
|---|---|---|
| baseline `e35405ee` @512 | 3,0% · 10,6% · 42,6% | 169 / 140 / 223 s |
| candidato @512 | 17,9% · 35,4% · 25,0% | 74 / 50 / 103 s |
| baseline @1024 | 0% · 0% · 0% | 508 / 1023 / 1543 s |
| candidato @1024 | 2,9% · 0% · 0% | 515 / 1040 / 1565 s |

**Entrambe le revisioni muoiono a 512 MB**, con intervalli di fallimento
sovrapposti. Non è una regressione del branch: la campagna precedente mostrava
già uptime di 48–170 s a quel tetto, cioè gli stessi riavvii, mai registrati
come tali.

### Il throughput identico non significa "va bene"

Le due celle riportano 435,1 req/s entrambe. È un profilo **open-loop**: quella è
la frequenza *offerta* dal generatore, non quella servita. Il segnale vero è il
tasso di fallimento.

### Una trappola nei dati, segnalata perché è facile caderci

`function_success_total.delta` dà −91% per mem512, che è **falso**: il contatore
si azzera a ogni riavvio del pod. Con throughput identico e zero errori registrati
quel numero era incompatibile, e il controllo incrociato con `http_reqs` lo ha
smascherato. Ogni metrica cumulativa del control plane è inaffidabile su un run
in cui il processo riparte.

### Il consumo di RAM è cresciuto? Non lo sappiamo

A 1024 MB, dove nessuno dei due bracci muore:

| metrica | baseline | candidato | delta mediana |
|---|---|---|---|
| heap min MB | 49,9 [38,6–73,2] | 177,5 [42,5–345,0] | +256% |
| heap finale MB | 249 [135–457] | 441 [287–582] | +77% |
| heap max MB | 466 [266–486] | 488 [483–641] | +5% |
| CPU max | 0,38 | 0,36 | −6% |

Le mediane puntano in alto, ma **la dispersione le divora**: l'intervallo del
candidato contiene interamente quello della baseline. E `heap min` è il minimo
dei campioni scrapati, quindi un proxy rumoroso del live set.

Indizio contrario: se il candidato avesse bisogno di più memoria, a 512 MB
dovrebbe fallire distintamente prima della baseline. Non lo fa.

**Registrato come domanda aperta in
[#207](https://github.com/miciav/nanofaas/issues/207)**, con il disegno del soak
che la risolverebbe: ~90 minuti per braccio per attraversare più volte le
finestre di ritenzione (`ttl 5m`, `max-lifetime 30m`), campionando l'heap **dopo
GC forzata** invece del minimo scrapato, più i contatori dello store e l'RSS del
container.

### Esperimento B: non eseguito

`runtime-comparison-cpu1` con 4 varianti (`jvm`, `jvm-c2`, `jvm-loop1`,
`jvm-c2-loop1`) × 3 ripetizioni = 12 celle, ognuna con build di immagine nella
VM. Le varianti `*-loop1` che la vecchia `queue.tsv` segnalava come assenti da
NanoLab **ora esistono** (`control_plane_variants.py`), quindi il blocco è
rimosso: resta solo il costo.
