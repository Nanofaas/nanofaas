# Logging comune NanoFaaS e raccolta edge

Data: 2026-10-10. Disegno approvato preventivamente dall’utente dopo il completamento della progettazione; esecuzione native autorizzata, senza autorizzazione implicita a merge/push.

## Obiettivo e confini

Rendere disponibili log strutturati e correlabili a tutti i moduli NanoFaaS e a tutti gli SDK: Java Spring, Java lite, Python, Go, JavaScript e Rust. Raccoglierli tramite Fluent Bit per nodo, con Loki/Grafana centrali opzionali. L’integrazione deve funzionare indipendentemente dalla campagna one-shot; NanoLab aggiunge soltanto orchestrazione e metadati sperimentali.

Si sceglie un contratto comune più configurazione di processo e deployment. Un modulo del control plane non configura processi autonomi; Fluent Bit non viene incorporato nel runtime o negli SDK. Evitare una nuova libreria Java finché le API/backend esistenti permettono il riuso: `common` non deve acquisire dipendenze Spring/Logback per configurare applicazioni. Se l’analisi dimostra necessario un helper condiviso, mantenerlo piccolo e privo di I/O di rete.

Alternativa scartata: invio diretto a Loki da ogni SDK, che lega l’invocazione al backend e replica buffering/retry. Alternativa possibile: Alloy al posto di Fluent Bit; il contratto stdout/stderr resta compatibile.

## Contratto degli eventi

Una riga JSON per evento, UTF-8. Campi canonici: `schemaVersion` intero 1, `timestamp` RFC3339 UTC, `level` maiuscolo, `service`, `logger`, `message`. Campi opzionali: `functionName`, `executionId`, `traceId`, `attempt` intero positivo, `event`, `status`, `exception` stringa. Campi assenti non diventano identificativi sintetici; non ricavare attempt da dati non disponibili. Timestamp e dettagli backend aggiuntivi sono ammessi; il JSON resta valido con newline, virgolette, Unicode ed eccezioni multilinea. Dati liberi applicativi restano sotto `attributes`, senza poter sovrascrivere i campi canonici. Compatibilità dei nomi vecchi Python va documentata; normalizzazione effettuata dal formatter, non dal codice utente.

`NANOFAAS_LOG_FORMAT=json|text` seleziona il formato a livello di applicazione; default text conserva il comportamento esistente, preset di osservabilità usa json. `NANOFAAS_LOG_SERVICE` sovrascrive il nome servizio; default control-plane o nome runtime SDK. Non riconfigurare globalmente il logger dell’applicazione che incorpora una libreria SDK: configurazione esplicita, runtime standalone e helper documentati. Per Java rispettare logging configurato dall’applicazione ospite. stdout/stderr rimane il confine di raccolta; protocollo HTTP, callback e corpo della risposta non cambiano.

Il contesto di richiesta usa gli identificativi già risolti dal runtime. Ogni richiesta concorrente, handler, callback e task asincrono deve avere il proprio contesto, ripristinato in finally o tramite meccanismi equivalenti. Un task deve ricevere una snapshot prima del cambio di thread. Scheduler e operazioni senza richiesta emettono solo contesto realmente disponibile; non attribuire executionId a un intero batch o round con più richieste. Tutti i moduli nello stesso processo condividono formato/backend; processi autonomi configurano il proprio backend. Non esigere che una richiesta venga riconosciuta tramite testo del messaggio.

I log operativi non registrano payload, risultati, credenziali o header arbitrari. In INFO limitare gli eventi di lifecycle e le anomalie; dettagli per richiesta/asta a DEBUG quando possibile. Non cambiare stdout degli artefatti di misura in un nuovo protocollo: eventuali righe legacy restano riconoscibili e la normalizzazione riguarda i log, non i file di evidence.

## Collector e backend

Fluent Bit legge i file JSON Docker tramite tail+parser Docker, preserva checkpoint e chunk su volume persistente, normalizza log applicativi JSON senza eliminare quelli text, aggiunge `nodeId` e attributi di deployment. Per Kubernetes legge i file CRI tramite DaemonSet con RBAC minimo per arricchimento; parsing CRI e Docker sono configurazioni distinte. Non richiedere accesso al socket Docker per il tail dei file. Percorsi host/volume sono configurabili per installazioni differenti.

Loki è backend opzionale: supportare un endpoint Loki esistente senza installare Grafana/Loki. Il preset locale include backend monolitico e datasource Grafana; nessun requisito SaaS. Immagini con versione fissata, senza latest, scelta verificata sulle documentazioni ufficiali all’implementazione. Persistenza e retention Loki esplicite; collector assente dai deployment standard se non abilitato.

Budget iniziale collector per nodo: memoria 64 MiB, CPU 0.10, buffer su disco 256 MiB e retry senza timeout applicativo, con massimo spazio vincolato. A buffer pieno si elimina il chunk più vecchio per la destinazione; documentare perdita possibile, esporre errori/retry/drop/storage tramite metriche Fluent Bit e controllare che l’input riprenda. Nessuna promessa exactly-once: restart/retry possono duplicare eventi. Rotazione dei log sorgente limita quanto può essere recuperato prima dell’ingestione. Dimensionamento pilota può revisionare i budget prima delle misure, non durante il confronto.

Etichette Loki limitate a servizio, nodo e ambiente; executionId, traceId, epoch e identificativi richiesta non diventano etichette indicizzate. Metadati campagna/run sono campi del record, non una proliferazione di stream. Agent failure non blocca le invocazioni; CPU/disco del collector e del backend restano costi misurati, non risorse invisibili.

## NanoLab e validità sperimentale

La campagna eterogenea integra questo sistema prima della calibrazione. La tabella VM rimane una proposta, ma il preflight include agenti, backend su host e generatore. Nessuna VM aggiuntiva implicita. Collector configurato e budget identici fra modalità; immagini/configurazioni e formato entrano nei fingerprint. Backend fuori dalle sei VM di calcolo, su host con budget esplicito e storage posseduto.

NanoLab aggiunge nodeId, campaignId, runId, mode e ruolo nodo tramite configurazione/metadata, senza imporli agli SDK generici; epoch viene dall’evento quando noto, non da inferenza temporale presentata come certezza. Prima di calibrare confrontare logging text/off con json+collector sotto identico carico: almeno tre coppie bilanciate, CPU/RAM/I/O e latenza/goodput. Gate iniziale: differenza di goodput e p95 entro 5%, nessun deficit generatore; se superato, conservare misure e revisionare budget/livello prima di congelare i profili. Queste prove misurano interferenza, non vantaggi algoritmici.

I log servono alla diagnosi; accounting scientifico continua su evidence SDK, contatori e artefatti verificati. Una perdita di log non inventa né cancella completamenti. Dichiarare completezza dei log separatamente da validità dell’esperimento; perdita di correlazione impedisce dichiarazioni diagnostiche complete. Esportare log e metriche collector prima del cleanup, con ricevute hash, conservando failure e buffer quando acquisibili. Backend/volumi esterni non vengono rimossi.

## Accettazione

- Corpus JSON condiviso validato da tutti e sei gli SDK e dal control plane, inclusi log senza richiesta.
- Nessuna contaminazione tra richieste concorrenti, errori, timeout, callback e riuso thread.
- Moduli nello stesso processo e servizi separati producono lo stesso contratto con json abilitato; modalità text e logger ospite compatibili.
- Compose opt-in e Helm DaemonSet funzionanti con Loki remoto oppure preset locale.
- Backend irraggiungibile/restart, buffer pieno, multilinea e rotazione hanno verifiche reali, risorse vincolate e politica di perdita dichiarata.
- Campagna usa configurazione congelata, overhead misurato e accounting indipendente dai log.

Riferimenti: [Fluent Bit buffering](https://docs.fluentbit.io/manual/data-pipeline/buffering), [output Loki](https://docs.fluentbit.io/manual/data-pipeline/outputs/loki), [etichette Loki](https://grafana.com/docs/loki/latest/get-started/labels/bp-labels/).
