# k8s-deployment-provider

Optional control-plane module: the Kubernetes backend (`k8s`) for the managed
`DEPLOYMENT` execution mode. Registering a DEPLOYMENT function provisions a
Deployment + Service in the cluster; invocations are dispatched to those warm
pods.

## Provides

- `KubernetesManagedDeploymentProvider` — resolves DEPLOYMENT intents:
  provision on register, deprovision on delete, replica updates (also driven
  by the autoscaler module).
- `KubernetesDeploymentBuilder` — builds the Deployment/Service from the
  `FunctionSpec` (image, command, env, resources, `imagePullSecrets`) and
  injects the `CALLBACK_URL` env var from `nanofaas.k8s.callbackUrl`.
- `KubernetesResourceManager`, `KubernetesMetricsTranslator`,
  `KubernetesClientConfig` (fabric8 client), `VertxRuntimeHints` (native
  image support).

## Configuration (`nanofaas.k8s.*`)

```yaml
nanofaas:
  k8s:
    namespace: ""    # empty = client default
    callbackUrl: "http://control-plane.default.svc.cluster.local:8080/v1/internal/executions"
```

Backend selection: `nanofaas.deployment.default-backend` picks the provider
when more than one is on the classpath.

## Notes

- Requires cluster access (`KUBECONFIG` for E2E; fabric8 mock server in unit
  tests).
- E2E: Run via the NanoLab `validate-k8s` scenario:
  ```bash
  NANOFAAS_ROOT=/path/to/nanofaas ./nanolab.sh run packages/nanolab/scenarios-v2/validate-k8s.yaml --environment packages/nanolab/environments/multipass.yaml
  ```
  Or against an already-prepared cluster:
  ```bash
  NANOFAAS_RUN_K8S_E2E=true KUBECONFIG=/path/to/kubeconfig NANOFAAS_E2E_NAMESPACE=nanofaas-e2e ./gradlew :control-plane-modules:k8s-deployment-provider:test -PrunE2e --tests 'it.unimib.datai.nanofaas.modules.k8s.e2e.K8sE2eTest'
  ```
