# Kubernetes Design (Detailed)

## Control Plane (Single Pod)

- Deployment:
  - 1 replica
  - container: control-plane image
  - ports: 8080 (HTTP), 8081 (actuator)
- Service:
  - ClusterIP for internal access
- ServiceAccount + RBAC:
  - create/list/watch Jobs
  - get/list/watch Pods

## Function Execution (Default)

- Job per invocation
- Pod template fields:
  - image: function image
  - command: from FunctionSpec (optional)
  - env: FunctionSpec env + execution metadata
  - resources: requests/limits from FunctionSpec
  - restartPolicy: Never

## Managed Deployments

- Function Deployments and Services are reconciled in place instead of deleted
  and recreated during provisioning updates.
- HPA objects are reconciled only when the function scaling strategy is `HPA`;
  stale HPAs are deleted when a function is updated to another strategy.
- Function image pull policy is configurable through
  `nanofaas.k8s.image-pull-policy`. The default is `Always` to preserve
  mutable-tag behavior; use `IfNotPresent` with immutable image references to
  reduce registry pulls.

## Labels & Annotations

- Labels:
  - app=nanofaas
  - function=<name>
  - executionId=<id>
- Annotations:
  - traceId
  - idempotencyKey

## Resource Defaults (Control Plane)

- cpu: 250m
- memory: 512Mi
- tune as needed for queue size and concurrency

## Network

- Control plane calls K8s API inside cluster.
- Function pods are invoked via HTTP from control plane or via Job + container logic.

## Secrets

- Use K8s Secrets for function env if needed.
- Control plane reads secret refs and injects into Job env.
