# build-metadata

Optional control-plane module: a small diagnostics endpoint exposing what this
control-plane binary was built from and what it is currently running on.

## Provides

- `BuildMetadataController` — `GET /modules/build-metadata` returns build/
  runtime metadata; useful to verify which build is actually running during
  deploys and E2E runs.

Every field is best-effort: a value absent at build or runtime is reported as
JSON `null`, never a sentinel string, and missing diagnostics never prevent
startup. Response shape is documented in `openapi.yaml` in this directory
(`BuildMetadataResponse`), which is folded into the composed `/openapi.yaml`
only when this module is selected.

```json
{
  "version": "0.22.0",
  "revision": "b0df4d5cf3a2e9d1c8a7f6e5d4c3b2a1908f7e6d",
  "dirty": false,
  "modules": ["async-queue", "build-metadata", "k8s-deployment-provider"],
  "build": {
    "type": "native",
    "variant": "native-o3",
    "optimization": "3",
    "baseImages": {
      "builder": "oraclelinux:9-slim",
      "runtime": "gcr.io/distroless/cc-debian13:nonroot"
    }
  },
  "runtime": {
    "architecture": "arm64",
    "kernelVersion": "6.5.0-generic",
    "javaVersion": "25",
    "vm": "Substrate VM",
    "garbageCollectors": ["G1 Old Generation", "G1 Young Generation"]
  }
}
```

### Field sourcing

- `revision` — the full 40-character git SHA at build time, not a short hash.
- `dirty` — whether `git status --porcelain --untracked-files=no` was
  non-empty at build time; untracked files are ignored, so a build next to
  scratch files still reports `dirty: false`.
- `modules` — the sorted list of control-plane modules selected for this
  build (`nanofaasSelectedControlPlaneModules`).
- `build.type` — read from the generated properties when present; only when
  the properties carry no `type` at all does the module fall back to
  detecting `org.graalvm.nativeimage.imagecode` at runtime and report
  `"native"`. A `type` present in the properties always wins over that
  fallback.
- `build.variant` / `build.optimization` — whatever the build passed via
  `-PnanofaasBuildVariant=...` / `-PnanofaasBuildOptimization=...` (or
  `-PnativeOptimization=...`); absent unless supplied.
- `build.baseImages` — read at runtime from the `NANOFAAS_BUILD_BASE_IMAGE`
  and `NANOFAAS_RUNTIME_BASE_IMAGE` environment variables. The JVM and native
  Dockerfiles set these `ENV` entries from their `BUILDER_IMAGE`/
  `RUNTIME_IMAGE` build args, so an image built with an overridden base
  reports the base it actually used, not a hardcoded default. Base-image
  *digests* are out of scope — only the image reference is reported.
- `runtime.architecture` — `os.arch` normalized to exactly `arm64` or
  `x86_64`; any other value (including one this module doesn't recognize) is
  `null`, never passed through raw.
- `runtime.vm` — `java.vm.name` verbatim; this is the only source for it
  (native-image detection feeds `build.type`, not this field).
- `runtime.garbageCollectors` — sorted alphabetically, like `modules` above.
- Raw JVM arguments are never exposed anywhere in this response — they may
  carry secrets.

## Configuration

None.
