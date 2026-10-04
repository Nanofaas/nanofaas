# One-shot decentralizzato per NanoFaaS

Data: 2026-10-04

Stato: specifica approvata per la stesura del piano il 2026-10-04, comprese le revisioni su librerie API, assenza di compatibilità DFaaS e dimensionamento temporale. Implementazione non avviata.

Base NanoFaaS: `a62a743f16205b3d4d3ce2945f857a88c559d83e` (`origin/main` osservato durante la progettazione).

Riferimento algoritmico: branch `feat/uv-migration-and-extended-tests` di DFaaSOptimizer, commit `71899f7`. Il riferimento non è il suo branch `main`. Le campagne devono registrare il commit completo di entrambi i progetti.

## 1. Obiettivo e decisioni concordate

Implementare su nodi NanoFaaS reali l'asta one-shot del branch di DFaaSOptimizer, usando una previsione del traffico in ingresso per decidere repliche ed esecuzione locale, inoltro ai vicini oppure invio al cloud. Il primo confronto deve isolare l'asta senza ricerca locale PG; quest'ultima sarà un'estensione successiva dello stesso solver locale.

Sono decisioni concordate:

- Estendere il modulo `offload` e usare `p2p-discovery` per discovery e messaggi tra vicini.
- Separare i contratti P2P e di previsione nelle librerie `p2p-api` e `forecasting-api`; mantenere in `control-plane-spi` i contratti propri del control plane.
- Operare quasi sincronicamente, con periodi configurabili scelti dopo aver misurato la durata dell'asta, che deve essere di molto inferiore al periodo. Un minuto non è un valore predefinito né un requisito. Un'asta contiene più round: one-shot non significa un solo messaggio o un solo round.
- Aggiungere un modulo di previsione del carico con un metodo semplice e un provider esterno. Negli esperimenti il provider esterno riceve il carico futuro dalla traccia che alimenta il generatore.
- Imporre il vincolo one-hop, riprendendo e adattando le idee di DFaaS ai meccanismi nativi di NanoFaaS, senza richiedere compatibilità con header o protocollo DFaaS.
- Assumere un cloud raggiungibile, con capacità sufficiente e funzioni disponibili.
- Allocare memoria per replica e mantenere una sola esecuzione attiva per istanza. Il numero delle repliche è controllato dall'algoritmo.
- Usare funzioni sperimentali warm in Rust, con lavoro CPU/memoria significativo e risultati verificabili. Rust è una scelta di deployment, non una soluzione provvisoria in attesa di migrazione.
- Portare il solver locale DP in Java; usare Python come riferimento di correttezza.
- Calibrare prima degli esperimenti one-shot. Calibrazione e campagne sono workflow distinti NanoLab, composti da task Sonata: si sviluppano e verificano prima su Multipass; gli esperimenti finali Azure sono un lavoro successivo separato.

Le regole operative nelle sezioni successive completano queste scelte, comprese granularità dei flussi, transizione tra epoche e comportamento degradato. I dettagli di implementazione sono sviluppati nei piani collegati.

## 2. Ambito e separazione del lavoro

Questa è la specifica di integrazione: definisce contratti e criteri di accettazione comuni a NanoFaaS, NanoLab e alle funzioni sperimentali. Il piano di implementazione dovrà separare solver e previsione, protocollo e attuazione, calibrazione e campagna, conservando questi contratti.

L'esecuzione è divisa in tre fasi ordinate: A, tutta l'implementazione NanoFaaS, inclusi test unitari, integrazione, prove distribuite locali, funzioni sperimentali e telemetria; B, sviluppo e verifica dei workflow NanoLab/Sonata su VM locali Multipass; C, esperimenti finali Azure come lavoro successivo separato. Il piano di implementazione attuale copre A e B, non l'esecuzione di C. La fase A deve poter essere verificata senza NanoLab, usando fixture esplicitamente sintetiche al posto dei profili calibrati. La fase B deve eseguire realmente i workflow su VM Multipass e non limitarsi a mock o dry-run. Nessuna delle due richiede un account cloud. I punti di passaggio e i due piani sono descritti in [One-shot implementation plan](../plans/2026-10-04-one-shot-implementation.md).

Misure e calibrazioni ottenute su Multipass sono reali ma valide solo per l'ambiente locale misurato. Devono dichiarare provider, fingerprint di host/VM e finalità di verifica dei workflow; non si trasferiscono a una campagna Azure cambiando un'etichetta. La fase C ripeterà calibrazione e qualificazione temporale sul target prima dei confronti finali.

La prima versione copre invocazioni sincrone di funzioni gestite, disponibili sui nodi abilitati e sul cloud. Le modalità asincrone, la distribuzione automatica di nuove funzioni, aste gerarchiche, previsioni CPU/memoria e ricerca locale PG sono fuori ambito. Le modalità di offload esistenti restano disponibili per le funzioni non gestite da one-shot.

Il protocollo non introduce un coordinatore globale dell'ottimizzazione. NanoLab coordina gli esperimenti e raccoglie i risultati; ciascun nodo decide usando parametri locali e informazioni dei vicini. Le assunzioni di fiducia del P2P esistente restano valide: gli header non costituiscono autenticazione.

## 3. Modello, unità e invarianti

Per nodo `i` e funzione `f`:

| Simbolo | Significato | Unità |
| --- | --- | --- |
| `lambda[i,f]` | Carico esterno previsto al nodo di origine | richieste/s |
| `D[i,f]` | Tempo medio di servizio calibrato per replica | secondi/richiesta |
| `U[f]` | Utilizzazione massima ammessa dal modello | rapporto in `(0,1]` |
| `M[f]` | Memoria allocata a ciascuna replica | MiB interi positivi |
| `B[i]` | Budget utilizzabile dalle repliche sul nodo | MiB |
| `r[i,f]` | Repliche pronte assegnate alla funzione | intero non negativo |
| `x[i,f]` | Traffico esterno eseguito all'origine | richieste/s |
| `y[i,j,f]` | Traffico inviato direttamente dall'origine `i` al vicino `j` | richieste/s |
| `z[i,f]` | Traffico inviato direttamente dall'origine al cloud | richieste/s |

Per il piano applicato valgono:

```text
lambda[i,f] = x[i,f] + sum_j y[i,j,f] + z[i,f]
x[i,f] + sum_j y[j,i,f] <= r[i,f] * U[f] / D[i,f]
sum_f r[i,f] * M[f] <= B[i]
```

La notazione runtime distingue il cloud dagli scarti/errori osservati. DFaaSOptimizer usa anche variabili intermedie come `omega` e `z` con significati legati al sottoproblema: il porting deve conservare quei significati internamente e documentare la mappatura verso il piano runtime. Non si deve cambiare l'obiettivo rinominando una variabile intermedia come flusso cloud.

I coefficienti dell'obiettivo, i costi e le regole d'asta vengono dal branch fissato. Non si sostituisce l'utilità locale con un obiettivo globale. Gli header, il ledger e l'esecuzione devono impedire che `y[j,i,f]` diventi nuovo traffico da inoltrare.

`B[i]` esclude sistema operativo, control plane, trasporto, telemetria e margine operativo. La memoria configurata è un vincolo di allocazione, non una promessa sul RSS misurato. La capacità lineare in `r` è una previsione del modello valida solo nell'intervallo verificato dalla calibrazione: contention CPU e co-locazione vanno misurate.

### Flussi frazionari

Il backend DP Python supporta domini interi per le variabili decisionali previste dai suoi modelli. Non deve ricevere tassi frazionari arrotondati implicitamente.

Si propone una granularità di campagna esplicita `q`, espressa in richieste/s per unità intera del solver. Per conservare il modello si convertono insieme carico e capacità: `lambda_solver = lambda/q`, `D_solver = D*q`; in uscita si moltiplicano i flussi per `q`. I coefficienti monetari o di utilità per unità, dove presenti, devono essere trasformati coerentemente e verificati per modello; non basta scalare il solo carico.

La campagna oracle deve generare tassi rappresentabili sulla griglia scelta. Per EWMA si propone di ottimizzare `floor(lambda/q)` unità e destinare il resto previsto direttamente al cloud all'origine, rendendo visibile tale quantità nelle metriche. La proprietà di ottimalità riguarda il problema discretizzato, non il problema continuo. Dimensione di `q`, trasformazioni e errore di discretizzazione sono salvati nel manifest e coperti da test di equivalenza; non sono parametri impliciti del codice.

## 4. Componenti e confini

| Componente | Responsabilità |
| --- | --- |
| Modulo `forecasting` proposto | Snapshot di carico futuro da EWMA o provider esterno |
| Modulo `offload` esteso | Epoche, asta, ledger, solver, costruzione e applicazione dei piani |
| Modulo `p2p-discovery` | Identità e vicini attivi, trasporto dei messaggi |
| Libreria `p2p-api` proposta | Contratti pubblici minimi per conoscere i vicini e inviare/ricevere payload |
| Libreria `forecasting-api` proposta | Contratto di lettura degli snapshot di previsione e relativi tipi pubblici |
| `control-plane-spi` | Contratti propri del control plane, inclusi accesso al catalogo e controllo delle repliche |
| Runtime gestito e SDK | Readiness, dispatch verso slot disponibili, concorrenza fisica per replica |
| NanoLab e Sonata | Provisioning Multipass e verifica locale dei workflow; calibrazione, carico, raccolta e cleanup. Impiego Azure nel lavoro sperimentale successivo |

Non sono consentite dipendenze dirette tra le implementazioni dei moduli opzionali. I contratti condivisi sono organizzati per funzionalità, senza far confluire tutte le interfacce in `control-plane-spi`:

- `p2p-discovery` implementa i contratti di `p2p-api`; `offload` dipende da questa libreria per conoscere i vicini e scambiare payload, senza importare le classi interne del P2P.
- `forecasting` implementa il contratto di `forecasting-api`; `offload` dipende da questa libreria per leggere le previsioni, senza conoscere EWMA, caricamento oracle o altri dettagli del provider.
- Per catalogo, capacità e controllo delle repliche si continuano a usare i contratti pertinenti di `control-plane-spi`, incluso `ManagedReplicaControl`.

`p2p-api` e `forecasting-api` sono piccole librerie Java, non servizi o nuovi processi. Contengono soltanto interfacce e tipi pubblici necessari ai consumatori; non contengono connessioni, thread, stato operativo, algoritmi o autoconfigurazione Spring. Non dipendono dalle implementazioni opzionali né dall'implementazione del control plane. La selezione dei moduli e il wiring collegano i contratti ai provider disponibili: one-shot richiede entrambi i provider, mentre il normale offload non deve richiederli per funzionare.

Solver e negoziazione lavorano fuori dal percorso delle richieste e dagli event loop di trasporto, con esecuzione e memoria limitate. Il percorso HTTP legge un piano immutabile e gestisce ammissione, routing e contatori.

Abilitare one-shot richiede P2P attivo, forecasting disponibile, profilo di calibrazione compatibile, destinazione cloud e supporto al controllo delle repliche. Configurazioni incomplete devono essere rifiutate esplicitamente.

## 5. Previsione e sorgente oracle

Ogni snapshot contiene origine, funzione e sua generazione, inizio/fine intervallo, tasso, provider, revisione e istante di produzione. Un valore assente o scaduto non equivale a zero.

Il provider EWMA usa esclusivamente gli arrivi esterni originali. Sono esclusi gli arrivi inoltrati dai peer e i tentativi interni dovuti a retry. Il contatore di dispatch esistente non è quindi una sorgente sufficiente. Coefficiente EWMA, finestra osservata e periodo previsto sono espliciti e riproducibili.

Il provider esterno accetta la traccia futura generata per gli esperimenti, con le stesse origini, funzioni e finestre del generatore. Un aggiornamento produce una nuova revisione; non modifica lo snapshot già congelato per un'asta. Si propone di caricare la traccia tramite un endpoint amministrativo del modulo, con caricamento e lettura della revisione corrente, limiti dimensionali e validazione atomica. La forma API precisa sarà definita nel piano, mantenendo questi requisiti.

Il piano registra la revisione effettivamente utilizzata. Si misurano separatamente carico programmato, carico realmente emesso dal generatore e arrivi osservati al gateway: conoscere la traccia non garantisce che il generatore riesca a emetterla nei tempi previsti.

## 6. Solver locale Java

Il riferimento è `models/local_sp.py`, con il kernel `minimize_replica_costs` in `plasma/core/sbm.py`. Il primo costruisce alternative e costi per funzione; il secondo risolve il multiple-choice knapsack sul budget RAM. I cicli principali sono Python, anche se usano array NumPy: non è necessario un binding a un kernel nativo esterno.

Il solver Java usa array primitivi e un contratto puro: input locale immutabile, risultato con allocazioni, repliche, obiettivo, stato e diagnostica di lavoro/tempo. Non legge il registro delle funzioni né invia messaggi. Deve preservare:

- obiettivi e domini dei modelli effettivamente usati da one-shot;
- carico in ingresso già impegnato, limiti di offload e repliche fissate;
- tolleranze numeriche, ordine delle funzioni e delle alternative, gestione deterministica dei pareggi;
- calcolo diretto per `LSPr_x` e casi con repliche fissate;
- riduzione esatta degli stati RAM tramite massimo comune divisore delle memorie, senza arrotondamenti arbitrari.

Il nucleo DP usa due vettori dei costi e le scelte necessarie per ricostruire la soluzione. Il lavoro cresce con budget RAM ridotto e numero di livelli di replica; la memoria di ricostruzione cresce anche con il numero di funzioni. Prima delle allocazioni si verificano overflow, dimensione massima degli stati e budget di lavoro. Durante il calcolo si verifica cooperativamente la scadenza.

Gli stati distinguono almeno soluzione ottima, problema non ammissibile, input non supportato, limite di dimensione e scadenza. Non si restituisce una soluzione incompleta come ottima. La configurazione della campagna deve rientrare nel dominio supportato; non è previsto un fallback MILP nel deployment iniziale. Python/Pyomo rimangono strumenti offline di verifica.

Il core può essere condiviso con PG in futuro, ma questa versione implementa e valida soltanto le varianti necessarie all'asta senza ricerca locale. Compatibilità JVM e immagine GraalVM sono requisiti: le prestazioni devono essere misurate sul formato effettivamente usato in campagna.

## 7. Epoche e protocollo d'asta

Il periodo di controllo `T` è la durata dell'intervallo di carico per cui si applica un piano. Si sceglie dopo aver misurato l'asta distribuita sulle configurazioni sperimentali previste, non fissandolo a un minuto. Il requisito è `T_asta << T`; completare appena prima della fine del periodo non è sufficiente.

`T_asta` comprende pianificazione locale iniziale, tutti i round, ricalcoli, scambio di messaggi, attese del protocollo e chiusura delle decisioni negoziate. Si misura per nodo con clock monotono; per ogni esecuzione si riporta anche la durata complessiva dall'avvio concordato all'ultima chiusura richiesta, tenendo conto dello skew misurato. Non si usa la somma dei tempi CPU dei nodi né soltanto il tempo del kernel DP. Se il protocollo aspetta una finestra fissa anche dopo l'ultimo bid utile, quell'attesa fa parte del tempo operativo dell'asta.

Prima dei confronti si misura la distribuzione di `T_asta` con ripetizioni, variando almeno numero di nodi e funzioni, topologia, intensità/sbilanciamento del carico e condizioni di rete. Si includono le condizioni di contesa con l'esecuzione delle funzioni. Il manifest dichiara un quantile alto da usare per il dimensionamento, un margine e una frazione massima piccola `epsilon_asta`. Il criterio è `quantile(T_asta) + margine <= epsilon_asta * T`. Per esempio, una soglia del 5% richiederebbe un periodo almeno venti volte il tempo dimensionante: è un esempio di rapporto, non una soglia già concordata. Si riportano anche massimi, numerosità dei campioni e sforamenti, senza presentare il quantile come un limite garantito.

La durata viene misurata prima di scegliere `T`; tagliare artificialmente l'asta a `epsilon_asta*T` non dimostra che il requisito sia soddisfatto. Le esecuzioni interrotte per deadline sono campioni censurati e degradazioni, non completamenti rapidi. Il deadline operativo è distinto da `T` e viene dimensionato usando le misure, rimanendo una piccola parte del periodo. Gli sforamenti in campagna sono registrati e gestiti con la modalità degradata, senza allungare automaticamente il periodo durante un confronto.

Si misura separatamente anche il tempo dalla preparazione del piano fino alla capacità pronta per l'attivazione, includendo negoziazione, eventuale drenaggio, avvio e readiness delle repliche. Le attività sovrapposte non si sommano due volte. La finestra di previsione deve essere disponibile con un anticipo sufficiente a questo tempo complessivo più il margine operativo. Un'asta breve non dimostra da sola che le repliche possano essere preparate in tempo. Nel profilo iniziale non si sovrappongono negoziazioni di epoche diverse sullo stesso nodo.

La scelta di `T` deve inoltre restare significativa per la dinamica della traccia: non si allunga il periodo fino a nascondere variazioni di carico importanti. Se non esiste un periodo che rispetti sia il rapporto temporale sia la risoluzione del carico richiesta, la configurazione sperimentale non è qualificata e va rivista.

Ogni nodo attraversa preparazione, negoziazione, finalizzazione, attivazione e drenaggio. I messaggi contengono versione di schema, identità e incarnazione del mittente, epoca, round, ID idempotente, funzione/generazione, revisione del piano e intervallo di validità. La revisione della previsione è locale e non deve coincidere tra nodi.

Lo snapshot delle funzioni, dei parametri e della previsione rimane stabile durante l'asta. Le risposte con epoca, incarnazione o revisione incompatibili sono scartate. I deadline si misurano con clock monotono; gli intervalli condivisi usano il clock sincronizzato delle VM. Il manifest fissa uno skew massimo ammesso e un margine di attivazione; il workflow verifica lo skew prima della campagna e lo monitora. Superare la soglia impedisce di attivare nuove assegnazioni peer: il nodo drena quelle già ammesse e usa la modalità degradata descritta sotto, marcando il run come non conforme alle assunzioni temporali.

Si riproducono le fasi del branch: pianificazione locale iniziale, capacità residua, offerte, bid, assegnazione e aggiornamento delle repliche con i flussi fissati. Per la variante one-shot si mantiene la politica del branch che non rimpiazza assegnazioni esistenti nei round successivi. Le funzioni Python che leggono array globali del simulatore devono essere tradotte in decisioni locali e messaggi; non diventano chiamate a un servizio centrale.

Il venditore è autorità sulla propria capacità: registra ogni assegnazione prima di confermarla e non vende due volte lo stesso budget. Ritrasmettere un bid o una conferma non aggiunge una seconda assegnazione. Una conferma persa può lasciare capacità inutilizzata, ma non autorizza il compratore a inviare traffico senza conferma.

Ogni round ha una finestra e un termine espliciti; messaggi tardivi non modificano round chiusi. Il primo protocollo usa un massimo di round e un deadline d'asta comuni, distinti dalla durata del periodo, senza introdurre un algoritmo aggiuntivo di terminazione globale. Un nodo senza nuovi bid continua a rispondere fino alla chiusura. Terminazione per deadline, limite dei round e convergenza naturale devono essere distinguibili nei risultati.

Le assegnazioni negoziate sono provvisorie fino alla conferma finale di capacità pronta. Non confermare capacità basandosi solo su repliche richieste. In finalizzazione ciascun venditore conferma i propri impegni sostenibili; gli impegni non confermati non entrano nel piano applicato. Il compratore invia il residuo al cloud. La politica di riduzione in caso di readiness parziale deve essere deterministica e registrata; questa è una degradazione operativa, non un risultato dell'asta ideale.

## 8. Transizione tra epoche e guasti

Non si assume un cutover atomico globale. Ogni richiesta inoltrata porta epoca e assegnazione; il destinatario verifica che siano ancora accettabili. Le regole one-hop rimangono indipendenti dalla validità dell'assegnazione.

Si propone una transizione conservativa: la nuova ammissione usa il nuovo piano soltanto nell'intervallo concordato; le richieste già ammesse del vecchio piano terminano mantenendo le risorse necessarie. La preparazione deve verificare il massimo uso simultaneo di memoria tra vecchie repliche mantenute e nuove repliche in avvio. Non si assume che il solo rispetto dei due budget, separatamente, renda ammissibile la sovrapposizione.

Se manca spazio per preparare tutto il nuovo piano, si procede con drenaggio e attivazione parziale; all'origine, il traffico non ancora assegnabile va direttamente al cloud. Non si eliminano istanze occupate per far coincidere forzatamente il sistema con il nuovo piano. Il tempo e la quota di traffico in transizione sono risultati sperimentali da misurare.

Offerte e conferme scadute non si riutilizzano nell'epoca successiva. In caso di previsione mancante, solver fallito o pianificazione incompleta, si propone una modalità esplicita cloud per il nuovo traffico esterno non coperto da capacità locale verificata. Restano da onorare e drenare gli impegni già ammessi. Perdita di peer, riavvio o isolamento invalidano le nuove assegnazioni pertinenti; un riavvio cambia incarnazione e non ricostruisce un ledger da vecchi messaggi.

Un errore dopo l'inoltro non autorizza un secondo invio a un'altra destinazione: potrebbe duplicare un'esecuzione già avvenuta. I retry preesistenti devono mantenere destinazione, origine e marcatura one-hop, ed essere contati separatamente. Il timeout del chiamante non libera uno slot se l'handler sta ancora eseguendo.

## 9. Routing, quote e one-hop

Una quota è una capacità di ammissione per origine, destinazione, funzione e intervallo; non è una nuova replica né una promessa di latenza. Il piano contiene destinazioni confermate, tassi assegnati e limiti di burst. Si propone un routing deterministico pesato con ammissione per destinazione; il limite del venditore fa comunque autorità, perché un tasso medio da solo non impedisce burst o superamento della concorrenza.

L'eccedenza rispetto alle quote è inviata al cloud dall'origine prima di qualsiasi invio a un peer. I parametri di burst fanno parte del manifest. Il venditore riserva la capacità locale e applica i limiti dei singoli impegni in ingresso, evitando che il proprio traffico consumi senza controllo gli slot promessi ai vicini. Un peer che non può ammettere una richiesta restituisce un errore esplicito, senza inoltrarla. La prima versione non promette che l'asta elimini code, errori o variabilità stocastica del carico.

DFaaS è un riferimento per le idee di marcatura dell'inoltro, distinzione tra traffico esterno e ricevuto dai peer e identificazione del nodo di esecuzione. Non è richiesta interoperabilità con DFaaS: non si introducono alias, traduzioni o supporto ai suoi header `DFaaS-Node-ID` e `X-Server`. Questo non modifica il riferimento algoritmico a DFaaSOptimizer indicato in apertura.

- Si riusa `X-NanoFaaS-Offload-Hop` come marcatore nativo della richiesta già inoltrata, mantenendo il vincolo anche nel normale offload. Una marcatura presente ma invalida non può essere rimossa o interpretata come nuovo traffico esterno per consentire un ulteriore inoltro.
- Origine, epoca e assegnazione sono metadati NanoFaaS versionati, validati insieme alla marcatura di inoltro. Nella modalità one-shot, metadati mancanti, incoerenti o non validi causano un rifiuto esplicito, senza riclassificare la richiesta come traffico esterno. La loro assenza non rende invalide le richieste del normale offload, che continuano a usare il proprio contratto e a rispettare one-hop.
- Le risposte devono rendere identificabile il nodo di esecuzione attraverso metadati propri di NanoFaaS, preservati nel ritorno attraverso il nodo di origine. Nomi e formato saranno definiti nel contratto API e non sono vincolati a quelli DFaaS.
- Generatori di carico e verifiche NanoLab consumano il contratto NanoFaaS, senza adattatori di compatibilità DFaaS.

Sono ammessi `A -> B` e `A -> cloud`. Sono vietati `A -> B -> C` e `A -> B -> cloud`. Il destinatario esegue localmente oppure restituisce errore. Il cloud non partecipa all'asta come venditore edge ed è destinazione terminale.

## 10. Repliche, risorse e concorrenza

Si usano le risorse già presenti in `FunctionSpec` e il controllo delle repliche esposto da `ManagedReplicaControl`, con fencing sulla generazione della funzione. L'API esistente `PUT /v1/functions/{name}/replicas` è utile per strumenti e verifiche; il modulo usa il contratto interno, senza chiamate HTTP a sé stesso.

Per gli esperimenti si configurano richieste e limiti di memoria coerenti, normalmente uguali al taglio del modello, insieme a quote CPU esplicite. One-shot è l'unico proprietario delle decisioni sulle repliche per le funzioni selezionate: autoscaler interni, HPA o scritture concorrenti devono essere esclusi o rifiutati finché questa proprietà è attiva.

`FunctionSpec.concurrency` è un tetto per funzione sul nodo, non per replica. Impostarlo a uno limiterebbe anche molte repliche a una sola invocazione complessiva. La configurazione prevista usa `STATIC_PER_POD`, `targetInFlightPerPod=1`, un tetto di funzione sufficiente e `NANOFAAS_MAX_CONCURRENT_HANDLERS=1` nel runtime. La capacità utile dipende dalle repliche pronte e dai loro slot liberi; zero repliche pronte significa zero capacità locale ammissibile.

La selezione del backend deve conoscere gli slot occupati. Il round robin attuale del proxy container locale, da solo, può scegliere una replica occupata mentre un'altra è libera e non soddisfa il requisito. Il backend scelto deve dimostrare concorrenza massima uno per replica e utilizzazione delle repliche libere, anche con tempi di servizio diversi. La prima integrazione propone `container-local`, prima su VM Multipass per verificare i workflow e successivamente su VM Azure per gli esperimenti finali, associando esplicitamente nodo logico, budget e pool di istanze; altri backend richiedono la stessa verifica prima di essere confrontati.

## 11. Workflow di calibrazione: Multipass prima, Azure nel lavoro finale

Il workflow di calibrazione è precedente e indipendente dalla campagna one-shot. NanoLab esegue provisioning, deployment e raccolta tramite task Sonata con cleanup anche in caso di fallimento.

La fase B implementa e verifica questo workflow su Multipass, comprese prove di errore, artefatti, riuso e invalidazione. Le VM edge e la VM che rappresenta il cloud sono nodi logici locali; condividere l'host fisico va dichiarato nelle evidenze. Nella fase C lo stesso workflow sarà configurato e verificato sul target Azure e produrrà una nuova calibrazione per quel target. Non è richiesta la verifica Azure per completare B.

Per ogni profilo funzione/nodo, il workflow prepara immagine e input, attende readiness, esegue warmup e misura lavoro reale CPU/memoria con output verificato. Un semplice sleep non rappresenta il carico principale delle funzioni sperimentali. Si ripetono le prove per stimare media, dispersione, quantili e incertezza, con un criterio dichiarato di numerosità/stabilità.

Si distinguono:

- tempo dell'handler e tempo durante il quale la replica rimane occupata;
- attesa nel gateway e nel runtime;
- rete e latenza end-to-end;
- cold start e tempo necessario a rendere pronta una replica;
- timeout, cancellazioni ed esecuzioni ancora attive dopo la risposta al chiamante.

`D[i,f]` deve rappresentare l'occupazione media warm della replica che limita la capacità. Una misura dispatch-completion del control plane o un timer che termina al timeout del chiamante non sono automaticamente questa quantità. Campioni censurati e errori si riportano separatamente, senza attribuire loro durata nulla o successo.

La calibrazione verifica anche capacità e contention con più repliche e mix di funzioni. Il profilo dichiara l'intervallo di configurazioni in cui `r*U/D` è un modello accettabile e il relativo errore. Una campagna fuori da tale intervallo richiede nuova calibrazione o un modello rivisto.

L'artefatto risultante è immutabile e identificato da hash. Contiene commit e digest delle immagini, SDK/runtime, input, provider e fingerprint dell'ambiente, finalità (`workflow-validation` oppure `scientific-experiment`), tipo VM e CPU, quote CPU, memoria, backend, numero di repliche, co-locazioni, condizioni di warmup, statistiche, campioni e unità. Le latenze tra nodi sono misurate separatamente dal servizio. Un profilo Multipass misurato non è sintetico, ma la sua finalità e il suo fingerprint ne impediscono il riuso per qualificare Azure.

## 12. Verifica dei workflow e successiva campagna Azure

Un secondo workflow NanoLab/Sonata consuma un profilo esistente e ne verifica la compatibilità prima di avviare il carico. Non esegue una ricalibrazione implicita e non aggiorna `D` durante il confronto. Continua a raccogliere misure per evidenziare deriva e violazioni del profilo.

In fase B tutti i percorsi del workflow vengono verificati su Multipass con piccoli scenari: baseline, oracle, EWMA, raccolta, report, fallimenti e cleanup. Il report dichiara la finalità di verifica locale; non deve dimostrare il vantaggio scientifico dell'algoritmo per considerare implementato il workflow. Dimensionamento e avvio dei confronti finali Azure appartengono a un successivo piano sperimentale.

Prima dei confronti si eseguono prove preliminari per qualificare i tempi dell'asta distribuita e della preparazione del piano: su Multipass per verificare il workflow in fase B, poi nuovamente su Azure per la campagna finale C. Queste prove consumano la calibrazione delle funzioni già disponibile sullo stesso target e producono un artefatto separato con distribuzioni temporali, condizioni misurate e scelta motivata di periodo, anticipo e deadline. Non modificano il profilo di servizio. Ogni confronto richiede entrambi gli artefatti compatibili e mantiene fissi i parametri temporali scelti; cambiamenti a solver, protocollo, dimensione dello scenario o condizioni operative richiedono di verificarne nuovamente la validità. Il periodo locale non diventa automaticamente il periodo degli esperimenti Azure.

La topologia proposta usa una VM per nodo edge logico, una destinazione cloud distinta e sufficientemente dimensionata, e generatori separati quando necessario a non contaminare le misure. I test dei workflow usano VM Multipass dimensionate rispetto alle risorse dell'host; gli esperimenti finali useranno Azure. Collocare tutte le VM nello stesso host o datacenter non crea automaticamente una rete edge-cloud realistica: posizione, RTT, banda, risorse condivise ed eventuale emulazione di rete vanno dichiarati e verificati.

Il manifest fissa topologia, funzioni, immagini, profilo di servizio, artefatto di qualificazione temporale, coefficienti del modello, periodo `T`, quantile/margine ed `epsilon_asta`, anticipo, round e deadline, granularità dei flussi, burst, forecast, trace hash, seed, ripetizioni e warmup. La previsione oracle e il generatore condividono traccia e riferimento temporale.

Il confronto iniziale include una baseline locale con residuo al cloud e one-shot senza PG; oracle ed EWMA sono confronti separati sullo stesso carico. La successiva aggiunta di PG mantiene invariati profili e scenari per isolare il suo effetto.

Si raccolgono welfare calcolato con la stessa convenzione del riferimento, flussi previsti ed effettivi, traffico cloud, errori, latenza, code, repliche desiderate/pronte/occupate, RAM e CPU, tempo/memoria del solver, messaggi, round, durata dell'asta e rapporto `T_asta/T`, scadenze, tempo complessivo di preparazione e durata delle transizioni. Ogni invocazione originale è correlabile ai suoi eventuali tentativi e alla destinazione terminale. La verifica di conservazione distingue richieste originali, tentativi, completamenti ed errori, senza doppi conteggi.

Sonata gestisce dipendenze tra task, risorse e rilascio; NanoLab conserva manifest, log e risultati anche nei run falliti. Non si introduce un orchestratore shell parallelo al workflow.

## 13. Criteri di accettazione

Il controllo architetturale deve verificare che `offload` consumi P2P e previsioni tramite `p2p-api` e `forecasting-api`, che le due librerie non dipendano dalle implementazioni e che i contratti specifici di queste funzionalità non vengano aggiunti a `control-plane-spi`. Un profilo con il normale offload deve funzionare senza i provider P2P e forecasting; l'attivazione di one-shot senza tali provider deve essere rifiutata esplicitamente.

1. **Solver:** su fixture del branch e istanze generate ammissibili, Java e Python concordano su vincoli, obiettivo e scelta deterministica; il confronto con Pyomo ammette allocazioni diverse a parità di ottimo. Inclusi carico nullo, RAM insufficiente, input invalido, impegni in ingresso, pareggi, limite degli stati e scadenza. Le trasformazioni delle unità hanno test dedicati.
2. **Asta:** su trascrizioni deterministiche, le decisioni coincidono con one-shot Python senza PG, salvo degradazioni operative esplicitamente etichettate. Duplicati, riordino, perdita di messaggi e conferme tardive non sovrallocano capacità.
3. **Forecast:** oracle ed EWMA usano arrivi esterni; caricamenti atomici, revisione congelata, assenza/scadenza e discrepanze tra carico programmato ed emesso sono verificati.
4. **One-hop:** il marcatore nativo e la validazione dei metadati NanoFaaS impediscono qualsiasi secondo inoltro, anche in caso di valori invalidi, incoerenti o metadati one-shot mancanti. Il normale offload conserva il proprio comportamento one-hop. Le risposte riportano il nodo effettivo di esecuzione quando l'esecuzione è avvenuta; gli errori precedenti non inventano una destinazione di esecuzione. I test non richiedono supporto agli header DFaaS.
5. **Repliche:** sotto carico e durante scaling, ciascuna replica esegue al massimo un handler; repliche libere sono utilizzabili, readiness precede la capacità annunciata, il budget RAM include la transizione e nessun altro scaler modifica il piano.
6. **Guasti:** solver scaduto, peer perso, riavvio, clock fuori soglia e readiness parziale producono comportamento esplicito senza riuso di assegnazioni scadute né inoltri aggiuntivi.
7. **Calibrazione:** workflow completabile separatamente e verificato su Multipass in fase B, artefatto verificabile e rifiuto di profili incompatibili. Tempi di servizio, coda e rete non vengono confusi. Calibrazione Azure richiesta per gli esperimenti finali C, non per chiudere B.
8. **Campagna:** workflow riproducibile tramite NanoLab/Sonata, verificato su Multipass con conservazione, rilascio risorse anche su errore e confronti a parità di trace e profilo. Il budget temporale dell'intera pianificazione include solver, rete e preparazione delle repliche, non solo il kernel DP. Validazione scientifica finale e run Azure sono criteri della fase C separata.
9. **Separazione temporale:** periodo scelto sulla base delle prove preliminari, con durata dell'asta di molto inferiore a `T` secondo il rapporto dichiarato, anticipo sufficiente alla capacità pronta e risoluzione coerente con la traccia. Timeout e limite dei round non valgono come evidenza di completamento; gli sforamenti osservati nei confronti sono riportati e non nascosti modificando `T` in corso di esecuzione.

## 14. Riferimenti di implementazione

Percorsi NanoFaaS relativi al repository:

- `platform/modules/offload/` e `platform/modules/p2p-discovery/`.
- Nuove librerie previste: `platform/p2p-api/` e `platform/forecasting-api/`, con i soli contratti delle rispettive funzionalità.
- `platform/control-plane-spi/`, in particolare i contratti `OffloadGateway`, `ManagedReplicaControl` e `InvocationObservations`.
- `platform/common/`, modelli `FunctionSpec`, `ResourceSpec`, `ScalingConfig`.
- `platform/modules/concurrency-control/` e `platform/container-deployment-runtime/`.
- `sdks/rust/` e `openapi/core.yaml`.

Riferimenti esterni consultati, da fissare nel manifest delle verifiche:

- DFaaSOptimizer, branch e commit indicati in apertura: `decentralized_auction.py`, `one_shot_pg.py`, `models/local_sp.py`, `models/sp.py`, `plasma/core/sbm.py`, `run_faasmacro.py` e `tests/test_local_sp.py`.
- PDF di riferimento: `Decentralized_FaaS_coordination.pdf`, bozza fornita dall'utente. È contesto scientifico; in caso di differenza, il comportamento da riprodurre è quello del branch fissato.
- DFaaS: `dfaasagent/agent/loadbalancer/haproxycfgstatic.tmpl`, `haproxycfgnms.tmpl` e configurazioni correlate, come riferimento per le idee di one-hop e attribuzione del traffico, non come contratto di compatibilità.
- NanoLab: `packages/nanolab/src/nanolab/plans/offload_loadtest.py`, `packages/nanolab/src/nanolab/tasks/offload_loadtest.py` e gli scenari `scenarios-v2` come punti d'integrazione, non come workflow one-shot già esistenti.
- Sonata: contratti `Task`, `TaskInputs`, `TaskOutcome`, `Workflow` e risorse con cleanup.
