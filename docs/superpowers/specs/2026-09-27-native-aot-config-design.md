# Native builds evaluate their conditions with the recipe's configuration

Issue: miciav/nanofaas#216, the bug "In native, property-conditional beans are fixed at build time".

## Problem

Spring decides at startup which beans to create, and some of those decisions depend on
properties:

- the runtime-config admin API needs `nanofaas.admin.runtime-config.enabled=true`;
- the soak gauges need `nanofaas.metrics.profile=soak`;
- each image validator depends on `nanofaas.deployment.default-backend`.

A native build takes those decisions once, during Spring AOT (`processAot`), and only with the
configuration it sees at that moment, which is `application.yml`'s defaults. The executable then
contains exactly the beans those defaults select. Setting the property at run time changes
nothing: the bean it would have enabled is not in the executable, so a native control plane
answers `404` on `/v1/admin/runtime-config` even with the switch on.

A recipe already carries the control plane's configuration, `controlPlane.config`, which is
written as `config/recipe.yaml` and read at startup. The JVM build honours it and the native
build does not, so the same recipe yields different components in the two modes.

## Goal

A native control-plane build evaluates its conditions with the configuration the runtime reads,
so that JVM and native select the same components for the same recipe. This covers:

- a recipe built on the host;
- a recipe built in the container builder, both the executable export and `recipe-native`;
- a direct build without a recipe.

The release is unchanged: it builds one generic image, so it keeps the defaults.

## Evidence

A throwaway spike confirmed the mechanism, with no change to the repository:

- **The build.** `nativeCompile` of the control plane with
  `-Dspring.config.additional-location=file:<yaml>` on `processAot`, where the YAML set
  `nanofaas.admin.runtime-config.enabled: true`.
- **The run.** The executable, started with no configuration file and no environment,
  answered `200` on `GET /v1/admin/runtime-config`.
- **The baseline.** The same build without the property answered `404`.

## Decisions

- **One hook in the control plane's build.** `-PnanofaasAotConfig=<file>` passes the file to
  `processAot`. Recipes and the container builder only produce the file and pass that property.
  A plugin-only hook was rejected because a direct build would need a second mechanism, and the
  container builder does not go through the plugin. An environment variable on the task was
  rejected because Gradle does not treat it as an input, and would reuse stale AOT output.
- **Into the container as a build argument.** The configuration travels as `NATIVE_AOT_CONFIG`,
  base64-encoded, which is empty by default. The alternative was a named build context, like
  `containerd_maven_repo`. It would become mandatory for every caller of
  `deploy/native-java/Dockerfile`, including the release script and nanolab's bake, which would
  otherwise try to pull an image called `aot_config`. The build argument appears in BuildKit
  provenance (`mode=max`), but it holds the same configuration the image already ships as
  `config/recipe.yaml`.
- **Deciding the switches at startup instead** was considered and rejected by the user.
  - **The idea.** The deployment switches (the runtime-config admin API, the soak metrics in the
    control plane and the Java SDK, and the SDK's Prometheus endpoint) would always be built
    into the executable, and would check their property at startup.
  - **What it would have covered.** The published native image as well.
  - **Where it stays.** Recorded here, in case the question comes back for release users.

## The control plane's build

`-PnanofaasAotConfig=<file>` names a YAML file. A relative path resolves against the repository
root, as `-Precipe` does. In a native build, `platform/control-plane/build.gradle` configures
`processAot` with:

- `systemProperty 'spring.config.additional-location', 'file:<absolute path>'`, so the file is
  added to `application.yml` and overrides it, exactly as `config/recipe.yaml` does at run time;
- `inputs.file(<path>)`, so a changed configuration reruns AOT instead of reusing stale output.

The path uses `file:`, not `optional:file:`. A missing file fails the task, and Gradle names the
property. In a JVM build `processAot` is disabled, so the property has no effect, and a JVM build
reads its configuration at run time anyway. `scripts/native-java-image.sh` and the release do not
pass it.

## Recipes

### On the host

The hook applies when `controlPlane.build.mode` is `native` and the recipe has
`controlPlane.config`:

- **The property.** The plugin passes
  `nanofaasAotConfig=<root>/build/recipe-aot/<recipe name>/control-plane.yaml` to
  `:control-plane`. It uses the mechanism that already passes each project's native options,
  `RecipeBuildProperties.byProject`.
- **The file.** The plugin writes `controlPlane.config` there when it resolves the recipe, the
  moment it computes `byProject`. The path and the content are known at that point, so this needs
  no task and no cross-project ordering. It uses the same YAML serialisation helper as
  `config/recipe.yaml`. The file is rewritten on every invocation with that recipe, including
  `validateRecipe`. That is harmless, because it lives under `build/`.
- **Why that location.** The file lives under the root `build/`, not in the recipe output:
  - the output is emptied and ownership-checked;
  - `config/recipe.yaml` is written only after compilation;
  - the container builder could not see the file there either.
- **When the hook does not apply.** Without `controlPlane.config`, or with a JVM control plane,
  there is no property and no file.

`nanofaasAotConfig` joins the flags a recipe owns. Passing it by hand together with `-Precipe`
is rejected before anything is built.

### In the container builder

Both container paths share the builder stage of `deploy/native-java/Dockerfile`: the executable
export (`native-executable`) and the one-build multi-architecture image (`recipe-native`).

- **`RecipeContainerBuild`.** For a native control plane with a configuration,
  `builderArguments` adds `--build-arg NATIVE_AOT_CONFIG=<the YAML, base64>`. The YAML is the
  same serialisation as on the host.
- **The builder stage** declares `ARG NATIVE_AOT_CONFIG`, empty by default. When the argument
  is set, the Gradle `RUN` decodes it to `/tmp/nanofaas-aot-config.yaml` first, and adds the flag
  itself, so the path is written in one place only:
  `./gradlew "$NATIVE_TASK" $GRADLE_ARGS ${NATIVE_AOT_CONFIG:+-PnanofaasAotConfig=/tmp/nanofaas-aot-config.yaml}`.
- **Other callers** that do not pass the argument see no difference: the release,
  `scripts/native-java-image.sh`, and nanolab.

### Report

The report is unchanged. The recipe's SHA-256 already identifies the configuration.

## Errors

- **`-PnanofaasAotConfig` with `-Precipe`:** named error, raised before any build.
- **A missing `-PnanofaasAotConfig` file in a direct build:** `processAot` fails, and the error
  names the property.
- **Invalid YAML:** `processAot` fails with Spring's error. A recipe cannot produce this, because
  its configuration is parsed as YAML when the recipe is read.

## Testing

- **`RecipePluginTest`**, reading the property through the fixture's existing `printRecipeProps`.
  - **Native control plane with a configuration:**
    - the project receives `nanofaasAotConfig`;
    - the file holds the configuration.
  - **Without a configuration, or with a JVM control plane:** no property and no file.
  - **The rejection** of the flag passed by hand.
- **Unit tests of `RecipeContainerBuild`.** With a configuration, the command has
  `NATIVE_AOT_CONFIG`, which decodes to exactly the configuration. Without one, it has no such
  argument.
- **`scripts/tests`.** The builder stage declares `ARG NATIVE_AOT_CONFIG`. It decodes the
  argument and adds `-PnanofaasAotConfig` only when the argument is set. `docker build --check`
  stays clean.
- **Manual end-to-end.** Each run uses a recipe with a native control plane, `runtime-config` in
  its modules, and `nanofaas.admin.runtime-config.enabled: true`.
  - **On the host.** Start the staged executable with no configuration and no environment:
    `GET /v1/admin/runtime-config` must answer `200`.
  - **In the container**, with `builder: container`. Start the image with
    `SPRING_CONFIG_ADDITIONALLOCATION` set empty, so the shipped `config/recipe.yaml` is not
    read. The API must still answer `200`.
  - **Direct build.** `nativeCompile -PnanofaasAotConfig=<file>`, then the same `200` check.
  - **Baseline.** A native build without a configuration still answers `404`.

## Documentation

- **`docs/recipes.md`, "Native builds":**
  - in native, `controlPlane.config` is also read by Spring AOT, so it decides which components
    the executable contains;
  - changing it after the build changes values but not components, so it needs a rebuild;
  - environment variables at run time cannot switch components on or off.
- **The same section, for direct builds:**
  - `-PnanofaasAotConfig`;
  - the published native image keeps the defaults.

## Out of scope

- Components other than the control plane: they have no `config` in a recipe.
- Deciding the deployment switches at startup (rejected, see Decisions).
- Recording the AOT configuration in `/modules/build-metadata` or in the report.
