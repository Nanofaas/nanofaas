# Recipes v2, part 1: declarative build options, services and relocatable output

Issue: miciav/nanofaas#218. This spec covers the first of three parts:

1. **This spec:** schema v2 with native options, build identity, services and bash
   functions, the local image ID, and `-PrecipeOutput`. Everything still builds on the host.
2. **Later:** native builds inside a container (`builder: container`).
3. **Later, after 2:** multi-architecture images with BuildKit provenance. GraalVM cannot
   cross-compile, so a native image for another platform needs the containerized builder.

Each part gets its own spec, plan and pull request.

## Goal

nanolab (validate, loadtest, soak, comparison, release) builds nanoFaaS in five places, each
spelling out Gradle task names, Dockerfile paths and `-P` properties. The goal is for nanolab
to write a recipe per run, call `assembleRecipe`/`publishRecipe`, and read
`distribution.json` without knowing any of them.

After this part, every host-built artifact nanolab produces today can be described by a
recipe:

- the native variants `-Os`, `-O3` and `-O3` with G1;
- the build identity that soak and comparison evidence records;
- warm-echo, the watchdog and the bash functions;
- images that are built but not pushed, pinned by their ID.

A v1 recipe keeps working unchanged. Recipes still never deploy, register functions, run
tests or sign images (see `docs/recipes.md`).

## Answers to the issue's open questions

- **One control plane per recipe (question 1).** It stays that way. A comparison run with
  nine variants runs nine `assembleRecipe` calls back to back, each with its own
  `-PrecipeOutput`. Gradle's incremental compilation and Docker's layer cache make the
  repeats cheap. Allowing several control planes per recipe would complicate the schema,
  the preview and the report for no gain.
- **`jvm.args` against the Dockerfile's `JVM_TUNING` (question 2).** There is no longer a
  discrepancy. Since `7d6be8a0`, `platform/control-plane/Dockerfile` defaults to
  `-XX:+UseSerialGC` with full tiering, which is also the recipe's default. Migrating nanolab
  to recipes does not move its baseline.
- **Where multi-arch lives (question 3).** Deferred to part 3.
- **Builder-sizing flags.** `-PnativeBuildMemory`, `-PnativeParallelism`,
  `-PcontainerdMavenLocal` and `-Dmaven.repo.local` size or feed the builder without changing
  the artifact. They remain accepted alongside `-Precipe`, and a test pins that.

## Recipe format v2

```yaml
schemaVersion: 2
name: comparison-native-o3-g1

registry:
  repository: 127.0.0.1:5000/nanofaas
  tag: "run-42"

controlPlane:
  modules: [async-queue, k8s-deployment-provider]
  build:
    mode: native
    variant: native-o3-g1          # optional; control plane only
    native:                        # native mode only
      optimization: "3"            # "s" | "0" | "1" | "2" | "3"; default "3"
      gc: serial                   # serial | G1; default serial
      monitoring: [jfr, jvmstat]   # optional
  container: {image: control-plane}

functions:
  - {name: word-stats, sdk: bash, container: {image: word-stats-bash}}

services:
  - {name: warm-echo, sdk: java, build: {mode: native}, container: {image: warm-echo}}
  - {name: watchdog, sdk: dockerfile, container: {image: watchdog}}
```

### Rules

- **`build.native`** is allowed only with `mode: native`, on every Java component: the
  control plane, Java functions and Java services.
  - It maps onto the flags the root `build.gradle` already applies: `-O<optimization>`,
    `--gc=<gc>` and `--enable-monitoring=<list>`.
  - The existing rule that G1 adds `jfr` stays where it is.
  - Omitted fields use today's defaults: `-O3`, the serial collector, no monitoring.
- **`build.variant`** is allowed only on the control plane, the only component that writes
  `META-INF/nanofaas-build.properties`.
  - Its value is a slug.
  - The optimization recorded in the metadata is derived, never declared. A native build
    records `native.optimization`, default `3`. A JVM build records `c1` when the effective
    `jvm.options` last sets `-XX:TieredStopAtLevel=1`, and `c2` otherwise. This is the rule
    nanolab's `jvm_optimization` applies today.
  - The optimization is recorded when `variant` is set or `native.optimization` is given
    explicitly. That matches today, where `-PnativeOptimization` alone already records it.
    Otherwise both stay absent, as they are without `-P` flags.
- **`services`** has the same shape as `functions`, with two SDKs:
  - `java`: sources in `services/java/<name>`, depending on `:sdks:java`. It needs `build`,
    and accepts `jvm` and `native` like a Java function.
  - `dockerfile`: `runtimes/<name>/Dockerfile`. It needs `container` and forbids `build` and
    `jvm`.
- **`functions[].sdk: bash`** uses `functions/bash/<name>/Dockerfile`, like `python`,
  `javascript` and `go`: `container` is required, `build` and `jvm` are forbidden.
- **Uniqueness** still covers images across all components. Two entries with the same name
  and SDK are rejected within `functions` and within `services`.
- **Recipe-owned `-P` flags.** With `-Precipe`, the following are rejected before any build,
  like `-PcontrolPlaneModules` today:
  - `-PnativeOptimization`, `-PnativeGc` and `-PnativeMonitoring`;
  - `-PnanofaasBuildVariant` and `-PnanofaasBuildOptimization`.

  The error names the recipe field that replaces the flag.
- **`gc: G1` on the host** needs Oracle GraalVM installed there, since Community offers only
  `serial` and `epsilon`. The plugin does not detect the distribution: `native-image` fails
  with its own message, and `docs/recipes.md` says so. Part 2's containerized builder will
  select the distribution itself.

## Reading and compatibility

- **Schema dispatch.** `RecipeReader` reads `schemaVersion` first and validates against
  `recipe-v1.schema.json`, which stays frozen byte for byte, or `recipe-v2.schema.json`.
  Any other value fails with a message naming the supported versions.
- **Normalisation.** A validated v1 document is normalised to the v2 model: no
  `build.native`, no `variant`, no `services`. Catalog, preview, build and report handle
  only the v2 model.
- **v1 behaviour.**
  - A v1 recipe builds the same artifacts and images, with the same build commands, as
    today. The only extra command is the `docker image inspect` after each image build.
  - A v1 file that uses a v2 field is still rejected, because the v1 schema does not allow
    it.
  - Its report gains the additive fields described below.

## How the options reach the build

The settings plugin already registers a `beforeProject` hook. With a recipe, the hook sets
extra properties on the target project. These are the properties the build scripts already
read through `project.findProperty`, so the root `build.gradle` and
`platform/modules/build-metadata/build.gradle` stay unchanged.

| Recipe field | Project | Extra properties |
| --- | --- | --- |
| `controlPlane.build.native` | `:control-plane` | `nativeOptimization`, `nativeGc`, `nativeMonitoring` |
| `functions[].build.native` | `:functions:java:<name>` or `<name>-lite` | the same |
| `services[].build.native` (java) | `:services:java:<name>` | the same |
| `controlPlane.build.variant`; the derived optimization (see Rules) | `:control-plane-modules:build-metadata` | `nanofaasBuildVariant`, `nanofaasBuildOptimization` |

Only fields the recipe sets become extra properties, so the build scripts keep their
defaults. Two native components in one recipe can use different options within the same
build.

## Services and catalog

- The resolver gains two roots:
  - `services/java/<name>`, with the same task detection as Java functions: `bootJar` or
    `installDist` for `jvm`, `nativeCompile` for `native`;
  - `runtimes/<name>/Dockerfile`.
- `validateRecipe` previews each service's task, staging directory and image, exactly as for
  functions.
- `listRecipeFunctions` prints a SERVICES section after the functions. The task keeps its
  name.
- Images:
  - a Java service is staged and built from `deploy/recipes/Dockerfile.jvm` or
    `Dockerfile.native`, like a Java function;
  - a `dockerfile` service builds with its own Dockerfile and **its own directory as the
    build context**, because the watchdog's Dockerfile copies `Cargo.toml` and `src`
    relative to it;
  - Dockerfile functions, bash included, keep the repository root as their context.

## Output directory

- **`-PrecipeOutput=<dir>`** relocates the staging tree and `distribution.json`. A relative
  path resolves against the repository root, like `-Precipe`. The default remains
  `build/recipes/<name>/`.
- **Safety.** An assembly deletes and regenerates its output directory, so it accepts the
  directory only when one of these holds:
  - it does not exist;
  - it is empty;
  - it contains a `distribution.json` written by an earlier assembly.

  In every other case the task fails before deleting anything. The repository root and its
  ancestors are always refused. The error names the rule that failed.

## Report: `distribution.json` version 2

The report becomes `schemaVersion: 2` for every recipe, v1 included. All the changes are
additions, so a reader of the version 1 fields sees no difference.

- `components[].kind`: `control-plane`, `function` or `service`.
- `components[].variant` and `components[].optimization` on the control plane, whenever
  the build metadata records them (see Rules).
- `components[].native`: the effective `optimization`, `gc` and `monitoring` of each native
  component, with the G1 rule applied.
- `components[].image.id`: the local image ID from
  `docker image inspect --format {{.Id}} <reference>`, recorded after each build. It stays in
  place after a push, next to `digest`. If the inspect fails, the assembly fails, because an
  image that cannot be pinned is not a usable result.

## Errors

The style is unchanged: `recipe.yaml: <field>: <reason>`, raised at configuration or before
the first build step.

- The v2 schema rejects `native` with `mode: jvm`, `variant` outside the control plane, and
  `build`/`jvm` on Dockerfile components.
- An unknown service, or an SDK a service does not provide, gets the same message shape as a
  missing function implementation.
- Recipe-owned `-P` flags and an unsafe `-PrecipeOutput` fail as described above.

## Testing

- **`RecipeReaderTest`:**
  - v1 fixtures still validate;
  - v1 with any v2 field is rejected;
  - valid and invalid v2 fixtures for each rule above;
  - v1 → v2 normalisation.
- **`RecipePluginTest`**, using the existing TestKit fixture with its fake `bootJar`,
  `nativeCompile` and Docker script:
  - a fake `nativeCompile` that records the extra properties it sees proves per-component
    native options;
  - `variant` and the derived optimization appear in the build-metadata properties:
    - `c1` and `c2` from `jvm.args`;
    - the `-O` level in native;
  - services and bash functions appear in the catalog and preview, and build with the right
    context: the service directory for `dockerfile`, the repository root for bash;
  - `image.id` comes from the fake `docker image inspect`, and a failed inspect fails the
    assembly;
  - `-PrecipeOutput`: each refusal, and the regeneration of an earlier output;
  - builder-sizing flags are accepted alongside `-Precipe`, and recipe-owned flags are
    rejected;
  - a v1 recipe issues the same build and `docker build` commands as before, plus one
    `docker image inspect` per image.
- **Manual end-to-end, once, on the host.** A v2 recipe with a native control plane at `-Os`
  and a `variant`, warm-echo (native) and the watchdog as services, and one bash function.
  Checks:
  - `/modules/build-metadata` reports the variant and optimization;
  - `distribution.json` records the kinds, the native options and each image ID;
  - the images run.

## Documentation

`docs/recipes.md` describes:

- the v2 format and its rules;
- services and the bash SDK;
- `-PrecipeOutput` and its safety rules;
- the report changes;
- the two answered questions: back-to-back assemblies, and the JVM tiering default;
- the G1-on-host requirement.

The v2 schema gets the same editor hint as v1.

## Out of scope

- `builder: container` (part 2).
- `registry.platforms`, provenance, and per-platform digests (part 3).
- Signing, which stays in nanolab and the release.
- Exposing AOT-fixed switches such as the runtime-config API and the image validator as
  recipe options, which #216 suggests; they can join schema v2 later as additive fields.
