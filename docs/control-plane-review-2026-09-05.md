**Analisi del control plane e dei moduli — 5 settembre 2026**

Revisione dei sorgenti nel workspace, base Git `42e49556158bc75a0646fb4cd1044d6c6f6cc454`. Nessuna modifica al codice applicativo. Le modifiche preesistenti agli esperimenti e a Helm non fanno parte di questa revisione.

Sono stati esaminati invocazione, completamento, idempotenza, store, dispatcher, rate limiting e i percorsi principali dei moduli async-queue, sync-queue, offload, autoscaler, concurrency-control, runtime-config, container-deployment-provider, k8s-deployment-provider e build-metadata. È una revisione mirata dei percorsi critici, non una certificazione completa di ogni classe.

GitNexus MCP non era disponibile. La CLI installata ed eseguita ha restituito `Repository not indexed` e `No indexed repositories found`, contrariamente al catalogo riportato in AGENTS.md. I riferimenti e le dipendenze sotto derivano dalla lettura dei sorgenti; non rappresentano un'analisi di impatto del grafo. Non sono stati modificati simboli né creati commit.

**Problemi prioritari**

1. **P1 — Replay asincrono di un'esecuzione archiviata genera NullPointerException.**

   In [InvocationService.java](../platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/service/InvocationService.java), righe 127–135, `invokeAsync` usa sempre `lookup.executionRecord()`. La factory restituisce invece `executionRecord=null` quando trova un `settledOutcome`. `terminalResponse(record)` chiama subito `record.snapshot()`. Un secondo `:enqueue` con la stessa chiave, dopo il completamento e prima della scadenza, può quindi rispondere con errore server invece del replay.

   **Correzione:** gestire `settledOutcome` prima di accedere al record, come già fa il coordinatore sincrono. Verificare il replay ASYNC di successi, errori e timeout dopo `settle`, preservando execution ID ed envelope. **Evidenza:** riprodotto il lookup archiviato e la dereferenziazione usata dal servizio; non eseguita una richiesta HTTP end-to-end.

2. **P1 — Il proxy container-local serializza le invocazioni.**

   [RoundRobinFunctionProxy.java](../platform/modules/container-deployment-provider/src/main/java/it/unimib/datai/nanofaas/modules/containerdeploymentprovider/RoundRobinFunctionProxy.java), righe 33–41 e 80: il server parte senza executor esplicito e il suo handler chiama `httpClient.send` in modo bloccante. Il round robin seleziona backend diversi ma non assicura dispatch concorrenti.

   **Evidenza sperimentale:** quattro richieste contemporanee, backend con executor a virtual thread e 200 ms di lavoro per richiesta: accesso diretto, concorrenza massima 4 e 284 ms totali; attraverso il proxy, concorrenza massima 1 e 827 ms totali. È una prova locale del collo di bottiglia, non una stima del miglioramento della piattaforma in produzione.

   **Correzione:** configurare un executor concorrente con gestione esplicita del ciclo di vita e limite alle richieste in volo, oppure usare un proxy non bloccante. Allineare anche il timeout fisso di 30 secondi alla policy della funzione: oggi una funzione con timeout maggiore può fallire anticipatamente in questo hop. Verificare parallelismo, backpressure, health durante una richiesta lenta e chiusura delle risorse.

3. **P1 — La scadenza di un record in dispatch può perdere definitivamente uno slot.**

   [ExecutionStore.java](../platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/execution/ExecutionStore.java), riga 78, applica `expireAfterWrite(maxLifetime)` ai record vivi senza un percorso di finalizzazione. [ExecutionCompletionHandler.java](../platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/service/ExecutionCompletionHandler.java), righe 218–224, ignora un completamento se il record non esiste più. Lo slot acquisito resta quindi occupato e la future non viene completata. Serve un dispatch che superi `maxLifetime`, per esempio con timeout configurati lunghi o una scadenza dello store troppo breve.

   **Evidenza:** con ticker controllato, completamento dopo la scadenza: zero rilasci dello slot e future ancora pendente.

   **Correzione:** rendere la proprietà dello slot indipendente dalla presenza del record nella cache, con rilascio esattamente una volta per tentativo. La scadenza deve finalizzare i waiter e gestire il dispatch sottostante; liberare semplicemente lo slot mentre il backend continua a lavorare può violare il limite reale di concorrenza. Verificare scadenza, completamento tardivo e callback duplicati insieme.

4. **P1 — Retry senza moduli di coda lascia l'esecuzione pendente.**

   [ExecutionCompletionHandler.java](../platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/service/ExecutionCompletionHandler.java), righe 319–347, riporta il record in QUEUED e tenta sempre `enqueuer.enqueue`, catturando soltanto `QueueFullException`. [NoOpInvocationEnqueuer.java](../platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/service/NoOpInvocationEnqueuer.java) lancia `UnsupportedOperationException`. Questo caso è raggiungibile nel dispatch diretto senza code, con un errore EXTERNAL/DEPLOYMENT e retry disponibili. L'eccezione nel callback `whenComplete` finisce nella future derivata, che non viene osservata.

   **Evidenza:** completamento fallito con enqueuer assente: eccezione, stato QUEUED e future pendente. Il client sincrono può attendere fino al timeout invece di ricevere una conclusione coerente.

   **Correzione:** introdurre una capacità di retry esplicita, separata da `enabled()` che rappresenta anche la disponibilità dell'API asincrona. Supportare retry diretti pianificati, o terminare con un errore definito se non supportati. Gestire tutte le eccezioni di scheduling garantendo la conclusione del record.

5. **P1 — Attivazione runtime della sync queue senza scheduler.**

   [SyncQueueConfiguration.java](../platform/modules/sync-queue/src/main/java/it/unimib/datai/nanofaas/modules/syncqueue/SyncQueueConfiguration.java), riga 83, crea lo scheduler solo se `sync-queue.enabled=true` all'avvio. L'estensione runtime accetta però modifiche a `enabled` e [MutableSyncQueueConfigSource.java](../platform/modules/sync-queue/src/main/java/it/unimib/datai/nanofaas/modules/syncqueue/MutableSyncQueueConfigSource.java) aggiorna il flag senza creare o avviare scheduler.

   **Scenario:** avvio con coda disabilitata, aggiornamento runtime a true, nuove invocazioni accodate senza consumer. **Correzione:** creare sempre lo scheduler quando il modulo è caricato e governarne il comportamento in modo coerente con lo stato runtime, oppure rendere il flag non modificabile a caldo. Verificare entrambe le transizioni con richieste in volo. **Evidenza:** percorso verificato staticamente, non avviato il contesto Spring per questa prova.

6. **P1 — La chiave idempotente può scadere prima dell'esito.**

   [IdempotencyStore.java](../platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/execution/IdempotencyStore.java), righe 50–71: durata pari al massimo tra TTL e maxLifetime, con minimo di due minuti, a partire dalla pubblicazione della chiave. Il TTL dell'esito parte invece dal completamento. Le due finestre non coincidono.

   **Evidenza:** TTL esiti 5 minuti, maxLifetime 2 minuti, completamento dopo 1 minuto. Al secondo 301 l'esito esiste ancora, ma `acquireOrGet` restituisce CLAIMED: la stessa chiave può avviare una seconda esecuzione.

   **Correzione:** mantenere il vincolo durante l'esecuzione e far partire la ritenzione idempotente dal completamento. Una durata conservativa deve coprire anche il tempo trascorso prima del completamento, non soltanto il massimo delle due durate. Considerare insieme l'eviction per `maxOutcomes`: oggi anche un esito espulso per capacità può causare una nuova esecuzione, attraverso `claimIfMatches`. Se la finestra di deduplicazione deve essere garantita, conservare un tombstone o rifiutare nuove ammissioni quando manca spazio, invece di dimenticare silenziosamente la chiave.

7. **P2 — L'offload perde gli header applicativi del chiamante.**

   [DefaultOffloadGateway.java](../platform/modules/offload/src/main/java/it/unimib/datai/nanofaas/modules/offload/DefaultOffloadGateway.java), righe 92–104, inoltra gli header applicativi nel corpo di `InvocationRequest`, ma trasferisce come header HTTP soltanto quelli di hop e tracing. Il control plane remoto, in [InvocationController.java](../platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/api/InvocationController.java), righe 59–70, sostituisce sempre `request.headers` con gli header HTTP ricevuti.

   **Scenario:** un handler legge `x-tenant` o un altro header applicativo; il valore presente nella chiamata locale scompare quando la stessa funzione viene offloaded. **Correzione:** inoltrare una selezione esplicita degli header applicativi sul trasporto remoto, escludendo quelli riservati e quelli relativi al singolo hop. Verificare un vero attraversamento di due control plane; il solo mock della risposta remota non rileva la perdita. **Evidenza:** statica.

**Ottimizzazioni e ulteriori difetti che influenzano le prestazioni**

| Priorità | Intervento | Motivazione e verifica |
| --- | --- | --- |
| Alta | Risvegliare SyncScheduler al rilascio di uno slot | `SyncScheduler` arriva a `Thread.sleep(50)` sotto saturazione. `SyncQueueService.onDispatchSlotReleased` fa cleanup, senza svegliarlo. Uno slot appena liberato può restare inutilizzato fino al risveglio. Usare notifiche con controllo del predicato e timeout di sicurezza; misurare p99 di attesa e utilizzo degli slot. |
| Alta | Conservare l'istante originale di ammissione attraverso i retry | `handleRetry` crea il task con un nuovo `Instant.now()`; il completamento calcola e2e e queue wait dal task dell'ultimo tentativo. La metrica e2e sottostima quindi il tempo totale e alimenta SOJOURN con un segnale parziale. Separare durata del tentativo e durata dell'invocazione, includendo gli esiti timeout. |
| Alta | Distinguere repliche desiderate e pronte nell'autoscaler | `InternalScaler` usa solo `getReadyReplicas` per decidere e poi sovrascrive il numero desiderato. Con 10 desiderate, 2 pronte e ratio 2 può ordinare 4 chiamandolo scale-up, riducendo in realtà il target 10. Tenere conto dello scaling già in corso e verificare rollout/startup lenti. |
| Media | Condividere uno snapshot delle repliche tra autoscaler e governor | Il provider Kubernetes fa una GET per `getReplicaStatus`; entrambi i loop richiedono lo stato per funzione. Valutare cache/watch condivisi con gestione della freschezza, e riconciliazioni lente isolate per funzione. Misurare richieste all'API e durata dei cicli. |
| Media | Ridurre lock, scansioni e diagnostica sul percorso delle code | La sync queue ha un deque globale con scansioni; l'async queue combina monitor di `FunctionQueueState` e lock di `ArrayBlockingQueue`. Provare contatori per funzione e batch async configurabile, oggi fissato a 2. Ottimizzare solo dopo profilo CPU/allocazioni, preservando fairness e rimozione delle funzioni. |
| Media | Configurare esplicitamente i limiti del pool HTTP | `HttpClientConfig` configura timeout e dimensione del corpo, ma non un budget esplicito per connessioni e attese nel pool. Allineare tale budget alla concorrenza ammessa, così da non creare una seconda coda poco visibile. Misurare attese di acquisizione prima di aumentare il pool. |
| Media | Limitare la memoria in byte, oltre al numero di esiti | `maximumSize` limita il numero di outcome, non il peso di output e header delle esecuzioni ASYNC o con chiave. La stima del solo outcome compatto non descrive questi payload. Valutare budget ponderati e admission globale, senza indebolire la deduplicazione. |

Un ulteriore difetto concorrente è in `RateLimiter.allow`: cambio finestra e reset del contatore sono due operazioni distinte. Un thread può pubblicare il nuovo secondo, altri incrementare il contatore, e il primo azzerare anche quegli incrementi. Usare uno stato atomico coerente per finestra e conteggio; verificare il confine del secondo con scheduling controllato. Non basta sostituire il contatore con un LongAdder per ottenere un limite rigoroso.

Non sono emersi difetti prioritari nel percorso di lettura di build-metadata esaminato. Per runtime-config il problema concreto identificato è l'interazione con il ciclo di vita della sync queue.

**Validazione e ordine di lavoro**

I sorgenti coinvolti nelle quattro prove di stato sono stati ricompilati con Java 25, usando le dipendenze già presenti in cache e le classi locali per i collaboratori. La prova del proxy ha usato il suo sorgente attuale e socket su localhost. Harness e output sono in `/tmp/nanofaas-audit-0905/` (`Audit.java`, `Audit.out`, `ProxyAudit.java`, `ProxyAudit.out`). Non è stata eseguita la suite Gradle completa, né un E2E Kubernetes/container della piattaforma. Le prove temporanee non sono test di regressione integrati nel repository.

Ordine suggerito: correggere replay, lifecycle degli slot/retry e idempotenza; rimuovere la serializzazione del proxy e il ritardo di risveglio sync; rendere affidabili metriche e scaling; infine intervenire su pool, batch e allocazioni.

Per confrontare le prestazioni usare throughput di invocazioni riuscite, p50/p95/p99 end-to-end, 429, timeout, tentativi per invocazione, utilizzo degli slot, CPU per successo, byte allocati per successo e pause GC. Confrontare a parità di CPU, memoria, payload, backend e carico offerto; includere traffico con chiavi idempotenti, retry e funzioni lente concorrenti a funzioni veloci. Aumentare la profondità delle code da sola può peggiorare la latenza senza aumentare il throughput utile.
