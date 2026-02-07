# nanofaas Feature Roadmap

Documento di riferimento per le funzionalita mancanti nella piattaforma nanofaas.
Ogni sezione descrive il problema, la soluzione proposta, l'impatto sulla piattaforma,
le dipendenze con altre feature e una stima di complessita.

> **Stato attuale (v0.5.0):** control-plane single-pod in-memory, execution via K8s Job
> o warm pool (POOL mode), runtime Java e Python, backpressure tramite SyncQueue,
> retry con max 3 tentativi, rate limit globale 1000 req/s, metriche Prometheus.

---

## Indice

1. [Autenticazione e Autorizzazione](#1-autenticazione-e-autorizzazione)
2. [Persistenza dello Stato](#2-persistenza-dello-stato)
3. [Alta Disponibilita del Control Plane](#3-alta-disponibilita-del-control-plane)
4. [Event Sources e Trigger](#4-event-sources-e-trigger)
5. [Autoscaling delle Funzioni](#5-autoscaling-delle-funzioni)
6. [Versionamento delle Funzioni](#6-versionamento-delle-funzioni)
7. [CLI e SDK](#7-cli-e-sdk)
8. [Circuit Breaker e Resilienza Avanzata](#8-circuit-breaker-e-resilienza-avanzata)
9. [Networking e Sicurezza di Rete](#9-networking-e-sicurezza-di-rete)
10. [Multi-Tenancy](#10-multi-tenancy)
11. [Observability Avanzata](#11-observability-avanzata)
12. [Helm Chart e GitOps](#12-helm-chart-e-gitops)
13. [Runtime Aggiuntivi](#13-runtime-aggiuntivi)
14. [Function Composition e Workflow](#14-function-composition-e-workflow)
15. [Developer Experience](#15-developer-experience)
16. [Custom Resource Definitions (CRD)](#16-custom-resource-definitions-crd)

---

## 1. Autenticazione e Autorizzazione

### Stato attuale
Nessun meccanismo di autenticazione. Tutti gli endpoint (`/v1/functions`, `/v1/functions/{name}:invoke`,
ecc.) sono pubblicamente accessibili da chiunque raggiunga il control-plane.
L'unico header di sicurezza e `Idempotency-Key` per deduplicazione, ma non ha valore autenticativo.

### Problema
Senza autenticazione, la piattaforma e utilizzabile solo in ambienti completamente isolati.
Qualsiasi client puo registrare funzioni, invocarle, eliminarle e leggere i risultati di esecuzione
altrui. In un cluster Kubernetes condiviso questo e inaccettabile.

### Soluzione proposta

**Fase 1 - API Key statica:**
- Aggiungere un filtro WebFlux che verifica l'header `Authorization: Bearer <api-key>`.
- Le API key sono configurate in `application.yml` o in un Secret Kubernetes.
- Endpoint `/v1/internal/*` escluso (callback dai pod funzione, comunicazione interna).

**Fase 2 - API Key per-tenant con RBAC:**
- Ogni API key e associata a un tenant ID e a un set di permessi.
- Permessi granulari: `functions:read`, `functions:write`, `functions:invoke`, `executions:read`.
- Persistenza delle key in database (vedi sezione 2).

**Fase 3 - OAuth2/OIDC (opzionale):**
- Integrazione con provider esterni (Keycloak, Auth0, Dex).
- Spring Security OAuth2 Resource Server per validazione JWT.
- Claim JWT mappati ai permessi RBAC.

### Componenti da creare/modificare
- `SecurityConfig.java` - Configurazione Spring Security WebFlux
- `ApiKeyAuthenticationFilter.java` - Filtro estrazione e validazione API key
- `TenantContext.java` - Thread-local/Reactor Context per tenant corrente
- `InvocationController`, `FunctionController` - Annotazioni `@PreAuthorize` o check programmatici

### Impatto performance
- Fase 1: overhead trascurabile (~0.01ms per lookup in HashSet).
- Fase 2: lookup in ConcurrentHashMap con cache locale, ~0.05ms.
- Fase 3: validazione JWT con chiave pubblica cached, ~0.5ms prima invocazione poi ~0.05ms.

### Dipendenze
- Fase 2 dipende da [Persistenza dello Stato](#2-persistenza-dello-stato) per storage key.
- Fase 2-3 dipende da [Multi-Tenancy](#10-multi-tenancy) per il concetto di tenant.

### Complessita stimata
- Fase 1: **Bassa** (1-2 giorni)
- Fase 2: **Media** (3-5 giorni)
- Fase 3: **Alta** (1-2 settimane)

---

## 2. Persistenza dello Stato

### Stato attuale
Tutto e in-memory:
- `FunctionRegistry` usa `ConcurrentHashMap<String, FunctionSpec>`.
- `ExecutionStore` usa `ConcurrentHashMap<String, StoredExecution>` con TTL 15 min.
- `IdempotencyStore` usa `ConcurrentHashMap<String, StoredKey>` con TTL 15 min.
- `QueueManager` usa `ConcurrentHashMap<String, FunctionQueueState>` con `ArrayBlockingQueue`.

Al riavvio del control-plane, **tutte le funzioni registrate, le esecuzioni in corso
e le code pendenti vengono perse**. I `CompletableFuture` dei client sincroni in attesa
ricevono un timeout.

### Problema
1. **Perdita definizioni funzioni:** dopo un restart bisogna ri-registrare tutte le funzioni.
2. **Perdita esecuzioni in-flight:** nessun recovery possibile.
3. **Perdita code:** le richieste enqueued ma non ancora dispatched scompaiono.
4. **Nessuna Dead Letter Queue:** le invocazioni che falliscono dopo max retry vengono
   completate con errore e poi evicted dal TTL. Non c'e modo di ispezionarle in seguito.

### Soluzione proposta

**Fase 1 - Persistenza del Function Registry:**
- Salvare `FunctionSpec` in un database (PostgreSQL o SQLite per semplicita).
- Al boot, `FunctionRegistry` carica tutte le funzioni dal DB.
- Le operazioni CRUD su funzioni scrivono prima in DB, poi aggiornano la cache in-memory.
- Modello: tabella `functions(name PK, spec JSONB, created_at, updated_at)`.

**Fase 2 - Dead Letter Queue:**
- Le esecuzioni che esauriscono i retry e hanno stato ERROR vengono persistite in una
  tabella `dead_letter_queue(id, function_name, execution_id, request JSONB, error JSONB, created_at)`.
- Endpoint REST per consultare e ri-sottomettere i messaggi DLQ:
  - `GET /v1/dlq` - lista messaggi
  - `POST /v1/dlq/{id}:replay` - re-invia l'invocazione
  - `DELETE /v1/dlq/{id}` - rimuovi manualmente

**Fase 3 - Code durevoli (opzionale, alternativa a in-memory):**
- Sostituire `ArrayBlockingQueue` con Redis Streams o una tabella DB `pending_tasks`.
- Consente recovery delle code dopo restart.
- Trade-off: aggiunge latenza (~1-5ms per operazione su Redis, ~5-20ms su PostgreSQL)
  rispetto ai ~0.001ms della coda in-memory.

### Impatto performance
- Fase 1: nessun impatto sul hot path (invocazione). Solo CRUD funzioni diventa piu lento
  (~5ms per scrittura DB vs ~0.01ms in-memory).
- Fase 2: overhead solo sul path di errore dopo max retry (~5ms per scrittura DLQ).
- Fase 3: impatto significativo su ogni enqueue/dequeue. Da valutare con benchmark.

### Tecnologie candidate
| Opzione | Latenza write | Complessita operativa | Note |
|---------|---------------|----------------------|------|
| PostgreSQL | ~5ms | Media | JSONB nativo, gia standard in K8s |
| SQLite | ~1ms | Bassa | File locale, no HA, buono per dev |
| Redis | ~1ms | Media | Ottimo per code, richiede deploy separato |
| etcd | ~2ms | Alta | Gia presente in K8s, ma sconsigliato per dati applicativi |

### Dipendenze
- Nessuna dipendenza bloccante. Puo essere implementata indipendentemente.
- [HA Control Plane](#3-alta-disponibilita-del-control-plane) richiede che la Fase 1 sia completata.

### Complessita stimata
- Fase 1: **Media** (3-5 giorni)
- Fase 2: **Bassa** (2-3 giorni)
- Fase 3: **Alta** (1-2 settimane, richiede benchmark estensivi)

---

## 3. Alta Disponibilita del Control Plane

### Stato attuale
Il control-plane e un Deployment Kubernetes con **1 sola replica** (`replicas: 1`
in `k8s/control-plane-deployment.yaml`). Contiene un singolo `Scheduler` thread
che consuma da tutte le code, e un `SyncScheduler` per la sync queue.

Se il pod crasha, Kubernetes lo riavvia, ma:
- Lo stato in-memory e perso (vedi sezione 2).
- Le connessioni HTTP dei client sincroni in attesa vengono chiuse.
- Il tempo di recovery e pari al tempo di startup Spring Boot (~3-8 secondi).

### Problema
Single point of failure. Un crash, un OOM kill, o un rolling update causa
un'interruzione completa della piattaforma.

### Soluzione proposta

**Approccio A - Active/Passive con leader election:**
- 2+ repliche del control-plane.
- Solo il leader esegue `Scheduler` e `SyncScheduler`.
- I follower gestiscono le API in read-only (GET funzioni, GET executions).
- Spring Integration Leader Election via Kubernetes Lease API.
- Al failover del leader, un follower acquisisce il lease e avvia gli scheduler.
- Richiede: stato condiviso (DB) per le definizioni funzioni e le esecuzioni.

**Approccio B - Sharding per funzione (piu complesso):**
- Ogni replica possiede un sottoinsieme di funzioni (consistent hashing).
- Le richieste vengono instradate alla replica corretta tramite un load balancer
  che ispeziona il path (`/v1/functions/{name}`).
- Vantaggi: throughput scala linearmente con le repliche.
- Svantaggi: complessita di rebalancing, split-brain risk.

**Raccomandazione:** Iniziare con Approccio A. Il throughput di un singolo pod
(migliaia di req/s con WebFlux) e sufficiente per la maggior parte degli use-case.
L'HA serve per resilienza, non per scalabilita orizzontale.

### Prerequisiti
- [Persistenza dello Stato](#2-persistenza-dello-stato) Fase 1 completata (le definizioni
  funzioni devono essere in un database condiviso).

### Componenti da creare/modificare
- `LeaderElectionConfig.java` - Bean Spring per Kubernetes Lease.
- `Scheduler.java` - Avvio condizionale: solo se leader.
- `SyncScheduler.java` - Avvio condizionale: solo se leader.
- `k8s/control-plane-deployment.yaml` - `replicas: 2`, aggiunta RBAC per Lease objects.

### Complessita stimata
- Approccio A: **Alta** (2-3 settimane)
- Approccio B: **Molto alta** (1-2 mesi)

---

## 4. Event Sources e Trigger

### Stato attuale
L'unico modo per invocare una funzione e tramite HTTP:
- `POST /v1/functions/{name}:invoke` (sincrono)
- `POST /v1/functions/{name}:enqueue` (asincrono)

Non esistono trigger automatici, scheduled job, o consumer da message broker.

### Problema
Limita nanofaas a scenari request/response. Molti use-case FaaS reali
richiedono invocazioni event-driven:
- Processing periodico (ETL, report, cleanup)
- Reazione a eventi (nuovo messaggio in coda, file caricato, webhook esterno)
- Fan-out da stream di dati

### Soluzione proposta

**Fase 1 - Cron Trigger:**
- Nuovo componente `CronTriggerManager` che usa `ScheduledExecutorService`.
- Configurazione nella `FunctionSpec`:
  ```json
  {
    "name": "daily-report",
    "schedule": "0 0 * * *",
    "image": "report-fn:latest"
  }
  ```
- Usa la libreria `cron-utils` per parsing espressioni cron.
- Al trigger, crea un'invocazione asincrona interna con payload vuoto o configurabile.

**Fase 2 - HTTP Webhook Trigger:**
- Endpoint dedicato `POST /v1/webhooks/{name}` che accetta payload arbitrario.
- Differenza con `:invoke`: non richiede autenticazione del chiamante (usa secret condiviso),
  pensato per integrazioni esterne (GitHub, Stripe, ecc.).
- Supporto per header-based routing e filtering.

**Fase 3 - Message Queue Trigger:**
- Consumer plugin per Kafka, RabbitMQ, o NATS.
- Architettura: un `EventSourceController` per ogni sorgente attiva.
- Configurazione nella `FunctionSpec`:
  ```json
  {
    "name": "order-processor",
    "eventSource": {
      "type": "kafka",
      "topic": "orders",
      "consumerGroup": "nanofaas-order-processor",
      "batchSize": 10
    }
  }
  ```
- Ogni messaggio diventa una `InvocationRequest` con il payload del messaggio.
- Commit offset solo dopo completamento (at-least-once semantics).

### Impatto performance
- Fase 1: nessun impatto. Il cron scheduler e un thread separato.
- Fase 2: overhead minimo, riusa il path di invocazione esistente.
- Fase 3: dipende dal throughput della sorgente. Kafka consumer puo saturare
  le code se non configurato correttamente. La backpressure della SyncQueue
  puo aiutare a limitare l'ingestion rate.

### Componenti da creare
- `CronTriggerManager.java` - Scheduler cron
- `WebhookController.java` - Endpoint webhook
- `EventSourceController.java` - Consumer generico
- `KafkaEventSource.java`, `RabbitEventSource.java` - Implementazioni specifiche
- Estensione di `FunctionSpec`: campi `schedule`, `eventSource`

### Dipendenze
- Fase 3 dipende da [Persistenza](#2-persistenza-dello-stato) per garantire at-least-once.
- Fase 2 dipende opzionalmente da [Autenticazione](#1-autenticazione-e-autorizzazione)
  per secret-based webhook validation.

### Complessita stimata
- Fase 1: **Bassa** (2-3 giorni)
- Fase 2: **Bassa** (2-3 giorni)
- Fase 3: **Alta** (2-3 settimane per la prima sorgente, 3-5 giorni per ogni successiva)

---

## 5. Autoscaling delle Funzioni

### Stato attuale
- **Modalita JOB (REMOTE):** un nuovo K8s Job per ogni invocazione. Lo "scaling" e implicito
  (ogni Job e un pod separato), ma il cold-start e significativo (seconds).
- **Modalita WARM (POOL):** il numero di pod warm e fisso, configurato manualmente
  nel Deployment della funzione. Non c'e scaling automatico.
- La concurrency per funzione (`FunctionSpec.concurrency`, default 4) limita quanti
  dispatch paralleli sono possibili, ma non crea/distrugge pod.

### Problema
- In modalita WARM, se il traffico aumenta i pod warm si saturano e le richieste
  finiscono in coda. Se il traffico cala, i pod restano allocati sprecando risorse.
- Non c'e modo di passare da 0 pod (idle) a N pod (sotto carico) automaticamente.

### Soluzione proposta

**Fase 1 - Scale-to-N per warm pool:**
- Il control-plane monitora la metrica `function_inFlight` e `function_queue_depth`.
- Quando `queue_depth > 0` per un tempo superiore a una soglia, il control-plane
  scala il Deployment warm della funzione (incrementa `replicas`).
- Quando `inFlight == 0` e `queue_depth == 0` per un cooldown period, scala a `minReplicas`.
- Richiede RBAC aggiuntivo: `apps/deployments` (get, update, patch).

**Fase 2 - Scale-to-zero:**
- Quando una funzione non riceve invocazioni per `scaleToZeroGracePeriod` (es. 5 minuti),
  il control-plane scala il Deployment a 0 repliche.
- La prossima invocazione scala a `minReplicas` (>= 1) e attende il readiness del pod.
- Il client percepisce un cold-start, ma le risorse del cluster sono liberate.
- Configurazione:
  ```yaml
  scaling:
    minReplicas: 0
    maxReplicas: 10
    scaleToZeroGracePeriod: 5m
    scaleUpThreshold: 1    # queue_depth che innesca scale-up
    cooldownPeriod: 30s
  ```

**Fase 3 - Integrazione con Kubernetes HPA (alternativa):**
- Invece di logica custom, esporre una custom metric via Prometheus Adapter
  e lasciare che l'HPA Kubernetes gestisca lo scaling.
- Pro: riusa l'infrastruttura K8s nativa.
- Contro: meno controllo fine-grained, latenza di reazione HPA (~15-30 secondi).

### Impatto performance
- Scale-up: cold-start di 2-10 secondi alla prima invocazione dopo scale-to-zero.
- Scale-down: nessun impatto sulle richieste in corso (graceful shutdown).
- Il controller di scaling gira in un thread separato, nessun impatto sul hot path.

### Dipendenze
- Richiede modalita WARM/POOL gia funzionante (gia implementata).
- Beneficia da [Observability Avanzata](#11-observability-avanzata) per metriche di scaling.

### Complessita stimata
- Fase 1: **Media** (1 settimana)
- Fase 2: **Media** (1 settimana, incrementale su Fase 1)
- Fase 3: **Bassa** (2-3 giorni, ma richiede Prometheus Adapter installato)

---

## 6. Versionamento delle Funzioni

### Stato attuale
`FunctionSpec` e identificata solo dal campo `name`. Registrare una funzione
con lo stesso nome sovrascrive la precedente. Non c'e history, rollback, o
coesistenza di versioni diverse.

### Problema
- Aggiornare una funzione in produzione e un'operazione distruttiva e non reversibile.
- Non e possibile fare canary deployment (es. 10% su nuova versione).
- Non c'e modo di tornare alla versione precedente se la nuova ha un bug.

### Soluzione proposta

**Fase 1 - Versioning con history:**
- Estendere `FunctionSpec` con campo `version` (intero auto-incrementante).
- La registrazione crea una nuova versione; la versione precedente resta in history.
- `GET /v1/functions/{name}/versions` - lista tutte le versioni.
- `GET /v1/functions/{name}/versions/{version}` - dettaglio versione.
- `POST /v1/functions/{name}:rollback?version=N` - ripristina versione N come attiva.
- Modello DB: `function_versions(name, version, spec JSONB, active BOOLEAN, created_at)`.

**Fase 2 - Traffic splitting:**
- Supporto per routing basato su peso:
  ```json
  {
    "name": "my-func",
    "traffic": [
      {"version": 3, "weight": 90},
      {"version": 4, "weight": 10}
    ]
  }
  ```
- Il `DispatcherRouter` seleziona la versione in base al peso (weighted random).
- Utile per canary deployment e A/B testing.

**Fase 3 - Blue/Green deployment:**
- Endpoint `POST /v1/functions/{name}:promote?version=N` che switcha
  atomicamente tutto il traffico dalla versione attiva alla nuova.
- Verifica di salute pre-switch (health check sulla nuova versione).

### Impatto performance
- Fase 1: nessun impatto. Il lookup della versione attiva e un read aggiuntivo
  nella cache in-memory.
- Fase 2: overhead trascurabile (~random number generation per weighted routing).

### Dipendenze
- Fase 1 dipende da [Persistenza](#2-persistenza-dello-stato) per storage versioni.
- Fase 2 beneficia da [Observability](#11-observability-avanzata) per metriche per-versione.

### Complessita stimata
- Fase 1: **Media** (3-5 giorni)
- Fase 2: **Media** (3-5 giorni)
- Fase 3: **Bassa** (1-2 giorni, incrementale su Fase 1)

---

## 7. CLI e SDK

### Stato attuale
L'interazione con nanofaas avviene solo tramite chiamate HTTP manuali (curl)
o attraverso l'OpenAPI spec (`openapi.yaml`) con client generati.
Non esiste un tool ufficiale da riga di comando o libreria client.

### Problema
- L'onboarding di nuovi sviluppatori richiede conoscenza dell'API REST.
- Operazioni comuni (deploy, invoke, logs) richiedono comandi curl lunghi.
- Non c'e scaffolding per creare nuove funzioni.

### Soluzione proposta

**Fase 1 - CLI minimale (Go):**
```bash
nanofaas function list
nanofaas function deploy --name my-func --image my-func:v1 --runtime java
nanofaas function invoke my-func --data '{"input": "hello"}'
nanofaas function delete my-func
nanofaas execution get <execution-id>
nanofaas execution list --function my-func --status error
```
- Scritto in Go per distribuzione come singolo binario.
- Configurazione via `~/.nanofaas/config.yaml` (endpoint, api-key).
- Output formattabile: JSON, table, YAML.

**Fase 2 - Scaffolding:**
```bash
nanofaas init --runtime python --name my-func
# Crea: my-func/handler.py, my-func/Dockerfile, my-func/function.yaml
```
- Template per ogni runtime supportato.
- `function.yaml` contiene la FunctionSpec in formato dichiarativo.
- `nanofaas deploy -f function.yaml` per deploy da file.

**Fase 3 - SDK client (Python, Java):**
- Libreria Python:
  ```python
  from nanofaas import Client
  client = Client("http://control-plane:8080", api_key="...")
  result = client.invoke("my-func", {"input": "hello"})
  ```
- Libreria Java (gia quasi possibile con WebClient, ma wrapper tipizzato).
- Generazione automatica da OpenAPI spec con `openapi-generator`.

### Dipendenze
- Beneficia da [Autenticazione](#1-autenticazione-e-autorizzazione) per supporto API key nel CLI.

### Complessita stimata
- Fase 1: **Media** (1-2 settimane)
- Fase 2: **Bassa** (2-3 giorni)
- Fase 3: **Bassa** per generazione da OpenAPI (1-2 giorni), **Media** per SDK custom (1 settimana)

---

## 8. Circuit Breaker e Resilienza Avanzata

### Stato attuale
- **Retry:** fino a `maxRetries` (default 3), immediato dopo il fallimento.
  Implementato in `InvocationService.completeExecution()`.
- **Timeout:** per-invocazione, configurabile in `FunctionSpec.timeoutMs` (default 30s).
  Implementato con `Mono.timeout()` per sync e `CompletableFuture.get(timeout)`.
- **Backpressure:** `SyncQueueService` rifiuta con 429 se coda piena o wait stimato alto.
- **Queue full su retry:** se la coda e piena durante un retry, il `CompletableFuture`
  viene completato con l'errore originale (fix recente).

Manca: circuit breaker, exponential backoff, bulkhead isolation, fallback.

### Problema
- Se una funzione fallisce sistematicamente (es. immagine Docker rotta, dependency esterna down),
  i retry continuano a creare Job Kubernetes inutili, sprecando risorse.
- Non c'e modo di "disabilitare temporaneamente" una funzione problematica.
- I fallimenti di una funzione possono indirettamente rallentare le altre
  (competizione per slot di concurrency a livello di cluster).

### Soluzione proposta

**Fase 1 - Exponential backoff sui retry:**
- Invece di retry immediato, aggiungere un delay crescente:
  `delay = baseDelay * 2^(attempt-1)` con jitter.
- Configurazione in `FunctionSpec`:
  ```json
  {
    "retryBackoff": {
      "baseDelayMs": 500,
      "maxDelayMs": 30000,
      "jitterFactor": 0.2
    }
  }
  ```
- Implementazione: il retry task viene inserito in una `DelayQueue` invece
  che direttamente nella coda della funzione.

**Fase 2 - Circuit breaker per funzione:**
- Stati: CLOSED (normale) -> OPEN (dopo N fallimenti consecutivi) -> HALF_OPEN (test).
- Parametri: `failureThreshold` (es. 5), `resetTimeout` (es. 60s).
- In stato OPEN, le invocazioni ritornano immediatamente con errore
  `CIRCUIT_OPEN` senza creare Job.
- In stato HALF_OPEN, una sola invocazione passa come test.
  Se ha successo, torna CLOSED. Se fallisce, torna OPEN.
- Componente: `CircuitBreakerRegistry` con `ConcurrentHashMap<String, CircuitBreaker>`.

**Fase 3 - Bulkhead isolation:**
- Ogni funzione ha gia un proprio `FunctionQueueState` con concurrency limit.
  Questo e gia una forma di bulkhead.
- Estensione: aggiungere un **cluster-level bulkhead** che limita il totale
  di Job Kubernetes attivi (per evitare di saturare il cluster).
- Configurazione: `nanofaas.k8s.maxTotalJobs: 100`.

### Impatto performance
- Fase 1: riduce carico inutile, migliora throughput complessivo.
- Fase 2: in stato OPEN, le risposte sono immediate (~0.01ms), liberando risorse.
- Fase 3: previene saturazione del cluster, protegge tutte le funzioni.

### Dipendenze
- Nessuna dipendenza bloccante. Puo essere implementato indipendentemente.

### Complessita stimata
- Fase 1: **Bassa** (2-3 giorni)
- Fase 2: **Media** (3-5 giorni)
- Fase 3: **Bassa** (1-2 giorni)

---

## 9. Networking e Sicurezza di Rete

### Stato attuale
- Il control-plane espone un `Service` Kubernetes di tipo `ClusterIP` su porte 8080/8081.
- La comunicazione tra pod funzione e control-plane avviene via HTTP non cifrato.
- Non c'e Ingress configurato. L'accesso esterno richiede `kubectl port-forward` o NodePort.
- Non ci sono Network Policies: i pod funzione possono comunicare con qualsiasi servizio nel cluster.

### Problema
- **Nessun TLS:** credenziali e payload transitano in chiaro.
- **Nessun Ingress:** non e possibile esporre la piattaforma a utenti esterni senza configurazione manuale.
- **Nessun isolamento di rete:** un pod funzione malevolo puo accedere al database, ad altri servizi, o al Kubernetes API server.

### Soluzione proposta

**Fase 1 - Ingress controller:**
- Aggiungere manifest `k8s/ingress.yaml` per NGINX Ingress o Traefik.
- Path-based routing: `/v1/*` -> control-plane:8080.
- TLS termination all'ingress con cert-manager per certificati automatici (Let's Encrypt).

**Fase 2 - Network Policies:**
- Policy per il control-plane: accetta traffico solo dall'Ingress e dai pod funzione.
- Policy per i pod funzione: permettono solo egress verso il control-plane callback URL
  e verso eventuali servizi dichiarati nella `FunctionSpec`.
- Blocco di default: `deny-all` ingress/egress nel namespace `nanofaas`.

**Fase 3 - mTLS (opzionale, con service mesh):**
- Integrazione con Istio o Linkerd per mTLS automatico tra tutti i pod.
- Sidecar proxy gestisce la cifratura trasparentemente.
- Vantaggio: zero-trust networking senza modifiche al codice.

### Componenti da creare
- `k8s/ingress.yaml` - Manifest Ingress
- `k8s/network-policies.yaml` - Network Policies
- `k8s/cert-manager/` - Configurazione cert-manager (opzionale)

### Impatto performance
- Fase 1: TLS termination aggiunge ~0.5-1ms al primo handshake, poi trascurabile con connection reuse.
- Fase 2: nessun impatto (Network Policies sono implementate a livello kernel/CNI).
- Fase 3: overhead sidecar proxy ~0.5-2ms per hop.

### Dipendenze
- Fase 1: richiede un Ingress controller installato nel cluster.
- Fase 3: richiede un service mesh installato.

### Complessita stimata
- Fase 1: **Bassa** (1-2 giorni)
- Fase 2: **Bassa** (1-2 giorni)
- Fase 3: **Media** (dipende dal service mesh, 3-5 giorni)

---

## 10. Multi-Tenancy

### Stato attuale
Nessun concetto di tenant. Tutte le funzioni condividono lo stesso namespace,
le stesse code, lo stesso rate limit globale, e le stesse metriche.

### Problema
Se nanofaas viene usato da piu team o clienti, non c'e modo di:
- Isolare le funzioni di un team da quelle di un altro.
- Applicare quote differenziate (es. team A ha 100 req/s, team B ha 1000 req/s).
- Addebitare l'uso delle risorse per team/progetto.
- Prevenire che un team monopolizzi le risorse del cluster.

### Soluzione proposta

**Fase 1 - Tenant come namespace logico:**
- Ogni funzione appartiene a un tenant (derivato dall'API key, vedi sezione 1).
- Il nome della funzione diventa `{tenant}/{function-name}`.
- Isolamento logico: un tenant non vede le funzioni degli altri.
- Le metriche includono il tag `tenant`.

**Fase 2 - Quote per-tenant:**
- `TenantQuotaManager` che applica limiti:
  - Max funzioni registrate
  - Max invocazioni/secondo
  - Max concurrency totale
  - Max Job Kubernetes attivi
- Configurazione via API o file:
  ```yaml
  tenants:
    team-a:
      maxFunctions: 50
      maxRequestsPerSecond: 500
      maxConcurrency: 20
    team-b:
      maxFunctions: 10
      maxRequestsPerSecond: 100
      maxConcurrency: 5
  ```

**Fase 3 - Isolamento a livello Kubernetes (opzionale):**
- Ogni tenant ottiene un namespace Kubernetes dedicato.
- I Job delle funzioni di un tenant girano nel suo namespace.
- Network Policies isolano i namespace tra loro.
- ResourceQuota Kubernetes per limitare CPU/memoria per namespace.

### Impatto performance
- Fase 1: overhead minimo (lookup tenant da contesto, ~0.01ms).
- Fase 2: check quota aggiuntivo per richiesta (~0.05ms, ConcurrentHashMap lookup).
- Fase 3: nessun overhead sul hot path (isolamento a livello infrastruttura).

### Dipendenze
- Fase 1: dipende da [Autenticazione](#1-autenticazione-e-autorizzazione) Fase 2.
- Fase 2: dipende da Fase 1.
- Fase 3: dipende da Fase 1 e da [Networking](#9-networking-e-sicurezza-di-rete) Fase 2.

### Complessita stimata
- Fase 1: **Media** (3-5 giorni)
- Fase 2: **Media** (3-5 giorni)
- Fase 3: **Alta** (1-2 settimane)

---

## 11. Observability Avanzata

### Stato attuale
- **Metriche Prometheus:** 15+ metriche via Micrometer (counter, gauge, timer, summary).
  Esposte su `:8081/actuator/prometheus`.
- **Logging:** SLF4J + Logback, MDC con `traceId` e `executionId`.
- **Health checks:** liveness e readiness su `:8081/actuator/health`.
- **Tracing:** header `X-Trace-Id` propagato ai pod funzione, ma **non esportato**
  a nessun backend di tracing.

### Problema
- Non c'e distributed tracing end-to-end (client -> control-plane -> function pod -> callback).
- Non ci sono dashboard Grafana predefinite.
- Non ci sono alerting rules per anomalie (error rate spike, latency degradation).
- Non c'e audit log (chi ha fatto cosa e quando).

### Soluzione proposta

**Fase 1 - OpenTelemetry tracing:**
- Aggiungere `io.opentelemetry:opentelemetry-spring-boot-starter`.
- Ogni invocazione crea uno span con attributi: function name, execution id, trace id.
- Lo span viene propagato ai pod funzione tramite header W3C Trace Context.
- Export a Jaeger, Zipkin, o OTLP collector.
- Configurazione:
  ```yaml
  otel:
    exporter:
      otlp:
        endpoint: http://otel-collector:4318
    traces:
      sampler:
        percentage: 10  # campiona 10% delle richieste
  ```

**Fase 2 - Dashboard Grafana:**
- Dashboard predefinita con pannelli per:
  - Throughput per funzione (req/s)
  - Latency P50/P95/P99 per funzione
  - Error rate per funzione
  - Queue depth e in-flight per funzione
  - Rate limit rejections
  - Cold-start latency (se disponibile)
- Distribuita come JSON in `k8s/grafana/nanofaas-dashboard.json`.

**Fase 3 - Alerting rules:**
- PrometheusRule per:
  - `function_error_total / function_success_total > 0.1` per 5 minuti (error rate > 10%).
  - `function_latency_ms{quantile="0.99"} > 10000` per 5 minuti (P99 > 10s).
  - `function_queue_depth > 80` per 2 minuti (coda quasi piena su default 100).
  - `up{job="nanofaas-control-plane"} == 0` per 30 secondi (control-plane down).

**Fase 4 - Audit log (opzionale):**
- Log strutturato per ogni operazione mutativa:
  ```json
  {"event": "function.created", "tenant": "team-a", "function": "my-func",
   "actor": "api-key-xxx", "timestamp": "..."}
  ```
- Scritto su stdout (raccolto dal log aggregator del cluster) o su tabella DB dedicata.

### Impatto performance
- Fase 1: con campionamento al 10%, overhead ~0.1ms per richiesta campionata.
  Senza campionamento, ~0.5-1ms per span creation + export.
- Fase 2-3: nessun impatto (componenti esterni).
- Fase 4: ~0.05ms per log entry aggiuntiva.

### Dipendenze
- Fase 1: richiede un OTLP collector nel cluster.
- Fase 2: richiede Grafana + Prometheus gia installati.
- Fase 4: beneficia da [Autenticazione](#1-autenticazione-e-autorizzazione) per tracciare l'attore.

### Complessita stimata
- Fase 1: **Media** (3-5 giorni)
- Fase 2: **Bassa** (1-2 giorni)
- Fase 3: **Bassa** (1 giorno)
- Fase 4: **Bassa** (1-2 giorni)

---

## 12. Helm Chart e GitOps

### Stato attuale
I manifest Kubernetes in `k8s/` sono YAML statici con valori hardcoded
(es. `image: nanofaas/control-plane:0.5.0`, `replicas: 1`).
Il deploy richiede applicazione manuale con `kubectl apply -f k8s/`.

### Problema
- Nessuna parametrizzazione: cambiare namespace, immagine, repliche richiede
  editing manuale dei file YAML.
- Nessun meccanismo di upgrade/rollback nativo.
- Non integrabile con pipeline GitOps (ArgoCD, Flux).

### Soluzione proposta

**Fase 1 - Helm chart:**
- Struttura:
  ```
  charts/nanofaas/
    Chart.yaml
    values.yaml
    templates/
      namespace.yaml
      serviceaccount.yaml
      rbac.yaml
      deployment.yaml
      service.yaml
      ingress.yaml (opzionale)
      configmap.yaml
  ```
- `values.yaml` parametrizza: image tag, replicas, resources, nanofaas config,
  ingress, service type, ecc.
- `helm install nanofaas ./charts/nanofaas --set image.tag=0.6.0`

**Fase 2 - GitOps con ArgoCD:**
- Aggiungere `ApplicationSet` template per ArgoCD.
- I valori di deploy per ogni ambiente (dev, staging, prod) sono in directory separate:
  ```
  environments/
    dev/values.yaml
    staging/values.yaml
    prod/values.yaml
  ```
- ArgoCD monitora il repo e applica automaticamente le modifiche.

### Dipendenze
- Nessuna dipendenza bloccante.

### Complessita stimata
- Fase 1: **Bassa** (2-3 giorni)
- Fase 2: **Bassa** (1-2 giorni)

---

## 13. Runtime Aggiuntivi

### Stato attuale
Due runtime ufficiali:
- **Java** (`function-runtime`): Spring Boot servlet, SPI handler loading.
- **Python** (`python-runtime`): Flask + Gunicorn, dynamic module loading.

Il contratto runtime e semplice: un server HTTP che accetta `POST /invoke`
e posta il risultato al `CALLBACK_URL`.

### Problema
Go e Node.js sono tra i linguaggi piu popolari per le funzioni serverless.
La loro assenza limita l'adozione della piattaforma.

### Soluzione proposta

**Fase 1 - Go runtime:**
- HTTP server minimale (net/http standard library).
- Handler interface:
  ```go
  type Handler func(ctx context.Context, input json.RawMessage) (any, error)
  ```
- Plugin loading via Go plugin system o compilazione statica.
- Vantaggi: startup estremamente veloce (~10ms), consumo memoria minimo (~5MB).

**Fase 2 - Node.js runtime:**
- Express.js o Fastify server.
- Handler: `module.exports.handle = async (input) => { return result; }`
- Loading dinamico via `require()`.
- Vantaggi: grande ecosistema NPM, familiare a molti sviluppatori.

**Fase 3 - Runtime generico (Dockerfile-based):**
- L'utente fornisce un Dockerfile che produce un container conforme al contratto:
  - Espone `POST /invoke` su porta 8080.
  - Legge `EXECUTION_ID`, `CALLBACK_URL` dalle env var.
- Il control-plane usa l'immagine direttamente nel Job senza runtime intermedio.
- Consente qualsiasi linguaggio senza supporto ufficiale.

### Contratto runtime (da rispettare per tutti)
```
ENV: EXECUTION_ID, CALLBACK_URL, FUNCTION_NAME
Headers in: X-Trace-Id, X-Execution-Id
Endpoint: POST /invoke
  Input: {"input": ..., "metadata": {...}}
  Output: qualsiasi JSON
Callback: POST {CALLBACK_URL}/{EXECUTION_ID}:complete
  Body: {"success": true/false, "output": ..., "error": {"code": "...", "message": "..."}}
```

### Impatto performance
- Go runtime: cold-start ~10-50ms (vs Java ~500ms-3s).
- Node.js runtime: cold-start ~100-300ms.
- Runtime generico: dipende dall'immagine utente.

### Dipendenze
- Nessuna dipendenza bloccante. I runtime sono moduli indipendenti.

### Complessita stimata
- Fase 1 (Go): **Media** (1 settimana)
- Fase 2 (Node.js): **Media** (1 settimana)
- Fase 3 (generico): **Bassa** (documentazione del contratto, 1-2 giorni)

---

## 14. Function Composition e Workflow

### Stato attuale
Ogni invocazione e atomica e indipendente. Non c'e modo di:
- Concatenare funzioni (output di A diventa input di B).
- Eseguire funzioni in parallelo e aggregare i risultati.
- Definire workflow con branching condizionale.

### Problema
Molti use-case reali richiedono pipeline multi-step: ad esempio,
validazione -> trasformazione -> arricchimento -> salvataggio.
Oggi l'utente deve orchestrare manualmente le chiamate dal client.

### Soluzione proposta

**Fase 1 - Chain semplice:**
- Nuovo campo in `FunctionSpec`:
  ```json
  {
    "name": "enricher",
    "chain": {
      "next": "saver",
      "passOutput": true
    }
  }
  ```
- Al completamento di `enricher`, il control-plane invoca automaticamente `saver`
  con l'output di `enricher` come input.
- Implementazione: hook in `InvocationService.completeExecution()`.

**Fase 2 - Workflow DAG (opzionale, alta complessita):**
- Definizione di workflow come grafo aciclico diretto (DAG):
  ```json
  {
    "name": "etl-pipeline",
    "steps": [
      {"id": "extract", "function": "extractor"},
      {"id": "transform", "function": "transformer", "dependsOn": ["extract"]},
      {"id": "validate", "function": "validator", "dependsOn": ["extract"]},
      {"id": "load", "function": "loader", "dependsOn": ["transform", "validate"]}
    ]
  }
  ```
- Workflow engine che esegue i passi rispettando le dipendenze.
- Parallelismo automatico per passi indipendenti.

### Impatto performance
- Fase 1: overhead di una invocazione asincrona aggiuntiva per step (~1ms).
- Fase 2: overhead del workflow engine, ma i singoli step sono paralleli.

### Dipendenze
- Fase 2 dipende da [Persistenza](#2-persistenza-dello-stato) per tracking stato workflow.

### Complessita stimata
- Fase 1: **Bassa** (2-3 giorni)
- Fase 2: **Molto alta** (3-6 settimane)

---

## 15. Developer Experience

### Stato attuale
- Documentazione: architettura, quickstart, API spec (OpenAPI).
- Build: Gradle, Spring Boot buildpacks.
- Test: E2E con Docker, K3s, kind.
- Nessun tool di sviluppo locale dedicato.

### Problema
- Lo sviluppo di funzioni richiede un cluster Kubernetes funzionante.
- Non c'e modo di testare localmente una funzione senza deployarla.
- Non c'e log streaming per debug in tempo reale.
- Non c'e UI web per gestione visuale.

### Soluzione proposta

**Fase 1 - Modalita sviluppo locale:**
- `./gradlew :control-plane:bootRun` gia funziona con `LocalDispatcher` (ExecutionMode.LOCAL).
- Estendere `LocalDispatcher` per caricare ed eseguire funzioni da un path locale:
  ```yaml
  nanofaas:
    dev:
      functionsDir: ./sample-functions
  ```
- La funzione viene eseguita in-process senza Docker o Kubernetes.

**Fase 2 - Log streaming:**
- Nuovo endpoint: `GET /v1/functions/{name}/logs?follow=true` (SSE stream).
- Il control-plane raccoglie i log dei pod funzione via Kubernetes Log API.
- Header `X-Trace-Id` per filtrare log di una specifica invocazione.
- CLI: `nanofaas logs my-func --follow --trace-id=abc123`.

**Fase 3 - Web UI (opzionale):**
- Single-page application (React o HTMX) per:
  - Lista funzioni con stato e metriche.
  - Invocazione manuale con editor JSON.
  - Visualizzazione esecuzioni e risultati.
  - Grafici real-time di throughput e latency.
- Servita dal control-plane su `/ui`.

### Impatto performance
- Fase 1: nessun impatto in produzione (attiva solo in dev mode).
- Fase 2: overhead di streaming log, ma e un endpoint separato.
- Fase 3: servire file statici, overhead trascurabile.

### Dipendenze
- Fase 2 per CLI: dipende da [CLI](#7-cli-e-sdk).
- Fase 3: indipendente.

### Complessita stimata
- Fase 1: **Bassa** (2-3 giorni)
- Fase 2: **Media** (3-5 giorni)
- Fase 3: **Alta** (2-4 settimane)

---

## 16. Custom Resource Definitions (CRD)

### Stato attuale
Le funzioni sono gestite tramite API REST. Non c'e integrazione nativa
con il modello dichiarativo Kubernetes. Per deployare una funzione bisogna
chiamare `POST /v1/functions`, non e possibile usare `kubectl apply`.

### Problema
- Non si integra con l'ecosistema Kubernetes nativo (GitOps, kubectl, kustomize).
- Non c'e reconciliation: se il control-plane perde lo stato,
  bisogna ri-registrare tutte le funzioni manualmente.
- Non e possibile usare `kubectl get functions` o `kubectl describe function my-func`.

### Soluzione proposta

**Fase 1 - CRD Function:**
- Definire un CRD:
  ```yaml
  apiVersion: nanofaas.io/v1
  kind: Function
  metadata:
    name: my-func
    namespace: nanofaas
  spec:
    image: my-func:v1
    runtime: python
    timeoutMs: 30000
    concurrency: 4
    env:
      API_KEY: "..."
  ```
- Il control-plane legge le risorse Function dal Kubernetes API e le sincronizza
  con il `FunctionRegistry` interno.

**Fase 2 - Operator con reconciliation loop:**
- Implementazione completa dell'operator pattern con Fabric8 o Java Operator SDK.
- Il controller monitora le risorse `Function` e garantisce che lo stato
  desiderato (CRD) corrisponda allo stato effettivo (registry + deployment warm).
- Gestisce automaticamente la creazione/aggiornamento/eliminazione.

### Impatto performance
- Fase 1: nessun impatto. Il watch sul CRD e asincrono.
- Fase 2: il reconciliation loop aggiunge un overhead minimo (periodic check).

### Dipendenze
- Fase 2 beneficia da [Persistenza](#2-persistenza-dello-stato) per confronto stato desiderato vs effettivo.
- Fase 2 beneficia da [Autoscaling](#5-autoscaling-delle-funzioni) per gestione deployment warm.

### Complessita stimata
- Fase 1: **Media** (1 settimana)
- Fase 2: **Alta** (2-3 settimane)

---

## Matrice Dipendenze

| Feature | Dipende da |
|---------|-----------|
| 1. Auth Fase 2-3 | 2. Persistenza, 10. Multi-Tenancy |
| 2. Persistenza | (nessuna) |
| 3. HA | 2. Persistenza Fase 1 |
| 4. Event Sources Fase 3 | 2. Persistenza |
| 5. Autoscaling | (nessuna, richiede warm mode gia presente) |
| 6. Versioning | 2. Persistenza |
| 7. CLI | (nessuna, beneficia da 1. Auth) |
| 8. Circuit Breaker | (nessuna) |
| 9. Networking | (nessuna, Ingress controller esterno) |
| 10. Multi-Tenancy | 1. Auth Fase 2 |
| 11. Observability | (nessuna, OTLP collector esterno) |
| 12. Helm Chart | (nessuna) |
| 13. Runtime aggiuntivi | (nessuna) |
| 14. Workflow Fase 2 | 2. Persistenza |
| 15. Dev Experience | (nessuna, log streaming richiede 7. CLI) |
| 16. CRD | (nessuna, Operator beneficia da 2 e 5) |

## Ordine di Implementazione Suggerito

Basato su dipendenze e impatto, un possibile ordine:

```
Fase 1 (fondamenta):    2. Persistenza  ->  1. Auth Fase 1  ->  12. Helm Chart
Fase 2 (resilienza):    8. Circuit Breaker  ->  9. Networking  ->  11. Observability
Fase 3 (scalabilita):   5. Autoscaling  ->  3. HA  ->  10. Multi-Tenancy
Fase 4 (ecosistema):    7. CLI  ->  13. Runtime  ->  4. Event Sources
Fase 5 (avanzato):      6. Versioning  ->  16. CRD  ->  14. Workflow  ->  15. Dev Experience
```

Ogni fase e indipendente dalle successive e puo essere rilasciata separatamente.
