# NanoFaaS CLI Control-Plane Alignment Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Make nanofaas-cli safely consume the current control-plane contract, including function-decided HTTP statuses, live function updates, replicas, artifact capabilities, build identity, and runtime configuration.

**Architecture:** Keep ControlPlaneClient as the single HTTP boundary and use the existing JDK HTTP client plus Jackson for every new contract. Derive only the capability booleans needed by the CLI from the artifact-specific /openapi.yaml; keep Picocli help static and guard optional commands at execution time. Make destructive replacement explicit, while routing mutable manifest changes through PATCH.

**Tech Stack:** Java 25, Picocli 4.7, JDK HttpClient, Jackson JSON/YAML, JUnit 6, AssertJ, MockWebServer, Gradle.

---

Use @superpowers:test-driven-development for every implementation task. Before editing a named symbol, follow AGENTS.md and run GitNexus upstream impact analysis. Before completion, use @superpowers:verification-before-completion and @superpowers:requesting-code-review.

Work in:

~~~bash
cd /Users/micheleciavotta/Downloads/nanofaas/.worktrees/cli-control-plane-alignment
~~~

### Task 1: Remove the inert namespace contract

**Files:**
- Modify: clients/cli/src/main/java/it/unimib/datai/nanofaas/cli/commands/RootCommand.java:34-85
- Modify: clients/cli/src/main/java/it/unimib/datai/nanofaas/cli/config/ConfigStore.java:50-67
- Modify: clients/cli/src/main/java/it/unimib/datai/nanofaas/cli/config/Context.java
- Modify: clients/cli/src/main/java/it/unimib/datai/nanofaas/cli/config/ResolvedContext.java
- Test: clients/cli/src/test/java/it/unimib/datai/nanofaas/cli/commands/RootCommandTest.java
- Test: clients/cli/src/test/java/it/unimib/datai/nanofaas/cli/config/ConfigStoreTest.java

**Step 1: Check impact**

Run GitNexus upstream impact for RootCommand.resolvedContext, ConfigStore.loadResolvedContext, Context, and ResolvedContext. Record every depth-1 consumer.

**Step 2: Write the failing tests**

Replace namespace tests with:

~~~java
@Test
void namespaceOptionIsNotAccepted() {
    CommandLine cli = new CommandLine(new RootCommand());

    assertThatThrownBy(() -> cli.parseArgs("--namespace", "unused", "fn", "list"))
            .isInstanceOf(CommandLine.ParameterException.class);
}

@Test
void legacyNamespacePropertyIsIgnored() throws Exception {
    Path path = tmp.resolve("config.yaml");
    Files.writeString(path, """
            currentContext: dev
            contexts:
              dev:
                endpoint: http://localhost:8080
                namespace: legacy
            """);

    ResolvedContext resolved = new ConfigStore(path, key -> null).loadResolvedContext();

    assertThat(resolved).isEqualTo(new ResolvedContext("dev", "http://localhost:8080"));
}
~~~

Update existing test constructors and assertions to the two-field context.

**Step 3: Verify the tests fail**

Run:

~~~bash
./gradlew :nanofaas-cli:test --tests '*RootCommandTest' --tests '*ConfigStoreTest' --no-daemon
~~~

Expected: FAIL because Picocli accepts --namespace and ResolvedContext has three fields.

**Step 4: Implement the minimum removal**

Make the context:

~~~java
public record ResolvedContext(String contextName, String endpoint) {
}
~~~

Remove namespace from Context, NANOFAAS_NAMESPACE resolution from ConfigStore, and the option from RootCommand. Keep JsonIgnoreProperties on Context so old configuration files remain readable. Resolve:

~~~java
resolved = new ResolvedContext(base.contextName(), firstNonBlank(
        endpoint,
        base.endpoint(),
        DEFAULT_ENDPOINT
));
~~~

**Step 5: Verify and commit**

Run:

~~~bash
./gradlew :nanofaas-cli:test --tests '*RootCommandTest' --tests '*ConfigStoreTest' --no-daemon
./gradlew :nanofaas-cli:test --no-daemon
git add clients/cli/src/main/java/it/unimib/datai/nanofaas/cli/commands/RootCommand.java clients/cli/src/main/java/it/unimib/datai/nanofaas/cli/config clients/cli/src/test/java/it/unimib/datai/nanofaas/cli/commands/RootCommandTest.java clients/cli/src/test/java/it/unimib/datai/nanofaas/cli/config/ConfigStoreTest.java
git commit -m "fix(cli): remove inert namespace option"
~~~

Expected: PASS and a clean commit.

### Task 2: Preserve function-decided HTTP statuses

**Files:**
- Create: clients/cli/src/main/java/it/unimib/datai/nanofaas/cli/http/InvocationCallResult.java
- Modify: clients/cli/src/main/java/it/unimib/datai/nanofaas/cli/http/ControlPlaneClient.java:100-123
- Modify: clients/cli/src/main/java/it/unimib/datai/nanofaas/cli/commands/invoke/InvokeCommand.java
- Modify: clients/cli/src/main/java/it/unimib/datai/nanofaas/cli/commands/fn/FnTestCommand.java:45-70
- Test: clients/cli/src/test/java/it/unimib/datai/nanofaas/cli/http/ControlPlaneClientTest.java
- Test: clients/cli/src/test/java/it/unimib/datai/nanofaas/cli/commands/invoke/InvokeCommandTest.java
- Test: clients/cli/src/test/java/it/unimib/datai/nanofaas/cli/commands/fn/FnTestCommandTest.java

**Step 1: Check callers**

Run GitNexus context and upstream impact for ControlPlaneClient.invokeSync. Confirm InvokeCommand.run and FnTestCommand.call are the production depth-1 callers.

**Step 2: Write failing contract tests**

Add marked 201, marked 404, unmarked 404, and marked 204 cases. Core example:

~~~java
@Test
void invokeReturnsFunctionDecidedNon200() {
    server.enqueue(new MockResponse()
            .setResponseCode(404)
            .addHeader("X-NanoFaaS-Function-Status", "true")
            .addHeader("Content-Type", "application/json")
            .setBody("""
                    {"executionId":"exec-1","status":"success",
                     "output":{"error":"missing"},"statusCode":404}
                    """));

    InvocationCallResult result = new ControlPlaneClient(server.url("/").toString())
            .invokeSync("lookup", new InvocationRequest(Map.of(), null), null, null, null);

    assertThat(result.httpStatus()).isEqualTo(404);
    assertThat(result.response().statusCode()).isEqualTo(404);
}
~~~

The unmarked 404 must still throw ControlPlaneHttpException. The 204 case expects a synthetic response with statusCode 204 and null output. Command tests expect: marked 201 exits 0; marked 404 prints the envelope and exits 1; marked 204 prints {"statusCode":204}.

**Step 3: Verify failure**

~~~bash
./gradlew :nanofaas-cli:test --tests '*ControlPlaneClientTest' --tests '*InvokeCommandTest' --tests '*FnTestCommandTest' --no-daemon
~~~

Expected: FAIL because InvocationCallResult is absent and invokeSync rejects non-200.

**Step 4: Implement the transport result**

Create:

~~~java
package it.unimib.datai.nanofaas.cli.http;

import it.unimib.datai.nanofaas.common.model.InvocationResponse;

public record InvocationCallResult(int httpStatus, InvocationResponse response) {
    public boolean isSuccessful() {
        return httpStatus >= 200 && httpStatus < 300;
    }
}
~~~

Replace the old status check with:

~~~java
HttpResponse<String> resp = send(b.build());
boolean functionDecided = resp.headers().firstValue("X-NanoFaaS-Function-Status")
        .map(Boolean::parseBoolean)
        .orElse(false);
if (resp.statusCode() != 200 && !functionDecided) {
    throw httpError("invoke function", resp);
}
InvocationResponse response = resp.body() == null || resp.body().isBlank()
        ? new InvocationResponse(
                resp.headers().firstValue("X-Execution-Id").orElse(null),
                resp.statusCode() >= 200 && resp.statusCode() < 300 ? "success" : "error",
                null, null, resp.statusCode(), null, null)
        : json.fromJson(resp.body(), InvocationResponse.class);
return new InvocationCallResult(resp.statusCode(), response);
~~~

Change InvokeCommand to Callable<Integer>. Print Map.of("statusCode", 204) for 204 and otherwise the envelope; return 0 for 2xx and 1 for marked non-2xx. In FnTestCommand use invokeSync(...).response().

**Step 5: Verify and commit**

~~~bash
./gradlew :nanofaas-cli:test --tests '*ControlPlaneClientTest' --tests '*InvokeCommandTest' --tests '*FnTestCommandTest' --no-daemon
./gradlew :nanofaas-cli:test --no-daemon
git add clients/cli/src/main/java/it/unimib/datai/nanofaas/cli/http clients/cli/src/main/java/it/unimib/datai/nanofaas/cli/commands/invoke/InvokeCommand.java clients/cli/src/main/java/it/unimib/datai/nanofaas/cli/commands/fn/FnTestCommand.java clients/cli/src/test/java/it/unimib/datai/nanofaas/cli
git commit -m "fix(cli): preserve function HTTP statuses"
~~~

Expected: PASS; unmarked platform errors remain errors.

### Task 3: Discover artifact capabilities

**Files:**
- Create: clients/cli/src/main/java/it/unimib/datai/nanofaas/cli/http/ControlPlaneCapabilities.java
- Modify: clients/cli/src/main/java/it/unimib/datai/nanofaas/cli/http/ControlPlaneClient.java
- Modify: clients/cli/src/main/java/it/unimib/datai/nanofaas/cli/commands/invoke/EnqueueCommand.java
- Test: clients/cli/src/test/java/it/unimib/datai/nanofaas/cli/http/ControlPlaneCapabilitiesTest.java
- Test: clients/cli/src/test/java/it/unimib/datai/nanofaas/cli/http/ControlPlaneClientTest.java
- Test: clients/cli/src/test/java/it/unimib/datai/nanofaas/cli/commands/invoke/EnqueueCommandTest.java

**Step 1: Check impact**

Run upstream impact for ControlPlaneClient and EnqueueCommand.run.

**Step 2: Write failing parser and guard tests**

Use minimal inline OpenAPI containing the PATCH function route, GET/PUT replicas, POST enqueue, GET build metadata, and all three runtime-config operations. Assert five booleans. Add missing-route and missing-method cases. Update enqueue tests to serve /openapi.yaml first; when async is absent, assert only the contract request occurs and the error says Asynchronous invocation is not supported by this control-plane build.

**Step 3: Verify failure**

~~~bash
./gradlew :nanofaas-cli:test --tests '*ControlPlaneCapabilitiesTest' --tests '*ControlPlaneClientTest' --tests '*EnqueueCommandTest' --no-daemon
~~~

Expected: FAIL because capability discovery is absent.

**Step 4: Implement the narrow parser**

~~~java
public record ControlPlaneCapabilities(
        boolean functionUpdate,
        boolean replicas,
        boolean asyncInvocation,
        boolean buildMetadata,
        boolean runtimeConfig
) {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    public static ControlPlaneCapabilities fromOpenApi(String source) {
        try {
            JsonNode paths = YAML.readTree(source).path("paths");
            return new ControlPlaneCapabilities(
                    has(paths, "/v1/functions/{name}", "patch"),
                    has(paths, "/v1/functions/{name}/replicas", "get")
                            && has(paths, "/v1/functions/{name}/replicas", "put"),
                    has(paths, "/v1/functions/{name}:enqueue", "post"),
                    has(paths, "/modules/build-metadata", "get"),
                    has(paths, "/v1/admin/runtime-config", "get")
                            && has(paths, "/v1/admin/runtime-config/{namespace}", "patch")
                            && has(paths, "/v1/admin/runtime-config/{namespace}/validate", "post"));
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid control-plane OpenAPI document", e);
        }
    }

    private static boolean has(JsonNode paths, String path, String method) {
        return paths.path(path).has(method);
    }
}
~~~

Add openApi() to ControlPlaneClient: GET openapi.yaml, require 200, return the body unchanged. capabilities() calls the parser. Guard EnqueueCommand before enqueue.

**Step 5: Verify and commit**

~~~bash
./gradlew :nanofaas-cli:test --tests '*ControlPlaneCapabilitiesTest' --tests '*ControlPlaneClientTest' --tests '*EnqueueCommandTest' --no-daemon
./gradlew :nanofaas-cli:test --no-daemon
git add clients/cli/src/main/java/it/unimib/datai/nanofaas/cli clients/cli/src/test/java/it/unimib/datai/nanofaas/cli
git commit -m "feat(cli): discover control-plane capabilities"
~~~

### Task 4: Add control-plane identity and contract commands

**Files:**
- Create: clients/cli/src/main/java/it/unimib/datai/nanofaas/cli/http/BuildMetadata.java
- Create: clients/cli/src/main/java/it/unimib/datai/nanofaas/cli/commands/controlplane/ControlPlaneCommand.java
- Create: clients/cli/src/main/java/it/unimib/datai/nanofaas/cli/commands/controlplane/ControlPlaneInfoCommand.java
- Create: clients/cli/src/main/java/it/unimib/datai/nanofaas/cli/commands/controlplane/ControlPlaneContractCommand.java
- Modify: clients/cli/src/main/java/it/unimib/datai/nanofaas/cli/commands/RootCommand.java
- Modify: clients/cli/src/main/java/it/unimib/datai/nanofaas/cli/http/ControlPlaneClient.java
- Test: clients/cli/src/test/java/it/unimib/datai/nanofaas/cli/commands/controlplane/ControlPlaneCommandTest.java
- Test: clients/cli/src/test/java/it/unimib/datai/nanofaas/cli/commands/RootCommandTest.java

**Step 1: Check impact**

Run upstream impact for RootCommand and ControlPlaneClient.

**Step 2: Write failing tests**

Test that contract prints the OpenAPI body unchanged. Test info with OpenAPI plus metadata and assert metadata.revision and capabilities.asyncInvocation. Add metadata 404: output retains capabilities and has null metadata. Add control-plane to the root command set.

**Step 3: Verify failure**

~~~bash
./gradlew :nanofaas-cli:test --tests '*ControlPlaneCommandTest' --tests '*RootCommandTest' --no-daemon
~~~

Expected: FAIL because the commands are missing.

**Step 4: Implement typed metadata and commands**

~~~java
public record BuildMetadata(
        String version,
        String revision,
        Boolean dirty,
        List<String> modules,
        Build build,
        Runtime runtime
) {
    public record Build(String type, String variant, String optimization, BaseImages baseImages) {}
    public record BaseImages(String builder, String runtime) {}
    public record Runtime(String architecture, String kernelVersion, String javaVersion,
                          String vm, List<String> garbageCollectors) {}
}
~~~

Add buildMetadataOrNull(): null on 404, parse on 200, throw otherwise. Info fetches metadata only when capabilities.buildMetadata() is true; otherwise it uses null without calling the absent route. It prints this local record via HttpJson:

~~~java
record Info(BuildMetadata metadata, ControlPlaneCapabilities capabilities) {}
~~~

Contract prints client.openApi() unchanged. Do not add Rich or another renderer.

**Step 5: Verify and commit**

~~~bash
./gradlew :nanofaas-cli:test --tests '*ControlPlaneCommandTest' --tests '*RootCommandTest' --no-daemon
./gradlew :nanofaas-cli:test --no-daemon
git add clients/cli/src/main/java/it/unimib/datai/nanofaas/cli clients/cli/src/test/java/it/unimib/datai/nanofaas/cli
git commit -m "feat(cli): inspect control-plane identity"
~~~

### Task 5: Model mutable function updates

**Files:**
- Create: clients/cli/src/main/java/it/unimib/datai/nanofaas/cli/http/FunctionPatch.java
- Modify: clients/cli/src/main/java/it/unimib/datai/nanofaas/cli/http/FunctionDetails.java
- Modify: clients/cli/src/main/java/it/unimib/datai/nanofaas/cli/http/ControlPlaneClient.java
- Test: clients/cli/src/test/java/it/unimib/datai/nanofaas/cli/http/FunctionDetailsMatchesTest.java
- Test: clients/cli/src/test/java/it/unimib/datai/nanofaas/cli/http/ControlPlaneClientTest.java

**Step 1: Check impact**

Run upstream impact for FunctionDetails.matches and ControlPlaneClient. Preserve matches until Task 6 changes FunctionApplier.

**Step 2: Write failing diff and PATCH tests**

Prove changed concurrency, timeoutMs, maxRetries, and nested concurrencyControl enter FunctionPatch; unspecified values remain null. Prove image, command, resources, queueSize, modes, scaling strategy/min/max/metrics, pull secrets, and offload are immutable. Verify PATCH /v1/functions/echo sends only non-null fields.

**Step 3: Verify failure**

~~~bash
./gradlew :nanofaas-cli:test --tests '*FunctionDetailsMatchesTest' --tests '*ControlPlaneClientTest' --no-daemon
~~~

Expected: FAIL because patch classification is absent.

**Step 4: Implement the patch and explicit comparisons**

~~~java
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FunctionPatch(
        Integer concurrency,
        Integer timeoutMs,
        Integer maxRetries,
        ConcurrencyControlConfig concurrencyControl
) {
    public boolean isEmpty() {
        return concurrency == null && timeoutMs == null && maxRetries == null
                && concurrencyControl == null;
    }
}
~~~

Add mutablePatch(FunctionSpec) and hasImmutableDifferences(FunctionSpec) to FunctionDetails. Use matchesIfSpecified for every top-level immutable field. Within ScalingConfig treat strategy, min/max, and metrics as immutable; only a non-null requested concurrencyControl is mutable.

Add:

~~~java
public FunctionDetails updateFunction(String name, FunctionPatch patch) {
    HttpRequest request = HttpRequest.newBuilder(base.resolve(FUNCTIONS_PATH + name))
            .header(CONTENT_TYPE, APPLICATION_JSON)
            .method("PATCH", HttpRequest.BodyPublishers.ofString(json.toJson(patch)))
            .timeout(Duration.ofSeconds(30))
            .build();
    HttpResponse<String> response = send(request);
    if (response.statusCode() != 200) {
        throw httpError("update function", response);
    }
    return json.fromJson(response.body(), FunctionDetails.class);
}
~~~

**Step 5: Verify and commit**

~~~bash
./gradlew :nanofaas-cli:test --tests '*FunctionDetailsMatchesTest' --tests '*ControlPlaneClientTest' --no-daemon
git add clients/cli/src/main/java/it/unimib/datai/nanofaas/cli/http clients/cli/src/test/java/it/unimib/datai/nanofaas/cli/http
git commit -m "feat(cli): model mutable function updates"
~~~

### Task 6: Make apply safe and add fn update

**Files:**
- Modify: clients/cli/src/main/java/it/unimib/datai/nanofaas/cli/commands/fn/FunctionApplier.java
- Modify: clients/cli/src/main/java/it/unimib/datai/nanofaas/cli/commands/fn/FnApplyCommand.java
- Create: clients/cli/src/main/java/it/unimib/datai/nanofaas/cli/commands/fn/FnUpdateCommand.java
- Modify: clients/cli/src/main/java/it/unimib/datai/nanofaas/cli/commands/fn/FnCommand.java
- Modify: clients/cli/src/main/java/it/unimib/datai/nanofaas/cli/commands/deploy/DeployCommand.java
- Test: clients/cli/src/test/java/it/unimib/datai/nanofaas/cli/commands/fn/FnApplyCommandTest.java
- Create: clients/cli/src/test/java/it/unimib/datai/nanofaas/cli/commands/fn/FnUpdateCommandTest.java
- Modify: clients/cli/src/test/java/it/unimib/datai/nanofaas/cli/commands/deploy/DeployCommandTest.java

**Step 1: Check impact**

Run upstream impact for FunctionApplier.apply, FnApplyCommand.run, and DeployCommand.run. Warn before edits if HIGH/CRITICAL.

**Step 2: Write failing safety tests**

Replace implicit replacement expectations with:
- mutable difference: POST 409, GET 200, PATCH 200, never DELETE;
- immutable difference without --replace: POST 409, GET 200 only, non-zero exit;
- immutable difference with --replace: DELETE then POST;
- deploy with a changed image also requires --replace.

For fn update, use a YAML FunctionPatch and assert GET /openapi.yaml then PATCH. Reject an empty patch locally.

**Step 3: Verify failure**

~~~bash
./gradlew :nanofaas-cli:test --tests '*FnApplyCommandTest' --tests '*FnUpdateCommandTest' --tests '*DeployCommandTest' --no-daemon
~~~

Expected: FAIL because apply deletes implicitly and update/--replace are absent.

**Step 4: Implement safe apply**

Change the signature to apply(ControlPlaneClient, FunctionSpec, boolean). After the existing 409/GET flow:

~~~java
FunctionPatch patch = existing.mutablePatch(desired);
if (existing.hasImmutableDifferences(desired)) {
    if (!replace) {
        throw new IllegalArgumentException(
                "Immutable function fields differ; rerun with --replace (replacement is not atomic)");
    }
    client.deleteFunction(desired.name());
    client.registerFunction(desired);
    return;
}
if (!patch.isEmpty()) {
    client.updateFunction(desired.name(), patch);
}
~~~

Add --replace to FnApplyCommand and DeployCommand. Do not invent rollback.

**Step 5: Add fn update**

Register FnUpdateCommand under fn. It accepts name plus required -f/--file, reads FunctionPatch through YamlIO, rejects empty input, checks functionUpdate capability, and calls updateFunction.

~~~java
@Override
public void run() {
    ControlPlaneClient client = parent.root.controlPlaneClient();
    if (!client.capabilities().functionUpdate()) {
        throw new IllegalStateException(
                "Function updates are not supported by this control-plane build");
    }
    FunctionPatch patch = YamlIO.read(file, FunctionPatch.class);
    if (patch.isEmpty()) {
        throw new IllegalArgumentException("Function update is empty");
    }
    client.updateFunction(name, patch);
}
~~~

**Step 6: Verify and commit**

~~~bash
./gradlew :nanofaas-cli:test --tests '*FnApplyCommandTest' --tests '*FnUpdateCommandTest' --tests '*DeployCommandTest' --no-daemon
./gradlew :nanofaas-cli:test --no-daemon
git add clients/cli/src/main/java/it/unimib/datai/nanofaas/cli/commands clients/cli/src/test/java/it/unimib/datai/nanofaas/cli/commands
git commit -m "fix(cli): require explicit function replacement"
~~~

### Task 7: Add managed replica commands

**Files:**
- Create: clients/cli/src/main/java/it/unimib/datai/nanofaas/cli/http/ReplicaStatus.java
- Modify: clients/cli/src/main/java/it/unimib/datai/nanofaas/cli/http/ControlPlaneClient.java
- Create: clients/cli/src/main/java/it/unimib/datai/nanofaas/cli/commands/fn/FnReplicasCommand.java
- Create: clients/cli/src/main/java/it/unimib/datai/nanofaas/cli/commands/fn/FnReplicasGetCommand.java
- Create: clients/cli/src/main/java/it/unimib/datai/nanofaas/cli/commands/fn/FnReplicasSetCommand.java
- Modify: clients/cli/src/main/java/it/unimib/datai/nanofaas/cli/commands/fn/FnCommand.java
- Create: clients/cli/src/test/java/it/unimib/datai/nanofaas/cli/commands/fn/FnReplicasCommandTest.java
- Modify: clients/cli/src/test/java/it/unimib/datai/nanofaas/cli/http/ControlPlaneClientTest.java

**Step 1: Check impact**

Run upstream impact for FnCommand and ControlPlaneClient.

**Step 2: Write failing tests**

Cover GET parsing, PUT body {"replicas":3}, get output, set to zero, missing capability, and preserved 400/503 errors.

**Step 3: Verify failure**

~~~bash
./gradlew :nanofaas-cli:test --tests '*FnReplicasCommandTest' --tests '*ControlPlaneClientTest' --no-daemon
~~~

Expected: FAIL because methods and commands are missing.

**Step 4: Implement**

~~~java
public record ReplicaStatus(String name, int desiredReplicas, int readyReplicas) {}
~~~

Add getReplicas(name) and setReplicas(name, replicas). Use Map.of("replicas", replicas) for PUT. Reject negative values before HTTP. Both leaves require capabilities().replicas(), print JSON with HttpJson, and live under one replicas parent.

**Step 5: Verify and commit**

~~~bash
./gradlew :nanofaas-cli:test --tests '*FnReplicasCommandTest' --tests '*ControlPlaneClientTest' --no-daemon
./gradlew :nanofaas-cli:test --no-daemon
git add clients/cli/src/main/java/it/unimib/datai/nanofaas/cli clients/cli/src/test/java/it/unimib/datai/nanofaas/cli
git commit -m "feat(cli): manage function replicas"
~~~

### Task 8: Add runtime-configuration administration

**Files:**
- Create: clients/cli/src/main/java/it/unimib/datai/nanofaas/cli/http/RuntimeConfigSnapshot.java
- Create: clients/cli/src/main/java/it/unimib/datai/nanofaas/cli/http/RuntimeConfigPatchRequest.java
- Create: clients/cli/src/main/java/it/unimib/datai/nanofaas/cli/http/RuntimeConfigPatchResponse.java
- Modify: clients/cli/src/main/java/it/unimib/datai/nanofaas/cli/http/ControlPlaneClient.java
- Modify: clients/cli/src/main/java/it/unimib/datai/nanofaas/cli/io/YamlIO.java
- Create: clients/cli/src/main/java/it/unimib/datai/nanofaas/cli/commands/controlplane/config/RuntimeConfigInput.java
- Create: clients/cli/src/main/java/it/unimib/datai/nanofaas/cli/commands/controlplane/config/ControlPlaneConfigCommand.java
- Create: clients/cli/src/main/java/it/unimib/datai/nanofaas/cli/commands/controlplane/config/ControlPlaneConfigGetCommand.java
- Create: clients/cli/src/main/java/it/unimib/datai/nanofaas/cli/commands/controlplane/config/ControlPlaneConfigValidateCommand.java
- Create: clients/cli/src/main/java/it/unimib/datai/nanofaas/cli/commands/controlplane/config/ControlPlaneConfigPatchCommand.java
- Modify: clients/cli/src/main/java/it/unimib/datai/nanofaas/cli/commands/controlplane/ControlPlaneCommand.java
- Create: clients/cli/src/test/java/it/unimib/datai/nanofaas/cli/commands/controlplane/config/ControlPlaneConfigCommandTest.java
- Modify: clients/cli/src/test/java/it/unimib/datai/nanofaas/cli/http/ControlPlaneClientTest.java
- Modify: clients/cli/src/test/java/it/unimib/datai/nanofaas/cli/io/YamlIOTest.java

**Step 1: Check impact**

Run upstream impact for ControlPlaneClient, YamlIO, and ControlPlaneCommand. Preserve YamlIO.read while adding tree access.

**Step 2: Write failing input/client tests**

Accept a plain values map and an envelope:

~~~yaml
expectedRevision: 7
values:
  maxQueueWait: PT2S
~~~

Assert null/7 revision and identical values. Cover aggregate GET, namespace GET, POST validate, and PATCH with exact paths and bodies.

**Step 3: Write failing command/error tests**

Cover get aggregate/namespace, validate success/422, patch with supplied revision, patch fetching revision when absent, 409 currentRevision, capability absent, and capability present plus 404. The disabled message must name nanofaas.admin.runtime-config.enabled=true.

**Step 4: Verify failure**

~~~bash
./gradlew :nanofaas-cli:test --tests '*ControlPlaneConfigCommandTest' --tests '*ControlPlaneClientTest' --tests '*YamlIOTest' --no-daemon
~~~

Expected: FAIL because support is absent.

**Step 5: Add generic models and YAML access**

~~~java
public record RuntimeConfigSnapshot(
        long revision,
        Map<String, Map<String, Object>> namespaces
) {}

public record RuntimeConfigPatchRequest(
        long expectedRevision,
        Map<String, Object> values
) {}

public record RuntimeConfigPatchResponse(
        long revision,
        RuntimeConfigSnapshot effectiveConfig,
        String appliedAt,
        String changeId,
        List<String> warnings
) {}
~~~

Expose YamlIO.readTree(Path) using the existing mapper. RuntimeConfigInput.load treats an object containing values as an envelope; otherwise the whole object is values. Reject non-object YAML and non-object values.

**Step 6: Add the HTTP methods**

Implement:
- GET v1/admin/runtime-config
- GET v1/admin/runtime-config/{namespace}
- POST v1/admin/runtime-config/{namespace}/validate
- PATCH v1/admin/runtime-config/{namespace}

Return typed aggregate/patch records and JsonNode for namespace/validation. Preserve ControlPlaneHttpException bodies.

**Step 7: Implement commands**

Every leaf requires runtimeConfig capability. Patch resolves:

~~~java
long revision = input.expectedRevision() != null
        ? input.expectedRevision()
        : client.getRuntimeConfig().revision();
RuntimeConfigPatchResponse response = client.patchRuntimeConfig(
        namespace, new RuntimeConfigPatchRequest(revision, input.values()));
~~~

Map only 404 disabled, 409 stale revision, and 422 validation to domain messages. Re-throw other errors. Keep stdout JSON.

**Step 8: Verify and commit**

~~~bash
./gradlew :nanofaas-cli:test --tests '*ControlPlaneConfigCommandTest' --tests '*ControlPlaneClientTest' --tests '*YamlIOTest' --no-daemon
./gradlew :nanofaas-cli:test --no-daemon
git add clients/cli/src/main/java/it/unimib/datai/nanofaas/cli clients/cli/src/test/java/it/unimib/datai/nanofaas/cli
git commit -m "feat(cli): administer runtime configuration"
~~~

### Task 9: Align API and CLI documentation

**Files:**
- Modify: openapi/core.yaml:144-185
- Modify: docs/nanofaas-cli.md
- Modify: docs/example-function.md:65-75
- Modify: docs/tutorial-function.md:168-210
- Test: clients/cli/src/test/java/it/unimib/datai/nanofaas/cli/commands/RootCommandTest.java
- Test: platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/api/OpenApiRouteCoverageTest.java

**Step 1: Check impact**

Run GitNexus API impact for /v1/functions/{name}:invoke and upstream impact for RootCommand.

**Step 2: Write final help assertions**

Assert top-level commands equal fn, invoke, enqueue, exec, deploy, control-plane. Add leaf help checks for fn replicas, fn update, control-plane info, and control-plane config patch.

**Step 3: Correct the invocation description**

Document X-NanoFaaS-Function-Status and state that handler-provided statuses replace 200 while the body remains InvocationResponse JSON. Do not claim raw handler output.

**Step 4: Update examples**

Remove --namespace. Add examples for update, replicas, info, contract, config get/validate/patch. Explain that optional commands remain visible but fail locally when unsupported, marked function non-2xx exits 1 after printing the envelope, and --replace is not atomic.

**Step 5: Verify and commit**

~~~bash
./gradlew :nanofaas-cli:test --tests '*RootCommandTest' --no-daemon
./gradlew :control-plane:test --tests '*OpenApiRouteCoverageTest' --no-daemon
git add openapi/core.yaml docs/nanofaas-cli.md docs/example-function.md docs/tutorial-function.md clients/cli/src/test/java/it/unimib/datai/nanofaas/cli/commands/RootCommandTest.java
git commit -m "docs: align CLI with modular control-plane"
~~~

Expected: PASS.

### Task 10: Full verification and review

**Files:**
- Verify only; no planned source changes.

**Step 1: Verify diff hygiene**

~~~bash
git diff --check main...HEAD
git status --short
~~~

Expected: no whitespace errors and a clean worktree.

**Step 2: Run CLI tests from scratch**

~~~bash
./gradlew :nanofaas-cli:test --rerun-tasks --no-daemon
~~~

Expected: BUILD SUCCESSFUL, zero failures.

**Step 3: Verify native CLI**

~~~bash
./gradlew :nanofaas-cli:nativeSmoke --no-daemon
~~~

Expected: native compilation and smoke pass. If GraalVM is unavailable, report the environmental blocker; do not claim success.

**Step 4: Verify server contracts**

~~~bash
./gradlew :control-plane:test --tests '*InvocationControllerTest' --tests '*FunctionControllerTest' --tests '*OpenApiRouteCoverageTest' --no-daemon
./gradlew :control-plane-modules:build-metadata:test --tests '*BuildMetadataControllerTest' --no-daemon
./gradlew :control-plane-modules:runtime-config:test --tests '*AdminRuntimeConfigIntegrationTest' --no-daemon
~~~

Expected: all PASS.

**Step 5: Inspect graph impact**

Run gitnexus_detect_changes with scope compare, base_ref main, repo nanofaas. Expected affected flows: CLI configuration, function apply/invoke, capabilities, replicas, runtime-config, and API documentation only.

**Step 6: Request and process review**

Use @superpowers:requesting-code-review. Ask specifically about marked/unmarked statuses, absence of implicit DELETE, optional capability guards, runtime-config revisions/errors, machine-readable stdout, offline help, and native-image support.

Use @superpowers:receiving-code-review before applying feedback. Each accepted finding gets a failing test, minimal fix, focused verification, and separate commit.

**Step 7: Final verification**

~~~bash
git diff --check main...HEAD
git status --short
./gradlew :nanofaas-cli:test --no-daemon
~~~

Expected: clean worktree and BUILD SUCCESSFUL.

Use @superpowers:finishing-a-development-branch after the user accepts the implementation.
