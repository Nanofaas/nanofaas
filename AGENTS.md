# Repository Guidelines

## Project Structure & Module Organization

- `common/` contains shared DTOs and runtime interfaces (e.g., handler contracts used by both services).
- `control-plane/` is the API gateway + scheduler + in-memory queues + Kubernetes dispatch logic (supports JOB and WARM execution modes).
- `function-runtime/` hosts the Java function invocation HTTP server and handler registry.
- `python-runtime/` provides Python function runtime with watchdog for WARM execution mode (OpenWhisk-style).
- `docs/` holds architecture and operational documentation; `openapi.yaml` is the API spec.
- `k8s/` contains Kubernetes manifests; `scripts/` provides helper workflows.
- Tests live in `*/src/test/java` with E2E tests under `control-plane/src/test/java/.../e2e`.

## Build, Test, and Development Commands

- `./gradlew build` — compile all modules and assemble artifacts.
- `./gradlew test` — run unit/integration/E2E tests (requires container runtime).
- `./gradlew :control-plane:bootRun` — run the control plane locally.
- `./gradlew :function-runtime:bootRun` — run the function runtime locally.
- `./gradlew :control-plane:bootBuildImage` and `:function-runtime:bootBuildImage` — create buildpack images.
- `python-runtime/build.sh` or `docker build -t nanofaas/python-runtime python-runtime/` — build Python runtime image.
- `scripts/native-build.sh` — build GraalVM native binaries (uses SDKMAN).
- `scripts/e2e.sh` and `scripts/e2e-buildpack.sh` — run local E2E suites.
- `scripts/e2e-k3s-junit-curl.sh` — provision a Multipass VM with k3s, deploy via Helm, run curl checks, and then run `K8sE2eTest`.

## Coding Style & Naming Conventions

- Java 21 toolchain; 4-space indentation; `com.nanofaas` package root.
- Class names `PascalCase`, methods/fields `camelCase`, constants `SCREAMING_SNAKE_CASE`.
- Configuration lives in `control-plane/src/main/resources/application.yml` and `function-runtime/src/main/resources/application.yml`.

## Testing Guidelines

- JUnit 5 is the primary framework; tests are named `*Test.java`.
- E2E tests use Testcontainers, RestAssured, and Fabric8; ensure Docker/compatible runtime is available.
- K8s E2E (`K8sE2eTest`) runs via `scripts/e2e-k3s-junit-curl.sh` on a real k3s cluster in Multipass.

## Project Constraints & Requirements (FaaS MVP)

- Language: Java with Spring Boot; native image support via GraalVM build tools.
- Single control-plane pod: API gateway, in-memory queueing, and a dedicated scheduler thread.
- Function execution runs in separate Kubernetes pods (JOB mode for cold starts, WARM mode for OpenWhisk-style warm containers).
- No authentication/authorization in scope.
- Prometheus metrics exposed via Micrometer/Actuator.
- Retry default is 3 and must be user-configurable; clients handle idempotency.
- Performance and low latency take priority over feature breadth.

## Commit & Pull Request Guidelines

- No git history is present; use short, imperative commits (e.g., `Add queue backpressure`).
- PRs should include a summary, tests run, and updates to `docs/`, `openapi.yaml`, and `k8s/` when behavior changes.

<!-- gitnexus:start -->
# GitNexus — Code Intelligence

This project is indexed by GitNexus as **ansible-vm-provisioning** (12594 symbols, 35391 relationships, 300 execution flows). Use the GitNexus MCP tools to understand code, assess impact, and navigate safely.

> If any GitNexus tool warns the index is stale, run `npx gitnexus analyze` in terminal first.

## Always Do

- **MUST run impact analysis before editing any symbol.** Before modifying a function, class, or method, run `gitnexus_impact({target: "symbolName", direction: "upstream"})` and report the blast radius (direct callers, affected processes, risk level) to the user.
- **MUST run `gitnexus_detect_changes()` before committing** to verify your changes only affect expected symbols and execution flows.
- **MUST warn the user** if impact analysis returns HIGH or CRITICAL risk before proceeding with edits.
- When exploring unfamiliar code, use `gitnexus_query({query: "concept"})` to find execution flows instead of grepping. It returns process-grouped results ranked by relevance.
- When you need full context on a specific symbol — callers, callees, which execution flows it participates in — use `gitnexus_context({name: "symbolName"})`.

## When Debugging

1. `gitnexus_query({query: "<error or symptom>"})` — find execution flows related to the issue
2. `gitnexus_context({name: "<suspect function>"})` — see all callers, callees, and process participation
3. `READ gitnexus://repo/ansible-vm-provisioning/process/{processName}` — trace the full execution flow step by step
4. For regressions: `gitnexus_detect_changes({scope: "compare", base_ref: "main"})` — see what your branch changed

## When Refactoring

- **Renaming**: MUST use `gitnexus_rename({symbol_name: "old", new_name: "new", dry_run: true})` first. Review the preview — graph edits are safe, text_search edits need manual review. Then run with `dry_run: false`.
- **Extracting/Splitting**: MUST run `gitnexus_context({name: "target"})` to see all incoming/outgoing refs, then `gitnexus_impact({target: "target", direction: "upstream"})` to find all external callers before moving code.
- After any refactor: run `gitnexus_detect_changes({scope: "all"})` to verify only expected files changed.

## Never Do

- NEVER edit a function, class, or method without first running `gitnexus_impact` on it.
- NEVER ignore HIGH or CRITICAL risk warnings from impact analysis.
- NEVER rename symbols with find-and-replace — use `gitnexus_rename` which understands the call graph.
- NEVER commit changes without running `gitnexus_detect_changes()` to check affected scope.

## Tools Quick Reference

| Tool | When to use | Command |
|------|-------------|---------|
| `query` | Find code by concept | `gitnexus_query({query: "auth validation"})` |
| `context` | 360-degree view of one symbol | `gitnexus_context({name: "validateUser"})` |
| `impact` | Blast radius before editing | `gitnexus_impact({target: "X", direction: "upstream"})` |
| `detect_changes` | Pre-commit scope check | `gitnexus_detect_changes({scope: "staged"})` |
| `rename` | Safe multi-file rename | `gitnexus_rename({symbol_name: "old", new_name: "new", dry_run: true})` |
| `cypher` | Custom graph queries | `gitnexus_cypher({query: "MATCH ..."})` |

## Impact Risk Levels

| Depth | Meaning | Action |
|-------|---------|--------|
| d=1 | WILL BREAK — direct callers/importers | MUST update these |
| d=2 | LIKELY AFFECTED — indirect deps | Should test |
| d=3 | MAY NEED TESTING — transitive | Test if critical path |

## Resources

| Resource | Use for |
|----------|---------|
| `gitnexus://repo/ansible-vm-provisioning/context` | Codebase overview, check index freshness |
| `gitnexus://repo/ansible-vm-provisioning/clusters` | All functional areas |
| `gitnexus://repo/ansible-vm-provisioning/processes` | All execution flows |
| `gitnexus://repo/ansible-vm-provisioning/process/{name}` | Step-by-step execution trace |

## Self-Check Before Finishing

Before completing any code modification task, verify:
1. `gitnexus_impact` was run for all modified symbols
2. No HIGH/CRITICAL risk warnings were ignored
3. `gitnexus_detect_changes()` confirms changes match expected scope
4. All d=1 (WILL BREAK) dependents were updated

## Keeping the Index Fresh

After committing code changes, the GitNexus index becomes stale. Re-run analyze to update it:

```bash
npx gitnexus analyze
```

If the index previously included embeddings, preserve them by adding `--embeddings`:

```bash
npx gitnexus analyze --embeddings
```

To check whether embeddings exist, inspect `.gitnexus/meta.json` — the `stats.embeddings` field shows the count (0 means no embeddings). **Running analyze without `--embeddings` will delete any previously generated embeddings.**

> Claude Code users: A PostToolUse hook handles this automatically after `git commit` and `git merge`.

## CLI

| Task | Read this skill file |
|------|---------------------|
| Understand architecture / "How does X work?" | `.claude/skills/gitnexus/gitnexus-exploring/SKILL.md` |
| Blast radius / "What breaks if I change X?" | `.claude/skills/gitnexus/gitnexus-impact-analysis/SKILL.md` |
| Trace bugs / "Why is X failing?" | `.claude/skills/gitnexus/gitnexus-debugging/SKILL.md` |
| Rename / extract / split / refactor | `.claude/skills/gitnexus/gitnexus-refactoring/SKILL.md` |
| Tools, resources, schema reference | `.claude/skills/gitnexus/gitnexus-guide/SKILL.md` |
| Index, status, clean, wiki CLI commands | `.claude/skills/gitnexus/gitnexus-cli/SKILL.md` |

<!-- gitnexus:end -->
