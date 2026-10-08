# Audit remediation validation — 2026-10-08

Branch: `work/audit-remediation`, base `fed1be9c`.
Plan: [audit remediation](../../../superpowers/plans/2026-10-08-audit-remediation.md).
These are local diagnostic gates, not Azure calibration or Kubernetes infrastructure validation.

## Runtime and packaging evidence

- Python SDK and shared runtime contracts: 203 tests and 11 subtests passed.
- Python 3.12 `word-stats` image built with runtime dependencies only; HTTPX imports, pytest and Requests absent; real callback preserved execution, trace and dispatch identity. Reproduce with `python sdks/python/tests/runtime_image_smoke.py IMAGE` after building its Dockerfile.
- Java SDK: 153 tests passed after isolating the real-dispatcher test dependency from server auto-configuration. Gradle resolves the producer's compile output and explicit libraries; no personal agent or extracted classpath is required.
- Script gates: 28 tests passed, including actual Docker/Gradle argument stubs for default/local/invalid repositories and paths with spaces.
- Plugin gates: `RecipeContainerBuildTest` and `RecipePluginTest` passed, including native export and multiarch repository propagation.
- Containerd `bootJar` from Central passed. Both native container images built at 4 GiB / two compiler threads, using the empty Maven context for Central and the bootstrap output for explicit local sources.
- Central image: `sha256:23e5961e7acae5aa96aafa722a633b1d089881362d520e3a59e9f614999f462c`.
- Local image: `sha256:4848d2901f968042380a293cdebb0332925fae7c53411fb08ce34be7f47a3ab7`.
- [Source receipt](containerd-source-revisions.txt): bootstrap used the current pinned release commits; all three JAR hashes verified. The input checkouts were read through git archive, not modified.

## Real-process JVM/native gates

After the final review fixes, the complete JVM and native gates each passed all three tests with zero skipped cases. JVM took 4m57s; native took 4m35s. The tests exercise the original three-edge/cloud cluster, automatic windows after genuine qualification, and the HTTP artifact contract:

```bash
./gradlew :control-plane-modules:offload:oneShotE2e -Precipe=recipes/one-shot-local-jvm.yaml
./gradlew :control-plane:nativeCompile -Precipe=recipes/one-shot-local-native.yaml -PnativeParallelism=2 -PnativeBuildMemory=6g
./gradlew :control-plane-modules:offload:oneShotE2e -Precipe=recipes/one-shot-local-native.yaml -DoneShot.controlPlaneBinary="$PWD/platform/control-plane/build/native/nativeCompile/control-plane"
```

The required initial 4 GiB one-shot compile failed with Java heap exhaustion during code compilation (exit 3). The same recipe and parallelism passed at 6 GiB. The builder bound changed only for this validation; 4 GiB is not claimed to pass.

Native executable: AArch64 ELF, SHA-256 `f02b8d4f50c95f68deabed045302062ffdd032f4d64cb4061d8dc123f898f4f0`.

- [JVM automatic plans](automatic-jvm-plans.json) and [native automatic plans](automatic-native-plans.json): twenty complete real preparations precede scheduled mode; epochs 21 and 22 have exactly contiguous windows. A changed anchor returns 409 and preserves revision 3. Zero demand isolates scheduling; the original cluster case covers physical routing and failure handling.
- [Native HTTP contract](native-http-contract.json): short waiter receives 408, completion and replay receive the marked function's 202 with string `hello`, the execution ID is retained, one physical endpoint call executes, and `waiterTimedOut` never appears on the wire.
- Composed OpenAPI contains caller 408 and no public internal waiter flag. New schedule helper records and ownership exceptions do not become public serialized DTOs.

## Integrated gates

`BUILDX_BUILDER=default ./gradlew test --continue` passed in the final serial run: 2569 cases in 457 current module reports, zero failures/errors, 2560 executed and nine conditional skips. The skips are three core-only cases (require module selector `none`), three async-provider governor cases (default composition selects sync), and three opt-in real Rust proxy cases. All new audit regressions and native artifact cases execute. Plugin task 8 separately passes 88 selected tests.

`./gradlew deadCode deadCodePublic` passed after the serial global suite: 35 PMD reports, zero violations, and an empty public-unused list. The final Python/contract gate again passed 203 tests and 11 subtests; script gates passed 28 tests.

The initial global attempt was invalidated by overlapping Gradle builds rewriting a JAR during scanning; SDK metrics context failures separately identified and drove the test-classpath correction. The later serial run is the global evidence. [Independent whole-branch review](review.md) found three Important defects and no Critical, Minor or declined findings. All three received one fix pass with deterministic RED→GREEN regressions; [six final regression results](review-regressions.json) include cancellation after subscription. Both JVM and rebuilt-native artifact gates then passed 3/3; all new audit regressions execute. No findings are deferred. The containerd-only packaging inputs are unchanged by this final offload/container-local correction.

All nine tasks are complete. The final recorded task-9 gate reran the offload, container-local, containerd and common deployment-runtime suites successfully (1m06s). The [full progress ledger](progress.md) preserves completion ranges, RED→GREEN evidence and every ruling before temporary workflow cleanup. Branch and worktree are retained for the integration decision.
