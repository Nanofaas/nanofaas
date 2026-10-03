# P2P node information validation

Validated on macOS ARM64 with Java/GraalVM 25.2.4 on 2026-10-03, starting from
`origin/main` commit `ef856960e99a6c56b93be6a978965535f7a3eba7`.

| Check | Evidence |
| --- | --- |
| P2P module | 138 tests, no failures or skips; baseline was 114 |
| Three real socket nodes | Distinct mutable catalogs, exact workload values, function deletion convergence, test-source images/resources, receive-only node, masking, exclusion and isolation/rejoin |
| Docker provider | Inventory, configuration, architecture and native-hint tests; Java and CLI read a real daemon, including a unique test-owned image alias |
| Kubernetes provider | Paginated node listing, node IDs, source-age disclosure, failure and cap behavior, configuration and architecture |
| Containerd provider | Namespace/digest/empty/error/cap tests, configuration and architecture |
| SPI | Defensive-copy and identity contract test |
| Build plugin | 12 module-descriptor and OpenAPI composer tests |
| HTTP route coverage | 9 tests, one composition-dependent skip |
| Deploy | Helm lint/templates with node RBAC off/on and Compose validation |
| Native | P2P-only and Docker/P2P native artifacts compiled; two native nodes exchanged snapshots and runtime flag changes; actual Docker inventory contained 15 images |

Commands used (provider patterns select the new inventory tests and existing configuration/architecture checks):

```bash
./gradlew :control-plane-modules:p2p-discovery:test -PcontrolPlaneModules=p2p-discovery
./gradlew :control-plane-modules:p2p-discovery:test -PcontrolPlaneModules=all +  -PcontainerdMavenLocal=true -Dmaven.repo.local=/private/tmp/nanofaas-p2p-m2
./gradlew :control-plane-modules:container-deployment-provider:test +  -PcontrolPlaneModules=container-deployment-provider,p2p-discovery +  --tests '*ImageInventory*Test' --tests '*DockerJavaRuntimeHintsTest' +  --tests '*ConfigurationTest' --tests '*ArchitectureTest'
./gradlew :control-plane-modules:k8s-deployment-provider:test +  -PcontrolPlaneModules=k8s-deployment-provider,p2p-discovery +  --tests '*KubernetesImageInventorySourceTest' --tests '*ConfigurationTest' --tests '*ArchitectureTest'
./gradlew :control-plane-modules:containerd-deployment-provider:test +  -PcontrolPlaneModules=all -PcontainerdMavenLocal=true +  -Dmaven.repo.local=/private/tmp/nanofaas-p2p-m2 +  --tests '*ContainerdImageInventorySourceTest' --tests '*ConfigurationTest' --tests '*ArchitectureTest'
./gradlew :control-plane-spi:test --tests '*ImageInventoryTest'
./gradlew -p platform/gradle-plugin test --tests '*RepositoryModuleDescriptorsTest' +  --tests '*ControlPlaneModuleProjectPluginTest' --tests '*OpenApiComposerTest'
./gradlew :control-plane:test -PcontrolPlaneModules=p2p-discovery --tests '*OpenApiRouteCoverageTest'
./gradlew :control-plane:nativeCompile -PcontrolPlaneModules=container-deployment-provider,p2p-discovery
helm lint deploy/helm/nanofaas
helm template information-test deploy/helm/nanofaas --set rbac.nodeImageInventory=true
docker compose -f deploy/compose/compose.yaml config --quiet
```

No real containerd daemon was provisioned or exercised. Kubernetes provider checks use test clients;
they do not establish operation against a live Kubernetes cluster.
The configured local Kubernetes API at 127.0.0.1:6443 refused connections.
The socket-test inventory sources prove exchange behavior; the separate Docker integration test
proves real backend inventory.

Containerd's pinned sources were inspected and built into a temporary local Maven repository.
The upstream gRPC 1.73 macOS artifact labeled ARM64 contained an x86_64 executable.
For local compilation only, the temporary clone used a native gRPC 1.76 generator,
omitted its new BlockingV2 stubs unsupported by the pinned runtime, and assigned a distinct
Java outer class name to imagestore.proto to avoid case-insensitive output collisions.
The source SPI/business code and NanoFaaS dependency pins were unchanged.
Use the repository's documented bootstrap on a supported build environment for reproducible artifacts.

One independent review identified cross-category invalidation and missing Docker Image
reflection metadata. Regression tests reproduced both before the fixes.
Local diagnostic category limits and Kubernetes pagination were also verified with failing tests first.
Native Docker startup with an existing config required reflection for DockerConfigFile/AuthConfig
and its package-private currentContext setter; these are covered by the hints test.
The final Docker/P2P native executable starts with the existing Docker configuration,
exchanges 15 real images and preserves functions when images are disabled and re-enabled.
