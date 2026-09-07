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
