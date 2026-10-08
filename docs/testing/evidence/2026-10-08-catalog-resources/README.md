# Resource catalog restart regression — 2026-10-08

Issue: [#247](https://github.com/Nanofaas/nanofaas/issues/247).
Base: `a3722a471b92c67cb2cb335995d2fdc4742f3f7d`; branch `fix/catalog-resources`.

## Cause and correction

Jackson treats `ResourceSpec.isRequestWithinLimit()` as a JSON getter. The
application enables `FAIL_ON_UNKNOWN_PROPERTIES`, so the resulting catalog
cannot be loaded again. Existing catalog tests used Jackson's permissive default
and omitted explicit resources, concealing the failed round trip.

The getter now has `@JsonIgnore`; its `@AssertTrue` constraint remains active.
Requests and limits remain serialized. The known legacy property is ignored on
read, independently of its stored boolean value; validation is recomputed from
actual resource quantities. No schema migration or manual catalog edit is needed.
The next mutation rewrites the catalog without the derived property. Other
unknown resource fields are still rejected. The common library explicitly
declares the Jackson annotations dependency, using the existing Spring BOM.

## Verification

- Strict regression RED: three failures, including the exact reported
  `UnrecognizedPropertyException` during registry restart. See
  [red-results.json](red-results.json).
- GREEN: all 11 catalog tests and seven registry persistence tests pass, including
  legacy recovery, rewrite, invalid-limit rejection and unknown-field rejection.
- Full default-composition `BUILDX_BUILDER=default ./gradlew test --continue`:
  2,808 cases, 2,799 executed, zero failures/errors, nine conditional skips;
  elapsed 10m09s. Counts and all skipped test names are in
  [java-suite-results.json](java-suite-results.json). Skips cover core-only,
  async-provider governor and opt-in Rust proxy cases. Compilation/runtime
  warnings and Gradle deprecation warnings remain; the run exited successfully.
- Real JVM process: registration 201 and successful invocation before restart,
  after restart and after injecting the legacy property into the retained catalog;
  resource values preserved. Invalid resource limits and unknown fields return
  400. See [jvm-restart-results.json](jvm-restart-results.json).
- Real GraalVM native process: the same restart and legacy recovery smoke passed,
  including unchanged resources and both 400 rejection checks. The executable
  includes `k8s-deployment-provider,async-queue,runtime-config`, matching the
  reported module selection; build took 3m42s with 10 GiB heap and two compiler
  threads. See [native-restart-results.json](native-restart-results.json) for
  binary SHA-256 and toolchain provenance. An initial 6 GiB build was interrupted
  after sustained full GC (480 collections, over 320s measured GC time); it is
  not claimed to have passed.
- GitNexus impact reports CRITICAL risk for the shared DTO. The change analysis
  used the explicit correction worktree and the current main index, with neither
  partial nor truncated results. See [graph-changes.json](graph-changes.json).

The HTTP smoke uses `LOCAL` execution to isolate catalog persistence from
infrastructure. It does not claim verification of managed deployment recovery on
k3s, Helm/PVC behavior or provider resource reconciliation. Kubernetes E2E remains
owned by NanoLab.

## Reproduce

From the repository root:

```bash
./gradlew :control-plane:test --tests '*FunctionCatalogTest' --tests '*FunctionRegistryPersistenceTest'
BUILDX_BUILDER=default ./gradlew test --continue
python3 docs/testing/evidence/2026-10-08-catalog-resources/restart_smoke.py platform/control-plane/build/libs/app.jar
./gradlew :control-plane:nativeCompile -PcontrolPlaneModules=k8s-deployment-provider,async-queue,runtime-config -PnativeParallelism=2 -PnativeBuildMemory=10g
python3 docs/testing/evidence/2026-10-08-catalog-resources/restart_smoke.py platform/control-plane/build/native/nativeCompile/control-plane
```

The JVM smoke was run on the default-composition JAR before switching to the
three-module native composition. Build that composition's artifact before each
corresponding smoke; do not overlap Gradle runs that write the same artifacts.
