# Quickstart

This guide separates the application CLI from infrastructure provisioning. The
`nanofaas` CLI talks only to the control-plane HTTP API; the Python
control-plane tool provisions VMs and installs the platform.

## Prerequisites

- Java 25 for the regular Gradle build.
- Docker or a compatible container runtime for Docker-backed tests and local
  image builds.
- GraalVM with Native Image for native Java builds.
- Multipass for the local k3s path; SSH and Ansible for an external VM.

Some automated tests require Docker to be installed and available to non-root
users. Follow the [Linux post-installation steps for Docker
Engine](https://docs.docker.com/engine/install/linux-postinstall/#manage-docker-as-a-non-root-user)
to configure Docker correctly.

If you do not have a compatible Java runtime, Gradle downloads one
automatically. You can use this runtime to run the control plane or the CLI
manually. You can set `JAVA_HOME` to the directory where Gradle downloads the
runtime. For example:
`/home/<user>/.gradle/jdks/eclipse_adoptium-25-amd64-linux.2`. 

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

The nanolab tool (a separate checkout) owns this lifecycle. It uses a locked
uv environment; first verify its local prerequisites and list the available
scenarios:

```bash
export NANOFAAS_ROOT="$(pwd)"
cd ../nanolab
./nanolab.sh doctor
./nanolab.sh list
```

Inspect a workflow before running it:

```bash
export NANOFAAS_ROOT="$(pwd)"
cd ../nanolab
./nanolab.sh plan packages/nanolab/scenarios-v2/validate-container.yaml
./nanolab.sh run packages/nanolab/scenarios-v2/validate-container.yaml
```

For k3s in Multipass:

```bash
export NANOFAAS_ROOT="$(pwd)"
cd ../nanolab
./nanolab.sh plan packages/nanolab/scenarios-v2/validate-k8s.yaml \
  --environment packages/nanolab/environments/multipass.yaml
```

For an SSH-only remote VM, copy `packages/nanolab/environments/external.yaml.example` (in your `nanolab` checkout), set its host/user/home, provision the checkout with Ansible, then use the same `plan` or `run` command.

Azure and Proxmox use the same workflow. Copy the matching example from
`packages/nanolab/environments/`, fill in provider values, and run with
`--provision`. The tool deletes managed VMs when the workflow exits; use `--keep`
only when they must remain available for inspection. External VMs are never deleted.

Start the interactive client with:

```bash
export NANOFAAS_ROOT="$(pwd)"
(cd ../nanolab && ./nanolab.sh tui)
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
and load-test workflow, read the [nanolab guide](https://github.com/miciav/nanolab#readme).

## Where to go next

- [CLI guide](nanofaas-cli.md) for function operations and deploy semantics.
- [nanolab guide](https://github.com/miciav/nanolab#readme) for provisioning and scenarios.
- [Testing guide](testing.md) for the appropriate test layer.
- [E2E tutorial](e2e-tutorial.md) for scenario-specific validation.
