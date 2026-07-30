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
Usage: nanofaas [-hV] [--config=<configPath>] [--endpoint=<endpoint>]
                [-n=<namespace>] [COMMAND]
Nanofaas control-plane client.
      --config=<configPath>
                  Path to config file (default: ~/.config/nanofaas/config.yaml).
      --endpoint=<endpoint>
                  Control-plane base URL (overrides config/env).
  -h, --help      Show this help message and exit.
  -n, --namespace=<namespace>
                  Function namespace (overrides config/env).
  -V, --version   Print version information and exit.
Commands:
  fn       Manage registered functions.
  invoke   Invoke a function synchronously.
  enqueue  Invoke a function asynchronously.
  exec     Manage executions.
  deploy   Build+push image (docker buildx) and apply the function spec.
```

## Commands

The retained command surface is deliberately small:

```text
nanofaas fn apply|list|get|delete|test
nanofaas invoke <function> <json|@file|@->
nanofaas enqueue <function> <json|@file|@->
nanofaas exec get <execution-id>
nanofaas deploy --file function.yaml
```

`deploy` is a local developer convenience: it reads `x-cli.build` from the
function manifest, runs `docker buildx`, then applies the function through the
control-plane API. Build paths are resolved relative to the manifest. A build
with `push: false` uses `--load`, so its image is available to the local Docker
daemon rather than silently discarded.

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
