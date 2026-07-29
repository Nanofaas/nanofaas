# Docker Java Compatibility Spike Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Prove that `docker-java` can manage Docker containers from both the JVM and the published-style GraalVM native control-plane image.

**Architecture:** Keep the existing `ContainerRuntimeAdapter` boundary and add a `docker-java` implementation selected explicitly with `nanofaas.container-local.runtime-adapter=docker-java`. The spike preserves the CLI adapter as the default until JVM, real-Docker, and Native Image gates all pass.

**Tech Stack:** Java 21, Spring Boot, docker-java 3.7.1, JUnit 5, Docker Engine, GraalVM Native Image.

---

### Task 1: Add the docker-java adapter with unit coverage

**Files:**
- Modify: `platform/modules/container-deployment-provider/build.gradle`
- Create: `platform/modules/container-deployment-provider/src/main/java/it/unimib/datai/nanofaas/modules/containerdeploymentprovider/DockerJavaContainerRuntimeAdapter.java`
- Create: `platform/modules/container-deployment-provider/src/test/java/it/unimib/datai/nanofaas/modules/containerdeploymentprovider/DockerJavaContainerRuntimeAdapterTest.java`

**Steps:**
1. Add a failing test for Docker availability through `pingCmd`.
2. Run the test and verify that it fails because the adapter does not exist.
3. Add `docker-java-core` and `docker-java-transport-zerodep` version `3.7.1`.
4. Implement the smallest adapter supporting availability, remove, create, start, environment, command, port binding, and resource constraints.
5. Run the adapter test and the complete module test suite.

### Task 2: Add explicit runtime selection

**Files:**
- Modify: `platform/modules/container-deployment-provider/src/main/java/it/unimib/datai/nanofaas/modules/containerdeploymentprovider/ContainerDeploymentProviderConfiguration.java`
- Modify: `platform/modules/container-deployment-provider/src/test/java/it/unimib/datai/nanofaas/modules/containerdeploymentprovider/ContainerDeploymentProviderConfigurationTest.java`

**Steps:**
1. Add a failing configuration test selecting `runtime-adapter=docker-java`.
2. Verify the expected failure.
3. Wire the new adapter only for the explicit `docker-java` value; preserve existing Docker, Podman, and nerdctl CLI behavior.
4. Run configuration and module tests.

### Task 3: Prove real Docker lifecycle

**Files:**
- Create: `platform/modules/container-deployment-provider/src/test/java/it/unimib/datai/nanofaas/modules/containerdeploymentprovider/DockerJavaContainerRuntimeAdapterIntegrationTest.java`

**Steps:**
1. Add an integration test that creates, starts, inspects, and removes a uniquely named `nginx:alpine` container.
2. Run it against the local Docker Engine.
3. Verify cleanup in a `finally` block and rerun to prove idempotency.

### Task 4: Prove Native Image compatibility

**Files:**
- Modify only if required by the native build: runtime hints under `platform/modules/container-deployment-provider/src/main/`.

**Steps:**
1. Build the native control-plane image with `container-deployment-provider` enabled.
2. Start it with `/var/run/docker.sock` mounted and `runtime-adapter=docker-java`.
3. Verify control-plane readiness and Docker backend availability.
4. Register a managed function, verify that its container starts, then delete it and verify cleanup.
5. If native compilation or execution fails, capture the exact incompatibility; add only the minimal reachability metadata required and repeat.

### Task 5: Record the result

**Files:**
- Modify: `docs/plans/2026-07-29-docker-java-spike.md`

**Steps:**
1. Record JVM, Docker lifecycle, and Native Image outcomes.
2. Run `git diff --check`, the module test suite, and GitNexus change detection.
3. Commit only if every required gate passes; otherwise leave the spike uncommitted with the blocker documented.

## Spike results

Date: 2026-07-29

- **JVM unit tests:** passed. The adapter maps availability, environment,
  command, port publishing, CPU shares, CPU limits, memory reservation, memory
  limits, force removal, and idempotent removal.
- **Real Docker lifecycle:** passed twice against Docker Engine 29.6.2. The
  test created, started, inspected, listed, and removed a uniquely named
  container through `docker-java` 3.7.1.
- **Native Image compilation:** passed with the
  `container-deployment-provider` module enabled. The resulting ARM64 native
  executable was packaged in `nanofaas/control-plane:docker-java-spike`.
- **Native Docker socket transport:** passed. `pingCmd()` works from the native
  executable when the container process has permission to access the mounted
  socket.
- **Native create/start/remove:** passed after registering targeted Spring AOT
  runtime hints for the eight Docker request/response model types used by this
  adapter. A managed replica was observed running, and failure cleanup removed
  it.

Two deployment constraints were confirmed:

1. The Paketo image runs as the non-root `cnb` user and cannot open the
   host-mounted Docker socket by default. Running as root proves the transport
   works; the final Compose design must deliberately grant socket access.
2. The current provider publishes replicas on host ports and then probes
   `127.0.0.1:<host-port>`. From a containerized control plane this loopback is
   the control-plane container, so readiness times out even though the replica
   is running. The final provider must attach replicas to the shared Compose
   network and probe `http://<container-name>:8080`.

**Conclusion:** `docker-java` 3.7.1 is compatible with nanoFaaS on both the JVM
and GraalVM Native Image. The remaining blocker is the provider's legacy
host-port addressing model, not the library.
