# Native Java packaging regression

The shared native builder packages the executable together with the `.so`
libraries emitted in its native output directory. These include AWT libraries
and the Native Image JDK shims; copying libraries from an unrelated JRE would
not preserve that compiled artifact. The runtime and both container recipe
paths keep those files beside `/app/application`. Host-built recipes stage
the emitted `.so` files alongside the renamed executable too.

The QR module also carries the AWT/ImageIO reachability metadata required by
JNI. It was collected with GraalVM CE 25.2.4 / Java 25.0.4's native-image agent
while invoking real headless PNG generation at 128, 256 and 1024 pixels, then
filtered to the observed AWT, raster and ImageIO types and their exact members,
plus `System.load(String)`, which AWT's native loader invokes through JNI to
load its headless backend.
This follows the [GraalVM AWT guidance](https://www.graalvm.org/jdk25/reference-manual/native-image/overview/BuildOutput/#awt-missing-reachability-metadata-for-abstract-window-toolkit)
and [agent workflow](https://www.graalvm.org/jdk25/reference-manual/native-image/metadata/AutomaticMetadataCollection/).

The QR function exercises AWT and ImageIO after startup. A successful startup
alone does not detect missing JNI libraries. Build and invoke its packaged image:

```sh
docker buildx build --load -f tools/native-java/Dockerfile \
  --build-context containerd_maven_repo=tools/native-java/empty-maven-repo \
  --build-arg NATIVE_TASK=:functions:java:qr-code:nativeCompile \
  --build-arg NATIVE_BINARY=functions/java/qr-code/build/native/nativeCompile/qr-code \
  --build-arg 'GRADLE_ARGS=-PnativeBuildMemory=6g -PnativeParallelism=4' \
  -t nanofaas/qr-code:native-packaging .
NANOFAAS_QR_NATIVE_IMAGE=nanofaas/qr-code:native-packaging \
  uv run --python 3.12 --with pytest python -m pytest \
  scripts/tests/test_native_qr_container.py -q
```

The live test verifies HTTP status, content type, base64 encoding, PNG signature
and 256×256 dimensions. It removes its own container, including on failure.
Without `NANOFAAS_QR_NATIVE_IMAGE` this test is explicitly skipped; the ordinary
tools suite does not build a native image. NanoLab's full
`artifact-contract-container.yaml` workflow separately verifies all selected
catalog flavors, invalid inputs, real callbacks, watchdog contracts and cleanup.
