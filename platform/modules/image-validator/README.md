# image-validator

Optional control-plane module: Kubernetes-backed validation of container
images at function registration time — misconfigured images fail fast at
`POST /v1/functions` instead of surfacing later as crash-looping pods.

## Provides

- `ImageValidator` (`KubernetesImageValidator`) — core SPI implementation that
  checks the image referenced by the `FunctionSpec` is resolvable/pullable in
  the target cluster (including `imagePullSecrets`).

Validation failures reject the registration with error codes such as
`IMAGE_NOT_FOUND` and `IMAGE_PULL_AUTH_REQUIRED` (the CLI maps them to
actionable messages).

## Configuration

No dedicated prefix; uses the same Kubernetes client/namespace configuration
as the k8s deployment provider (`nanofaas.k8s.*`).

## Notes

- Requires cluster access; without this module the core no-op validator
  accepts any image string.
