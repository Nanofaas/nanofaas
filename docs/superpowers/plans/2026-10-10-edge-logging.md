# Logging edge comune — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans; modalità native già scelta. Implementare task ordinati, con verifica inline; nessuna delega di implementazione.

**Goal:** log uniformi e correlabili in tutta NanoFaaS e sei SDK, raccolti sul nodo con Fluent Bit e consultabili in Loki/Grafana opzionali.

**Architecture:** contratto JSON condiviso, configurazione per processo, propagazione del contesto; collector esterno per nodo. Nessun modulo control-plane obbligatorio, nessuna dipendenza dei runtime da Loki. La campagna eterogenea dipende dai gate del logging prima della calibrazione.

**Tech Stack:** Java 25 e backend esistenti; Python >=3.12 logging/contextvars; Go slog/context; JavaScript logger/AsyncLocalStorage; Rust tracing; Docker Compose e Helm; Fluent Bit, Loki, Grafana con versioni fissate.

**Spec:** [disegno logging](../specs/2026-10-10-edge-logging-design.md).

**Autorizzazione:** l’utente ha autorizzato il 2026-10-10 il completamento del progetto e l’avvio successivo dell’implementazione native, considerando approvati progetto e piano. Commit di sviluppo locali pertinenti al piano; merge e push non inclusi. Non eseguire automaticamente l’intera campagna statistica a seguito dei soli gate tecnologici del logging.

## Global Constraints

- Tutti i moduli, processi autonomi e SDK Java Spring/Java lite/Python/Go/JavaScript/Rust; collector per nodo, non per modulo.
- SchemaVersion 1; timestamp UTC RFC3339, level/service/logger/message; opzionali functionName/executionId/traceId/attempt/event/status/exception; attributi liberi sotto attributes.
- `NANOFAAS_LOG_FORMAT=json|text`, default text; `NANOFAAS_LOG_SERVICE` opzionale. Non sovrascrivere logger globali di applicazioni embedding; niente nuovi protocolli HTTP.
- Collector 0.10 CPU/64 MiB, buffer disco 256 MiB; oldest-chunk eviction esplicita, perdita e duplicati possibili; endpoint esterno supportato.
- Nessun payload, risultato o header arbitrario nei log di runtime. ID richiesta/traccia/epoch non sono etichette Loki.
- Worktree isolato; GitNexus query/context/impact prima di modificare simboli Java; HIGH/CRITICAL segnalati, UNKNOWN verificato; detect_changes completo prima di commit NanoFaaS.
- Test dei contratti reali, nessuna falsa richiesta di RED quando il comportamento è già corretto. GraalVM e dipendenze leggere preservati.

## Mappa dei file

N = NanoFaaS, L = NanoLab. Java package root `it/unimib/datai/nanofaas`.

| Percorso | Responsabilità |
| --- | --- |
| `N/sdks/runtime-contract/logging-{schema,corpus}.json` nuovi | Schema JSON e esempi cross-language |
| `N/docs/observability.md`, `N/sdks/runtime-contract/README.md` | Contratto, opt-in, contesto e limiti |
| `N/platform/control-plane/src/main/resources/application.yml` | Selezione formato nel processo Spring |
| `N/platform/libs/common/src/main/java/it/unimib/datai/nanofaas/common/logging/` | Solo helper condivisi necessari senza Spring/backend |
| `N/platform/libs/execution-runtime/`, `N/platform/modules/` | Propagazione nei percorsi asincroni realmente identificati dal grafo |
| `N/sdks/java/src/main/java/it/unimib/datai/nanofaas/sdk/runtime/TraceLoggingFilter.java`, risorse SDK Java | Contesto e formatter Spring |
| `N/sdks/java-lite/src/main/resources/logback.xml`, runtime/handler Java lite | Formatter e contesto backend standalone |
| `N/sdks/python/src/nanofaas/sdk/{logging,context}.py`, `runtime/app.py` | Formatter/helper Python e lifecycle |
| `N/sdks/go/nanofaas/{context,runtime,http_invoke}.go` | Logger contestuale Go |
| `N/sdks/javascript/src/{logger,context,runtime}.ts` | Logger contestuale JavaScript |
| `N/sdks/rust/src/{context,runtime,server}.rs` | JSON subscriber e campi tracing |
| `N/deploy/observability/` nuovo | Configurazioni collector Docker, preset Loki/Grafana, istruzioni |
| `N/deploy/helm/nanofaas/{values.yaml,templates/}` | DaemonSet collector opzionale, config e metriche |
| `L/packages/nanolab/src/nanolab/{config/one_shot.py,plans/one_shot_common.py,tasks/one_shot/}` | Budget, lifecycle, fingerprint ed esportazione log |

## Review Focus

1. Contesto rimasto su worker riusato o callback: test concorrenti/exception nei Task 2–4.
2. Log multilinea/non-JSON e logger ospite: corpus e compatibilità Task 1–4/6.
3. Backend offline o disco pieno: invocazione continua, perdita dichiarata e recupero Task 5–6.
4. ID ad alta cardinalità trasformati in label o campi riservati sovrascritti: Task 1/5–6.
5. Collector o backend alterano calibrazione e cleanup elimina volumi estranei: Task 7 e gate della campagna.

## Task 1: Contratto e corpus comune

**Files:** creare schema/corpus sopra, `N/sdks/runtime-contract/test_logging_corpus.py`; aggiornare README e observability.

**Interface:** schema versione 1 con required schemaVersion/timestamp/level/service/logger/message; corpus `cases[{name,input,expected}]`, valori Unicode/newline/exception, contesto assente, campi attributes. Normalizzare timestamp dinamici nei test; confrontare i campi significativi, non byte/order JSON.

- [x] Scrivere test schema/corpus: versione errata, attempt non positivo, timestamp invalido, ID mancante ammesso, escaping e riservati sotto attributes; validator riusa strumenti già presenti nel contratto.
- [x] Verificare test fallisce soltanto per i nuovi file/contratti mancanti; implementare schema/corpus e documentare formato, opt-in e backward compatibility.
- [x] Eseguire `python3 -m pytest sdks/runtime-contract/test_logging_corpus.py -q` con Python >=3.12 e ambiente test disponibile; expected pass. Commit locale `Define shared structured logging contract` dopo detect_changes completo.

## Task 2: Piattaforma e moduli Java

**Files:** application.yml, common/logging se necessario, risorse logging control-plane e test di processo nuovi; execution-runtime e moduli solo dove impact identifica handoff di contesto. Aggiornare configurazione warm-echo se eseguibile autonomamente.

**Interfaces:** formato selezionabile da env sopra; contesto immutabile snapshot di executionId/traceId/functionName/attempt quando disponibile. Scoped restore del contesto precedente, mai clear globale indiscriminato. Utilizzare backend Spring già disponibile, senza nuovo modulo prodotto.

- [x] Graph-first mappare ingresso, scheduler, dispatch, callback, offload e servizi autonomi; registrare lista dei processi e handoff effettivi prima di edit, segnalare rischi. Verificare campo MDC richiesto dal formatter e quello effettivamente impostato.
- [x] Scrivere test JSON di log provenienti da common, execution-runtime e almeno due moduli nello stesso processo; test assenza di contesto per scheduler multi-request. Due richieste concorrenti su worker riusato, exception e task callback non contaminano gli ID.
- [x] Implementare configurazione per processo e propagazione minima agli handoff verificati; usare logging di libreria senza riconfigurare globalmente applicazioni embedding. Gli eventi multi-request espongono event/epoch disponibili ma nessun executionId unico inventato.
- [x] GREEN `./gradlew :common:test :execution-runtime:test :control-plane:test :control-plane-modules:offload:test :control-plane-modules:p2p-discovery:test` su Java 25. Verificare processo JVM reale in json/text e build native configurata per i componenti modificati, oppure dichiarare blocker concreto. Commit `Add shared platform logging and scoped correlation`.

## Task 3: SDK Java Spring e Java lite

**Files:** TraceLoggingFilter, handler/executor/callback Java e lite individuati dal grafo, risorse logging dei due SDK e rispettivi test/README.

**Interface:** stesso schema e variabili; helper pubblico per scoped context solo se necessario al codice utente. Java Spring usa backend dell’host; Java lite mantiene Logback, evitando aggiunta di stack Spring.

- [x] Test corpus su entrambi, header→contesto già risolto, fallback cold esistente, exception/cancellazione e callback; logger host personalizzato rimane funzionante e non viene sostituito dalla libreria.
- [x] Implementare formatter leggero con serializzazione JSON corretta e nessuna dipendenza nuova se il backend esistente basta; contesto propagato al vero thread handler, non soltanto al filtro HTTP. Campo exception resta una singola riga fisica JSON.
- [x] GREEN `./gradlew :sdks:java:test :sdks:java-lite:test`; verificare native per entrambi e smoke runtime reali. Commit `Align Java SDK logging with shared contract`.

## Task 4: SDK Python, Go, JavaScript e Rust

**Files:** quelli nella mappa, test logging nuovi nelle directory test di ciascun SDK; README ed esempi pertinenti.

**Interface:** configurazione esplicita dei logger e helper context-aware secondo API idiomatica esistente; tutti consumano lo stesso corpus. Nomi canonici nel json, modalità text preservata; contextvars/context/AsyncLocalStorage/tracing gestiscono richiesta e task asincroni.

- [x] Per ciascun SDK aggiungere test corpus e due handler concorrenti con ID distinti, callback fallita, ritorno/exception/cancellazione e log successivo senza contesto. Test che attributes non sovrascriva executionId/level/service e che la libreria non riconfiguri logger globali al solo import.
- [x] Eseguire i test prima delle modifiche e implementare il minimo per i nuovi contratti mancanti. Python mantiene compatibilità API helper; Go offre logger derivato dal context senza affidarsi a variabili globali della richiesta; JavaScript usa contesto asincrono; Rust innesta campi degli span senza inventare ID.
- [x] GREEN Python: `python3 -m pytest sdks/python/tests -q` in ambiente con extra test SDK installato; Go da sdks/go: `go test -race ./...`; JavaScript da sdks/javascript: `npm ci` e `npm test`; Rust: `cargo test --manifest-path sdks/rust/Cargo.toml --locked`. Registrare output e versioni, non assumere dipendenze già installate. Commit locale per SDK completato.

## Task 5: Deployment opt-in Fluent Bit, Loki e Grafana

**Files:** deploy/observability/{compose.yaml,fluent-bit.conf,parsers.conf,loki.yaml,grafana-datasources.yaml,README.md} nuovi; Helm valori/templates/documentazione; docs/observability.

**Interfaces:** Compose separato per node-agent e backend tramite profili `agent`/`backend`; endpoint/hostname/percorso log/nodeId configurabili. Helm `observability.logs.enabled=false`, `loki.url`, `node metadata`, storage/resource limits; log JSON opt-in coerente quando abilitato. Schema dei valori Helm se esiste da aggiornare.

- [x] Verificare versioni e direttive nelle fonti ufficiali; configurare parser Docker o CRI, merge JSON con fallback text senza perdere record, checkpoint e storage.persistente, 256 MiB storage.total_limit_size, memoria/CPU iniziali, retry illimitato e labels a bassa cardinalità.
- [x] Configurare Loki retention/storage espliciti, datasource Grafana, endpoint remoto senza backend locale. Agent legge i file senza Docker socket; RBAC Kubernetes solo lettura metadata necessaria, hostPath minimali, nessun privileged se non necessario.
- [x] Verificare `docker compose -f deploy/observability/compose.yaml config`, `helm lint deploy/helm/nanofaas`, render enabled/disabled; avviare collector reale per validare config/parser, non affidarsi solo a YAML valido. Test zero collector con enabled=false, no executionId/traceId nelle labels, budget e buffer persistenti.
- [x] Commit `Add opt-in edge log collection deployments` dopo graph-change check.

## Task 6: Verifica end-to-end e resilienza

**Files:** script/test integrazione logging in scripts o test deployment esistenti, dossier `N/docs/testing/edge-logging/README.md` e receipt pertinenti.

**Interface:** fixture emette eventi noti con executionId/traceId; query Loki confronta gli ID e campi del corpus, senza usare un conteggio esatto come promessa di exactly-once. Risorse di test hanno prefisso univoco e cleanup posseduto.

- [x] Eseguire log platform+funzione per SDK, verificare ricerca correlata tra origine e destinazione; multilinea, Unicode, log text e eventi senza richiesta conservati. Verificare REST e callback invariati con collector assente e presente.
- [x] Interrompere endpoint Loki, emettere eventi, riavviare Fluent Bit sul medesimo storage e ripristinare backend: eventi buffered recuperati entro quota. Test rotazione sorgente e test separato di buffer pieno con limite ridotto dichiarato; perdita esplicita e ripresa, invocazioni sane, RAM limitata. Salvare metriche reali e duplicati eventuali.
- [x] Eseguire suite SDK/platform pertinenti, GraalVM, Helm e smoke, registrare blocker esterni senza mascherarli. Archivio di log/query/config/hash e inventario cleanup; nessuna modifica alla contabilità SDK per far passare i log.
- [x] Commit `Verify edge logging correlation and recovery`.

## Task 7: Integrazione NanoLab e gate della campagna

**Files:** configurazione one-shot/composizione comune/task lifecycle NanoLab, preset eterogenei, test budget/fingerprint/export/cleanup; piano/spec campagna collegati.

**Interface:** configurazione opzionale `observability.logs` con enabled, endpoint, metadata e limiti; default disabilitato legacy. Riusare resource manager per agent/backend di proprietà, leggere e mantenere risorse esterne. Fingerprint include versioni/config/formato/livello/budget; dossier contiene export log e metriche collector con hash.

- [x] Test budget include collector/backend, ogni nodo ha agent e nodeId distinti, metadata non modifica SDK, fingerprint cambia col config, cleanup preserva backend esterno anche su errore; la perdita di log non modifica completamenti/errori della contabilità.
- [x] Implementare lifecycle con export prima del teardown, failure best-effort e status completo/parziale distinto da validità scientifica. Nessun nuovo motore di provisioning e nessun endpoint di logging nelle funzioni.
- [x] GREEN suite pertinente NanoLab e hook, poi tre coppie bilanciate di overhead logging-off/on con lo stesso carico; registrare CPU/RAM/I/O/p95/goodput e deficit generatore. Gate differenze goodput/p95 entro 5%; altrimenti revisionare budget/livello prima della calibrazione. Non interpretare questo gate come esperimento one-shot principale.
- [x] Congelare stack e metadati prima dei Task 3/5/8 della campagna; profili antecedenti al logging non qualificano automaticamente lo stack cambiato. Commit locale NanoLab e dossier pertinente.

## Dipendenze e consegna

`1 contratto → 2 piattaforma → 3 SDK Java → 4 altri SDK → 5 deployment → 6 E2E → 7 NanoLab/overhead → calibrazione della campagna`.

Applicare verification-before-completion; una review indipendente finale secondo modalità native, non deleghe di implementazione. Se un limite ambientale impedisce un gate reale, consegnare codice e verifiche raggiunte indicando esattamente il gate non eseguito; non dichiarare pronta la campagna. Nessun merge/push automatico.

Self-review: sei SDK, tutti i moduli e processi autonomi coperti; default/embedding e contesto concorrente testati; offline/buffer/retention/etichette espliciti; logging e accounting separati; budget e fingerprint antecedenti alla calibrazione. Esecuzione autorizzata dall’utente dopo questa progettazione.

Execution qualification: all seven logging tasks delivered. Task 7 freezes the
technology pilot; the full scientific campaign remains planned separately and
must repeat its gate on its complete stack before calibration. Failed warmups
and the failed fixed-phase series are retained alongside the passing protocol v2.

Final review qualification: eight Important findings (including two regraded
from Minor) fixed in one RED/GREEN pass. Supplemental final-stack pilot records
cumulative application/collector/backend CPU and block-I/O, endpoint RAM, 18
verified state archives and confirmed cleanup; median p95 +0.8286%, goodput
-0.1105%, all 5428 requests complete. Full statistical campaign still requires
its own frozen topology/replicas/P2P/trace gate. See docs/testing/edge-logging/final-review.md.
