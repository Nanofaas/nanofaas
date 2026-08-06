# SonarQube Python Implementation Plan — 8 findings

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Portare a 0 gli 8 findings aperti (4 MINOR, 3 MAJOR, 1 CRITICAL) del progetto `nanofaas-python`, tutti in `sdks/python/src/nanofaas/runtime/app.py`.

**Architecture:** 2 task sequenziali sull'unico file: T1 applica le 3 regole meccaniche (S8410 Annotated ×4, S8415 responses ×2, S8572 logging.exception ×1), T2 rifattorizza `invoke()` per la complessità cognitiva (S3776, 19→sotto 15) con 3 helper estratti a codice prescritto. Gate pytest per task, verifica con re-scan `sonar-scanner`.

**Tech Stack:** Python 3 (uv project in `sdks/python/`, venv esistente), FastAPI, pytest.

## Global Constraints

- **Behavior-preserving**: nessun cambiamento di comportamento osservabile. Per S3776: ordine delle operazioni (cold-start prima del callback), messaggi di errore, header di risposta, metriche Prometheus identici.
- **Nessun NOSONAR / suppression**.
- **New findings in scope**: se un fix ne introduce di nuovi (import inutilizzati, parametri non-Annotated, ecc.), risolverli nello stesso task; il re-scan finale deve dare 0 aperti.
- **Gate per task**: `cd sdks/python && .venv/bin/pytest tests/` verde (progetto uv; venv a `sdks/python/.venv`).
- **Niente trailer Co-Authored-By** nei commit (repo a autore singolo).
- Commit piccoli, messaggi `fix(sonar): <rule>: <descrizione>`.
- Lingua: italiano nelle comunicazioni; commit in inglese.
- Linee mobili: i numeri di riga della spec si riferiscono a main @ 582df3f2; localizzare per contenuto.

---

### Task 1: S8410 + S8415 + S8572 — Annotated, responses, logging.exception (7)

**Files:**
- Modify: `sdks/python/src/nanofaas/runtime/app.py` (solo righe interessate; nessun altro file)

**Occorrenze:** spec `docs/superpowers/specs/2026-08-06-sonarqube-python-design.md` (triage per regola).

- [ ] **Step 1: S8410 ×4 — params header di `invoke()` (righe ~229-232)**

Before:
```python
    x_execution_id: str | None = Header(None),
    x_trace_id: str | None = Header(None),
    x_callback_url: str | None = Header(None),
    x_dispatch_attempt: str | None = Header(None),
```
After:
```python
    x_execution_id: Annotated[str | None, Header()] = None,
    x_trace_id: Annotated[str | None, Header()] = None,
    x_callback_url: Annotated[str | None, Header()] = None,
    x_dispatch_attempt: Annotated[str | None, Header()] = None,
```
Aggiungere `from typing import Annotated` al blocco import `typing` (in ordine alfabetico). `Header` resta importato da fastapi. DI FastAPI identica.

- [ ] **Step 2: S8415 ×2 — documentare le HTTPException nel decorator (riga ~226)**

`@app.post("/invoke")` → aggiungere il parametro `responses` documentando i due raise del body (400 riga ~272, 500 riga ~279):
```python
@app.post(
    "/invoke",
    responses={
        400: {"description": "Execution ID required"},
        500: {"description": "No function registered with @nanofaas_function"},
    },
)
```
Solo metadata OpenAPI; il comportamento non cambia.

- [ ] **Step 3: S8572 ×1 — logging.exception nel lifespan (riga ~85)**

Before: `logger.error(f"Failed to load handler module {HANDLER_MODULE}: {e}", exc_info=True)`
After: `logger.exception(f"Failed to load handler module {HANDLER_MODULE}: {e}")`
(equivalente: stesso messaggio, exc_info automatico; `e` resta usato nel messaggio).

- [ ] **Step 4: Gate pytest**

Run: `cd sdks/python && .venv/bin/pytest tests/ -q`
Expected: verde (stesso risultato del baseline).

- [ ] **Step 5: Commit**

```bash
git commit -am "fix(sonar): S8410 Annotated deps, S8415 responses metadata, S8572 logging.exception (7 findings)"
```

---

### Task 2: S3776 — ridurre la complessità cognitiva di invoke() (1)

**Files:**
- Modify: `sdks/python/src/nanofaas/runtime/app.py`

**Obiettivo:** Cognitive Complexity di `invoke()` da 19 a < 15 (soglia), estraendo 3 helper con i codici prescritti. Behavior-preserving.

- [ ] **Step 1: Estrarre `_consume_cold_start()`** (prima di `_schedule_callback`, riga ~218)

```python
def _consume_cold_start() -> bool:
    """Atomically read and clear the first-invocation flag."""
    global _first_invocation
    with _cold_start_lock:
        is_cold_start = _first_invocation
        _first_invocation = False
    return is_cold_start
```

- [ ] **Step 2: Estrarre `_build_cold_start_headers()`** (accanto al precedente)

```python
def _build_cold_start_headers(is_cold_start: bool) -> dict[str, str]:
    """Return cold-start response headers, incrementing the cold-start metrics."""
    if not is_cold_start:
        return {}
    init_duration_ms = int((time.monotonic() - CONTAINER_START_TIME) * 1000)
    RUNTIME_COLD_START_TOTAL.labels(function=FUNCTION_NAME).inc()
    RUNTIME_INIT_DURATION_SECONDS.labels(function=FUNCTION_NAME).observe(init_duration_ms / 1000.0)
    return {"X-Cold-Start": "true", "X-Init-Duration-Ms": str(init_duration_ms)}
```

- [ ] **Step 3: Estrarre `_fail_response()`** (accanto ai precedenti)

Unifica SOLO i rami JSONDecodeError e TimeoutError (forma identica; il ramo generico resta invariato perché usa shape diverse: callback con dict, content con `str(e)`).

```python
def _fail_response(
    background_tasks: BackgroundTasks,
    callback_url: str | None,
    execution_id: str,
    trace_id: str | None,
    x_dispatch_attempt: str | None,
    *,
    status_code: int,
    code: str,
    message: str,
    count_failure: bool,
) -> JSONResponse:
    """Build the shared failure response: error body, callback, optional failure counter."""
    error = {"code": code, "message": message}
    if count_failure:
        RUNTIME_INVOCATIONS_TOTAL.labels(function=FUNCTION_NAME, success="false").inc()
    if callback_url:
        _schedule_callback(
            background_tasks,
            callback_url,
            execution_id,
            trace_id,
            {"success": False, "output": None, "error": error},
            x_dispatch_attempt,
        )
    return JSONResponse(status_code=status_code, content={"error": error})
```

- [ ] **Step 4: Riscrivere il body di `invoke()`**

- Sostituire il blocco cold-start (righe ~281-284) con: `is_cold_start = _consume_cold_start()`
- Sostituire il blocco header cold-start (righe ~309-315) con: `headers = _build_cold_start_headers(is_cold_start)` (la variabile `headers` resta usata dal `return`)
- Sostituire i rami except:

```python
    except json.JSONDecodeError:
        return _fail_response(
            background_tasks, callback_url, execution_id, trace_id, x_dispatch_attempt,
            status_code=400, code="INVALID_JSON",
            message="Request body must be valid JSON", count_failure=False,
        )
    except asyncio.TimeoutError:
        return _fail_response(
            background_tasks, callback_url, execution_id, trace_id, x_dispatch_attempt,
            status_code=504, code="HANDLER_TIMEOUT",
            message="Handler exceeded configured timeout", count_failure=True,
        )
```
- Il ramo `except Exception as e` e il `finally` restano **invariati**.
- Non toccare: docstring, firma, percorso di successo (salvo la sostituzione degli header), metriche.

- [ ] **Step 5: Verificare la complessità e il gate**

Run: `cd sdks/python && .venv/bin/pytest tests/ -q` → verde.
Verifica complessità: la scansione finale (sezione Verifica) confermerà < 15. Se dopo la scansione risultasse ancora ≥ 15, iterare estraendo un ulteriore helper (candidato: il ramo `except Exception` con la sua shape) nello stesso task prima del commit.

- [ ] **Step 6: Commit**

```bash
git commit -am "fix(sonar): S3776 reduce invoke cognitive complexity (1 finding)"
```

---

## Verifica (post-task, a cura del controllore)

1. **Re-scan**: `sonar-scanner -Dsonar.host.url=http://127.0.0.1:9000 -Dsonar.token=$TOKEN -Dsonar.projectKey=nanofaas-python -Dsonar.projectName="nanofaas Python" -Dsonar.sources=sdks/python -Dsonar.exclusions="**/__pycache__/**,**/*.pyc"` (token in /tmp/sonar-token-165). Attendere `SUCCESS` via `/api/ce/component`.
2. **Query**: `/api/issues/search?componentKeys=nanofaas-python&statuses=OPEN,CONFIRMED,REOPENED&ps=100` → **0 aperti**. **Nota**: senza `statuses=` le query includono le risolte (lezione #166).
3. Eventuali residui: fix wave singolo con la lista completa, poi re-scan (attese 1-2 iterazioni).
4. Merge su main, gate pytest su main, push, creazione issue GitHub (non esiste per Python — prossimo numero) e chiusura con commento di risoluzione, ri-analisi GitNexus.
