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

## Unused Java code before pushing

Run the focused PMD checks on authored production Java sources:

```bash
./gradlew deadCode -PcontrolPlaneModules=all
```

`deadCode` covers all Java subprojects, including optional modules and the included
`platform/gradle-plugin` build. It checks unused private methods and fields, local
variables, private method/constructor parameters, and assignments whose values
are never read. Test sources and generated sources under `build/` are excluded.
The normal command reports findings without failing. Tool, parsing, compilation,
and dependency-resolution errors fail the command in both modes; the XML report
is checked for processing/configuration errors to catch incomplete analysis.
To fail on findings:

```bash
./gradlew deadCode -PcontrolPlaneModules=all -PdeadCodeStrict=true --continue
```

`--continue` lets independent modules finish and produce reports even if another
module has findings. Reports are written to each project's
`build/reports/pmd/main.html` and `main.xml`, with findings also printed to the
console. For a quicker check of a single module, use `./gradlew :common:pmdMain`;
the same strict flag applies. PMD is opt-in and is not added to `check`, `build`,
or the mandatory release gate. No Git hook is installed.

Use Java 25 and the same dependencies as a normal build: Gradle compiles sources
and resolves their classpaths for PMD's type analysis. The full inventory includes
the containerd provider, whose published libraries resolve from Maven Central
without credentials. For a build from the recorded source revisions, bootstrap
the libraries as described in the [containerd guide](deployment-containerd.md#build-from-reviewed-source-revisions).
If they were staged locally, add
`-PcontainerdMavenLocal=true -Dmaven.repo.local="$PWD/.gradle/containerd-m2"`.
Subsequent runs reuse Gradle's up-to-date checks and PMD's incremental analysis.

Findings are candidates for review, not proof that a declaration can be deleted.
PMD does not identify unused public classes or methods across the whole project.
Reflection and framework entry points require review; PMD's defaults skip
annotated private fields/classes and standard lifecycle methods, which also means
some unused declarations will not be reported. For an intentional reflective use,
add a narrow `@SuppressWarnings("PMD.UnusedPrivateMethod")` (or the corresponding
rule name) with a comment explaining the caller. The selected rules live in
`config/pmd/dead-code.xml`; the pinned version lives in `gradle.properties`.

For unused public classes and methods, run the separate ProGuard reachability
report:

```bash
./gradlew deadCodePublic -PcontrolPlaneModules=all
```

This analyzes the compiled production classes of all Java subprojects together,
so a call from a different module counts as a use. Test classes and the included
Gradle plugin build are outside this application analysis. Main methods,
Spring components and annotated callbacks, Jackson binding members, Picocli
commands, and providers listed in `META-INF/services` or Spring `.imports` files
are preserved. Public/protected SDK APIs and function handlers are entry points
because callers can live outside this repository. Rules are in
`config/proguard/dead-code-public.pro`; ProGuard's version is pinned in
`gradle.properties`. External dependencies are resolved into one common
classpath by Gradle to avoid selecting between duplicate library versions by
JAR order.

Reports are under the root `build/reports/dead-code-public/`:

- `unused.txt`: candidate classes and members, including public methods.
- `entry-points.txt`: symbols preserved explicitly by keep rules.
- `effective.pro`: the complete ProGuard configuration, for investigating a finding.

The command only writes reports: it does not produce a shrunk JAR or modify
compiled classes. Findings do not fail the task; compilation, resolution, and
analysis errors do. It is opt-in and is not attached to `check`, `build`, or Git
hooks. Gradle reuses the report when its inputs have not changed.

This is a conservative analysis of the combined application, not proof that a
symbol can be deleted. It includes candidates used only by tests, inlined
constants, and private utility constructors. Conversely, keep rules deliberately
hide some unused SDK, DTO, and framework members. Custom reflection, custom
Spring stereotypes, or named lifecycle methods need explicit keep rules; add a
narrow rule with a comment identifying the external caller. Review candidates
against the source and tests before removal. To run both checks before pushing:

```bash
./gradlew deadCode deadCodePublic -PcontrolPlaneModules=all -PdeadCodeStrict=true --continue
```

For dependency usage, the existing dependency-analysis plugin provides a separate
check:

```bash
./gradlew buildHealth -PcontrolPlaneModules=all
```

This produces `build/reports/dependency-analysis/build-health-report.txt`. Its
advice also needs review for runtime dependencies loaded by frameworks.

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

The tool gate installs k6 2.3.0 from its pinned Grafana release with SHA-256 verification. The benchmark semantic-threshold test executes `k6 inspect` and requires the actual binary; it is not skipped when the dependency is absent.

Before the scaffolder suite, the tool gate selects the Rust toolchain and fetches the SDK's locked dependencies. The generated Rust function test then runs Cargo offline, including on runners with an initially empty registry cache.
