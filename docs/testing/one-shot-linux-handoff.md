# NanoFaaS one shot verification on Linux

This handoff originally recorded the checks still open after the review
corrections at
`e9829a77f54f8cd8d7dae002f37633e56b94c4d6`, on branch
`codex/one-shot-nanofaas`, as of 2026-10-05. NanoFaaS supports Linux exclusively.
The Linux verification below was completed on 2026-10-06 at
`2ef16c16c964611fe7d231869819026cb5db12c0`. macOS results are partial development
evidence; they do not establish
support for that platform or replace Linux verification.

The scope remains NanoFaaS. NanoLab and Sonata workflow work follows in phase B;
scientific Azure experiments remain a separate phase C. The detailed historical
results and failing test names are in the [phase A dossier](one-shot-phase-a.md).

## Linux closure (2026-10-06)

The tested checkout was clean at runtime commit
`2ef16c16c964611fe7d231869819026cb5db12c0`, based on merged main
`b87b721beba924a94b9c9340ab82b55f5e2cb349`. The local environment was Ubuntu
24.04.5 LTS on aarch64, with 115 GiB initially available RAM, GraalVM CE
25.2.4/Java 25.0.4, Rust 1.98.1, Python 3.12.3, uv 0.12.9, Helm 4.3.0 and
Docker 29.6.2.

| Check | Linux result |
| --- | --- |
| Complete Java tests and release checks | `test releaseChecks -PcontrolPlaneModules=all --continue` passed. Retained XML contains 2736 tests, zero failures/errors and six expected composition skips. All 233 recipe plugin tests ran without skips. |
| Containerd preparation | The prepared local Maven repository resolved the pinned dependencies; all 48 provider tests and release checks passed. The retained source/JAR receipt was checked against the three local JAR hashes. |
| Physical Rust tests | All 45 container runtime tests passed without skips, including the three real Rust physical-proxy cases using the freshly built host workload. |
| Alternate compositions | Core-only, async-queue and sync-queue gates passed separately. Mutually exclusive tests retain the expected composition skips; these runs exercise cases excluded by the all-module composition. |
| External timeout | Three real-network regressions first failed with `EXTERNAL_ERROR`, then passed with `EXTERNAL_TIMEOUT`. The 63-test dispatch/connection/cancellation gate and SpotBugs passed, followed by the full suite. Connection refusal remains `EXTERNAL_ERROR`. |
| Real one-shot processes | JVM and native gates passed in 3m52s and 3m47s. Both recorded 30 logical executions and 30 physical completions, including replay and faulted next-epoch assertions, without skipped process tests. The native gate used the executable extracted from the packaged image. |
| All-module native artifact | Compilation passed in 3m50s with an 8 GiB compiler heap and two threads. The ELF executable check and actual HTTP 404/400 management checks passed. |
| Packaged artifacts | JVM and native recipes were rebuilt from the tested commit with `dirty=false`. Both default-user packaged startup checks passed with HTTP 404/400 assertions. Image identities and assembly reports are retained below. |

The first local complete run had five CLI failures: the selected
`nanolab-heap-analysis` Buildx builder could not start its NVIDIA prestart hook
because the driver was not loaded. Setting `BUILDX_BUILDER=default` for the
verification commands resolved this environment problem. The subsequent complete
command passed; Gradle reused successful tasks from the same revision and reran
the failed CLI task. The first reports and log remain retained. Persistent Docker
configuration was not changed.

The downloaded [complete Linux CI run](https://github.com/miciav/nanofaas/actions/runs/37316494938)
passed all nine required jobs at baseline `b87b721b`, including the previously
cancelled Java compositions and JVM gate. Its retained XML includes 233 recipe
tests, 148 P2P tests and three physical Rust tests without skips. Native executable,
API and real cluster checks passed there too. Python, Rust, watchdog, Go,
JavaScript and tooling sources were unchanged by the Java timeout fix; their
evidence remains that baseline CI run. Local Java/native evidence covers
`2ef16c16`; the baseline CI run is not relabeled as testing the new fix.

The Linux race was captured as `io.netty.handler.timeout.ReadTimeoutException`
for delayed response bodies and as `WebClientRequestException` wrapping that cause
for delayed headers. The regressions hold Reactor timer workers with latches so
Netty's real response timeout wins while both production deadlines stay at
200 ms. The correction recognizes timeout causes and lets HTTP error-body
timeouts reach the same mapping. The original uncaptured macOS exception cannot
be proved identical. The historical scheduler-switch timeout did not recur in
the full Linux suite; its original cause remains unknown, with its diagnostic
timeout extension retained. An independent read-only review found no actionable
findings in the runtime/test correction.

The first ARM64 container-native attempt at baseline `b87b721b` exhausted its
4 GiB compiler heap after recorded GC thrash. The successful local recipe build
used 8 GiB and two threads and took 3m25s. This is new Linux ARM64 evidence;
the verified Linux x86_64 CI recipe setting remains 4 GiB/two threads. No CI heap
or runtime function memory setting was changed.

Durable evidence is in
[verification.json](evidence/2026-10-06-linux-one-shot/verification.json),
[the dependency receipt](evidence/2026-10-06-linux-one-shot/containerd-source-revisions.txt),
and the [JVM](evidence/2026-10-06-linux-one-shot/distribution-jvm.json) and
[native](evidence/2026-10-06-linux-one-shot/distribution-native.json) assembly reports.
The manifest includes image IDs, binary hashes, test counts, conservation results
and diagnostic log hashes. Raw CI downloads, RED/GREEN XML, complete suite reports,
failed build diagnostics and process logs remain in
`build/test-diagnostics/linux-handoff/` and the recorded offload diagnostics
directories. These are functional Linux checks, not scientific calibration or
scheduled-period qualification.

## Historical development evidence

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

## Original handoff checklist

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

### Initial timeout investigation

Before the Linux correction, `ExternalDispatcher` configured both Netty's HTTP
`responseTimeout` and Reactor's
outer `.timeout`. Its error handling explicitly mapped `TimeoutException` to
`EXTERNAL_TIMEOUT` and otherwise mapped errors to `EXTERNAL_ERROR`.

**Initially unconfirmed hypothesis:** a Netty timeout can win the race and reach the generic
error mapping. The macOS assertion failure did not establish the exception type;
connection errors or shared-suite state must also be considered. Capture the
exception and cause chain before changing production behavior. Add a failing
regression for the confirmed path, and retain tests showing that unrelated
network failures remain errors. Do not hide the failure with retries, longer
test timeouts or an assertion accepting either result.

## Linux preparation and first checks

Use a Linux checkout of the tested revision, Java 25, Rust/Cargo, Python 3.12 or newer
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

Confirm the checkout's SHA before running. For a machine with a previously
configured custom Buildx builder, select the working verification builder with
`export BUILDX_BUILDER=default`. Keep the workload environment variable set for the
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
and two build threads succeeded on the Linux x86_64 PR runner. The recorded ARM64
checks below use 8 GiB after the retained 4 GiB OOM; confirm
that the target machine has enough memory for the compiler and other processes.

```sh
docker build -f functions/rust/one-shot-workload/Dockerfile -t nanofaas-one-shot-workload .
./gradlew :control-plane-modules:offload:oneShotE2e \
  -Precipe=recipes/one-shot-local-jvm.yaml

./gradlew :control-plane:nativeCompile \
  -Precipe=recipes/one-shot-local-native.yaml \
  -PnativeParallelism=2 -PnativeBuildMemory=8g
scripts/assert-native-executable.sh platform/control-plane/build/native/nativeCompile/control-plane
./gradlew :control-plane-modules:offload:oneShotE2e \
  -Precipe=recipes/one-shot-local-native.yaml \
  -DoneShot.controlPlaneBinary="$PWD/platform/control-plane/build/native/nativeCompile/control-plane"

./gradlew assembleRecipe -Precipe=recipes/one-shot-local-jvm.yaml
./gradlew assembleRecipe -Precipe=recipes/one-shot-local-native.yaml \
  -PnativeParallelism=2 -PnativeBuildMemory=8g
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

The Linux results are recorded here and in the phase A dossier. The original PR
was already merged at baseline `b87b721b`; this local correction and verification
are on `codex/linux-one-shot-handoff`. No additional merge, NanoLab work or Azure
campaign was performed as part of this handoff.
