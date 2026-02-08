# Architettura interna dei Pod delle funzioni

## Panoramica

Ogni funzione registrata in nanofaas viene eseguita all'interno di un pod K8s.
Il contenuto del pod dipende da due scelte ortogonali:

| Dimensione | Opzioni | Impatto |
|---|---|---|
| **ExecutionMode** | `DEPLOYMENT` (default), `REMOTE` (Job) | Come il control plane crea le risorse K8s |
| **RuntimeMode** | `HTTP` (default), `STDIO`, `FILE` | Come il watchdog comunica con il processo utente |

---

## Struttura di un pod funzione

Ogni pod contiene **due livelli**: il watchdog (process supervisor) e il runtime
(codice utente). Il watchdog e' un binary Rust statico (~2 MB) che gestisce
l'intero ciclo di vita del processo figlio.

```
+--------------------------------------------------------------+
|  POD (container singolo)                                     |
|                                                              |
|  +--------------------------------------------------------+  |
|  |  ENTRYPOINT: /watchdog  (Rust, ~2 MB, statico)        |  |
|  |                                                        |  |
|  |  - Legge config da ENV                                 |  |
|  |  - Avvia il processo figlio (WATCHDOG_CMD)             |  |
|  |  - Gestisce health check, timeout, callback            |  |
|  +----+---------------------------------------------------+  |
|       |                                                      |
|       | spawn processo figlio                                 |
|       v                                                      |
|  +--------------------------------------------------------+  |
|  |  RUNTIME: processo utente                              |  |
|  |                                                        |  |
|  |  Opzione A: Java (Spring Boot)                         |  |
|  |    java -jar /app/app.jar                              |  |
|  |    -> InvokeController espone POST /invoke             |  |
|  |    -> HandlerRegistry carica handler via Spring scan   |  |
|  |                                                        |  |
|  |  Opzione B: Python (Flask + Gunicorn)                  |  |
|  |    gunicorn nanofaas_runtime.app:app                   |  |
|  |    -> Flask espone POST /invoke                        |  |
|  |    -> importlib carica handle() da handler.py          |  |
|  |                                                        |  |
|  |  Opzione C: Qualsiasi eseguibile                       |  |
|  |    /app/mio-binario                                    |  |
|  |    -> Deve esporre HTTP /invoke (mode HTTP)            |  |
|  |    -> Oppure leggere stdin/stdout (mode STDIO)         |  |
|  |    -> Oppure leggere/scrivere file (mode FILE)         |  |
|  +--------------------------------------------------------+  |
+--------------------------------------------------------------+
```

---

## Il Watchdog in dettaglio

Il watchdog (`watchdog/src/main.rs`) e' un binary Rust compilato staticamente
con musl libc. Gira su un'immagine `FROM scratch` (~2 MB totali).

### Modalita' di comunicazione (RuntimeMode)

```
MODALITA' HTTP (default)
========================
Watchdog              Runtime (HTTP server su :8080)
   |                        |
   |-- spawn processo ----->|
   |                        |
   |-- GET /health -------->|  (poll ogni 50ms, max 10s)
   |<------- 200 OK --------|
   |                        |
   |-- POST /invoke ------->|  (payload JSON)
   |   Content-Type: json   |
   |                        |  (handler esegue)
   |<------- response ------|
   |                        |
   |-- SIGTERM ------------->|
   |-- SIGKILL (dopo 100ms)->|


MODALITA' STDIO
================
Watchdog              Processo figlio
   |                        |
   |-- spawn processo ----->|
   |-- write stdin -------->|  (JSON payload)
   |-- close stdin -------->|  (EOF)
   |                        |  (handler esegue)
   |<------- stdout --------|  (JSON response)
   |   (processo termina)   |


MODALITA' FILE
===============
Watchdog              Processo figlio
   |                        |
   |-- write /tmp/input.json|
   |-- spawn processo ----->|
   |   INPUT_FILE=/tmp/input.json
   |   OUTPUT_FILE=/tmp/output.json
   |                        |  (legge input, scrive output)
   |   (processo termina)   |
   |-- read /tmp/output.json|
```

### One-shot vs Warm

Il watchdog ha due comportamenti radicalmente diversi:

```
ONE-SHOT (ExecutionMode = REMOTE, un Job per invocazione)
=========================================================

   Control Plane          K8s Job Pod
        |                      |
        |-- crea Job --------->|
        |                      |  watchdog avvia
        |                      |  watchdog esegue funzione (HTTP/STDIO/FILE)
        |<-- callback result --|  POST /v1/internal/executions/{id}:complete
        |                      |  pod termina
        |                      X


WARM (ExecutionMode = DEPLOYMENT, pod persistente)
===================================================

   Control Plane          Deployment Pod (n repliche)
        |                      |
        |                      |  watchdog avvia runtime
        |                      |  runtime pronto su :8080
        |                      |  watchdog espone HTTP server su :8080
        |                      |
        |-- POST /invoke ----->|  {execution_id, callback_url, payload}
        |                      |  watchdog --> POST /invoke al runtime
        |                      |              con header X-Execution-Id
        |<-- callback result --|
        |<----- response ------|
        |                      |
        |-- POST /invoke ----->|  (riusa stesso container!)
        |                      |  ...
        |<----- response ------|
        |                      |
        |      (il pod resta in vita tra le invocazioni)
```

In modalita' WARM il watchdog **diventa un reverse proxy HTTP**: accetta
richieste sulla porta 8080, le inoltra al runtime interno, e gestisce
callback e tracing.

---

## Variabili d'ambiente iniettate nel pod

### Pod Job (one-shot, ExecutionMode=REMOTE)

| Variabile | Esempio | Descrizione |
|---|---|---|
| `FUNCTION_NAME` | `word-stats` | Nome della funzione |
| `EXECUTION_ID` | `exec-abc-123` | ID univoco dell'invocazione |
| `CALLBACK_URL` | `http://cp:8080/v1/internal/executions` | URL callback al control plane |
| `INVOCATION_PAYLOAD` | `{"input":"hello"}` | Payload serializzato JSON |
| `TIMEOUT_MS` | `30000` | Timeout in millisecondi |
| `EXECUTION_MODE` | `HTTP` | RuntimeMode (HTTP/STDIO/FILE) |
| `WATCHDOG_CMD` | `java -jar /app/app.jar` | Comando per avviare il runtime |
| `TRACE_ID` | `trace-xyz` | ID per distributed tracing |

### Pod Deployment (warm, ExecutionMode=DEPLOYMENT)

| Variabile | Esempio | Descrizione |
|---|---|---|
| `FUNCTION_NAME` | `word-stats` | Nome della funzione |
| `WARM` | `true` | Flag che indica modalita' warm |
| `TIMEOUT_MS` | `30000` | Timeout in millisecondi |
| `EXECUTION_MODE` | `HTTP` | RuntimeMode sottostante |
| `WATCHDOG_CMD` | `java -jar /app/app.jar` | Comando per avviare il runtime |

**Differenza chiave**: nel warm mode non ci sono `EXECUTION_ID`,
`CALLBACK_URL`, `INVOCATION_PAYLOAD` come ENV. Arrivano nel body di ogni
richiesta HTTP al watchdog.

---

## Risorse K8s per funzione (modalita' DEPLOYMENT)

```
+-- Namespace: nanofaas ----------------------------------------+
|                                                                |
|  Funzione "image-resize"                                       |
|  +-----------------------+   +--------------------+            |
|  |  Deployment           |   |  Service (ClusterIP)|           |
|  |  fn-image-resize      |   |  fn-image-resize    |          |
|  |  replicas: 3          |   |  port: 8080         |          |
|  |                       |   |  selector:           |          |
|  |  +------+ +------+   |   |    function:          |          |
|  |  | Pod  | | Pod  |   |<--|    image-resize       |          |
|  |  +------+ +------+   |   +--------------------+            |
|  |  +------+             |                                     |
|  |  | Pod  |             |   +--------------------+            |
|  |  +------+             |   |  HPA (opzionale)   |           |
|  +-----------------------+   |  min: 1, max: 10   |           |
|                              |  metric: cpu 50%   |           |
|                              +--------------------+            |
|                                                                |
|  Funzione "word-stats"                                         |
|  +-----------------------+   +--------------------+            |
|  |  Deployment           |   |  Service (ClusterIP)|           |
|  |  fn-word-stats        |   |  fn-word-stats      |          |
|  |  replicas: 1          |   |  port: 8080         |          |
|  +-----------------------+   +--------------------+            |
+----------------------------------------------------------------+
```

---

## Come si usa il watchdog

### Caso 1: Funzione Java con runtime nanofaas

L'utente scrive un handler Java e lo pacchettizza con il function-runtime:

```
Dockerfile:
  FROM eclipse-temurin:21-jre
  COPY build/libs/my-function.jar /app/app.jar
  ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

Il jar contiene sia il function-runtime (server HTTP Spring Boot) sia
l'handler utente. L'handler viene scoperto automaticamente via Spring
component scan grazie all'annotazione `@NanofaasFunction`.

**Il watchdog non serve** in questo caso se usi DEPLOYMENT mode, perche'
il runtime Java stesso espone `/invoke` e `/health`. Il control plane
invia richieste direttamente al Service K8s.

**Il watchdog serve** in JOB mode (REMOTE) perche' gestisce il callback
al control plane e il timeout.

### Caso 2: Funzione Java con watchdog (Dockerfile.combined)

```
+-------------------------------------------+
|  Container                                |
|                                           |
|  ENTRYPOINT: /watchdog                    |
|       |                                   |
|       +-- spawn --> java -jar /app/app.jar|
|                          |                |
|                    Spring Boot server     |
|                    POST /invoke           |
|                          |                |
|                    HandlerRegistry        |
|                    (SPI discovery)        |
|                          |                |
|                    @NanofaasFunction      |
|                    MyHandler.handle()     |
+-------------------------------------------+
```

### Caso 3: Funzione Python

```
+-------------------------------------------+
|  Container                                |
|                                           |
|  ENTRYPOINT: gunicorn app:app             |
|       |                                   |
|       +-- Flask server su :8080           |
|              |                            |
|              POST /invoke                 |
|              |                            |
|              importlib.import_module()    |
|              handler.handle(request)      |
+-------------------------------------------+
```

### Caso 4: Eseguibile generico (STDIO mode)

```
+-------------------------------------------+
|  Container                                |
|                                           |
|  ENTRYPOINT: /watchdog                    |
|  WATCHDOG_CMD: /app/my-binary             |
|  EXECUTION_MODE: STDIO                    |
|       |                                   |
|       +-- spawn --> /app/my-binary        |
|       +-- write stdin: {"input":"..."}    |
|       +-- close stdin (EOF)               |
|       +-- read stdout: {"result":"..."}   |
|       +-- send callback                   |
+-------------------------------------------+
```

Qui l'eseguibile puo' essere **qualsiasi cosa**: un binary Go, Rust, C,
uno script Node.js, un programma compilato nativamente.

---

## Posso usare un eseguibile compilato?

**Si', assolutamente.** Ci sono tre approcci:

### Approccio 1: Eseguibile con server HTTP integrato

L'eseguibile espone un server HTTP su porta 8080 con endpoint `/invoke`
e `/health`. Non serve il watchdog in DEPLOYMENT mode.

```
FROM scratch
COPY my-native-binary /app/handler
EXPOSE 8080
ENTRYPOINT ["/app/handler"]
```

Registrazione:
```json
{
  "name": "my-func",
  "image": "my-registry/my-native-func:1.0",
  "executionMode": "DEPLOYMENT"
}
```

Il binary deve:
- Ascoltare su `0.0.0.0:8080`
- Accettare `POST /invoke` con body JSON
- Rispondere con JSON
- Esporre `GET /health` che ritorna 200

### Approccio 2: Eseguibile con watchdog (HTTP mode)

Come sopra ma il watchdog gestisce lifecycle, health check e callback:

```
FROM scratch
COPY --from=watchdog /watchdog /watchdog
COPY my-native-binary /app/handler
ENV WATCHDOG_CMD="/app/handler"
ENV EXECUTION_MODE=HTTP
ENTRYPOINT ["/watchdog"]
```

### Approccio 3: Eseguibile semplice con watchdog (STDIO mode)

L'eseguibile legge JSON da stdin e scrive JSON su stdout. Non serve un
server HTTP. Ideale per CLI tools, script, binary semplici.

```
FROM scratch
COPY --from=watchdog /watchdog /watchdog
COPY my-cli-tool /app/handler
ENV WATCHDOG_CMD="/app/handler"
ENV EXECUTION_MODE=STDIO
ENTRYPOINT ["/watchdog"]
```

### Approccio 4: Script/processo con watchdog (FILE mode)

Il processo legge da file e scrive su file. Utile per bash scripts o
programmi legacy.

```
FROM alpine
COPY --from=watchdog /watchdog /watchdog
COPY process.sh /app/process.sh
ENV WATCHDOG_CMD="/app/process.sh"
ENV EXECUTION_MODE=FILE
ENTRYPOINT ["/watchdog"]
```

---

## Posso compilare le funzioni Java nativamente?

**Si'.** Una funzione Java puo' essere compilata con GraalVM native-image
per ottenere un binary nativo senza JVM. Questo riduce drasticamente
il tempo di avvio e il consumo di memoria.

### Come funziona

```
COMPILAZIONE

  Codice Java          GraalVM native-image           Binary nativo
  +-------------+      +-------------------+         +-------------+
  | MyHandler   | ---> | AOT compilation   | ------> | my-func     |
  | + SDK       |      | + analisi statica |         | ~40-80 MB   |
  | + Runtime   |      | + SubstrateVM     |         | avvio <0.1s |
  +-------------+      +-------------------+         +-------------+


IMMAGINE DOCKER

  +-------------------------------------------+
  |  FROM scratch  (o distroless)             |
  |  COPY my-func /app/handler                |
  |  ~40-80 MB totali                         |
  |                                           |
  |  vs                                       |
  |                                           |
  |  FROM eclipse-temurin:21-jre              |
  |  COPY app.jar /app/app.jar                |
  |  ~200-300 MB totali                       |
  +-------------------------------------------+
```

### Confronto avvio

```
  JVM (temurin:21)       Nativo (GraalVM)
  +-----------------+    +-----------------+
  | Avvio: 1-3 sec  |    | Avvio: <100 ms  |
  | RAM: ~150 MB    |    | RAM: ~30 MB     |
  | Immagine: ~300MB|    | Immagine: ~80MB |
  | Throughput: +++  |    | Throughput: ++  |
  +-----------------+    +-----------------+
```

### Come compilare una funzione Java nativa

1. Il progetto usa Spring Boot con il plugin GraalVM:

```groovy
// build.gradle della funzione
plugins {
    id 'org.springframework.boot'
    id 'org.graalvm.buildtools.native'
}

dependencies {
    implementation project(':function-sdk-java')
}
```

2. Compilare:

```bash
./gradlew :examples:java:json-transform:nativeCompile
```

3. Il binary nativo risultante include:
   - SubstrateVM (runtime GraalVM minimale)
   - Spring Boot (AOT-processed)
   - function-runtime (InvokeController, HandlerRegistry)
   - Il tuo handler

4. Dockerfile nativo:

```dockerfile
FROM gcr.io/distroless/static
COPY build/native/nativeCompile/my-function /app/handler
EXPOSE 8080
ENTRYPOINT ["/app/handler"]
```

### Limitazioni del native build

- **Reflection**: Spring AOT lo gestisce automaticamente, ma librerie
  esterne che usano reflection pesante possono richiedere configurazione
  aggiuntiva (`RuntimeHints`)
- **Tempo di compilazione**: 1-3 minuti vs pochi secondi per il JVM build
- **Throughput**: dopo il warmup la JVM puo' essere piu' veloce del nativo
  per workload CPU-intensive (JIT vs AOT)
- **Debugging**: piu' difficile in nativo

### Quando conviene il nativo

| Scenario | JVM | Nativo |
|---|---|---|
| Cold start critico | No | **Si'** |
| Tante repliche (costo RAM) | No | **Si'** |
| Scale-to-zero | No | **Si'** |
| CPU-intensive prolungato | **Si'** | No |
| Sviluppo/debug veloce | **Si'** | No |

---

## Riepilogo: cosa c'e' dentro un pod

```
+================================================================+
|                        POD FUNZIONE                            |
|================================================================|
|                                                                |
|  Layer 1: WATCHDOG (opzionale in DEPLOYMENT mode)              |
|  +----------------------------------------------------------+  |
|  |  Binary Rust statico (~2 MB)                             |  |
|  |  Ruolo: process supervisor, HTTP proxy (warm),           |  |
|  |         health check, timeout, callback, tracing         |  |
|  |  Modalita': HTTP | STDIO | FILE | WARM                   |  |
|  +----------------------------------------------------------+  |
|           |                                                    |
|           | spawn (WATCHDOG_CMD)                               |
|           v                                                    |
|  Layer 2: RUNTIME                                              |
|  +----------------------------------------------------------+  |
|  |  Java: Spring Boot (function-runtime + handler)          |  |
|  |    - InvokeController: POST /invoke                      |  |
|  |    - HandlerRegistry: scopre @NanofaasFunction via scan  |  |
|  |    - CallbackClient: POSTa risultato al control plane    |  |
|  |    - TraceLoggingFilter: MDC con executionId + traceId   |  |
|  |                                                          |  |
|  |  Python: Flask + Gunicorn                                |  |
|  |    - POST /invoke                                        |  |
|  |    - importlib carica handle() da modulo configurabile   |  |
|  |    - callback con retry                                  |  |
|  |                                                          |  |
|  |  Nativo: qualsiasi eseguibile                            |  |
|  |    - HTTP server su :8080 (mode HTTP)                    |  |
|  |    - oppure stdin/stdout (mode STDIO)                    |  |
|  |    - oppure file I/O (mode FILE)                         |  |
|  +----------------------------------------------------------+  |
|           |                                                    |
|           | invoca                                             |
|           v                                                    |
|  Layer 3: CODICE UTENTE                                        |
|  +----------------------------------------------------------+  |
|  |  Java:   class MyHandler implements FunctionHandler      |  |
|  |          Object handle(InvocationRequest request)        |  |
|  |                                                          |  |
|  |  Python: def handle(request: dict) -> dict               |  |
|  |                                                          |  |
|  |  Altro:  qualsiasi logica che risponde JSON              |  |
|  +----------------------------------------------------------+  |
+================================================================+
```
