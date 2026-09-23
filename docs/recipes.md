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

The schema is
[`recipe-v1.schema.json`](../platform/gradle-plugin/src/main/resources/recipes/recipe-v1.schema.json);
the plugin validates against the copy packaged with it, and never downloads a schema. Add
this line at the top of a recipe to get completion and checks in editors that use the YAML
language server:

```yaml
# yaml-language-server: $schema=../platform/gradle-plugin/src/main/resources/recipes/recipe-v1.schema.json
```

```yaml
schemaVersion: 1
name: demo                         # lowercase words joined by '-'

registry:                          # optional; required by publishRecipe
  repository: ghcr.io/my-org       # host[:port][/path], no scheme
  tag: "1.0.0"

controlPlane:
  modules: [async-queue, container-deployment-provider]   # exact selection; [] = core only
  build: {mode: native}            # jvm | native, required
  jvm: {args: [...]}               # jvm mode only
  container: {image: control-plane}                       # optional
  config:                          # optional Spring configuration
    nanofaas: {metrics: {profile: basic}}

functions:                         # optional
  - name: word-stats
    sdk: java-lite                 # java | java-lite | python | javascript | go
    build: {mode: native}          # required for java and java-lite, forbidden otherwise
    container: {image: word-stats-java-lite}   # required for python, javascript and go
```

- `controlPlane.modules` is the exact module list: no `all` or `none`, and no hidden
  optional modules. The usual module constraints still apply. With `-Precipe`,
  `-PcontrolPlaneModules` is rejected and `NANOFAAS_CONTROL_PLANE_MODULES` is ignored.
- Each function selects source code that already exists. `java` uses
  `functions/java/<name>` (depending on `:sdks:java`), `java-lite` uses
  `functions/java/<name>-lite` (depending on `:sdks:java-lite`), and the other SDKs use
  `functions/<sdk>/<name>/Dockerfile`. The same function can appear once per SDK. Two
  entries with the same name and SDK are rejected, and so are two images with the same
  reference.
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

## Native builds

A `native` component compiles with `nativeCompile`, which needs a local GraalVM (see
`scripts/native-build.sh` for the release this repository pins). JVM components and
`validateRecipe` do not need it. Each component's mode is independent: a native function
leaves a `jvm` control plane on the JVM, without Spring AOT. With a `jvm` control plane in
the recipe, requesting native tasks directly (`nativeCompile -Precipe=...`) and passing a
contradicting `-PnanofaasBuildType` both fail. Build through `assembleRecipe` instead.

The native executable is compiled on the host and copied as-is into the image, so a native
component with `container.image` needs a Linux host of the image's architecture; other
hosts are rejected at configuration. The runtime image is `distroless/cc-debian13`, so the
host's glibc must not be newer than Debian 13's.

## Output and runtime configuration

```
build/recipes/<name>/
  distribution.json
  control-plane/                 app.jar | application, jvm.options, launch.args, config/recipe.yaml
  functions/<sdk>/<name>/        Java functions only: app.jar | lib/ | application, jvm.options, launch.args
```

The directory is regenerated on every assembly. Gradle's incremental compilation and
Docker's layer cache are what make repeated assemblies fast. Java images are built from
the staged directory with [`deploy/recipes/Dockerfile.jvm`](../deploy/recipes/Dockerfile.jvm)
or [`Dockerfile.native`](../deploy/recipes/Dockerfile.native). Python, JavaScript and Go
images use the function's own Dockerfile with the repository as the build context.

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

`distribution.json` records:

- the recipe's name and the SHA-256 of the recipe file
- the effective tag
- the Git revision and dirty state (`"source": null` when Git or the repository is unavailable)
- the resolved modules
- each component's SDK, mode, staging directory and image

It contains neither the runtime configuration nor environment variables. After a push, an
image's `status` is one of:

| Status | Meaning |
| --- | --- |
| `built` | Built by the last assembly, not pushed |
| `published` | Pushed; `digest` is the registry manifest digest printed by `docker push` (or the matching `RepoDigests` entry), never the local image ID |
| `published-unverified` | The push succeeded but the digest could not be determined; the task fails |
| `failed` | The push failed; the task fails and names the images already published |

Images are pushed one at a time, in order, and the report is rewritten after each push by
writing a temporary file and moving it into place. A later failure therefore never hides
an earlier success. There is no retry and no rollback: a registry has no transaction
across images. A new assembly discards all publication data.

The Docker CLI and its configured credentials are used as they are. `-PrecipeDocker=<path>`
selects another Docker-compatible executable. Every command runs with separate arguments,
never through a shell.
