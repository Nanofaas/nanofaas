# NanoFaaS CLI

`nanofaas` is a backend-neutral HTTP client for a NanoFaaS control plane. It does
not provision infrastructure, call Helm or `kubectl`, or inspect provider-specific
resources. The Python control-plane tool owns VM provisioning, Kubernetes/Helm
installation, image distribution, and end-to-end scenarios.

## How to build

NanoFaaS CLI is built with Gradle, just like the NanoFaaS control-plane.
All CLI-related tasks live under the `:nanofaas-cli:` project. From the
NanoFaaS repository root, you can list all available CLI tasks with:

```console
$ ./gradlew :nanofaas-cli:tasks --all
```

To build and install the NanoFaaS CLI:

```console
$ ./gradlew :nanofaas-cli:installDist
```

This creates the `nanofaas-cli` executable under the
`clients/cli/build/install/nanofaas-cli/bin/` directory. You can then run the
CLI with:

```console
$ clients/cli/build/install/nanofaas-cli/bin/nanofaas-cli --help
```

To avoid typing the full path every time, add the installation directory to
your `$PATH`:

```console
$ echo 'export PATH="$PATH:$HOME/nanofaas/clients/cli/build/install/nanofaas-cli/bin"' >> ~/.bashrc
```

Make sure to adjust the `$HOME/nanofaas` prefix to match the directory where
you cloned NanoFaaS.

After reloading your shell, you can run the CLI simply by typing:

```console
user@linux:~$ nanofaas-cli --help
Usage: nanofaas [-hV] [--config=<configPath>] [--endpoint=<endpoint>] [COMMAND]
Nanofaas control-plane client.
      --config=<configPath>
                  Path to config file (default: ~/.config/nanofaas/config.yaml).
      --endpoint=<endpoint>
                  Control-plane base URL (overrides config/env).
  -h, --help      Show this help message and exit.
  -V, --version   Print version information and exit.
Commands:
  fn             Manage registered functions.
  invoke         Invoke a function synchronously.
  enqueue        Invoke a function asynchronously.
  exec           Manage executions.
  deploy         Build+push image (docker buildx) and apply the function spec.
  control-plane  Inspect the control-plane build and API contract.
```

## Commands

The retained command surface is deliberately small:

```text
nanofaas fn apply|list|get|delete|test
nanofaas fn update <name> -f patch.yaml
nanofaas fn replicas get <name>
nanofaas fn replicas set <name> <count>
nanofaas invoke <function> <json|@file|@->
nanofaas enqueue <function> <json|@file|@->
nanofaas exec get <execution-id>
nanofaas deploy --file function.yaml
nanofaas control-plane info
nanofaas control-plane contract
nanofaas control-plane config get [namespace]
nanofaas control-plane config validate <namespace> -f values.yaml
nanofaas control-plane config patch <namespace> -f values.yaml
```

`deploy` is a local developer convenience: it reads `x-cli.build` from the
function manifest, runs `docker buildx`, then applies the function through the
control-plane API. Build paths are resolved relative to the manifest. A build
with `push: false` uses `--load`, so its image is available to the local Docker
daemon rather than silently discarded.

`fn update` sends a partial patch for the mutable function settings
(`concurrency`, `timeoutMs`, `maxRetries`, `concurrencyControl`) without
re-registering or restarting the deployment. `fn replicas get`/`set` read and
change the desired replica count of a managed deployment. `control-plane info`
prints the build identity and the capabilities derived from the OpenAPI
contract; `control-plane contract` prints that contract unchanged.
`control-plane config get|validate|patch` administer hot runtime configuration:
`validate` checks values without applying them, and `patch` is an
optimistic-concurrency update that reports `409` on a stale revision.

Commands backed by an optional control-plane capability stay visible in
`--help` even when the running control plane does not provide them; when the
capability is absent they fail locally with "not supported by this build". No
live endpoint is consulted for `--help`.

`invoke` prints the `InvocationResponse` envelope — `executionId`, `status`,
`output`, and, when the handler set them, `statusCode`/`headers`/`encoding` —
never the raw handler result. A handler-decided status is marked with the
`X-NanoFaaS-Function-Status` response header: a marked 2xx exits `0`, a marked
non-2xx prints the envelope and exits `1` without being reported as a
control-plane error.

On `fn apply` and `deploy`, changing an immutable field requires `--replace`,
which is a delete-then-register sequence and therefore not atomic.

Payloads passed to `invoke` and `enqueue` are raw function input. They can be
inline JSON, `@path/to/input.json`, or `@-` for standard input. Files consumed by
`nanofaas fn test` keep their test-case envelope because they also describe the
expected response.

## Provisioning and validation

The platform CLI lifecycle is represented by
`packages/nanolab/scenarios-v2/cli.yaml` (in the nanolab checkout) and uses the
same workflow engine as platform validation:

```bash
export NANOFAAS_ROOT="$(pwd)"
cd ../nanolab
./nanolab.sh plan packages/nanolab/scenarios-v2/cli.yaml
./nanolab.sh run packages/nanolab/scenarios-v2/cli.yaml \
  --environment packages/nanolab/environments/external.yaml.example
```

The scenario builds the CLI, applies selected function manifests, lists functions,
invokes them, and deletes them during cleanup. The chosen environment decides
whether these client commands run on the host or on the stack VM; provisioning
remains outside the Java CLI.
