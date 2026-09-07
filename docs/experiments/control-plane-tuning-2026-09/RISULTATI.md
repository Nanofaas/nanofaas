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
