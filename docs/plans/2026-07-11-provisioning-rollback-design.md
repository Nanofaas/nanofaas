# Provisioning Rollback Design

Managed function registration can fail after a provider has already created external resources. The control-plane registry cannot compensate when `provision()` itself throws because no `ProvisionResult` or `RegisteredFunction` exists yet. Each provider must therefore clean up resources created during its own failed call.

For Kubernetes, `KubernetesResourceManager.provision()` will track whether it created the Deployment, Service, and HPA. On failure it will delete only those newly created resources, in reverse dependency order. Resources that existed before the call are never deleted. This intentionally does not restore patches made to pre-existing resources: normal function registration rejects duplicate names, while full object snapshots would add substantial complexity for a path outside the registration contract.

For the local container provider, a failed initial scale-up will remove every replica already recorded in the temporary function state, then remove the state and close the proxy. Cleanup continues after individual cleanup failures; those failures are attached to the original provisioning exception as suppressed exceptions.

Regression tests will force a Kubernetes Service creation failure after Deployment creation, verify that pre-existing Kubernetes resources survive cleanup, and force the second local-container replica to fail after the first becomes ready. A failed call must leave no resource created by that call and must permit a clean retry.
