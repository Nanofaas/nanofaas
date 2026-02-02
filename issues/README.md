# Issue Tracker - nanofaas

Questo documento elenca tutte le issue identificate durante l'analisi del codebase, ordinate per priorità di risoluzione.

## Indice delle Issue

| # | Titolo | Severità | Componente | Stato |
|---|--------|----------|------------|-------|
| 001 | [REMOTE dispatch non completa mai l'esecuzione](./001-remote-dispatch-never-completes.md) | CRITICA | Scheduler.java | ✅ DONE |
| 002 | [Race condition nel RateLimiter](./002-rate-limiter-race-condition.md) | ALTA | RateLimiter.java | ✅ DONE |
| 003 | [Memory leak in IdempotencyStore](./003-idempotency-store-memory-leak.md) | ALTA | IdempotencyStore.java | ✅ DONE |
| 004 | [Retry logic rompe le garanzie di idempotenza](./004-retry-breaks-idempotency.md) | ALTA | InvocationService.java | ✅ DONE |
| 005 | [Race condition tra canDispatch() e incrementInFlight()](./005-function-queue-state-race-condition.md) | MEDIA | FunctionQueueState.java | ✅ DONE |
| 006 | [Race condition in FunctionService.register()](./006-function-service-register-race.md) | MEDIA | FunctionService.java | ✅ DONE |
| 007 | [Data race tra campi volatile in ExecutionRecord](./007-execution-record-volatile-data-race.md) | MEDIA | ExecutionRecord.java | ✅ DONE |
| 008 | [CallbackClient ignora silenziosamente le eccezioni](./008-callback-client-swallows-exceptions.md) | MEDIA | CallbackClient.java | ✅ DONE |
| 009 | [KubernetesDispatcher non ha timeout](./009-kubernetes-dispatcher-no-timeout.md) | MEDIA | KubernetesDispatcher.java | ✅ DONE |
| 010 | [Scheduler non ha shutdown graceful](./010-scheduler-no-graceful-shutdown.md) | BASSA | Scheduler.java | ✅ DONE |
| 011 | [Validazione input mancante nei controller](./011-missing-input-validation.md) | BASSA | Controllers | ✅ DONE |
| 012 | [Resource leak - WebClient e RestClient](./012-resource-leaks-webclient-restclient.md) | BASSA | PoolDispatcher, CallbackClient | ✅ DONE |

## Legenda Severità

- **CRITICA**: Bug che causa perdita di dati o blocco del sistema
- **ALTA**: Bug che compromette funzionalità core o sicurezza
- **MEDIA**: Bug che causa comportamento inatteso ma non bloccante
- **BASSA**: Miglioramenti di qualità, performance o manutenibilità

## Legenda Stato

- ⬜ TODO - Da iniziare
- 🔄 IN PROGRESS - In lavorazione
- ✅ DONE - Completato e testato
- ❌ WONTFIX - Non verrà risolto (con motivazione)

## Ordine di Risoluzione Consigliato

### Fase 1: Bug Critici e Alti (Priorità Immediata)
1. **Issue 001** - REMOTE dispatch non completa → sistema inutilizzabile in modalità K8s
2. **Issue 002** - RateLimiter race → potenziale DoS
3. **Issue 003** - IdempotencyStore leak → OOM in produzione
4. **Issue 004** - Retry idempotency → retry non funzionano

### Fase 2: Bug Medi (Stabilità)
5. **Issue 005** - Queue state race → superamento concurrency limit
6. **Issue 006** - Register race → registrazione duplicata
7. **Issue 007** - ExecutionRecord race → stato inconsistente
8. **Issue 008** - CallbackClient → risultati persi
9. **Issue 009** - K8s timeout → sistema bloccato

### Fase 3: Qualità (Manutenibilità)
10. **Issue 010** - Graceful shutdown
11. **Issue 011** - Input validation
12. **Issue 012** - Resource leaks

## Come Usare le Issue

Ogni file issue contiene:

1. **Descrizione**: Spiegazione dettagliata del problema
2. **Codice problematico**: Snippet del codice attuale
3. **Impatto**: Conseguenze del bug
4. **Piano di risoluzione**: Step-by-step per la fix
5. **Test da creare**: Test cases per verificare la correzione
6. **File da modificare**: Lista dei file interessati
7. **Criteri di accettazione**: Checklist per considerare l'issue chiusa
8. **Note di sviluppo**: Spazio per appunti durante l'implementazione

## Workflow di Risoluzione

```
1. Leggere l'issue completamente
2. Verificare che il bug esista ancora (potrebbe essere già fixato)
3. Creare branch: git checkout -b fix/issue-XXX
4. Implementare la fix seguendo il piano
5. Scrivere i test indicati
6. Verificare i criteri di accettazione
7. Aggiornare stato issue: ✅ DONE
8. Aprire PR con riferimento all'issue
```

## Note Generali

- Le issue possono essere risolte in parallelo se non hanno dipendenze
- Issue 001-004 potrebbero avere impatto sulle altre, valutare se risolvere prima
- Ogni fix dovrebbe includere test di regressione
- Documentare eventuali decisioni di design nelle note di sviluppo

---

_Documento generato il 2026-01-25 durante analisi del codebase nanofaas_
