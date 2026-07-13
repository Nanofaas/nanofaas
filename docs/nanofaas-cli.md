# NanoFaaS CLI

`nanofaas` is a backend-neutral HTTP client for a NanoFaaS control plane. It does
not provision infrastructure, call Helm or `kubectl`, or inspect provider-specific
resources. The Python control-plane tool owns VM provisioning, Kubernetes/Helm
installation, image distribution, and end-to-end scenarios.

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
`tools/controlplane/scenarios-v2/cli.yaml` and uses the same workflow engine as
platform validation:

```bash
scripts/controlplane.sh plan tools/controlplane/scenarios-v2/cli.yaml
scripts/controlplane.sh run tools/controlplane/scenarios-v2/cli.yaml \
  --environment tools/controlplane/environments/external.yaml.example
```

The scenario builds the CLI, applies selected function manifests, lists functions,
invokes them, and deletes them during cleanup. The chosen environment decides
whether these client commands run on the host or on the stack VM; provisioning
remains outside the Java CLI.
