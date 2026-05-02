# Pluggable DEPLOYMENT Providers — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Decouple the control-plane core from Kubernetes so that `DEPLOYMENT` mode becomes a provider-backed intent, with `k8s` and `container-local` as pluggable optional modules.

**Architecture:** Introduce a `ManagedDeploymentProvider` SPI in the core with a deterministic resolver. Extract all Kubernetes deployment code into `control-plane-modules/k8s-deployment-provider`. Add a `container-local` provider module for single-node warm containers. API responses shift from raw `FunctionSpec` echo to dedicated DTOs exposing requested/effective mode, backend, and degradation metadata.

**Tech Stack:** Java 21, Spring Boot (WebFlux), Gradle multi-module, Fabric8 (k8s module only), Java ServiceLoader SPI, JUnit 5, Mockito, WebTestClient.

**Issue:** [miciav/nanofaas#51](https://github.com/miciav/nanofaas/issues/51)

---

## Phase 1: Core Contracts and Metadata

**Gate:** All existing tests pass. New SPI, resolver, response DTOs, and fallback logic are unit-tested. No behavioral changes to existing flows yet — `FunctionService` still uses `KubernetesResourceManager` directly. This phase is purely additive.

---

### Task 1: Define the `ManagedDeploymentProvider` SPI

**Files:**
- Create: `control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/deployment/ManagedDeploymentProvider.java`
- Create: `control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/deployment/ProvisionResult.java`

- [ ] **Step 1: Create the `ProvisionResult` record**

```java
package it.unimib.datai.nanofaas.controlplane.deployment;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import java.util.Map;

public record ProvisionResult(
        String endpointUrl,
        String backendId,
        ExecutionMode effectiveExecutionMode,
        String degradationReason,
        Map<String, String> metadata
) {
    public ProvisionResult(String endpointUrl, String backendId) {
        this(endpointUrl, backendId, ExecutionMode.DEPLOYMENT, null, Map.of());
    }
}
```

- [ ] **Step 2: Create the `ManagedDeploymentProvider` interface**

```java
package it.unimib.datai.nanofaas.controlplane.deployment;

import it.unimib.datai.nanofaas.common.model.FunctionSpec;

public interface ManagedDeploymentProvider {
    String backendId();
    boolean isAvailable();
    boolean supports(FunctionSpec spec);
    ProvisionResult provision(FunctionSpec spec);
    void deprovision(String functionName);
    void setReplicas(String functionName, int replicas);
    int getReadyReplicas(String functionName);
}
```

- [ ] **Step 3: Commit**

```bash
git add control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/deployment/
git commit -m "feat: add ManagedDeploymentProvider SPI and ProvisionResult"
```

---

### Task 2: Build the `ManagedDeploymentProviderResolver`

**Files:**
- Create: `control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/deployment/DeploymentProviderResolver.java`
- Create: `control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/deployment/DeploymentProperties.java`
- Create: `control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/deployment/DeploymentProviderResolverTest.java`

- [ ] **Step 1: Create `DeploymentProperties`**

```java
package it.unimib.datai.nanofaas.controlplane.deployment;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "nanofaas.deployment")
public record DeploymentProperties(
        String defaultBackend
) {
    public DeploymentProperties() {
        this(null);
    }
}
```

- [ ] **Step 2: Write the failing tests for `DeploymentProviderResolverTest`**

Test cases:
1. Explicit backend hint selects that provider
2. Default backend config is preferred when no hint
3. Single available provider is selected when no hint and no default
4. Ambiguity (multiple providers, no hint, no default) throws `IllegalStateException`
5. Unavailable provider throws `IllegalStateException`
6. No providers at all throws `IllegalStateException`
7. Provider that doesn't support the spec throws `IllegalStateException`

```java
package it.unimib.datai.nanofaas.controlplane.deployment;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DeploymentProviderResolverTest {

    private static ManagedDeploymentProvider stubProvider(String id, boolean available, boolean supports) {
        ManagedDeploymentProvider p = mock(ManagedDeploymentProvider.class);
        when(p.backendId()).thenReturn(id);
        when(p.isAvailable()).thenReturn(available);
        when(p.supports(any())).thenReturn(supports);
        return p;
    }

    private static FunctionSpec spec(String name) {
        return new FunctionSpec(name, "img:latest", null, null, null,
                null, null, null, null, null, ExecutionMode.DEPLOYMENT, null, null, null);
    }

    @Test
    void explicitHint_selectsMatchingProvider() {
        ManagedDeploymentProvider k8s = stubProvider("k8s", true, true);
        ManagedDeploymentProvider local = stubProvider("container-local", true, true);
        var resolver = new DeploymentProviderResolver(List.of(k8s, local), new DeploymentProperties(null));

        assertThat(resolver.resolve(spec("fn"), "k8s")).isSameAs(k8s);
    }

    @Test
    void explicitHint_unavailableProvider_throws() {
        ManagedDeploymentProvider k8s = stubProvider("k8s", false, true);
        var resolver = new DeploymentProviderResolver(List.of(k8s), new DeploymentProperties(null));

        assertThatThrownBy(() -> resolver.resolve(spec("fn"), "k8s"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not available");
    }

    @Test
    void explicitHint_doesNotSupport_throws() {
        ManagedDeploymentProvider k8s = stubProvider("k8s", true, false);
        var resolver = new DeploymentProviderResolver(List.of(k8s), new DeploymentProperties(null));

        assertThatThrownBy(() -> resolver.resolve(spec("fn"), "k8s"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not support");
    }

    @Test
    void defaultBackend_preferred() {
        ManagedDeploymentProvider k8s = stubProvider("k8s", true, true);
        ManagedDeploymentProvider local = stubProvider("container-local", true, true);
        var resolver = new DeploymentProviderResolver(List.of(k8s, local), new DeploymentProperties("container-local"));

        assertThat(resolver.resolve(spec("fn"), null)).isSameAs(local);
    }

    @Test
    void singleProvider_selected() {
        ManagedDeploymentProvider k8s = stubProvider("k8s", true, true);
        var resolver = new DeploymentProviderResolver(List.of(k8s), new DeploymentProperties(null));

        assertThat(resolver.resolve(spec("fn"), null)).isSameAs(k8s);
    }

    @Test
    void ambiguousProviders_throws() {
        ManagedDeploymentProvider k8s = stubProvider("k8s", true, true);
        ManagedDeploymentProvider local = stubProvider("container-local", true, true);
        var resolver = new DeploymentProviderResolver(List.of(k8s, local), new DeploymentProperties(null));

        assertThatThrownBy(() -> resolver.resolve(spec("fn"), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Ambiguous");
    }

    @Test
    void noProviders_throws() {
        var resolver = new DeploymentProviderResolver(List.of(), new DeploymentProperties(null));

        assertThatThrownBy(() -> resolver.resolve(spec("fn"), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No managed deployment provider");
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :control-plane:test --tests '*DeploymentProviderResolverTest' -PcontrolPlaneModules=none`
Expected: FAIL — class does not exist.

- [ ] **Step 4: Implement `DeploymentProviderResolver`**

```java
package it.unimib.datai.nanofaas.controlplane.deployment;

import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DeploymentProviderResolver {

    private final List<ManagedDeploymentProvider> providers;
    private final DeploymentProperties properties;

    public DeploymentProviderResolver(List<ManagedDeploymentProvider> providers,
                                      DeploymentProperties properties) {
        this.providers = providers != null ? providers : List.of();
        this.properties = properties;
    }

    /**
     * Resolves the provider for a DEPLOYMENT-mode function.
     * @param spec the function spec
     * @param backendHint explicit backend id from the request, or null
     * @return the selected provider
     * @throws IllegalStateException if no suitable provider is found
     */
    public ManagedDeploymentProvider resolve(FunctionSpec spec, String backendHint) {
        if (backendHint != null) {
            return resolveExplicit(spec, backendHint);
        }
        if (properties.defaultBackend() != null) {
            return resolveExplicit(spec, properties.defaultBackend());
        }
        return resolveImplicit(spec);
    }

    public boolean hasProviders() {
        return !providers.isEmpty();
    }

    private ManagedDeploymentProvider resolveExplicit(FunctionSpec spec, String backendId) {
        ManagedDeploymentProvider provider = providers.stream()
                .filter(p -> p.backendId().equals(backendId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No managed deployment provider with id '" + backendId + "' found"));
        if (!provider.isAvailable()) {
            throw new IllegalStateException(
                    "Provider '" + backendId + "' is not available");
        }
        if (!provider.supports(spec)) {
            throw new IllegalStateException(
                    "Provider '" + backendId + "' does not support function '" + spec.name() + "'");
        }
        return provider;
    }

    private ManagedDeploymentProvider resolveImplicit(FunctionSpec spec) {
        List<ManagedDeploymentProvider> candidates = providers.stream()
                .filter(ManagedDeploymentProvider::isAvailable)
                .filter(p -> p.supports(spec))
                .toList();

        if (candidates.isEmpty()) {
            throw new IllegalStateException(
                    "No managed deployment provider available for function '" + spec.name() + "'");
        }
        if (candidates.size() > 1) {
            List<String> ids = candidates.stream().map(ManagedDeploymentProvider::backendId).toList();
            throw new IllegalStateException(
                    "Ambiguous provider selection for function '" + spec.name() + "': " + ids
                            + ". Set nanofaas.deployment.default-backend or specify a backend hint.");
        }
        return candidates.get(0);
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :control-plane:test --tests '*DeploymentProviderResolverTest' -PcontrolPlaneModules=none`
Expected: PASS (all 7 tests).

- [ ] **Step 6: Commit**

```bash
git add control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/deployment/DeploymentProviderResolver.java \
      control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/deployment/DeploymentProperties.java \
      control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/deployment/DeploymentProviderResolverTest.java
git commit -m "feat: add DeploymentProviderResolver with deterministic selection"
```

---

### Task 3: Add fallback logic tests and implementation

**Files:**
- Create: `control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/deployment/ManagedDeploymentFallbackTest.java`
- Modify: `control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/deployment/DeploymentProviderResolver.java`

The resolver needs a `resolveWithFallback` method that handles degradation:
- Provider available → `DEPLOYMENT` (normal path)
- No provider but `endpointUrl` present → degrade to `POOL` with `degradationReason`
- No provider and no `endpointUrl` → reject
- Never degrade to `LOCAL`

- [ ] **Step 1: Write failing tests**

```java
package it.unimib.datai.nanofaas.controlplane.deployment;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ManagedDeploymentFallbackTest {

    private static FunctionSpec specWithEndpoint(String name, String url) {
        return new FunctionSpec(name, "img:latest", null, null, null,
                null, null, null, null, url, ExecutionMode.DEPLOYMENT, null, null, null);
    }

    @Test
    void providerAvailable_returnsProvisionResult() {
        ManagedDeploymentProvider provider = mock(ManagedDeploymentProvider.class);
        when(provider.backendId()).thenReturn("k8s");
        when(provider.isAvailable()).thenReturn(true);
        when(provider.supports(any())).thenReturn(true);
        when(provider.provision(any())).thenReturn(new ProvisionResult("http://svc:8080", "k8s"));

        var resolver = new DeploymentProviderResolver(List.of(provider), new DeploymentProperties(null));
        FunctionSpec spec = specWithEndpoint("fn", null);

        ProvisionResult result = resolver.resolveAndProvision(spec, null);
        assertThat(result.effectiveExecutionMode()).isEqualTo(ExecutionMode.DEPLOYMENT);
        assertThat(result.endpointUrl()).isEqualTo("http://svc:8080");
        assertThat(result.backendId()).isEqualTo("k8s");
        assertThat(result.degradationReason()).isNull();
    }

    @Test
    void noProvider_endpointPresent_degradesToPool() {
        var resolver = new DeploymentProviderResolver(List.of(), new DeploymentProperties(null));
        FunctionSpec spec = specWithEndpoint("fn", "http://external:8080/invoke");

        ProvisionResult result = resolver.resolveAndProvision(spec, null);
        assertThat(result.effectiveExecutionMode()).isEqualTo(ExecutionMode.POOL);
        assertThat(result.endpointUrl()).isEqualTo("http://external:8080/invoke");
        assertThat(result.degradationReason()).contains("No managed deployment provider");
    }

    @Test
    void noProvider_noEndpoint_rejectsRegistration() {
        var resolver = new DeploymentProviderResolver(List.of(), new DeploymentProperties(null));
        FunctionSpec spec = specWithEndpoint("fn", null);

        assertThatThrownBy(() -> resolver.resolveAndProvision(spec, null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void neverDegradesToLocal() {
        var resolver = new DeploymentProviderResolver(List.of(), new DeploymentProperties(null));
        FunctionSpec spec = specWithEndpoint("fn", "http://external:8080/invoke");

        ProvisionResult result = resolver.resolveAndProvision(spec, null);
        assertThat(result.effectiveExecutionMode()).isNotEqualTo(ExecutionMode.LOCAL);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :control-plane:test --tests '*ManagedDeploymentFallbackTest' -PcontrolPlaneModules=none`
Expected: FAIL — `resolveAndProvision` method does not exist.

- [ ] **Step 3: Add `resolveAndProvision` to `DeploymentProviderResolver`**

Add this method to the existing class:

```java
/**
 * Resolves a provider and provisions, with POOL degradation fallback.
 * Never degrades to LOCAL.
 */
public ProvisionResult resolveAndProvision(FunctionSpec spec, String backendHint) {
    try {
        ManagedDeploymentProvider provider = resolve(spec, backendHint);
        return provider.provision(spec);
    } catch (IllegalStateException e) {
        // Fallback: if endpoint already known, degrade to POOL
        if (spec.endpointUrl() != null && !spec.endpointUrl().isBlank()) {
            return new ProvisionResult(
                    spec.endpointUrl(),
                    null,
                    ExecutionMode.POOL,
                    "No managed deployment provider available; degraded to POOL using existing endpointUrl",
                    Map.of()
            );
        }
        throw e;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :control-plane:test --tests '*ManagedDeploymentFallbackTest' -PcontrolPlaneModules=none`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/deployment/DeploymentProviderResolver.java \
      control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/deployment/ManagedDeploymentFallbackTest.java
git commit -m "feat: add DEPLOYMENT->POOL fallback, reject DEPLOYMENT->LOCAL"
```

---

### Task 4: Create response DTOs for function API

**Files:**
- Create: `control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/api/FunctionResponse.java`
- Create: `control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/api/FunctionResponseContractTest.java`

- [ ] **Step 1: Write failing serialization contract test**

```java
package it.unimib.datai.nanofaas.controlplane.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FunctionResponseContractTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void serialization_includesAllMetadataFields() throws Exception {
        FunctionSpec spec = new FunctionSpec("fn", "img:latest", null, null, null,
                30000, 4, 100, 3, "http://svc:8080", ExecutionMode.DEPLOYMENT, null, null, null);

        FunctionResponse response = FunctionResponse.from(spec,
                ExecutionMode.DEPLOYMENT, ExecutionMode.DEPLOYMENT,
                "k8s", null, "http://svc:8080");

        String json = mapper.writeValueAsString(response);
        assertThat(json).contains("\"requestedExecutionMode\":\"DEPLOYMENT\"");
        assertThat(json).contains("\"effectiveExecutionMode\":\"DEPLOYMENT\"");
        assertThat(json).contains("\"deploymentBackend\":\"k8s\"");
        assertThat(json).contains("\"endpointUrl\":\"http://svc:8080\"");
        assertThat(json).doesNotContain("\"degradationReason\":");
    }

    @Test
    void serialization_degradedResponse_includesReason() throws Exception {
        FunctionSpec spec = new FunctionSpec("fn", "img:latest", null, null, null,
                30000, 4, 100, 3, "http://ext:8080", ExecutionMode.POOL, null, null, null);

        FunctionResponse response = FunctionResponse.from(spec,
                ExecutionMode.DEPLOYMENT, ExecutionMode.POOL,
                null, "No provider available", "http://ext:8080");

        String json = mapper.writeValueAsString(response);
        assertThat(json).contains("\"requestedExecutionMode\":\"DEPLOYMENT\"");
        assertThat(json).contains("\"effectiveExecutionMode\":\"POOL\"");
        assertThat(json).contains("\"degradationReason\":\"No provider available\"");
    }

    @Test
    void serialization_localMode_noBackendFields() throws Exception {
        FunctionSpec spec = new FunctionSpec("fn", "img:latest", null, null, null,
                30000, 4, 100, 3, null, ExecutionMode.LOCAL, null, null, null);

        FunctionResponse response = FunctionResponse.from(spec,
                ExecutionMode.LOCAL, ExecutionMode.LOCAL,
                null, null, null);

        String json = mapper.writeValueAsString(response);
        assertThat(json).contains("\"requestedExecutionMode\":\"LOCAL\"");
        assertThat(json).contains("\"effectiveExecutionMode\":\"LOCAL\"");
        assertThat(json).doesNotContain("\"deploymentBackend\":");
    }

    @Test
    void listSerialization_usesConsistentShape() throws Exception {
        FunctionSpec spec = new FunctionSpec("fn", "img:latest", null, null, null,
                30000, 4, 100, 3, null, ExecutionMode.POOL, null, null, null);

        FunctionResponse response = FunctionResponse.fromNonManaged(spec);

        String json = mapper.writeValueAsString(response);
        assertThat(json).contains("\"requestedExecutionMode\":\"POOL\"");
        assertThat(json).contains("\"effectiveExecutionMode\":\"POOL\"");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :control-plane:test --tests '*FunctionResponseContractTest' -PcontrolPlaneModules=none`
Expected: FAIL — `FunctionResponse` does not exist.

- [ ] **Step 3: Implement `FunctionResponse`**

```java
package it.unimib.datai.nanofaas.controlplane.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import it.unimib.datai.nanofaas.common.model.*;

import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record FunctionResponse(
        String name,
        String image,
        List<String> command,
        Map<String, String> env,
        ResourceSpec resources,
        Integer timeoutMs,
        Integer concurrency,
        Integer queueSize,
        Integer maxRetries,
        String endpointUrl,
        ExecutionMode requestedExecutionMode,
        ExecutionMode effectiveExecutionMode,
        String deploymentBackend,
        String degradationReason,
        RuntimeMode runtimeMode,
        String runtimeCommand,
        ScalingConfig scalingConfig,
        List<String> imagePullSecrets
) {
    public static FunctionResponse from(FunctionSpec spec,
                                         ExecutionMode requested,
                                         ExecutionMode effective,
                                         String backend,
                                         String degradationReason,
                                         String endpointUrl) {
        return new FunctionResponse(
                spec.name(), spec.image(), spec.command(), spec.env(),
                spec.resources(), spec.timeoutMs(), spec.concurrency(),
                spec.queueSize(), spec.maxRetries(),
                endpointUrl != null ? endpointUrl : spec.endpointUrl(),
                requested, effective, backend, degradationReason,
                spec.runtimeMode(), spec.runtimeCommand(),
                spec.scalingConfig(), spec.imagePullSecrets()
        );
    }

    public static FunctionResponse fromNonManaged(FunctionSpec spec) {
        return from(spec,
                spec.executionMode(), spec.executionMode(),
                null, null, spec.endpointUrl());
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :control-plane:test --tests '*FunctionResponseContractTest' -PcontrolPlaneModules=none`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/api/FunctionResponse.java \
      control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/api/FunctionResponseContractTest.java
git commit -m "feat: add FunctionResponse DTO with deployment metadata"
```

---

### Task 5: Add effective-state metadata to function registry

The registry needs to store deployment metadata alongside `FunctionSpec`. Rather than modifying `FunctionSpec` (which is also the request DTO), create a `RegisteredFunction` wrapper that holds both the spec and its deployment context.

**Files:**
- Create: `control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/registry/RegisteredFunction.java`
- Create: `control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/registry/DeploymentMetadata.java`
- Modify: `control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/registry/FunctionRegistry.java`

- [ ] **Step 1: Create `DeploymentMetadata`**

```java
package it.unimib.datai.nanofaas.controlplane.registry;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;

public record DeploymentMetadata(
        ExecutionMode requestedExecutionMode,
        ExecutionMode effectiveExecutionMode,
        String deploymentBackend,
        String degradationReason,
        String effectiveEndpointUrl
) {
    /** For non-managed modes (LOCAL, POOL) where requested == effective. */
    public static DeploymentMetadata nonManaged(ExecutionMode mode, String endpointUrl) {
        return new DeploymentMetadata(mode, mode, null, null, endpointUrl);
    }
}
```

- [ ] **Step 2: Create `RegisteredFunction`**

```java
package it.unimib.datai.nanofaas.controlplane.registry;

import it.unimib.datai.nanofaas.common.model.FunctionSpec;

public record RegisteredFunction(
        FunctionSpec spec,
        DeploymentMetadata deploymentMetadata
) {
    public String name() {
        return spec.name();
    }
}
```

- [ ] **Step 3: Read `FunctionRegistry.java` and update it to store `RegisteredFunction`**

Add an overloaded `put(RegisteredFunction)` and `getRegistered(String)` method alongside the existing `put(FunctionSpec)`/`get(String)` to allow incremental migration. The existing methods continue to work by wrapping with `DeploymentMetadata.nonManaged()`.

```java
// Add to FunctionRegistry alongside existing methods:

public void put(RegisteredFunction registered) {
    functions.put(registered.name(), registered.spec());
    metadata.put(registered.name(), registered.deploymentMetadata());
}

public Optional<RegisteredFunction> getRegistered(String name) {
    FunctionSpec spec = functions.get(name);
    if (spec == null) return Optional.empty();
    DeploymentMetadata meta = metadata.getOrDefault(name,
            DeploymentMetadata.nonManaged(spec.executionMode(), spec.endpointUrl()));
    return Optional.of(new RegisteredFunction(spec, meta));
}

// Add field:
private final ConcurrentHashMap<String, DeploymentMetadata> metadata = new ConcurrentHashMap<>();

// Update remove to also clean metadata:
// In the existing remove method, add: metadata.remove(name);
```

- [ ] **Step 4: Run full test suite to verify no regressions**

Run: `./gradlew :control-plane:test -PcontrolPlaneModules=none`
Expected: PASS — existing tests still use `FunctionSpec` paths which remain unchanged.

- [ ] **Step 5: Commit**

```bash
git add control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/registry/RegisteredFunction.java \
      control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/registry/DeploymentMetadata.java \
      control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/registry/FunctionRegistry.java
git commit -m "feat: add RegisteredFunction with DeploymentMetadata to registry"
```

---

### Task 6: Register `DeploymentProperties` in core config

**Files:**
- Modify: `control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/config/CoreConfig.java` (or equivalent config class)
- Modify: `control-plane/src/main/resources/application.yml`

- [ ] **Step 1: Enable `DeploymentProperties` via `@EnableConfigurationProperties`**

Add `DeploymentProperties.class` to the existing `@EnableConfigurationProperties` annotation in the core configuration class.

- [ ] **Step 2: Add default config to `application.yml`**

```yaml
nanofaas:
  deployment:
    default-backend:   # unset = auto-detect single provider
```

- [ ] **Step 3: Wire `DeploymentProviderResolver` with `@Autowired(required = false)` for providers list**

Update `DeploymentProviderResolver` constructor so `providers` defaults to empty list when no `ManagedDeploymentProvider` beans exist:

```java
public DeploymentProviderResolver(
        @Autowired(required = false) List<ManagedDeploymentProvider> providers,
        DeploymentProperties properties) {
    this.providers = providers != null ? providers : List.of();
    this.properties = properties;
}
```

- [ ] **Step 4: Run core-only test to verify startup**

Run: `./gradlew :control-plane:test --tests '*CoreOnlyApiTest' -PcontrolPlaneModules=none`
Expected: PASS — no providers, resolver initializes with empty list.

- [ ] **Step 5: Commit**

```bash
git add control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/config/ \
      control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/deployment/DeploymentProviderResolver.java \
      control-plane/src/main/resources/application.yml
git commit -m "feat: wire DeploymentProperties and resolver in core config"
```

---

### Phase 1 Gate Checklist

- [ ] `./gradlew :control-plane:test -PcontrolPlaneModules=none` — PASS
- [ ] `./gradlew :control-plane:test -PcontrolPlaneModules=all` — PASS
- [ ] New types: `ManagedDeploymentProvider`, `ProvisionResult`, `DeploymentProviderResolver`, `DeploymentProperties`, `FunctionResponse`, `RegisteredFunction`, `DeploymentMetadata` all compile and are unit-tested
- [ ] No existing behavior changed — `FunctionService` still uses `KubernetesResourceManager` directly
- [ ] Fallback rules tested: `DEPLOYMENT→POOL` (with endpoint), reject (without endpoint), never `DEPLOYMENT→LOCAL`

---

## Phase 2: Extract the Kubernetes Provider

**Gate:** All Kubernetes-specific deployment code moves out of `:control-plane` into a new module. `FunctionService` uses the provider SPI instead of `KubernetesResourceManager` directly. Core-only builds start without Kubernetes beans. All existing K8s behavior preserved.

---

### Task 7: Create `k8s-deployment-provider` module skeleton

**Files:**
- Create: `control-plane-modules/k8s-deployment-provider/build.gradle`
- Create: `control-plane-modules/k8s-deployment-provider/src/main/java/it/unimib/datai/nanofaas/modules/k8s/KubernetesDeploymentProviderModule.java`
- Create: `control-plane-modules/k8s-deployment-provider/src/main/resources/META-INF/services/it.unimib.datai.nanofaas.common.controlplane.ControlPlaneModule`

- [ ] **Step 1: Create `build.gradle`**

```groovy
dependencies {
    implementation project(':common')
    implementation project(':control-plane')
    implementation 'org.springframework.boot:spring-boot-starter'
    implementation 'io.fabric8:kubernetes-client:7.5.2'
    implementation 'io.fabric8:kubernetes-httpclient-vertx:7.5.2'
    testImplementation 'io.fabric8:kubernetes-server-mock:7.5.2'
}
```

- [ ] **Step 2: Create the module SPI entry**

```java
package it.unimib.datai.nanofaas.modules.k8s;

import it.unimib.datai.nanofaas.common.controlplane.ControlPlaneModule;
import java.util.Set;

public final class KubernetesDeploymentProviderModule implements ControlPlaneModule {
    @Override
    public Set<Class<?>> configurationClasses() {
        return Set.of(KubernetesDeploymentProviderConfiguration.class);
    }
}
```

ServiceLoader file content: `it.unimib.datai.nanofaas.modules.k8s.KubernetesDeploymentProviderModule`

- [ ] **Step 3: Create placeholder `KubernetesDeploymentProviderConfiguration`**

```java
package it.unimib.datai.nanofaas.modules.k8s;

import org.springframework.context.annotation.Configuration;

@Configuration
public class KubernetesDeploymentProviderConfiguration {
    // Will be populated in the next task
}
```

- [ ] **Step 4: Verify module discovery**

Run: `./gradlew :control-plane:test --tests '*ControlPlaneApplicationModulesTest' -PcontrolPlaneModules=all`
Expected: PASS — module selector discovers new module.

- [ ] **Step 5: Commit**

```bash
git add control-plane-modules/k8s-deployment-provider/
git commit -m "feat: create k8s-deployment-provider module skeleton"
```

---

### Task 8: Move Kubernetes classes into k8s module and implement provider

**Files:**
- Move: `control-plane/.../dispatch/KubernetesResourceManager.java` → `control-plane-modules/k8s-deployment-provider/.../k8s/KubernetesResourceManager.java`
- Move: `control-plane/.../dispatch/KubernetesDeploymentBuilder.java` → `control-plane-modules/k8s-deployment-provider/.../k8s/KubernetesDeploymentBuilder.java`
- Move: `control-plane/.../dispatch/KubernetesMetricsTranslator.java` → `control-plane-modules/k8s-deployment-provider/.../k8s/KubernetesMetricsTranslator.java`
- Move: `control-plane/.../dispatch/NanofaasDeploymentConstants.java` → `control-plane-modules/k8s-deployment-provider/.../k8s/NanofaasDeploymentConstants.java`
- Move: `control-plane/.../config/KubernetesClientConfig.java` → `control-plane-modules/k8s-deployment-provider/.../k8s/KubernetesClientConfig.java`
- Move: `control-plane/.../config/KubernetesProperties.java` → `control-plane-modules/k8s-deployment-provider/.../k8s/KubernetesProperties.java`
- Move: `control-plane/.../config/VertxRuntimeHints.java` → `control-plane-modules/k8s-deployment-provider/.../k8s/VertxRuntimeHints.java`
- Create: `control-plane-modules/k8s-deployment-provider/.../k8s/KubernetesDeploymentProvider.java`
- Modify: `control-plane-modules/k8s-deployment-provider/.../k8s/KubernetesDeploymentProviderConfiguration.java`
- Modify: `control-plane/build.gradle` — remove Fabric8 dependencies from always-on core

This is a large mechanical step. The key structural change:

- [ ] **Step 1: Create `KubernetesDeploymentProvider` that adapts `KubernetesResourceManager` to the SPI**

```java
package it.unimib.datai.nanofaas.modules.k8s;

import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentProvider;
import it.unimib.datai.nanofaas.controlplane.deployment.ProvisionResult;

public class KubernetesDeploymentProvider implements ManagedDeploymentProvider {
    private final KubernetesResourceManager resourceManager;

    public KubernetesDeploymentProvider(KubernetesResourceManager resourceManager) {
        this.resourceManager = resourceManager;
    }

    @Override
    public String backendId() {
        return "k8s";
    }

    @Override
    public boolean isAvailable() {
        return true; // if the module is loaded, K8s client is configured
    }

    @Override
    public boolean supports(FunctionSpec spec) {
        return true; // K8s supports all function specs
    }

    @Override
    public ProvisionResult provision(FunctionSpec spec) {
        String serviceUrl = resourceManager.provision(spec);
        return new ProvisionResult(serviceUrl, "k8s");
    }

    @Override
    public void deprovision(String functionName) {
        resourceManager.deprovision(functionName);
    }

    @Override
    public void setReplicas(String functionName, int replicas) {
        resourceManager.setReplicas(functionName, replicas);
    }

    @Override
    public int getReadyReplicas(String functionName) {
        return resourceManager.getReadyReplicas(functionName);
    }
}
```

- [ ] **Step 2: Move all Kubernetes classes to the new module package**

Move each file from `it.unimib.datai.nanofaas.controlplane.dispatch` / `...config` to `it.unimib.datai.nanofaas.modules.k8s`, updating package declarations and imports.

- [ ] **Step 3: Update `KubernetesDeploymentProviderConfiguration` to register all beans**

```java
package it.unimib.datai.nanofaas.modules.k8s;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;

@Configuration
@EnableConfigurationProperties(KubernetesProperties.class)
@ImportRuntimeHints(VertxRuntimeHints.class)
public class KubernetesDeploymentProviderConfiguration {

    @Bean
    KubernetesClientConfig kubernetesClientConfig() {
        return new KubernetesClientConfig();
    }

    @Bean
    KubernetesResourceManager kubernetesResourceManager(
            org.springframework.beans.factory.ObjectProvider<io.fabric8.kubernetes.client.KubernetesClient> clientProvider,
            KubernetesProperties properties) {
        return new KubernetesResourceManager(clientProvider, properties);
    }

    @Bean
    KubernetesDeploymentProvider kubernetesDeploymentProvider(KubernetesResourceManager resourceManager) {
        return new KubernetesDeploymentProvider(resourceManager);
    }
}
```

- [ ] **Step 4: Remove Fabric8 from `:control-plane` `build.gradle` core dependencies**

Remove these lines from `control-plane/build.gradle`:
```groovy
// REMOVE:
implementation 'io.fabric8:kubernetes-client:7.5.2'
implementation 'io.fabric8:kubernetes-httpclient-vertx:7.5.2'
testImplementation 'io.fabric8:kubernetes-server-mock:7.5.2'
```

The `k8s-deployment-provider` module's `build.gradle` already has them.

- [ ] **Step 5: Remove moved source files from `:control-plane`**

Delete the following files from `control-plane/src/main/java/`:
- `dispatch/KubernetesResourceManager.java`
- `dispatch/KubernetesDeploymentBuilder.java`
- `dispatch/KubernetesMetricsTranslator.java`
- `dispatch/NanofaasDeploymentConstants.java`
- `config/KubernetesClientConfig.java`
- `config/KubernetesProperties.java`
- `config/VertxRuntimeHints.java`

- [ ] **Step 6: Verify compilation**

Run: `./gradlew :control-plane:compileJava :control-plane-modules:k8s-deployment-provider:compileJava -PcontrolPlaneModules=all`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "refactor: move Kubernetes deployment code to k8s-deployment-provider module"
```

---

### Task 9: Rewire `FunctionService` to use `DeploymentProviderResolver`

**Files:**
- Modify: `control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/registry/FunctionService.java`
- Modify: `control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/registry/FunctionServiceTest.java`
- Create: `control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/registry/FunctionServiceManagedDeploymentTest.java`

- [ ] **Step 1: Update `FunctionService` constructor**

Replace `KubernetesResourceManager` with `DeploymentProviderResolver`:

```java
@Service
public class FunctionService {
    private final FunctionRegistry registry;
    private final FunctionSpecResolver resolver;
    private final DeploymentProviderResolver providerResolver;
    private final ImageValidator imageValidator;
    private final List<FunctionRegistrationListener> listeners;
    // ... locks unchanged

    @Autowired
    public FunctionService(FunctionRegistry registry,
                           FunctionDefaults defaults,
                           DeploymentProviderResolver providerResolver,
                           ImageValidator imageValidator,
                           @Autowired(required = false) List<FunctionRegistrationListener> listeners) {
        this.registry = registry;
        this.resolver = new FunctionSpecResolver(defaults);
        this.providerResolver = providerResolver;
        this.imageValidator = imageValidator;
        this.listeners = listeners == null ? List.of() : listeners;
    }
```

- [ ] **Step 2: Update `register()` to use provider resolver**

```java
public Optional<RegisteredFunction> register(FunctionSpec spec) {
    FunctionSpec initialResolved = resolver.resolve(spec);

    return withFunctionLock(initialResolved.name(), () -> {
        if (registry.get(initialResolved.name()).isPresent()) {
            return Optional.empty();
        }

        try {
            imageValidator.validate(initialResolved);
        } catch (RuntimeException e) {
            throw e;
        }

        DeploymentMetadata metadata;
        FunctionSpec resolved = initialResolved;

        if (initialResolved.executionMode() == ExecutionMode.DEPLOYMENT) {
            ProvisionResult result = providerResolver.resolveAndProvision(initialResolved, null);
            resolved = withEndpointUrl(initialResolved, result.endpointUrl());
            metadata = new DeploymentMetadata(
                    ExecutionMode.DEPLOYMENT,
                    result.effectiveExecutionMode(),
                    result.backendId(),
                    result.degradationReason(),
                    result.endpointUrl()
            );
        } else {
            metadata = DeploymentMetadata.nonManaged(
                    initialResolved.executionMode(), initialResolved.endpointUrl());
        }

        RegisteredFunction registered = new RegisteredFunction(resolved, metadata);
        try {
            notifyRegisterListeners(resolved);
            registry.put(registered);
            return Optional.of(registered);
        } catch (RuntimeException e) {
            rollbackProvisionedRegistration(registered, e);
            throw e;
        }
    });
}
```

- [ ] **Step 3: Update `remove()` and `setReplicas()` to use provider from metadata**

```java
public Optional<FunctionSpec> remove(String name) {
    return withFunctionLock(name, () -> {
        RegisteredFunction existing = registry.getRegistered(name).orElse(null);
        if (existing == null) return Optional.empty();

        registry.remove(name);
        List<FunctionRegistrationListener> notified = new ArrayList<>();
        try {
            for (FunctionRegistrationListener listener : listeners) {
                listener.onRemove(name);
                notified.add(listener);
            }
            if (existing.deploymentMetadata().deploymentBackend() != null) {
                ManagedDeploymentProvider provider = providerResolver.resolve(
                        existing.spec(), existing.deploymentMetadata().deploymentBackend());
                provider.deprovision(name);
            }
        } catch (RuntimeException e) {
            rollbackRemovalListeners(existing.spec(), notified, e);
            registry.put(existing);
            throw e;
        }
        return Optional.of(existing.spec());
    });
}

public Optional<Integer> setReplicas(String name, int replicas) {
    return withFunctionLock(name, () -> {
        RegisteredFunction registered = registry.getRegistered(name).orElse(null);
        if (registered == null) return Optional.empty();

        if (registered.deploymentMetadata().effectiveExecutionMode() != ExecutionMode.DEPLOYMENT) {
            throw new IllegalArgumentException("Function '" + name + "' is not in DEPLOYMENT mode");
        }
        String backend = registered.deploymentMetadata().deploymentBackend();
        if (backend == null) {
            throw new IllegalStateException("No deployment backend for function '" + name + "'");
        }
        ManagedDeploymentProvider provider = providerResolver.resolve(registered.spec(), backend);
        provider.setReplicas(name, replicas);
        return Optional.of(replicas);
    });
}
```

- [ ] **Step 4: Update `FunctionServiceTest` to mock `DeploymentProviderResolver` instead of `KubernetesResourceManager`**

Replace `resourceManager` mock with:
```java
private DeploymentProviderResolver providerResolver;
private ManagedDeploymentProvider provider;

@BeforeEach
void setUp() {
    registry = new FunctionRegistry();
    defaults = new FunctionDefaults(30000, 4, 100, 3);
    provider = mock(ManagedDeploymentProvider.class);
    when(provider.backendId()).thenReturn("k8s");
    when(provider.isAvailable()).thenReturn(true);
    when(provider.supports(any())).thenReturn(true);
    when(provider.provision(any())).thenReturn(new ProvisionResult("http://fn-svc:8080", "k8s"));
    providerResolver = new DeploymentProviderResolver(List.of(provider), new DeploymentProperties(null));
    imageValidator = mock(ImageValidator.class);
    listener = mock(FunctionRegistrationListener.class);
    service = new FunctionService(registry, defaults, providerResolver, imageValidator, List.of(listener));
}
```

Update assertions to work with `RegisteredFunction` return type.

- [ ] **Step 5: Write `FunctionServiceManagedDeploymentTest`**

Test cases:
1. Backend metadata persisted at registration
2. Correct provider used for deprovision (matches the one that provisioned)
3. Rollback deprovisions the same provider that provisioned
4. Remove path uses effective provider metadata

- [ ] **Step 6: Run tests**

Run: `./gradlew :control-plane:test -PcontrolPlaneModules=none`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/registry/FunctionService.java \
      control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/registry/FunctionServiceTest.java \
      control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/registry/FunctionServiceManagedDeploymentTest.java
git commit -m "refactor: FunctionService uses DeploymentProviderResolver instead of KubernetesResourceManager"
```

---

### Task 10: Update `FunctionController` to return `FunctionResponse`

**Files:**
- Modify: `control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/api/FunctionController.java`
- Modify: `control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/api/FunctionControllerTest.java`

- [ ] **Step 1: Update controller to return `FunctionResponse`**

```java
@GetMapping
public Collection<FunctionResponse> list() {
    return functionService.list().stream()
            .map(FunctionResponse::fromNonManaged)
            .toList();
}

@PostMapping
public ResponseEntity<FunctionResponse> register(@Valid @RequestBody FunctionSpec spec) {
    return functionService.register(spec)
            .map(registered -> {
                DeploymentMetadata meta = registered.deploymentMetadata();
                FunctionResponse response = FunctionResponse.from(
                        registered.spec(),
                        meta.requestedExecutionMode(),
                        meta.effectiveExecutionMode(),
                        meta.deploymentBackend(),
                        meta.degradationReason(),
                        meta.effectiveEndpointUrl());
                return ResponseEntity.status(HttpStatus.CREATED).body(response);
            })
            .orElse(ResponseEntity.status(HttpStatus.CONFLICT).build());
}

@GetMapping("/{name}")
public ResponseEntity<FunctionResponse> get(
        @PathVariable @NotBlank(message = "Function name is required") String name) {
    return functionService.getRegistered(name)
            .map(registered -> {
                DeploymentMetadata meta = registered.deploymentMetadata();
                return ResponseEntity.ok(FunctionResponse.from(
                        registered.spec(),
                        meta.requestedExecutionMode(),
                        meta.effectiveExecutionMode(),
                        meta.deploymentBackend(),
                        meta.degradationReason(),
                        meta.effectiveEndpointUrl()));
            })
            .orElse(ResponseEntity.notFound().build());
}
```

Note: Also expose `getRegistered()` and update `list()` in `FunctionService` to return `RegisteredFunction` so the controller can produce correct metadata for list too.

- [ ] **Step 2: Update `FunctionControllerTest` assertions for new response shape**

- [ ] **Step 3: Run tests**

Run: `./gradlew :control-plane:test -PcontrolPlaneModules=none`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/api/FunctionController.java \
      control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/registry/FunctionService.java \
      control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/api/FunctionControllerTest.java
git commit -m "feat: FunctionController returns FunctionResponse with deployment metadata"
```

---

### Task 11: Rewire `InternalScaler` to use provider SPI

**Files:**
- Modify: `control-plane-modules/autoscaler/src/main/java/it/unimib/datai/nanofaas/modules/autoscaler/InternalScaler.java`
- Modify: `control-plane-modules/autoscaler/src/main/java/it/unimib/datai/nanofaas/modules/autoscaler/AutoscalerConfiguration.java`
- Modify: `control-plane-modules/autoscaler/src/test/java/it/unimib/datai/nanofaas/modules/autoscaler/InternalScalerTest.java`
- Modify: `control-plane-modules/autoscaler/src/test/java/it/unimib/datai/nanofaas/modules/autoscaler/InternalScalerBranchTest.java`
- Modify: `control-plane-modules/autoscaler/src/test/java/it/unimib/datai/nanofaas/modules/autoscaler/InternalScalerResilienceTest.java`

- [ ] **Step 1: Replace `KubernetesResourceManager` with `DeploymentProviderResolver` in `InternalScaler`**

Change the constructor parameter from `KubernetesResourceManager resourceManager` to `DeploymentProviderResolver providerResolver`. The scaling loop should look up the provider for each function via `providerResolver.resolve(spec, metadata.deploymentBackend())`.

- [ ] **Step 2: Update `AutoscalerConfiguration` bean wiring**

Replace `KubernetesResourceManager` injection with `DeploymentProviderResolver`.

- [ ] **Step 3: Update all three autoscaler test classes**

Replace `KubernetesResourceManager` mocks with `DeploymentProviderResolver` + `ManagedDeploymentProvider` mocks.

- [ ] **Step 4: Run autoscaler tests**

Run: `./gradlew :control-plane-modules:autoscaler:test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add control-plane-modules/autoscaler/
git commit -m "refactor: InternalScaler uses provider SPI instead of KubernetesResourceManager"
```

---

### Task 12: Move K8s-specific tests to k8s module

**Files:**
- Move: `control-plane/src/test/.../dispatch/KubernetesResourceManagerTest.java` → `control-plane-modules/k8s-deployment-provider/src/test/.../k8s/KubernetesResourceManagerTest.java`
- Move: `control-plane/src/test/.../dispatch/KubernetesDeploymentBuilderTest.java` → `control-plane-modules/k8s-deployment-provider/src/test/.../k8s/KubernetesDeploymentBuilderTest.java`
- Move: `control-plane/src/test/.../dispatch/KubernetesMetricsTranslatorTest.java` → `control-plane-modules/k8s-deployment-provider/src/test/.../k8s/KubernetesMetricsTranslatorTest.java`
- Move: `control-plane/src/test/.../dispatch/MockK8sDeploymentReplicaSetFlowTest.java` → `control-plane-modules/k8s-deployment-provider/src/test/.../k8s/MockK8sDeploymentReplicaSetFlowTest.java`
- Move: `control-plane/src/test/.../config/KubernetesClientConfigTest.java` → `control-plane-modules/k8s-deployment-provider/src/test/.../k8s/KubernetesClientConfigTest.java`
- Move: `control-plane/src/test/.../config/VertxRuntimeHintsTest.java` → `control-plane-modules/k8s-deployment-provider/src/test/.../k8s/VertxRuntimeHintsTest.java`
- Move: `control-plane/src/test/.../config/VertxRuntimeHintsBranchTest.java` → `control-plane-modules/k8s-deployment-provider/src/test/.../k8s/VertxRuntimeHintsBranchTest.java`
- Create: `control-plane-modules/k8s-deployment-provider/src/test/.../k8s/KubernetesDeploymentProviderTest.java`
- Create: `control-plane-modules/k8s-deployment-provider/src/test/.../k8s/KubernetesDeploymentProviderConfigurationTest.java`

- [ ] **Step 1: Move test files, update package declarations and imports**

- [ ] **Step 2: Write `KubernetesDeploymentProviderTest` — verifies SPI adapter delegates correctly**

```java
package it.unimib.datai.nanofaas.modules.k8s;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.controlplane.deployment.ProvisionResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class KubernetesDeploymentProviderTest {

    @Test
    void provision_delegatesAndReturnsResult() {
        KubernetesResourceManager rm = mock(KubernetesResourceManager.class);
        when(rm.provision(any())).thenReturn("http://fn-svc.ns.svc.cluster.local:8080/invoke");

        var provider = new KubernetesDeploymentProvider(rm);
        FunctionSpec spec = new FunctionSpec("fn", "img", null, null, null,
                null, null, null, null, null, ExecutionMode.DEPLOYMENT, null, null, null);

        ProvisionResult result = provider.provision(spec);
        assertThat(result.endpointUrl()).isEqualTo("http://fn-svc.ns.svc.cluster.local:8080/invoke");
        assertThat(result.backendId()).isEqualTo("k8s");
        assertThat(result.effectiveExecutionMode()).isEqualTo(ExecutionMode.DEPLOYMENT);
    }

    @Test
    void deprovision_delegates() {
        KubernetesResourceManager rm = mock(KubernetesResourceManager.class);
        var provider = new KubernetesDeploymentProvider(rm);
        provider.deprovision("fn");
        verify(rm).deprovision("fn");
    }

    @Test
    void setReplicas_delegates() {
        KubernetesResourceManager rm = mock(KubernetesResourceManager.class);
        var provider = new KubernetesDeploymentProvider(rm);
        provider.setReplicas("fn", 3);
        verify(rm).setReplicas("fn", 3);
    }

    @Test
    void getReadyReplicas_delegates() {
        KubernetesResourceManager rm = mock(KubernetesResourceManager.class);
        when(rm.getReadyReplicas("fn")).thenReturn(2);
        var provider = new KubernetesDeploymentProvider(rm);
        assertThat(provider.getReadyReplicas("fn")).isEqualTo(2);
    }

    @Test
    void backendId_isK8s() {
        var provider = new KubernetesDeploymentProvider(mock(KubernetesResourceManager.class));
        assertThat(provider.backendId()).isEqualTo("k8s");
    }
}
```

- [ ] **Step 3: Write `KubernetesDeploymentProviderConfigurationTest`**

Test that module wiring creates the expected beans and that beans are absent in core-only profile.

- [ ] **Step 4: Run all tests**

```bash
./gradlew :control-plane-modules:k8s-deployment-provider:test
./gradlew :control-plane:test -PcontrolPlaneModules=none
./gradlew :control-plane:test -PcontrolPlaneModules=all
```
Expected: PASS on all three.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor: move K8s tests to k8s-deployment-provider, add provider SPI tests"
```

---

### Task 13: Update `image-validator` module for provider alignment

**Files:**
- Modify: `control-plane-modules/image-validator/src/main/java/it/unimib/datai/nanofaas/modules/imagevalidator/ImageValidatorConfiguration.java`

The `ImageValidatorConfiguration` currently uses `@ConditionalOnBean(KubernetesProperties.class)`. Since `KubernetesProperties` has moved to the k8s module, the condition needs updating.

- [ ] **Step 1: Change the conditional to check for `KubernetesClient` bean presence** (or depend on k8s module)

If image-validator should be K8s-specific, make it depend on the k8s-deployment-provider module and update the import. If it should remain independent, change the condition to `@ConditionalOnBean(KubernetesClient.class)`.

Decision: image-validator is inherently K8s-specific (creates pods). Add `implementation project(':control-plane-modules:k8s-deployment-provider')` to its build.gradle and update the import.

- [ ] **Step 2: Update image-validator tests**

- [ ] **Step 3: Run tests**

Run: `./gradlew :control-plane-modules:image-validator:test`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add control-plane-modules/image-validator/
git commit -m "refactor: align image-validator with k8s-deployment-provider module"
```

---

### Task 14: Core-only startup and regression verification

**Files:**
- Modify: `control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/CoreOnlyApiTest.java`
- Modify: `control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/ControlPlaneApplicationModulesTest.java`

- [ ] **Step 1: Add test to `CoreOnlyApiTest` — `DEPLOYMENT` registration without providers returns error**

```java
@Test
void deploymentRegistration_withNoProvider_returns503OrRejectsCleanly() {
    webTestClient.post()
            .uri("/v1/functions")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""
                {"name":"fn-dep","image":"img:latest","executionMode":"DEPLOYMENT"}
                """)
            .exchange()
            .expectStatus().is5xxServerError();
}
```

- [ ] **Step 2: Extend `ControlPlaneApplicationModulesTest` to discover k8s-deployment-provider module**

- [ ] **Step 3: Run gate checks**

```bash
./gradlew :control-plane:test -PcontrolPlaneModules=none
./gradlew :control-plane:test -PcontrolPlaneModules=all
./gradlew test -PcontrolPlaneModules=all
```
Expected: PASS on all.

- [ ] **Step 4: Commit**

```bash
git add control-plane/src/test/
git commit -m "test: verify core-only startup and DEPLOYMENT rejection without providers"
```

---

### Task 15: Update `openapi.yaml`

**Files:**
- Modify: `openapi.yaml`

- [ ] **Step 1: Add `FunctionResponse` schema with new fields**

Add to components/schemas:
```yaml
FunctionResponse:
  type: object
  properties:
    name: { type: string }
    image: { type: string }
    # ... all existing FunctionSpec fields ...
    requestedExecutionMode:
      type: string
      enum: [LOCAL, POOL, DEPLOYMENT]
    effectiveExecutionMode:
      type: string
      enum: [LOCAL, POOL, DEPLOYMENT]
    deploymentBackend:
      type: string
      description: "Provider backend id (e.g. 'k8s', 'container-local'). Null for non-managed modes."
    degradationReason:
      type: string
      description: "Reason for mode degradation (e.g. DEPLOYMENT->POOL). Null when no degradation."
    endpointUrl:
      type: string
      description: "Effective endpoint URL used for invocation."
```

- [ ] **Step 2: Update response references for GET/POST/list endpoints**

Change `$ref: '#/components/schemas/FunctionSpec'` to `$ref: '#/components/schemas/FunctionResponse'` in response bodies. Keep `FunctionSpec` as the request schema.

- [ ] **Step 3: Commit**

```bash
git add openapi.yaml
git commit -m "docs: update openapi.yaml with FunctionResponse and deployment metadata"
```

---

### Task 16: Update module combination smoke tests

**Files:**
- Modify: `scripts/test-control-plane-module-combinations.sh`

- [ ] **Step 1: Add new module combinations**

Add test combinations:
- `k8s-deployment-provider` alone
- `k8s-deployment-provider,autoscaler`
- `k8s-deployment-provider,image-validator`
- All modules

- [ ] **Step 2: Run the script**

Run: `./scripts/test-control-plane-module-combinations.sh --task :control-plane:bootJar`
Expected: All combinations pass.

- [ ] **Step 3: Commit**

```bash
git add scripts/test-control-plane-module-combinations.sh
git commit -m "test: add k8s-deployment-provider to module combination smoke tests"
```

---

### Phase 2 Gate Checklist

- [ ] `./gradlew :control-plane:test -PcontrolPlaneModules=none` — PASS (core has no Fabric8 dependency)
- [ ] `./gradlew :control-plane:test -PcontrolPlaneModules=k8s-deployment-provider` — PASS
- [ ] `./gradlew :control-plane:test -PcontrolPlaneModules=all` — PASS
- [ ] `./gradlew test -PcontrolPlaneModules=all` — PASS
- [ ] `FunctionService` no longer imports any `io.fabric8` or `KubernetesResourceManager` class
- [ ] `InternalScaler` no longer imports `KubernetesResourceManager`
- [ ] `control-plane/build.gradle` has no Fabric8 dependency
- [ ] `CoreOnlyApiTest` proves core starts without K8s beans
- [ ] `openapi.yaml` documents `FunctionResponse` with deployment metadata
- [ ] `./scripts/test-control-plane-module-combinations.sh` includes new module

---

## Phase 3: Add the `container-local` Provider

**Gate:** A `container-local` module exists that provisions warm containers on a single node, with a runtime adapter boundary that doesn't assume Docker-specific semantics. Integration tests prove end-to-end provisioning and invocation.

---

### Task 17: Create `container-deployment-provider` module skeleton

**Files:**
- Create: `control-plane-modules/container-deployment-provider/build.gradle`
- Create: `control-plane-modules/container-deployment-provider/src/main/java/it/unimib/datai/nanofaas/modules/container/ContainerDeploymentProviderModule.java`
- Create: `control-plane-modules/container-deployment-provider/src/main/resources/META-INF/services/it.unimib.datai.nanofaas.common.controlplane.ControlPlaneModule`
- Create: `control-plane-modules/container-deployment-provider/src/main/java/it/unimib/datai/nanofaas/modules/container/ContainerDeploymentProviderConfiguration.java`
- Create: `control-plane-modules/container-deployment-provider/src/main/java/it/unimib/datai/nanofaas/modules/container/ContainerLocalProperties.java`

- [ ] **Step 1: Create `build.gradle`**

```groovy
dependencies {
    implementation project(':common')
    implementation project(':control-plane')
    implementation 'org.springframework.boot:spring-boot-starter'
}
```

No Docker/containerd client library yet — the runtime adapter abstracts this.

- [ ] **Step 2: Create module SPI, configuration, and properties**

```java
// ContainerLocalProperties.java
@ConfigurationProperties(prefix = "nanofaas.container-local")
public record ContainerLocalProperties(
        String networkName,
        String runtimeAdapter,  // "docker" | "nerdctl" | "podman"
        int readinessTimeoutSeconds,
        int readinessPollIntervalMs
) {
    public ContainerLocalProperties() {
        this("nanofaas-fn", "docker", 30, 500);
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add control-plane-modules/container-deployment-provider/
git commit -m "feat: create container-deployment-provider module skeleton"
```

---

### Task 18: Define the runtime adapter boundary

**Files:**
- Create: `control-plane-modules/container-deployment-provider/src/main/java/it/unimib/datai/nanofaas/modules/container/runtime/ContainerRuntimeAdapter.java`
- Create: `control-plane-modules/container-deployment-provider/src/main/java/it/unimib/datai/nanofaas/modules/container/runtime/ContainerInfo.java`
- Create: `control-plane-modules/container-deployment-provider/src/main/java/it/unimib/datai/nanofaas/modules/container/runtime/ContainerCreateRequest.java`

- [ ] **Step 1: Define the adapter interface**

```java
package it.unimib.datai.nanofaas.modules.container.runtime;

import java.util.List;
import java.util.Map;

public interface ContainerRuntimeAdapter {

    String createContainer(ContainerCreateRequest request);
    void startContainer(String containerId);
    void stopContainer(String containerId);
    void removeContainer(String containerId);
    ContainerInfo inspectContainer(String containerId);
    List<ContainerInfo> listContainers(Map<String, String> labels);
    void createNetwork(String networkName);
    void removeNetwork(String networkName);
    boolean networkExists(String networkName);
}
```

- [ ] **Step 2: Define `ContainerCreateRequest` and `ContainerInfo`**

```java
package it.unimib.datai.nanofaas.modules.container.runtime;

import java.util.List;
import java.util.Map;

public record ContainerCreateRequest(
        String name,
        String image,
        Map<String, String> env,
        Map<String, String> labels,
        String networkName,
        List<String> command,
        String memoryLimit,
        String cpuLimit
) {}

public record ContainerInfo(
        String id,
        String name,
        String ipAddress,
        String status,  // "running", "stopped", "created"
        Map<String, String> labels
) {
    public boolean isRunning() {
        return "running".equalsIgnoreCase(status);
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add control-plane-modules/container-deployment-provider/src/main/java/it/unimib/datai/nanofaas/modules/container/runtime/
git commit -m "feat: define ContainerRuntimeAdapter boundary for container-local provider"
```

---

### Task 19: Implement CLI-based runtime adapter

A process-based adapter that shells out to `docker`/`nerdctl`/`podman` CLI. This avoids coupling to a specific client library and works with any OCI-compatible runtime.

**Files:**
- Create: `control-plane-modules/container-deployment-provider/src/main/java/it/unimib/datai/nanofaas/modules/container/runtime/CliContainerRuntimeAdapter.java`
- Create: `control-plane-modules/container-deployment-provider/src/test/java/it/unimib/datai/nanofaas/modules/container/runtime/CliContainerRuntimeAdapterTest.java`

- [ ] **Step 1: Write unit tests with mocked process execution**

Test that `createContainer` builds the correct CLI command, `inspectContainer` parses JSON output, etc. Mock the process execution layer.

- [ ] **Step 2: Implement `CliContainerRuntimeAdapter`**

Uses `ProcessBuilder` to execute commands like:
- `docker create --name <name> --label <k>=<v> --network <net> --env <k>=<v> <image>`
- `docker start <id>`
- `docker inspect --format '{{json .}}' <id>`
- `docker network create <name>`
- `docker rm -f <id>`

The binary name comes from `ContainerLocalProperties.runtimeAdapter()`.

- [ ] **Step 3: Run tests**

Run: `./gradlew :control-plane-modules:container-deployment-provider:test`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add control-plane-modules/container-deployment-provider/
git commit -m "feat: implement CLI-based ContainerRuntimeAdapter (docker/nerdctl/podman)"
```

---

### Task 20: Implement `ContainerLocalDeploymentProvider`

**Files:**
- Create: `control-plane-modules/container-deployment-provider/src/main/java/it/unimib/datai/nanofaas/modules/container/ContainerLocalDeploymentProvider.java`
- Create: `control-plane-modules/container-deployment-provider/src/test/java/it/unimib/datai/nanofaas/modules/container/ContainerLocalDeploymentProviderTest.java`
- Create: `control-plane-modules/container-deployment-provider/src/test/java/it/unimib/datai/nanofaas/modules/container/ContainerLocalDeploymentProviderLifecycleTest.java`
- Create: `control-plane-modules/container-deployment-provider/src/test/java/it/unimib/datai/nanofaas/modules/container/ContainerLocalDeploymentProviderScaleTest.java`
- Create: `control-plane-modules/container-deployment-provider/src/test/java/it/unimib/datai/nanofaas/modules/container/ContainerLocalDeploymentProviderFailureTest.java`

- [ ] **Step 1: Write failing tests for `ContainerLocalDeploymentProviderTest`**

Test cases (all with mocked `ContainerRuntimeAdapter`):
1. `backendId()` returns `"container-local"`
2. `provision()` creates network if absent, creates and starts container, waits for readiness, returns endpoint
3. Deterministic naming: container named `nanofaas-fn-<functionName>-<index>`
4. Labels applied: `nanofaas.function=<name>`, `nanofaas.managed=true`
5. Endpoint derived as `http://<containerIp>:8080/invoke`

- [ ] **Step 2: Run tests to verify failure**

- [ ] **Step 3: Implement `ContainerLocalDeploymentProvider`**

```java
package it.unimib.datai.nanofaas.modules.container;

import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentProvider;
import it.unimib.datai.nanofaas.controlplane.deployment.ProvisionResult;
import it.unimib.datai.nanofaas.modules.container.runtime.*;

import java.util.List;
import java.util.Map;

public class ContainerLocalDeploymentProvider implements ManagedDeploymentProvider {

    private static final String LABEL_FUNCTION = "nanofaas.function";
    private static final String LABEL_MANAGED = "nanofaas.managed";
    private static final String CONTAINER_PREFIX = "nanofaas-fn-";

    private final ContainerRuntimeAdapter runtime;
    private final ContainerLocalProperties properties;

    // ... constructor ...

    @Override public String backendId() { return "container-local"; }
    @Override public boolean isAvailable() { return true; }
    @Override public boolean supports(FunctionSpec spec) { return true; }

    @Override
    public ProvisionResult provision(FunctionSpec spec) {
        ensureNetwork();
        String containerName = CONTAINER_PREFIX + spec.name() + "-0";
        Map<String, String> labels = Map.of(
                LABEL_FUNCTION, spec.name(),
                LABEL_MANAGED, "true");

        // Build env from spec + CALLBACK_URL if available
        Map<String, String> env = buildEnv(spec);

        ContainerCreateRequest request = new ContainerCreateRequest(
                containerName, spec.image(), env, labels,
                properties.networkName(), spec.command(),
                resourceLimit(spec, "memory"), resourceLimit(spec, "cpu"));

        String containerId = runtime.createContainer(request);
        runtime.startContainer(containerId);

        ContainerInfo info = waitForReady(containerId);
        String endpointUrl = "http://" + info.ipAddress() + ":8080/invoke";
        return new ProvisionResult(endpointUrl, "container-local");
    }

    @Override
    public void deprovision(String functionName) {
        List<ContainerInfo> containers = runtime.listContainers(
                Map.of(LABEL_FUNCTION, functionName, LABEL_MANAGED, "true"));
        for (ContainerInfo c : containers) {
            runtime.stopContainer(c.id());
            runtime.removeContainer(c.id());
        }
    }

    @Override
    public void setReplicas(String functionName, int replicas) {
        // Scale up: create new containers; scale down: remove excess
        List<ContainerInfo> existing = runtime.listContainers(
                Map.of(LABEL_FUNCTION, functionName, LABEL_MANAGED, "true"));
        // ... scaling logic ...
    }

    @Override
    public int getReadyReplicas(String functionName) {
        List<ContainerInfo> containers = runtime.listContainers(
                Map.of(LABEL_FUNCTION, functionName, LABEL_MANAGED, "true"));
        return (int) containers.stream().filter(ContainerInfo::isRunning).count();
    }
}
```

- [ ] **Step 4: Write lifecycle, scale, and failure tests**

`ContainerLocalDeploymentProviderLifecycleTest`:
- create, recreate (deprovision + provision), delete, cleanup of orphaned containers on startup

`ContainerLocalDeploymentProviderScaleTest`:
- scale up creates additional containers, scale down removes excess
- ready replica count matches running containers
- zero-replica behavior (all stopped or removed)

`ContainerLocalDeploymentProviderFailureTest`:
- image pull failure (runtime throws) → propagated clearly
- container start failure → cleanup + propagate
- readiness timeout → cleanup + throw
- network setup failure → clear error

- [ ] **Step 5: Run tests**

Run: `./gradlew :control-plane-modules:container-deployment-provider:test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add control-plane-modules/container-deployment-provider/
git commit -m "feat: implement ContainerLocalDeploymentProvider with lifecycle and scaling"
```

---

### Task 21: Wire `ContainerDeploymentProviderConfiguration`

**Files:**
- Modify: `control-plane-modules/container-deployment-provider/src/main/java/it/unimib/datai/nanofaas/modules/container/ContainerDeploymentProviderConfiguration.java`
- Create: `control-plane-modules/container-deployment-provider/src/test/java/it/unimib/datai/nanofaas/modules/container/ContainerLocalDeploymentProviderConfigurationTest.java`

- [ ] **Step 1: Write configuration test**

```java
@Test
void moduleCreatesProviderBean_withoutKubernetesClasses() {
    // Verify that ContainerLocalDeploymentProvider bean is created
    // Verify that no KubernetesClient or KubernetesProperties beans are required
}

@Test
void coreWithContainerLocal_startsSuccessfully() {
    // SpringBootTest with only container-deployment-provider module
}
```

- [ ] **Step 2: Implement configuration**

```java
@Configuration
@EnableConfigurationProperties(ContainerLocalProperties.class)
public class ContainerDeploymentProviderConfiguration {

    @Bean
    ContainerRuntimeAdapter containerRuntimeAdapter(ContainerLocalProperties properties) {
        return new CliContainerRuntimeAdapter(properties.runtimeAdapter());
    }

    @Bean
    ContainerLocalDeploymentProvider containerLocalDeploymentProvider(
            ContainerRuntimeAdapter adapter, ContainerLocalProperties properties) {
        return new ContainerLocalDeploymentProvider(adapter, properties);
    }
}
```

- [ ] **Step 3: Run tests**

Run: `./gradlew :control-plane-modules:container-deployment-provider:test`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add control-plane-modules/container-deployment-provider/
git commit -m "feat: wire ContainerDeploymentProviderConfiguration with runtime adapter"
```

---

### Task 22: Add module combination smoke tests for container-local

**Files:**
- Modify: `scripts/test-control-plane-module-combinations.sh`

- [ ] **Step 1: Add combinations**

- `container-deployment-provider` alone
- `container-deployment-provider,autoscaler`
- `k8s-deployment-provider,container-deployment-provider` (both providers)
- All modules including both providers

- [ ] **Step 2: Run**

Run: `./scripts/test-control-plane-module-combinations.sh --task :control-plane:bootJar`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add scripts/test-control-plane-module-combinations.sh
git commit -m "test: add container-deployment-provider to module combination smoke tests"
```

---

### Phase 3 Gate Checklist

- [ ] `./gradlew :control-plane:test -PcontrolPlaneModules=none` — PASS
- [ ] `./gradlew :control-plane:test -PcontrolPlaneModules=container-deployment-provider` — PASS
- [ ] `./gradlew :control-plane:test -PcontrolPlaneModules=k8s-deployment-provider` — PASS
- [ ] `./gradlew :control-plane:test -PcontrolPlaneModules=all` — PASS
- [ ] `./gradlew test -PcontrolPlaneModules=all` — PASS
- [ ] container-local provider creates/manages containers via runtime adapter
- [ ] Runtime adapter boundary does not assume Docker-specific semantics
- [ ] Scaling, readiness, and failure paths are unit-tested
- [ ] Module wiring verified without Kubernetes classes on classpath

---

## Phase 4: E2E Tests and Packaging

**Gate:** End-to-end tests prove both k8s and no-k8s managed deployment paths work. Scripts and docs exist for both profiles.

---

### Task 23: Create `e2e-container-local.sh` script

**Files:**
- Create: `scripts/e2e-container-local.sh`

- [ ] **Step 1: Write the script**

Script should:
1. Build control-plane with `container-deployment-provider` module
2. Start control-plane with `nanofaas.deployment.default-backend=container-local`
3. Register a function with `executionMode=DEPLOYMENT` (no `endpointUrl`)
4. Assert response contains `deploymentBackend=container-local` and non-null `endpointUrl`
5. Invoke the function sync and verify response
6. Scale up to 2 replicas and verify invocation still works
7. Delete function and verify container cleanup
8. Shutdown

- [ ] **Step 2: Make executable and test locally**

```bash
chmod +x scripts/e2e-container-local.sh
./scripts/e2e-container-local.sh
```

- [ ] **Step 3: Commit**

```bash
git add scripts/e2e-container-local.sh
git commit -m "test: add e2e-container-local.sh for no-k8s managed deployment"
```

---

### Task 24: Update existing E2E tests

**Files:**
- Modify: `control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/e2e/K8sE2eTest.java`
- Modify: `scripts/e2e.sh`
- Modify: `scripts/e2e-all.sh` (if exists)

- [ ] **Step 1: Update `K8sE2eTest` to register DEPLOYMENT functions without `endpointUrl`**

Assert:
- `deploymentBackend=k8s`
- Non-empty effective `endpointUrl`
- Sync/async invocation succeeds
- Deletion cleans up K8s resources

- [ ] **Step 2: Update `e2e.sh` to clarify whether it covers POOL, DEPLOYMENT, or both**

- [ ] **Step 3: Update `e2e-all.sh` to include `e2e-container-local.sh`**

- [ ] **Step 4: Commit**

```bash
git add scripts/ control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/e2e/
git commit -m "test: update K8s E2E for provider-backed DEPLOYMENT, add container-local to e2e-all"
```

---

### Task 25: Add no-k8s documentation

**Files:**
- Create: `docs/no-k8s-profile.md`
- Modify: `CLAUDE.md` (update build commands section)

- [ ] **Step 1: Write `docs/no-k8s-profile.md`**

Document:
- What the container-local provider does
- Required prerequisites (Docker, nerdctl, or Podman available on PATH)
- Configuration properties (`nanofaas.container-local.*`)
- How to start the control-plane with only the container-local provider
- Containerd-compatible direction and future plans

- [ ] **Step 2: Update CLAUDE.md with new build/test commands**

Add:
```bash
# Container-local E2E
./scripts/e2e-container-local.sh

# Run with specific provider module
./gradlew :control-plane:bootRun -PcontrolPlaneModules=container-deployment-provider
```

- [ ] **Step 3: Commit**

```bash
git add docs/no-k8s-profile.md CLAUDE.md
git commit -m "docs: add no-k8s profile documentation and update build commands"
```

---

### Phase 4 Gate Checklist

- [ ] `./scripts/e2e-container-local.sh` — PASS
- [ ] `./scripts/e2e.sh` — PASS (no regression)
- [ ] `./scripts/e2e-k8s-vm.sh` — PASS (no regression)
- [ ] `K8sE2eTest` uses `DEPLOYMENT` mode with provider-backed provisioning
- [ ] `docs/no-k8s-profile.md` documents container-local provider usage
- [ ] `CLAUDE.md` updated with new commands

---

## Final Acceptance Gate

All of the following must pass before the issue can be closed:

```bash
# Unit + integration tests
./gradlew :control-plane:test -PcontrolPlaneModules=none
./gradlew :control-plane:test -PcontrolPlaneModules=k8s-deployment-provider
./gradlew :control-plane:test -PcontrolPlaneModules=container-deployment-provider
./gradlew :control-plane:test -PcontrolPlaneModules=all
./gradlew test -PcontrolPlaneModules=all

# Module combination smoke
./scripts/test-control-plane-module-combinations.sh --task :control-plane:bootJar

# E2E
./scripts/e2e.sh
./scripts/e2e-buildpack.sh
./scripts/e2e-k8s-vm.sh
./scripts/e2e-container-local.sh
```

If any of these are intentionally deferred, a follow-up issue must be created explicitly.

---

## Summary of File Changes

### New files (core)
| File | Purpose |
|------|---------|
| `controlplane/deployment/ManagedDeploymentProvider.java` | Provider SPI |
| `controlplane/deployment/ProvisionResult.java` | Provision result DTO |
| `controlplane/deployment/DeploymentProviderResolver.java` | Deterministic provider selection + fallback |
| `controlplane/deployment/DeploymentProperties.java` | Config: `nanofaas.deployment.default-backend` |
| `controlplane/api/FunctionResponse.java` | Response DTO with deployment metadata |
| `controlplane/registry/RegisteredFunction.java` | Spec + deployment metadata wrapper |
| `controlplane/registry/DeploymentMetadata.java` | Deployment state record |

### New modules
| Module | Purpose |
|--------|---------|
| `control-plane-modules/k8s-deployment-provider/` | Kubernetes managed deployment (extracted from core) |
| `control-plane-modules/container-deployment-provider/` | Single-node container managed deployment |

### Moved files (core → k8s module)
| From | To |
|------|----|
| `controlplane/dispatch/KubernetesResourceManager.java` | `modules/k8s/KubernetesResourceManager.java` |
| `controlplane/dispatch/KubernetesDeploymentBuilder.java` | `modules/k8s/KubernetesDeploymentBuilder.java` |
| `controlplane/dispatch/KubernetesMetricsTranslator.java` | `modules/k8s/KubernetesMetricsTranslator.java` |
| `controlplane/dispatch/NanofaasDeploymentConstants.java` | `modules/k8s/NanofaasDeploymentConstants.java` |
| `controlplane/config/KubernetesClientConfig.java` | `modules/k8s/KubernetesClientConfig.java` |
| `controlplane/config/KubernetesProperties.java` | `modules/k8s/KubernetesProperties.java` |
| `controlplane/config/VertxRuntimeHints.java` | `modules/k8s/VertxRuntimeHints.java` |

### Modified files
| File | Change |
|------|--------|
| `FunctionService.java` | Uses `DeploymentProviderResolver` instead of `KubernetesResourceManager` |
| `FunctionController.java` | Returns `FunctionResponse` instead of `FunctionSpec` |
| `FunctionRegistry.java` | Stores `RegisteredFunction` with `DeploymentMetadata` |
| `InternalScaler.java` | Uses provider SPI for scaling |
| `AutoscalerConfiguration.java` | Wires `DeploymentProviderResolver` |
| `ImageValidatorConfiguration.java` | Depends on k8s module |
| `control-plane/build.gradle` | Removes Fabric8 from core |
| `openapi.yaml` | Adds `FunctionResponse` schema |
| `application.yml` | Adds `nanofaas.deployment.*` config |
