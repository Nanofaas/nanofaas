# Backend-neutral native CLI design

## Goal

Make the Java CLI a small, backend-neutral control-plane client that produces a fully functional GraalVM native executable. Kubernetes provisioning and diagnostics remain outside the CLI. A deployment using the container provider must not install or load Kubernetes, Helm, or kubectl components.

## Public surface

The retained commands are:

```text
nanofaas
├── fn list|get|apply|delete|test
├── deploy
├── invoke
├── enqueue
└── exec get [--watch]
```

The `k8s` and `platform` command groups are removed. Helm remains the manual Kubernetes installation mechanism, while the Python control-plane tool owns local and remote provisioning workflows. Kubernetes diagnostics use kubectl directly. A future `nanofaas status` command is recorded in the roadmap; it must query the control plane over HTTP and expose backend-neutral health, version, modules, and capabilities.

## Architecture

The CLI depends on Picocli, Jackson, the shared API models, and the Java standard HTTP client. It has no Kubernetes client, Helm integration, or kubectl integration. Commands communicate only with the configured control-plane endpoint, so the same executable works when functions run through the container provider or the Kubernetes provider.

Function responses are represented by a client-side response model matching the server contract instead of being deserialized into `FunctionSpec`. Apply and deploy share one reconciliation path. Server-managed response fields are never compared as if they were requested configuration.

Invocation input is raw function input. Contract files used by `fn test` remain separate documents containing `input` and `expected`; tutorials and E2E workflows must not pass those envelopes directly to `invoke` or `enqueue`.

Build paths in `x-cli.build` are relative to the function manifest. The Dockerfile is resolved from the build context. `push: false` means `docker buildx build --load`, allowing the container provider to use the resulting local image.

## User experience

Every command supports `--help`; global endpoint, namespace, and config options are inherited by subcommands. `--version` reports the Gradle project version. Expected user failures produce one concise message on stderr and a non-zero exit code; stack traces are reserved for an explicit debug mode if one is added later.

Structured single-object results use JSON. `fn list` keeps its concise tabular output. The implementation avoids a general output-format framework until a demonstrated automation need exists.

## Verification

Development follows red-green TDD. Unit tests cover the command surface, exact HTTP response shapes, apply reconciliation, invocation input, build path resolution, `--load`, help, version, and error rendering. The Python CLI workflow validates function output rather than accepting HTTP success alone.

The final gate runs the full Java CLI tests, affected Python tests, Gradle build, `nativeCompile`, and native smoke tests for help, version, and an HTTP command. The native runtime classpath must contain no Fabric8, Vert.x, or Netty dependencies.
