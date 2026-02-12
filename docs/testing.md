# Testing

This document describes the test suites available for nanofaas: unit tests,
integration tests, and end-to-end (E2E) tests.

---

## Unit Tests

All modules use JUnit 5. Test classes follow the `*Test.java` naming convention.

```bash
# Run all unit tests
./gradlew test

# Run a single module
./gradlew :control-plane:test
./gradlew :function-runtime:test
./gradlew :nanofaas-cli:test

# Run a single test class
./gradlew :control-plane:test --tests it.unimib.datai.nanofaas.controlplane.core.QueueManagerTest

# Run with coverage report (JaCoCo)
./gradlew :nanofaas-cli:test :nanofaas-cli:jacocoTestReport
# Report: nanofaas-cli/build/reports/jacoco/test/html/index.html
```

### Key testing libraries

| Module | Libraries |
|--------|-----------|
| control-plane | JUnit 5, Reactor Test, Fabric8 Mock Server, RestAssured (E2E) |
| function-runtime | JUnit 5, Spring Boot Test |
| nanofaas-cli | JUnit 5, OkHttp MockWebServer, Fabric8 Mock Client, AssertJ |

### CLI unit tests

The `nanofaas-cli` module has comprehensive unit tests covering all commands:

- **Function CRUD**: `FnApplyCommandTest`, `FnGetCommandTest`, `FnListCommandTest`, `FnDeleteCommandTest`
- **Invocation**: `InvokeCommandTest`, `EnqueueCommandTest`
- **Execution status**: `ExecGetCommandTest`
- **Deploy**: `DeployCommandTest` (requires Docker for build tests)
- **Kubernetes helpers**: `K8sPodsCommandTest`, `K8sDescribeCommandTest`, `K8sLogsCommandTest`
- **Infrastructure**: `RootCommandTest`, `ConfigStoreTest`, `ControlPlaneClientTest`, `HttpJsonTest`, `BuildSpecLoaderTest`, `DockerBuildxTest`, `YamlIOTest`

HTTP interactions are tested with OkHttp `MockWebServer`. Kubernetes interactions
use `@EnableKubernetesMockClient(crud = true)` from Fabric8.

---

## E2E Tests

nanofaas provides several E2E test suites. Each creates a Multipass VM with k3s,
deploys the platform, and runs tests against the live cluster.

### CLI E2E (`scripts/e2e-cli.sh`)

Tests the full `nanofaas` CLI against a real k3s cluster. This is the most
comprehensive CLI validation — it exercises every command end-to-end.

**Prerequisites:**
- [Multipass](https://multipass.run) (`brew install multipass`)
- SSH public key at `~/.ssh/id_rsa.pub` or `~/.ssh/id_ed25519.pub`
- Internet connection (downloads k3s, Docker, JDK 21 inside the VM)

**Run:**

```bash
# Full run (creates VM, tests, cleans up)
./scripts/e2e-cli.sh

# Keep VM for debugging
KEEP_VM=true ./scripts/e2e-cli.sh
```

**What it does:**

1. Creates a Multipass VM (4 CPU, 8 GB RAM, 30 GB disk)
2. Installs dependencies: JDK 21, Docker, k3s
3. Syncs the project and builds JARs + Docker images
4. Deploys control-plane and function-runtime on k3s
5. Builds the CLI distribution (`installDist`)
6. Runs **47 tests** across all CLI commands:

| Phase | Tests | What is verified |
|-------|-------|-----------------|
| Health | 5 | Pod status, `/actuator/health`, liveness, readiness |
| Config | 2 | CLI binary exists, `--help` output, `--endpoint` flag |
| Function CRUD | 8 | `fn apply`, `fn list`, `fn get`, `fn delete`, idempotency, negative cases |
| Sync invoke | 5 | Inline JSON, `@file` data, custom headers, invalid JSON, nonexistent function |
| Async enqueue | 2 | Returns `executionId`, custom headers |
| Execution status | 3 | `exec get`, ID matching, `--watch` polling |
| Kubernetes | 8 | `k8s pods`, `k8s describe`, `k8s logs`, `--container` flag, negative cases |
| Platform | 7 | `platform install/status/uninstall`, NodePort endpoint, post-uninstall failure |
| Cleanup | 3 | Delete function, idempotent delete, verify empty list |

**Configuration:**

| Variable | Default | Description |
|----------|---------|-------------|
| `VM_NAME` | `nanofaas-cli-e2e-<timestamp>` | Multipass VM name |
| `CPUS` | `4` | VM CPU count |
| `MEMORY` | `8G` | VM memory |
| `DISK` | `30G` | VM disk size |
| `NAMESPACE` | `nanofaas-e2e` | Kubernetes namespace |
| `KEEP_VM` | `false` | Keep VM after script exits |

**Typical duration:** ~10 minutes (mostly VM setup and Gradle build).

**Debugging a failure:**

```bash
# Run with preserved VM
KEEP_VM=true ./scripts/e2e-cli.sh

# SSH into the VM
multipass shell <vm-name>

# Check pods
kubectl get pods -n nanofaas-e2e

# Check control-plane logs
kubectl logs -l app=control-plane -n nanofaas-e2e --tail=50

# Run a CLI command manually
export KUBECONFIG=/home/ubuntu/.kube/config
export PATH=$PATH:/home/ubuntu/nanofaas/nanofaas-cli/build/install/nanofaas-cli/bin
export NANOFAAS_ENDPOINT=http://$(kubectl get svc control-plane -n nanofaas-e2e -o jsonpath='{.spec.clusterIP}'):8080
export NANOFAAS_NAMESPACE=nanofaas-e2e
nanofaas fn list

# Clean up when done
exit
multipass delete <vm-name> && multipass purge
```

### K3s Curl E2E (`scripts/e2e-k3s-curl.sh`)

Tests the control-plane REST API with `curl` against a k3s cluster.
Covers the core HTTP API (register, invoke, enqueue, get execution status).

```bash
./scripts/e2e-k3s-curl.sh
KEEP_VM=true ./scripts/e2e-k3s-curl.sh    # keep VM for debugging
```

### Docker E2E (`scripts/e2e.sh`)

Runs control-plane + function-runtime in local Docker containers using
Testcontainers and RestAssured. No Kubernetes needed.

```bash
./scripts/e2e.sh
```

### Buildpack E2E (`scripts/e2e-buildpack.sh`)

Same as Docker E2E but builds images using Cloud Native Buildpacks instead
of Dockerfiles.

```bash
./scripts/e2e-buildpack.sh
```

### Kubernetes E2E (JUnit on k3s)

JUnit-based E2E that runs in a dedicated Multipass VM with k3s.

```bash
# Full run (provision VM, install k3s, build/push images to local registry, run test)
./scripts/e2e-k8s-vm.sh

# Gradle alias
./gradlew k8sE2e
```

### Host CLI Platform E2E (`scripts/e2e-cli-host-platform.sh`)

End-to-end scenario where `nanofaas-cli` runs on the host machine and executes
`platform install/status/uninstall` (Helm local to host) against a k3s cluster
running in Multipass.

```bash
./scripts/e2e-cli-host-platform.sh
```

### Load Testing (`scripts/e2e-k3s-helm.sh` + `scripts/e2e-loadtest.sh`)

Full Helm-based deployment with k6 load testing and Grafana dashboard.
See [docs/e2e-tutorial.md](e2e-tutorial.md) for a detailed walkthrough.

```bash
./scripts/e2e-k3s-helm.sh     # deploy nanofaas with Helm
./scripts/e2e-loadtest.sh     # run k6 load tests + Grafana
```

---

## Test Coverage

Coverage is tracked with JaCoCo. Current targets for `nanofaas-cli`:

| Metric | Target | Current |
|--------|--------|---------|
| Instruction | > 90% | 92% |
| Branch | > 75% | 80% |

Generate the report:

```bash
./gradlew :nanofaas-cli:test :nanofaas-cli:jacocoTestReport
open nanofaas-cli/build/reports/jacoco/test/html/index.html
```

---

## Summary

| Suite | Scope | Requires | Command |
|-------|-------|----------|---------|
| Unit tests | All modules | JDK 21 | `./gradlew test` |
| CLI E2E | All CLI commands | Multipass + SSH key | `./scripts/e2e-cli.sh` |
| Host CLI Platform E2E | Host CLI + Helm lifecycle on k3s VM | Multipass + Helm on host | `./scripts/e2e-cli-host-platform.sh` |
| K3s Curl E2E | REST API | Multipass | `./scripts/e2e-k3s-curl.sh` |
| Docker E2E | Core flow | Docker | `./scripts/e2e.sh` |
| Buildpack E2E | Core flow (buildpack) | Docker | `./scripts/e2e-buildpack.sh` |
| K8s E2E (JUnit) | Control-plane on k3s | Multipass | `./scripts/e2e-k8s-vm.sh` or `./gradlew k8sE2e` |
| Load test | Performance | Multipass + k6 | `./scripts/e2e-k3s-helm.sh && ./scripts/e2e-loadtest.sh` |
