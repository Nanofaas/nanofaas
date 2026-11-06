# Forecasting WASM validation

Implementation on `codex/forecasting-wasm`, base aba820d4. No merge/push.

## Embedding gate

GraalVM Community 25.2.4 / Java 25.0.4, Polyglot and GraalWasm 25.2.4. The old wasm-community artifact is deprecated in this release; `org.graalvm.polyglot:wasm` resolves wasm-language and truffle-runtime. GraalWasm POM license: UPL; runtime dependencies retain their own published licenses.

JVM probe and Linux ARM64 Native Image probe both return 42. Native binary executes successfully with `-Xmx64m`. The native build includes 3661 runtime-compiled Truffle methods, not the interpreter-only fallback. Probe image size: 120929727 bytes (includes distroless base); this is not full control-plane RSS.

Reproduce:

```sh
./gradlew -PcontrolPlaneModules=forecasting,forecasting-wasm :control-plane-modules:forecasting-wasm:test :control-plane-modules:forecasting-wasm:prepareEmbeddingProbe
docker build --build-context probe=platform/modules/forecasting-wasm/build/embedding-probe -f docs/experiments/forecasting-wasm-2026-11-06/Dockerfile.probe -t nanofaas/wasm-embedding-probe:local .
docker run --rm nanofaas/wasm-embedding-probe:local -Xmx64m
```

The standalone probe deliberately excludes Spring's test classpath; Native Image cannot directly snapshot Netty's Logback logger initialization without the application's Spring AOT configuration. The full control-plane build is a separate required check.

## Full application validation and measurement

The production native recipe builds successfully in the existing Linux container
builder without Python or Cargo, and starts with a 64 MiB heap. The experiment
entry point in `src/` starts that same `ControlPlaneApplication`, registers a LOCAL
echo function, sends HTTP invocations across several windows, obtains forecasts
from the application context and switches providers through the existing runtime
configuration HTTP API. It adds no production endpoint. `benchmark.gradle` is an
explicit experiment-only init script: the normal artifact never includes this main.

The provider-free coordinator currently owns an empty generation registry, so its
observation lifecycle cannot initialize LOCAL windows. The client seeds observation
through the context using the real capacity generation; HTTP arrivals then populate
that history. This is an explicit limitation of the smoke, not a fix for automatic
LOCAL discovery. Runtime-config smoke and array parity still exercise the actual
guest. Included-runtime/Java mode creates the runtime but never invokes the guest.

```sh
python3 docs/experiments/forecasting-wasm-2026-11-06/benchmark.py build
python3 docs/experiments/forecasting-wasm-2026-11-06/benchmark.py run
```

The build creates JVM/native images with and without WASM, using pinned repository
versions and the existing native Dockerfile. The run performs three repetitions of
Java-only, runtime-included/Java-selected, and WASM-selected on each execution mode,
sequentially, with four CPU quota and `-Xmx64m`. No cloud machines are provisioned.
All runtime properties are provided identically; the native AOT configuration also
enables forecasting and the admin API. Normal recipes alongside this report use the
ordinary entry point and can be built with `./gradlew -Precipe=PATH assembleRecipe`.

Raw output is written to `build/wasm-benchmark`: build logs and image metadata, host /
Docker information, revision and working diff hash, guest manifest, per-run logs,
JSON events, process RSS samples and exit state. The harness asserts nine microbench
shapes, successful exit and a context-close marker. The process must then terminate
naturally, which also checks that no non-daemon guest thread keeps it alive.

Each run separates application startup and the first 360/60 calculation from the
warm 60/360/4096 history × 1/60/4096 steps matrix (200 warmups, 300 measured calls,
p50/p95). `first_shape_ns` is only the first call for that shape in the existing
runtime, not a fresh runtime. One maximum-size call checks limits. A context-level
forecast benchmark runs against simultaneous observation at approximately 1000/s;
it measures snapshot plus calculation on the short HTTP-populated history, not the
360-sample numeric microbenchmark. A separate sustained 360/60 loop runs for 60 s
after 1000 warmups and consumes every result through a volatile checksum. These are
local exploratory measurements, not JMH confidence intervals or an invocation
throughput benchmark.

RSS is sampled from `docker top -eo pid,rss,comm`, once per second, independently
from the heap maximum reported inside the application. It includes process native
allocations and code; Docker cache accounting is not substituted for RSS. Startup
is measured inside the entry point, excluding container creation. Instrumented image
sizes and timings include the test client and HTTP client, so do not equate them
with minimal production image footprint. Build timings include cached dependencies
and are not clean-download build timings.

The alternate native entry point explicitly selects `ControlPlaneApplication` and
includes experiment-only reflection metadata for its generated AOT initializer.
This is needed because its `main` is different from the normal Spring Boot main;
the production recipe does not need this extra metadata.

Validation suite: `./gradlew -PcontrolPlaneModules=all test --continue` observed
2761 tests, 2753 passed, 6 skipped, and only the two pre-existing macOS
`WarmEchoAotTest` failures (mixed JVM control plane/native warm-echo recipe tests).
The forecasting API/module/WASM suites and artifact-manifest self-check pass.
Three module-inventory assertions were updated for the new module; the architecture
rule still forbids sibling implementation dependencies. Spring auto-configuration
ordering uses `beforeName`, so the adapter depends only on the numeric API contract.

### Execution-mode caveat

The standard Distroless JVM command in these recipes does **not** enable JVMCI.
GraalWasm explicitly reports an interpreter-only fallback on that JVM. Its measured
WASM latency therefore describes the default JVM deployment, not an optimized
GraalVM JVM or a JVMCI-enabled deployment. Do not use these numbers to conclude that
optimized JVM GraalWasm has the same cost. The native build includes 3671
runtime-compiled Truffle methods and does not issue the interpreter-only warning.
Both modes execute the real Rust artifact and pass the same numeric assertions.

## Results — 6 November 2026

All 18 runs passed numeric assertions, maximum-size calls, context smoke and
natural shutdown; no container OOM or heap increase. Apple M3 Pro, Linux ARM64
containers, four CPU quota, Serial GC and 64 MiB heap. Values below are medians
of the three independent runs; RSS is the median of their sampled peaks. p50/p95
refer to history=360, steps=60; throughput is the separate 60-second numeric loop.

| Runtime | Composition / algorithm | Startup ms | Peak RSS MiB | p50 µs | p95 µs | Numeric calls/s |
|---|---|---:|---:|---:|---:|---:|
| jvm | Java only | 1433.8 | 229.1 | 2.92 | 3.00 | 461,004 |
| jvm | Runtime included / JAVA | 1629.5 | 247.0 | 2.67 | 2.88 | 461,230 |
| jvm | Runtime included / WASM | 1614.5 | 254.0 | 169.50 | 180.21 | 5,865 |
| native | Java only | 69.9 | 91.6 | 3.38 | 3.42 | 437,231 |
| native | Runtime included / JAVA | 73.4 | 104.0 | 2.96 | 3.00 | 437,110 |
| native | Runtime included / WASM | 68.1 | 187.0 | 7.67 | 11.88 | 110,353 |

The JVM WASM row is **interpreter-only**, as explained above. Native WASM is
functional within the tested heap budget but uses more RSS and is slower than
Java for this small algorithm. Keep the provider **experimental and opt-in**;
retain Java Holt as the normal choice. This demonstrates a non-JVM module inside
a native NanoFaaS process, not a performance improvement or a generic plugin API.

Instrumented image sizes: JVM Java 360.7 MiB, JVM with WASM 387.0 MiB; native
Java 182.2 MiB, native with WASM 223.2 MiB. Native build elapsed times with cached
dependencies were 117.7 s and 166.2 s, respectively. Native compiler-reported total
file sizes were 92.44 MiB and 121.82 MiB. The earlier ordinary production recipe
(without the experimental entry point) also built and started successfully.

[Versioned measurement data](results.json) contains every run's events, per-run
percentile summaries, one-second RSS samples, image IDs, source and guest hashes.
Individual per-call timings are not retained. Complete build/runtime logs and
container image metadata remain in `build/wasm-benchmark`. No x86-64 or cloud run
was performed, and the small fixed-order local experiment is not a statistical
performance guarantee.

## Final review

Fresh read-only review of `aba820d4..1b33ddc3` found no Critical or Important
production defects. Two measurement refinements are deferred: the concurrent
probe measures forecast latency but does not establish actual arrival overlap or
measure arrival latency; the latch-based unit test separately verifies that arrival
recording is not blocked by guest calculation. `cold_call.ns` includes Random,
stream and input-array setup, so it is not an isolated cold algorithm latency.
The published warm comparison table is unaffected.

Scope decisions confirmed by review: automatic LOCAL discovery remains a known
pre-existing limitation, so the test seeds observation; arbitrary replacement
guests are unsupported, so this is not an untrusted-code isolation guarantee;
x86-64, optimized JVMCI, cloud and production performance remain unmeasured.
These limits must be revisited before using this experiment as evidence for those
deployments. The module remains experimental and opt-in.
