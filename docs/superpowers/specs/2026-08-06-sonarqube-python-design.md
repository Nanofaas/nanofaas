# SonarQube Python — Findings (8) — Design

**Data:** 2026-08-06
**Fonte:** scansione fresca `sonar-scanner` su main @ 582df3f2 (2026-08-06 23:25), server locale sonar-sonata (porta 9000, admin/admin), progetto `nanofaas-python` (sorgenti `sdks/python`). Il server non aveva analisi Python (container ricreato 2026-08-05) — la scansione appena eseguita è la baseline vera: **8 findings aperti** in 4 regole, tutti in `sdks/python/src/nanofaas/runtime/app.py`.

## Decisioni utente (2026-08-06)

1. **Scope: tutti gli 8 findings.** Fix completo behavior-preserving, nessun suppression/NOSONAR.
2. **Gate per task:** `cd sdks/python && .venv/bin/pytest tests/` verde (progetto uv con venv esistente).
3. **Verifica:** re-scan `sonar-scanner` (stesso comando della baseline) → **0 findings aperti**.

## Triage per regola

| Regola | N | Severità | Tipo | Fix shape |
|---|---|---|---|---|
| S8410 | 4 | MINOR | CODE_SMELL | Parametri header di `invoke()` (righe 229-232): `x_execution_id: str \| None = Header(None)` → `x_execution_id: Annotated[str \| None, Header()] = None` (import `from typing import Annotated`; `Header` resta da fastapi). DI FastAPI identica. |
| S8415 | 2 | MAJOR | CODE_SMELL | Decorator `@app.post("/invoke")`: aggiungere `responses={400: {"description": "..."}, 500: {"description": "..."}}` documentando le due HTTPException (riga 272: 400 Execution ID required; riga 279: 500 no handler registered). Solo metadata OpenAPI, zero comportamento. |
| S3776 | 1 | CRITICAL | CODE_SMELL | `invoke()`: Cognitive Complexity 19 > 15 (soglia). Refactor behavior-preserving: estrarre helper da blocchi indipendenti (candidati: gestione cold-start con `_cold_start_lock`, schedulazione callback con `_schedule_callback` già esistente, parsing/validazione payload, gestione errore handler). I test esistenti (`test_runtime.py` su tutti i) coprono i percorsi. |
| S8572 | 1 | MAJOR | CODE_SMELL | Lifespan (riga 85): `logger.error(f"Failed to load handler module {HANDLER_MODULE}: {e}", exc_info=True)` → `logger.exception(f"Failed to load handler module {HANDLER_MODULE}: {e}")` (equivalente: stesso messaggio, exc_info automatico). Il parametro `e` resta usato. |

Totale: **8 findings chiusi via modifiche** (7 meccanici + 1 refactor coperto da test), **0 won't-fix**.

## Vincoli globali

- **Behavior-preserving**: nessun cambiamento di comportamento osservabile. Il refactor S3776 deve mantenere identici: ordine delle operazioni (cold-start prima del callback), messaggi di errore, header di risposta, metriche Prometheus.
- **Nessun NOSONAR / suppression** (convenzione #164-#166).
- **New findings in scope**: se il refactor ne introduce (es. S8410 su nuovi parametri, import inutilizzati), risolverli nello stesso task; il re-scan finale deve dare **0 aperti**.
- **Gate per task**: `cd sdks/python && .venv/bin/pytest tests/` verde.
- **Niente trailer Co-Authored-By** nei commit (repo a autore singolo).
- Commit piccolo, messaggio `fix(sonar): <rule>: <descrizione>` (convenzione #165).
- Lingua: italiano per le comunicazioni; commit in inglese.

## Verifica

1. Re-scan: `sonar-scanner -Dsonar.host.url=http://127.0.0.1:9000 -Dsonar.token=$TOKEN -Dsonar.projectKey=nanofaas-python -Dsonar.projectName="nanofaas Python" -Dsonar.sources=sdks/python -Dsonar.exclusions="**/__pycache__/**,**/*.pyc"` (token in /tmp/sonar-token-165). Attendere `SUCCESS` via `/api/ce/component`.
2. Query: `/api/issues/search?componentKeys=nanofaas-python&statuses=OPEN,CONFIRMED,REOPENED&ps=100` → **0 aperti**. **Nota:** senza `statuses=` le query includono le risolte (lezione #166).
3. Merge su main, suite `pytest` su main, push, creazione issue GitHub (non esiste — la numerazione Java è #164/#165/#166; il tranche Python prenderà il prossimo numero) e chiusura con commento di risoluzione.

## Fuori scope

- Findings Rust (4) — tranche separato, non toccato qui.
- Il contenitore sonar-sonata e la sua password (admin/admin) restano come sono.
