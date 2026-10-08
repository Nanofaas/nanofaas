# Distribution recipes

A recipe is a versioned YAML file that says which control plane to build — its modules and
JVM or native mode — and which function implementations to package with it. The
`it.unimib.datai.nanofaas.control-plane-modules` settings plugin reads it, builds the
artifacts with the existing Gradle tasks, and builds and pushes images with the Docker CLI.
A recipe never starts the control plane, registers functions, or deploys anything.

[`recipes/local-demo.yaml`](../recipes/local-demo.yaml) is a runnable example.

## The four commands

```bash
./gradlew listRecipeFunctions                                     # catalog, no recipe needed
./gradlew validateRecipe -Precipe=recipes/local-demo.yaml         # validation + preview, builds nothing
./gradlew assembleRecipe -Precipe=recipes/local-demo.yaml         # artifacts + images, no push
./gradlew publishRecipe  -Precipe=recipes/local-demo.yaml         # assemble everything, then push
```

- `listRecipeFunctions` prints every implementation in this checkout: name, SDK, source
  directory and the modes it supports. A Java mode is listed only when its task exists
  (`bootJar` or `installDist` for `jvm`, `nativeCompile` for `native`).
- `validateRecipe` checks the recipe and prints the selected modules and, for each
  component, the build task, the staging directory and the full image reference. It needs
  no Docker daemon, GraalVM, or registry credentials.
- `assembleRecipe` deletes `build/recipes/<name>/`, builds the selected artifacts and
  images, and writes `distribution.json`. It never pushes.
- `publishRecipe` requires a `registry` section and at least one image, which is checked
  before anything is built. It runs the whole assembly first and only then pushes.

Catalog, preview and build use the same resolver, so the preview shows exactly what the
build uses.

## Recipe format

The current schema is
[`recipe-v2.schema.json`](../tools/gradle-plugin/src/main/resources/recipes/recipe-v2.schema.json).
The plugin picks the schema that the file's `schemaVersion` names, validates against the
copy packaged with it, and never downloads a schema. A `schemaVersion: 1` file is validated
against the frozen
[`recipe-v1.schema.json`](../tools/gradle-plugin/src/main/resources/recipes/recipe-v1.schema.json)
and keeps working unchanged; it cannot use the fields v2 adds. Add this line at the top of a
recipe to get completion and checks in editors that use the YAML language server:

```yaml
# yaml-language-server: $schema=../tools/gradle-plugin/src/main/resources/recipes/recipe-v2.schema.json
```

```yaml
schemaVersion: 2
name: demo                         # lowercase words joined by '-'

registry:                          # optional; required by publishRecipe
  repository: ghcr.io/my-org       # host[:port][/path], no scheme
  tag: "1.0.0"
  platforms: [linux/amd64, linux/arm64]   # optional; selects docker buildx (see Multi-architecture images)
  provenance: true                 # optional, needs platforms; BuildKit provenance, mode=max

controlPlane:
  modules: [async-queue, container-deployment-provider, build-metadata]   # exact selection; [] = core only
  build:
    mode: native                   # jvm | native, required
    builder: container             # host (default) | container; see "Native builds"
    variant: native-os             # optional build identity; needs build-metadata
    native: {optimization: s}      # native mode only; see "Native options"
  jvm: {args: [...]}               # jvm mode only
  container: {image: control-plane}                       # optional
  config:                          # optional Spring configuration
    nanofaas: {metrics: {profile: basic}}

functions:                         # optional
  - name: word-stats
    sdk: java-lite                 # java | java-lite | python | javascript | go | bash
    build: {mode: native}          # required for java and java-lite, forbidden otherwise
    container: {image: word-stats-java-lite}   # required for the Dockerfile SDKs

services:                          # optional; see "Services"
  - {name: warm-echo, sdk: java, build: {mode: native}, container: {image: warm-echo}}
  - {name: watchdog, sdk: dockerfile, container: {image: watchdog}}
```

- `controlPlane.modules` is the exact module list: no `all` or `none`, and no hidden
  optional modules. The usual module constraints still apply. With `-Precipe`,
  `-PcontrolPlaneModules` is rejected and `NANOFAAS_CONTROL_PLANE_MODULES` is ignored.
- Each function selects source code that already exists. `java` uses
  `functions/java/<name>` (depending on `:sdks:java`), `java-lite` uses
  `functions/java/<name>-lite` (depending on `:sdks:java-lite`), and the Dockerfile SDKs
  (`python`, `javascript`, `go` and `bash`) use `functions/<sdk>/<name>/Dockerfile`. The
  same function can appear once per SDK. Two entries with the same name and SDK are rejected,
  and so are two images with the same reference, across functions and services.
- `container.image` is a plain name. The image reference is
  `<registry.repository>/<image>:<tag>`, or `nanofaas/<recipe>/<image>:local` when the
  recipe has no `registry`. The base images are the repository's current ones and cannot
  be overridden.
- Errors name the file, the field and the reason, for example
  `recipe.yaml: functions[0]: python implementation of figlet is not available`.
  Unknown fields, duplicate YAML keys, several documents, and values JSON cannot hold
  (dates, binary, anchor cycles) are rejected.

## CI tag override

```bash
./gradlew publishRecipe -Precipe=recipes/demo.yaml -PrecipeTag=1.0.1
```

`-PrecipeTag` replaces the tag of every image. It requires a `registry` section, must be a
valid container tag, and is used in the preview, the image builds, the pushes and the
report alike. The YAML file is not modified. There are no other overrides.

## Native options

`build.native` sets the native-image options of one component: the control plane, a Java
function or a Java service. It is allowed only with `mode: native`.

| Field | Values | Default | native-image flag |
| --- | --- | --- | --- |
| `optimization` | `"s"`, `"0"`, `"1"`, `"2"`, `"3"` | `"3"` | `-O<value>` |
| `gc` | `serial`, `G1` | `serial` | `--gc=<value>` |
| `monitoring` | `all`, `heapdump`, `jfr`, `jvmstat`, `jmxserver`, `jmxclient`, `threaddump`, `nmt`, `jcmd` | none | `--enable-monitoring=<list>` |

- `G1` adds `jfr` to `monitoring`, because G1 has no usable GC MXBeans. The report lists the
  effective values.
- Numeric optimizations are normalised: `3`, `3.0` and `3e0` all produce `-O3` and the report
  value `"3"`. A non-integral value such as `3.5` is rejected.
- `G1` needs Oracle GraalVM on the host, since Community offers only `serial` and `epsilon`.
  The plugin does not check the distribution; native-image fails with its own message.
- Two native components in one recipe can use different options.

With `-Precipe`, the recipe owns these choices, so these flags are rejected:
`-PnativeOptimization`, `-PnativeGc`, `-PnativeMonitoring`, `-PnanofaasBuildVariant` and
`-PnanofaasBuildOptimization`. Flags that only size or feed the builder remain accepted:
`-PnativeBuildMemory`, `-PnativeParallelism`, `-PcontainerdMavenLocal` and
`-Dmaven.repo.local`.

## Build identity

`controlPlane.build.variant` is a label written to `META-INF/nanofaas-build.properties`, which
`/modules/build-metadata` serves. Soak and comparison evidence uses it to prove which build
ran. The optimization recorded next to it is derived, never declared:

- native: `native.optimization`, default `3`;
- JVM: `c1` when the last `-XX:TieredStopAtLevel=` in `jvm.args` is `1`, otherwise `c2`.

The optimization is recorded when `variant` is set or `native.optimization` is given
explicitly. Either one requires `build-metadata` in `controlPlane.modules`. Module selection
stays exact, so the plugin fails before building rather than adding the module. Native
options on functions and services do not need it.

## Services

`services` lists components that are neither the control plane nor functions, with the same
shape as `functions`:

- `sdk: java` uses `services/java/<name>` (depending on `:sdks:java`). It needs `build`,
  accepts `jvm` and `native`, and is staged and built like a Java function.
- `sdk: dockerfile` uses `runtimes/<name>/Dockerfile`. It needs `container` and forbids
  `build` and `jvm`. Its own directory is the build context, unlike Dockerfile functions,
  which build from the repository root.

A Java service's mode is independent of the control plane's, Spring AOT included: a JVM
control plane can ship with a native warm-echo, and the reverse. `listRecipeFunctions` lists
services in a separate section.

## Native builds

With the default `host` builder, a `native` component compiles with `nativeCompile`, which
needs a local GraalVM (see `scripts/native-build.sh` for the release this repository pins).
JVM components, `builder: container` components and `validateRecipe` do not need it. Each
component's mode is independent: a native function leaves a `jvm` control plane on the JVM,
without Spring AOT. With a `jvm` control plane in the recipe, requesting native tasks directly
(`nativeCompile -Precipe=...`) and passing a contradicting `-PnanofaasBuildType` both fail.
Build through `assembleRecipe` instead.

`build.builder` chooses where a native component compiles:

- `host` (the default): `nativeCompile` on this machine, with its GraalVM. The executable is
  copied as-is into a Linux image, so the host must be Linux on the image's architecture, and
  its glibc must not be newer than Debian 13's (the runtime image is `distroless/cc-debian13`).
- `container`: inside the builder of
  [`tools/native-java/Dockerfile`](../tools/native-java/Dockerfile), the one the release uses.
  The host needs only a Docker-compatible CLI with BuildKit (Docker 23+, or podman through
  `-PrecipeDocker`), so it also works on macOS and Windows. The builder installs GraalVM
  Community, or Oracle GraalVM when the component's `gc` is `G1`: Community has no G1. Oracle
  GraalVM is GFTC-licensed, not GPL, so asking for G1 is also a licensing choice. The report
  records the distribution. Only the executable comes back
  (`docker build --target native-executable --output type=local,...`), into the component's
  staging directory. From there the image is packaged exactly as for `host`. Gradle's
  dependencies stay in a BuildKit cache between builds. `-PnativeBuildMemory` and
  `-PnativeParallelism` are passed into the builder. With the containerd module, the builder
  needs the staged repository: `-PcontainerdMavenLocal=true -Dmaven.repo.local=<dir>`.

In native, Spring decides which components exist while the executable is built (Spring AOT),
not at startup. For example, the runtime-config admin API needs
`nanofaas.admin.runtime-config.enabled=true`, the soak gauges need
`nanofaas.metrics.profile=soak`, and each image validator needs its `default-backend`.

- **What the build reads.** A native control plane's build hands `controlPlane.config` to that
  step. The executable therefore contains the components the recipe's configuration selects,
  as it would on the JVM.
- **Changing the configuration after the build.** Values change, but the components do not.
  Environment variables at run time cannot switch a component on or off either. Assemble again.
- **Without a recipe.** Pass the same file to a direct build:
  `./gradlew :control-plane:nativeCompile -PnanofaasAotConfig=<file>`. A relative path resolves
  against the repository root. A missing file fails the build.
- **The published native image** is built with the defaults: its runtime-config admin API is
  off. Build your own image for other choices.
- **In the container builder**, the configuration travels as the build argument
  `NATIVE_AOT_CONFIG`, so it also appears in the image's BuildKit provenance. The image already
  ships it as `config/recipe.yaml`.

## Multi-architecture images

```yaml
registry:
  repository: ghcr.io/my-org
  tag: "1.0.0"
  platforms: [linux/amd64, linux/arm64]
  provenance: true
```

- **The buildx path.** With `registry.platforms`, even with one platform, every image is built
  with `docker buildx build --platform <list>`. `provenance: true` attaches BuildKit provenance
  with `mode=max`. Without it, images carry none (`--provenance=false`). Without `platforms`,
  everything works as described above.
- **Assembly keeps no local image.** A multi-architecture image cannot live in the classic
  Docker image store, and provenance does not survive `--load`. So `assembleRecipe` builds every
  platform into the builder's cache only, and `publishRecipe` repeats each build with `--push`.
  The repeated build comes from that cache, so it takes a fraction of the first.
- **The builder.** `-PrecipeBuilder=<name>` selects the buildx builder; without it, the current
  one is used. Before anything is emptied or built, the `checkRecipeBuilder` task starts the
  builder and requires `docker buildx inspect --bootstrap` to list every platform.
  - A builder reaches another architecture through QEMU emulation
    (`docker run --privileged --rm tonistiigi/binfmt --install all`) or through a node running on
    it (`docker buildx create --append`).
  - The default `docker` driver cannot build several platforms at once; use a
    `docker-container` builder.
  - This path needs `docker buildx`: podman is not supported here.
- **Native components.**
  - With `builder: container` and an image, the component is compiled and packaged in one build,
    by the `recipe-native` stage of `tools/native-java/Dockerfile`, once per platform. The
    image's provenance therefore names the GraalVM builder stage. Its staging directory holds
    only the runtime files, with no `application`.
  - With `builder: host`, the executable has the host's architecture, so `platforms` must be
    exactly the host's platform.
  - native-image under QEMU emulation is very slow: in practice, each architecture needs a
    native node.
- **Other images.** JVM images package the same jar for every platform. Dockerfile components
  build from their own Dockerfile, and their `RUN` steps need emulation or a native node.
- **Signing stays outside the recipe.** nanolab and the release sign the digests the report
  records.

## Output and runtime configuration

```
build/recipes/<name>/
  distribution.json
  control-plane/                 app.jar | application, jvm.options, launch.args, config/recipe.yaml
  functions/<sdk>/<name>/        Java functions only: app.jar | lib/ | application, jvm.options, launch.args
```

The directory is regenerated on every assembly. Gradle's incremental compilation and
Docker's layer cache are what make repeated assemblies fast. Java images are built from
the staged directory with [`tools/gradle-plugin/dockerfiles/Dockerfile.jvm`](../tools/gradle-plugin/dockerfiles/Dockerfile.jvm)
or [`Dockerfile.native`](../tools/gradle-plugin/dockerfiles/Dockerfile.native). Python, JavaScript, Go and bash
images use the function's own Dockerfile with the repository as the build context; a
`dockerfile` service uses its own Dockerfile with its own directory as the context.

`-PrecipeOutput=<dir>` puts the staging tree and `distribution.json` elsewhere, for example
outside a read-only checkout. A relative path resolves against the repository root, like
`-Precipe`. Because an assembly empties the directory, the plugin only takes one that:

- does not exist;
- is empty;
- contains a valid `.nanofaas-recipe-output` marker; or
- contains a complete `distribution.json` from an earlier assembly, and nothing but assembly
  output (`control-plane/`, `functions/`, `services/`). A report copied into a directory of
  other files does not count.

The repository and its ancestors are always refused, even through a symbolic link, and so is a
regular file. The check runs before anything is deleted, and also guards `cleanRecipe` and
`stageRecipe` when they are called directly or when `cleanRecipe` is excluded with `-x`. The
marker is written as soon as the directory is claimed and survives a failed assembly, so a
retry can reuse the same directory. It does not claim that any artifact is usable: only
`distribution.json`, written last, does.

The marker is new. A default `build/recipes/<name>/` left by an assembly that failed before
this change has neither a marker nor a report, so it is refused. Delete it once, and later
assemblies mark it.

Keep `-PrecipeOutput` outside the repository, or under a `build/` directory. The repository is
the build context of every image that builds from it: Dockerfile functions, and the container
builder's `native-executable` and `recipe-native` builds. The root `.dockerignore` hides
`build/` directories but not an output placed elsewhere in the checkout, so such an output is
sent to the builder with each of those builds and changes their cache key.

`jvm.options` and `launch.args` are standard JVM argument files, with one quoted argument
per line. Spaces, quotes and backslashes are kept literally. `jvm.options` starts with the
control plane's fixed flags (`-XX:MaxRAMPercentage=70`, `-Xss256k`, ...), followed by the
recipe's `jvm.args`. The JVM keeps the last value it sees, so the recipe can override any
fixed flag. When `jvm.args` is absent, the fixed flags are followed by the default tuning,
which for the control plane is `-XX:+UseSerialGC`, so explicit options never add a second
collector. `launch.args` holds only the entry point. `jvm.args` applies to the
built component, not to Gradle or GraalVM.

`config/recipe.yaml` is `controlPlane.config`, loaded through Spring's
`spring.config.additional-location`. It adds to the packaged `application.yml`, and
environment variables still override it. Values that affect Spring AOT are build-time
choices and are not reconfigurable here. To run a staged component without a container:

```bash
cd build/recipes/demo/control-plane
SPRING_CONFIG_ADDITIONALLOCATION=optional:file:config/recipe.yaml \
  java @jvm.options @launch.args                                    # JVM (Boot jar or java-lite classpath)
SPRING_CONFIG_ADDITIONALLOCATION=optional:file:config/recipe.yaml ./application   # native
```

The images set the same variable to `/app/config/recipe.yaml`.

## The report

`distribution.json` has `schemaVersion: 2` for every recipe, v1 included. It records:

- the recipe's name and the SHA-256 of the recipe file
- the effective tag
- the Git revision and dirty state (`"source": null` when Git or the repository is unavailable)
- the resolved modules
- each component's kind (`control-plane`, `function` or `service`), SDK, mode, staging
  directory and image
- on the control plane, `variant` and `optimization` whenever the build metadata records them
- on each native component, `native` with the effective `optimization`, `gc` and `monitoring`,
  plus `builder` and, for the container builder, `distribution`
- for each image, `id`: the local image ID from `docker image inspect`, recorded after the
  build and kept after a push. A built image that is never pushed can still be pinned. If the
  inspect fails, the assembly fails.
- on the buildx path, instead of `id`: `platforms` and `provenance` for each image, and after
  publication `digest` (the multi-architecture index, or the manifest itself for one platform
  without provenance) and `manifests`, mapping each platform to its manifest digest. Nothing
  else distinguishes the two paths: a consumer checks for `platforms`.

Version 2 only adds fields: a reader of the version 1 fields sees no difference.

It contains neither the runtime configuration nor environment variables. After a push, an
image's `status` is one of:

| Status | Meaning |
| --- | --- |
| `built` | Built by the last assembly, not pushed |
| `published` | Pushed; `digest` is the registry digest (`docker push`'s manifest digest, the matching `RepoDigests` entry, or on the buildx path the pushed index), never the local image ID |
| `published-unverified` | The push succeeded but the digest, or on the buildx path one platform's manifest, could not be determined; the task fails |
| `failed` | The push failed; the task fails and names the images already published |

Images are pushed one at a time, in order, and the report is rewritten after each push by
writing a temporary file and moving it into place. A later failure therefore never hides
an earlier success. There is no retry and no rollback: a registry has no transaction
across images. A new assembly discards all publication data.

The Docker CLI and its configured credentials are used as they are. On the buildx path,
`-PrecipeBuilder=<name>` selects the builder. `-PrecipeDocker=<path>` selects another
Docker-compatible executable. Every command runs with separate arguments, never through a
shell.

## Running several variants

A recipe describes one control plane. To compare variants built from the same source, run
one `assembleRecipe` per variant, each with its own `-PrecipeOutput`. Gradle and Docker
caching keep the repeats cheap. The recipe's default JVM tuning, `-XX:+UseSerialGC` with full
tiering, is the same as the default of `platform/control-plane/Dockerfile`.

Variants that share an image name and tag also share the local image tag: each assembly
retags the image, so the last one wins. Give each variant its own tag (`-PrecipeTag`, which
needs a `registry` section) or its own `container.image`. Otherwise, pin each build by the
`image.id` in its own `distribution.json`.
