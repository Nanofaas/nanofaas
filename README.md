# NanoFaaS

NanoFaaS is a research FaaS platform for deploying and invoking containerized
functions. It includes a Java/Spring Boot control plane, Java and Python
runtimes, container and Kubernetes deployment providers, and a native-capable
Java CLI.

## Start here

Choose the tool that owns the operation:

| Goal | Tool |
|---|---|
| Build, register, invoke, or inspect a function | `nanofaas` CLI |
| Provision a VM, install k3s/Helm, distribute images, or run E2E | Python control-plane tool |
| Build the image matrix or publish an official release | `controlplane-tool images` / `release` ([guide](docs/operations/image-releases.md)) |
| Run the control plane during development | Gradle / Spring Boot |

For a complete local path—including JVM and GraalVM CLI builds—read the
[quickstart](docs/quickstart.md). The [CLI guide](docs/nanofaas-cli.md) documents
the command contract and its boundary with the provisioning tool.

## Build and run

NanoFaaS uses Java 21. Build all Java modules with:

```bash
./gradlew build
./gradlew :control-plane:bootRun
```

Build a runnable JVM distribution of the CLI:

```bash
./gradlew :nanofaas-cli:installDist
clients/cli/build/install/nanofaas-cli/bin/nanofaas-cli --help
```

Build the standalone native executable when GraalVM Native Image is available:

```bash
./gradlew :nanofaas-cli:nativeCompile
clients/cli/build/native/nativeCompile/nanofaas-cli --help
```

Run the focused native smoke test with:

```bash
./gradlew :nanofaas-cli:nativeSmoke
```

It checks help, version, and a retained HTTP command against a local stub. See
[the quickstart](docs/quickstart.md#build-the-cli) for prerequisites and the
complete sequence.

## Test

```bash
./gradlew test
```

Docker-backed and Kubernetes tests require their respective runtimes. Kubernetes
deployment is performed through the Helm chart in `deploy/helm/nanofaas`.

## Control-plane workflows

The Python control-plane tool reads versioned YAML scenarios and environment bindings. Its only commands are `run`, `plan`, `list`, `inspect`, `doctor`, and `tui`.

```bash
export NANOFAAS_ROOT="$(pwd)"
cd ../nanolab
./nanolab.sh list
./nanolab.sh plan packages/nanolab/scenarios-v2/validate-k8s.yaml \
  --environment packages/nanolab/environments/multipass.yaml
./nanolab.sh run packages/nanolab/scenarios-v2/validate-k8s.yaml \
  --environment packages/nanolab/environments/external.yaml.example
```

The external environment is suitable for a remote VM reachable through SSH and
provisioned separately with Ansible. Azure and Proxmox use the same workflow
model. See [the nanolab guide](https://github.com/miciav/nanolab#readme) and
the [quickstart](docs/quickstart.md).

## Repository layout

- `platform/`: common contracts, control plane, and deployment modules.
- `clients/cli/`: backend-neutral `nanofaas` HTTP client.
- `sdks/java/`: reusable Java invocation SDK.
- `services/java/warm-echo/`: long-running warm-service example.
- `functions/`: example functions and their manifests.
- `tools/controlplane/`: VM provisioning and scenario orchestration.
- `docs/`: operational and architectural documentation.

## Documentation

- [Quickstart](docs/quickstart.md): local build, CLI, and infrastructure paths.
- [CLI guide](docs/nanofaas-cli.md): commands, payloads, deploy behavior, and scope.
- [nanolab guide](https://github.com/miciav/nanolab#readme): provisioning, environments, and scenarios.
- [Control-plane operation](docs/control-plane.md): Java control-plane deployment overview.
- [Testing guide](docs/testing.md): test layers and commands.
- [E2E tutorial](docs/e2e-tutorial.md): validation environments and scenarios.
- [Function pod architecture](docs/function-pod-architecture.md): function execution model.
