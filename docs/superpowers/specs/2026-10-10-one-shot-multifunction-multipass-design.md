# Campagna one-shot Multipass con cinque funzioni e round sincronizzati

## Scopo e precedenza

Revisione richiesta dall'utente il 2026-10-10 durante il Task 8 del progetto eterogeneo. Sostituisce il disegno scientifico a funzione singola, anello e 54 round da qualificare. I Task 1–7 già implementati sono infrastruttura riutilizzabile, non evidenza del nuovo esperimento. Nessuna misura scientifica precedente esiste: bootstrap-gate-01 e -02 sono interrotti durante provisioning; tutti i loro VM sono stati rilasciati. `nanofaas-stack` è stata eliminata definitivamente con autorizzazione esplicita e non va ricreata.

L'autorizzazione precedente a completare il progetto, considerarlo approvato e implementare in modalità native resta valida. Le nuove istruzioni dell'utente prevalgono sui parametri precedenti. Esecuzione inline; una sola review indipendente finale. Nessun merge/push di questo lavoro senza nuova richiesta.

## Topologia e tempi

Cinque edge e un cloud: VM 1/1/2/2/3/8 vCPU e 2/3/3/4/6/12 GiB. Archi bidirezionali 1–2, 2–3, 3–1, 3–4, 4–5, 5–3: due triangoli planari con vertice E3 condiviso, gradi 2/2/4/2/2. Ogni edge può inoltrare direttamente al cloud. Un peer ricevente è terminale: nessun inoltro a un secondo peer.

**Primo protocollo: T=360 s e nove intervalli misurati.** Tutti i sei nodi sono caricati contemporaneamente con un anchor comune. Carico e pianificazione cambiano sullo stesso confine, ogni sei minuti. Nove round durano 3240s, cioè **54 minuti complessivi per modo, non per nodo**. Baseline/EWMA/ORACLE sono esecuzioni separate: una tripletta misura 162 minuti. Provisioning, riscaldamento >=2T e cleanup si contano separatamente e vanno pubblicati; non chiamare 54 minuti il wall time totale inclusivo di questi costi.

Il precedente estimand «ultimi quattro dei sei round per fase» non è applicabile: ogni condizione occupa un round. Conservare e analizzare tutti i nove round, inclusi cambi e recupero; nessuna pretesa di regime stazionario. Baseline/EWMA/ORACLE ricevono gli stessi originali, tempi, input e tassi congelati. Non fornire a EWMA la traccia futura di ORACLE.

**60 s è una fase futura**, non una riduzione automatica: occorrono nuova qualificazione temporale, traccia e protocollo separati. Conservare risultati e scope della fase a 360 s. I budget correnti sono asta .6s, solver .15s, peer .15s e preparazione 1s; .6+1=1.6s è un budget nominale, non una misura. Misurare l'intero wall time fino alla disponibilità fisica delle repliche, >=50 campioni completi per cella, q>=.95, epsilon<=.05, margini .2/.3s, zero censura. A 360 s il 5% è 18s. Non allargare budget per nascondere un errore: eventuali revisioni sono esplicite, pre-acquisizione.

## Cinque funzioni Rust

Cinque nomi/cataloghi distinti, disponibili su ogni nodo: `one-shot-arithmetic`, `one-shot-sort`, `one-shot-memory`, `one-shot-matrix`, `one-shot-mixed`. Carichi rispettivamente aritmetico intero, ordinamento deterministico, scansione di memoria, prodotto di matrici intere e composizione dei primi. Lavoro realmente diverso, non cinque alias con lo stesso input. Riutilizzare SDK Rust e un'implementazione comune senza nuove dipendenze: selezione del handler per nome o per variante esplicita vincolata al catalogo; cinque identità funzione distinte nell'API e nei manifest. La ricetta deve esportare e verificare ciascuna identità e il digest immutabile della sua immagine; immagini condivise sono ammesse, se handler/input distinti sono verificati.

Input deterministici e limitati, seed congelati, checksum u64 indipendenti calcolati in Python, nessun output ambiguo oltre 2^53. Il workload originale e il suo input devono restare compatibili. Ogni handler usa spawn_blocking e c=1, prova fisica SDK, nessun retry delle richieste. Validare i bound prima di allocare buffer; dimensioni/prodotti/overflow invalidi devono fallire senza allocazioni enormi. I tempi di servizio dipendono da funzione, quota e nodo: profili locali per tutte le 30 coppie, senza medie tra VM o funzioni.

## Memoria strict e budget fisici

Il backend Docker già impone Memory ma non MemorySwap. Aggiungere una politica operatore `nanofaas.container-local.strict-memory` (default false per compatibilità), abilitata nella ricetta sperimentale. In entrambi gli adapter Docker Java/CLI, con limite RAM M, strict impone MemorySwap=M/`--memory-swap M`: zero swap. Strict senza limite positivo deve essere rifiutato prima di creare il container. Non introdurre un nuovo campo pubblico DTO soltanto per questa campagna. Richieste e limiti RAM sperimentali coincidono con memoria dichiarata al planner.

Il limite è un tetto rigido cgroup, non preallocazione. Il planner addebita l'intero tetto a ogni replica, anche se il consumo corrente è inferiore. Prima delle misure verificare HostConfig e cgroup v2: memory.max=M, memory.swap.max=0, memory.current/peak/events, stato OOM e cpu.max. Kernel senza supporto swap limit => campagna non qualificata, non fallback silenzioso. OOM/handler ucciso restano errori reali e non esclusioni tecniche selettive.

Gate memoria prima del profilo finale: confrontare 64/128/256 MiB per funzione, input esatti e stress SDK c1, con logging congelato. Scegliere il più piccolo candidato senza OOM/errori e con picco <=75% del limite; registrare tutti i tentativi. Non promettere che 64 MiB bastino. Ripetere il controllo con quote finali e co-locazione. Se nessun candidato passa, correggere esplicitamente input/disponibilità e ricominciare prima del congelamento.

S=sum(M_f) è il minimo per cinque repliche iniziali. Pool proposti E1/E2=S, E3=2S, E4=2S, E5=3S, cloud=7S; devono lasciare memoria a OS, Docker, CP .35 CPU/512MiB e collector .1 CPU/64MiB. Verificare host 20 CPU senza VM estranee: 17 vCPU VM + logging backend 1.5 + operatore 1 =19.5.

Il controllo attuale somma i massimi di tutte le funzioni anche quando questi non possono coesistere per il limite di memoria: estenderlo con un **limite superiore conservativo sulle configurazioni ammissibili**, ad esempio min(sum(q_f*rmax_f), pool*max(q_f/M_f)). Non cancellare il controllo CPU o il vincolo memoria. Per assicurare qualsiasi configurazione memory-feasible, fissare q_f=floor(B_node*M_f/pool*1000)/1000, con B_node scelto entro vCPU-.35-.1-riserva OS. Quote positive e maxReplicas=floor(pool/M_f); verificare i cgroup effettivi. Pubblicare quote/pool finali prima di calibrare. Non confondere saturazione dei limiti delle funzioni/pool con utilizzo al 100% dell'intera VM; riportare entrambi.

## Capacità con carichi concorrenti

Generalizzare il trial da node->rate/replicas a node->function->rate/replicas. Ogni cella conserva pianificati/emessi/completamenti/errori/censura/lateness/output/prove SDK fisiche. Evitare che il primo elemento del dict rappresenti tutte le funzioni. I tassi sono per coppia, con input e oracolo corretti.

Misurare D[node,f] isolato, >=50 sample e intervallo di confidenza, caldo. Misurare capacità per funzione e vettori di repliche ammissibili, tutti i livelli che il protocollo qualifica, tre ripetizioni e sweep di carico predefinito; conservare prove anche fallite. Non riscaldare contemporaneamente rmax di tutte le funzioni quando non entra nel pool. La capacità a funzione singola non prova la capacità delle cinque concorrenti. Validare mix differenti e prove simultanee su tutte le VM, <=15% drift/model error, >=99% completamenti, zero deficit/censura, prove c1 e generator lateness <=.25s.

L'artefatto di capacità multi-funzione è versionato e contiene coppia nodo/funzione, vettore di repliche, mix/tassi, trial SHA e ambito delle inferenze. Conservare lettura compatibile degli artefatti scalar legacy; non far passare uno scalar per una misura multi-funzione. Il native service-profile v1 può già contenere più righe: non cambiare il contratto senza necessità. Verificare identità/range/quote/hash per ciascuna riga.

## Matrice temporale e offload

Nove finestre di 360 s: controllo basso; cinque hotspot, uno per edge con funzione dominante diversa; hotspot simultanei nei due triangoli; sovraccarico diffuso di un sottoinsieme di edge; recupero. Il disegno crea capacità compatibile ai vicini in alcune finestre e deficit residuo verso cloud in altre. Non è necessario saturare contemporaneamente tutti i cinque edge: il cloud deve avere margine per tutte le funzioni.

Ogni finestra contiene una matrice completa 6x5 di tassi strettamente positivi, differenti per nodo/funzione e mutevoli tra finestre. Determinare tassi dai D locali e capacità concorrenti validate, non moltiplicando ciascuna capacità isolata per un fattore indipendente e sommando. Mix seedato/frozen, fattori bassi circa .35 e hotspot circa1.8 come punti di partenza pre-pilot. Quantum candidato .05 richieste/s è rappresentabile sia a360 s che60 s; verificare che nessuna cella positiva venga persa e che la quantizzazione preservi sovraccarico/capacità residua previsti. Se una cella non entra nella griglia, fallire o rivedere esplicitamente quantum prima del congelamento, non gonfiare silenziosamente il carico.

Cloud diretto basso, per ogni funzione, separato da utility/forecast/primary degli edge. Cloud terminale con vettore fisso di repliche fisicamente feasible; dimensionarlo dalla capacità concorrente cloud e dall'intera traccia, con margine >=10% verificato per funzione e per il mix. Non usare somma di richieste con costi diversi come unità di capacità. Offload da edge verso peer e cloud conteggiato soltanto su completamenti fisici veri, no retry/doppio hop. Una disuguaglianza sui tassi non garantisce che l'algoritmo scelga quel routing.

Pilot gate: sei origini/30 celle realmente emesse, saturazione dei limiti misurata, generator sano, cloud con margine e almeno un completamento orizzontale e verticale nei modi nativi, con distribuzione per funzione e nodo. Se copertura manca, conservare prova e revisionare il disegno esplicitamente; non ottimizzare ex post il carico sul risultato main. Baseline con zero peer resta valida.

## Logging, analisi e acquisizione

Nuovo logging strutturato di tutti i componenti: Fluent Bit5.1.3 per VM, Loki3.7.8 e Grafana13.2.3 sull'host. Strict memory riguarda le repliche funzione; quote CP/collector/backend restano esplicite. Gate overhead tre coppie off/on sul nuovo runtime, tutte le cinque funzioni, vettori massimi feasible e controllo nativo attivo; poi gate sulla traccia effettivamente qualificata. Ogni coppia <=5% perdita goodput e aumento p95. Log off non qualifica profili del main on. Profili amministrativi sintetici soltanto per observer bootstrap, marcati non scientifici. Esportare log/prove/hash e receipt prima di cancellare le sole risorse possedute.

Aggiornare freezer/writer/report per 5 identità e nove epoche; alias multipli biunivoci, nessuna collisione. Per il protocollo360 s l'estimand usa tutti i nove intervalli: edge-cloud completions/planned edge originals, esclude cloud diretto; riportare anche ogni funzione, ogni intervallo e nodo, latenze/errori e variazione del mix. Nessuna steady-state claim. Sei pilot triplets bilanciati e separati dal main; se si prosegue al main sullo stesso scope, N12–36 dimensionato dal pilot, paired bootstrap interi blocchi 10k, guard completion lower95CI>=-.02, ORACLE secondario, nessuna conclusione confermativa. Mantieni registry completo, cause tecniche esterne documentate, rimpiazzi preset <=floor(.2N), nessuna esclusione algoritmo-associata.

Pubblicare il budget wall time reale dopo memoria/quote/capacità e prima di lunghe acquisizioni; nove round non riducono i costi di calibrazione di 30 coppie e mix concorrenti. Dataset precedente e immagini a funzione singola rimangono storici. Ogni modifica source/package/runtime dopo acquisizione invalida il relativo scope e impone riqualiﬁcazione; nessun riuso silenzioso.
