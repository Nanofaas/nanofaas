# Persistent Function Registry Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Persist the effective NanoFaaS function registry, including the desired replica target, and restore managed functions non-destructively before the control plane becomes ready.

**Architecture:** Keep persistence in `control-plane` as one concrete, versioned JSON catalog. Registry mutations write an atomic snapshot before reporting success; managed replica changes pass through the core coordinator; startup reloads the catalog and asks the exact recorded backend to reconcile its resources. Kubernetes and local-container reconciliation preserve healthy owned resources and create only what is missing.

**Tech Stack:** Java 25, Spring Boot 4.1, Jackson 3, Jakarta Validation, Java NIO, JUnit 5, Mockito, Fabric8 Kubernetes client, docker-java/Docker CLI, Gradle, Helm, Docker Compose.

---

## Implementation rules

- Work in `/Users/micheleciavotta/Downloads/nanofaas/.worktrees/persistent-function-registry` on branch `feat/persistent-function-registry`.
- Before changing an existing symbol in each task, run GitNexus upstream impact analysis for that symbol. Stop and warn the user if the result is `HIGH` or `CRITICAL`.
- Follow `superpowers:test-driven-development`: add one failing behavior at a time, run the focused test, implement the smallest change, rerun it.
- Do not add a storage SPI, database, journal, migration framework, or dependency. Java NIO, the existing Jackson mapper, and the existing validator are enough.
- Do not persist Kubernetes HPA observations. Persist only coordinator-driven desired replicas; restore HPA configuration and let Kubernetes resume control.
- Keep the OpenAPI files unchanged: this feature changes durability, not the public HTTP contract.
- Commit at the end of every task. Before every commit, run `git diff --check`, stage the intended files, and run `gitnexus_detect_changes({scope: "staged", repo: "nanofaas"})`.

### Task 1: Add the atomic JSON catalog

**Files:**

- Create: `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/registry/FunctionCatalogProperties.java`
- Create: `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/registry/FunctionCatalogSnapshot.java`
- Create: `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/registry/FunctionCatalog.java`
- Create: `platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/registry/FunctionCatalogTest.java`
- Modify: `platform/control-plane/src/main/resources/application.yml`

**Step 1: Confirm no existing persistence utility should be reused**

Run:

```bash
rg -n "ATOMIC_MOVE|FileChannel|ObjectMapper|ConfigurationProperties" platform/control-plane/src/main platform/control-plane/src/test
```

Expected: Jackson and configuration-property patterns exist, but no registry persistence implementation exists.

**Step 2: Write failing catalog tests**

Cover these behaviors in `FunctionCatalogTest` using `@TempDir`:

```java
@Test
void missingFileLoadsAnEmptyCatalog() {
    assertThat(catalog("missing/functions.json").load()).isEmpty();
}

@Test
void saveAndLoadRoundTripsInNameOrder() throws Exception {
    FunctionCatalog catalog = catalog("functions.json");
    catalog.save(List.of(managedFunction("zeta", 0), managedFunction("alpha", 2)));

    assertThat(catalog.load()).extracting(RegisteredFunction::name)
            .containsExactly("alpha", "zeta");
    assertThat(Files.readString(path("functions.json")))
            .contains("\"schemaVersion\" : 1")
            .containsSubsequence("alpha", "zeta");
}

@Test
void invalidOrUnsupportedCatalogFailsInsteadOfStartingEmpty() throws Exception {
    Files.writeString(path("functions.json"), "{\"schemaVersion\":99,\"functions\":[]}");

    assertThatThrownBy(() -> catalog("functions.json").load())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("schemaVersion");
}

@Test
void duplicateFunctionNamesFailLoading() throws Exception {
    Files.writeString(path("functions.json"), snapshotJson(function("echo"), function("echo")));

    assertThatThrownBy(() -> catalog("functions.json").load())
            .hasMessageContaining("Duplicate function 'echo'");
}

@Test
void failedReplacementLeavesThePreviousCatalogReadable() {
    // Inject a package-private move operation that throws after the temp file is forced.
    // Verify the original file still loads and the temp file is removed.
}
```

Also assert POSIX directory `0700` and file `0600` when `FileStore.supportsFileAttributeView(PosixFileAttributeView.class)`; skip only those permission assertions on non-POSIX stores.

**Step 3: Run the focused test and observe RED**

Run:

```bash
./gradlew :control-plane:test --tests '*FunctionCatalogTest'
```

Expected: compilation fails because `FunctionCatalog` and its properties do not exist.

**Step 4: Implement the minimal catalog**

Add the configuration record:

```java
@ConfigurationProperties(prefix = "nanofaas.registry")
public record FunctionCatalogProperties(
        @DefaultValue("build/nanofaas/functions.json") Path path
) {
}
```

Keep the serialized envelope package-private:

```java
record FunctionCatalogSnapshot(int schemaVersion, List<RegisteredFunction> functions) {
    static final int CURRENT_SCHEMA_VERSION = 1;

    FunctionCatalogSnapshot {
        functions = functions == null ? List.of() : List.copyOf(functions);
    }
}
```

Implement one concrete component. The production constructor uses `Files::move`; a package-private constructor accepts a tiny `MoveOperation` only so the atomic failure path is testable without mocking static NIO methods.

```java
@Component
final class FunctionCatalog {
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS =
            PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE_PERMISSIONS =
            PosixFilePermissions.fromString("rw-------");

    private final Path path;
    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final MoveOperation move;

    FunctionCatalog(FunctionCatalogProperties properties, ObjectMapper objectMapper, Validator validator) {
        this(properties.path(), objectMapper, validator,
                (source, target) -> Files.move(source, target,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING));
    }

    List<RegisteredFunction> load() {
        if (Files.notExists(path)) return List.of();
        try {
            FunctionCatalogSnapshot snapshot = objectMapper.readValue(path.toFile(), FunctionCatalogSnapshot.class);
            validate(snapshot);
            return snapshot.functions().stream()
                    .sorted(Comparator.comparing(RegisteredFunction::name))
                    .toList();
        } catch (IOException | RuntimeException failure) {
            throw new IllegalStateException("Cannot load function catalog " + path, failure);
        }
    }

    void save(Collection<RegisteredFunction> functions) {
        List<RegisteredFunction> sorted = functions.stream()
                .sorted(Comparator.comparing(RegisteredFunction::name))
                .toList();
        FunctionCatalogSnapshot snapshot =
                new FunctionCatalogSnapshot(FunctionCatalogSnapshot.CURRENT_SCHEMA_VERSION, sorted);
        validate(snapshot);

        Path parent = path.toAbsolutePath().getParent();
        Path temporary = null;
        try {
            Files.createDirectories(parent);
            setPermissionsIfSupported(parent, DIRECTORY_PERMISSIONS);
            temporary = Files.createTempFile(parent, path.getFileName().toString(), ".tmp");
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(Channels.newOutputStream(channel), snapshot);
                channel.force(true);
            }
            setPermissionsIfSupported(temporary, FILE_PERMISSIONS);
            move.move(temporary, path.toAbsolutePath());
            setPermissionsIfSupported(path.toAbsolutePath(), FILE_PERMISSIONS);
        } catch (IOException | RuntimeException failure) {
            throw new IllegalStateException("Cannot save function catalog " + path, failure);
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            }
        }
    }

    private void validate(FunctionCatalogSnapshot snapshot) {
        if (snapshot == null || snapshot.schemaVersion() != FunctionCatalogSnapshot.CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported function catalog schemaVersion");
        }
        Set<String> names = new HashSet<>();
        for (RegisteredFunction function : snapshot.functions()) {
            if (!names.add(function.name())) {
                throw new IllegalArgumentException("Duplicate function '" + function.name() + "'");
            }
            Set<ConstraintViolation<FunctionSpec>> violations = validator.validate(function.spec());
            if (!violations.isEmpty()) {
                throw new ConstraintViolationException(violations);
            }
        }
    }

    private static void setPermissionsIfSupported(Path target, Set<PosixFilePermission> permissions)
            throws IOException {
        if (Files.getFileStore(target).supportsFileAttributeView(PosixFileAttributeView.class)) {
            Files.setPosixFilePermissions(target, permissions);
        }
    }

    @FunctionalInterface
    interface MoveOperation {
        void move(Path source, Path target) throws IOException;
    }
}
```

Add the local default:

```yaml
nanofaas:
  registry:
    path: ${NANOFAAS_REGISTRY_PATH:build/nanofaas/functions.json}
```

Merge this under the existing `nanofaas:` key rather than creating a duplicate YAML key.

**Step 5: Run the focused test and control-plane tests**

Run:

```bash
./gradlew :control-plane:test --tests '*FunctionCatalogTest'
./gradlew :control-plane:test
```

Expected: both commands pass.

**Step 6: Commit**

```bash
git add platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/registry/FunctionCatalogProperties.java \
        platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/registry/FunctionCatalogSnapshot.java \
        platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/registry/FunctionCatalog.java \
        platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/registry/FunctionCatalogTest.java \
        platform/control-plane/src/main/resources/application.yml
git commit -m "Add atomic function catalog"
```

### Task 2: Make registry mutations durable and store desired replicas

**Files:**

- Modify: `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/registry/DeploymentMetadata.java`
- Modify: `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/registry/RegisteredFunction.java`
- Modify: `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/registry/FunctionRegistry.java`
- Create: `platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/registry/FunctionRegistryPersistenceTest.java`
- Modify: existing registry/service tests only where constructors need the new field or catalog dependency.

**Step 1: Run required impact analysis**

Run GitNexus upstream impact for `DeploymentMetadata`, `RegisteredFunction`, and `FunctionRegistry`.

Expected from the design audit: `FunctionRegistry` is `MEDIUM`; the metadata records are `LOW`. Re-run because the graph may have changed after Task 1, and enumerate all depth-1 dependents before editing.

**Step 2: Write failing persistence tests**

Use a real temporary `FunctionCatalog` and two registry instances:

```java
@Test
void constructorLoadsTheSavedSnapshot() {
    FunctionCatalog catalog = catalog(tempDir.resolve("functions.json"));
    catalog.save(List.of(managedFunction("echo", 0)));

    FunctionRegistry restarted = new FunctionRegistry(catalog);

    assertThat(restarted.getRegistered("echo")).get()
            .extracting(RegisteredFunction::desiredReplicas)
            .isEqualTo(0);
}

@Test
void failedSaveDoesNotPublishTheNewValueInMemory() {
    FunctionRegistry registry = new FunctionRegistry(failingCatalog());

    assertThatThrownBy(() -> registry.put(function("echo")))
            .isInstanceOf(IllegalStateException.class);
    assertThat(registry.getRegistered("echo")).isEmpty();
}

@Test
void successfulMutationIsVisibleToANewRegistry() {
    FunctionCatalog catalog = catalog(tempDir.resolve("functions.json"));
    FunctionRegistry registry = new FunctionRegistry(catalog);
    registry.put(managedFunction("echo", 2));

    assertThat(new FunctionRegistry(catalog).getRegistered("echo")).get()
            .extracting(RegisteredFunction::desiredReplicas)
            .isEqualTo(2);
}
```

Add tests for `putIfAbsent`, durable replacement, and durable removal. Assert a non-managed function has `desiredReplicas == null`, while a managed function initializes to `scalingConfig.minReplicas()` or `1` if absent.

**Step 3: Run RED**

```bash
./gradlew :control-plane:test --tests '*FunctionRegistryPersistenceTest'
```

Expected: compilation failures for the catalog-aware constructor and `desiredReplicas`.

**Step 4: Extend the persisted model**

Add the field to `DeploymentMetadata`, not `FunctionSpec`, because it is runtime control-plane state:

```java
public record DeploymentMetadata(
        ExecutionMode requestedExecutionMode,
        ExecutionMode effectiveExecutionMode,
        String deploymentBackend,
        String degradationReason,
        String effectiveEndpointUrl,
        Map<String, String> deploymentObjects,
        Integer desiredReplicas
) {
    public DeploymentMetadata {
        deploymentObjects = deploymentObjects == null ? Map.of() : Map.copyOf(deploymentObjects);
        if (effectiveExecutionMode != ExecutionMode.DEPLOYMENT && desiredReplicas != null) {
            throw new IllegalArgumentException("desiredReplicas is only valid for managed deployments");
        }
        if (desiredReplicas != null && desiredReplicas < 0) {
            throw new IllegalArgumentException("desiredReplicas must be >= 0");
        }
    }

    public DeploymentMetadata withDesiredReplicas(int replicas) {
        return new DeploymentMetadata(requestedExecutionMode, effectiveExecutionMode,
                deploymentBackend, degradationReason, effectiveEndpointUrl, deploymentObjects, replicas);
    }
}
```

Retain only compatibility constructors that current production/tests actually use. Have `RegisteredFunction` expose the value and construct a copy without duplicating metadata fields:

```java
public Integer desiredReplicas() {
    return deploymentMetadata.desiredReplicas();
}

public RegisteredFunction withDesiredReplicas(int replicas) {
    return new RegisteredFunction(spec, deploymentMetadata.withDesiredReplicas(replicas));
}
```

When registration creates managed metadata, initialize the field with:

```java
int initialDesiredReplicas = Optional.ofNullable(spec.scalingConfig())
        .map(ScalingConfig::minReplicas)
        .orElse(1);
```

The spec resolver already normalizes missing deployment scaling values to `1`; use the fallback defensively for older catalog input.

**Step 5: Replace concurrent in-place mutation with atomic snapshots**

Use a volatile immutable map for lock-free reads and synchronized copy/save/publish mutations:

```java
@Component
public class FunctionRegistry {
    private final FunctionCatalog catalog;
    private volatile Map<String, RegisteredFunction> functions;

    public FunctionRegistry(FunctionCatalog catalog) {
        this.catalog = catalog;
        this.functions = index(catalog.load());
    }

    public synchronized RegisteredFunction put(RegisteredFunction function) {
        Map<String, RegisteredFunction> next = new HashMap<>(functions);
        RegisteredFunction previous = next.put(function.name(), function);
        catalog.save(next.values());
        functions = Map.copyOf(next);
        return previous;
    }

    public synchronized RegisteredFunction putIfAbsent(RegisteredFunction function) {
        RegisteredFunction previous = functions.get(function.name());
        if (previous != null) return previous;
        put(function);
        return null;
    }

    public synchronized RegisteredFunction removeRegistered(String name) {
        RegisteredFunction previous = functions.get(name);
        if (previous == null) return null;
        Map<String, RegisteredFunction> next = new HashMap<>(functions);
        next.remove(name);
        catalog.save(next.values());
        functions = Map.copyOf(next);
        return previous;
    }

    synchronized RegisteredFunction detach(String name) {
        RegisteredFunction previous = functions.get(name);
        if (previous == null) return null;
        Map<String, RegisteredFunction> next = new HashMap<>(functions);
        next.remove(name);
        functions = Map.copyOf(next);
        return previous;
    }

    synchronized void restoreDetached(RegisteredFunction function) {
        Map<String, RegisteredFunction> next = new HashMap<>(functions);
        next.put(function.name(), function);
        functions = Map.copyOf(next);
    }

    synchronized void persistCurrentSnapshot() {
        catalog.save(functions.values());
    }

    synchronized void replaceAllDurably(Collection<RegisteredFunction> replacements) {
        Map<String, RegisteredFunction> next = index(replacements);
        catalog.save(next.values());
        functions = next;
    }
}
```

Keep `detach`, `restoreDetached`, and `persistCurrentSnapshot` package-private; they exist only to preserve delete ordering in Task 6, not as a general transaction API.

**Step 6: Run registry and control-plane tests**

```bash
./gradlew :control-plane:test --tests '*FunctionRegistryPersistenceTest' --tests '*FunctionService*Test'
./gradlew :control-plane:test
```

Expected: all pass.

**Step 7: Commit**

```bash
git add platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/registry \
        platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/registry
git commit -m "Persist function registry mutations"
```

### Task 3: Add non-destructive Kubernetes reconciliation

**Files:**

- Modify: `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/deployment/ManagedDeploymentProvider.java`
- Modify: `platform/modules/k8s-deployment-provider/src/main/java/it/unimib/datai/nanofaas/modules/k8s/deployment/KubernetesManagedDeploymentProvider.java`
- Modify: `platform/modules/k8s-deployment-provider/src/main/java/it/unimib/datai/nanofaas/modules/k8s/dispatch/KubernetesResourceManager.java`
- Modify: `platform/modules/k8s-deployment-provider/src/test/java/it/unimib/datai/nanofaas/modules/k8s/deployment/KubernetesManagedDeploymentProviderTest.java`
- Modify: `platform/modules/k8s-deployment-provider/src/test/java/it/unimib/datai/nanofaas/modules/k8s/dispatch/KubernetesResourceManagerTest.java`

**Step 1: Run required impact analysis**

Run upstream impact for `ManagedDeploymentProvider`, `KubernetesManagedDeploymentProvider`, and `KubernetesResourceManager`. Inspect depth-1 callers before editing.

Expected: all were `LOW` in the design audit; verify again.

**Step 2: Write failing reconciliation tests**

Add provider delegation coverage:

```java
@Test
void reconcileDelegatesThePersistedReplicaTargetAndObjectNames() {
    FunctionSpec spec = spec("echo", ScalingStrategy.INTERNAL);
    Map<String, String> objects = Map.of(
            ProvisionResult.NAMESPACE, "functions",
            ProvisionResult.DEPLOYMENT, "fn-echo",
            ProvisionResult.SERVICE, "fn-echo");

    provider.reconcile(spec, 0, objects);

    verify(resourceManager).reconcile(spec, 0, objects);
}
```

Add resource-manager tests for:

- existing coherent Deployment and Service are not deleted or recreated;
- missing Deployment or Service is created;
- non-HPA Deployment is patched to persisted `desiredReplicas`, including zero;
- HPA-managed Deployment replica count is left alone while a missing HPA is recreated;
- an existing HPA is preserved;
- malformed or mismatched persisted object names fail reconciliation.

Verify preservation with Fabric8 mock-server request counts, resource UIDs, and unchanged fields rather than merely checking final existence.

**Step 3: Run RED**

```bash
./gradlew :control-plane-modules:k8s-deployment-provider:test \
  --tests '*KubernetesManagedDeploymentProviderTest' \
  --tests '*KubernetesResourceManagerTest'
```

Expected: compilation fails because no reconcile contract exists.

**Step 4: Add the provider contract**

Add a temporary safe default so the branch compiles until Task 4 implements the container backend. Never delegate this default to destructive `provision`:

```java
default ProvisionResult reconcile(FunctionSpec spec,
                                  int desiredReplicas,
                                  Map<String, String> deploymentObjects) {
    throw new UnsupportedOperationException("Backend '" + backendId() + "' does not support reconciliation");
}
```

Implement delegation in the Kubernetes provider:

```java
@Override
public ProvisionResult reconcile(FunctionSpec spec,
                                 int desiredReplicas,
                                 Map<String, String> deploymentObjects) {
    return resourceManager.reconcile(spec, desiredReplicas, deploymentObjects);
}
```

**Step 5: Implement create-missing/preserve-existing behavior**

In `KubernetesResourceManager`, use the names and namespace from `deploymentObjects`; do not derive a new backend or silently fall back. Build desired resources with the existing builders, then apply only these decisions:

```java
public ProvisionResult reconcile(FunctionSpec spec,
                                 int desiredReplicas,
                                 Map<String, String> deploymentObjects) {
    ResourceNames names = requirePersistedNames(deploymentObjects);
    Deployment existingDeployment = client.apps().deployments()
            .inNamespace(names.namespace()).withName(names.deployment()).get();
    Service existingService = client.services()
            .inNamespace(names.namespace()).withName(names.service()).get();

    if (existingDeployment == null) {
        createDeployment(spec, names, desiredReplicas);
    } else if (!isHpaManaged(spec)
            && !Objects.equals(existingDeployment.getSpec().getReplicas(), desiredReplicas)) {
        client.apps().deployments().inNamespace(names.namespace())
                .withName(names.deployment()).scale(desiredReplicas);
    }
    if (existingService == null) {
        createService(spec, names);
    }
    if (isHpaManaged(spec) && getHpa(names) == null) {
        createHpa(spec, names);
    }

    return provisionResult(spec, names);
}
```

Extract only small private helpers from current `provision` when doing so removes duplication. Do not make a generic reconciler or rewrite the existing provisioning flow.

**Step 6: Run module tests**

```bash
./gradlew :control-plane-modules:k8s-deployment-provider:test
```

Expected: pass.

**Step 7: Commit**

```bash
git add platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/deployment/ManagedDeploymentProvider.java \
        platform/modules/k8s-deployment-provider
git commit -m "Reconcile persisted Kubernetes functions"
```

### Task 4: Discover and adopt local containers

**Files:**

- Modify: `platform/modules/container-deployment-provider/src/main/java/it/unimib/datai/nanofaas/modules/containerdeploymentprovider/ContainerInstanceSpec.java`
- Create: `platform/modules/container-deployment-provider/src/main/java/it/unimib/datai/nanofaas/modules/containerdeploymentprovider/ManagedContainer.java`
- Modify: `platform/modules/container-deployment-provider/src/main/java/it/unimib/datai/nanofaas/modules/containerdeploymentprovider/ContainerRuntimeAdapter.java`
- Modify: `platform/modules/container-deployment-provider/src/main/java/it/unimib/datai/nanofaas/modules/containerdeploymentprovider/DockerJavaContainerRuntimeAdapter.java`
- Modify: `platform/modules/container-deployment-provider/src/main/java/it/unimib/datai/nanofaas/modules/containerdeploymentprovider/CliContainerRuntimeAdapter.java`
- Modify: `platform/modules/container-deployment-provider/src/main/java/it/unimib/datai/nanofaas/modules/containerdeploymentprovider/ContainerLocalDeploymentProvider.java`
- Modify: corresponding tests under `platform/modules/container-deployment-provider/src/test/java/.../containerdeploymentprovider/`
- Modify: `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/deployment/ManagedDeploymentProvider.java`

**Step 1: Run required impact analysis**

Run upstream impact for all six existing container symbols and `ManagedDeploymentProvider`. Inspect direct callers, especially test adapters.

Expected: `LOW` in the design audit; verify again.

**Step 2: Write failing runtime-adapter tests**

Define stable ownership labels in `ContainerLocalDeploymentProvider`:

```java
static final String MANAGED_LABEL = "io.nanofaas.managed";
static final String FUNCTION_LABEL = "io.nanofaas.function";
static final String REPLICA_LABEL = "io.nanofaas.replica";
```

Test that both runtime adapters:

- pass all three labels when starting a replica;
- list stopped and running managed containers for one function;
- recover replica index and published host port;
- ignore containers lacking NanoFaaS ownership labels.

The shared data shapes are deliberately small:

```java
record ContainerInstanceSpec(
        String containerName,
        String image,
        Integer hostPort,
        List<String> command,
        Map<String, String> env,
        ResourceSpec resources,
        Map<String, String> labels
) {
    ContainerInstanceSpec {
        labels = labels == null ? Map.of() : Map.copyOf(labels);
    }
}

record ManagedContainer(String name, int replicaIndex, Integer hostPort, boolean running) {
}

interface ContainerRuntimeAdapter {
    // existing methods
    List<ManagedContainer> listManagedContainers(String functionName);
}
```

For docker-java, filter with `withLabelFilter(Map.of(MANAGED_LABEL, "true", FUNCTION_LABEL, functionName))`. For the CLI, use `docker ps -a --filter label=... --format ...` and `docker port <name> 8080/tcp`; keep command construction in the existing command runner so it remains unit-testable.

**Step 3: Run adapter tests and observe RED**

```bash
./gradlew :control-plane-modules:container-deployment-provider:test \
  --tests '*DockerJavaContainerRuntimeAdapterTest' \
  --tests '*CliContainerRuntimeAdapterTest'
```

Expected: compilation fails for labels and discovery.

**Step 4: Implement labels and discovery**

Add `.withLabels(spec.labels())` to docker-java creation and one `--label key=value` pair per entry to CLI creation. Parse only fields required by `ManagedContainer`; do not add a general container-inspection model.

Run the focused adapter tests until green.

**Step 5: Write failing provider reconciliation tests**

Add tests proving:

```java
@Test
void reconcileAdoptsHealthyOwnedContainersAndCreatesOnlyMissingReplicas() {
    when(runtime.listManagedContainers("echo")).thenReturn(List.of(
            new ManagedContainer("nanofaas-echo-r0", 0, 31001, true),
            new ManagedContainer("nanofaas-echo-r1", 1, 31002, true)));

    ProvisionResult result = provider.reconcile(spec("echo", 2), 2,
            Map.of(ProvisionResult.CONTAINER_NAME_PREFIX, "nanofaas-echo"));

    verify(runtime, never()).removeContainer(anyString());
    verify(runtime, never()).runContainer(any());
    assertThat(provider.getReplicaStatus("echo").readyReplicas()).isEqualTo(2);
    assertThat(result.endpointUrl()).isNotBlank();
}

@Test
void reconcileReplacesOnlyUnhealthyOrMissingReplicas() { /* exact r-index assertions */ }

@Test
void reconcileRemovesOnlyReplicasAboveThePersistedTarget() { /* scale-down assertions */ }
```

Also test that an invalid name prefix or duplicate replica index fails without removing an adopted healthy container.

**Step 6: Run provider test and observe RED**

```bash
./gradlew :control-plane-modules:container-deployment-provider:test \
  --tests '*ContainerLocalDeploymentProviderTest'
```

Expected: reconcile tests fail.

**Step 7: Implement adoption in the existing provider state**

Implement `reconcile` beside `provision`, rebuilding the in-memory proxy/state from discovered replicas:

```java
@Override
public ProvisionResult reconcile(FunctionSpec spec,
                                 int desiredReplicas,
                                 Map<String, String> deploymentObjects) {
    String prefix = requirePersistedPrefix(spec.name(), deploymentObjects);
    List<ManagedContainer> discovered = runtime.listManagedContainers(spec.name());
    validateReplicaIndexes(discovered);

    FunctionState state = createEmptyState(spec, prefix);
    Set<String> createdDuringReconcile = new HashSet<>();
    try {
        for (ManagedContainer container : discovered) {
            if (container.replicaIndex() >= desiredReplicas) {
                runtime.removeContainer(container.name());
            } else if (container.running() && container.hostPort() != null) {
                state.adopt(container);
            } else {
                runtime.removeContainer(container.name());
                createReplica(state, container.replicaIndex());
                createdDuringReconcile.add(container.name());
            }
        }
        createMissingReplicas(state, desiredReplicas, createdDuringReconcile);
        states.put(spec.name(), state);
        return resultFor(state);
    } catch (RuntimeException failure) {
        createdDuringReconcile.forEach(name -> suppressCleanup(name, failure));
        state.closeProxy();
        throw failure;
    }
}
```

Adapt the exact body to the existing `FunctionState`/proxy API. The invariant is more important than these helper names: cleanup after a failed restore may remove containers created by that restore, never healthy adopted containers.

Remove the temporary default body from `ManagedDeploymentProvider`; `reconcile` must now be abstract because both production backends implement it.

**Step 8: Run all container tests**

```bash
./gradlew :control-plane-modules:container-deployment-provider:test
```

Expected: pass. The Docker integration test may remain conditional on local Docker exactly as it is today.

**Step 9: Commit**

```bash
git add platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/deployment/ManagedDeploymentProvider.java \
        platform/modules/container-deployment-provider
git commit -m "Adopt persisted local containers"
```

### Task 5: Serialize function operations and persist coordinator scaling

**Files:**

- Create: `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/registry/FunctionOperationLocks.java`
- Create: `platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/registry/FunctionOperationLocksTest.java`
- Modify: `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/registry/FunctionService.java`
- Modify: `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/deployment/ManagedDeploymentCoordinator.java`
- Modify: `platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/deployment/ManagedDeploymentCoordinatorTest.java`
- Modify: `platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/registry/FunctionServiceConcurrencyTest.java`
- Modify only constructor setup in autoscaler and wake-up tests if required.

**Step 1: Run required impact analysis**

Run upstream impact for `FunctionService` and `ManagedDeploymentCoordinator`, then inspect both contexts. The design audit rated the coordinator `MEDIUM` because `FunctionService`, autoscaler, concurrency control, and wake-up paths call it. Include every depth-1 path in regression tests.

**Step 2: Extract the existing lock without changing behavior**

Move the current `ConcurrentHashMap<String, LockEntry>` and its acquire/release logic verbatim into one concrete component:

```java
@Component
public final class FunctionOperationLocks {
    private final ConcurrentHashMap<String, LockEntry> locks = new ConcurrentHashMap<>();

    public <T> T withLock(String functionName, Supplier<T> action) {
        LockEntry entry = acquire(functionName);
        entry.lock.lock();
        try {
            return action.get();
        } finally {
            entry.lock.unlock();
            release(functionName, entry);
        }
    }

    public void withLock(String functionName, Runnable action) {
        withLock(functionName, () -> { action.run(); return null; });
    }

    // Copy acquire/release and LockEntry from FunctionService unchanged.
}
```

First run existing concurrency tests after making `FunctionService` delegate to it. This is a pure extraction; do not redesign the lock.

```bash
./gradlew :control-plane:test --tests '*FunctionServiceConcurrencyTest' --tests '*FunctionOperationLocksTest'
```

Expected: pass before proceeding.

**Step 3: Write failing coordinator durability tests**

Test the exact order with Mockito `InOrder`:

```java
@Test
void setReplicasPersistsTargetBeforeApplyingProviderChange() {
    registry.put(managedFunction("fn", 1));

    coordinator.setReplicas(target("fn"), 3);

    InOrder order = inOrder(registry, provider);
    order.verify(registry).put(argThat(function -> function.desiredReplicas() == 3));
    order.verify(provider).setReplicas("fn", 3);
}

@Test
void providerFailureRestoresThePreviousDurableTarget() {
    registry.put(managedFunction("fn", 1));
    doThrow(new IllegalStateException("scale failed")).when(provider).setReplicas("fn", 3);

    assertThatThrownBy(() -> coordinator.setReplicas(target("fn"), 3))
            .hasMessageContaining("scale failed");
    assertThat(registry.getRegistered("fn")).get()
            .extracting(RegisteredFunction::desiredReplicas)
            .isEqualTo(1);
}
```

Add a concurrency test where an autoscaler-style coordinator call races with removal and verify only one complete per-function operation order is possible.

**Step 4: Run RED**

```bash
./gradlew :control-plane:test --tests '*ManagedDeploymentCoordinatorTest' \
  --tests '*FunctionServiceConcurrencyTest'
```

Expected: new durability/order tests fail.

**Step 5: Make the coordinator the single persistence point**

Inject `FunctionRegistry` and `FunctionOperationLocks`. Preserve the small resolver-only constructor only if unit tests outside Spring genuinely need it; otherwise update callers and delete it.

```java
public void setReplicas(ManagedDeploymentTarget target, int replicas) {
    if (replicas < 0) throw new IllegalArgumentException("replicas must be >= 0");
    locks.withLock(target.functionName(), () -> {
        RegisteredFunction existing = registry.getRegistered(target.functionName())
                .orElseThrow(() -> new IllegalStateException(
                        "Function '" + target.functionName() + "' is not registered"));
        if (!existing.managedDeploymentTarget().filter(target::equals).isPresent()) {
            throw new IllegalStateException("Persisted deployment backend does not match " + target);
        }

        RegisteredFunction updated = existing.withDesiredReplicas(replicas);
        registry.put(updated);
        try {
            requireProvider(target).setReplicas(target.functionName(), replicas);
        } catch (RuntimeException failure) {
            try { registry.put(existing); } catch (RuntimeException rollback) { failure.addSuppressed(rollback); }
            throw failure;
        }
    });
}
```

Remove the outer function lock from `FunctionService.setReplicas`; the coordinator now owns it for every caller: HTTP/manual scaling, `InternalScaler`, and `DeploymentWakeUpGate`. Keep lookup semantics by checking existence before calling the coordinator or add a coordinator result only if that produces less code without changing the API.

**Step 6: Run all affected paths**

```bash
./gradlew :control-plane:test --tests '*ManagedDeploymentCoordinatorTest' \
  --tests '*FunctionService*Test' \
  --tests '*DeploymentWakeUpGateTest'
./gradlew :control-plane-modules:autoscaler:test
./gradlew :control-plane-modules:concurrency-control:test
```

Expected: pass. Existing autoscaler/wake-up verifications still call `ManagedDeploymentCoordinator.setReplicas`; no module-specific persistence code is added.

**Step 7: Commit**

```bash
git add platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/registry \
        platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/deployment/ManagedDeploymentCoordinator.java \
        platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/registry \
        platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/deployment/ManagedDeploymentCoordinatorTest.java \
        platform/modules/autoscaler/src/test \
        platform/modules/concurrency-control/src/test
git commit -m "Persist managed replica targets"
```

### Task 6: Make registration, update, and removal obey durable commit ordering

**Files:**

- Modify: `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/registry/FunctionService.java`
- Modify: `platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/registry/FunctionServiceTest.java`
- Modify: `platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/registry/FunctionServiceManagedDeploymentTest.java`
- Modify: `platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/registry/FunctionServiceConcurrencyTest.java`

**Step 1: Re-run impact analysis**

Run upstream impact for `FunctionService` immediately before editing. Review any changed depth-1 paths after Task 5.

**Step 2: Write failing lifecycle tests**

Add these cases:

- registration: provision, listeners, durable `registry.put`; a catalog failure rolls back already-notified listeners and deprovisions newly created resources;
- update: durable replacement precedes listener convergence; catalog failure leaves both old registry value and listeners untouched;
- delete: function is hidden in memory while listeners/provider teardown run, but remains in the catalog until teardown completes;
- delete catalog failure: restore in-memory entry, replay notified listeners, and reconcile the exact backend if deprovision already succeeded;
- interrupted registration may leave an orphan backend resource, documented by the lack of an intent journal; startup never guesses that the orphan belongs to the catalog.

Representative ordering assertion:

```java
@Test
void registrationCommitsOnlyAfterProvisionAndListeners() {
    service.register(deploymentSpec("echo"));

    InOrder order = inOrder(provider, listener, registry);
    order.verify(provider).provision(any());
    order.verify(listener).onRegister(any());
    order.verify(registry).put(any(RegisteredFunction.class));
}
```

For catalog-failure tests, use a real registry with a controllable failing `FunctionCatalog`; do not mock away the in-memory-versus-file invariant.

**Step 3: Run RED**

```bash
./gradlew :control-plane:test --tests '*FunctionServiceTest' \
  --tests '*FunctionServiceManagedDeploymentTest' \
  --tests '*FunctionServiceConcurrencyTest'
```

Expected: the new rollback/order tests fail.

**Step 4: Implement minimal transaction ordering**

Registration:

```java
RegisteredFunction registered = resolveRegistration(initialResolved);
List<FunctionRegistrationListener> notified = new ArrayList<>();
try {
    for (FunctionRegistrationListener listener : listeners) {
        listener.onRegister(registered.spec());
        notified.add(listener);
    }
    registry.put(registered); // durable commit
    return Optional.of(registered);
} catch (RuntimeException failure) {
    rollbackRegistrationListeners(registered.name(), notified, failure);
    rollbackProvisionedRegistration(registered, failure);
    throw failure;
}
```

Update writes the new durable snapshot before listeners, preserving the current idempotent-listener convergence rule.

Removal uses the package-private transient registry methods from Task 2:

```java
RegisteredFunction existing = registry.detach(name);
if (existing == null) return Optional.empty();

List<FunctionRegistrationListener> notified = new ArrayList<>();
boolean deprovisioned = false;
try {
    for (FunctionRegistrationListener listener : listeners) {
        listener.onRemove(name);
        notified.add(listener);
    }
    if (existing.managedDeploymentTarget().isPresent()) {
        managedDeploymentCoordinator.deprovision(existing.managedDeploymentTarget().orElseThrow());
        deprovisioned = true;
    }
    registry.persistCurrentSnapshot(); // durable delete commit happens last
    return Optional.of(existing.spec());
} catch (RuntimeException failure) {
    registry.restoreDetached(existing);
    rollbackRemovalListeners(existing.spec(), notified, failure);
    if (deprovisioned) {
        try { reconcile(existing); } catch (RuntimeException rollback) { failure.addSuppressed(rollback); }
    }
    throw failure;
}
```

`reconcile(existing)` must require the exact persisted backend and call its new contract with `existing.spec()`, `existing.desiredReplicas()`, and persisted `deploymentObjects`. It must not invoke backend selection or degradation.

Keep registration/update/removal inside `FunctionOperationLocks`. Coordinator `setReplicas` uses the same component, so resource teardown cannot overlap a scale operation for that name.

**Step 5: Run affected tests**

```bash
./gradlew :control-plane:test --tests '*FunctionService*Test'
./gradlew :control-plane:test
```

Expected: pass.

**Step 6: Commit**

```bash
git add platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/registry/FunctionService.java \
        platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/registry
git commit -m "Make function lifecycle commits durable"
```

### Task 7: Restore the catalog before readiness

**Files:**

- Create: `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/registry/FunctionCatalogRestorer.java`
- Create: `platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/registry/FunctionCatalogRestorerTest.java`
- Modify: `platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/ControlPlaneApplicationTest.java` or the existing Spring context smoke test.
- Modify native runtime hints only if `nativeTestCompile` proves record binding needs them.

**Step 1: Impact-check reused startup symbols**

The restorer is new. Run upstream impact for `FunctionRegistry`, `DeploymentProviderResolver.requireBackend`, and `FunctionRegistrationListener` before wiring them together. Inspect context to ensure no existing startup runner already owns listener replay.

**Step 2: Write failing startup tests**

Cover:

- empty catalog performs no work;
- records are processed in function-name order;
- non-managed records replay listeners without provider calls;
- managed records require the exact persisted backend, reconcile with persisted desired replicas/object names, replace refreshed metadata once, then replay listeners;
- unavailable/missing backend, reconcile error, listener error, invalid record, or final snapshot error escapes `run` and fails startup;
- no fallback to another backend and no degraded execution mode;
- existing provider resources are never globally swept or deleted.

Representative test:

```java
@Test
void restoresExactBackendBeforeReplayingListeners() {
    registry.replaceAllDurably(List.of(managedFunction("echo", "container-local", 0)));
    when(resolver.requireBackend("container-local")).thenReturn(provider);
    when(provider.reconcile(any(), eq(0), anyMap())).thenReturn(reconciledResult());

    restorer.run(new DefaultApplicationArguments());

    InOrder order = inOrder(provider, registry, listener);
    order.verify(provider).reconcile(any(), eq(0), anyMap());
    order.verify(registry).replaceAllDurably(anyCollection());
    order.verify(listener).onRegister(any());
    verify(resolver, never()).resolveAndProvision(any(), any());
}
```

**Step 3: Run RED**

```bash
./gradlew :control-plane:test --tests '*FunctionCatalogRestorerTest'
```

Expected: compilation fails because the runner does not exist.

**Step 4: Implement one blocking `ApplicationRunner`**

```java
@Component
final class FunctionCatalogRestorer implements ApplicationRunner {
    private final FunctionRegistry registry;
    private final DeploymentProviderResolver resolver;
    private final List<FunctionRegistrationListener> listeners;

    @Override
    public void run(ApplicationArguments arguments) {
        List<RegisteredFunction> restored = new ArrayList<>();
        for (RegisteredFunction function : registry.listRegistered().stream()
                .sorted(Comparator.comparing(RegisteredFunction::name)).toList()) {
            restored.add(reconcileIfManaged(function));
        }
        registry.replaceAllDurably(restored);
        for (RegisteredFunction function : restored) {
            for (FunctionRegistrationListener listener : listeners) {
                listener.onRegister(function.spec());
            }
        }
    }

    private RegisteredFunction reconcileIfManaged(RegisteredFunction function) {
        ManagedDeploymentTarget target = function.managedDeploymentTarget().orElse(null);
        if (target == null) return function;
        ManagedDeploymentProvider provider = resolver.requireBackend(target.backendId());
        ProvisionResult result = provider.reconcile(
                function.spec(), function.desiredReplicas(), function.deploymentMetadata().deploymentObjects());
        return function.withProvisionResult(result); // small copy helper, exact backend must remain unchanged
    }
}
```

`ApplicationRunner` completes before Spring Boot publishes readiness. Let exceptions propagate; do not catch-and-log them. Do not add a custom readiness state machine.

`withProvisionResult` may refresh endpoint and object names returned by the same backend but must reject a changed backend ID or effective execution mode.

**Step 5: Run startup and native binding checks**

```bash
./gradlew :control-plane:test --tests '*FunctionCatalogRestorerTest' --tests '*ControlPlaneApplication*Test'
./gradlew :control-plane:nativeTestCompile
```

Expected: pass. Add a focused `RuntimeHintsRegistrar` only if native compilation or a native serialization test demonstrates it is required; otherwise skip it.

**Step 6: Commit**

```bash
git add platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/registry \
        platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane
git commit -m "Restore persisted functions at startup"
```

### Task 8: Persist the catalog in Helm and Docker Compose

**Files:**

- Modify: `deploy/helm/nanofaas/values.yaml`
- Create: `deploy/helm/nanofaas/templates/control-plane-pvc.yaml`
- Modify: `deploy/helm/nanofaas/templates/control-plane-deployment.yaml`
- Modify: `deploy/compose/compose.yaml`
- Modify: `scripts/tests/test_docker_compose_deployment.py`
- Create or modify the existing Helm rendering test under `scripts/tests/`.
- Modify: `docs/control-plane.md`
- Modify: `deploy/compose/README.md`

**Step 1: Impact-check deployment templates**

No Java symbol changes are planned. Render the current chart and inspect existing PVC/security-context conventions before editing:

```bash
rg -n "persistentVolumeClaim|existingClaim|storageClass|fsGroup|volumeMounts" deploy/helm/nanofaas
helm template nanofaas deploy/helm/nanofaas >/tmp/nanofaas-before.yaml
```

Expected: reuse the chart's existing naming helpers and Prometheus PVC style; do not add a chart library.

**Step 2: Write failing deployment assertions**

In the Compose test assert:

```python
control_plane = compose["services"]["control-plane"]
assert "control-plane-data:/var/lib/nanofaas" in control_plane["volumes"]
assert control_plane["environment"]["NANOFAAS_REGISTRY_PATH"] == "/var/lib/nanofaas/functions.json"
assert "control-plane-data" in compose["volumes"]
```

Add Helm render assertions for:

- default PVC enabled and mounted at `/var/lib/nanofaas`;
- `NANOFAAS_REGISTRY_PATH=/var/lib/nanofaas/functions.json`;
- configurable size, storage class, and existing claim;
- no new PVC when `existingClaim` is set;
- pod `fsGroup` allows the existing distroless non-root user to write;
- `controlPlane.replicaCount` remains the control-plane pod count and defaults to one.

**Step 3: Run RED**

```bash
uv run pytest scripts/tests/test_docker_compose_deployment.py scripts/tests/test_helm_chart.py
```

If the Helm test has a different existing filename, use that file rather than creating a duplicate suite. Expected: new volume assertions fail.

**Step 4: Add the minimum deployment configuration**

Add values following existing chart naming:

```yaml
controlPlane:
  replicaCount: 1
  persistence:
    enabled: true
    size: 1Gi
    storageClass: ""
    existingClaim: ""
```

The PVC template should mirror the existing Prometheus PVC condition/style. In the Deployment, mount:

```yaml
- name: registry-data
  mountPath: /var/lib/nanofaas
```

and set:

```yaml
- name: NANOFAAS_REGISTRY_PATH
  value: /var/lib/nanofaas/functions.json
```

Use a pod-level `fsGroup` matching the chart's existing non-root UID/GID convention. Do not change the function replica semantics: managed functions may have `desiredReplicas: 0`; only the control-plane itself remains a single pod.

For Compose:

```yaml
services:
  control-plane:
    environment:
      NANOFAAS_REGISTRY_PATH: /var/lib/nanofaas/functions.json
    volumes:
      - control-plane-data:/var/lib/nanofaas

volumes:
  control-plane-data:
```

**Step 5: Document operations and security**

Document:

- absent file means an empty first start;
- corrupt/unreadable/unsupported state fails startup;
- restore uses the recorded backend and never silently degrades;
- the file contains environment variables in plaintext;
- directory/file modes are `0700`/`0600` on POSIX;
- operators should secure and back up the PVC/volume;
- Helm and Compose persistence paths and configuration knobs;
- incomplete registration can leave an orphan because the MVP has no intent journal;
- unrelated residual Kubernetes resources or containers are not swept at startup.

Do not add an OpenAPI change.

**Step 6: Run rendering and script tests**

```bash
helm lint deploy/helm/nanofaas
helm template nanofaas deploy/helm/nanofaas >/tmp/nanofaas-after.yaml
docker compose -f deploy/compose/compose.yaml config >/tmp/nanofaas-compose.yaml
uv run pytest scripts/tests/test_docker_compose_deployment.py scripts/tests/test_helm_chart.py
```

Expected: all pass. If Docker Compose is unavailable, record the exact failure and still run the YAML unit assertion.

**Step 7: Commit**

```bash
git add deploy/helm/nanofaas deploy/compose scripts/tests docs/control-plane.md
git commit -m "Persist registry deployment state"
```

### Task 9: Verify the complete recovery contract

**Files:**

- Modify only tests or documentation needed to close a demonstrated gap.

**Step 1: Run focused recovery suites**

```bash
./gradlew :control-plane:test \
  --tests '*FunctionCatalogTest' \
  --tests '*FunctionRegistryPersistenceTest' \
  --tests '*FunctionCatalogRestorerTest' \
  --tests '*FunctionService*Test' \
  --tests '*ManagedDeploymentCoordinatorTest'
./gradlew :control-plane-modules:k8s-deployment-provider:test
./gradlew :control-plane-modules:container-deployment-provider:test
./gradlew :control-plane-modules:autoscaler:test
./gradlew :control-plane-modules:concurrency-control:test
```

Expected: all pass.

**Step 2: Run whole-project and native verification**

Use `superpowers:verification-before-completion` and run fresh commands:

```bash
./gradlew test
./gradlew :control-plane:nativeTestCompile
git diff --check
git status --short
```

Expected: all tests and native test compilation pass; only intentional changes are present.

**Step 3: Exercise a real restart where the environment supports it**

Container scenario:

1. Start `deploy/compose/compose.yaml`.
2. Register a `container-local` function and set replicas to `0`, then `2`.
3. Restart only the control-plane service without deleting the named volume or function containers.
4. Verify the registration returns, healthy containers are adopted rather than recreated, and the persisted desired target is restored.

Kubernetes scenario is owned by NanoLab. From a NanoLab checkout with `NANOFAAS_ROOT` pointing here, run the repository-documented validation command only if that scenario exists in the checkout:

```bash
nanolab.sh run packages/nanolab/scenarios-v2/validate-k8s.yaml \
  --environment packages/nanolab/environments/multipass.yaml
```

Extend NanoLab in its own change if restart/adoption assertions are absent; do not copy infrastructure provisioning into NanoFaaS tests.

**Step 4: Check graph impact before final commit**

Stage only any final gap fixes, then run:

```text
gitnexus_detect_changes({scope: "staged", repo: "nanofaas"})
```

Confirm:

- all depth-1 dependents from the earlier `MEDIUM` impacts are covered;
- changes are limited to registry persistence, exact-backend reconciliation, startup restore, and deployment configuration;
- no unexpected HTTP/OpenAPI execution flow changed.

If there are final fixes:

```bash
git commit -m "Verify persistent registry recovery"
```

If there are none, do not create an empty commit.

**Step 5: Refresh GitNexus after the final commit**

Check `.gitnexus/meta.json` first. If `stats.embeddings` is zero:

```bash
npx gitnexus analyze
```

Otherwise:

```bash
npx gitnexus analyze --embeddings
```

Finally run `git status --short` and restore only analyzer-generated documentation drift if it changed tracked generated blocks; do not discard implementation changes.
