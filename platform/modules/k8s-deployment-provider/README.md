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
  `KubernetesClientConfig` (fabric8 client over the JDK HTTP client), `Fabric8RuntimeHints` (native
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

- Requires cluster access (fabric8 mock server in unit tests).
- E2E validation is owned by NanoLab. Run the `validate-k8s` scenario:
  ```bash
  NANOFAAS_ROOT=/path/to/nanofaas ./nanolab.sh run packages/nanolab/scenarios-v2/validate-k8s.yaml --environment packages/nanolab/environments/multipass.yaml
  ```
