# Piano di correzione e ottimizzazione del control plane

**Data:** 2026-09-05. **Stato:** proposto, implementazione non avviata da questo piano.

Riferimento: [analisi del control plane e dei moduli](../control-plane-review-2026-09-05.md). Il piano copre tutti i problemi e i suggerimenti del report, compresi rate limiter, metriche dei retry e autoscaling. Ogni attività termina con codice verificato, documentazione pertinente e risultati riproducibili; le ottimizzazioni cambiano i default solo quando il beneficio è misurato.

Il [piano sul sovraccarico del 4 settembre](2026-09-04-overload-path-fixes.md) documenta già interventi su WebFilter, JIT ed event loop. Verificarne lo stato nel checkout usato per la baseline e mantenerne fisse le impostazioni durante i confronti di questo piano. L'eventuale esperimento Azure C resta nel piano originale: non è un prerequisito per correggere questi bug.

## 1. Risultati attesi e vincoli

- Replay idempotenti coerenti per chiamate sincrone e asincrone, prima e dopo l'archiviazione.
- Ogni invocazione ammessa raggiunge un esito; timeout, errori di scheduling e callback tardivi non lasciano risorse locali irrecuperabili.
- Uno slot viene rilasciato al massimo una volta per tentativo e alla generazione della funzione che lo ha acquisito.
- Retry configurabili anche senza moduli di coda; default invariato a tre retry oltre al primo tentativo.
- Attivazione/disattivazione runtime della sync queue senza lavoro abbandonato.
- Proxy container concorrente, code e memoria limitate, health utilizzabile sotto carico.
- Metriche attendibili per governare concorrenza e repliche.
- Più invocazioni riuscite entro lo SLO a parità di risorse, senza ottenere throughput soltanto aumentando timeout o code.

Restano i vincoli del progetto: un control plane con stato in memoria, scheduler dedicato, Java 25, supporto native image e nessuna autenticazione aggiunta. Il piano non introduce persistenza distribuita o garanzie di deduplicazione attraverso un riavvio.

## 2. Preparazione e baseline — attività A0

**Consegna:** baseline versionata e riproduzioni dei difetti.

1. Registrare commit, stato del workspace, moduli selezionati, configurazione JVM/native, CPU, memoria, payload e backend. Non includere modifiche estranee nelle patch.
2. Rendere disponibile GitNexus e creare l'indice locale mancante. Se esiste già un indice, controllarne la freschezza e preservare eventuali embeddings. Prima di modificare ogni simbolo eseguire l'impact upstream e riportare chiamanti diretti, processi coinvolti e rischio; segnalare HIGH/CRITICAL prima degli edit. Per estrazioni o spostamenti aggiungere context, per rinomine usare rename con anteprima.
3. Trasferire le riproduzioni utili di `/tmp/nanofaas-audit-0905/` in test di regressione nei moduli proprietari. Se i file temporanei non esistono più, ricostruirle dagli scenari del report. Ogni test di bug deve fallire sulla base e passare dopo il fix.
4. Confermare in test i rilievi ancora statici: attivazione runtime della coda, perdita degli header offload, race del rate limiter, metriche dei retry e scaling durante startup.
5. Raccogliere una baseline locale breve prima dei fix. Dopo le correzioni funzionali raccogliere una seconda baseline: sarà il riferimento per il tuning, perché correggere retry e contabilizzazione può cambiare il lavoro effettivamente svolto.

Non serve completare la matrice di performance per iniziare i fix. Evitare test di concorrenza basati su sleep fragili: usare ticker, clock controllabili, latch e completamenti pilotati. I tempi wall-clock restano nei benchmark, non nelle asserzioni funzionali della CI.

## 3. Correzioni funzionali

### A1 — Replay asincrono archiviato

**Ambito:** `InvocationService`, `InvocationExecutionFactory`, `InvocationResponseMapper`, test del servizio e del controller. **Dipendenza:** A0.

Gestire `settledOutcome` in `invokeAsync` prima di dereferenziare il record, riusando il mapping già disponibile per gli esiti archiviati. Conservare la semantica HTTP esistente di `:enqueue`, lo stesso execution ID, il risultato e l'envelope; non reinserire un task per un replay.

**Accettazione:** success, error e timeout già archiviati vengono restituiti senza NPE. Un replay concorrente alla transizione live→settled non genera una seconda esecuzione. Il contatore di ammissione non aumenta per il replay e il dispatch avviene una sola volta.

### A2 — Proprietà dello slot e scadenza delle esecuzioni

**Ambito:** `ExecutionStore`, `ExecutionRecord`, `ExecutionCompletionHandler`, scheduler, enqueuer e registro di capacità in `workload-metrics`. **Dipendenza:** A0. È l'intervento con maggiore accoppiamento; mantenerlo in una PR dedicata.

Introdurre un oggetto leggero che rappresenti lo slot acquisito per uno specifico tentativo e una specifica generazione della funzione. Il callback del dispatch conserva tale riferimento e può effettuare un rilascio idempotente anche se lo store non contiene più il record. I percorsi che non acquisiscono slot, come l'offload, non devono rilasciarne.

Separare tre eventi: fine dell'attesa di un chiamante, fine del tentativo di dispatch locale e scadenza amministrativa dell'invocazione. Un timeout del singolo waiter non cancella la future condivisa da altri chiamanti idempotenti. La scadenza amministrativa conclude i waiter ancora pendenti, archivia l'esito previsto e avvia la cancellazione/chiusura del dispatch locale. Prevedere una scadenza attiva, non dipendente soltanto dall'attività opportunistica della cache.

La cancellazione HTTP non prova che il backend abbia smesso di eseguire la funzione: documentare gli slot come limite dei dispatch locali, senza promettere un limite assoluto al lavoro remoto dopo disconnessione. Per un eventuale limite remoto rigoroso serve cooperazione del runtime, fuori dal fix minimo. Non trattare la sola eviction del record come una prova di fine del dispatch.

**Accettazione:** dopo conclusione o scadenza dei tentativi locali non rimangono slot trattenuti né future pendenti; completamenti duplicati non rendono negativo il conteggio. Coprire scadenza prima/dopo il completamento, task scaduto ancora in coda, callback vecchio durante retry, rimozione e nuova registrazione dello stesso nome, errori di submission e shutdown. Verificare che un callback della vecchia generazione non liberi uno slot della nuova.

### A3 — Retry utilizzabili senza coda

**Ambito:** completamento, contratto `InvocationEnqueuer` e implementazioni no-op/async/sync. **Dipendenza:** A2.

Separare la capacità di pianificare un retry dalla disponibilità dell'endpoint asincrono: `enabled()` non deve rappresentare entrambe. Conservare i retry sulle code esistenti; nel profilo senza coda pianificare il prossimo tentativo con un executor gestito e risorse limitate, evitando ricorsione quando la future completa subito. Le eccezioni nella pianificazione devono produrre un esito terminale osservabile.

Mantenere `maxRetries` e la policy attuale degli errori ripetibili in questa PR. Un eventuale backoff con jitter richiede una misura separata e una definizione esplicita del budget temporale; non introdurlo implicitamente insieme al fix.

**Accettazione:** errore→successo e fallimento definitivo con 0, 1 e 3 retry, in tutti e tre i profili di coda. Con 3 retry si hanno al massimo 4 tentativi. Coda piena, executor in shutdown ed eccezioni di enqueue terminano la richiesta senza stato QUEUED orfano. L'API `:enqueue` resta indisponibile dove non prevista.

### A4 — Lifecycle runtime della sync queue

**Ambito:** configurazione e scheduler sync, sorgente mutabile, estensione runtime-config. **Dipendenza:** A0; integrazione con A2/A3 prima del rilascio.

Creare lo scheduler quando il modulo è caricato, anche se l'ammissione in coda è inizialmente disabilitata. Tenere il worker dormiente senza polling continuo quando non ha lavoro. Il flag runtime decide il percorso delle nuove invocazioni; alla disattivazione il lavoro già ammesso continua a essere drenato. I retry di quel lavoro seguono una policy esplicita e non vengono abbandonati.

Pubblicare le impostazioni runtime correlate tramite un unico snapshot immutabile, così che admission e scheduler non osservino una combinazione parziale di valori durante apply/restore. Conservare revisioni e rollback del servizio runtime-config.

**Accettazione:** avvio false→true con dispatch riuscito, true→false con coda e dispatch in corso, riattivazione, update rifiutato e rollback. Verificare un contesto Spring reale, non soltanto la mutazione del bean di configurazione.

### A5 — Ritenzione idempotente e recupero dell'esito

**Ambito:** store delle chiavi e delle esecuzioni, factory, finalizzazione, API e documentazione. **Dipendenze:** A1 e A2.

Vincolare la chiave all'esecuzione per tutta la sua vita e far partire la ritenzione terminale dal completamento. La pubblicazione della chiave, l'archiviazione dell'esito e la transizione al vincolo terminale devono impedire finestre in cui la stessa chiave sia nuovamente acquisibile. La cleanup delle chiavi pending deve essere legata alla conclusione o all'abbandono dell'ammissione.

Separare la garanzia di deduplicazione dalla conservazione del payload. Decisione proposta: se l'esito viene espulso per capacità prima della fine della finestra, conservare un tombstone leggero con execution ID e scadenza. Il replay non riesegue la funzione e restituisce un errore esplicito di esito non più disponibile, proposto come HTTP 410. Questa è una variazione di contratto da implementare con OpenAPI, mapping e test, non un comportamento già presente.

Limitare anche il numero di vincoli/tombstone: a budget esaurito rifiutare nuove ammissioni con chiave prima del dispatch, mantenendo servibili i replay esistenti. Il successivo budget in byte non deve espellere silenziosamente la protezione di deduplicazione.

**Accettazione:** test con tempo controllato per esecuzione lunga più TTL terminale, retry interni, eviction per capacità, budget chiavi esaurito e richieste concorrenti. Un replay entro la finestra non produce mai un nuovo dispatch, anche quando il payload non è più recuperabile. Dopo la scadenza documentata una nuova esecuzione è consentita.

### A6 — Header applicativi attraverso l'offload

**Ambito:** `DefaultOffloadGateway`, policy degli header e test HTTP con due control plane. **Dipendenza:** A0.

Trasferire gli header applicativi consentiti anche come header HTTP del secondo hop, perché il control plane remoto ricostruisce `InvocationRequest.headers` dal trasporto. Escludere header riservati, hop-by-hop e quelli nominati dal campo `Connection`; preservare la gestione dedicata di tracing e offload-hop. Il Content-Type del trasporto deve continuare a descrivere l'envelope JSON.

**Accettazione:** un handler riceve lo stesso `x-tenant` locale e offloaded; header riservati non possono sovrascrivere quelli del gateway; tracing e prevenzione del re-offload continuano a funzionare. Verificare la ricostruzione della richiesta sul secondo control plane, non soltanto gli header del WebClient in uscita.

### A7 — Rate limiter coerente al cambio di finestra

**Ambito:** `RateLimiter` e suoi test; conservare il WebFilter già introdotto. **Dipendenza:** A0.

Rappresentare finestra e conteggio con un unico stato aggiornato atomicamente tramite CAS. Usare un riferimento temporale controllabile per verificare i confini; evitare un'allocazione obbligatoria per ogni richiesta se è possibile mantenere lo stato compatto. Conservare la semantica a finestre e gli aggiornamenti runtime del limite.

**Accettazione:** interleaving controllato fra cambio finestra e richieste concorrenti, nessuna ammissione persa dal conteggio. Documentare che due finestre adiacenti possono comunque ammettere un burst: non è un token bucket. Misurare costo per ammissione/rifiuto e contesa prima e dopo.

## 4. Rimozione dei colli di bottiglia confermati

### P1 — Proxy container concorrente e limitato

**Ambito:** `RoundRobinFunctionProxy`, factory, proprietà del provider e lifecycle. **Dipendenza:** A0; verifica integrata dopo A2/A3.

Prima implementazione: executor a virtual thread con chiusura esplicita, admission non bloccante con limite al numero di richieste di invocazione e rifiuto definito quando esaurito. Evitare di parcheggiare un numero illimitato di richieste su un semaforo. Health non deve consumare gli stessi permessi delle invocazioni. Chiudere exchange, client e executor anche su errori e shutdown.

Propagare al proxy una policy di timeout coerente con la funzione al provisioning e agli aggiornamenti; eliminare il valore fisso di 30 secondi. Verificare separatamente durata del singolo hop e budget complessivo del chiamante. Un proxy interamente non bloccante resta un'alternativa successiva solo se il profilo mostra un limite della soluzione più piccola.

**Accettazione:** il test con backend bloccato su latch deve osservare più richieste in volo prima di liberarlo; con 4 permessi e 4 richieste il massimo osservato deve essere 4. Testare saturazione, health, cambio backend, timeout maggiore di 30 secondi con tempo/test appropriato, disconnessione e shutdown. Ripetere il benchmark del report senza trasformare i suoi 284/827 ms in soglie CI.

### P2 — Risveglio sync al rilascio di capacità

**Ambito:** `SyncScheduler`, `SyncQueueService`, notifiche da capacità/enqueuer. **Dipendenze:** A2 e A4.

Sostituire il backoff tramite sleep con attesa notificabile su lavoro o capacità disponibile, conservando un timeout di sicurezza per scadenze e recupero. Notificare anche incrementi di capacità dal governor, registrazione e cambi runtime rilevanti. Usare un predicato o una sequenza di notifiche per evitare segnali persi fra controllo e attesa.

**Accettazione:** rilascio di uno slot risveglia il worker senza attendere il vecchio backoff; nessun busy loop a coda vuota o capacità zero; stop interrompe l'attesa. Preservare avanzamento delle funzioni non sature quando altre occupano la testa della coda. Misurare ritardo rilascio→dispatch, p99 queue wait e CPU idle.

## 5. Metriche e regolazione della capacità

### M1 — Tempi dell'invocazione separati dai tempi del tentativo

**Ambito:** task/record, completamento, metriche, letture SOJOURN/adaptive. **Dipendenze:** A2 e A3. **Prima del tuning dei controller.**

Conservare un istante originale di ammissione che non venga sostituito al retry; usare tempo monotono per le durate e wall-clock per gli istanti esposti nell'API. Definire separatamente attesa di ogni tentativo, tempo di servizio e durata totale dell'invocazione. Registrare una sola conclusione end-to-end per invocazione, inclusi errori e timeout secondo la policy terminale; i timeout dei singoli waiter restano una misura distinta.

Verificare i consumatori prima di cambiare le serie esistenti. Se il significato di una metrica cambia in modo incompatibile, introdurre una serie esplicita e aggiornare SOJOURN, dashboard e query nella stessa consegna. Non trattare una durata censurata dal timeout come un campione di successo veloce.

**Accettazione:** durata totale con retry include tutti i tentativi e le attese; nessun doppio campione su callback tardivi/duplicati. Testare la risposta dei controller a mix di successi, retry e timeout, oltre al solo valore del timer.

### M2 — Scaling consapevole delle repliche già richieste

**Ambito:** `InternalScaler`, calcolatore, provider e coordinatore deployment. **Dipendenza:** A0; validazione con M1 e governor.

Leggere desiderate e pronte da uno stesso `ReplicaStatus`. Preservare la semantica delle metriche nella formula; confrontare il nuovo comando con il target già richiesto, non etichettare ogni aumento rispetto alle sole ready come scale-up. Una raccomandazione intermedia durante startup non deve ridurre un target più alto ancora in corso. Applicare downscale solo con un segnale esplicito, cooldown e protezione wake-up rispettati.

**Accettazione:** caso 10 desiderate/2 pronte/raccomandazione 4 senza riduzione involontaria, zero repliche, min/max, startup lento o fallito, rollout, cooldown e rimozione concorrente. Non bloccare indefinitamente un downscale reale a causa di repliche mai diventate ready: prevedere e testare la gestione del mancato progresso.

### M3 — Snapshot condiviso delle repliche

**Ambito:** coordinatore deployment, autoscaler, concurrency governor e provider. **Dipendenza:** M2.

Prima soluzione: snapshot condiviso con timestamp, TTL esplicito e singola richiesta di refresh per funzione/generazione. Invalidare dopo modifica del target, rimozione e nuova registrazione. Uno stato scaduto o una GET fallita non deve essere interpretato come zero repliche. Wake-up e lifecycle mantengono la possibilità di ottenere una lettura fresca.

Isolare i refresh lenti con concorrenza limitata e impedire applicazioni fuori ordine. Passare a watch/informer Kubernetes solo se la frequenza di polling o la latenza misurata lo richiedono.

**Accettazione:** meno chiamate API duplicate per ciclo, freshness entro il limite scelto, una funzione lenta non blocca le altre e nessun aggiornamento della vecchia generazione. Misurare numero di GET, durata dei cicli e tempo di reazione allo scaling.

## 6. Tuning guidato dalle misure

Queste attività producono prima un profilo e un confronto isolato. Se il beneficio non emerge oltre la variabilità della baseline, documentare il risultato e conservare l'implementazione/default precedente.

| ID | Ambito e implementazione proposta | Dipendenze e accettazione |
| --- | --- | --- |
| T1 | Pool HTTP: proprietà per connessioni, numero massimo di acquisizioni pendenti e timeout di acquisizione; provider condiviso con lifecycle esplicito. Coordinare budget dei pool per destinazione con admission e concorrenza, considerando traffico dispatch e offload. | Dopo A2/A3 e P1. Carico su una e molte destinazioni, backend lento e pool esaurito: nessuna attesa senza limite o esplosione dei retry. Miglioramento di acquisizione/p99 senza crescita incontrollata di connessioni e memoria. |
| T2 | Code: profilare monitor e scansioni; mantenere contatori per funzione nei punti di mutazione della sync queue. Rendere configurabile il batch async, confrontando 2, 4, 8 e 16. Ridurre i lock sovrapposti solo se il profilo ne dimostra il costo e resta atomica la relazione fra chiusura, offer e contatori. | Dopo P2 e M1. Misurare throughput, CPU/allocazioni e p99 per funzione, includendo una funzione molto attiva e molte poco attive. Nessuna starvation o regressione di remove/re-register. La sostituzione del deque globale è un secondo passo, non una premessa. |
| T3 | Memoria: aggiungere un budget ponderato dei payload degli esiti oltre al limite di conteggio e budget di admission per richieste vive. Stimare il peso una sola volta senza riserializzare il payload a ogni accesso. Includere output, header e overhead; mantenere margine per memoria nativa e strutture condivise. | Dopo A5. Payload piccoli/grandi, ASYNC/keyed, lungo soak e pressione: ritenzione e deduplicazione rispettate, heap/RSS stabilizzati, costo del weigher misurato. Il budget stimato non è una garanzia di limite esatto dell'heap. |
| T4 | Diagnostica: profilare registrazione/lettura dei meter e costo delle sonde sul percorso caldo; riusare i profili metrici esistenti, spostare fuori dai lock i callback e le operazioni non necessarie all'atomicità. | Dopo M1. Confronto con la stessa osservabilità nei due bracci; non attribuire un guadagno alla sola eliminazione di metriche che guidano governor o scaler. |

## 7. Matrice di validazione

Async queue e sync queue sono alternative dichiarate nei descrittori: provarle in build distinte, non selezionarle insieme. Autoscaler e concurrency-control richiedono un modulo di coda.

| Profilo | Verifiche richieste |
| --- | --- |
| Nessun modulo di coda | Invocazione diretta, retry e cleanup; `:enqueue` indisponibile. |
| Async queue | Replay ASYNC, saturazione, retry, fairness, rimozione delle funzioni. |
| Sync queue + runtime-config | Lifecycle runtime, timeout di coda, risvegli, drain e rollback. |
| Una coda + autoscaler + concurrency-control | Scaling con ready diverse da desired; comportamento dei controller con metriche corrette. |
| Una coda + offload | Header attraverso due control plane, successo/errore/timeout e assenza di rilasci di slot mai acquisiti. |
| Container provider | Proxy concorrente, limiti, health, timeout, cambio repliche e teardown. |
| Kubernetes provider | Lifecycle e scaling tramite gli scenari NanoLab; nessun provisioning aggiunto ai test NanoFaaS. |
| JVM e native | Build/AOT e smoke dei profili modificati; benchmark della variante effettivamente distribuita. |

Comandi di partenza, da restringere ai test interessati durante ogni PR:

```bash
./gradlew :control-plane:test -PcontrolPlaneModules=none
./gradlew :control-plane:test :control-plane-modules:async-queue:test -PcontrolPlaneModules=async-queue
./gradlew :control-plane:test :control-plane-modules:sync-queue:test :control-plane-modules:runtime-config:test -PcontrolPlaneModules=sync-queue,runtime-config
./gradlew :control-plane-modules:container-deployment-provider:test -PcontrolPlaneModules=container-deployment-provider
```

Per gli altri profili aggiungere i moduli espliciti e i rispettivi task `:control-plane-modules:<id>:test`. Eseguire build e controlli di contratto/AOT pertinenti prima dell'integrazione finale. Alcuni test richiedono un runtime container; registrare sempre eseguiti, saltati e motivi. Per E2E riusare `deployment-lifecycle-container` e `deployment-lifecycle-k8s` dal checkout NanoLab con `NANOFAAS_ROOT` impostato. Eventuali estensioni agli scenari appartengono a quel repository e vanno consegnate separatamente.

## 8. Protocollo di misura e criteri di promozione

Usare il workflow compare e la raccolta Prometheus già disponibili in NanoLab. Ogni confronto include baseline e candidato nella stessa matrice e sulla stessa infrastruttura, almeno tre ripetizioni per braccio con ordine alternato. Registrare warm-up, durata e dispersione; aumentare le ripetizioni solo quando il risultato resta inconcludente.

Variare un solo intervento per volta. Coprire carico sotto saturazione, vicino al limite e sovraccarico; SYNC e ASYNC dove supportati; senza chiavi e con replay; payload piccoli e grandi; funzioni veloci insieme a lente; errori/retry. Le prove di capacità mantengono le repliche fisse, poi una matrice distinta misura l'autoscaling. Separare smoke brevi e soak sufficientemente lunghi da attraversare le finestre di ritenzione configurate.

Metriche decisive: successi entro SLO al secondo, latenza client p50/p95/p99, 429 e timeout, tentativi per invocazione, latenza di coda, ritardo di risveglio, attesa nel pool HTTP, slot occupati, CPU per successo, allocazioni per successo, heap/RSS, GC e throttling CFS. Per ASYNC misurare fino al completamento, non fermarsi alla risposta 202. Conservare offerte, ammesse, rifiutate e concluse separatamente.

**Soglie iniziali proposte:** regressioni funzionali ammesse zero; per puro tuning, obiettivo di almeno 10% sulla metrica bersaglio e nessun peggioramento superiore al 5% di throughput utile o p99 negli scenari di controllo. Sono criteri sperimentali da confrontare con il rumore misurato, non promesse di risultato o soglie CI wall-clock. Un fix di correttezza resta necessario anche se costa CPU: il costo deve essere riportato e ottimizzato senza annullare il fix.

## 9. Sequenza di consegna e chiusura

| Fase | Attività | Condizione per procedere |
| --- | --- | --- |
| 0 | A0 | Baseline identificata, riproduzioni e indice disponibili per gli edit. |
| 1 | A1, A2, A3, A4, A5 | Invocazioni, slot, retry, replay e transizioni runtime verificati nei profili interessati. |
| 2 | A6, A7, P1, P2 | Offload e rate limit corretti; serializzazione del proxy e attesa non notificabile eliminate. |
| 3 | M1, M2, M3 | Metriche affidabili e capacità regolata senza confondere target e ready. Nuova baseline dopo i fix. |
| 4 | T1, T2, T3, T4 | Ogni cambiamento sostenuto da un confronto riproducibile, oppure archiviato come non conveniente. |
| 5 | Matrice integrata, documentazione e rilascio | Nessuna perdita di slot, duplicazione idempotente o regressione di contratto; prove operative registrate. |

Ogni ID è una unità di lavoro verificabile, preferibilmente una PR piccola; T2/T3 e A2 possono richiedere più PR, ciascuna con invarianti preservati. L'ordine sopra non richiede agenti paralleli. Prima di ciascuna PR verificare nuovamente le dipendenze nel codice corrente, perché i primi fix cambieranno i simboli da modificare nei successivi.

Aggiornare `docs/control-plane.md`, README dei moduli e documentazione delle metriche/configurazioni dove cambia il comportamento. Modificare `openapi/core.yaml` o il frammento di modulo per nuovi esiti/parametri, senza editare l'OpenAPI generata. Aggiornare Helm/Compose per le proprietà effettivamente introdotte e mantenerne i default allineati all'applicazione.

Prima di ogni commit eseguire `gitnexus_detect_changes`, controllare simboli e processi attesi e aggiornare i dipendenti diretti; dopo i refactor verificare lo scope completo. Dopo il commit aggiornare l'indice preservando gli embeddings esistenti.

Conservare risultati grezzi e sintesi in una nuova directory di esperimenti, senza sovrascrivere la coda di sovraccarico già presente. Per ciascuna ottimizzazione annotare configurazione precedente e procedura di ripristino. Un rollback che riavvia il control plane perde lo stato in memoria: verificarne l'impatto operativo prima del rilascio e preferire, per il tuning, proprietà che consentano di ripristinare i valori senza riavvio quando supportato.

Il piano è completato quando tutti i fix hanno test di regressione integrati, ogni suggerimento di performance ha un'implementazione misurata o un esito motivato di non adozione, e la matrice finale riporta esplicitamente copertura e limiti delle verifiche.
