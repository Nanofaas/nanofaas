# SonarQube Docker/Watchdog — Findings (4) — Design

**Data:** 2026-08-07
**Fonte:** scansione fresca `sonar-scanner` su main @ 74687498 (2026-08-07 07:39), server locale sonar-sonata (porta 9000, admin/admin), progetto `nanofaas-rust` (key storica del tranche watchdog; sorgenti `runtimes/watchdog`). Il server non aveva analisi (container ricreato 2026-08-05) — la scansione appena eseguita è la baseline vera: **4 findings aperti**, tutti `docker:S6471` (MINOR, "image might run with root as the default user"), nei Dockerfile del watchdog. Nessun finding nel sorgente Rust vero e proprio.

## Decisioni utente (2026-08-07)

1. **Scope: tutti e 4 i findings.** Fix completo con direttiva `USER`, nessun suppression/NOSONAR.
2. **Gate per task:** `cd runtimes/watchdog && ./test.sh` verde (build immagine test + integration tests via Docker; verifica che il watchdog funzioni da non-root).
3. **Verifica:** re-scan `sonar-scanner` (stesso comando della baseline) → **0 findings aperti**.

## Triage per regola

| File | Riga | Fix shape |
|---|---|---|
| `runtimes/watchdog/Dockerfile` | 21 | Dopo il `COPY --from=builder /watchdog /watchdog` (e prima di ENTRYPOINT): `USER 65534:65534` — UID numerico di nobody, funziona su scratch senza `/etc/passwd`. |
| `runtimes/watchdog/Dockerfile.simple` | 16 | Idem (dopo il COPY, prima di ENTRYPOINT): `USER 65534:65534`. |
| `runtimes/watchdog/Dockerfile.combined` | 34 | Dopo il `FROM gcr.io/distroless/java25-debian13:nonroot` (riga 34): `USER 65532` — il base gira già non-root (UID 65532) ma l'analizzatore non riconosce il tag `:nonroot`; la direttiva esplicita soddisfa la regola e documenta l'intento. Posizionarla dopo tutti i `RUN`/`COPY` che necessitano di root in build (la regola guarda l'utente di default dell'immagine finale, non i passi di build). |
| `runtimes/watchdog/tests/Dockerfile.test` | 20 | Dopo i `RUN chmod +x ...` e prima di `WORKDIR`: `RUN addgroup -S watchtest && adduser -S -G watchtest watchtest` + `USER watchtest`. I test scrivono solo in `/tmp` (world-writable: `mkdir -p /tmp/custom`) e bindano porte alte — compatibili con non-root. |

Totale: **4 findings chiusi via modifiche**, 0 won't-fix.

## Vincoli globali

- **Behavior-preserving del watchdog**: il processo resta lo stesso; l'utente runtime cambia da root a non-root (caso standard per function container). Il gate `test.sh` deve restare verde (build immagine + integration tests HTTP/STDIO/FILE/callback).
- **Nessun NOSONAR / suppression** (convenzione #164-#167).
- **New findings in scope**: se i fix ne introducono (es. `adduser` senza pin dell'UID, righe lunghe), risolverli nello stesso task; il re-scan finale deve dare **0 aperti**.
- **Gate per task**: `cd runtimes/watchdog && ./test.sh` verde (Docker richiesto; "Apple container" per test.sh).
- **Niente trailer Co-Authored-By** nei commit (repo a autore singolo).
- Commit piccolo, messaggio `fix(sonar): <rule>: <descrizione>` (convenzione #165).
- Lingua: italiano per le comunicazioni; commit in inglese.

## Verifica

1. Re-scan: `sonar-scanner -Dsonar.host.url=http://127.0.0.1:9000 -Dsonar.token=$TOKEN -Dsonar.projectKey=nanofaas-rust -Dsonar.projectName="nanofaas Rust" -Dsonar.sources=runtimes/watchdog -Dsonar.rust.cargo.manifestPaths=runtimes/watchdog/Cargo.toml` (token in /tmp/sonar-token-165). Attendere `SUCCESS` via `/api/ce/component`.
2. Query: `/api/issues/search?componentKeys=nanofaas-rust&statuses=OPEN,CONFIRMED,REOPENED&ps=100` → **0 aperti**. **Nota:** senza `statuses=` le query includono le risolte (lezione #166).
3. Merge su main, gate `test.sh` su main, push, creazione issue GitHub (prossimo numero dopo #167) e chiusura con commento di risoluzione.

## Fuori scope

- Il sorgente Rust del watchdog (nessun finding aperto — la scansione lo conferma).
- Il contenitore sonar-sonata e la sua password (admin/admin) restano come sono.
