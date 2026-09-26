# Recipes v2, part 3: multi-architecture images with provenance

Issue: miciav/nanofaas#218, section 4. This spec builds on:

- part 1, `docs/superpowers/specs/2026-09-25-recipes-v2-design.md`, which is merged;
- part 2, `docs/superpowers/specs/2026-09-26-recipes-v2-container-builder-design.md` (PR #220).

## Goal

A recipe can produce multi-architecture images that carry BuildKit provenance, and it can record
their digests so that nanolab and the release can pin and sign exactly what was published.

- **What it builds.** `registry.platforms` selects the platforms, and `registry.provenance`
  attaches BuildKit provenance (`mode=max`).
- **What it records.** `distribution.json` records the digest of the multi-architecture index and
  one digest per platform.
- **Where provenance comes from.** For a native component built in the container, the image's
  provenance names the GraalVM builder stage. The soak binds `native-image` to the digest of that
  stage's `FROM`.
- **What stays outside.** Signing stays in nanolab and the release, which read the recorded
  digests.
- **What does not change.** A recipe without `platforms` behaves exactly as it does today.

## Decisions

- **The multi-arch build lives in the Gradle plugin** (the issue's open question 3). The plugin runs
  `docker buildx build` itself, on the builder the invocation selects, and records the digests.
  The builder's topology is the builder's concern: QEMU, native nodes per architecture, remote
  nodes. The rejected alternative, handing nanolab a bake file, would split the digests out of the
  report.
- **Only `publishRecipe` pushes.** A multi-architecture image cannot live in the classic local image
  store, and provenance attestations do not survive `--load`.
  - `assembleRecipe` builds every platform into the builder's cache, with no output.
  - `publishRecipe` repeats the build from that cache with `--push` and records what it pushed.
  - Rejected alternatives:
    - A push by digest during assembly: it needs a registry to assemble, and leaves orphaned
      manifests in it.
    - An OCI archive in the output: pushing it needs a new tool, since docker cannot push an OCI
      archive.
- **A native image built in the container is built in one build.** The executable is compiled
  inside the image build, by a new `recipe-native` stage of `deploy/native-java/Dockerfile`, so the
  image's provenance contains the GraalVM builder stage. Part 2's two builds cannot give this: an
  exported executable packaged afterwards, whose image provenance names only a local context.
- **A separate `RecipeBuildx` class** holds pure functions, the same pattern as
  `RecipeContainerBuild`:
  - the buildx commands;
  - the builder-platform check;
  - the metadata and index parsing.

  `RecipeArtifacts` branches once, on whether `platforms` is present.

## Recipe format

```yaml
schemaVersion: 2
registry:
  repository: ghcr.io/nanofaas
  tag: "0.19.0"
  platforms: [linux/amd64, linux/arm64]   # optional
  provenance: true                        # optional; default false
```

### Rules

- **v2 only.** Both fields exist only in `recipe-v2.schema.json`; the v1 schema stays frozen.
- **`platforms`.**
  - **Format.** A non-empty list, without duplicates, of `linux/amd64` and `linux/arm64`. These are
    the architectures `install-graalvm.sh` has checksums for and the release ships. Adding another
    one means updating the schema.
  - **Effect.** Its presence selects the buildx path, even with one platform, so a recipe can ask
    for an arm64 image from an amd64 host.
- **`provenance`.**
  - `true` builds with `--provenance=mode=max`.
  - Absent or `false`, the build uses `--provenance=false`, so the shape of what is pushed is
    predictable.
  - `provenance: true` without `platforms` is a schema error. The classic path cannot carry
    attestations, and ignoring them silently would be worse.
- **Scope.** `platforms` applies to every component with an image. A component without an image
  follows the part 2 flow: it is compiled for the host and staged.
- **Host-built native images.** A component with `mode: native`, `builder: host` and an image is
  allowed only when `platforms` is exactly `[<host platform>]`, because an executable compiled on
  the host has the host's architecture.
  - **Host platform.** It is derived from `os.arch`: `aarch64` and `arm64` become `linux/arm64`;
    `amd64` and `x86_64` become `linux/amd64`. Any other architecture has no host platform, so
    every such component is rejected.
  - **Error.** Otherwise configuration fails before any build. The error names the component and
    suggests `builder: container`.
- **JVM components.** No constraint: the jar is platform-independent.
- **Dockerfile components** (Python, bash and other functions, the watchdog service). They are built
  for each platform from their own Dockerfile. Their `RUN` steps need emulation or a native node,
  which is the builder's concern.
- **Invocation properties.**
  - `-PrecipeBuilder=<name>` adds `--builder <name>` to every buildx command. Without it, the
    current buildx builder is used. On a recipe without `platforms` it is an error, not silently
    ignored.
  - `-PrecipeDocker` remains the CLI. The buildx path needs `docker buildx`: a CLI without it, such
    as podman, fails with its own error.
- **Preview.** `validateRecipe` shows the platforms and, for each image,
  `docker buildx build --platform <list> (provenance: max|off)`. It still needs no daemon.

## Execution

### Builder check

The new task `checkRecipeBuilder` is registered only when `platforms` is present.

- **What it checks.** It runs `docker buildx inspect [--builder B]` and parses the `Platforms:`
  line. A trailing `*` marks a platform the user configured, and is stripped. Every requested
  platform must be listed.
- **When.** `cleanRecipe` depends on it, so it runs before the output is emptied and before any
  compilation.
- **Error.** It names the missing platforms and points to `-PrecipeBuilder`, a native node, or
  QEMU.

### Image builds

The image tasks keep their names, `recipeImage<N>`. Each image uses one command shape:

```
docker buildx build [--builder B] --platform <p1,p2> --provenance=<mode=max|false>
  -f <dockerfile> -t <reference> <kind-specific arguments> <context>
```

- **JVM and host-built native components.** Only the command changes: `deploy/recipes/Dockerfile.jvm`
  or `Dockerfile.native`, on the staging directory as the context.
- **Dockerfile components.** Their own Dockerfile and context, as today.
- **Container-built native components with an image.** A single build:

  ```
  -f deploy/native-java/Dockerfile --target recipe-native
  --build-context recipe=<output>/<staging-dir>
  --build-context containerd_maven_repo=<staged Maven repository | deploy/native-java/empty-maven-repo>
  --build-arg NATIVE_TASK=… --build-arg NATIVE_BINARY=…
  --build-arg GRAALVM_DISTRIBUTION=… --build-arg GRADLE_ARGS=…
  <repository root>
  ```

  - **Build arguments.** They are computed exactly as in part 2, by `RecipeContainerBuild.gradleArgs`,
    including the revision and dirty state taken on the host at execution. So are the containerd
    checks.
  - **No separate native build.** No `recipeNativeBuild<N>` is registered for such a component:
    each platform compiles inside its own build.
  - **Staging.** The staging directory holds only the runtime files, such as the control plane's
    `config/recipe.yaml`, and no `application`.
- **Cost.** A native build under QEMU emulation is very slow, so a builder with a native node for
  each architecture is the practical choice. The documentation says so.

### The `recipe-native` stage

It is added to `deploy/native-java/Dockerfile` and mirrors `deploy/recipes/Dockerfile.native`:

- the runtime base, with the writable registry directory prepared in the builder stage;
- `COPY --from=recipe . /app/`, then `COPY --from=builder /tmp/application /app/application`;
- the same `ENV` lines: base images, `NANOFAAS_REGISTRY_PATH` and `SPRING_CONFIG_ADDITIONALLOCATION`;
- the same `EXPOSE` and `ENTRYPOINT ["/app/application"]`.

The runtime stage stays last, so the default target used by the release and by
`scripts/native-java-image.sh` is unchanged.

### `assembleRecipe`

- **Build.** Each image task runs the command with no output, so every platform is built and the
  builder's cache is filled.
- **Report.** Each image is recorded as `status: built`, with `platforms` and `provenance`, and
  with no `id` and no digest.

### `publishRecipe`

For each image, in report order:

1. **Push.** Run the same command with `--push --metadata-file <temporary file>`. The build comes
   from the cache, so a native component is not compiled again.
2. **Digest.** Read `containerimage.digest` from the metadata file: an index, or a single manifest
   for one platform without provenance.
3. **Per-platform digests.** Run `docker buildx imagetools inspect <repository>@<digest> --raw`.
   - For an index, map each manifest's `os/architecture` to its digest. Skip attestation manifests,
     marked by the annotation `vnd.docker.reference.type: attestation-manifest`.
   - For a single manifest, map the only requested platform to the digest.
4. **Record.** Rewrite the report after each image, as today.
   - If the digest is missing, or the mapped platforms differ from `platforms`, the status is
     `published-unverified` and the task fails.
   - The message lists the images already published.

A recipe without `platforms` publishes exactly as today: `docker push` and the pushed digest, or
the RepoDigests fallback.

## Report

The report stays at version 2, because it only gains fields. On the buildx path a component's
`image` is:

```json
"image": {
  "reference": "ghcr.io/nanofaas/control-plane:0.19.0",
  "status": "published",
  "platforms": ["linux/amd64", "linux/arm64"],
  "provenance": true,
  "digest": "sha256:…",
  "manifests": {"linux/amd64": "sha256:…", "linux/arm64": "sha256:…"}
}
```

- **Fields after each task.** `assembleRecipe` writes `reference`, `status: built`, `platforms` and
  `provenance`. `publishRecipe` adds `digest` and `manifests`. There is no `id`.
- **`digest`.** It is the index digest, which is what nanolab signs and pins. With one platform and
  no provenance, it is that platform's manifest digest.
- **Consumers** tell the paths apart by the presence of `platforms`. nanolab does not read the
  report yet, so no consumer breaks.
- **`RecipeOutput`'s report validation**, which counts as ownership evidence. For version 2 it now
  requires either an `id` or a `platforms` list.
  - A `published` image on the buildx path also needs a `digest` and a `manifests` map whose keys
    are exactly `platforms`.
  - Reports written before this change remain valid.

## Errors

The style is unchanged, and errors are raised before any build unless stated.

- **Schema errors:**
  - `provenance: true` without `platforms`;
  - an unknown or duplicated platform;
  - `platforms: []`.
- **`-PrecipeBuilder` on a recipe without `platforms`:** named error.
- **A host-built native component with an image** and platforms other than `[<host platform>]`:
  named error at configuration, suggesting `builder: container`.
- **A builder without a requested platform:** error from `checkRecipeBuilder`, before the output is
  emptied.
- **A failing buildx build during assembly** fails its task, like a failing image build today.
- **A failing push** marks the image `failed`, rewrites the report and fails. The message lists
  the images already published.
- **A missing digest, or mismatched platforms:** `published-unverified`, then failure.

## Testing

- **`RecipeReaderTest`:** the schema rules for `platforms` and `provenance`, and their absence
  from v1.
- **Unit tests of `RecipeBuildx`:**
  - the command for each kind (JVM, host native, container native, Dockerfile), with and without a
    builder, with and without provenance, and with and without `--push --metadata-file`;
  - parsing `Platforms:` from `buildx inspect`, including `*` suffixes and several nodes;
  - parsing the metadata file;
  - parsing `imagetools --raw`: an index with attestations, a single manifest, a missing platform;
  - mapping `os.arch` to the host platform.
- **`RecipePluginTest`**, where the fake docker handles `buildx inspect`, `buildx build` (writing
  the metadata file) and `buildx imagetools inspect --raw`:
  - the exact commands during assembly and during publish;
  - no `recipeNativeBuild` for a container-built native component with an image;
  - the staging directory holds `config/recipe.yaml` but no `application`;
  - the report after assembly and after publish;
  - the builder error is raised before `cleanRecipe` empties anything;
  - the host-platform error;
  - a recipe without `platforms` is unchanged.
- **`RecipeOutputTest`:** the new rule for either an `id` or `platforms`, and for `digest` and
  `manifests` on published buildx images.
- **`scripts/tests`:** `deploy/native-java/Dockerfile` defines `recipe-native`, and the runtime
  stage stays last. `docker build --check` stays clean.
- **Manual end-to-end on the arm64 development host:**
  - **Setup.** A recipe with `platforms: [linux/arm64]`, `provenance: true` and a native control
    plane built in the container. It is published to a local `registry:2` through a
    `docker-container` builder.
  - **Provenance.** `docker buildx imagetools inspect --format '{{json .Provenance}}'` must show the
    builder stage's base image with its digest, and `GRAALVM_DISTRIBUTION`.
  - **Runtime.** The published image must start and serve `/modules/build-metadata`.
  - **Two platforms.** They need QEMU (`tonistiigi/binfmt`) on this host, which is a system
    change, asked for before it is made. Without it, the two-platform case is not run here and is
    recorded as not run.

## Documentation

A new "Multi-architecture images" section in `docs/recipes.md` covers:

- the fields, and what selects the buildx path;
- builders: QEMU or native nodes, and why native-image needs a native node in practice;
- why assembly produces no local image;
- the report's image shape;
- that signing stays outside the recipe.

## Out of scope

- Signing (cosign).
- SBOM attestations (`--sbom`).
- Creating builders or nodes.
- Platforms other than `linux/amd64` and `linux/arm64`.
- podman on the buildx path.
