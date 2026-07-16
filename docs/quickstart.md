# Quickstart

This guide separates the application CLI from infrastructure provisioning. The
`nanofaas` CLI talks only to the control-plane HTTP API; the Python
control-plane tool provisions VMs and installs the platform.

## Prerequisites

- Java 21 for the regular Gradle build.
- Docker or a compatible container runtime for Docker-backed tests and local
  image builds.
- GraalVM with Native Image only for the standalone CLI executable.
- Multipass for the local k3s path; SSH and Ansible for an external VM.

## Build the platform

```bash
./gradlew build
```

Run the control plane locally when developing its HTTP API:

```bash
./gradlew :control-plane:bootRun
```

## Build the CLI

Build a JVM distribution:

```bash
./gradlew :nanofaas-cli:installDist
CLI=clients/cli/build/install/nanofaas-cli/bin/nanofaas-cli
"$CLI" --help
```

With a control plane listening locally, pass its endpoint explicitly:

```bash
"$CLI" --endpoint http://localhost:8080 fn list
```

Build the standalone native executable when `native-image` is available through
`JAVA_HOME`:

```bash
./gradlew :nanofaas-cli:nativeCompile
NATIVE_CLI=clients/cli/build/native/nativeCompile/nanofaas-cli
"$NATIVE_CLI" --help
```

Verify the native distribution without deploying a platform:

```bash
./gradlew :nanofaas-cli:nativeSmoke
```

The smoke test starts a temporary local HTTP stub and verifies `--help`,
`--version`, and `fn list`. For the command surface, payload formats, and the
`deploy` behavior, read the [CLI guide](nanofaas-cli.md).

## Provision and validate a platform

The control-plane tool owns this lifecycle. It uses a locked uv environment
through the repository launcher; first verify its local prerequisites and list
the available scenarios:

```bash
scripts/controlplane.sh doctor
scripts/controlplane.sh list
```

Inspect a workflow before running it:

```bash
scripts/controlplane.sh plan tools/controlplane/scenarios-v2/validate-container.yaml
scripts/controlplane.sh run tools/controlplane/scenarios-v2/validate-container.yaml
```

For k3s in Multipass:

```bash
scripts/controlplane.sh plan tools/controlplane/scenarios-v2/validate-k8s.yaml \
  --environment tools/controlplane/environments/multipass.yaml
```

For an SSH-only remote VM, copy `tools/controlplane/environments/external.yaml.example`, set its host/user/home, provision the checkout with Ansible, then use the same `plan` or `run` command.

Azure and Proxmox use the same workflow. Copy the matching example from
`tools/controlplane/environments/`, fill in provider values, and run with
`--provision`. The tool deletes managed VMs when the workflow exits; use `--keep`
only when they must remain available for inspection. External VMs are never deleted.

Start the interactive client with:

```bash
scripts/controlplane.sh tui
```

Its adapted navigation exposes **Validation**, **CLI**, **Load Testing**, and
**Tools**. Workflow entries continue with environment and plan/run selection;
non-local runs can provision the selected environment and choose their cleanup
policy. A single invariant branded header stays at the top as menus and static
views change. Running a workflow opens the live workflow dashboard, where phase
progress and command logs remain visible; press `l` to toggle the log panel.

When only the provider templates are present, the TUI environment picker still
shows **Azure (setup required)** and **Proxmox (setup required)**. Selecting
either entry displays setup guidance, writes no files, and starts no workflow.
The TUI never loads or executes `.yaml.example` templates. Copy
`azure.yaml.example` to `azure.yaml` or `proxmox.yaml.example` to `proxmox.yaml`,
then fill in the provider values and configuration. Keep external authentication
outside YAML: run `az login` for Azure, and provide the Proxmox password through
the environment variable named by `password_env`. Do not store secrets in YAML.

For the complete command reference, provider configuration, VM cleanup rules,
and load-test workflow, read the [control-plane tool guide](../tools/controlplane/README.md).

## Where to go next

- [CLI guide](nanofaas-cli.md) for function operations and deploy semantics.
- [Control-plane tool guide](../tools/controlplane/README.md) for provisioning and scenarios.
- [Testing guide](testing.md) for the appropriate test layer.
- [E2E tutorial](e2e-tutorial.md) for scenario-specific validation.
