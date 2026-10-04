# Testing

Run Java tests with `./gradlew test`. Run the nanolab workspace tests from the
separate [nanolab](https://github.com/miciav/nanolab) checkout:

```bash
cd ../nanolab && uv run --locked --all-packages --all-groups pytest -c packages/nanolab/pyproject.toml packages/nanolab/tests
```

Plan tests do not need Docker or a VM:

```bash
export NANOFAAS_ROOT="$(pwd)"
cd ../nanolab
./nanolab.sh plan packages/nanolab/scenarios-v2/deployment-lifecycle-container.yaml
./nanolab.sh plan packages/nanolab/scenarios-v2/deployment-lifecycle-k8s.yaml \
  --environment packages/nanolab/environments/multipass.yaml
./nanolab.sh plan packages/nanolab/scenarios-v2/persistent-recovery-container.yaml
./nanolab.sh plan packages/nanolab/scenarios-v2/persistent-recovery-k8s.yaml \
  --environment packages/nanolab/environments/multipass.yaml
./nanolab.sh plan packages/nanolab/scenarios-v2/loadtest.yaml \
  --environment packages/nanolab/environments/external.yaml.example
```

Actual container validation needs Docker. With `--provision`, Kubernetes validation
can prepare Multipass, Azure, or Proxmox VMs; an external SSH host remains
user-managed. Load tests additionally need provider credentials and network access.
Managed VMs are deleted after the run unless `--keep` is set.

The lifecycle scenarios deliberately start clean and remove state at teardown.
The persistent-recovery scenarios keep it only across their internal
control-plane restart, where they verify function adoption and replica recovery;
they then use the same normal teardown.

Use `--only`, `--from`, and `--until` to isolate tasks. Use `--keep` only when infrastructure must remain available for investigation.

## Watchdog

The watchdog has Rust unit tests and local integration tests for HTTP, STDIO, FILE, callback, metrics, and warm lifecycle behavior. They require Rust, Python 3, `jq`, `curl`, and `nc`; Docker and a VM are not required.

```bash
cargo test --manifest-path runtimes/watchdog/Cargo.toml
bash runtimes/watchdog/test-local.sh

# Focused suites while debugging
bash runtimes/watchdog/test-local.sh --http
bash runtimes/watchdog/test-local.sh --stdio
bash runtimes/watchdog/test-local.sh --file
bash runtimes/watchdog/test-local.sh --callback
```

## Mandatory release checks

CI exposes the aggregate `release-gate` status. It succeeds only when every
verification job succeeds; cancelled, skipped and failed jobs block the gate.
Repository administrators must select **release-gate** in the branch protection
or ruleset's required status checks. Adding a workflow does not configure branch
protection by itself.

Run these checks from a clean checkout. Use Java 25 for the Gradle launcher as
well as its toolchain; the included Gradle plugin requires it. The complete module
composition also requires the pinned containerd dependencies: follow the
bootstrap steps in `.github/workflows/gitops.yml` and set
`-PcontainerdMavenLocal=true` when using the locally built artifacts.

| Check | Command (repository root unless indicated) | Requirements | Reports |
| --- | --- | --- | --- |
| Versions and static analysis | `./gradlew releaseChecks -PcontrolPlaneModules=all --continue` | Java 25, pinned containerd artifacts | `**/build/reports/spotbugs/` |
| Java and included build | `./gradlew test --continue` | Java 25, Docker for integration tests | `**/build/test-results/test/` |
| P2P composition | `./gradlew :control-plane-modules:p2p-discovery:test -PcontrolPlaneModules=all` | Same Java prerequisites | Gradle XML |
| Core-only API | `./gradlew :control-plane:test -PcontrolPlaneModules=none --tests '*CoreOnlyApiTest' --tests '*P07ConfiguredHttpCalibrationTest'` | Java 25 | Gradle XML |
| Async queue | `./gradlew :control-plane-modules:concurrency-control:test -PcontrolPlaneModules=async-queue,concurrency-control,runtime-config` | Java 25 | Gradle XML |
| Sync queue | `./gradlew :control-plane-modules:concurrency-control:test :control-plane-modules:sync-queue:test -PcontrolPlaneModules=sync-queue,concurrency-control,runtime-config` | Java 25 | Gradle XML |
| Scripts and experiments | `uv run --python 3.12 --with pytest --with pyyaml python -m pytest scripts/tests experiments/tests sdks/runtime-contract -ra --junitxml=build/test-results/tools.xml` | uv, Python 3.12, Helm, Node, Bash | XML and skip summary |
| Scaffolder | `uv run --python 3.12 --project tools/fn-init --group dev python -m pytest tools/fn-init/tests -ra --junitxml=build/test-results/scaffolder.xml` | uv, Python 3.12; fn-init's declared dependencies | XML and skip summary |

The SDK and watchdog jobs retain their language-specific tests. The native job
retains the pinned GraalVM build and executable-artifact assertion. Python tooling
checks run locally without provisioning infrastructure; NanoLab owns actual
campaign and VM execution. When #240 transfers experiments, transfer their gate
to NanoLab and update the commands together.

SpotBugs failures are blocking. `config/spotbugs/exclude.xml` contains narrowly
scoped, documented false positives. Any temporary debt baseline must identify the
exact bug/class/method and a follow-up; new findings must never be hidden by a
package-wide filter or `ignoreFailures`. Error Prone remains advisory in this
increment. Reports and `-ra` expose skipped tests; a successful aggregate status
is not evidence that a deliberately conditional test ran in every composition.

The native job bootstraps the same pinned containerd dependencies before compilation, asserts executable kind and runs `python3 scripts/assert-native-api-errors.py platform/control-plane/build/native/nativeCompile/control-plane`. This launches the real binary and checks missing-function 404 and registration-validation 400 bodies over HTTP, with finite startup and cleanup deadlines. It requires no benchmark framework at runtime or in CI.

`releaseChecks` analyzes the full project graph, including optional modules, even when a control-plane test selects `none`. Use the complete-composition dependency bootstrap for this gate; module flags select application composition, not a reduced static-analysis inventory. Java corpus files and their validator are explicit Gradle test inputs, so fixture-only edits rerun conformance tests.

The reproducible reference environment is Linux, Node 24, Go 1.24 and Java 25. macOS's IPv4/IPv6 bind semantics can invalidate the Go bind-failure test; host-built native recipe tests require Linux. Node 26 also changes the existing shutdown test behavior. Run the exact CI toolchains/container environment before classifying these as production regressions; do not suppress the tests. Runtime conformance differences and the Java-lite TCP ingress gap are listed in the [runtime contract](../sdks/runtime-contract/README.md).
