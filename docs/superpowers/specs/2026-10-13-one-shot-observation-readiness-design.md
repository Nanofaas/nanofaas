# One-shot: conferme incomplete e soglie di arresto della campagna

## Obiettivo e autorizzazione

Richiesta iniziale del 13 ottobre: pianificare la
correzione dei controlli che arrestano una campagna con un piano DEGRADED
utilizzabile e l'aumento delle soglie che fermano la procedura. Le conferme
mancanti devono diventare dati per comprendere l'esecuzione.

Implementazione successivamente autorizzata il 13 ottobre, in modalità native; nessuna nuova campagna in questo task.

Chiarimento dell'utente: «intendo dire alza le soglie al di là delle quali la
procedura si ferma»; «non aumentare la ram e la cpu».

Estensione richiesta nella revisione: audit di tutto il codice dell'esperimento
e verifica che l'esecuzione sia un workflow Sonata. L'audit include preparazione,
taratura Python, asset/preset, provisioning, protocollo/API, SDK Rust, generazione,
telemetria, supervisione, report e archiviazione con le dipendenze raggiunte.
Il registro per file distingue lettura approfondita, prova eseguita e verifica
ancora da fare; una suite verde non equivale a dimostrazione di assenza di bug.

Restano fissi: cinque edge e un cloud, due triangoli, cinque funzioni Rust,
CPU/RAM delle VM, quote CPU/RAM delle funzioni e collector, pool RAM strict,
un handler per replica, carico tarato, warmup 720 s, nove intervalli da 360 s,
8 round. Non si cambiano rate di assegnazione, burst, code di invocazione o
timeout delle singole richieste: i loro reject/errori sono risultati, non
soglie di arresto della procedura. Il limite di preparazione già richiesto
resta 10 s. Nessuna nuova soglia prestazionale o sugli orologi.

## Comportamento richiesto

Distinguere tre proprietà: piano utilizzabile, misure raccolte, piena
preparazione delle repliche/assegnazioni. DEGRADED non implica misura censurata
né guasto operativo. Con piano valido, capacità pronta anche parziale e
fallback cloud già costruito dal runtime, proseguire nell'intervallo e nei
successivi; non inventare capacità o conferme mancanti.

La qualificazione scientifica separata può continuare a richiedere piena
preparazione. Non importare quel criterio nell'osservazione funzionale.
Una misura realmente mancante rimane mancante, senza sostituirla con zero.

La mancanza di READY, READY_ACK o della risposta a READY_ACK è un evento di
protocollo. Distinguere «riscontro non osservato da questo nodo» da «il peer
non ha ricevuto il messaggio»: la seconda conclusione non è dimostrata da un
timeout. Nessun nuovo retry o rilancio della campagna.

Ogni assegnazione conserva: UTC e offset monotono, run/sessione, epoca, round,
funzione/generazione, ID assegnazione, buyer/seller/incarnazioni, quantità
negoziata, quantità confermata, READY ricevuto, ACK inviato, risposta all'ACK,
motivo dell'assenza e rate residuo cloud. Motivi distinti: timeout, rifiuto
esplicito, errore di trasporto, READY non ricevuto, operazione non tentata
per esaurimento della finestra. Non classificare tutti come timeout.

Prenotazioni inbound senza outbound del buyer sono capacità riservata ma non
usata, un esito ammissibile con conferma mancante. L'outbound realmente usato
deve continuare ad avere autorizzazione coerente sul venditore. Restano errori
operativi: assenza di piano utilizzabile, violazione RAM/identità/topologia,
processi necessari terminati, impossibilità di scrivere i dati, provisioning
fallito, interruzione. Campioni mancanti e metriche diagnostiche incomplete
non diventano da soli guasti dei processi ancora attivi.

## Proposta di soglie operative per il prossimo freeze

Valori proposti per la prova funzionale, non misure né criteri di successo.
La tabella è la fonte della futura configurazione congelata; gli altri preset
mantengono i propri valori. Un timeout finito non garantisce l'assenza di guasti.

| Controllo che può fermare la procedura | Attuale | Proposto |
|---|---:|---:|
| Asta complessiva | 4 s | 60 s |
| Solver, per chiamata | 0,15 s | 5 s |
| RPC dell'asta | 0,15 s | 5 s |
| Preparazione fisica | 10 s, già modificato | 10 s |
| Frazione massima di asta/operatività | 0,20 | 1,00; nessun gate prestazionale sull'esito |
| Anticipo | 14 s | 90 s; 60 + 10 ≤ 90 < 360 |
| API amministrativa NanoLab | 30 s | 180 s |
| Barriera dei prepare paralleli | 10 s | 60 s |
| Richieste P2P simultanee | 8 | 8; nessun aumento di contesa senza finding pertinente |
| Parallelismo negoziazione | 4 | 4 |
| Coda negoziazione | 64 | 64 |
| Massimo vicini | 16 | 16; grafo attuale con massimo quattro vicini |
| Stati/livelli solver | 2.000.000 | 10.000.000; RAM solver resta 64 MiB |
| Cloud-init | 600 s | 1.800 s |
| Avvio control-plane / Loki | 90 s | 300 s |
| Comandi Compose logging | 90 s | 300 s |
| Avvio collector / sampler | 30 s | 120 s |
| Bootstrap SDK, attesa replica | 120 s | 300 s |
| Bootstrap SDK, chiamata/prova di rilascio | 30 s | 120 s; non il timeout delle richieste misurate |
| HTTP probe/metrics/proof della raccolta | 1–5 s | 10 s; mancata lettura registrata, campione non inventato |
| k6, allowance finale | 60 s | 180 s |
| Lateness diagnostica del percorso osservazionale | 3.600 s | invariata, solo osservazione; non arresta il generatore |
| Join collector / clock thread | 40 s | 300 s |
| Drain sampler / processi | 10 s | 30 s |
| Wait dopo TERM / KILL, join reader | 5 s | 30 s |
| Lettura eventi | 100 pagine × 100 entry | 1.000 pagine × 100 entry |
| Retention eventi runtime | 10.000 | invariata; export incrementale e segnalazione dei buchi |

Correzione rispetto alla prima stesura: peer, parallelismo, code e retention
non erano dimostrati come cause di arresto per questa topologia. Aumentarli
indiscriminatamente cambia contesa e uso di memoria. Qualsiasi successiva
proposta deve indicare il controllo che interrompe realmente la procedura.
Inoltre `collect_original_evidence` registra la lateness come violazione legacy,
ma `ObservationReport` non la usa per fermare il workflow: descriverla come
«lateness che ferma il generatore» era inesatto.

READY/ACK: eliminare i cap nascosti di 500/200 ms dal percorso della campagna;
usare il peer timeout configurato (5 s), sempre limitato dalla finestra residua
e ripartito fra le operazioni ancora necessarie. L'esaurimento del tempo di
conferma, dopo che il piano locale sicuro è pronto, produce DEGRADED e
diagnostica, non FAILED. Non allungare il limite fisico di 10 s né aspettare
oltre la validità dell'epoca. Non aggiungere un secondo protocollo di chiusura.

Con allowance generatore 180 s, ORACLE deve coprire **4.230 s**:
720 warmup + 90 setup + 3.240 misura + 180 allowance. Formula unica, non
costante scollegata. Heartbeat 5 s e campioni CPU/RAM 1 s restano invariati.
Il limite del runner attualmente aggiunge 35 s, mentre k6 usa 60 s; il sampler
ha un'altra durata hardcoded. Unificare scadenze e durata relativa al momento
reale di avvio, oltre alla formula di validità ORACLE. La configurazione API
deve ricevere lo stesso lead del manifest: oggi `ConfigureOracle` passa 8 s
anche quando il freeze dichiara 14 s e preparazione 10 s.

Per tutti i controlli del censimento del 13 ottobre produrre una decisione
tracciabile: aumentato, solo osservazione, già disabilitato/non applicato,
parametro sperimentale fisso o vincolo di integrità. Body/codec/profile cap,
intervalli numerici e cap RAM non vanno gonfiati indiscriminatamente: provare
offline che gli input effettivi stanno nei limiti. Troncamenti diagnostici
sono dichiarati nel report, senza escludere una campagna utilizzabile.

## Esiti e prove

### Workflow e gestione dei guasti

L'osservazione usa già `Workflow("first-functional-oracle-observation")` e
`workflow.run()`, mentre il workflow qualificato è costruito da un builder
separato nel package. Portare anche la composizione osservazionale nel package
e lasciare uno script di ingresso sottile. Nessun nuovo engine o scheduler;
le risorse conservano dipendenze e compensation Sonata. Inventario iniziale,
esito e archiviazione finale sono hook del comando/supervisore: la copia finale
avviene dopo l'ultima release e dopo l'arresto del sink che scrive eventi.

Supervisionare le risorse per tutto il loro lifetime, incluso backend Loki
posseduto e periodo antecedente all'avvio del sampler. Un errore di un container
funzione non deve mascherare l'assenza certa del control-plane da un inventario
Docker completo. Dati mancanti da un inventario non leggibile restano sconosciuti.
Il guasto del canale di logging/status del supervisore deve interrompere
l'azione anche se non è possibile scrivere l'evento di errore sullo stesso disco.

### Conservazione e riproducibilità

Il generatore deve registrare anche risposte JSON `null` o malformate senza
eccezioni nel proprio codice di raccolta. Report con grafici di sei VM e
container anche nei run falliti; la policy `vmTelemetry` va nel manifest reale.
Per vecchi manifest assenti di quel campo usare le evidenze policy/raw già
presenti senza modificare il freeze. La conservazione comprende i log consegnati
al backend: query limitata e buffer Fluent Bit non garantiscono da soli una
copia completa prima della cancellazione dei volumi Loki.

Congelare anche gli asset esterni al package letti in esecuzione (logging,
definizioni funzioni, bootstrap/ricetta); nessun file mutabile deve alterare
l'esperimento sotto lo stesso fingerprint. Modifiche rilevanti dopo prepare
devono essere escluse usando copie congelate oppure rilevate prima di acquisire VM.

Un run completo con assegnazioni senza riscontro può terminare con successo
operativo e risultato sperimentale DEGRADED. Esporre entrambi; non chiamarlo
convergenza o qualificazione riuscita. Nel report: quantità e rate negoziati,
confermati, non confermati, riserve inutilizzate, cloud previsto/effettivo,
reject/errori e relativi denominatori, per funzione/nodo/epoca.

Test nativi di perdita delle risposte e capacità parziale; test con vera
Workflow.run di Sonata e risorse simulate per tutti i nove intervalli,
DEGRADED, guasto reale, SIGINT/SIGTERM, cleanup e archivi senza scritture
concorrenti. Tempo simulato, nessuna VM per i test. Verifica offline del
contratto Java effettivo per frazione 1 e configurazione completa.
La prova Sonata esegue tutti i task osservazionali reali e cattura il payload
che parte da `ConfigureOracle`: compile-only e test che iniettano subito
`fail_load=True` non dimostrano il completamento del workflow. I boundary simulati
sono I/O, processi e tempo. Il registro dell'audit deve essere chiuso prima del
freeze successivo; prestazioni e qualità dei risultati rimangono osservazioni.

Conservare immutati freeze e raw precedenti; costruire la nuova distribuzione
e il nuovo fingerprint dopo le verifiche, includendo API, diagnostica,
operational limits, collector/sampler, ricetta, SDK e dipendenze effettive.
La successiva campagna è un'azione separata dalla scrittura di questo piano.


## Chiarimenti della revisione finale applicata

Le letture diagnostiche dell’osservazione passano al collector autonomo esistente,
fuori dal thread che prepara e attiva le epoche. I tempi di chiamata e la durata
operativa restano misurati; timer diagnostici non raccolti sono null e rinviano ai
raw `metrics.jsonl` / `runtime-events.jsonl`. Non si consumano 90 secondi di lead
aspettando endpoint diagnostici.

La raccolta fisica controlla stop e scadenza prima di ogni candidato/richiesta e
prima dell’eventuale secondo GET già presente. Il lavoro proof viene sottomesso
in blocchi del parallelismo esistente (16), non tutto insieme. Snapshot live e
finale riusano i 30 secondi del drain per delimitare una passata best effort:
una passata incompleta è dichiarata, non esclude il run. Dopo un guasto non si
apre una nuova raccolta di rete; si analizzano i raw già presenti. Il finale
normale evita di interrogare nuovamente originali con prova RELEASED.

La preservazione precede la cancellazione: se la copia raw Loki fallisce si
fermano i servizi, ma si conservano i volumi posseduti e una recovery receipt.
Se fallisce l’archivio dello stato collector, il container si ferma, il volume
rimane e la sua VM posseduta viene arrestata e trattenuta. Le altre risorse
possedute vengono rilasciate. L’esito è non zero e cleanup incompleto esplicito,
con identificativi recuperabili; nessun retry/replay/cancellazione successiva
automatica. Anche una receipt non scrivibile lascia la protezione in memoria.

Gli errori di scrittura degli artefatti non vengono convertiti in metriche
mancanti. Errori di segnalazione/receipt secondari diventano note e non
sostituiscono la prima causa della procedura.
