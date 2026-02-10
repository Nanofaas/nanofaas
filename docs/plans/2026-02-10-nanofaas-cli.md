# Nanofaas CLI (GraalVM Native) Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add a new Java CLI subproject that can build+push function images, register functions, invoke/enqueue, query executions, and provide Kubernetes-native logs/status via the Fabric8 client; ship as a single GraalVM native executable named `nanofaas-cli` with a `nanofaas` wrapper entrypoint.

**Architecture:** A non-Spring `picocli` application. It talks to the control-plane via `java.net.http.HttpClient` using DTOs from `:common`. It optionally talks to Kubernetes via Fabric8 using kubeconfig/in-cluster config. `deploy` always runs `docker buildx build --push ...` and then performs a function apply (create, or replace when different).

**Tech Stack:** Java 21, Gradle multi-project, Picocli, Jackson (JSON + YAML), Fabric8 Kubernetes Client, OkHttp MockWebServer, GraalVM Native Build Tools.

---

## Scope (MVP)

- Control-plane API (from `openapi.yaml`): `list/get/delete/register`, `invoke`, `enqueue`, `getExecution`.
- Build/push via local Docker: `docker buildx build --push`.
- `deploy` always builds (using `x-cli.build` in the function YAML) and then applies the function to the control-plane.
- Kubernetes helpers via Fabric8 (cluster-agnostic): show pods, fetch logs, describe Deployment/Service/HPA using known naming/labels (`fn-<name>`, labels `function=<name>`).

---

## Task 1: Create New Gradle Subproject `nanofaas-cli`

**Files:**
- Modify: `settings.gradle`
- Create: `nanofaas-cli/build.gradle`
- Create: `nanofaas-cli/src/main/java/it/unimib/datai/nanofaas/cli/NanofaasCli.java`
- Create: `nanofaas-cli/src/main/java/it/unimib/datai/nanofaas/cli/commands/RootCommand.java`
- Create: `nanofaas-cli/src/test/java/it/unimib/datai/nanofaas/cli/commands/RootCommandTest.java`

**Step 1: Write failing test**
- Create `RootCommandTest` expecting `--help` to print usage and exit 0.

**Step 2: Run test to verify it fails**
- Run: `./gradlew :nanofaas-cli:test`
- Expected: FAIL (project missing / classes missing)

**Step 3: Minimal implementation**
- Add the module to `settings.gradle` via `include('nanofaas-cli')`.
- Add `build.gradle` with:
  - `application` plugin, `org.graalvm.buildtools.native`, `java`
  - deps: `implementation project(':common')`, `picocli`, `jackson` json+yaml, `fabric8 kubernetes-client`
  - test deps: JUnit 5, AssertJ (optional), MockWebServer, Fabric8 server mock (optional)
  - `application { mainClass = 'it.unimib.datai.nanofaas.cli.NanofaasCli' }`
  - `graalvmNative { binaries { main { imageName = 'nanofaas-cli' } } }`
- Implement `NanofaasCli.main` to run picocli on `RootCommand`.

**Step 4: Run tests**
- Run: `./gradlew :nanofaas-cli:test`
- Expected: PASS

**Step 5: Commit**
- `git commit -m "Add nanofaas CLI module skeleton"`

---

## Task 2: Config + Contexts (`~/.config/nanofaas/config.yaml`)

**Files:**
- Create: `nanofaas-cli/src/main/java/it/unimib/datai/nanofaas/cli/config/Config.java`
- Create: `nanofaas-cli/src/main/java/it/unimib/datai/nanofaas/cli/config/Context.java`
- Create: `nanofaas-cli/src/main/java/it/unimib/datai/nanofaas/cli/config/ConfigStore.java`
- Create: `nanofaas-cli/src/test/java/it/unimib/datai/nanofaas/cli/config/ConfigStoreTest.java`
- Modify: `nanofaas-cli/src/main/java/it/unimib/datai/nanofaas/cli/commands/RootCommand.java`

**Step 1: Write failing test**
- `ConfigStoreTest`:
  - writes a config file to a temp dir (override base path)
  - reads it back and asserts current context + endpoint.

**Step 2: Run test (fail)**
- Run: `./gradlew :nanofaas-cli:test --tests '*ConfigStoreTest'`

**Step 3: Implement**
- `ConfigStore`:
  - default path: `${user.home}/.config/nanofaas/config.yaml`
  - supports env overrides: `NANOFAAS_ENDPOINT`, `NANOFAAS_CONTEXT`, `NANOFAAS_NAMESPACE`, `NANOFAAS_OUTPUT`
  - creates parent directories on save
  - uses Jackson YAML mapper (SnakeYAML via jackson-dataformat-yaml)
- Root command loads config early and provides it to subcommands.

**Step 4: Run tests (pass)**
- Same command

**Step 5: Commit**
- `git commit -m "Add CLI config and contexts"`

---

## Task 3: Control-Plane HTTP Client (DTOs from `:common`)

**Files:**
- Create: `nanofaas-cli/src/main/java/it/unimib/datai/nanofaas/cli/http/ControlPlaneClient.java`
- Create: `nanofaas-cli/src/main/java/it/unimib/datai/nanofaas/cli/http/HttpJson.java`
- Create: `nanofaas-cli/src/test/java/it/unimib/datai/nanofaas/cli/http/ControlPlaneClientTest.java`

**Step 1: Write failing test**
- Use OkHttp `MockWebServer` to assert:
  - `listFunctions()` does GET `/v1/functions`
  - `registerFunction(spec)` does POST `/v1/functions` with JSON body
  - `invokeSync(name, request)` does POST `/v1/functions/{name}:invoke`

**Step 2: Run test (fail)**
- Run: `./gradlew :nanofaas-cli:test --tests '*ControlPlaneClientTest'`

**Step 3: Implement minimal client**
- `HttpClient` with base URL from context.
- `HttpJson` wraps Jackson ObjectMapper for JSON.
- Methods:
  - `listFunctions()`
  - `getFunction(name)`
  - `deleteFunction(name)`
  - `registerFunction(FunctionSpec spec)`
  - `invokeSync(name, InvocationRequest req, headers...)`
  - `enqueue(name, InvocationRequest req, headers...)`
  - `getExecution(executionId)`
- Error handling: non-2xx -> throw `ControlPlaneException` with status + body snippet.

**Step 4: Run tests (pass)**

**Step 5: Commit**
- `git commit -m "Add control-plane HTTP client"`

---

## Task 4: CLI Commands for Control-Plane (`fn`, `invoke`, `enqueue`, `exec`)

**Files:**
- Create: `nanofaas-cli/src/main/java/it/unimib/datai/nanofaas/cli/commands/context/ContextCommand.java`
- Create: `nanofaas-cli/src/main/java/it/unimib/datai/nanofaas/cli/commands/fn/FnCommand.java`
- Create: `nanofaas-cli/src/main/java/it/unimib/datai/nanofaas/cli/commands/fn/FnApplyCommand.java`
- Create: `nanofaas-cli/src/main/java/it/unimib/datai/nanofaas/cli/commands/invoke/InvokeCommand.java`
- Create: `nanofaas-cli/src/main/java/it/unimib/datai/nanofaas/cli/commands/invoke/EnqueueCommand.java`
- Create: `nanofaas-cli/src/main/java/it/unimib/datai/nanofaas/cli/commands/exec/ExecCommand.java`
- Create: `nanofaas-cli/src/test/java/it/unimib/datai/nanofaas/cli/commands/fn/FnApplyCommandTest.java`

**Step 1: Write failing test**
- `FnApplyCommandTest`:
  - given a `function.yaml` with a FunctionSpec and `x-cli` ignored by DTO parsing
  - ensures `apply` calls register and, on 409, performs `get`+replace when different.

**Step 2: Run test (fail)**

**Step 3: Implement**
- `fn apply -f` reads YAML:
  - Use Jackson YAML to parse into a tree, then map `FunctionSpec` from the same tree (ignoring unknown fields like `x-cli`).
  - Replacement strategy default: `if-different` (deep compare of specs ignoring nulls/defaults if needed).
- `invoke` supports `-d @file` and `-d @-` (stdin), content-type defaults to JSON.
- `exec get --watch` polls `/v1/executions/{id}` until terminal state or timeout.

**Step 4: Run tests**
- `./gradlew :nanofaas-cli:test`

**Step 5: Commit**
- `git commit -m "Add control-plane CLI commands"`

---

## Task 5: Build Engine (Docker Buildx) + `deploy` Always Builds

**Files:**
- Create: `nanofaas-cli/src/main/java/it/unimib/datai/nanofaas/cli/build/BuildSpec.java`
- Create: `nanofaas-cli/src/main/java/it/unimib/datai/nanofaas/cli/build/BuildSpecLoader.java`
- Create: `nanofaas-cli/src/main/java/it/unimib/datai/nanofaas/cli/build/DockerBuildx.java`
- Create: `nanofaas-cli/src/main/java/it/unimib/datai/nanofaas/cli/commands/deploy/DeployCommand.java`
- Create: `nanofaas-cli/src/test/java/it/unimib/datai/nanofaas/cli/build/BuildSpecLoaderTest.java`
- Create: `nanofaas-cli/src/test/java/it/unimib/datai/nanofaas/cli/build/DockerBuildxTest.java`

**Step 1: Write failing tests**
- `BuildSpecLoaderTest` parses `x-cli.build` from a YAML file (context, dockerfile, platform, buildArgs).
- `DockerBuildxTest` verifies generated command line equals:
  - `docker buildx build --push --tag <image> --platform <platform> -f <dockerfile> <context>`
  - plus `--build-arg KEY=VALUE` for buildArgs.

**Step 2: Run tests (fail)**

**Step 3: Implement**
- `BuildSpecLoader` reads YAML as a Jackson tree and extracts `x-cli.build`.
- `DeployCommand`:
  - loads FunctionSpec + BuildSpec
  - enforces: must have `image` and `x-cli.build.context`
  - runs docker buildx (ProcessBuilder) and streams stdout/stderr
  - then calls `fn apply` logic.

**Step 4: Run tests (pass)**

**Step 5: Commit**
- `git commit -m "Add docker buildx build+push and deploy"`

---

## Task 6: Kubernetes Commands via Fabric8 (`k8s pods/logs/describe`)

**Files:**
- Create: `nanofaas-cli/src/main/java/it/unimib/datai/nanofaas/cli/k8s/KubeClients.java`
- Create: `nanofaas-cli/src/main/java/it/unimib/datai/nanofaas/cli/commands/k8s/K8sCommand.java`
- Create: `nanofaas-cli/src/main/java/it/unimib/datai/nanofaas/cli/commands/k8s/K8sPodsCommand.java`
- Create: `nanofaas-cli/src/main/java/it/unimib/datai/nanofaas/cli/commands/k8s/K8sLogsCommand.java`
- Create: `nanofaas-cli/src/main/java/it/unimib/datai/nanofaas/cli/commands/k8s/K8sDescribeCommand.java`
- Create: `nanofaas-cli/src/test/java/it/unimib/datai/nanofaas/cli/commands/k8s/K8sLogsCommandTest.java`

**Step 1: Write failing test**
- Use Fabric8 `kubernetes-server-mock`:
  - create Pods with label `function=<name>` and container `function`
  - ensure `k8s logs <name>` selects a pod and calls the logs endpoint for container `function`.

**Step 2: Run test (fail)**

**Step 3: Implement**
- `KubeClients` loads config from:
  - `KUBECONFIG` if present, else default kubeconfig path, else in-cluster.
- Namespace selection:
  - `--namespace` overrides; else from CLI config context; else `default`.
- Pod selection heuristic:
  - prefer Ready pods; else latest by creationTimestamp.
- `describe` fetches:
  - Deployment `fn-<name>`, Service `fn-<name>`, HPA `fn-<name>` if present.

**Step 4: Run tests (pass)**

**Step 5: Commit**
- `git commit -m "Add Kubernetes pods/logs/describe commands"`

---

## Task 7: Wrapper Entrypoint `nanofaas` + Native Build Verification

**Files:**
- Modify: `nanofaas-cli/build.gradle`
- Create: `nanofaas-cli/src/main/dist/bin/nanofaas` (wrapper script)
- Create: `nanofaas-cli/src/test/java/it/unimib/datai/nanofaas/cli/NativeConfigTest.java` (optional)
- Modify: `README.md` (brief usage)

**Step 1: Write failing check**
- Add Gradle task to assemble a distribution including:
  - native binary name `nanofaas-cli` (from `nativeCompile`)
  - wrapper script `nanofaas` that execs `nanofaas-cli`.

**Step 2: Implement**
- In `build.gradle`:
  - configure `distributions { main { contents { from('src/main/dist') } } }`
  - ensure `nanofaas` is executable (`fileMode 0755`).
- Add doc snippet to `README.md`:
  - build native: `./gradlew :nanofaas-cli:nativeCompile`
  - usage: `nanofaas --help`, `nanofaas deploy -f function.yaml`, `nanofaas k8s logs <fn> -n <ns>`

**Step 3: Verify**
- Run: `./gradlew :nanofaas-cli:nativeCompile` (requires GraalVM)
- Run (if binary exists): `./nanofaas-cli/build/native/nativeCompile/nanofaas-cli --help`

**Step 4: Commit**
- `git commit -m "Package nanofaas wrapper and document native build"`

---

## Final Verification

Run:
- `./gradlew test`
- (if available) `./gradlew :nanofaas-cli:nativeCompile`

Manual smoke (with a running control-plane on `http://localhost:8080`):
- `nanofaas context add dev --endpoint http://localhost:8080`
- `nanofaas deploy -f examples/.../function.yaml`
- `nanofaas invoke <name> -d @-`
- `nanofaas k8s logs <name> --follow --namespace <ns>`

