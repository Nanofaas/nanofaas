**Esito della campagna correttezza e prestazioni del control plane — settembre 2026**

Terzo documento di una trilogia: [la revisione](control-plane-review-2026-09-05.md)
trovò i problemi, [il piano](plans/2026-09-05-control-plane-correctness-and-performance.md)
decise cosa farne, questo registra **cosa è successo davvero** — comprese le cose
che il piano non prevedeva e le decisioni prese contro l'intuizione iniziale.

Non ripete ciò che la storia Git già racconta (chi ha cambiato cosa, quando).
Conserva il *perché*, che il codice non può portare: le alternative scartate, le
misure che hanno smentito una tesi ragionevole, e le domande rimaste aperte.

Rilasciato come **v0.21.0**.

---

## 1. Il difetto peggiore, e perché nessuno l'aveva visto

Un replay con chiave di idempotenza, dopo l'espulsione del payload per capacità,
**rieseguiva la funzione** invece di rispondere `410`. Cioè esattamente la
garanzia che la chiave esiste per dare.

Il meccanismo: `InvocationEnqueueSupport.admitIfNew` dispaccia **prima** e
pubblica la rivendicazione **dopo**. Se l'esecuzione si conclude dentro l'azione
di ammissione, `markTerminal` trova la chiave ancora `pending` e non fa nulla per
design; la rivendicazione pubblicata subito dopo resta non terminale per sempre.

Nel profilo senza moduli di coda non è una corsa ma un **comportamento
deterministico**: `admitLocally` dispaccia inline su una future già completa.

Perché era sfuggito: ogni test della ritenzione idempotente chiamava
`publishAdmission()` *prima* di concludere l'esecuzione — l'ordine inverso a
quello di produzione. Costruivano a mano la sequenza felice, e nessuno esercitava
quella reale.

> **Lezione riusabile.** Quando un test costruisce a mano l'ordine delle
> operazioni, verificare che sia l'ordine che la produzione produce. Qui bastava
> confrontare il test con `admitIfNew`.

## 2. Le metriche che alimentano un anello di controllo

M1 aveva ridefinito la conclusione end-to-end, ma quattro percorsi terminali non
la registravano affatto: timeout della coda sincrona, funzione rimossa mentre il
lavoro era accodato, e le due conclusioni dell'offload.

Non è una lacuna di copertura: quelle sono **precisamente le popolazioni di
sovraccarico**, e `SojournConcurrencyController` regola la concorrenza su quel
timer. Censurarle gli faceva vedere un sojourn ottimisticamente basso proprio
quando doveva reagire.

La correzione non aggiunge chiamate ai singoli percorsi: aggancia la conclusione
al **listener terminale dello store**, l'unico evento comune a ogni politica
terminale — e il modulo sync-queue, che possiede due di quei percorsi, non ha
nemmeno accesso al completion handler. Un percorso terminale futuro è coperto
senza che nessuno se ne ricordi.

## 3. Tre interventi di tuning su sei sono stati respinti

È l'esito più utile della fase 4, e il piano lo prescrive: se il beneficio non
emerge oltre la variabilità misurata, si documenta e si conserva il default.
Dettaglio completo e dati grezzi in
[`experiments/control-plane-tuning-2026-09/`](experiments/control-plane-tuning-2026-09/RISULTATI.md).

| Intervento | Esito | Il numero che ha deciso |
|---|---|---|
| Provider HTTP esplicito con lifecycle | adottato | non prestazionale: il provider globale non veniva **mai** chiuso |
| Timeout di acquisizione più corto | **respinto** | con la cancellazione del dispatcher, 45 s / 5 s / 1 s indistinguibili |
| Contatori di profondità per funzione | adottato | enqueue sotto scrape 8.739 → 305 ns |
| Rotazione a monitor unico | **respinto** | rotazione 7,5× più veloce, ma enqueue concorrente 2–9× più lento |
| Batch async 4/8/16 | **respinto** | indistinguibile da 2 su throughput e p99 |
| Budget degli esiti in byte | adottato | heap 1.279 → 6 MB con payload da 64 KB |
| Lock globale delle metriche fuori dal percorso caldo | adottato | 8 thread: 2.638 → 786 ns |

Due meritano una nota, perché il ragionamento vale più del verdetto.

**La rotazione a monitor unico** sembra un'ottimizzazione ovvia: 65 prese del
monitor invece di una. Accorparle rende la rotazione 7,5 volte più veloce **e
affama l'ammissione**, che non può più infilarsi fra due sezioni critiche corte.
La rotazione è manutenzione, l'ammissione è il percorso del chiamante. Il
javadoc ora lo spiega, perché non venga "ottimizzata" di nuovo.

**Il budget degli esiti** era documentato come «circa 12 MB» — vero per un esito
compatto da 116 byte, falso per un esito *leggibile* che trattiene il payload del
chiamante. Misurati, 20.000 esiti da 64 KB occupano **1,28 GB**, e al valore
predefinito di 100.000 sarebbero ~6 GB: la stessa forma del guasto del 2026-08-23
che lo store cita nel proprio javadoc, e che il tetto in numero non impedisce.

## 4. Il reperto che solo il carico reale poteva mostrare

Lo scenario `concurrency-cycle-container` falliva. Bisezione su sette revisioni:
il commit che ribalta l'esito è **P1**, «proxy container concorrente».

Non era una regressione. P1 ha reso concorrente `RoundRobinFunctionProxy`, e
**quella serializzazione era il degrado a cui il governor reagiva**: 857 → 3.387
req/s, p95 60,01 → 14,52 ms. Con otto richieste concorrenti la funzione risponde
in 1-3 ms senza degradare, quindi il governor tiene il massimo — correttamente —
e lo scenario legge quel comportamento corretto come fallimento.

> **Tre reviewer, 536 test unitari e l'intera matrice di validazione non
> l'avevano visto**, perché nessuno di quei controlli mette il control plane
> sotto carico reale. È l'argomento più forte a favore delle sezioni 7 e 8 del
> piano.

Il rovescio, altrettanto istruttivo: prima di P1 lo scenario **falliva le soglie
SLO** (p95 60 ms) mentre il governor regolava. Il governor regolava *perché* la
piattaforma era lenta.

## 5. Due errori di misura, con la stessa radice

Entrambi hanno prodotto numeri grandi, coerenti e **sbagliati**.

**T1.** Il primo banco misurava 62% di lavoro backend sprecato e p95 doppia del
budget. Ma non cancellava mai la richiesta, mentre `ExternalDispatcher` la avvolge
in `.timeout(functionTimeout)`, che cancella anche l'acquisizione pendente.
Rimisurato, il guadagno spariva del tutto — e il default precedente è rimasto.

**T3.** Il primo banco riusava la stessa istanza di `String` per tutti gli esiti:
l'heap ne teneva una sola, e i payload grandi sembravano gratis.

> **Lezione riusabile.** Prima di misurare un intervento, riprodurre ciò che il
> chiamante reale fa alla chiamata. Entrambi i run sono conservati in `raw/`,
> quello confutato incluso, perché il motivo per cui era sbagliato è la parte che
> serve di nuovo.

## 6. Il confronto baseline contro candidato

Tre ripetizioni per braccio, ordine alternato, stessa macchina (NVIDIA DGX Spark,
aarch64), stesso scenario, stesso corpus.

| metrica | v0.20.0 | v0.21.0 | delta |
|---|---|---|---|
| throughput | 350,1 req/s [346,3–366,7] | 849,9 [845,0–862,5] | **+142,7%** |
| p50 | 113,2 ms | 39,9 | −64,7% |
| p95 | 143,9 ms | 68,6 | −52,4% |
| p99 | 152,7 ms | 77,6 | −49,1% |

La dispersione non si sovrappone su nessuna metrica.

Due letture da non capovolgere. Il `fail` della baseline **non** dice che fosse
rotta: le soglie sono tarate sul candidato e la baseline le manca perché è più
lenta. E il governor più basso della baseline (2 contro 5) **non** è un governor
migliore: deve rinunciare a più concorrenza per reggere lo stesso carico.

## 7. Cosa resta aperto

- **Il consumo di RAM è cresciuto?** Non lo sappiamo. Le mediane puntano in alto
  ma la dispersione le divora, e `heap min` è un proxy rumoroso del live set.
  Registrato in [#207](https://github.com/miciav/nanofaas/issues/207) con il
  disegno del soak che lo deciderebbe.
- **512 MB non bastano** al control plane sotto il profilo di confronto:
  `OOMKilled` verificato sul pod. Non è nuovo — la campagna precedente mostrava
  già gli stessi riavvii, mai registrati come tali.
- **Righe della sezione 7 non eseguite**: provider Kubernetes, build native vera
  e smoke. Eseguibili, non eseguite.
- **Sezione 8 parziale**: mancano i profili ASYNC, il replay con chiavi, i payload
  piccoli, gli errori/retry e il soak lungo.
- **Esperimento B** del piano precedente (JIT ed event loop): non eseguito, 12
  celle con build di immagine nella VM. Le varianti `jvm-loop1` e `jvm-c2-loop1`
  che allora mancavano da NanoLab **ora esistono**: resta solo il costo.

## 8. Una nota sul metodo

Metà delle correzioni di questa campagna nasce da revisioni del codice, l'altra
metà da misure. Le due non sono intercambiabili, e si sono trovate a vicenda i
punti ciechi.

Le revisioni hanno trovato il difetto di idempotenza, la censura delle metriche e
il `PATCH` che non raggiungeva il proxy — tutte cose invisibili a un benchmark,
perché il sistema *sembra* funzionare.

Il carico reale ha trovato ciò che le revisioni non potevano: che un intervento
di prestazioni aveva reso obsoleto lo scenario che doveva verificarlo. Nessuna
lettura del codice lo avrebbe mostrato.

E in due casi su tre le misure hanno **smentito** l'intuizione di partenza. Una
manopola che sembrava ovvia non serviva a nulla; un'ottimizzazione evidente
peggiorava il percorso che conta. Vale la pena ricordarlo la prossima volta che
un cambiamento sembra troppo ovvio per essere misurato.
