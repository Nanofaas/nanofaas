# Repository Guidelines

## Project Structure & Module Organization

- `platform/common/` contains shared DTOs and runtime interfaces (e.g., handler contracts used by both services).
- `platform/control-plane/` is the API gateway + scheduler + in-memory queues + dispatch logic (execution modes: `LOCAL`, `EXTERNAL`, and managed `DEPLOYMENT` via backend providers).
- `sdks/java/` provides the reusable Java invocation runtime; `services/java/warm-echo/` is its runnable long-running example service.
- `sdks/python/` provides the Python function SDK and FastAPI runtime.
- `docs/` holds architecture and operational documentation; the API spec is composed at build time from `openapi/core.yaml` plus per-module `platform/modules/<id>/openapi.yaml` fragments into a generated `/openapi.yaml` packaged into each artifact (see `docs/control-plane.md`).
- `deploy/helm/nanofaas/` contains the Helm chart; `deploy/compose/` a Docker Compose stack; `scripts/` provides helper workflows.
- Tests live in `*/src/test/java` with E2E tests under `platform/control-plane/src/test/java/.../e2e`.

## Build, Test, and Development Commands

- `./gradlew build` — compile all modules and assemble artifacts.
- `./gradlew test` — run unit/integration/E2E tests (requires container runtime).
- `./gradlew :control-plane:bootRun` — run the control plane locally.
- `./gradlew :services:java:warm-echo:bootRun` — run the warm-echo example service locally.
- `docker build -f platform/control-plane/Dockerfile -t nanofaas/control-plane .` — create a JVM image on Distroless Java 25.
- `scripts/native-java-image.sh control-plane` — create a native control-plane image on Distroless.
- `scripts/native-build.sh` — build every Java GraalVM native binary with the configured GraalVM release.
- `nanolab.sh run packages/nanolab/scenarios-v2/deployment-lifecycle-container.yaml` (run from a `nanolab` checkout with `NANOFAAS_ROOT` set to this repo) — run local container E2E validation.
- `nanolab.sh run packages/nanolab/scenarios-v2/deployment-lifecycle-k8s.yaml --environment packages/nanolab/environments/multipass.yaml` — provision a VM with k3s, deploy via Helm, and validate the platform through HTTP and Kubernetes resource assertions (requires NanoLab and a VM environment).

## Coding Style & Naming Conventions

- Java 25 toolchain; 4-space indentation; `com.nanofaas` package root.
- Python 3.12 or newer for every Python project (SDK, tools, functions, experiments); function images use `python:3.12-alpine` in both build stages with dependencies precompiled (`uv pip install --compile-bytecode`), except a function whose dependencies ship only glibc wheels (such as `mlimage` with PyTorch), which uses `python:3.12-slim`.
- Class names `PascalCase`, methods/fields `camelCase`, constants `SCREAMING_SNAKE_CASE`.
- Configuration lives in `platform/control-plane/src/main/resources/application.yml` and `services/java/warm-echo/src/main/resources/application.yml`.

## Testing Guidelines

- JUnit 5 is the primary framework; tests are named `*Test.java`.
- Kubernetes end-to-end validation is owned by NanoLab (`deployment-lifecycle-k8s` scenario). NanoFaaS does not provision infrastructure for E2E tests.

## Project Constraints & Requirements (FaaS MVP)

- Language: Java with Spring Boot; native image support via GraalVM build tools.
- Single control-plane pod: API gateway, in-memory queueing, and a dedicated scheduler thread.
- Function execution runs in managed warm instances (Kubernetes Deployments via the `k8s` provider, or local containers via `container-local`), as external passthroughs (`EXTERNAL`), or in-process (`LOCAL`).
- No authentication/authorization in scope.
- Prometheus metrics exposed via Micrometer/Actuator.
- Retry default is 3 and must be user-configurable; clients handle idempotency.
- Performance and low latency take priority over feature breadth.

## Commit & Pull Request Guidelines

- Use short, imperative commits (e.g., `Add queue backpressure`).
- PRs should include a summary, tests run, and updates to `docs/`, the relevant `openapi/core.yaml` or module `openapi.yaml` fragment, and `deploy/` when behavior changes.

<!-- gitnexus:start -->
# GitNexus — Code Intelligence

This project is indexed by GitNexus as **nanofaas** (16911 symbols, 45352 relationships, 743 execution flows).

> Index stale? Run `node .gitnexus/run.cjs analyze --index-only` from the project root — it auto-selects an available runner. No `.gitnexus/run.cjs` yet? Bootstrap with `npx`, `bunx`, or `pnpm dlx` — e.g. `bunx gitnexus@latest analyze` (npm 11 npx crash; #1939).

## Always Do

- **MUST run impact before editing.** Use `impact({target: "symbolName", direction: "upstream"})` or `node .gitnexus/run.cjs impact "symbolName" --direction upstream --repo .`; report callers, processes, and risk. Never substitute grep for graph analysis.
- **MUST analyze graph changes before committing.** Use `detect_changes({scope: "all"})` (MCP) or `node .gitnexus/run.cjs detect-changes --scope all --repo .` (CLI fallback). `partial: true` or `truncated: true` is not a clean check — a zero means unseen, not unaffected; re-run it. For regression review: `detect_changes({scope: "compare", base_ref: "main"})` or `node .gitnexus/run.cjs detect-changes --scope compare --base-ref "main" --repo .`.
- MUST warn on HIGH/CRITICAL `risk` pre-edit; never use `riskSharedAxes` to waive a HIGH/CRITICAL `risk` warning. Compare File/symbol: MCP File omits axes; Graph-RAG expands File.
- **MUST treat `risk: UNKNOWN` as unresolved, not as low.** An empty caller set is not evidence the symbol is unused — it can also mean the callers are not resolvable by the index (plain-object property access, dynamic dispatch, cross-language calls). `impact` pairs `UNKNOWN` with a `riskNote` saying so. Confirm with a text search before treating the symbol as safe to change or delete; do not proceed on the strength of a zero.
- **MUST use `query({search_query: "concept"})` for concepts/flows, `context({name: "symbolName"})` for a named symbol, or `impact` for blast radius, on read-only callers, dependencies, imports, or execution flow.** Graph first; text search only for empty/`UNKNOWN`/literals.
- For security review, `explain({target: "fileOrSymbol"})` lists taint findings (source→sink flows; needs `analyze --pdg`).

## Never Do

- NEVER edit a function, class, or method before MCP/CLI impact analysis.
- NEVER ignore HIGH or CRITICAL risk warnings from impact analysis, and never read `UNKNOWN` as an all-clear — it means the walk could not answer, which is the one verdict that requires confirming by other means.
- NEVER rename symbols with find-and-replace — use `rename` which understands the call graph.
- NEVER commit before MCP/CLI graph change analysis.

## Resources

| Resource | Use for |
| --- | --- |
| `gitnexus://repo/nanofaas/context` | Codebase overview, check index freshness |
| `gitnexus://repo/nanofaas/clusters` | All functional areas |
| `gitnexus://repo/nanofaas/processes` | All execution flows |
| `gitnexus://repo/nanofaas/process/{name}` | Step-by-step execution trace |

## CLI

| Task | Read this skill file |
| --- | --- |
| Understand architecture / "How does X work?" | `.claude/skills/gitnexus-exploring/SKILL.md` |
| Blast radius / "What breaks if I change X?" | `.claude/skills/gitnexus-impact-analysis/SKILL.md` |
| Trace bugs / "Why is X failing?" | `.claude/skills/gitnexus-debugging/SKILL.md` |
| Rename / extract / split / refactor | `.claude/skills/gitnexus-refactoring/SKILL.md` |
| Tools, resources, schema reference | `.claude/skills/gitnexus-guide/SKILL.md` |
| Index, status, clean, wiki CLI commands | `.claude/skills/gitnexus-cli/SKILL.md` |

<!-- gitnexus:end -->
