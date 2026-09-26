# Recipes v2, part 2: native builds inside a container

Issue: miciav/nanofaas#218. Part 1 (`docs/superpowers/specs/2026-09-25-recipes-v2-design.md`) is
merged. This spec covers part 2, the containerized native builder. Part 3 (multi-architecture
images with BuildKit provenance) builds on it, because GraalVM cannot cross-compile, and it
gets its own spec.

## Goal

A native component can be compiled inside a container instead of on the host:

- The host needs only a Docker-compatible CLI with BuildKit. No GraalVM is needed on the host.
- The container selects the GraalVM distribution itself: Community, or Oracle when the component
  asks for G1. nanolab can then express its `native-o3-g1` variant as a recipe without
  installing Oracle GraalVM on the build machine.
- Recipes keep every contract from part 1:
  - the staging directory still holds a runnable `application`;
  - images are packaged the same way;
  - the report is written the same way;
  - the checkout is not written to.

## Decisions

- **Export the executable only.** The container compiles the executable, and BuildKit exports it
  into the component's staging directory. Packaging, the report and the image ID then follow the
  existing host path unchanged.

  Rejected alternatives:
  - A single multi-stage build producing the final image: it would leave no executable in the
    staging directory, and the control plane's `config/recipe.yaml` would have to be injected
    into the builder.
  - `docker run` with the checkout mounted: it would write `build/` into the checkout.
- **Reuse `deploy/native-java/Dockerfile`**, which the release and nanolab already use, so every
  path shares one builder and one toolchain.
- **Cache Gradle across container builds** with a BuildKit cache mount in that shared builder.
- **G1 implies Oracle GraalVM (GFTC licence).** This is the rule `scripts/native-java-image.sh`
  already applies. Writing `gc: G1` is the explicit choice, and the report records the
  distribution that was used.

## Recipe format

```yaml
controlPlane:
  modules: [build-metadata]
  build:
    mode: native
    builder: container            # host (default) | container
    variant: native-o3-g1
    native: {gc: G1}
services:
  - {name: warm-echo, sdk: java, build: {mode: native, builder: container}, container: {image: warm-echo}}
```

### Rules

- **Where `builder` is allowed.** On the `build` of every Java component (control plane, Java
  functions, Java services), and only with `mode: native`. Like `native`, it is a validation
  error with `mode: jvm`.
- **v2 only.** It exists only in `recipe-v2.schema.json`; the v1 schema stays frozen.
- **Default.** `host`: today's behaviour, unchanged.
- **Per component.** Each component chooses independently, so one recipe can mix builders.
- **Distribution.** With `container`, the builder installs GraalVM Community, or Oracle GraalVM
  when the component's effective `gc` is `G1`. With `host`, whatever GraalVM the host provides
  is used, as today.
- **Host check.** "a native image needs a Linux host" does not apply to `container` components,
  since the builder is Linux. On macOS or Windows a recipe can therefore produce native images,
  provided every native component with an image uses `builder: container`.
- **Builder sizing.** `-PnativeBuildMemory` and `-PnativeParallelism` are passed into the
  container build too. native-image sizes itself from the memory it can see, so this matters as
  much there. Docker resource limits are not the recipe's concern.
- **Requirements.**
  - `builder: container` needs a Docker-compatible CLI with BuildKit: Docker 23 or newer by
    default, or podman through `-PrecipeDocker`.
  - It uses `--target`, `--output type=local`, `--build-context` and `RUN --mount=type=cache`.
  - A builder without these features fails with its own error.
- **Preview.** For a `container` component, `validateRecipe` shows
  `docker build --target native-executable … (container builder, community|oracle)` instead of
  the Gradle task. It still needs no Docker daemon.

## Execution

### Changes to `deploy/native-java/Dockerfile`

- The Gradle `RUN` gains `--mount=type=cache,target=/root/.gradle`. Dependencies and the Gradle
  distribution persist in the BuildKit cache between builds. Gradle's cache is safe under
  concurrent processes, and the produced image does not change.
- A new stage, placed before the final one:

  ```dockerfile
  FROM scratch AS native-executable
  COPY --from=builder /tmp/application /application
  ```

- The final stage remains last, so the default target, which the release and
  `scripts/native-java-image.sh` use, is unchanged.

### The container build task

For each component with `builder: container`, the recipe registers an `Exec` task in place of
that component's host `nativeCompile`. It runs from the repository root:

```
docker build -f deploy/native-java/Dockerfile --target native-executable
  --output type=local,dest=<output>/<staging-dir>
  --build-context containerd_maven_repo=<staged Maven repository | deploy/native-java/empty-maven-repo>
  --build-arg NATIVE_TASK=<:project:nativeCompile>
  --build-arg NATIVE_BINARY=<that task's output file, relative to the repository>
  --build-arg GRAALVM_DISTRIBUTION=<community | oracle>
  --build-arg GRADLE_ARGS=<arguments below>
  <repository root>
```

- **Ordering.** The task runs after `stageRecipe`, whose `Sync` would otherwise delete the
  exported executable because it does not copy it. It runs before the component's image build.
- **No writes to the checkout.** The executable lands in the staging directory. The build
  context is the repository, and the root `.dockerignore` excludes `.git`, `.gradle` and
  `build/`.
- **Staged files survive the export.** The `local` exporter must leave the control plane's staged
  `config/recipe.yaml` in place. If BuildKit clears the destination, export to a temporary
  directory next to the staging directory and move `application` into place.
- **Environment.** The task runs with `DOCKER_BUILDKIT=1`, which is harmless for Docker 23+ and
  for podman.
- **Failure.** A failing build fails its task, like the image tasks. The output marker from part
  1 allows a retry in the same directory.

### `GRADLE_ARGS`

The arguments are computed per component from the existing model, by a pure function so they can
be unit-tested. The container build runs without `-Precipe`: the recipe file may live outside the
build context, and the component's options are passed as the plain `-P` flags the build scripts
read.

- **Every component:**
  - `-PnanofaasBuildType=native`;
  - the component project's native flags, from `RecipeBuildProperties.byProject`
    (`nativeOptimization`, `nativeGc`, `nativeMonitoring`);
  - `-PnativeBuildMemory` and `-PnativeParallelism`, when the invocation sets them.
- **The control plane, in addition:**
  - `-PcontrolPlaneModules=<comma-separated modules>`, or `none` when the list is empty, because a
    blank selector falls back to the defaults;
  - the build-metadata flags `nanofaasBuildVariant` and `nanofaasBuildOptimization`, when the
    recipe records an identity;
  - `-PnanofaasBuildRevision=<revision>` and `-PnanofaasBuildDirty=<true|false>`, computed on the
    host, because the build context has no `.git`. They are omitted when Git is unavailable, as
    in `scripts/native-java-image.sh`.
- **When `controlPlane.modules` contains `containerd-deployment-provider`:**
  - add `-PcontainerdMavenLocal=true -Dmaven.repo.local=/tmp/containerd-m2`;
  - point the `containerd_maven_repo` build context at the host's `-Dmaven.repo.local`;
  - without `-PcontainerdMavenLocal` and `-Dmaven.repo.local` on the invocation, the recipe
    fails before any build and names the missing properties.

## Report

A native component's `native` object gains two fields:

- `builder`: `host` or `container`;
- `distribution`: `community` or `oracle`, recorded only for `container`. With `host`, the
  plugin does not know which GraalVM the host provides, and does not guess.

Part 3's provenance will reuse these fields. `RecipeOutput`'s report validation does not
constrain `native`, so reports written before this change remain valid ownership evidence.

## Errors

The style is unchanged, and errors are raised before any build unless stated.

- `builder` with `mode: jvm`, or an unknown value: schema error.
- containerd modules without the staged Maven repository properties: named error.
- A failing container build fails its task (execution error), as a failing image build does.

## Testing

- **`RecipeReaderTest`:** `builder` is accepted only with `mode: native`, and only as `host` or
  `container`.
- **Unit tests of the argument function:**
  - control plane with modules, with `[]` (`none`), with containerd, with identity, and with
    revision and dirty state;
  - a function with G1 gets `oracle`;
  - sizing flags pass through;
  - no identity or revision flags for functions and services.
- **`RecipePluginTest`**, where the fake Docker honours `--output type=local,dest=…` by writing
  `application`:
  - the exact `docker build --target native-executable` command;
  - the component's host `nativeCompile` does not run;
  - `application` is staged, and the control plane's `config/recipe.yaml` survives;
  - the image is packaged with `Dockerfile.native`;
  - the report records `builder` and `distribution`;
  - the host check is skipped for `container` components;
  - host and container builders can be mixed in one recipe;
  - the containerd properties error is raised.
- **`scripts/tests`:** `deploy/native-java/Dockerfile` defines the `native-executable` stage and
  keeps the final stage last.
- **Manual end-to-end:**
  - Run a recipe with a native control plane on `builder: container` with `gc: G1`. The image must
    start, report Oracle GraalVM's G1 collector, and serve `/modules/build-metadata` with its
    identity.
  - Run a second assembly and record how much time the Gradle cache saves.

## Documentation

The "Native builds" section of `docs/recipes.md` covers:

- the two builders and when to choose `container`;
- that G1 implies Oracle GraalVM, and its licence;
- the BuildKit requirement and the cache;
- that non-Linux hosts need `builder: container` for native images.

## Out of scope

- Other platforms (`registry.platforms`), provenance and per-platform digests: part 3.
- Docker resource limits for the builder.
- Detecting the host GraalVM distribution for `builder: host`.
