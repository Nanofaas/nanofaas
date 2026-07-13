# Self-contained Load-test Workflow Design

## Goal

Restore the complete load-test lifecycle lost during the control-plane tool simplification without restoring the deleted runners, recipes, profiles, or provider-specific orchestration layers.

## Architecture

The Kubernetes deployment portion of the current `validate` workflow becomes a small public task-spec factory. Both `validate` and `loadtest` use that factory, so build, image push, Helm values, function registration, and cleanup have one implementation. The existing load-test workflow remains responsible for k6, result transport, Prometheus snapshots, report generation, thresholds, and autoscaling verification.

The control-plane plan composes one sequential workflow:

1. preflight, JVM artifacts, core and selected function images;
2. control-plane and function-runtime Helm releases with load-test NodePorts;
3. function registration, including the scaling policy when autoscaling verification is enabled;
4. k6 preparation and execution on `stack` or `loadgen`;
5. result fetch, Prometheus snapshot, autoscaling gate, report, and k6 gate;
6. function and Helm cleanup.

Topology remains an environment concern. Without a `loadgen` role, k6 executes on `stack`; with a `loadgen` role, the same workflow uses the dedicated executor and fetcher. Multipass and external SSH use the same task graph. `--provision` remains explicit and idempotent; it prepares machines but does not deploy NanoFaaS.

## Endpoint resolution

The load-test stack exposes the existing NodePorts 30080, 30081, and 30090. Explicit CLI URLs continue to override discovery. Multipass and external environments derive defaults from the stack endpoint after optional provisioning; `plan` remains provider-side-effect free and renders stable placeholder URLs. This avoids requiring users to copy the Multipass IP while preserving remote-host control.

## Failure and cleanup

Main execution stops at the first failure. Cleanup removes only function registrations and Helm releases owned by the workflow and never destroys VMs. `--keep` skips infrastructure cleanup so a failed research run remains inspectable. Cleanup errors are reported alongside the primary failure.

## Testing

Contract tests assert the exact lifecycle order for shared and dedicated load generators, load-test NodePort Helm values, registration before k6, cleanup, and `--keep`. Provider tests cover Multipass and external endpoint resolution. The real gates are a one-VM Multipass run and, after that succeeds, a two-role Multipass run using a small load-generator VM. Both runs must produce k6 and Prometheus artifacts and leave no Helm releases when cleanup is enabled.
