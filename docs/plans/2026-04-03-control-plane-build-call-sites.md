# Control-Plane Build Call Sites

Data: `2026-04-03`

Questa tabella elenca i punti del repository che oggi configurano o invocano la compilazione/build del `control-plane`, insieme all'interfaccia target proposta per unificare la UX.

Interfaccia target proposta:

```text
scripts/control-plane-build.sh <run|jar|image|native|test|print|matrix> [--profile <core|k8s|container-local|all>] [--modules <csv>] [-- <extra gradle args>]
```

Regola:

- `--profile` copre i casi comuni
- `--modules` resta l'escape hatch per build custom
- i consumer non dovrebbero più assemblare direttamente `-PcontrolPlaneModules=...`

## Configurazione centrale

| File | Ruolo attuale | Stato attuale | Interfaccia target |
| --- | --- | --- | --- |
| `settings.gradle` | Validazione selettore moduli | Legge `controlPlaneModules` e `NANOFAAS_CONTROL_PLANE_MODULES` | Delegare a una singola property/normalizzazione consumata dal wrapper |
| `control-plane/build.gradle` | Selezione moduli e default task-sensitive | Decide default diversi per `bootRun`, `bootJar`, `bootBuildImage`, `build`, `assemble` | Consumare un selettore centralizzato già risolto dal wrapper/profilo |

## Call Site Eseguibili

| File | Comando/uso attuale | Interfaccia target |
| --- | --- | --- |
| `.github/workflows/gitops.yml` | `./gradlew :control-plane:bootBuildImage -PcontrolPlaneImage=...` | `scripts/control-plane-build.sh image --profile all -- -PcontrolPlaneImage=...` |
| `scripts/build-push-images.sh` | `./gradlew :control-plane:bootBuildImage -PcontrolPlaneImage=...` | `scripts/control-plane-build.sh image --profile all -- -PcontrolPlaneImage=...` |
| `scripts/release-manager/release.py` | `./gradlew :control-plane:bootBuildImage -PcontrolPlaneImage=... -PimagePlatform=...` | `scripts/control-plane-build.sh image --profile all -- -PcontrolPlaneImage=... -PimagePlatform=...` |
| `scripts/native-build.sh` | `./gradlew :control-plane:nativeCompile` | `scripts/control-plane-build.sh native --profile all` |
| `scripts/e2e-container-local.sh` | `./gradlew :control-plane:bootJar -PcontrolPlaneModules="${CONTROL_PLANE_MODULES}"` | `scripts/control-plane-build.sh jar --profile container-local` oppure `--modules "${CONTROL_PLANE_MODULES}"` |
| `scripts/e2e-k3s-helm.sh` | `./gradlew :control-plane:bootBuildImage -PcontrolPlaneImage=... -PcontrolPlaneModules=...` | `scripts/control-plane-build.sh image --profile k8s -- -PcontrolPlaneImage=...` oppure `--modules ...` |
| `scripts/e2e-k3s-helm.sh` | `./gradlew :control-plane:bootJar -PcontrolPlaneModules=...` | `scripts/control-plane-build.sh jar --profile k8s` oppure `--modules ...` |
| `scripts/test-control-plane-module-combinations.sh` | task parametrico + `:control-plane:printSelectedControlPlaneModules` + `-PcontrolPlaneModules=...` | `scripts/control-plane-build.sh matrix --task ... --modules ...` |
| `scripts/e2e.sh` | `./gradlew -PrunE2e :control-plane:test ...` | `scripts/control-plane-build.sh test --profile all -- -PrunE2e --tests ...` |
| `scripts/e2e-buildpack.sh` | `./gradlew -PrunE2e :control-plane:test ...` | `scripts/control-plane-build.sh test --profile all -- -PrunE2e --tests ...` |
| `scripts/e2e-k8s-vm.sh` | `./gradlew -PrunE2e :control-plane:test ...` | `scripts/control-plane-build.sh test --profile k8s -- -PrunE2e --tests ...` |
| `scripts/lib/e2e-k3s-common.sh` | `./gradlew :control-plane:bootJar :function-runtime:bootJar ...` | `scripts/control-plane-build.sh jar --profile all -- --rerun-tasks` per il solo control-plane |
| `scripts/image-builder/image_builder.py` | mappa task `:control-plane:bootBuildImage` | invocare wrapper `image` invece del task raw |
| `control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/e2e/BuildpackE2eTest.java` | `ProcessBuilder` con `./gradlew :control-plane:bootBuildImage ...` | chiamare wrapper `image` dal test oppure helper Java dedicato |
| `tooling/controlplane_tui/src/controlplane_tool/adapters.py` | genera `-PcontrolPlaneModules=...`; invoca `bootJar`, `bootBuildImage`, `nativeCompile`, `test` | usare il wrapper come backend unico dell'interfaccia TUI |
| `tooling/controlplane_tui/src/controlplane_tool/control_plane_runtime.py` | `./gradlew :control-plane:bootRun --console=plain` | `scripts/control-plane-build.sh run --profile ... -- --console=plain` |

## Call Site Sperimentali

| File | Comando/uso attuale | Interfaccia target |
| --- | --- | --- |
| `experiments/e2e-runtime-config.sh` | `./gradlew :control-plane:bootJar -x test --quiet` | `scripts/control-plane-build.sh jar --profile all -- -x test --quiet` |
| `experiments/e2e-memory-ab.sh` | passa `CONTROL_PLANE_MODULES` ai runner | passare `CONTROL_PLANE_PROFILE` o `--profile` |
| `experiments/e2e-runtime-ab.sh` | passa `CONTROL_PLANE_MODULES` ai runner | passare `CONTROL_PLANE_PROFILE` o `--profile` |

## Riferimenti Documentali

| File | Riferimento attuale | Interfaccia target |
| --- | --- | --- |
| `README.md` | esempi con `:control-plane:bootRun`, `:control-plane:bootJar`, `-PcontrolPlaneModules=...` | aggiornare agli esempi del wrapper |
| `CLAUDE.md` | esempi raw Gradle e `NANOFAAS_CONTROL_PLANE_MODULES` | aggiornare agli esempi del wrapper |
| `docs/control-plane.md` | documenta `-PcontrolPlaneModules` e `NANOFAAS_CONTROL_PLANE_MODULES` | documentare profili e wrapper |
| `docs/control-plane-modules.md` | esempi `bootJar -PcontrolPlaneModules=...` | documentare `--profile` e `--modules` |
| `docs/no-k8s-profile.md` | `bootRun -PcontrolPlaneModules=container-deployment-provider` | `scripts/control-plane-build.sh run --profile container-local` |
| `docs/quickstart.md` | `./gradlew :control-plane:bootRun` | `scripts/control-plane-build.sh run --profile all` |
| `docs/tutorial-java-function.md` | `./gradlew :control-plane:bootRun` | `scripts/control-plane-build.sh run --profile all` |
| `docs/testing.md` | riferimenti a `:control-plane:test` e matrix script raw | riallineare al wrapper per `test` e `matrix` |

## Note

- I file sotto `arch_report/**` non sono inclusi perché sono output di analisi, non call site operativi.
- I file sotto `experiments/control-plane-staging/**` non sono inclusi perché sono snapshot/archivio.
- I task `:control-plane:test` restano call site rilevanti anche quando la compilazione del control-plane è implicita.
