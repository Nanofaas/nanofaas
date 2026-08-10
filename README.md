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
| Build the image matrix or publish an official release | `nanolab.sh release prepare` + `nanolab.sh run scenarios-v2/release.yaml` ([guide](docs/operations/image-releases.md)) |
| Run the control plane during development | Gradle / Spring Boot |

For a complete local path—including JVM and GraalVM CLI builds—read the
[quickstart](docs/quickstart.md). The [CLI guide](docs/nanofaas-cli.md) documents
the command contract and its boundary with the provisioning tool.

## Build and run

NanoFaaS uses Java 25. Build all Java modules with:

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

Build and smoke-test all native Java targets with the GraalVM release pinned in
`gradle.properties`:

```bash
scripts/native-build.sh
```

Build a native Distroless image for any supported service or example function:

```bash
scripts/native-java-image.sh control-plane
scripts/native-java-image.sh warm-echo
scripts/native-java-image.sh roman-numeral-lite
```

JVM images use their module Dockerfile and Distroless Java 25. Native image
creation does not use Spring Boot buildpacks.

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
- `docs/`: operational and architectural documentation.

VM provisioning and scenario orchestration now live in the separate
[nanolab](https://github.com/miciav/nanolab) repository.

## Documentation

- [Documentation index](docs/README.md): complete table of contents (guides, architecture, operations, reference).
- [Quickstart](docs/quickstart.md): local build, CLI, and infrastructure paths.
- [Tutorial: writing a function](docs/tutorial-function.md): end-to-end walkthrough with examples.
- [nanolab guide](https://github.com/miciav/nanolab#readme): provisioning, environments, and scenarios.
