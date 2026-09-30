# Tutorial: Writing a nanofaas Function

This tutorial walks you through creating, building, and invoking a nanofaas
function from scratch. Examples are shown for Java, Python, and JavaScript; sections that
differ between languages are marked accordingly.

---

## Prerequisites

| Requirement | Version |
|---|---|
| nanofaas CLI (`nanofaas`) | any recent |
| Java (SDKMAN recommended) | 25 — *Java only* |
| Node.js + npm | 24 — *JavaScript only* |
| Go | 1.24 — *Go only* |
| Rust toolchain (rustup) | 1.85 or newer — *Rust only* |
| Docker or compatible runtime | any recent |
| nanofaas platform running | — |

Start the platform locally:

```bash
./gradlew :control-plane:bootRun   # API on http://localhost:8080
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
│   src/index.ts              (JavaScript)
│   main.go                   (Go)
│   src/main.rs               (Rust)
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
./scripts/fn-init.sh greet --lang javascript --yes
./scripts/fn-init.sh greet --lang go --yes
./scripts/fn-init.sh greet --lang rust --yes
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

### JavaScript

Edit `src/handler.ts`:

```ts
import type { Handler, JsonObject } from "nanofaas-function-sdk";

export const handleGreet: Handler = async (ctx, req) => {
    ctx.logger.info("greet invoked");
    const input = typeof req.input === "object" && req.input !== null && !Array.isArray(req.input)
        ? req.input as JsonObject
        : {};
    const name = typeof input.name === "string" ? input.name : "world";
    return { greeting: `Hello, ${name}!` };
};
```

### Go

Edit `main.go`: replace the generated `handleGreet`.

```go
func handleGreet(ctx context.Context, req nanofaas.InvocationRequest) (any, error) {
	nanofaas.Logger(ctx, slog.Default()).Info("greet invoked")
	input, _ := req.Input.(map[string]any)
	name, _ := input["name"].(string)
	if name == "" {
		name = "world"
	}
	return map[string]any{"greeting": "Hello, " + name + "!"}, nil
}
```

The generated `main_test.go` expects a 422 for empty input; replace that test
with one for the new behavior, for example:

```go
func TestHandleGreetDefaultsTheName(t *testing.T) {
	result, err := handleGreet(context.Background(), nanofaas.InvocationRequest{Input: map[string]any{}})
	if err != nil {
		t.Fatal(err)
	}
	if result.(map[string]any)["greeting"] != "Hello, world!" {
		t.Fatalf("unexpected result: %v", result)
	}
}
```

### Rust

Edit `src/main.rs`: replace `Input` and `respond`, keeping `main` and `handle`
as generated.

```rust
#[derive(Deserialize)]
struct Input {
    #[serde(default)]
    name: Option<String>,
}

fn respond(input: Input) -> HandlerResponse {
    let name = input.name.unwrap_or_else(|| "world".to_string());
    HandlerResponse::new(json!({"greeting": format!("Hello, {name}!")}), 200)
}
```

Then update the tests at the bottom of the file to the new input and output,
for example:

```rust
#[test]
fn greets_by_name() {
    let input = Input { name: Some("Alice".into()) };
    let expected = json!({"greeting": "Hello, Alice!"});
    assert_eq!(respond(input), HandlerResponse::new(expected, 200));
}
```

A Rust handler returns a `HandlerResponse`, which sets the status code (and
optionally headers) for every outcome. CPU-heavy work belongs in
`ctx.spawn_blocking(...)`, so that it keeps its concurrency slot until it
really finishes.

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
./gradlew :functions:java:greet:test
```

### Python

```bash
uv run pytest
```

### JavaScript

```bash
npm install
npm test
```

If you want the compiled output before packaging, run:

```bash
npm run build
```

### Go

```bash
go test ./...
```

### Rust

```bash
cargo test
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
nanofaas invoke greet -d '{"name":"Alice"}'
```

Expected response — the `InvocationResponse` envelope, with the handler's
result nested under `output` (not returned raw):

```json
{
  "executionId": "...",
  "status": "success",
  "output": {"greeting": "Hello, Alice!"},
  "statusCode": 200
}
```

`statusCode`, `headers`, and `encoding` appear only when the handler sets them.
A handler-decided non-2xx status (marked by the `X-NanoFaaS-Function-Status`
response header) still prints this envelope, but the CLI exits `1` so pipelines
can distinguish it from a successful invocation.

---

## Step 7 — Run contract tests

```bash
nanofaas fn test greet --payloads ./payloads/
```

Runs every payload file against the deployed function and compares responses
to `expected`. A case also checks the HTTP status: `expectedStatusCode`,
200 when absent. `missing-input.json` declares the 422 the generated handler
returns for missing input.

---

## Step 8 — Invoke asynchronously (optional)

```bash
nanofaas enqueue greet -d '{"name":"Alice"}'
# returns {"executionId": "..."}

nanofaas exec get <executionId> --watch
```

---

## What's next

- Add more payload cases in `payloads/` for edge cases and error paths.
- Deploy to Kubernetes: see `docs/k8s.md`.
- Run a full E2E load test: see `docs/e2e-tutorial.md`.
- Add the function key to a YAML scenario, inspect it with `nanolab.sh plan <scenario>`, then execute the same file with `nanolab.sh run <scenario>` (from a `nanolab` checkout with `NANOFAAS_ROOT` set to this repo).
