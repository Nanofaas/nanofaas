# Tutorial: Writing a nanofaas Function

This tutorial walks you through creating, building, and invoking a nanofaas
function from scratch. Examples are shown for Java and Python; sections that
differ between languages are marked accordingly.

---

## Prerequisites

| Requirement | Version |
|---|---|
| nanofaas CLI (`nanofaas`) | any recent |
| Java (SDKMAN recommended) | 21 — *Java only* |
| Docker or compatible runtime | any recent |
| nanofaas platform running | — |

Start the platform locally:

```bash
scripts/controlplane.sh run --profile core   # API on http://localhost:8080
```

---

## Concepts

A nanofaas function is an HTTP service that implements one endpoint (`POST /invoke`).
The SDK wires up the server; you write only the handler.

The platform calls your handler with an `InvocationRequest`:

| Field | Type | Description |
|---|---|---|
| `input` | any | JSON body sent by the caller |
| `metadata` | map | Optional caller-supplied metadata |

Whatever your handler returns is serialized back to the caller as JSON.

---

## Step 1 — Scaffold the project

```bash
./scripts/fn-init.sh
```

The interactive wizard asks for a function name, language, and output directory,
then generates a ready-to-run project:

```
greet/
├── src/…/GreetHandler.java   (Java)
│   handler.py                (Python)
├── build.gradle / Dockerfile
├── function.yaml
└── payloads/
    ├── happy-path.json
    └── missing-input.json
```

For non-interactive use (CI):

```bash
./scripts/fn-init.sh greet --lang java --yes
./scripts/fn-init.sh greet --lang python --yes
```

---

## Step 2 — Implement the handler

### Java

Edit `src/main/java/.../GreetHandler.java`:

```java
@Override
public Object handle(InvocationRequest request) {
    @SuppressWarnings("unchecked")
    Map<String, Object> input = (Map<String, Object>) request.input();
    String name = (String) input.getOrDefault("name", "world");
    return Map.of("greeting", "Hello, " + name + "!");
}
```

### Python

Edit `handler.py`:

```python
@nanofaas_function
def handle(input_data):
    name = input_data.get("name", "world") if isinstance(input_data, dict) else "world"
    return {"greeting": f"Hello, {name}!"}
```

---

## Step 3 — Update the payloads

Edit `payloads/happy-path.json` to match your handler's actual input/output:

```json
{
  "description": "greet with explicit name",
  "input": {"name": "Alice"},
  "expected": {"greeting": "Hello, Alice!"}
}
```

---

## Step 4 — Run unit tests

### Java

```bash
./gradlew :examples:java:greet:test
```

### Python

```bash
uv run pytest
```

---

## Step 5 — Deploy

```bash
nanofaas deploy -f function.yaml
```

This builds the container image and registers the function on the control plane.

---

## Step 6 — Invoke

```bash
nanofaas invoke greet -d @payloads/happy-path.json
```

Expected response:

```json
{"greeting": "Hello, Alice!"}
```

---

## Step 7 — Run contract tests

```bash
nanofaas fn test greet --payloads ./payloads/
```

Runs every payload file against the deployed function and compares responses
to `expected`. *(Requires `nanofaas fn test` — see CLI docs for availability.)*

---

## Step 8 — Invoke asynchronously (optional)

```bash
nanofaas enqueue greet -d @payloads/happy-path.json
# returns {"executionId": "..."}

nanofaas exec get <executionId> --watch
```

---

## What's next

- Add more payload cases in `payloads/` for edge cases and error paths.
- Deploy to Kubernetes: see `docs/k8s.md`.
- Run a full E2E load test: see `docs/e2e-tutorial.md`.
