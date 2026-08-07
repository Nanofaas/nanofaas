# SonarQube Docker Implementation Plan — 4 findings

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Portare a 0 i 4 findings aperti (docker:S6471, MINOR) dei Dockerfile del watchdog, aggiungendo la direttiva `USER` a ciascuna immagine runtime.

**Architecture:** 1 task su 4 file (`runtimes/watchdog/Dockerfile`, `Dockerfile.simple`, `Dockerfile.combined`, `tests/Dockerfile.test`) — una direttiva `USER` per immagine con UID esplicito. Gate: integration tests via Docker (`test.sh`). Verifica con re-scan `sonar-scanner`.

**Tech Stack:** Docker, build multi-stage, scratch/distroless/python-alpine, shell integration tests.

## Global Constraints

- **Behavior-preserving del watchdog**: il processo resta identico; cambia solo l'utente runtime (root → non-root, caso standard per function container). Il gate `test.sh` deve restare verde (build immagine test + integration tests HTTP/STDIO/FILE/callback).
- **Nessun NOSONAR / suppression**.
- **New findings in scope**: se i fix ne introducono (righe troppo lunghe, `adduser` senza pin), risolverli nello stesso task; il re-scan finale deve dare 0 aperti.
- **Gate per task**: `cd runtimes/watchdog && ./test.sh` verde (Docker richiesto — "Apple container").
- **Niente trailer Co-Authored-By** nei commit (repo a autore singolo).
- Commit piccolo, messaggio `fix(sonar): <rule>: <descrizione>`.
- Lingua: italiano nelle comunicazioni; commit in inglese.

---

### Task 1: docker:S6471 — USER non-root nelle 4 immagini runtime (4)

**Files:**
- Modify: `runtimes/watchdog/Dockerfile` (dopo il `COPY --from=builder /watchdog /watchdog`, prima di `ENTRYPOINT`)
- Modify: `runtimes/watchdog/Dockerfile.simple` (dopo il `COPY --from=builder /build/target/release/nanofaas-watchdog /watchdog`, prima di `ENTRYPOINT`)
- Modify: `runtimes/watchdog/Dockerfile.combined` (riga 34: dopo `FROM gcr.io/distroless/java25-debian13:nonroot`)
- Modify: `runtimes/watchdog/tests/Dockerfile.test` (dopo i `RUN chmod +x ...`, prima di `WORKDIR`)

**Occorrenze:** spec `docs/superpowers/specs/2026-08-07-sonarqube-docker-design.md` (triage per regola).

- [ ] **Step 1: `Dockerfile` (scratch)**

Aggiungere esattamente dopo la riga `COPY --from=builder /watchdog /watchdog` e prima di `ENTRYPOINT ["/watchdog"]`:
```dockerfile
# Run as non-root (UID 65534 = nobody; numeric UID works on scratch without /etc/passwd)
USER 65534:65534
```

- [ ] **Step 2: `Dockerfile.simple` (scratch)**

Aggiungere esattamente dopo la riga `COPY --from=builder /build/target/release/nanofaas-watchdog /watchdog` e prima di `ENTRYPOINT ["/watchdog"]`:
```dockerfile
# Run as non-root (UID 65534 = nobody; numeric UID works on scratch without /etc/passwd)
USER 65534:65534
```

- [ ] **Step 3: `Dockerfile.combined` (distroless nonroot)**

Aggiungere esattamente dopo la riga 34 `FROM gcr.io/distroless/java25-debian13:nonroot` (prima di `# Install minimal dependencies`):
```dockerfile
# The :nonroot tag already runs as UID 65532; make it explicit so the intent survives tooling
USER 65532
```
Non spostare i `RUN`/`COPY` successivi (i passi di build possono girare come root; la regola guarda l'utente di default dell'immagine finale).

- [ ] **Step 4: `tests/Dockerfile.test` (python alpine)**

Aggiungere esattamente dopo i due `RUN chmod +x ...` e prima di `# Default working directory`:
```dockerfile
# Run integration tests as non-root; tests only write under /tmp (world-writable)
RUN addgroup -S watchtest && adduser -S -G watchtest watchtest
USER watchtest
```

- [ ] **Step 5: Gate integration tests**

Run: `cd runtimes/watchdog && ./test.sh`
Expected: build dell'immagine test OK e tutti gli integration tests (HTTP, STDIO, FILE, callback) verdi. Se un test fallisce per permessi da non-root, correggere la causa alla radice nel Dockerfile (permessi/chown di ciò che serve) — mai tornare a root senza motivazione.

- [ ] **Step 6: Commit**

```bash
git commit -am "fix(sonar): S6471 run watchdog images as non-root (4 findings)"
```

---

## Verifica (post-task, a cura del controllore)

1. **Re-scan**: `sonar-scanner -Dsonar.host.url=http://127.0.0.1:9000 -Dsonar.token=$TOKEN -Dsonar.projectKey=nanofaas-rust -Dsonar.projectName="nanofaas Rust" -Dsonar.sources=runtimes/watchdog -Dsonar.rust.cargo.manifestPaths=runtimes/watchdog/Cargo.toml` (token in /tmp/sonar-token-165). Attendere `SUCCESS` via `/api/ce/component`.
2. **Query**: `/api/issues/search?componentKeys=nanofaas-rust&statuses=OPEN,CONFIRMED,REOPENED&ps=100` → **0 aperti**. **Nota**: senza `statuses=` le query includono le risolte (lezione #166).
3. Eventuali residui: fix wave singolo, poi re-scan.
4. Merge su main, gate `test.sh` su main, push, creazione issue GitHub (prossimo numero dopo #167) e chiusura con commento di risoluzione.
