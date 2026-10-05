# NanoFaaS one shot verification on Linux

This handoff records the checks still open after the review corrections at
`e9829a77f54f8cd8d7dae002f37633e56b94c4d6`, on branch
`codex/one-shot-nanofaas`, as of 2026-10-05. NanoFaaS supports Linux exclusively.
Complete the remaining verification on Linux before treating phase A as fully
validated. macOS results are partial development evidence; they do not establish
support for that platform or replace Linux verification.

The scope remains NanoFaaS. NanoLab and Sonata workflow work follows in phase B;
scientific Azure experiments remain a separate phase C. The detailed historical
results and failing test names are in the [phase A dossier](one-shot-phase-a.md).

## Current evidence

The five review findings were reproduced and corrected in TDD: the missing CI
`jsonschema` dependency, P2P permit release ordering, false handler attribution,
Rust retained-history lookup overhead and invalid OpenAPI numeric constraints.
The same correction pass fixed the Sonar workload manifest list and ambiguous
autoscaler test matchers. Recorded checks passed 258 Python, 103 Rust and 368
Java tests in the affected components, plus SpotBugs, Clippy and host-native
compilation and startup checks. These counts describe those recorded runs, not
a successful complete Linux suite at the latest revision.

Earlier Linux recipe and packaged-image checks in the dossier precede the latest
runtime corrections. Their image identities must not be reused as evidence for
the current branch. Temporary logs from the macOS session are not portable;
generate new reports on Linux and retain them with the tested commit SHA.

## Problems and checks still open

| Item | Observed result | Work on Linux and closure condition |
| --- | --- | --- |
| Full recipe plugin suite | 19 cases failed in the macOS root run because fixtures use Linux tools such as `sha256sum` and host-native packaging requires Linux. | Run the complete suite on Linux without exclusions. Treat any remaining Linux failures as new evidence to investigate. No macOS portability work is required. |
| Containerd dependency preparation | The root run could not resolve `io.nanofaas:containerd-java-cni:0.23.0`; the bootstrap repository was not configured for that run. | Build the pinned source revisions with the existing bootstrap script and select its Maven repository explicitly. Require successful dependency resolution, tests and release checks; retain the source/JAR receipt. |
| External timeout classification | `ExternalDispatcherTimeoutTest.dispatch_slowServer_returnsPoolTimeout` returned `EXTERNAL_ERROR` instead of `EXTERNAL_TIMEOUT` in the broad run, then passed in an isolated class rerun. | Reproduce under Linux, capture the actual exception and fix the confirmed cause in TDD. Verify both isolated and full-suite execution. A successful rerun alone does not close an intermittent failure. |
| Current Linux runtime artifacts | The latest correction pass rebuilt a macOS executable and the Rust workload, but did not rebuild and verify the Linux packaged images. | Rebuild JVM/native recipe images from the current commit, run startup smoke checks and the real one-shot process gates, and retain reports and image identities. |
| Native compiler memory | The macOS compilation exhausted a 4 GiB heap, but the first Linux PR run successfully compiled the one-shot recipe with 4 GiB and two threads. | Keep the verified Linux setting; no CI heap increase is justified by that run. Reassess only if a Linux build provides new evidence of insufficient memory. Compiler heap is separate from runtime function memory. |

### First Linux PR run and CI corrections

The [first PR CI run](https://github.com/miciav/nanofaas/actions/runs/37279269873)
tested `4d21c7bca21bd6cc753b06c8c5dd62fdfc2b25fe` on Linux. The containerd
bootstrap succeeded, all 233 recipe plugin tests passed without skips, and both
native compilations succeeded. Python, Rust, watchdog, Go, JavaScript, tooling
and CodeQL jobs passed. The Java and native cluster jobs failed for these reasons:

- `ReplicaPlanActuatorTest.transitionBetweenMemorySaturatingFunctionsNeverAllocatesBothAtMaximum`
  returned `FAILED` instead of `DEGRADED`. A deterministic new subscriber-chain
  regression reproduced `preparation already active`: `doFinally` released the
  preparation gate after delivering its result. Eager `Mono.using` cleanup now
  releases it before delivery, while retaining cancellation cleanup.
- `OneShotLocalClusterE2eTest.fullAuctionReadinessRoutingAndFaultedNextEpoch`
  received registration HTTP 503 instead of 201. The native Docker client could
  not construct `GraphData` inside the image inspection response. A failing
  native-hints regression confirmed the missing constructor/field metadata;
  the existing Docker DTO registrar now includes this model.
- `SchedulerSwitchInvocationEquivalenceTest.syncWaiterAndAsyncPollingObserveTheSameResultAcrossTwoStrategySwitches`
  timed out on the first synchronous invocation after switching to shared-queue.
  It passed locally and in 30 diagnostic repetitions. Its cause remains unknown;
  the test now uses the existing timeout extension to capture a thread dump if
  it recurs, without increasing its timeout or accepting an unsuccessful result.

The two confirmed fixes were verified with 84 provider and 87 offload tests,
zero failures/errors/skips, plus both modules' SpotBugs checks. Those development
runs are not a replacement for the corrected PR's Linux CI rerun. The release
gate failed because the preceding jobs failed; it was not a fourth independent
cause. The earlier `ExternalDispatcherTimeoutTest` failure did not recur in
this first Linux run and remains a separate historical intermittent observation.

The [second Linux CI run](https://github.com/miciav/nanofaas/actions/runs/37295410026)
tested the fixes at `e2801a343e965c8a4873ecb830640a17335f69ab`. Its complete
`./gradlew test --continue` run passed in 20m11s; `releaseChecks` passed in 4m55s
and the subsequent P2P gate passed. The job then exceeded its total 30-minute
limit during the core-only checks. This was a job-budget cancellation, not an
observed failing assertion; later compositions and the JVM cluster gate were
not verified by that job. `test-java` now has a 60-minute budget, matching the
native job. Test timeouts and assertions remain unchanged. The corrected budget
still requires a complete CI run; the scheduler timeout's cause is not declared
fixed merely because it passed in this Linux suite.

The same run's `test-native-artifact` job passed in 38m35s, including both native
compilations, executable/API checks and the real native one-shot cluster. This
verifies the Docker image-registration fix on Linux. Its recipe compilation used
4 GiB and two threads. Retained Java XML reports show 84 provider, 87 offload,
45 physical runtime, 148 P2P and 233 recipe plugin tests with no failures, errors
or skips. New Linux packaged-image assembly/startup identities and completion
of the cancelled Java job's remaining gates are still required.

### Timeout investigation

`ExternalDispatcher` configures both Netty's HTTP `responseTimeout` and Reactor's
outer `.timeout`. Its error handling explicitly maps `TimeoutException` to
`EXTERNAL_TIMEOUT` and otherwise maps errors to `EXTERNAL_ERROR`.

**Unconfirmed hypothesis:** a Netty timeout can win the race and reach the generic
error mapping. The macOS assertion failure did not establish the exception type;
connection errors or shared-suite state must also be considered. Capture the
exception and cause chain before changing production behavior. Add a failing
regression for the confirmed path, and retain tests showing that unrelated
network failures remain errors. Do not hide the failure with retries, longer
test timeouts or an assertion accepting either result.

## Linux preparation and first checks

Use a Linux checkout of this branch, Java 25, Rust/Cargo, Python 3.12 or newer
with `uv`, Git, Helm and a working Docker daemon/context. Native builds require
the GraalVM release pinned in `gradle.properties`; the setup in
[the CI workflow](../../.github/workflows/gitops.yml) is the reference. Record
the commit, OS/architecture, tool versions, available RAM and compiler settings.

The following commands run from the repository root. Clone the two dependency
repositories once; if already present, use those checkouts after confirming the
pinned commits are available. The bootstrap script verifies and stages the exact
revisions from `deploy/containerd-rootless/dependencies.env`.

```sh
git fetch origin
git switch --track origin/codex/one-shot-nanofaas
git rev-parse HEAD

mkdir -p .gradle/ci-sources
git clone https://github.com/Nanofaas/libcni-java.git .gradle/ci-sources/libcni-java
git clone https://github.com/Nanofaas/containerd-java.git .gradle/ci-sources/containerd-java
bash scripts/bootstrap-containerd-dependencies.sh \
  .gradle/ci-sources/libcni-java .gradle/ci-sources/containerd-java

cargo build --locked --release --manifest-path functions/rust/one-shot-workload/Cargo.toml
export NANOFAAS_ONE_SHOT_WORKLOAD_BINARY="$PWD/functions/rust/one-shot-workload/target/release/one-shot-workload"

./gradlew :control-plane:test -PcontrolPlaneModules=all \
  -PcontainerdMavenLocal=true -Dmaven.repo.local="$PWD/.gradle/containerd-m2" \
  --tests '*ExternalDispatcherTimeoutTest' --rerun-tasks

./gradlew test releaseChecks -PcontrolPlaneModules=all --continue \
  -PcontainerdMavenLocal=true -Dmaven.repo.local="$PWD/.gradle/containerd-m2"
```

If the local branch already exists, switch to it and update it with a fast-forward
instead of creating it again. Keep the workload environment variable set for the
Java physical-proxy tests; check their XML reports to ensure none were skipped.
Preserve failing diagnostics before rerunning tests, because Gradle may replace
the previous reports. Repeat the isolated timeout case and run it with the wider
control-plane suite when investigating intermittency.

Run the remaining CI gates, including Python contracts, Rust checks and the
separate core-only/queue compositions, using the workflow's commands. The full
all-module suite does not replace checks of those different compositions.

## One shot processes and packaged artifacts

Start with the JVM process gate. Under the pinned GraalVM `JAVA_HOME`, compile
the native composition and run its process gate too. The 4 GiB compiler heap
and two build threads below succeeded on the first Linux PR runner; confirm
that the target machine has enough memory for the compiler and other processes.

```sh
docker build -f functions/rust/one-shot-workload/Dockerfile -t nanofaas-one-shot-workload .
./gradlew :control-plane-modules:offload:oneShotE2e \
  -Precipe=recipes/one-shot-local-jvm.yaml

./gradlew :control-plane:nativeCompile \
  -Precipe=recipes/one-shot-local-native.yaml \
  -PnativeParallelism=2 -PnativeBuildMemory=4g
scripts/assert-native-executable.sh platform/control-plane/build/native/nativeCompile/control-plane
./gradlew :control-plane-modules:offload:oneShotE2e \
  -Precipe=recipes/one-shot-local-native.yaml \
  -DoneShot.controlPlaneBinary="$PWD/platform/control-plane/build/native/nativeCompile/control-plane"

./gradlew assembleRecipe -Precipe=recipes/one-shot-local-jvm.yaml
./gradlew assembleRecipe -Precipe=recipes/one-shot-local-native.yaml \
  -PnativeParallelism=2 -PnativeBuildMemory=4g
python3 scripts/one-shot/smoke_packaged.py nanofaas/one-shot-local-jvm/control-plane-one-shot-jvm:local
python3 scripts/one-shot/smoke_packaged.py nanofaas/one-shot-local-native/control-plane-one-shot-native:local
```

Also run the all-module native executable/API checks from CI with the prepared
containerd repository. Record recipe assembly reports and image identities.
The process gates and packaged startup checks cover different boundaries; neither
substitutes for the other. These are functional diagnostics, not service-time
calibration, auction-period qualification or scientific experiments.

## Evidence required before closing this handoff

- A complete Linux test/release run, including all recipe cases and no skipped
  physical-proxy tests, with the tested SHA and dependency bootstrap receipt.
- A reproduced and corrected timeout cause, or an explicit unresolved entry with
  the captured diagnostics; do not declare it fixed from isolated success alone.
- Current JVM/native process-gate results, native executable/API checks and
  packaged-image startup results with their artifact identities.
- Recorded Linux build memory settings; the first PR run already validates
  4 GiB/two threads for compilation, without establishing cluster correctness.

Update this document and the phase A dossier with the Linux results. The PR is
kept in draft while these checks remain open. No merge, NanoLab work or Azure
campaign is part of this handoff.
