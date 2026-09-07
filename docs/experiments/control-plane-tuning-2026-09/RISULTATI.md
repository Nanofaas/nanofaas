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
