# nanofaas-cli Coverage Gaps Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Raise nanofaas-cli unit test coverage from 76.9% instruction / 54.9% branch to >90% instruction / >75% branch by closing all identified JaCoCo gaps.

**Architecture:** All production code already exists. Each task adds tests to exercise uncovered branches. Pattern: MockWebServer for HTTP commands, `@EnableKubernetesMockClient(crud = true)` for k8s commands, `@TempDir` for file I/O. Tests use picocli `CommandLine.execute()` to exercise the full command stack.

**Tech Stack:** JUnit 5, AssertJ, OkHttp MockWebServer, Fabric8 KubernetesMockServer, picocli

---

### Task 1: EnqueueCommand — @file data path and invalid JSON

**Files:**
- Modify: `nanofaas-cli/src/test/java/it/unimib/datai/nanofaas/cli/commands/invoke/EnqueueCommandTest.java`

**Context:** `EnqueueCommand` is at 51.5% instruction coverage. The `@file` read path (line 49-56 of `EnqueueCommand.java`) and the invalid-JSON error path (line 68-70) are not exercised.

**Step 1: Write test — enqueue with @file data**

Add to `EnqueueCommandTest.java`:

```java
@TempDir
Path tmp;

@Test
void enqueueWithFileData() throws Exception {
    Path inputFile = tmp.resolve("input.json");
    java.nio.file.Files.writeString(inputFile, "{\"msg\":\"from-file\"}");

    server.enqueue(new MockResponse()
            .setResponseCode(202)
            .addHeader("Content-Type", "application/json")
            .setBody("{\"executionId\":\"eq-f\",\"status\":\"queued\"}"));

    RootCommand root = new RootCommand();
    CommandLine cli = new CommandLine(root);
    cli.setExpandAtFiles(false);

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream prev = System.out;
    System.setOut(new PrintStream(out));
    try {
        int exit = cli.execute(
                "--endpoint", server.url("/").toString(),
                "enqueue", "echo",
                "-d", "@" + inputFile
        );
        assertThat(exit).isEqualTo(0);
    } finally {
        System.setOut(prev);
    }

    RecordedRequest req = server.takeRequest();
    assertThat(req.getBody().readUtf8()).contains("\"msg\":\"from-file\"");
}
```

**Step 2: Write test — enqueue with invalid JSON exits non-zero**

```java
@Test
void enqueueWithInvalidJsonExitsNonZero() {
    RootCommand root = new RootCommand();
    CommandLine cli = new CommandLine(root);

    int exit = cli.execute(
            "--endpoint", server.url("/").toString(),
            "enqueue", "echo",
            "-d", "not-valid-json{{"
    );
    assertThat(exit).isNotEqualTo(0);
}
```

**Step 3: Run tests**

```bash
./gradlew :nanofaas-cli:test --tests "it.unimib.datai.nanofaas.cli.commands.invoke.EnqueueCommandTest" -i
```

Expected: all 4 tests PASS.

**Step 4: Commit**

```bash
git add nanofaas-cli/src/test/java/it/unimib/datai/nanofaas/cli/commands/invoke/EnqueueCommandTest.java
git commit -m "test: cover EnqueueCommand @file path and invalid JSON branch"
```

---

### Task 2: InvokeCommand — invalid JSON error path

**Files:**
- Modify: `nanofaas-cli/src/test/java/it/unimib/datai/nanofaas/cli/commands/invoke/InvokeCommandTest.java`

**Context:** `InvokeCommand` is at 69.3% instruction. The invalid-JSON-input branch (line 68-72 of `InvokeCommand.java`) is not exercised.

**Step 1: Write test**

Add to `InvokeCommandTest.java`:

```java
@Test
void invokeWithInvalidJsonExitsNonZero() {
    RootCommand root = new RootCommand();
    CommandLine cli = new CommandLine(root);

    int exit = cli.execute(
            "--endpoint", server.url("/").toString(),
            "invoke", "echo",
            "-d", "<<<not json>>>"
    );
    assertThat(exit).isNotEqualTo(0);
}
```

**Step 2: Run tests**

```bash
./gradlew :nanofaas-cli:test --tests "it.unimib.datai.nanofaas.cli.commands.invoke.InvokeCommandTest" -i
```

Expected: all 4 tests PASS.

**Step 3: Commit**

```bash
git add nanofaas-cli/src/test/java/it/unimib/datai/nanofaas/cli/commands/invoke/InvokeCommandTest.java
git commit -m "test: cover InvokeCommand invalid JSON error branch"
```

---

### Task 3: ExecGetCommand — watch polling loop and error/timeout terminal states

**Files:**
- Modify: `nanofaas-cli/src/test/java/it/unimib/datai/nanofaas/cli/commands/exec/ExecGetCommandTest.java`

**Context:** `ExecGetCommand` is at 69% instruction / 50% branch. The `--watch` polling loop (lines 45-60), multiple terminal states (`"error"`, `"timeout"`), and the watch-timeout path (line 51-52) are uncovered.

**Step 1: Write test — watch polls until terminal**

```java
@Test
void watchPollsUntilTerminal() throws Exception {
    // First poll: "running" (non-terminal)
    server.enqueue(new MockResponse()
            .setResponseCode(200)
            .addHeader("Content-Type", "application/json")
            .setBody("{\"executionId\":\"exec-3\",\"status\":\"running\"}"));
    // Second poll: "error" (terminal)
    server.enqueue(new MockResponse()
            .setResponseCode(200)
            .addHeader("Content-Type", "application/json")
            .setBody("{\"executionId\":\"exec-3\",\"status\":\"error\"}"));

    RootCommand root = new RootCommand();
    CommandLine cli = new CommandLine(root);

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream prev = System.out;
    System.setOut(new PrintStream(out));
    try {
        int exit = cli.execute(
                "--endpoint", server.url("/").toString(),
                "exec", "get", "exec-3",
                "--watch", "--interval", "PT0.1S", "--timeout", "PT10S"
        );
        assertThat(exit).isEqualTo(0);
    } finally {
        System.setOut(prev);
    }

    assertThat(server.getRequestCount()).isEqualTo(2);
    String output = out.toString();
    assertThat(output).contains("\"status\":\"running\"");
    assertThat(output).contains("\"status\":\"error\"");
}
```

**Step 2: Write test — watch with "timeout" terminal state**

```java
@Test
void watchExitsOnTimeoutTerminalState() throws Exception {
    server.enqueue(new MockResponse()
            .setResponseCode(200)
            .addHeader("Content-Type", "application/json")
            .setBody("{\"executionId\":\"exec-4\",\"status\":\"timeout\"}"));

    RootCommand root = new RootCommand();
    CommandLine cli = new CommandLine(root);

    int exit = cli.execute(
            "--endpoint", server.url("/").toString(),
            "exec", "get", "exec-4", "--watch"
    );
    assertThat(exit).isEqualTo(0);
    assertThat(server.getRequestCount()).isEqualTo(1);
}
```

**Step 3: Write test — watch deadline exceeded throws error**

```java
@Test
void watchDeadlineExceededExitsNonZero() throws Exception {
    // Always return non-terminal status
    for (int i = 0; i < 20; i++) {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"executionId\":\"exec-5\",\"status\":\"running\"}"));
    }

    RootCommand root = new RootCommand();
    CommandLine cli = new CommandLine(root);

    int exit = cli.execute(
            "--endpoint", server.url("/").toString(),
            "exec", "get", "exec-5",
            "--watch", "--interval", "PT0.05S", "--timeout", "PT0.2S"
    );
    assertThat(exit).isNotEqualTo(0);
}
```

**Step 4: Run tests**

```bash
./gradlew :nanofaas-cli:test --tests "it.unimib.datai.nanofaas.cli.commands.exec.ExecGetCommandTest" -i
```

Expected: all 5 tests PASS.

**Step 5: Commit**

```bash
git add nanofaas-cli/src/test/java/it/unimib/datai/nanofaas/cli/commands/exec/ExecGetCommandTest.java
git commit -m "test: cover ExecGetCommand watch loop, polling, and timeout branches"
```

---

### Task 4: K8sLogsCommand — no pods, ready-pod priority, custom container

**Files:**
- Modify: `nanofaas-cli/src/test/java/it/unimib/datai/nanofaas/cli/commands/k8s/K8sLogsCommandTest.java`

**Context:** `K8sLogsCommand` is at 55.9% instruction / 15% branch. The "no pods found" error (line 30), ready-pod ranking logic (lines 33-37, 44-54), and `--container` option are uncovered.

**Step 1: Write test — no pods throws error**

```java
@Test
void logsWithNoPodsExitsNonZero() {
    // No pods created — empty namespace
    K8sCommand root = new K8sCommand(() -> client, () -> "ns");
    CommandLine cli = new CommandLine(root);

    int exit = cli.execute("logs", "missing");
    assertThat(exit).isNotEqualTo(0);
}
```

**Step 2: Write test — ready pod selected over non-ready pod**

```java
@Test
void logsSelectsReadyPodOverNonReady() {
    // Create non-ready pod (older)
    Pod notReady = new PodBuilder()
            .withNewMetadata()
                .withName("fn-echo-old")
                .withNamespace("ns")
                .addToLabels("function", "echo")
                .withCreationTimestamp("2024-01-01T00:00:00Z")
            .endMetadata()
            .withNewSpec()
                .addNewContainer().withName("function").withImage("x").endContainer()
            .endSpec()
            .withNewStatus()
                .addNewCondition().withType("Ready").withStatus("False").endCondition()
            .endStatus()
            .build();
    client.pods().inNamespace("ns").resource(notReady).create();

    // Create ready pod (newer)
    Pod ready = new PodBuilder()
            .withNewMetadata()
                .withName("fn-echo-new")
                .withNamespace("ns")
                .addToLabels("function", "echo")
                .withCreationTimestamp("2024-01-02T00:00:00Z")
            .endMetadata()
            .withNewSpec()
                .addNewContainer().withName("function").withImage("x").endContainer()
            .endSpec()
            .withNewStatus()
                .addNewCondition().withType("Ready").withStatus("True").endCondition()
            .endStatus()
            .build();
    client.pods().inNamespace("ns").resource(ready).create();

    // Stub logs for the ready pod
    server.expect().get()
            .withPath("/api/v1/namespaces/ns/pods/fn-echo-new/log?pretty=false&container=function")
            .andReturn(200, "from-ready-pod\n").always();

    K8sCommand root = new K8sCommand(() -> client, () -> "ns");
    CommandLine cli = new CommandLine(root);

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream prev = System.out;
    System.setOut(new PrintStream(out));
    try {
        int exit = cli.execute("logs", "echo");
        assertThat(exit).isEqualTo(0);
    } finally {
        System.setOut(prev);
    }

    assertThat(out.toString()).contains("from-ready-pod");
}
```

**Step 3: Run tests**

```bash
./gradlew :nanofaas-cli:test --tests "it.unimib.datai.nanofaas.cli.commands.k8s.K8sLogsCommandTest" -i
```

Expected: all 3 tests PASS.

**Step 4: Commit**

```bash
git add nanofaas-cli/src/test/java/it/unimib/datai/nanofaas/cli/commands/k8s/K8sLogsCommandTest.java
git commit -m "test: cover K8sLogsCommand no-pods error and ready-pod selection"
```

---

### Task 5: FnApplyCommand — 409+null GET retry and non-409 error rethrow

**Files:**
- Modify: `nanofaas-cli/src/test/java/it/unimib/datai/nanofaas/cli/commands/fn/FnApplyCommandTest.java`

**Context:** `FnApplyCommand` is at 70.5% / 66.7% branch. The "409 + GET returns null → retry create" path (lines 35-38) and "non-409 HTTP error → rethrow" path (lines 29-31) are uncovered.

**Step 1: Write test — 409 then GET returns 404, retry create**

```java
@Test
void applyOnConflictRetriesWhenGetReturnsNull() throws Exception {
    Path fn = tmp.resolve("function.yaml");
    java.nio.file.Files.writeString(fn, """
            name: echo
            image: registry.example/echo:1
            """);

    // 1) register -> 409
    server.enqueue(new MockResponse().setResponseCode(409));
    // 2) GET -> 404 (null)
    server.enqueue(new MockResponse().setResponseCode(404));
    // 3) retry register -> 201
    server.enqueue(new MockResponse()
            .setResponseCode(201)
            .addHeader("Content-Type", "application/json")
            .setBody("{\"name\":\"echo\",\"image\":\"registry.example/echo:1\"}"));

    RootCommand root = new RootCommand();
    CommandLine cli = new CommandLine(root);

    int exit = cli.execute("--endpoint", server.url("/").toString(), "fn", "apply", "-f", fn.toString());
    assertThat(exit).isEqualTo(0);

    assertThat(server.getRequestCount()).isEqualTo(3);
    RecordedRequest r1 = server.takeRequest();
    assertThat(r1.getMethod()).isEqualTo("POST");
    RecordedRequest r2 = server.takeRequest();
    assertThat(r2.getMethod()).isEqualTo("GET");
    RecordedRequest r3 = server.takeRequest();
    assertThat(r3.getMethod()).isEqualTo("POST"); // retry
}
```

**Step 2: Write test — non-409 error exits non-zero**

```java
@Test
void applyNon409ErrorExitsNonZero() throws Exception {
    Path fn = tmp.resolve("function.yaml");
    java.nio.file.Files.writeString(fn, """
            name: echo
            image: registry.example/echo:1
            """);

    // register -> 500
    server.enqueue(new MockResponse().setResponseCode(500).setBody("Internal Server Error"));

    RootCommand root = new RootCommand();
    CommandLine cli = new CommandLine(root);

    int exit = cli.execute("--endpoint", server.url("/").toString(), "fn", "apply", "-f", fn.toString());
    assertThat(exit).isNotEqualTo(0);
}
```

**Step 3: Run tests**

```bash
./gradlew :nanofaas-cli:test --tests "it.unimib.datai.nanofaas.cli.commands.fn.FnApplyCommandTest" -i
```

Expected: all 4 tests PASS.

**Step 4: Commit**

```bash
git add nanofaas-cli/src/test/java/it/unimib/datai/nanofaas/cli/commands/fn/FnApplyCommandTest.java
git commit -m "test: cover FnApplyCommand 409+null-GET retry and non-409 rethrow"
```

---

### Task 6: DockerBuildx — no push, no platform, no dockerfile

**Files:**
- Modify: `nanofaas-cli/src/test/java/it/unimib/datai/nanofaas/cli/image/DockerBuildxTest.java`

**Context:** `DockerBuildx.toCommand()` is at 68.6% / 50% branch. The `push=false`, `platform=null`, and `dockerfile=null` branches are uncovered.

**Step 1: Write test — no push, no platform, no dockerfile**

```java
@Test
void commandWithoutPushPlatformOrDockerfile() {
    BuildSpec spec = new BuildSpec(
            Path.of("./app"),
            null,       // no dockerfile
            null,       // no platform
            false,      // push = false
            Map.of()
    );

    List<String> cmd = DockerBuildx.toCommand("my-image:1", spec);

    assertThat(cmd).containsExactly(
            "docker", "buildx", "build",
            "--tag", "my-image:1",
            "./app"
    );
    assertThat(cmd).doesNotContain("--push", "--platform", "-f");
}
```

**Step 2: Write test — platform but no push**

```java
@Test
void commandWithPlatformButNoPush() {
    BuildSpec spec = new BuildSpec(
            Path.of("."),
            Path.of("Dockerfile.custom"),
            "linux/arm64",
            false,
            Map.of()
    );

    List<String> cmd = DockerBuildx.toCommand("img:2", spec);

    assertThat(cmd).contains("--platform", "linux/arm64");
    assertThat(cmd).contains("-f", "Dockerfile.custom");
    assertThat(cmd).doesNotContain("--push");
}
```

**Step 3: Run tests**

```bash
./gradlew :nanofaas-cli:test --tests "it.unimib.datai.nanofaas.cli.image.DockerBuildxTest" -i
```

Expected: all 3 tests PASS.

**Step 4: Commit**

```bash
git add nanofaas-cli/src/test/java/it/unimib/datai/nanofaas/cli/image/DockerBuildxTest.java
git commit -m "test: cover DockerBuildx no-push, no-platform, no-dockerfile branches"
```

---

### Task 7: BuildSpecLoader — missing x-cli.build, missing context, defaults

**Files:**
- Modify: `nanofaas-cli/src/test/java/it/unimib/datai/nanofaas/cli/image/BuildSpecLoaderTest.java`

**Context:** `BuildSpecLoader` is at 83.1% / 52.9% branch. The "missing x-cli.build" error (line 23), "missing context" error (line 28), and default values (no platform, no buildArgs) are uncovered.

**Step 1: Write test — missing x-cli.build throws**

```java
@Test
void throwsWhenXCliBuildMissing() throws Exception {
    Path p = tmp.resolve("no-build.yaml");
    Files.writeString(p, """
            name: echo
            image: example/echo:1
            """);

    assertThatThrownBy(() -> BuildSpecLoader.load(p))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Missing x-cli.build");
}
```

**Step 2: Write test — missing context throws**

```java
@Test
void throwsWhenContextMissing() throws Exception {
    Path p = tmp.resolve("no-context.yaml");
    Files.writeString(p, """
            name: echo
            image: example/echo:1
            x-cli:
              build:
                dockerfile: Dockerfile
            """);

    assertThatThrownBy(() -> BuildSpecLoader.load(p))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Missing x-cli.build.context");
}
```

**Step 3: Write test — defaults applied (no platform, no buildArgs)**

```java
@Test
void defaultsAppliedWhenFieldsOmitted() throws Exception {
    Path p = tmp.resolve("minimal.yaml");
    Files.writeString(p, """
            name: echo
            image: example/echo:1
            x-cli:
              build:
                context: .
            """);

    BuildSpec spec = BuildSpecLoader.load(p);

    assertThat(spec.context()).isEqualTo(Path.of("."));
    assertThat(spec.dockerfile()).isEqualTo(Path.of("Dockerfile"));
    assertThat(spec.platform()).isNull();
    assertThat(spec.push()).isTrue();
    assertThat(spec.buildArgs()).isEmpty();
}
```

**Step 4: Run tests**

```bash
./gradlew :nanofaas-cli:test --tests "it.unimib.datai.nanofaas.cli.image.BuildSpecLoaderTest" -i
```

Expected: all 4 tests PASS.

**Step 5: Commit**

```bash
git add nanofaas-cli/src/test/java/it/unimib/datai/nanofaas/cli/image/BuildSpecLoaderTest.java
git commit -m "test: cover BuildSpecLoader missing-build, missing-context, and defaults"
```

---

### Task 8: HttpJson — fromJson error path

**Files:**
- Modify: `nanofaas-cli/src/test/java/it/unimib/datai/nanofaas/cli/http/ControlPlaneClientTest.java` (HttpJson is package-private, tested indirectly)
- Create: `nanofaas-cli/src/test/java/it/unimib/datai/nanofaas/cli/http/HttpJsonTest.java`

**Context:** `HttpJson` is at 68.9%. The `fromJson` error path and `toJson` success path are not directly tested.

**Step 1: Write test — fromJson with invalid JSON throws**

```java
package it.unimib.datai.nanofaas.cli.http;

import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpJsonTest {

    @Test
    void fromJsonWithInvalidJsonThrows() {
        HttpJson json = new HttpJson();
        assertThatThrownBy(() -> json.fromJson("not valid json", FunctionSpec.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Failed to parse JSON");
    }

    @Test
    void toJsonAndFromJsonRoundTrip() {
        HttpJson json = new HttpJson();
        FunctionSpec spec = new FunctionSpec("echo", "img:1",
                null, null, null, null, null, null, null, null, null, null, null, null);
        String serialized = json.toJson(spec);
        assertThat(serialized).contains("\"name\":\"echo\"");
        FunctionSpec deserialized = json.fromJson(serialized, FunctionSpec.class);
        assertThat(deserialized.name()).isEqualTo("echo");
    }
}
```

**Step 2: Run tests**

```bash
./gradlew :nanofaas-cli:test --tests "it.unimib.datai.nanofaas.cli.http.HttpJsonTest" -i
```

Expected: all 2 tests PASS.

**Step 3: Commit**

```bash
git add nanofaas-cli/src/test/java/it/unimib/datai/nanofaas/cli/http/HttpJsonTest.java
git commit -m "test: cover HttpJson fromJson error path and round-trip"
```

---

### Task 9: ControlPlaneClient — deleteFunction server error, getExecution error

**Files:**
- Modify: `nanofaas-cli/src/test/java/it/unimib/datai/nanofaas/cli/http/ControlPlaneClientTest.java`

**Context:** `ControlPlaneClient` is at 87.7% / 73.8% branch. The `deleteFunction` with non-404/non-204 error and `getExecution` with non-200 error are uncovered.

**Step 1: Write test — deleteFunction server error throws**

```java
@Test
void deleteFunctionServerErrorThrows() {
    server.enqueue(new MockResponse().setResponseCode(500).setBody("error"));
    ControlPlaneClient client = new ControlPlaneClient(server.url("/").toString());

    assertThatThrownBy(() -> client.deleteFunction("echo"))
            .isInstanceOf(ControlPlaneHttpException.class)
            .satisfies(ex -> assertThat(((ControlPlaneHttpException) ex).status()).isEqualTo(500));
}
```

**Step 2: Write test — getExecution error throws**

```java
@Test
void getExecutionErrorThrows() {
    server.enqueue(new MockResponse().setResponseCode(404).setBody("not found"));
    ControlPlaneClient client = new ControlPlaneClient(server.url("/").toString());

    assertThatThrownBy(() -> client.getExecution("exec-missing"))
            .isInstanceOf(ControlPlaneHttpException.class)
            .satisfies(ex -> assertThat(((ControlPlaneHttpException) ex).status()).isEqualTo(404));
}
```

**Step 3: Write test — invokeSync error throws**

```java
@Test
void invokeSyncErrorThrows() {
    server.enqueue(new MockResponse().setResponseCode(503).setBody("unavailable"));
    ControlPlaneClient client = new ControlPlaneClient(server.url("/").toString());
    var req = new it.unimib.datai.nanofaas.common.model.InvocationRequest(java.util.Map.of("x", 1), null);

    assertThatThrownBy(() -> client.invokeSync("echo", req, null, null, null))
            .isInstanceOf(ControlPlaneHttpException.class)
            .satisfies(ex -> assertThat(((ControlPlaneHttpException) ex).status()).isEqualTo(503));
}
```

**Step 4: Run tests**

```bash
./gradlew :nanofaas-cli:test --tests "it.unimib.datai.nanofaas.cli.http.ControlPlaneClientTest" -i
```

Expected: all 15 tests PASS.

**Step 5: Commit**

```bash
git add nanofaas-cli/src/test/java/it/unimib/datai/nanofaas/cli/http/ControlPlaneClientTest.java
git commit -m "test: cover ControlPlaneClient delete/getExecution/invokeSync error branches"
```

---

### Task 10: RootCommand — missing endpoint error

**Files:**
- Modify: `nanofaas-cli/src/test/java/it/unimib/datai/nanofaas/cli/commands/RootCommandTest.java`

**Context:** `RootCommand` is at 84.4% / 55% branch. The "missing endpoint" error path in `controlPlaneClient()` (lines 65-67) is uncovered.

**Step 1: Read existing test**

Read `RootCommandTest.java` first to understand the existing pattern.

**Step 2: Write test — command with no endpoint exits non-zero**

```java
@Test
void commandWithNoEndpointExitsNonZero() {
    RootCommand root = new RootCommand();
    CommandLine cli = new CommandLine(root);

    int exit = cli.execute("fn", "list");
    assertThat(exit).isNotEqualTo(0);
}
```

**Step 3: Run tests**

```bash
./gradlew :nanofaas-cli:test --tests "it.unimib.datai.nanofaas.cli.commands.RootCommandTest" -i
```

Expected: all 2 tests PASS.

**Step 4: Commit**

```bash
git add nanofaas-cli/src/test/java/it/unimib/datai/nanofaas/cli/commands/RootCommandTest.java
git commit -m "test: cover RootCommand missing-endpoint error branch"
```

---

### Task 11: K8sPodsCommand — empty result for no matching pods

**Files:**
- Modify: `nanofaas-cli/src/test/java/it/unimib/datai/nanofaas/cli/commands/k8s/K8sPodsCommandTest.java`

**Context:** `K8sPodsCommand` is at 93.2% / 60% branch. The null-check branches (lines 22-24, 26-27) for empty pod list and missing metadata/status are not fully exercised.

**Step 1: Write test — no matching pods produces empty output**

```java
@Test
void noMatchingPodsProducesEmptyOutput() {
    // No pods created for this function name
    K8sCommand root = new K8sCommand(() -> client, () -> "ns");
    CommandLine cli = new CommandLine(root);

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream prev = System.out;
    System.setOut(new PrintStream(out));
    try {
        int exit = cli.execute("pods", "nonexistent");
        assertThat(exit).isEqualTo(0);
    } finally {
        System.setOut(prev);
    }

    assertThat(out.toString().trim()).isEmpty();
}
```

**Step 2: Run tests**

```bash
./gradlew :nanofaas-cli:test --tests "it.unimib.datai.nanofaas.cli.commands.k8s.K8sPodsCommandTest" -i
```

Expected: all 2 tests PASS.

**Step 3: Commit**

```bash
git add nanofaas-cli/src/test/java/it/unimib/datai/nanofaas/cli/commands/k8s/K8sPodsCommandTest.java
git commit -m "test: cover K8sPodsCommand empty pod list branch"
```

---

### Task 12: Final verification

**Step 1: Run all tests**

```bash
./gradlew :nanofaas-cli:test --rerun
```

Expected: all tests PASS, 0 failures.

**Step 2: Generate coverage report**

```bash
./gradlew :nanofaas-cli:test :nanofaas-cli:jacocoTestReport --rerun
```

**Step 3: Parse coverage**

```bash
python3 -c "
import xml.etree.ElementTree as ET
tree = ET.parse('nanofaas-cli/build/reports/jacoco/test/jacocoTestReport.xml')
root = tree.getroot()
for counter in root.findall('counter'):
    ctype = counter.get('type')
    missed = int(counter.get('missed'))
    covered = int(counter.get('covered'))
    total = missed + covered
    pct = (covered / total * 100) if total > 0 else 0
    print(f'{ctype:15s}: {covered:5d}/{total:5d}  ({pct:5.1f}%)')
"
```

Expected targets:
- **Instruction** > 90% (from 76.9%)
- **Branch** > 75% (from 54.9%)
- **Line** > 85% (from 74.9%)

**Step 4: Commit plan verification**

```bash
git add docs/plans/2026-02-11-cli-coverage-gaps.md
git commit -m "docs: add coverage gap plan"
```

---

## Out of Scope

| Class | Reason |
|---|---|
| `DeployCommand` (3.8%) | Calls `DockerBuildx.run()` which starts a real process via `ProcessBuilder.inheritIO()`. Testing requires either refactoring to inject a process builder or integration-test level setup. Not worth the coupling for a thin orchestration method. |
| `NanofaasCli` (0%) | Only contains `public static void main(String[] args)`. Testing provides no value. |
| `YamlIO` (66.7%) | Only untested path is the `IOException` catch block (line 21-22) which requires injecting a read-failure. Already exercised indirectly through `FnApplyCommandTest` for the happy path. |
