# Forecasting WASM validation

Implementation in progress, base aba820d4. No merge/push.

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
