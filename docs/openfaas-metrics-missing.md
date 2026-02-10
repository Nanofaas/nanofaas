# Metriche OpenFaaS Mancanti (Nanofaas Parity Gap)

Data: 2026-02-10

Questo file elenca le metriche (nomi OpenFaaS) che **non sono disponibili 1:1** in Nanofaas **con l'implementazione attuale** (Prometheus embedded + compat rules + /metrics su function pods).

Legenda:
- `MISSING`: non esiste oggi (ne come metrica nativa, ne come recording rule equivalente).
- `PARTIAL`: esiste ma con semantica/label diverse o approssimata.
- `N/A`: non applicabile senza introdurre componenti OpenFaaS-like (es. queue-worker esterno, provider faas-netes).

## OpenFaaS CE: metriche mancanti o parziali

- `gateway_service_count` (`MISSING`)
  - Conteggio servizi/funzioni osservate dal gateway.
- `gateway_service_ready_count` (`MISSING`)
  - Conteggio servizi "ready".
- `gateway_http_request_total` (`MISSING`)
  - Richiede mapping dalle metriche HTTP del control-plane (oggi non esportato con questo nome).
- `gateway_http_request_duration_seconds` (`MISSING`)
  - Richiede mapping dalle metriche HTTP del control-plane (oggi non esportato con questo nome).
- `gateway_function_invocation_duration_seconds_bucket` (`MISSING`)
  - Oggi esponiamo solo `*_sum` e `*_count` (best-effort); non ci sono bucket histogram.
- `gateway_function_invocation_duration_seconds` (serie base senza suffisso) (`PARTIAL`)
  - In OpenFaaS e' un histogram; in Nanofaas oggi abbiamo solo `*_sum`/`*_count` derivati.
- `gateway_function_invocation_started` (`PARTIAL`)
  - In Nanofaas viene approssimata a `function_dispatch_total` tramite recording rule (non identica a "started" OpenFaaS).
- `gateway_function_invocation_inflight` (`PARTIAL`)
  - Approssimata con `function_in_flight` / `function_inFlight` (nome e semantica possono differire).

## OpenFaaS Pro: metriche mancanti o parziali

- `gateway_service_min` (`MISSING`)
  - Min replicas per funzione.
- `gateway_service_max` (`MISSING`)
  - Max replicas per funzione.
- `gateway_service_target` (`PARTIAL`)
  - In Nanofaas esiste `gateway_service_target_load{function,scaling_type}`; manca la metrica OpenFaaS "singola" e l'eventuale label-set atteso.
- `gateway_service_capacity` (`PARTIAL`)
  - Possibile derivarla da `gateway_service_target_load{scaling_type="capacity"}` ma oggi non c'e' una recording rule con quel nome.
- `gateway_backend_request_duration_seconds` (`MISSING`)
  - OpenFaaS Pro misura il backend/provider; Nanofaas non ha una metrica equivalente oggi.
- `queue_worker_messages_received_total` (`N/A` oggi)
  - Nanofaas non ha un queue-worker OpenFaaS esterno; andrebbe strumentato/aggiunto un componente equivalente o registrare contatori del nostro scheduler/queue.
- `queue_worker_messages_processed_total` (`N/A` oggi)
  - Come sopra.
- `queue_worker_messages_processed_duration_seconds` (`N/A` oggi)
  - Come sopra.
- `queue_worker_messages_in_flight` (`N/A` oggi)
  - Come sopra.

## Metriche OpenFaaS di componenti non presenti in Nanofaas (non applicabili)

Queste metriche tipicamente provengono da componenti OpenFaaS specifici (provider, queue, NATS, faas-netes/faasd, ecc.) e non sono riproducibili 1:1 senza introdurre quei componenti o un equivalente architetturale:

- `faas_netes_*` (`N/A`)
- `nats_*` / `stan_*` / metriche del queue-worker OpenFaaS (`N/A`)
- metriche specifiche dell'API gateway OpenFaaS (oltre a quelle elencate sopra) (`N/A` senza gateway equivalente)

