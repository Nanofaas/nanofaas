# Piano eseguibile: correttezza, memoria e modularità del control plane

Data: 8 settembre 2026. Revisione analizzata: `1d9e2f5518c21641be2e791cca9a952ca4128d34`.

Questo piano copre **tutti gli otto rilievi R1–R8, gli ulteriori rischi di memoria
e le modifiche architetturali** della [review pre-soak](../control-plane-pre-soak-review-2026-09-08.md).
È un piano di implementazione; la sua presenza non significa che le correzioni
o i test descritti siano già stati eseguiti. Non attribuisce ancora a un bug
specifico la crescita di RAM dell'esperimento storico.

La sequenza obbligatoria è: riproduzioni e contratti → correzioni e proprietà
delle risorse → limiti e isolamento → baseline misurata → estrazione dei
contratti e wiring → verifica finale e soak. Una riorganizzazione di package
non vale come correzione di una perdita di memoria.

## 1. Istruzioni per l'agente esecutore

1. Leggi questa pagina, la review e il [consuntivo precedente](../control-plane-outcome-2026-09.md).
   Il [piano del 5 settembre](2026-09-05-control-plane-correctness-and-performance.md)
   fornisce contesto, ma non sostituisce i criteri di accettazione qui sotto.
2. Lavora su una modifica logica per volta. Non avviare contemporaneamente
   refactoring di store, capacità e moduli. Non modificare sorgenti sotto
   `docs/experiments` per farli sembrare la nuova implementazione.
3. Conserva il lavoro già presente: al momento della stesura risultano modificati
   `docs/experiments/overload-path-2026-09/STATO.md` e `run-queue-2.out`.
   Review, piano e harness pre-soak sono documenti nuovi da preservare.
   Controlla nuovamente `git status`; non usare reset/clean per ottenere una baseline.
4. Prima di modificare simboli esistenti, esegui GitNexus context e impact
   upstream, disambiguando con file e tipo; comunica dipendenze e rischio.
   `ExecutionStore` e `FunctionCapacityRegistry` risultavano **HIGH** nella review.
   L'analisi di classe non sostituisce quella dei metodi modificati.
5. Verifica che l'indice appartenga al checkout modificato. L'indice usato dalla
   review era registrato come `nanofaas` in un altro worktree allo stesso commit:
   un suo `detect-changes` non verifica i cambiamenti di questo checkout.
   Se obsoleto, aggiorna l'indice; conserva gli embedding quando presenti.
   Usa MCP se disponibile, altrimenti la CLI GitNexus installata e il suo help.
   Non aggirare un impact indisponibile modificando comunque i simboli.
6. Per rename usa il dry-run GitNexus; dopo refactoring e prima di eventuali
   commit esegui `detect_changes` sul checkout corretto e confronta anche il diff Git.
   Commit, pubblicazione e merge seguono l'autorizzazione della sessione esecutiva.
7. Per ogni task registra in `docs/experiments/lifecycle-memory-2026-09/STATO.md`:
   ID, revisione, file cambiati, impact, test/comandi/esiti, misure, eventuale
   incompatibilità documentata e prossimo passo. Non dichiarare completato un
   task con test falliti, mancanti o soltanto programmati.
8. Test di concorrenza: usa latch/barrier, future controllate, clock/ticker
   iniettabili e scadenze massime. Non usare `sleep` come prova di un ordinamento.
   Ogni regressione deve fallire sulla baseline per la causa attesa e passare
   dopo il fix; conserva l'evidenza red/green.
9. Se un nuovo risultato contraddice il piano, riproducilo, aggiorna la decisione
   e i task dipendenti prima di proseguire. Non inventare un comportamento API
   né accettare una regressione per completare una casella.

**Perimetro:** singolo control-plane pod, code in memoria, Java/Spring Boot e
supporto native. Nessuna autenticazione, coda distribuita, riscrittura del
linguaggio o nuovo servizio di orchestrazione. I test infrastrutturali restano
in NanoLab. I nomi di nuove classi/proprietà qui proposti non esistono ancora;
usa il package reale `it.unimib.datai.nanofaas` del repository.

## 2. Decisioni e invarianti da proteggere

Queste sono le scelte raccomandate per l'implementazione. Le modifiche al
contratto pubblico devono comparire nei test HTTP, nella specifica e nelle note
di compatibilità dello stesso cambiamento.

| ID | Invariante / scelta |
|---|---|
| I1 | Una esecuzione ha un solo risultato terminale condiviso; future, polling e replay concordano. Un timeout del singolo waiter termina soltanto la sua attesa. Il timeout dell'esecuzione è un evento distinto. |
| I2 | Finché una chiave è protetta dalla sua retention, la rimozione/evizione del risultato non autorizza una seconda esecuzione. Un risultato non più disponibile usa il contratto di tombstone, senza redispatch. |
| I3 | Una transizione terminale conclude sempre future e stato locale. Le risorse vengono chiuse oppure restano attribuite al lavoro realmente ancora attivo fino al suo drain; non scompaiono dai contatori per il solo timeout HTTP. Listener e metriche non fanno parte della transazione di correttezza. |
| I4 | Ogni tentativo acquisisce esattamente le risorse che rilascia. Il lease appartiene al tentativo e alla generazione della funzione, non al solo nome. Retry e callback tardive non rilasciano risorse di altri tentativi. |
| I5 | L'ammissione di nuovo lavoro ha limiti finiti di conteggio e byte anche senza moduli queue/governor. Replay già esistenti non consumano una seconda ammissione di esecuzione; i waiter hanno comunque un proprio limite. |
| I6 | Un payload che non può essere pesato entro un costo limitato non viene conservato nella cache dei risultati. Si mantiene la protezione della chiave. Il peso stimato non viene presentato come misura esatta dell'intero heap. |
| I7 | Rimozione e nuova registrazione non permettono a callback/refresh vecchi di ricreare stato o modificare metriche/capacità della nuova generazione. Lo stato ritirato sparisce dopo il rilascio delle risorse che possiede. |
| I8 | Code di executor, timer, callback e acquisizioni HTTP sono osservabili e limitate; ogni risorsa ha un owner di shutdown. Un errore di un backend non blocca indefinitamente gli altri. |
| I9 | La verità del provider distingue osservazione disponibile, stale e indisponibile. Un errore di lettura non diventa `readyReplicas=0`. |
| I10 | Un errore parziale di deprovision non fa perdere la possibilità di rintracciare e ripulire risorse. Chiudere il contesto Java non equivale a eliminare i container persistenti recuperabili al riavvio. |

**Compatibilità R3:** la scelta I1 cambia il comportamento oggi documentato in
cui un waiter breve può rendere terminale `TIMEOUT` l'esecuzione condivisa.
Mantieni il formato e lo status HTTP del timeout del waiter dove già previsti,
ma documenta che un polling/replay successivo può osservare ancora lavoro in
corso e poi il risultato reale. Mantieni separati budget del chiamante, deadline
del tentativo, retry policy e vita massima dell'esecuzione. Non cambiare il
numero predefinito di retry né renderlo non configurabile.

**Destinazione architetturale:** un `ExecutionLifecycle` interno al core,
`DispatchAttempt`/`DispatchLease`, capacità obbligatoria nel core, piccoli port
per le estensioni e una libreria obbligatoria `platform/control-plane-spi`.
`common` resta dedicato ai modelli wire/runtime condivisi. `workload-metrics`
resta un componente di osservabilità e perde la proprietà della capacità mutabile.
Async queue e sync queue restano distinte; autoscaler e concurrency-control
restano selezionabili separatamente; i due provider restano adattatori esclusivi.
Offload, runtime-config e build-metadata restano moduli opzionali.

Non creare in questa campagna un ulteriore JAR `execution` o un modulo opzionale
`deployment-management`: i relativi confini saranno package/configurazioni
verificati nel core. La separazione in JAR non aggiunge qui un requisito di
deploy o un consumatore indipendente. Registrare questa decisione chiude la
valutazione di quei possibili split; non costituisce lavoro dimenticato.

## 3. Copertura completa e ordine

| Rilievo della review | Task |
|---|---|
| R1: peso dei risultati; semantica count/byte cap | P02 |
| R2: finestra tra archivio, chiave e replay | P04 |
| R3: timeout del waiter altera il risultato comune | P01, P05 |
| R4: offload e altre uscite terminali non concludono lo stato | P04, P05, P06 |
| R5: sync disabilitata rilascia slot non acquisiti | P06 |
| R6: deprovision parziale perde stato e proxy | P08 |
| R7: superamento concorrente di maxKeys | P03 |
| R8: storia dei nomi in metriche/snapshot; metriche offload | P09, P10 |
| Ammissione diretta; byte di lavoro vivo/code/waiter | P06, P07 |
| Executor dei refresh e letture che bloccano i loop | P10 |
| Timer wake-up e GET ripetuti su funzioni già pronte | P11 |
| Cancellazione del dispatch alla scadenza amministrativa | P06 |
| Pool HTTP per destinazione e churn degli endpoint | P12 |
| Buffer e deadline del proxy container | P13 |
| Timestamp del WaitEstimator | P14 |
| Copie e persistenza dell'intero catalogo | P15 |
| Byte dei callback SDK; Python dopo timeout; shutdown Java-lite | P16, P17, P18 |
| Owner del lifecycle e della capacità; separazione dei port | P04–P07, P20 |
| SPI, dipendenze tra moduli, RuntimeConfigExtension | P21 |
| Bean di deployment/refresh e osservazioni condivise | P10, P11, P20, P22 |
| Protezione dei confini, prestazioni, packaging/native | P19, P21–P23 |
| Soak e attribuzione della RAM originale | P24 |
| Documentazione e chiusura verificabile | P25 |

Esegui P00–P09 in ordine. Esegui quindi P10–P18; le dipendenze indicate sono
obbligatorie anche se in futuro il lavoro venisse assegnato a più agenti.
P19 congela una baseline corretta prima delle estrazioni P20–P22.
P23 verifica il risultato completo; P24 esegue il soak; P25 chiude la campagna.
Non saltare i rischi senza riproduzione: P14/P15 prevedono una decisione misurata,
mentre gli altri task indicano correzioni o limiti concreti da realizzare.

Mappa iniziale per trovare i file citati nei task; i package sotto `src/main/java`
partono da `it/unimib/datai/nanofaas`. Le posizioni dopo P06/P21 saranno diverse.

| Area | Directory iniziale |
|---|---|
| Execution, service, registry, deployment, config, metrics core | `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/` |
| Test core | `platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/` |
| Capacità e osservazioni attuali | `platform/workload-metrics/src/main/java/it/unimib/datai/nanofaas/workloadmetrics/` |
| Implementazioni opzionali e rispettivi test | `platform/modules/<id>/src/main/java/` e `src/test/java/` |
| Build e selezione moduli | `settings.gradle`, `platform/control-plane/build.gradle`, `platform/modules/<id>/build.gradle`, `platform/gradle-plugin/` |
| Contratti pubblici e deploy | `openapi/core.yaml`, `platform/modules/<id>/openapi.yaml`, `deploy/helm/nanofaas/`, `deploy/compose/` |

## 4. Baseline e correzioni del ciclo di vita

### P00 — Preparare evidenze e test di regressione

**Dipendenze:** nessuna. **Output:** registro di esecuzione e regressioni riproducibili.

1. Registra SHA, stato Git, JVM, architettura, heap, moduli e configurazione.
   Conserva come riferimento il commit analizzato e la baseline storica `e35405ee`.
2. Leggi [README e harness diagnostico](../experiments/pre-soak-review-2026-09-08/README.md).
   Il suo esito positivo conferma nove comportamenti difettosi: non è una suite
   che debba continuare a passare dopo i fix. Non invertirne indiscriminatamente
   gli assert; porta ogni caso nella suite del componente responsabile.
3. Crea test di regressione per R1–R8 e per l'ammissione diretta senza queue.
   Per R2 forza esplicitamente evizione e interleaving prima della notifica della
   chiave; per R6 inietta fallimenti dell'adapter; per R7 sincronizza le claim.
4. Aggiungi alla matrice i percorsi: nessuna queue, async queue, sync queue
   abilitata/disabilitata a runtime, offload, LOCAL/EXTERNAL/DEPLOYMENT,
   retry, rimozione e nuova registrazione dello stesso nome.
5. Esegui i nuovi test sulla baseline, separatamente dai test già esistenti.
   Registra gli assert falliti attesi; errori di compilazione o dipendenze mancanti
   non costituiscono una riproduzione red.

**Accettazione:** ogni R ha almeno un test che verifica il comportamento corretto
e fallisce per il difetto attuale, con esecuzione limitata nel tempo. L'harness
storico e il suo output restano intatti. La suite preesistente ha un esito registrato.
P00 è l'eccezione esplicita al requisito green: registra i test red attesi e il
task che li farà passare. Nei task intermedi distingui questi fallimenti già
registrati da regressioni nuove; ai gate P19/P23 nessun red atteso può rimanere.

### P01 — Scrivere contratto, macchina a stati e matrice delle risorse

**Dipendenze:** P00. **File iniziali:** `ExecutionRecord`, `ExecutionState`,
`ReactiveInvocationCoordinator`, `ExecutionCompletionHandler`, `openapi/core.yaml`.

1. Scrivi un ADR in `docs/architecture/` con I1–I10 e una tabella di transizione:
   stato iniziale, evento, stato finale, risultato condiviso, effetto su chiave,
   store, lease, budget e cancellazione. Non introdurre nuovi stati senza transizioni.
2. Elenca almeno: submit rifiutata, queued, dispatch, successo, errore ritentabile,
   retry rifiutato, retry esauriti, timeout waiter, timeout tentativo, scadenza
   amministrativa, cancellazione client, rimozione funzione, offload, shutdown
   e callback duplicata/tardiva. Il timeout di un tentativo segue la retry policy;
   non conclude per forza l'intera esecuzione.
3. Definisci che il distacco di un waiter non cancella il lavoro condiviso.
   La fine globale conclude tutti i waiter rimasti con lo stesso risultato.
   Le metriche distinguono latenza dell'attesa, del tentativo e dell'esecuzione.
4. Mantieni l'attuale scope pubblico delle chiavi, verificandolo nel codice/spec;
   l'identità di generazione è interna e non permette di aggirare una tombstone
   ancora valida riutilizzando lo stesso nome. Documenta il caso remove/re-register.
5. Definisci per ogni risorsa chi acquisisce, chi chiude e cosa accade se il metodo
   fallisce prima di pubblicare il relativo handle. Usa questa tabella nei task seguenti.

**Accettazione:** nessun evento della matrice ha ownership implicita o un
risultato diverso tra future, archivio e replay. La modifica semantica R3 è
esplicita nell'ADR; i test del comportamento nuovo vengono attivati in P05.

### P02 — Rendere conservativo e limitato il peso degli outcome

**Dipendenze:** P01. **File:** `OutcomeWeigher`, `ExecutionStore`,
`ExecutionStoreProperties`, `ExecutionStoreEvictionTest` e test del weigher.

1. Introduci un risultato di pesatura che distingua peso noto da non conservabile.
   Mantieni un limite di nodi/profondità visitati. Superarlo non deve attribuire
   peso zero al resto: marca l'outcome non conservabile e non inserirne il payload.
2. Gestisci cicli, oggetti opachi LOCAL, stringhe non Latin-1, array, mappe e
   collezioni; usa aritmetica saturante per overflow. Includi overhead conservativo
   del contenitore e della chiave. Non serializzare l'intero risultato a ogni lookup.
3. Congela/copia in modo limitato il valore conservato o trattalo come non
   conservabile se può mutare dopo la pesatura. Un risultato LOCAL modificabile
   non può crescere indisturbato dentro una cache pesata all'inserimento.
4. Separa risposta al chiamante e conservazione per replay: un esito valido può
   essere consegnato senza conservarne il payload. La chiave resta protetta;
   P04 garantisce che il risultato scartato non apra una finestra di redispatch.
5. Rendi esplicita la configurazione attuale: `maxOutcomes` è oggi usato per
   derivare il budget predefinito, non è un secondo cap effettivo. Conserva questa
   compatibilità e correggi documentazione/Javadoc; non promettere due limiti che
   Caffeine non applica. Se serve un vero count cap, realizzalo in un cambiamento
   separato con test, senza confonderlo con la correzione del peso.

**Test:** payload profondi oltre il limite, lista con 256 null seguiti da 1 MiB,
mappe larghe, stringhe Unicode, cicli, mutazione post-completion, peso oltre `int`
e somma oltre `long`, output scartato con chiave ancora valida. Il caso storico
30 × 1 MiB sotto 11.600 byte non deve più conservare quei payload.

**Accettazione:** nessuna forma non misurabile viene conservata con peso piccolo;
tempo di pesatura limitato; benchmark di payload ordinari confrontabile con P00.
Non dichiarare un limite esatto di heap sulla base di un estimatore.

### P03 — Rendere atomico il budget delle chiavi

**Dipendenze:** P02. **File:** `IdempotencyStore`, `InvocationExecutionFactory`,
`IdempotencyStoreTest`, `IdempotencyKeyLifetimeTest`.

1. Sostituisci `size` → controllo → `putIfAbsent` con prenotazione atomica di una
   quota prima di creare una nuova associazione. Il perdente di `putIfAbsent`
   restituisce la propria prenotazione e usa l'associazione esistente.
2. Associa la quota all'identità della voce, con rilascio una sola volta. Copri
   expire, remove, abandon, sostituzione e shutdown; verifica se i listener cache
   sono sincroni o asincroni e non basare la correttezza su un ordine non garantito.
3. Reclaim/replace della stessa associazione non consuma una seconda quota.
   Anche gli helper di inserimento e i percorsi di test devono rispettare il cap.
   La saturazione non impedisce il replay di una chiave già presente.
4. Esponi occupazione e rifiuti, senza etichette per execution ID o chiave utente.

**Test:** 16 claim simultanee con `maxKeys=1` ammettono una sola chiave; molte
claim della stessa chiave producono un solo owner; scadenze e abbandoni concorrenti
non rendono la quota negativa e consentono nuove claim dopo il rilascio.

**Accettazione:** il massimo osservato non supera il configurato in ogni
interleaving forzato; nessun contatore rimane occupato dopo drain/expiration.

### P04 — Un solo owner della transizione terminale e della deduplicazione

**Dipendenze:** P03. **File:** `ExecutionStore`, `ExecutionRecord`,
`InvocationExecutionFactory`, `ExecutionCompletionHandler` e listener terminali.

1. Introduci `ExecutionLifecycle` nel core, inizialmente dietro adattatori delle
   API esistenti. Deve possedere l'operazione terminale, non soltanto delegarla
   a una catena di listener che può interrompersi a metà.
2. Rendi non reclamabile la chiave prima di perdere l'ultima rappresentazione
   live protetta. Gestisci anche completion prima della pubblicazione della
   chiave; il fix non può limitarsi a scambiare due righe di `settle`.
   In particolare, l'assenza di record/outcome non è prova che una claim già
   pubblicata sia abbandonata: una sostituzione richiede stato esplicito
   reclamabile e confronto dell'identità. Una chiave pubblicata ancora valida
   deve restituire esecuzione, outcome o tombstone, mai una nuova claim.
3. Implementa una transizione coordinata per identità di esecuzione/chiave:
   risultato definitivo, protezione dedup, eventuale archivio, rimozione live e
   completamento della future devono avere un ordine dimostrabile. Evita lock
   globali sul percorso caldo e chiamate a codice esterno sotto lock.
4. Solo dopo aver garantito gli invarianti notifica metriche/listener; un listener
   che lancia non deve interrompere cleanup o impedire agli altri owner di chiudere.
5. Instrada scadenza amministrativa, eviction pertinente, completamenti e rimozioni
   attraverso l'owner. Mantieni temporaneamente gli adattatori usati dalle queue;
   la loro eliminazione è P20. Rendi idempotenti eventi duplicati e concorrenti.

**Test:** evizione immediata per peso e per TTL durante completion; key publish
ritardata; replay concorrente; listener che lancia; doppia completion;
completion contro expiry. Conta gli avvii reali del dispatcher, oltre a `isNew`.

**Accettazione:** un backend viene avviato al massimo una volta per chiave
protetta; la race R2 non crea una seconda esecuzione. Future e stato concordano
anche quando l'outcome non viene conservato e un observer fallisce.

### P05 — Separare timeout dei waiter e chiusura di esecuzioni/offload

**Dipendenze:** P04. **File:** `ReactiveInvocationCoordinator`,
`ExecutionCompletionHandler`, modulo offload e test di invocation/replay.

1. Elimina la mutazione del record condiviso dal timeout del singolo waiter.
   Mantieni la future condivisa protetta dalla cancellazione del subscriber.
   Rilascia subscription/timer/riferimenti del solo waiter che termina.
2. Fai passare successi ed errori offload attraverso `ExecutionLifecycle`.
   Uno stato già terminale non autorizza un ritorno anticipato che lasci future
   o store aperti. Il risultato già definitivo prevale sulle risposte tardive.
3. Cerca tutte le uscite anticipate di dispatch, retry, queue removal e sync
   timeout usando la matrice P01; elimina ogni uscita che salta il cleanup dovuto.
4. Aggiorna contestualmente OpenAPI, documentazione e test che oggi richiedono
   il vecchio timeout condiviso. Non rimuovere assert difficili senza sostituirli
   con quelli del nuovo contratto.

**Test:** waiter breve e lungo sulla stessa chiave, in entrambi gli ordini di
arrivo; il breve riceve timeout, il lungo e il replay ricevono il risultato reale.
Ripeti con errore, retry, offload success/failure e distacco di tutti i waiter.
Se scade la deadline globale, tutti gli osservatori rimasti vedono lo stesso
terminale e una risposta tardiva non lo cambia.

**Accettazione:** dopo il backend e il cleanup previsti, R4 ha zero record live
e nessuna future pendente; non occorre attendere i 30 minuti di maxLifetime.
Le metriche non classificano come errore del backend il solo timeout di un waiter.

### P06 — Lease dei tentativi, capacità obbligatoria e cancellazione locale

**Dipendenze:** P05. **File:** `FunctionCapacityRegistry/State` in workload-metrics,
handler/dispatcher core, `InvocationEnqueuer`, entrambi i moduli queue.

1. Introduci identità della generazione, `DispatchAttempt` e `DispatchLease`.
   Il lease contiene l'owner della capacità acquisita e un rilascio idempotente;
   non eseguire più release cercando soltanto il nome della funzione.
2. Sposta la capacità mutabile in un package interno del core, con adattatori
   temporanei per i consumatori. Non trasferire nel core gli algoritmi opzionali
   del governor: il core applica limiti, il governor può regolarne il valore.
3. Copri ogni tentativo iniziale e retry. Senza queue applica comunque la
   concorrenza configurata; se non c'è posto rifiuta secondo il contratto di
   overload, senza creare una coda implicita illimitata. Sync disabilitata non
   equivale a capacità disabilitata. Offload usa la propria ammissione locale,
   senza fingere di aver acquisito uno slot del backend locale.
4. Registra il vero handle cancellabile del trasporto per ciascun tentativo.
   Su scadenza amministrativa/rimozione/shutdown richiedi la cancellazione
   locale e chiudi la proprietà una sola volta. Gestisci anche cancellazione
   prima che l'handle sia pubblicato. Una future composta non prova che il
   subscriber HTTP sottostante sia stato cancellato: testalo al confine trasporto.
5. Non cancellare la future di wake-up condivisa da altre esecuzioni. Una
   cancellazione locale non garantisce che una funzione remota interrompa il
   lavoro; per handler LOCAL non interrompibili conserva il conteggio del lavoro
   realmente attivo fino alla sua fine, pur potendo concludere l'attesa HTTP.
6. Ritiro della funzione: i lease vecchi restano legati al vecchio owner fino
   al drain; non possono decrementare lo stato della nuova registrazione.

**Test:** concorrenza 1 senza queue con 100 chiamate; una sola dispatch attiva.
Toggle sync durante un tentativo bloccato; una risposta diretta non libera il
suo slot. Retry successivi, submit che lancia, timeout prima/dopo handle,
doppia callback, remove/re-register, HTTP lento e handler LOCAL non cooperativo.

**Accettazione:** conteggio degli owner acquisiti = attivi + rilasciati, senza
valori negativi; nessun percorso aggira il cap. Un server HTTP controllato e le
metriche del client confermano la cancellazione locale, non solo il terminale
del record. Nessuna promessa di terminazione remota forzata.

### P07 — Budget aggregati del lavoro vivo, dei payload e dei waiter

**Dipendenze:** P06. **File:** ammissione core, store, ingress HTTP, queue,
offload, proprietà/configurazione/Helm.

1. Aggiungi budget distinti e finiti per esecuzioni ammesse, byte di input
   trattenuti e waiter collegati; applica un limite globale oltre ai limiti per
   funzione. Ogni quota ha un owner e lo stesso criterio di rilascio di P06.
2. Prenota prima di pubblicare nuovo stato live/queue. Su errore intermedio
   restituisci tutte le quote; su retry trasferisci la proprietà dell'input senza
   contarla due volte. Una chiave esistente riusa l'esecuzione ma non aggira il
   limite dei waiter. Anche il lavoro offload e pending HTTP conta nel totale.
3. Limita il body all'ingresso prima del parsing completo; poi contabilizza la
   rappresentazione trattenuta con una policy conservativa e costo limitato.
   Per oggetti LOCAL non misurabili rifiuta l'ammissione o richiedi una
   rappresentazione limitata; non addebitare un valore simbolico di pochi byte.
4. Prima di fissare i default prepara una tabella con heap/container minimo
   supportato, byte live, outcome, chiavi, headroom per parsing/HTTP/thread e
   limiti di conteggio. Esegui qui il corpus T1/T2 della sezione 8 rispetto a P00
   per scegliere valori numerici finiti sostenibili; P19 li riverificherà,
   senza costituire una dipendenza circolare. Registra formula, valori e motivo. È vietato chiudere
   questo task con default `unlimited` o soltanto un rate limit.
5. Per body oltre limite usa 413; per ammissione satura usa il contratto di
   overload esistente, con errore distinguibile e Retry-After coerente. Documenta
   precedenza tra replay, validazione e ammissione. Valori invalidi di nuove
   proprietà falliscono all'avvio; una riduzione runtime non espelle work attivo,
   ma blocca nuove ammissioni finché rientra nel limite.

**Test:** molti nomi con queue individualmente piccole; input grandi; retry;
replay massivo; cap ridotto sotto l'occupazione corrente; offload lento; errori
durante publish/enqueue. Dopo drain ogni quota live/waiter torna a zero.

**Accettazione:** i contatori non superano i cap dichiarati; memoria di input
trattenuto non cresce linearmente con richieste rifiutate. Sono dichiarati
anche i buffer transitori esclusi dalla stima, con propri limiti di ingresso.
Nessun claim secondo cui questi contatori da soli limitano RSS o memoria remota.

### P08 — Rendere recuperabile il deprovision parziale

**Dipendenze:** P07. **File:** `ContainerLocalDeploymentProvider`,
`RoundRobinFunctionProxy`, `FunctionService` e test managed deployment.

1. Non cancellare da `states` l'unico riferimento prima di terminare o registrare
   la pulizia. Non cancellare dalla mappa la replica prima di sapere che è stata
   rimossa o di aver conservato uno stato esplicito da ritentare.
2. Prova a pulire tutte le risorse pertinenti anche se una rimozione fallisce;
   raccogli gli errori e gestisci il proxy in un percorso garantito. Specifica
   quando il proxy resta operativo per un rollback e quando viene chiuso per
   una rimozione pendente: non chiuderlo e poi dichiarare ripristino operativo.
3. Allinea provider e `FunctionService` su un esito parziale esplicito. La policy
   raccomandata è rimozione pendente dopo una cancellazione parziale: blocco di
   nuove invocazioni per quella generazione, errore API esplicito e retry della
   pulizia. Mantieni proprietà e dati sufficienti per retry/reconciliation; non
   simulare un rollback completo quando mancano risorse. Il rollback operativo
   è ammesso soltanto se nessuna risorsa necessaria è stata persa o è già stata
   ricostruita e verificata. Documenta il comportamento API nello stesso task.
4. Verifica discovery/riavvio mediante metadati delle risorse gestite. Una seconda
   deprovision riprende la pulizia in modo idempotente. Lo shutdown del contesto
   chiude proxy/executor/client locali, preservando i container da recuperare.

**Test:** fallimento di una replica su più repliche; errore di close; secondo
tentativo riuscito; rollback/reconcile; stop/start del provider; rimozione durante
invocazione. Integra il test con adapter controllato con un caso Docker in NanoLab.

**Accettazione:** R6 non perde lo stato proprietario; ogni risorsa rimasta è
rintracciabile e ripulibile. Dopo pulizia riuscita non restano proxy o executor;
dopo un errore il catalogo e l'API rappresentano lo stato realmente ottenuto.

### P09 — Eliminare stato storico senza permettere resurrezioni

**Dipendenze:** P08 e identità di P06. **File:** `Metrics`, `SyncQueueMetrics`,
`SyncQueueService`, `DefaultOffloadGateway`, lifecycle della funzione.

1. Inventaria mappe, set di nomi rimossi e meter per funzione. Sostituisci i set
   che crescono per sempre con owner di generazione: active → retiring → closed.
   Retiring dura solo finché esistono risorse reali, limitate da P06/P07.
2. Centralizza registrazione e rimozione dei meter; un evento vecchio può chiudere
   il suo owner ma non registrarne uno nuovo. Copri anche i contatori offload
   creati al subscribe/onError e i meter creati pigramente.
3. Non risolvere la crescita con una TTL che dimentica il nome mentre callback
   vecchie possono ancora arrivare. Evita execution ID e generation ID come tag
   Prometheus; la generazione è un'identità interna, non nuova cardinalità esposta.
4. Collega la rimozione definitiva dello snapshot a P10; fino a quel task conserva
   un test red esplicito, senza marcare chiuso l'intero R8.

**Test:** almeno 1.000 nomi distinti registrati/invocati/rimossi; stessi nomi
riregistrati con callback vecchie sospese; offload errore dopo removal;
toggle sync e chiusura del contesto. Controlla stato interno e meter registry.

**Accettazione:** dopo drain lo stato torna al livello delle funzioni correnti;
eventi vecchi non ricreano meter e non contaminano la nuova generazione.

## 5. Risorse, isolamento e runtime

### P10 — Snapshot delle repliche con refresh limitati e non bloccanti

**Dipendenze:** P09. **File:** `ReplicaStatusSnapshot`,
`ManagedDeploymentCoordinator`, autoscaler, concurrency-control e relativi test.

1. Trasforma lo snapshot in un bean con executor posseduto dal contesto,
   coda limitata, configurazione validata e shutdown esplicito. Elimina il pool
   statico con coda illimitata. Definisci rifiuto/coalescing senza eseguire la
   chiamata remota sul thread del loop come fallback (`CallerRuns` non va bene).
2. Mantieni al massimo un refresh corrente per generazione; separa invalidazione
   del dato e proprietà del task. Invalidate non deve consentire di accodare
   infinite nuove richieste mentre le precedenti sono ancora bloccate.
3. Introduci un'osservazione immutabile con timestamp e stato fresh/stale/unavailable.
   Il percorso periodico legge immediatamente l'osservazione e pianifica refresh,
   senza GET sincrono o `join`. Conserva l'ultimo dato buono entro il limite stale
   documentato; oltre tale limite dichiara indisponibilità senza inventare zero.
4. Fornisci un percorso distinto per operazioni che richiedono freschezza,
   con deadline locale e cancellazione ove supportata. Un timeout del provider
   non può occupare indefinitamente worker e impedire tutti i progressi.
5. Su removal rimuovi la voce dopo aver invalidato la sua generazione e gestito
   il task posseduto. Una risposta vecchia non reinserisce la voce. Esponi queue
   depth, task attivi, rifiuti, età dell'osservazione e durata dei refresh.

**Test:** un provider non risponde mentre altri rispondono; coda satura;
invalidate ripetuta; remove/re-register durante GET; completamento tardivo;
fresh/stale/unavailable con clock controllato; close del contesto.

**Accettazione:** il loop visita gli altri backend entro il proprio periodo
senza attendere quello bloccato; coda e task rispettano i limiti. Le 1.000 voci
storiche di R8 spariscono dopo removal/drain. Il governor non tratta errore come
zero repliche e non compie una regolazione basata su una misura inesistente.

### P11 — Wake-up condiviso, timer cancellabili e percorso già pronto

**Dipendenze:** P10. **File:** `DeploymentWakeUpGate`,
`DeploymentWakeUpCoordinator`, `DeploymentWakeUpProperties`.

1. Usa le osservazioni di P10 per il percorso già pronto, con freschezza
   dichiarata. Una funzione warm con `minReplicas=0` non deve richiedere un GET
   nuovo a ogni invocazione. Su dato assente/stale oltre policy usa il gate.
2. Coalesci il wake-up per generazione. Conserva gli handle dei timer e cancellali
   a completamento, errore, rimozione e shutdown. Configura la rimozione dei task
   cancellati dalla coda; limita anche il numero di wake-up pendenti mediante
   l'ammissione e gli owner delle funzioni.
3. Non permettere che il timeout di un waiter cancelli il wake-up degli altri.
   Un wake-up non riduce il desired target già richiesto da autoscaler o utente;
   distingue desired > 0 da repliche effettivamente ready.
4. Mantieni un timeout assoluto del gate e cleanup anche se scale/read falliscono
   sincronicamente prima della restituzione della future.

**Test:** migliaia di successi immediati non lasciano migliaia di timer in coda;
GET rate sul caso warm cresce con la policy di refresh, non con le invocazioni;
100 waiter condividono un wake-up; desired 10 / ready 0 non viene ridotto a 1;
remove/re-register non riusa un vecchio gate.

**Accettazione:** timer e gate tornano a zero dopo drain; percorso warm e percorso
cold rispettano la readiness dichiarata e i test di timeout isolato.

### P12 — Pool HTTP con vita finita e budget aggregato

**Dipendenze:** P07, P10. **File:** `HttpClientConfig`, dispatcher HTTP,
proprietà e test con server locali controllati.

1. Verifica le API sulla versione Reactor Netty risolta dal build, usando le
   fonti ufficiali. Configura idle timeout, eventuale lifetime, eviction in
   background e rimozione dei pool inattivi con valori documentati e misurati.
2. Conserva un owner che dispone il `ConnectionProvider` allo shutdown.
   Misura connessioni, richieste in acquisizione e numero di destinazioni.
   Il limite per host non sostituisce P07; un eventuale `maxConnectionPools`
   che produce soltanto warning non è un limite aggregato effettivo.
3. Verifica che cancellazione, timeout e response error liberino connessione,
   buffer e richiesta pending. Gestisci anche endpoint cambiato/rimosso durante
   acquisizione. Non creare un pool nuovo per ogni invocazione.
4. Mantieni i default di acquisition timeout già valutati, salvo nuova evidenza:
   nella campagna precedente 45/5/1 secondi non diedero il beneficio ipotizzato.
   Non confondere tuning della latenza con correzione dell'ownership.

**Test:** churn di molte destinazioni locali, connessioni idle, server lento,
pool saturo, client che cancella, chiusura ripetuta del contesto.

**Accettazione:** dopo drain e finestra di eviction il numero di pool/socket
ritorna vicino al numero di destinazioni correnti, con soglia e tolleranza
documentate. I limiti di ammissione continuano a valere su host differenti.

### P13 — Buffer e deadline al proxy container

**Dipendenze:** P08, P12. **File:** `RoundRobinFunctionProxy`, factory,
configurazione provider e test del proxy.

1. Sostituisci le letture integrali illimitate della richiesta e della risposta
   con buffering limitato. Controlla byte realmente ricevuti anche senza
   Content-Length e in chunked; una lunghezza dichiarata non basta.
2. Applica un budget aggregato dei buffer oltre a maxInFlight. Il limite del
   decoder del control plane non protegge il proxy, che riceve prima la risposta.
   Un backend che supera il limite viene cancellato e produce errore controllato.
3. Fai partire una deadline per la lettura inbound prima di leggere tutto il
   body; aggiungi scadenze per backend e invio della risposta. Su disconnessione
   del chiamante interrompi il lavoro locale ove supportato e rilascia permessi.
4. Parti dal buffering limitato per mantenere piccolo il cambiamento. Valuta
   streaming solo se le misure mostrano che i limiti scelti impediscono payload
   supportati o hanno un costo eccessivo; in tal caso aggiungi test di backpressure,
   errori dopo gli header e proprietà dei buffer prima di sostituire la strategia.
5. Mantieni health/readiness disponibili sotto saturazione delle invocazioni;
   non trattenere un permit dopo errore prima del dispatch o durante close.

**Test:** body oltre soglia, chunked senza lunghezza, upload lento, risposta
backend enorme, client che non legge, disconnect e deprovision concorrente.

**Accettazione:** conteggio e byte sono limitati a questo hop; nessun
`readAllBytes`/accumulo equivalente senza cap rimane nel percorso invocazione.
Errori e timeout liberano buffer/permit; benchmark del payload ordinario passa P23.

### P14 — WaitEstimator: eliminare retention inattiva e misurare i bucket

**Dipendenze:** P09. **File:** modulo sync-queue, `WaitEstimator` e relativo test.

1. Misura allocazioni, campioni trattenuti e costo per dispatch con una funzione
   e molte funzioni, a rate basso/alto e dopo traffico fermo. Verifica come la
   stima influisce su ammissione, fairness e Retry-After.
2. Assicura cleanup dei campioni scaduti anche per funzioni diventate inattive,
   senza introdurre una scansione illimitata sul percorso di ogni invocazione.
   Usa pulizia periodica limitata o strutture a finestra di dimensione fissa.
3. Prototipa bucket temporali fissi, con memoria proporzionale a funzioni attive
   × numero di bucket, confrontandoli con un calcolo esatto offline sullo stesso
   stream. Definisci prima della misura risoluzione e tolleranza della stima.
4. Adotta i bucket se la stima resta entro la tolleranza e riducono costo/memoria
   senza peggiorare fairness. Altrimenti conserva l'algoritmo con cleanup/cap
   espliciti e registra il confronto che giustifica la decisione. Nessuna voce
   viene chiusa soltanto con «da profilare in futuro».

**Test:** finestre vuote, bordi dei bucket, burst, idle prolungato, rimozione,
clock monotono e overflow; confronti su stream deterministici.

**Accettazione:** nessuna storia oltre la finestra rimane indefinitamente dopo
idle; memoria massima spiegabile dai limiti. Decisione sui bucket accompagnata
da dati e test delle decisioni di ammissione, non soltanto ns/op.

### P15 — Catalogo: costo delle copie e correttezza della persistenza

**Dipendenze:** P08, P10. **File:** `FunctionRegistry`, `FunctionService`,
catalog store e test persistence/concurrency.

1. Misura register/update/remove e aggiornamenti di desired replicas con cataloghi
   da 1, 100 e 1.000 funzioni: allocazioni, byte serializzati, durata e scritture.
   Se il prodotto dichiara un limite inferiore, includi almeno quel limite.
2. Elimina scritture realmente no-op e copie ridondanti dimostrate dal profilo;
   separa osservazioni volatili dal dato che deve sopravvivere al riavvio.
   Non rendere volatile il desired state persistente solo per velocizzare lo scaling.
3. Riproduci errori di salvataggio e del provider durante PATCH/scale/remove.
   Definisci e testa stato desiderato, stato osservato e riconciliazione: una
   risposta di successo non deve promettere una scrittura durevole mai avvenuta.
4. Mantieni inizialmente il catalogo snapshot. Se resta un collo di bottiglia
   significativo rispetto al periodo di controllo/SLO misurato, prepara nello
   stesso task un ADR e un intervento circoscritto su copy-on-write/persistenza.
   Eventuale coalescing deve attendere la persistenza prima di confermare le
   operazioni che la promettono. Un journal richiede recovery, compaction e
   migrazione testati; non introdurlo come ottimizzazione automatica.
5. Se lo snapshot rimane adeguato, registra i numeri e chiudi esplicitamente
   l'ipotesi di sostituzione. Conserva comunque i fix di no-op/consistenza e i test.

**Test:** crash/reload ai confini di scrittura mediante store controllato, errore
provider dopo persistenza, errore persistenza, aggiornamenti concorrenti e no-op.

**Accettazione:** nessuna perdita di aggiornamenti confermati; recovery documentata;
costo al catalogo massimo dichiarato misurato e compatibile con i loop. Ogni
incoerenza riprodotta viene corretta, anche se non spiega la RAM del soak.

### P16 — Limiti dei callback e dei payload nei runtime SDK

**Dipendenze:** P01, P07. **File:** callback/runtime in `sdks/java`,
`sdks/java-lite`, `sdks/python`, `sdks/go`, `sdks/javascript`.

1. Per ciascun runtime censisci code/task, input, output e callback trattenuti;
   verifica i limiti reali anziché dedurli dal numero di worker. Le code già
   limitate per conteggio non vanno riscritte senza motivo.
2. Aggiungi un cap dei byte dei callback pendenti e un cap del singolo payload,
   acquisiti prima di accodare/serializzare grandi copie ove possibile. Non
   generare prima una copia illimitata per poi verificarne la lunghezza.
3. Definisci una policy comune di saturazione: rifiuto/backpressure espliciti,
   timeout finiti e rilascio su successo, errore, cancellazione e stop. Non
   accettare silenziosamente un callback che verrà perso; conserva il contratto
   di retry/idempotenza documentato e gli errori osservabili.
4. Copri anche invocazioni dirette al runtime, che possono bypassare i limiti
   del control plane. Confronta timeout/cancellazione e health in tutti gli SDK;
   correggi le differenze che violano il contratto, senza imporre identiche API
   interne tra linguaggi.

**Test:** backend callback lento/irraggiungibile, payload grandi, saturazione,
response error, stop con coda piena e ripartenza. Aggiungi un corpus wire comune
per verificare le stesse risposte limite nei diversi runtime.

**Accettazione:** byte e conteggio pendenti hanno cap finiti e tornano a zero
dopo drain/stop; nessuna Promise/task/callback viene lasciata senza owner.
Report separato per memoria dei processi funzione e memoria del control plane.

### P17 — Python: timeout dell'attesa e lavoro del thread ancora attivo

**Dipendenze:** P16. **File:** implementazione runtime Python e `tests/test_runtime.py`.

1. Conserva l'handle dell'esecuzione reale del synchronous handler. La scadenza
   di `wait_for` non significa che il thread di `to_thread` sia terminato.
   Rilascia il permit di lavoro dal completamento reale, non dal `finally`
   della sola richiesta che ha smesso di attendere.
2. Usa un executor con ammissione limitata prima del submit; `max_workers` da
   solo non limita la coda del ThreadPoolExecutor. Isola i callback bloccanti
   dai worker handler in modo che non possano affamarsi reciprocamente.
3. Per handler async propaga la cancellazione prevista e gestisci quelli che
   la ritardano; il conteggio deve rappresentare il lavoro realmente attivo.
   Documenta che un thread Python non cooperativo non si può terminare in modo
   sicuro. Non introdurre processi per invocation come effetto collaterale del fix.
4. Rendi osservabili attese terminate e handler ancora attivi. Lo shutdown ha
   un limite documentato e segnala lavoro non cooperativo; non dichiara cleanup
   riuscito mentre continuano thread che possiedono payload.

**Test:** handler bloccato con event, molti timeout consecutivi, contatore reale
di handler attivi, callback simultanei, release dell'event e drain, shutdown.

**Accettazione:** dopo ripetuti timeout il numero di handler reali non supera
il limite e non cresce una coda nascosta. Health/callback continuano a fare
progresso secondo i rispettivi limiti; nessuna pretesa di hard timeout del thread.

### P18 — Java-lite: proprietà e chiusura di executor/client

**Dipendenze:** P16. **File:** `NanofaasRuntime`, `CallbackClient` e test Java-lite.

1. Conserva come campi posseduti l'executor HTTP creato dal runtime, gli executor
   dei callback e i client che richiedono close. Distingui risorse create dal
   runtime da risorse iniettate di cui non possiede la chiusura.
2. Implementa stop idempotente e cleanup dell'avvio parziale fallito. Ferma
   nuove ammissioni, drena/cancella secondo policy, chiudi server e risorse
   nell'ordine necessario, con tempi massimi e interrupt preservati.
3. Verifica anche la configurazione Spring del Java SDK per lo stesso ownership
   pattern; correggi solo le risorse effettivamente prive di owner.

**Test:** start/stop ripetuti, bind fallito, handler/callback attivi allo stop,
stop doppio e thread factory controllata. Integra con conteggio thread/socket
del processo dopo warm-up; evita assert basati su thread estranei della JVM.

**Accettazione:** tutte le risorse create dal runtime hanno una chiusura
verificata; le risorse iniettate esterne rispettano il contratto di ownership.

## 6. Baseline corretta e riorganizzazione dei moduli

### P19 — Congelare una baseline corretta prima dei movimenti strutturali

**Dipendenze:** P00–P18 completati.

1. Esegui i test di regressione e i profili brevi della sezione 8. Registra il
   commit/build esatto e un artefatto identificabile, separato dalla baseline
   difettosa e dalla futura versione con SPI.
2. Misura throughput utile, p50/p95/p99, allocazioni per successo, heap post-GC,
   conteggi live/key/outcome e tutte le popolazioni introdotte nei task precedenti.
3. Per il confronto prestazionale usa almeno tre ripetizioni alternate delle
   revisioni, stesso ambiente e stesso lavoro offerto/ammesso. Escludi warm-up
   e checkpoint GC dalla finestra usata per la latenza ordinaria.
4. Se compare un problema di correttezza o un nuovo retainer, torna al task
   responsabile. Una baseline che contiene un bug noto non è il controllo
   adatto a provare la neutralità di un semplice spostamento di moduli.

**Accettazione:** dossier baseline riutilizzabile da P23; tutti gli R1–R8 hanno
evidenza green e i rischi aggiuntivi hanno fix oppure decisione misurata ammessa
dal relativo task. Le metriche di ammissione distinguono rifiuti da lavoro utile.

### P20 — Sostituire i contratti sovraccarichi con port piccoli

**Dipendenze:** P19. **File:** `InvocationEnqueuer`, scheduler queue,
`InvocationService`, lifecycle, capacità, governor e metriche.

1. Elenca i metodi davvero consumati da ciascun modulo tramite context/import.
   Definisci port distinti per ammissione/capability async, pianificazione retry,
   dispatch e capacità. Un booleano `enabled()` non può più significare
   contemporaneamente queue attiva, async disponibile e capacità acquisita.
2. Introduci un task immutabile con ID e dati necessari al dispatch; non esporre
   il record mutabile. Le queue notificano eventi expired/removed/rejected al
   lifecycle attraverso un port, senza `markTimeout`, `settle` o release diretti.
3. Fai chiamare agli scheduler il dispatch port; rimuovi la dipendenza da
   `InvocationService`, che rimane orchestrazione del percorso API.
4. Fai consumare al governor un'interfaccia read-only `InvocationObservations`
   con semantica delle durate documentata, non l'API di registrazione `Metrics`.
   Autoscaler e governor riusano osservazioni e identità, mantenendo le proprie
   politiche e i propri periodi di controllo.
5. Migra un consumatore per volta usando adattatori temporanei; esegui i test
   del consumatore e del lifecycle a ogni passaggio. Rimuovi gli adattatori
   obsoleti prima di P21, senza mantenere due percorsi terminali permanenti.

**Accettazione:** nessun modulo conclude direttamente record interni o rilascia
capacità per nome; nessuna queue chiama il servizio HTTP. Le invarianti e i
risultati di P19 restano invariati, salvo difetti nuovi riprodotti e corretti.

### P21 — Estrarre control-plane-spi e rimuovere dipendenze dalle implementazioni

**Dipendenze:** P20. **File:** `settings.gradle`, build core/moduli,
nuovo `platform/control-plane-spi`, ArchUnit e runtime-config extension.

1. Crea il progetto Gradle obbligatorio `:control-plane-spi`, distinto dai moduli
   opzionali scoperti sotto `platform/modules`. Inserisci soltanto i contratti
   di P20, viste lifecycle e provider/replica DTO consumati realmente dai moduli.
2. Lascia nel core `ExecutionLifecycle`, record/store, capacità mutabile,
   implementazioni di osservazioni e registry. Lo SPI non contiene Caffeine,
   controller, executor, registry mutabili né dipendenze Web/autoconfigurazioni.
   Può dipendere da `common` per modelli condivisi senza creare il verso inverso.
3. Sposta nello SPI il contratto `RuntimeConfigExtension` e i soli DTO necessari.
   L'adapter e la configurazione mutabile della sync queue restano nella queue;
   runtime-config conserva endpoint e implementazione. Elimina il compile-only
   verso l'implementazione runtime-config e prova il caso modulo assente.
4. Cambia i moduli da `implementation(project(':control-plane'))` a dipendenze
   dei soli contratti/utility legittimi. Non spostare un'implementazione intera
   nello SPI per far compilare un singolo import: introduci il port mancante.
5. Mantieni core → moduli selezionati come dipendenza runtime per packaging;
   aggiorna fixture di test senza ricreare una dipendenza circolare nei task
   Gradle. I test d'integrazione dei moduli possono vivere nel core consumatore.
6. Aggiorna ArchUnit, dependency analysis, OpenAPI composition, metadata,
   autoconfiguration imports e hint AOT/native per i simboli effettivamente mossi.
   Usa preview di rename/move e verifica il diff contro source snapshot storici.

**Accettazione:** moduli compilabili contro SPI senza core implementation;
SPI privo di import di implementazioni. Build/runtime mantengono selezione,
conflitti e API dei moduli. Nessun nuovo ciclo di progetti o task Gradle.

### P22 — Consolidare bean e confini architetturali

**Dipendenze:** P21; sfrutta P10/P11.

1. Raccogli orchestrazione managed, wake-up e refresh in configurazione/package
   deployment del core attivata in presenza di un provider managed. Esponi
   al lifecycle un readiness port con implementazione immediata per LOCAL/EXTERNAL.
2. La configurazione minima non crea scheduler, pool di refresh, gate o client
   Kubernetes/Docker inutilizzati. `FunctionRegistry` rimane disponibile anche
   senza provider managed. Verifica condizioni e ordine delle auto-configurazioni.
3. Completa lo svuotamento della capacità mutabile da workload-metrics. Mantieni
   osservazioni/metriche dove hanno consumatori; elimina API ponte non usate.
4. Rafforza i test di architettura: soltanto l'owner modifica lo stato terminale;
   moduli non importano store/record/service/capacità concreta; SPI non importa
   core/moduli; governor non registra meter; niente cicli di package/progetto.
   Per una regola negativa dimostra che rileva una violazione controllata,
   senza commettere il sorgente volutamente scorretto.
5. Registra l'ADR finale con diagramma dei progetti, ownership dei bean e motivo
   delle separazioni mantenute. Chiudi esplicitamente la valutazione degli
   accorpamenti queue, governor, provider e dei JAR non creati della sezione 2.

**Accettazione:** ogni risorsa asincrona ha un solo owner Spring/runtime;
start/stop dei profili minimi non lascia task. La matrice della sezione 8
verifica comportamento, non soltanto presenza di bean.

## 7. Verifica finale, soak e consegna

### P23 — Verificare comportamento, prestazioni e artefatti finali

**Dipendenze:** P22.

1. Esegui la matrice della sezione 8, includendo errori, callback tardive e
   rimozioni; compila JVM e native con la toolchain del repository. Mantieni
   separati fallimenti ambientali e regressioni, ma non dichiarare superato
   un controllo non eseguito.
2. Confronta la versione con SPI con P19, e la campagna complessiva con P00.
   Non confrontare solo richieste/sec totali: usa successi, lavoro ammesso,
   payload e percentuale di rifiuti. Le nuove protezioni possono rifiutare
   correttamente carico che prima saturava memoria senza limiti.
3. Investiga una regressione ripetibile oltre il 5% di throughput utile o
   p95/p99 a parità di lavoro nel range supportato; considera la variabilità
   prima di attribuirla. Per soli movimenti strutturali è un blocco finché
   non spiegata e rimossa. Un costo di correttezza indispensabile va quantificato
   e documentato, non occultato aumentando i limiti.
4. Non reintrodurre tuning già respinti senza dati nuovi: queue a monitor unico,
   batch maggiori o timeout del pool più corto non sono automaticamente migliori.
   Non usare soglie wall-clock fragili come assert della normale CI.
5. Verifica spec OpenAPI generata, config binding/validation, Helm/Compose,
   avvio HTTP, stato/errori e spegnimento sui profili supportati.

**Accettazione:** risultati completi e riproducibili; nessun test rosso ignorato,
nessuna dipendenza involontaria dal provider o da un modulo non selezionato.
Non avviare il soak finale se rimangono perdite note nei profili da misurare.

### P24 — Soak controllato e attribuzione della memoria

**Dipendenze:** P23. **Owner infrastruttura:** NanoLab, in checkout separato.

1. Leggi le istruzioni del checkout NanoLab prima di modificarlo. Implementa o
   riusa un profilo di carico costante di circa 90 minuti per revisione, con
   configurazione salvata e inspect/plan prima del run. Se manca supporto soak,
   realizza il task/scenario in NanoLab; non introdurre provisioning nei test Java.
2. Per la domanda storica confronta **`e35405ee`** con il candidato finale sullo
   stesso ambiente. Conserva `1d9e2f55` come eventuale terzo braccio forense per
   attribuire il problema pre-fix; è distinto dalla baseline corretta P19.
   Non sostituire la baseline con v0.20.0 o attribuire ogni differenza al solo fix R1.
3. Blocca immagine/digest, moduli, CPU/RAM, heap/GC, runtime, numero funzioni,
   payload, flag warm e rate. Usa il carico moderato SYNC senza chiavi del
   quesito originale come scenario principale. Registra offered/admitted,
   successi, errori, retry e replay; una differenza nel lavoro utile rende il
   confronto di memoria non direttamente equivalente.
4. Prima della durata lunga esegui i profili brevi sotto. Non mischiare churn,
   replay e workload storico in una media unica. Dopo il tratto costante ferma
   il traffico e osserva il drain oltre le finestre di retention pertinenti;
   il drain è aggiuntivo ai 90 minuti di carico.
5. Raccogli heap post-GC a checkpoint controllati, GC log, RSS, composizione
   cgroup, direct buffer, thread, socket, pending HTTP, code refresh/timer,
   live/key/outcome/byte stimati, owner ritirati e numero meter. Se forzi GC,
   verifica che sia avvenuta e separa le pause dalle misure di latenza ordinaria.
   Per native usa strumenti compatibili e non fingere metriche JVM assenti.
6. Misura separatamente control plane, proxy e processi SDK. Su crescita post-GC
   o plateau sospetto raccogli istogramma/dump e identifica i retainers;
   registra object population, owner, età attesa e motivo della permanenza.
7. Per finestre 30 secondi, 5 minuti e 30 minuti verifica popolazioni dopo più
   cicli. Se la configurazione le cambia, allunga la prova. Un plateau a
   `rate × 30 minuti × payload` di lavoro già concluso resta un difetto.

**Accettazione:** nessuna popolazione priva di owner cresce con la durata o
con la storia dei nomi; dopo drain rimane solo stato utile entro i limiti e
retention documentati. Una crescita richiede identificazione degli oggetti e
ritorno al task proprietario, poi ripetizione del profilo coinvolto. Non chiudere
con «aumentare la RAM nel chart» senza attribuzione. Se il confronto storico
resta inconcludente, dichiararlo e distinguere i bug corretti dalla causa non provata.

### P25 — Chiudere piano, documentazione e passaggio di consegne

**Dipendenze:** P24.

1. Aggiorna `docs/control-plane.md`, ADR, documentazione SDK, proprietà/Javadoc,
   OpenAPI core/frammenti, Helm/Compose e note di compatibilità interessate.
   Pubblica nel repo un consuntivo con link a evidenze e revisioni.
2. Per ogni riga della tabella di copertura indica task, fix/decisione, test
   red/green, misura e stato finale. P14/P15 richiedono dati anche quando si
   conserva la struttura esistente. Nessuna riga viene chiusa per sola intuizione.
3. Elenca limiti reali rimasti: per esempio cancellazione remota cooperativa,
   stime di heap conservative e impossibilità di uccidere thread Python in sicurezza.
   Devono essere limiti del contratto, non perdite riproducibili lasciate aperte.
4. Esegui diff/scope check nel checkout corretto. Verifica che i file sperimentali
   preesistenti non siano stati alterati dalla campagna e che non restino adapter
   temporanei, test disabilitati o configurazioni di benchmark attive nei default.
5. Se restano controlli bloccati da ambiente o accesso, consegna gli artefatti
   già verificati e indica esattamente il comando mancante. Stato campagna:
   **incompleta**, non «tutto risolto».

**Accettazione:** un altro agente può ricostruire cosa è cambiato, perché,
come è stato provato e quali artefatti usare, senza leggere la conversazione.

## 8. Matrice di verifica e comandi di partenza

I comandi sono punti di partenza verificati rispetto alla struttura del repo
al momento della stesura. I nomi dei nuovi test vanno scelti durante P00.
Esegui test mirati dopo ciascun task; la matrice completa serve ai gate P19/P23,
non va ripetuta dopo ogni modifica di documentazione.

```bash
# Suite core: usare --tests '<package.ClasseTest>' per il task corrente.
./gradlew :control-plane:test

# Individuare i task reali dei moduli selezionati prima di costruire filtri.
./gradlew projects -PcontrolPlaneModules=all
./gradlew tasks --all -PcontrolPlaneModules=sync-queue,runtime-config

# Build dei profili. Ogni selezione richiede un'invocazione Gradle separata.
./gradlew :control-plane:bootJar -PcontrolPlaneModules=none
./gradlew :control-plane:bootJar -PcontrolPlaneModules=async-queue
./gradlew :control-plane:bootJar -PcontrolPlaneModules=sync-queue,runtime-config
./gradlew :control-plane:bootJar -PcontrolPlaneModules=container-deployment-provider
./gradlew :control-plane:bootJar -PcontrolPlaneModules=all

# SDK Java e Java-lite.
./gradlew :sdks:java:test :sdks:java-lite:test

# Suite completa e build: runtime container richiesto dove previsto.
./gradlew build
./gradlew test

# Native: usare lo script/toolchain del repo, registrando la selezione moduli.
scripts/native-java-image.sh control-plane
```

Per Python esegui `python -m pytest tests` dalla directory `sdks/python` in un
ambiente con SDK e dipendenze test installati. Per Go esegui `go test ./...`
da `sdks/go`. Per JavaScript esegui `npm test` da `sdks/javascript` dopo aver
installato le dipendenze secondo il lockfile; lo script compila i test TypeScript
e usa `node --test`. Non sostituire questi test con il solo harness Java.
Le dipendenze non disponibili sono un problema di ambiente da registrare,
non una prova di correttezza o un motivo per cancellare i test.

| Profilo | Verifiche obbligatorie |
|---|---|
| Core `none` | LOCAL/EXTERNAL, limiti attivi senza queue, retry, nessun bean managed inutilizzato, stop |
| Async queue | accodamento/rifiuto, polling, replay, retry, peso outcome, rimozione con lavoro queued |
| Sync queue, senza runtime-config | compilazione/avvio e assenza dipendenza nascosta dall'estensione |
| Sync queue + runtime-config | enable/disable e modifica limiti durante lavoro, fairness, timeout waiter, release |
| Offload con core minimo e con queue compatibile | risultati/failure, waiter discordanti, saturazione, rimozione, cleanup |
| Autoscaler e concurrency-control separati e insieme | osservazioni indisponibili/stale, backend bloccato, progressi indipendenti, generazioni |
| Provider container | proxy, limiti byte/deadline, deprovision parziale, recovery, stop senza cancellazione dei container persistenti |
| Provider Kubernetes | deployment lifecycle, desired/ready, wake-up, errori/timeout provider, removal; E2E NanoLab |
| Default e `all` | OpenAPI/metadata, wiring, conflitti di selezione; `all` non copre sync e container esclusi dai default |
| SDK nei linguaggi supportati | payload/callback cap, saturazione, timeout, lavoro realmente attivo, shutdown |
| JVM/native | packaging, binding, hint AOT, avvio HTTP, invocation/replay e arresto sul minimo e su un profilo managed |

Conserva anche test negativi del selettore: due queue in conflitto, due provider
in conflitto, `none` combinato con altro modulo e ID sconosciuti devono fallire
come previsto. La suite core non sostituisce test reali della selezione sync o
container; `all` preferisce i moduli compatibili di default.

**Profili brevi prima del soak** — usa clock virtuali nei test unitari per le
retention; usa tempo reale e HTTP nei profili di integrazione. Durata sufficiente
al fenomeno, con timeout massimo esplicito e raccolta delle popolazioni al drain.

| Profilo | Carico / fault injection | Risultato da verificare |
|---|---|---|
| T1 | SYNC unkeyed, funzioni fisse, carico moderato | baseline di latenza/allocazioni e stato utile trattenuto |
| T2 | ASYNC/keyed con payload piatti, profondi, larghi e grandi | budget outcome conservativo, nessun redispatch su evizione |
| T3 | stessa chiave, waiter brevi/lunghi, offload on/off | un risultato globale, attese isolate, live/future chiusi |
| T4 | backend error/slow, retry, toggle sync, scadenza amministrativa | lease corretti, cancellazione locale, cap anche senza queue |
| T5 | nomi/endpoint nuovi, remove/re-register con callback sospese | stato storico, meter, pool e proxy tornano al livello atteso |
| T6 | un provider replica bloccato, invalidazioni e wake-up ripetuti | loop indipendenti, code/timer limitati, no resurrezione |
| T7 | input/output/callback grandi e client lenti | limiti a ogni hop, niente buffer o task illimitati |
| T8 | timeout ripetuti dei runtime e start/stop | lavoro reale entro cap, executor/client chiusi |
| T9 | stop del traffico dopo ogni caso | quote live a zero; cache residue soltanto entro retention/policy |

## 9. Criteri di arresto e ripresa

- **Prima di estrarre moduli:** fermati se P19 non è green o manca una baseline
  utilizzabile. Correggi il task proprietario; non nascondere il difetto nel move.
- **Durante una migrazione:** se serve esporre record mutabili nel nuovo SPI,
  torna a P20 e completa il port. Non indebolire ArchUnit per far passare il build.
- **Durante la verifica:** risultato terminale incoerente, redispatch della stessa
  chiave, quota negativa, cap aggirato o stato perso su errore sono blocchi di
  correttezza, indipendentemente dal throughput ottenuto.
- **Durante il soak:** arresta il caso se rischia OOM o perde progresso; salva
  metriche/evidenze prima del teardown e analizza. Un run interrotto non è un
  soak superato; una prova da 90 minuti richiede tempo reale, non una stima.
- **Alla ripresa:** leggi STATO, verifica SHA/diff, ripeti solo verifiche rese
  necessarie da nuove modifiche o risultati mancanti, quindi riparti dal primo
  task incompleto. Non ricominciare automaticamente l'intera campagna.
