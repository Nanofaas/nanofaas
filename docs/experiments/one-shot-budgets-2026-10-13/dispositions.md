# Disposizione del censimento per il prossimo freeze

Il README adiacente descrive lo stato precedente all’implementazione. Questa
integrazione assegna una disposizione a **ogni riga** delle sue tabelle;
`dispositions.json` conserva anche i valori storici. Nessun valore qui è una
prestazione misurata. La configurazione effettiva viene congelata dal runner.

| Parametro | Disposizione | Prossima osservazione |
|---|---|---|
| `preparationBudget` / `preparationSeconds` | aumento / correzione operativa | 10 s invariati; scadenza delle sole conferme dopo piano sicuro → DEGRADED. |
| `auctionBudget` / `auctionSeconds` | aumento / correzione operativa | 60 s |
| `solverBudget` / `solverSeconds` | aumento / correzione operativa | 5 s |
| `peerTimeout` / `peerSeconds` | aumento / correzione operativa | 5 s |
| `maxRounds` | risorsa / parametro sperimentale fisso | Invariato; non aumentare risorse, contesa, parallelismo o timeout delle richieste misurate. |
| `parallelism` | risorsa / parametro sperimentale fisso | Invariato; non aumentare risorse, contesa, parallelismo o timeout delle richieste misurate. |
| `queueCapacity` | risorsa / parametro sperimentale fisso | Invariato; non aumentare risorse, contesa, parallelismo o timeout delle richieste misurate. |
| `maxPeers` | risorsa / parametro sperimentale fisso | Invariato; non aumentare risorse, contesa, parallelismo o timeout delle richieste misurate. |
| `maxSolverStates` | aumento / correzione operativa | 10.000.000 stati; RAM resta64MiB. |
| `maxSolverBytes` | risorsa / parametro sperimentale fisso | Invariato; non aumentare risorse, contesa, parallelismo o timeout delle richieste misurate. |
| `maxOperationalFraction` | aumento / correzione operativa | 1,00; coerenza configurazione, non gate sui tempi osservati. |
| `maxAuctionFraction` | aumento / correzione operativa | 1,00; coerenza configurazione, non gate sui tempi osservati. |
| `leadTime` / `leadSeconds` | aumento / correzione operativa | 90 s |
| `period` | risorsa / parametro sperimentale fisso | Invariato; non aumentare risorse, contesa, parallelismo o timeout delle richieste misurate. |
| Invio READY/READY_ACK | aumento / correzione operativa | peer5s limitato dal residuo e dagli invii rimanenti. |
| RPC READY/READY_ACK | aumento / correzione operativa | peer5s limitato dal residuo dell’invio. |
| Riserva finale READY | integrità / correttezza del runtime | Invariato; protegge capacità, identità, serializzazione o ciclo di vita. Non è un gate prestazionale sui risultati. |
| Poll readiness / pausa tra tentativi READY | risorsa / parametro sperimentale fisso | Invariato; non aumentare risorse, contesa, parallelismo o timeout delle richieste misurate. |
| `drain-grace` | integrità / correttezza del runtime | Invariato; protegge capacità, identità, serializzazione o ciclo di vita. Non è un gate prestazionale sui risultati. |
| Proposte automatiche v2 | non usato | Fuori dal driver manuale ORACLE osservazionale; fallback/qualificazione conservati. |
| Ritardo iniziale automatico | non usato | Fuori dal driver manuale ORACLE osservazionale; fallback/qualificazione conservati. |
| Qualificazione per avvio automatico | non usato | Fuori dal driver manuale ORACLE osservazionale; fallback/qualificazione conservati. |
| Richieste P2P simultanee | risorsa / parametro sperimentale fisso | Invariato; non aumentare risorse, contesa, parallelismo o timeout delle richieste misurate. |
| Ping vicini | solo osservazione | Valore invariato: reject, indisponibilità diagnostica, ritardi e campioni mancanti restano dati; non arrestano da soli la campagna. |
| Annuncio P2P | solo osservazione | Valore invariato: reject, indisponibilità diagnostica, ritardi e campioni mancanti restano dati; non arrestano da soli la campagna. |
| Payload P2P / batch asta | integrità / correttezza del runtime | Invariato; protegge capacità, identità, serializzazione o ciclo di vita. Non è un gate prestazionale sui risultati. |
| Messaggi per batch | integrità / correttezza del runtime | Invariato; protegge capacità, identità, serializzazione o ciclo di vita. Non è un gate prestazionale sui risultati. |
| Messaggi/bid del motore | integrità / correttezza del runtime | Invariato; protegge capacità, identità, serializzazione o ciclo di vita. Non è un gate prestazionale sui risultati. |
| Messaggio di avvio/status v2 | integrità / correttezza del runtime | Invariato; protegge capacità, identità, serializzazione o ciclo di vita. Non è un gate prestazionale sui risultati. |
| Quota di assegnazione | solo osservazione | Valore invariato: reject, indisponibilità diagnostica, ritardi e campioni mancanti restano dati; non arrestano da soli la campagna. |
| Handler per replica | risorsa / parametro sperimentale fisso | Invariato; non aumentare risorse, contesa, parallelismo o timeout delle richieste misurate. |
| Coda configurata per funzione | risorsa / parametro sperimentale fisso | Invariato; non aumentare risorse, contesa, parallelismo o timeout delle richieste misurate. |
| Coda sync condivisa | solo osservazione | Valore invariato: reject, indisponibilità diagnostica, ritardi e campioni mancanti restano dati; non arrestano da soli la campagna. |
| Stima throughput della sync-queue | solo osservazione | Valore invariato: reject, indisponibilità diagnostica, ritardi e campioni mancanti restano dati; non arrestano da soli la campagna. |
| Timeout invocazione | solo osservazione | Valore invariato: reject, indisponibilità diagnostica, ritardi e campioni mancanti restano dati; non arrestano da soli la campagna. |
| Retry delle invocazioni | solo osservazione | Valore invariato: reject, indisponibilità diagnostica, ritardi e campioni mancanti restano dati; non arrestano da soli la campagna. |
| Readiness backend container | integrità / correttezza del runtime | Invariato; protegge capacità, identità, serializzazione o ciclo di vita. Non è un gate prestazionale sui risultati. |
| Docker Java | integrità / correttezza del runtime | Invariato; protegge capacità, identità, serializzazione o ciclo di vita. Non è un gate prestazionale sui risultati. |
| Comandi Docker CLI | non usato | Fuori dal driver manuale ORACLE osservazionale; fallback/qualificazione conservati. |
| Cache stato repliche | integrità / correttezza del runtime | Invariato; protegge capacità, identità, serializzazione o ciclo di vita. Non è un gate prestazionale sui risultati. |
| Refresh stato repliche | integrità / correttezza del runtime | Invariato; protegge capacità, identità, serializzazione o ciclo di vita. Non è un gate prestazionale sui risultati. |
| Wakeup deployment | integrità / correttezza del runtime | Invariato; protegge capacità, identità, serializzazione o ciclo di vita. Non è un gate prestazionale sui risultati. |
| Previsione ORACLE | aumento / correzione operativa | 4.230 s =720+90+3.240+180. |
| Archivio ORACLE / EWMA | integrità / correttezza del runtime | Invariato; protegge capacità, identità, serializzazione o ciclo di vita. Non è un gate prestazionale sui risultati. |
| Clock-health legacy | solo osservazione | Valore invariato: reject, indisponibilità diagnostica, ritardi e campioni mancanti restano dati; non arrestano da soli la campagna. |
| Attesa cloud-init | aumento / correzione operativa | 1.800 s |
| Avvio control-plane / Loki | aumento / correzione operativa | 300 s; HTTP10s; Compose300s. |
| Avvio Fluent Bit / sampler VM | aumento / correzione operativa | 120 s; HTTP10s; sampler vivo senza primo campione → dato mancante. |
| Bootstrap SDK del runner | aumento / correzione operativa | Replica300s; invocazione e prova120s. |
| API one-shot NanoLab | aumento / correzione operativa | 180 s |
| Barriera prepare parallelo | aumento / correzione operativa | 60 s |
| Raccolta metriche/proof | aumento / correzione operativa | HTTP10s; errori diagnostici registrati, scrittura impossibile resta guasto. |
| Sampler VM | solo osservazione | Valore invariato: reject, indisponibilità diagnostica, ritardi e campioni mancanti restano dati; non arrestano da soli la campagna. |
| Attesa di finalizzazione locale del sampler | integrità / correttezza del runtime | Invariato; protegge capacità, identità, serializzazione o ciclo di vita. Non è un gate prestazionale sui risultati. |
| Silenzio sampler legacy | solo osservazione | Valore invariato: reject, indisponibilità diagnostica, ritardi e campioni mancanti restano dati; non arrestano da soli la campagna. |
| Clock probe | aumento / correzione operativa | HTTP10s; cadenza10s invariata; skew diagnostico. |
| Supervisore | solo osservazione | Valore invariato: reject, indisponibilità diagnostica, ritardi e campioni mancanti restano dati; non arrestano da soli la campagna. |
| Attesa setup generatore | risorsa / parametro sperimentale fisso | Invariato; non aumentare risorse, contesa, parallelismo o timeout delle richieste misurate. |
| Warmup | risorsa / parametro sperimentale fisso | Invariato; non aumentare risorse, contesa, parallelismo o timeout delle richieste misurate. |
| Misure | risorsa / parametro sperimentale fisso | Invariato; non aumentare risorse, contesa, parallelismo o timeout delle richieste misurate. |
| k6 `maxDuration` | aumento / correzione operativa | Orizzonte monotono residuo+180s; runner e sampler stessa scadenza. |
| Ritardo arrivo richieste | solo osservazione | Valore invariato: reject, indisponibilità diagnostica, ritardi e campioni mancanti restano dati; non arrestano da soli la campagna. |
| Arresto generatore | aumento / correzione operativa | TERM/KILL wait30s |
| Arresto sampler/processi e reader | aumento / correzione operativa | Drain30s; wait/join reader30s. |
| Arresto collector / clock monitor | aumento / correzione operativa | 300 s |
| Archivio finale | integrità / correttezza del runtime | Invariato; protegge capacità, identità, serializzazione o ciclo di vita. Non è un gate prestazionale sui risultati. |
| Export Loki | aumento / correzione operativa | HTTP10s e query5.000 invariati; store Loki grezzo copiato e verificato prima di down -v. |
| Eventi nativi | aumento / correzione operativa | 1.000 pagine×100; retention10.000 invariata; pagine/cursori salvati incrementalmente. |
| edge-1 | risorsa / parametro sperimentale fisso | Invariato; non aumentare risorse, contesa, parallelismo o timeout delle richieste misurate. |
| edge-2 | risorsa / parametro sperimentale fisso | Invariato; non aumentare risorse, contesa, parallelismo o timeout delle richieste misurate. |
| edge-3 | risorsa / parametro sperimentale fisso | Invariato; non aumentare risorse, contesa, parallelismo o timeout delle richieste misurate. |
| edge-4 | risorsa / parametro sperimentale fisso | Invariato; non aumentare risorse, contesa, parallelismo o timeout delle richieste misurate. |
| edge-5 | risorsa / parametro sperimentale fisso | Invariato; non aumentare risorse, contesa, parallelismo o timeout delle richieste misurate. |
| cloud | risorsa / parametro sperimentale fisso | Invariato; non aumentare risorse, contesa, parallelismo o timeout delle richieste misurate. |
| arithmetic | risorsa / parametro sperimentale fisso | Invariato; non aumentare risorse, contesa, parallelismo o timeout delle richieste misurate. |
| sort | risorsa / parametro sperimentale fisso | Invariato; non aumentare risorse, contesa, parallelismo o timeout delle richieste misurate. |
| memory | risorsa / parametro sperimentale fisso | Invariato; non aumentare risorse, contesa, parallelismo o timeout delle richieste misurate. |
| matrix | risorsa / parametro sperimentale fisso | Invariato; non aumentare risorse, contesa, parallelismo o timeout delle richieste misurate. |
| mixed | risorsa / parametro sperimentale fisso | Invariato; non aumentare risorse, contesa, parallelismo o timeout delle richieste misurate. |
| Ingress body | risorsa / parametro sperimentale fisso | Invariato; non aumentare risorse, contesa, parallelismo o timeout delle richieste misurate. |
| Execution admission | risorsa / parametro sperimentale fisso | Invariato; non aumentare risorse, contesa, parallelismo o timeout delle richieste misurate. |
| Input canonici trattenuti | risorsa / parametro sperimentale fisso | Invariato; non aumentare risorse, contesa, parallelismo o timeout delle richieste misurate. |
| Copie fisiche input | risorsa / parametro sperimentale fisso | Invariato; non aumentare risorse, contesa, parallelismo o timeout delle richieste misurate. |
| Waiter | risorsa / parametro sperimentale fisso | Invariato; non aumentare risorse, contesa, parallelismo o timeout delle richieste misurate. |
| Input references / depth / container entries / visited nodes | integrità / correttezza del runtime | Invariato; protegge capacità, identità, serializzazione o ciclo di vita. Non è un gate prestazionale sui risultati. |
| Input trattenuto per execution | risorsa / parametro sperimentale fisso | Invariato; non aumentare risorse, contesa, parallelismo o timeout delle richieste misurate. |
| Proxy container | risorsa / parametro sperimentale fisso | Invariato; non aumentare risorse, contesa, parallelismo o timeout delle richieste misurate. |
| Proxy read / write | risorsa / parametro sperimentale fisso | Invariato; non aumentare risorse, contesa, parallelismo o timeout delle richieste misurate. |
| HTTP pool per destinazione | risorsa / parametro sperimentale fisso | Invariato; non aumentare risorse, contesa, parallelismo o timeout delle richieste misurate. |
| Acquisizione HTTP pool | risorsa / parametro sperimentale fisso | Invariato; non aumentare risorse, contesa, parallelismo o timeout delle richieste misurate. |
| Retention pool | risorsa / parametro sperimentale fisso | Invariato; non aumentare risorse, contesa, parallelismo o timeout delle richieste misurate. |
| Execution store | risorsa / parametro sperimentale fisso | Invariato; non aumentare risorse, contesa, parallelismo o timeout delle richieste misurate. |
| Idempotency keys | risorsa / parametro sperimentale fisso | Invariato; non aumentare risorse, contesa, parallelismo o timeout delle richieste misurate. |
| Outcome byte budget | risorsa / parametro sperimentale fisso | Invariato; non aumentare risorse, contesa, parallelismo o timeout delle richieste misurate. |
| Rate generale | risorsa / parametro sperimentale fisso | Invariato; non aumentare risorse, contesa, parallelismo o timeout delle richieste misurate. |
| Profili one-shot | integrità / correttezza del runtime | Invariato; protegge capacità, identità, serializzazione o ciclo di vita. Non è un gate prestazionale sui risultati. |
| Configurazione amministrativa | integrità / correttezza del runtime | Invariato; protegge capacità, identità, serializzazione o ciclo di vita. Non è un gate prestazionale sui risultati. |
| Parser JSON | integrità / correttezza del runtime | Invariato; protegge capacità, identità, serializzazione o ciclo di vita. Non è un gate prestazionale sui risultati. |
| Valori di flusso | integrità / correttezza del runtime | Invariato; protegge capacità, identità, serializzazione o ciclo di vita. Non è un gate prestazionale sui risultati. |
| Revisioni di piano trattenute | integrità / correttezza del runtime | Invariato; protegge capacità, identità, serializzazione o ciclo di vita. Non è un gate prestazionale sui risultati. |
| Scheduler switch | non usato | Fuori dal driver manuale ORACLE osservazionale; fallback/qualificazione conservati. |

Le voci discorsive sono anch’esse invariate: disco20GiB/VM, collector0,10CPU/64MiB,
buffer256MiB, logging host1,5CPU/768MiB, heap JVM256MiB, limiti log Docker e
riserva OS/pool strict. Nessuna quota CPU/RAM è stata modificata.

Le soglie della qualificazione (campioni/CI/capacità, p99, margini, overhead5%,
repliche, VU massimi di schema) restano nel workflow qualificato. Nell’osservazione
il precheck registra il costo della raccolta senza applicare5%; lateness e
`legacyComparisonValid=False` non diventano esiti operativi falliti.
Il profilo adattato è esplicitamente non qualificato. VU512, warmup720s e
nove intervalli360s restano fissi. La copertura prevista peer9/9 e multi-peer≥2
è il requisito di disegno del carico richiesto dall’utente; il risultato misurato
non è filtrato per soddisfarlo.

`DEGRADED` è utilizzabile solo con piano valido: piena preparazione e completezza
dei dati sono campi separati. RAM, autorizzazione degli outbound, topologia e
identità restano verificati. Una riserva inbound inutilizzata è un’osservazione.
La preparazione fisica10s non viene allungata da questi cambiamenti.
