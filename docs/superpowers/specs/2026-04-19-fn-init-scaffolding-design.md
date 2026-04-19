# fn-init: Function Scaffolding Tool

**Date:** 2026-04-19
**Status:** Approved

## Goal

Automate the creation of a new nanofaas function project — directory structure, boilerplate code, build configuration, VS Code project files, and platform registration manifest — so a developer goes from zero to a runnable function in one command instead of following a 13-step manual tutorial.

## Scope

- Languages: Java and Python (Go deferred)
- Replaces: manual `cp -r examples/java/word-stats ...` + tutorial steps
- Also updates: `docs/tutorial-java-function.md` → unified `docs/tutorial-function.md`

---

## Tool Structure

Follows the same pattern as `tools/controlplane/` + `scripts/controlplane.sh`.

```
tools/fn-init/
├── pyproject.toml
├── uv.lock
├── src/
│   └── fn_init/
│       ├── main.py            # Typer entrypoint + Rich wizard
│       ├── wizard.py          # interactive Rich prompts
│       ├── generator.py       # template rendering + file writing
│       └── templates/
│           ├── java/
│           │   ├── Handler.java.tmpl
│           │   ├── Application.java.tmpl
│           │   ├── HandlerTest.java.tmpl
│           │   ├── build.gradle.tmpl
│           │   ├── Dockerfile.tmpl
│           │   └── function.yaml.tmpl
│           ├── python/
│           │   ├── handler.py.tmpl
│           │   ├── pyproject.toml.tmpl
│           │   └── function.yaml.tmpl
│           └── vscode/
│               ├── java/
│               │   ├── settings.json.tmpl
│               │   ├── launch.json.tmpl
│               │   └── extensions.json.tmpl
│               └── python/
│                   ├── settings.json.tmpl
│                   ├── launch.json.tmpl
│                   └── extensions.json.tmpl
└── tests/
    └── test_generator.py

scripts/fn-init.sh             # shell wrapper (uv run)
```

**`pyproject.toml` dependencies:** `rich`, `typer`

**`scripts/fn-init.sh`:**
```bash
#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"
if ! command -v uv >/dev/null 2>&1; then
  echo "uv not found. Install uv or provision the VM with ops/ansible/playbooks/provision-base.yml" >&2
  exit 1
fi
exec uv run --project tools/fn-init --locked fn-init "$@"
```

---

## Interface

### Interactive mode (default — no arguments)

```bash
./scripts/fn-init.sh
```

Rich wizard flow:
1. Welcome panel with tool description
2. `Prompt.ask` → function name (validated: lowercase, alphanumeric + hyphens)
3. `Prompt.ask` → language (`java` / `python`, default `java`)
4. `Prompt.ask` → output directory (shows computed default, user can override)
5. `Confirm.ask` → generate VS Code project files? (`.vscode/`)
6. Summary panel with `Tree` of files that will be created
7. `Confirm.ask` → proceed?
8. File generation with `console.status("Generating...")`
9. Next steps panel (green)

### Non-interactive mode (CI / scripting)

```bash
./scripts/fn-init.sh greet --lang java
./scripts/fn-init.sh greet --lang python --out ~/projects/ --vscode --yes
```

Flags:
- `--lang java|python` — language (default: `java`)
- `--out <dir>` — parent output directory (overrides monorepo default)
- `--vscode` — generate `.vscode/` files
- `--yes` — skip confirmation prompts

---

## Output Directory Logic

| Condition | Output path |
|---|---|
| Inside monorepo, no `--out` | `examples/<lang>/<name>/` |
| Inside monorepo, `--out` given | `<out>/<name>/` |
| Outside monorepo, `--out` given | `<out>/<name>/` |
| Outside monorepo, no `--out` | Error: `--out` is required outside the monorepo |

**Monorepo detection:** walk up from `cwd` looking for `settings.gradle`. First directory containing it is the monorepo root.

**Existing directory:** if the target directory already exists, abort with a red error panel — no silent overwrite.

---

## Template Rendering

Templates use `{{PLACEHOLDER}}` markers replaced via `str.replace()` — no Jinja2 dependency.

| Placeholder | Example |
|---|---|
| `{{FUNCTION_NAME}}` | `greet` |
| `{{CLASS_NAME}}` | `Greet` (CamelCase of function name) |
| `{{PACKAGE}}` | `it.unimib.datai.nanofaas.examples.greet` |
| `{{IMAGE_TAG}}` | `nanofaas/greet:latest` |
| `{{LANG}}` | `java` |

---

## Generated Files

### Java

- `src/main/java/.../{{CLASS_NAME}}Handler.java` — handler with `@NanofaasFunction` + stub `handle()`
- `src/main/java/.../{{CLASS_NAME}}Application.java` — Spring Boot main
- `src/test/java/.../{{CLASS_NAME}}HandlerTest.java` — minimal JUnit 5 test
- `build.gradle` — depends on `:function-sdk-java`, sets `bootJar.archiveFileName`
- `Dockerfile` — `eclipse-temurin:21-jre`
- `function.yaml` — `x-cli.build` section for `nanofaas deploy`

### Python

- `handler.py` — stub with `@nanofaas_function` decorator
- `pyproject.toml` — depends on `function-sdk-python`
- `function.yaml` — `x-cli.build` section for `nanofaas deploy`

### VS Code (optional, both languages)

- `.vscode/settings.json` — language-specific interpreter/formatter config
- `.vscode/launch.json` — debug configuration (Spring Boot for Java, FastAPI for Python)
- `.vscode/extensions.json` — recommended extensions

---

## Monorepo Integration (Java only)

When the output lands inside the monorepo (`examples/java/<name>/`), the script appends to `settings.gradle`:

```groovy
include 'examples:java:<name>'
```

Idempotent: checks if the line already exists before appending. Shows the diff inline with Rich before writing.

Not applicable to Python (no centralised registry equivalent).

---

## Next Steps Panel

After generation, a green Rich panel shows the exact CLI commands to proceed:

```
╭─ Next steps ─────────────────────────────────────╮
│  cd examples/java/greet                           │
│                                                   │
│  # implement your handler, then:                  │
│  nanofaas deploy -f function.yaml                 │
│  nanofaas invoke greet -d '{"input": {}}'         │
│                                                   │
│  # run tests:                                     │
│  ./gradlew :examples:java:greet:test   (Java)     │
│  uv run pytest                          (Python)  │
╰───────────────────────────────────────────────────╯
```

---

## Tutorial Update

Replace `docs/tutorial-java-function.md` with `docs/tutorial-function.md` — a unified tutorial that:

| Section | Java | Python |
|---|---|---|
| Prerequisites | shared | shared |
| Concepts (`FunctionHandler`, `InvocationRequest`) | shared | shared |
| Scaffolding (`./scripts/fn-init.sh`) | shared | shared |
| Implement the handler | ✦ diverges | ✦ diverges |
| Unit tests | ✦ diverges | ✦ diverges |
| Deploy (`nanofaas deploy`) | shared | shared |
| Invoke (`nanofaas invoke`) | shared | shared |
| Async + execution context | shared | shared |

All CLI operations (`deploy`, `invoke`, `enqueue`) use `nanofaas` CLI — no raw `docker build` or `curl` in the tutorial.

---

## Testing

`tools/fn-init/tests/test_generator.py` covers:
- Java scaffold produces correct files at expected paths
- Python scaffold produces correct files at expected paths
- `settings.gradle` update is idempotent
- Existing directory aborts with error
- `--out` outside monorepo works without `settings.gradle` update
- CamelCase conversion of function name

---

## What Is Not In Scope

- Go scaffolding (deferred)
- VS Code extension (may call `fn-init.sh` in a future phase)
- `nanofaas deploy` integration (CLI already handles this via `function.yaml`)
- Hot reload / devmode
