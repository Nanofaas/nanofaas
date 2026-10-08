# Issue 236 Repository Layout Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Adopt role-oriented library/build-tool locations and an explicit source-build Dockerfile name while preserving semantic and public identities.

**Architecture:** Complete the directory mapping and naming decisions in the shared spec after the boundary-check plan. Prepare backward-compatible NanoLab consumers first, then move NanoFaaS libraries, and finally move tooling and update all live consumers. Preserve historical evidence and existing build/runtime semantics.

**Tech Stack:** Java 25, Gradle included build and TestKit, Python 3.12+, existing pytest, Docker/Buildx, Compose, GraalVM native build tooling, NanoLab.

**Spec:** [2026-10-08-module-boundaries-and-layout-design.md](../specs/2026-10-08-module-boundaries-and-layout-design.md), Layout mapping, Naming convention, Consumer compatibility and Acceptance 3–8. Source issue: [#236](https://github.com/Nanofaas/nanofaas/issues/236).

## Global Constraints

- Java 25; Python 3.12 or newer for Python checks.
- No new production dependencies, API libraries, public interfaces, or runtime behavior changes.
- Preserve Java packages, Gradle project paths, plugin IDs, module descriptor IDs, backend IDs, artifact identities and public configuration keys.
- Preserve engine state ownership, lock ordering, admission, retry and resource-release behavior.
- Keep tests beside their owning projects; use existing JUnit, ArchUnit and pytest dependencies.
- Keep `container-deployment-provider` as the module ID and `container-local` as the backend ID.
- Recipe Dockerfiles remain filesystem inputs; do not copy them into staged application directories.
- Preserve native Dockerfile stages, named build contexts, build arguments and executable validation.
- Preserve recorded historical paths, raw results, checksums, source revisions and provenance manifests.
- No benchmark migration (#240), public website work (#239), SDK redesign, package renaming, or release publication is included.

## Review Focus

- NanoLab receives an old checkout after a new one: path selection must be per-plan, immutable and compatible with both layouts (Task 1).
- A moved library has Java-lite Docker COPY consumers or a relative Gradle input: compile-only validation must not conceal a broken image (Task 2).
- Plugin templates move while recipe staging stays fixed: emitted build commands must select the new template with the old staging semantics (Task 3).
- Native export, recipe packaging and default release stage use different targets/contexts: preserve all three, including `containerd_maven_repo` and `recipe` (Task 3).
- Historical source paths resemble active references: update only the latter and prove historical evidence remained unchanged (Tasks 2–3).

---

## File structure and order

The spec's 12-row mapping is the authoritative move list. It includes seven library directories, the included Gradle build, two recipe Dockerfiles, the native tool directory and the Compose source Dockerfile. No module/class/package IDs are renamed. The one semantic filename rename is `Dockerfile.from-source`; the naming decision matrix explains the names deliberately retained.

1. Complete [module-boundary-checks](2026-10-08-module-boundary-checks.md).
2. Task 1 below: NanoLab accepts both layouts and can be integrated independently.
3. Task 2: relocate libraries, keeping every Gradle identity unchanged.
4. Task 3: relocate tooling/templates/source Dockerfile and validate the complete build paths.

Use isolated worktrees at execution time. Paths prefixed `NanoLab:` are relative to `/home/michele/Documenti/nanolab`, not to NanoFaaS. Read that repository's instructions before editing it. Track its changes separately. Existing untracked experiment files in NanoFaaS are not part of this work.

Before modifying functions/classes, run upstream GitNexus impact in the owning repository, report callers/processes/risk and investigate UNKNOWN. Filesystem moves use `git mv`; there are no symbol renames. Re-index after moves and run `detect-changes --scope all` before each commit; investigate any partial/truncated result. Do not publish images or update an external release merely to validate this plan.

### Task 1: Make NanoLab image planning compatible with both layouts

**Files:**
- Modify: `NanoLab:packages/nanolab/src/nanolab/images/plan.py`
- Test: `NanoLab:packages/nanolab/tests/images/test_plan.py`
- Test: `NanoLab:packages/nanolab/tests/images/test_bake.py`
- Test: `NanoLab:packages/nanolab/tests/soak/test_images.py`
- Test: `NanoLab:packages/nanolab/tests/soak/test_recipe_observation.py`

**Interfaces:**
- Consumes: existing `build_image_plan(repo_root, version, *, registry, selectors, architectures, flavors) -> ImagePlan` and `render_bake(...)` without signature changes.
- Produces: private `_native_java_dockerfile(repo_root: Path) -> Path`; an appended `NativeBuild.dockerfile: Path` field defaulting to the existing legacy `NATIVE_JAVA_DOCKERFILE` constant for direct constructors.
- `ImageCell.dockerfile` and `_validate_targets` consume each target's immutable `native_build.dockerfile`; they no longer select a process-global path independently.

- [ ] **Step 1: Add layout-selection and bake-output regressions.**

Create the listed relative paths as empty files under `tmp_path` and assert:

```python
@pytest.mark.parametrize("present, expected", [
    (["deploy/native-java/Dockerfile"], "deploy/native-java/Dockerfile"),
    (["tools/native-java/Dockerfile"], "tools/native-java/Dockerfile"),
    (["deploy/native-java/Dockerfile", "tools/native-java/Dockerfile"],
     "tools/native-java/Dockerfile"),
])
def test_native_dockerfile_layout_selection(tmp_path, present, expected):
    for relative in present:
        path = tmp_path / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.touch()
    assert _native_java_dockerfile(tmp_path) == Path(expected)
```

Also assert: neither file -> `FileNotFoundError` naming both supported locations; inspecting two roots in succession preserves the first plan's path; `render_bake` uses the selected path for every native cell with a root context, while JVM cells remain unchanged. A directory at the new filename is not a valid Dockerfile.

- [ ] **Step 2: Run the focused tests and verify failure before implementation.**

Run from NanoLab: `uv run --python 3.12 --project packages/nanolab --group dev pytest packages/nanolab/tests/images/test_plan.py packages/nanolab/tests/images/test_bake.py -q`
Expected: missing resolver/field or legacy-path mismatch. Respect the repository's configured source-contract fixture; do not bypass its allowlist.

- [ ] **Step 3: Implement per-plan native Dockerfile selection.**

Select the first existing regular file from new then legacy paths; fail if neither exists. After `_all_targets` builds its target list, use `dataclasses.replace` to attach the selected path to each native build description. Preserve all task names, binaries, Gradle arguments and default constructors. Update native validation and `ImageCell.dockerfile` to use that stored field. Do not introduce a general layout registry or mutate `NATIVE_JAVA_DOCKERFILE` globally.

- [ ] **Step 4: Exercise both recipe-observation fixture layouts.**

Add `layout="legacy"` as a keyword-only parameter to the existing `publication_inputs(...)` fixture helper. For `layout="current"`, create `tools/gradle-plugin/build.gradle` and `tools/gradle-plugin/dockerfiles/Dockerfile.jvm`; preserve legacy as the default for old-revision coverage. Make the embedded `GRADLE_STUB` derive its plugin cache/template locations from the fixture's existing directory. Parameterize `test_one_snapshot_one_publication_all_receipts` for both values. Assert publication receipts still bind to the same assembled inputs, and build-cache files remain excluded from the owned source snapshot. Do not change actual publication policy.

- [ ] **Step 5: Verify and commit NanoLab compatibility.**

Run the four listed test files from NanoLab with its documented pytest environment. Expected: PASS for legacy and current layouts. Record the resulting NanoLab commit; integrate this backward-compatible change before shared workflows consume the relocated NanoFaaS tree. Commit message: `Support role-oriented NanoFaaS build paths`.

### Task 2: Relocate shared libraries without changing their identities

**Files:**
- Move the seven `platform/<library>` trees to `platform/libs/<library>` exactly as specified.
- Modify: `settings.gradle` (the seven `projectDir` values only for this task).
- Modify: `functions/java/word-stats-lite/Dockerfile`, `functions/java/json-transform-lite/Dockerfile`, `functions/java/roman-numeral-lite/Dockerfile` (common source COPY input).
- Test: `scripts/tests/test_java_container_images.py`.
- Modify current references: `AGENTS.md`, `CLAUDE.md`, `docs/control-plane.md`, `docs/testing.md`; maintain the existing actual Java package root `it.unimib.datai.nanofaas` when correcting the stale contributor instruction.
- Create: `docs/architecture/module-boundaries-and-layout.md` (current module-role/naming table and intentional dependency exceptions); link from `docs/README.md`.

**Interfaces:**
- Consumes: architecture ownership checks from the first plan; the seven existing Gradle project IDs.
- Produces unchanged `:common`, `:control-plane-spi`, `:execution-runtime`, `:container-deployment-runtime`, `:workload-metrics`, `:p2p-api`, `:forecasting-api`, now rooted under `platform/libs/`.
- Java-lite image builders continue receiving common sources at their existing in-image destination `common/`.

- [ ] **Step 1: Capture the behavior baseline and add the failing COPY regression.**

Save outputs of `./gradlew projects`, selected-module reporting for default/none/async/sync/all, and compile/runtime dependency reports for core, sync and both container providers outside the source tree. Record the base revision. Add assertions in the existing Java image test:

```python
for name in ("word-stats-lite", "json-transform-lite", "roman-numeral-lite"):
    text = (REPO_ROOT / "functions/java" / name / "Dockerfile").read_text()
    assert "COPY platform/libs/common/ common/" in text
    assert "COPY platform/common/ common/" not in text
```

- [ ] **Step 2: Verify the regression is red on the old layout.**

Run: `uv run --python 3.12 --with pytest --with pyyaml pytest scripts/tests/test_java_container_images.py -q`
Expected: the new COPY assertions fail; retain all previous image checks.

- [ ] **Step 3: Move the seven libraries and update active references.**

Use `git mv` for each spec mapping; adjust only the corresponding `projectDir` paths, source COPY inputs, and actual relative filesystem inputs of moved build files. Preserve `project(':...')` dependencies, source packages and artifacts. Re-run a targeted literal search to catch active references in scripts/build files. Do not rewrite comments that explicitly identify historical revisions in SDK sources, raw evidence or old plans.

Write the current architecture note using the spec's naming matrix. Explain `api` versus `spi` versus `runtime`, the role of `common`, and the distinction between optional module IDs and runtime backend IDs. Record why `common`, `workload-metrics` and provider IDs are retained rather than mechanically renamed. Update current contributor/documentation paths; leave historical sections intact.

- [ ] **Step 4: Verify identity, architecture and the three image consumers.**

Run the same project/module/dependency reports and compare semantic project IDs, selected modules and dependency coordinates with the baseline; paths alone may differ. Run the first plan's focused all/none/async/sync architecture matrix and the existing `:execution-runtime:verifyNoForbiddenDependencies` task. Run `scripts/tests/test_java_container_images.py` again: PASS.

Build the three Java-lite Dockerfiles using the repository-root context and local temporary tags, using the existing Docker build environment. Expected: each COPY succeeds and each image contains the same runtime entrypoint. Native image work requires the existing GraalVM prerequisites; record real build results, not only Dockerfile string assertions.

- [ ] **Step 5: Verify historical preservation, analyze changes and commit.**

Compare `docs/experiments`, archived plans/specs, `docs/testing/evidence` and existing raw untracked files against the starting state: no content changes. Review moves with `git diff --summary`. Commit only this task's moves and active references. Commit message: `Group shared libraries under platform libs`.

### Task 3: Move build tooling and preserve every packaging path

**Files:**
- Move `platform/gradle-plugin` to `tools/gradle-plugin`, keeping source/test/resources together.
- Move both `deploy/recipes/Dockerfile.*` files into `tools/gradle-plugin/dockerfiles/`.
- Move `deploy/native-java` to `tools/native-java`.
- Move `deploy/compose/Dockerfile` to `platform/control-plane/Dockerfile.from-source`.
- Modify: `settings.gradle`, root `build.gradle` (included-build path literal), `scripts/native-java-image.sh`, `deploy/compose/compose.yaml`, `deploy/compose/offload-loadtest.yaml`.
- Modify after move: `tools/gradle-plugin/src/main/java/it/unimib/datai/nanofaas/gradle/RecipeArtifacts.java`, `RecipeContainerBuild.java`, `RecipeBuildx.java`; preserve signatures.
- Test after move: the corresponding `RecipePluginTest.java`, `RecipeContainerBuildTest.java`, `RecipeBuildxTest.java` in `tools/gradle-plugin/src/test/java/it/unimib/datai/nanofaas/gradle/`.
- Modify: `recipes/local-demo.yaml`, `recipes/one-shot-local-jvm.yaml`, `recipes/one-shot-local-native.yaml` schema links; the moved `recipe-v2.schema.json` only where it documents a current path.
- Test: `scripts/tests/test_java_container_images.py`, `scripts/tests/test_docker_compose_deployment.py`, `scripts/tests/test_native_build_wrapper.py`, `scripts/tests/test_release_authority.py`.
- Modify current paths: `docs/recipes.md`, `docs/testing.md`, `docs/control-plane.md`, `docs/p2p-node-information-validation.md`, `docs/testing/one-shot-phase-a.md`, `AGENTS.md`, `CLAUDE.md`, and the architecture note from Task 2. Review `.github/workflows/gitops.yml`, `.dockerignore` and `.gitignore`; change only actual path consumers, not generic patterns that still work.

**Interfaces:**
- Consumes: Task 1's NanoLab compatibility and Task 2's library layout.
- Produces the same Gradle plugin ID `it.unimib.datai.nanofaas.control-plane-modules` and existing `validateRecipe`, `assembleRecipe`, `publishRecipe` task interfaces.
- `RecipeContainerBuild.DOCKERFILE = "tools/native-java/Dockerfile"`; `EMPTY_MAVEN_REPOSITORY = "tools/native-java/empty-maven-repo"`; `TARGET = "native-executable"` is unchanged.
- `RecipeArtifacts.dockerfile(Path rootDir, RecipeTasks.Target target): Path` defaults to `tools/gradle-plugin/dockerfiles/Dockerfile.<mode>`; custom Dockerfile/context overrides keep their current semantics.
- Compose keeps context `../..` relative to its own YAML and uses `platform/control-plane/Dockerfile.from-source` relative to that context.

- [ ] **Step 1: Pin command-generation and packaging expectations before the moves.**

Extend existing tests to assert the new template/native paths in emitted command lists, without changing staged content. Assert:

```python
assert config["services"]["control-plane"]["build"]["context"] == "../.."
assert config["services"]["control-plane"]["build"]["dockerfile"] == "platform/control-plane/Dockerfile.from-source"
```

The existing Compose service key is `control-plane`; also check `edge-control-plane` and `cloud-control-plane` in `offload-loadtest.yaml`. Parse those existing files rather than creating a second Compose document. In Java tests assert the `-f` argument points at the new template/native file, the final argument is the same staging/root context, and `--target native-executable`/`recipe-native` plus named contexts remain present where currently required. Retain stage-order, base-image redeclaration, executable and no-extra-staged-Dockerfile checks. Custom Dockerfile overrides must still win over defaults.

- [ ] **Step 2: Run focused tests and observe the new path assertions fail.**

```bash
./gradlew -p platform/gradle-plugin test --tests '*RecipePluginTest' --tests '*RecipeContainerBuildTest' --tests '*RecipeBuildxTest'
uv run --python 3.12 --with pytest --with pyyaml pytest scripts/tests/test_java_container_images.py scripts/tests/test_docker_compose_deployment.py scripts/tests/test_native_build_wrapper.py -q
```

- [ ] **Step 3: Perform the moves and update command producers and live consumers.**

Use `git mv`. Update included-build locations and recipe/native constants, wrapper defaults, schema links and Compose Dockerfile references. The plugin still lives two levels below root, so existing `../../` root-relative references must remain where they are already correct. Preserve the prebuilt-JAR Dockerfile and every native stage (`builder`, `native-executable`, `recipe-native`, final runtime), ARG, entrypoint and context name. Retain templates on disk beside the plugin; keep them out of recipe staging.

Class/package names and method signatures remain unchanged. Correct the role descriptions and current paths in the listed guides. Preserve completed plans, pinned evidence, provenance paths and historical ADR sections. Add a dated current-path note where a live ADR reader needs orientation rather than editing its historical record.

- [ ] **Step 4: Verify focused checks and all recipe definitions.**

Run the previous focused commands with `-p tools/gradle-plugin`, then its full test suite. Run `uv run --python 3.12 --with pytest --with pyyaml pytest scripts/tests -q`. Run `validateRecipe` once for every tracked recipe and `assembleRecipe -Precipe=recipes/local-demo.yaml`; compare staged relative filenames and launch/config semantics with the baseline. Revisions/build identities legitimately change and must not be overwritten to force byte equality. Expected: no copied build template and unchanged public task/module IDs.

Run `docker compose -f deploy/compose/compose.yaml config` and the offload Compose equivalent. Run the NanoLab image-plan/bake tests against the relocated worktree through its supported source-contract setup. Expected: selected native path is the new location; legacy fixture cases remain green.

- [ ] **Step 5: Validate real image and native build paths in the existing build environment.**

```bash
./gradlew :control-plane:bootJar -PcontrolPlaneModules=all
docker build -f platform/control-plane/Dockerfile -t nanofaas/control-plane:layout-audit platform/control-plane
docker build -f platform/control-plane/Dockerfile.from-source -t nanofaas/control-plane:layout-source-audit .
CONTROL_PLANE_MODULES=async-queue,container-deployment-provider,build-metadata scripts/native-java-image.sh control-plane nanofaas/control-plane:layout-native-audit
```

Extract `/app/application` from a disposable container created from the local native image into a temporary directory; run `scripts/assert-native-executable.sh` on that extracted file, then remove only that container. The wrapper builds inside Docker and does not produce a host binary by itself. Also run `./gradlew assembleRecipe -Precipe=recipes/one-shot-local-native.yaml` to exercise the real container builder/export. Use its generated `build/recipes/one-shot-local-native` report to locate the control-plane staging directory, then build the `recipe-native` target with that directory as the `recipe` named context, the empty local Maven directory as `containerd_maven_repo`, and the same native task/arguments recorded by assembly. This reuses the existing build inputs; no new recipe is needed.

Run existing multi-architecture command/provenance tests; exercise a real amd64/arm64 build in the established builder when available. Verify launch/health and packaged configuration, not performance. Preserve `containerd_maven_repo` and `recipe` contexts. Publishing to a public registry is not required: use local build/export paths and verify publish command generation through existing tests. If the native/multi-architecture environment is unavailable, leave that verification explicitly pending.

- [ ] **Step 6: Run the final build/profile checks, review naming and commit.**

Run the architecture all/none/async/sync matrix from the first plan, `./gradlew build -PcontrolPlaneModules=all --continue` and `./gradlew releaseChecks -PcontrolPlaneModules=all --continue` in the supported environment. Include the default profile's selected-module report and compare it with the baseline. Preserve reports before another profile overwrites them. Do not rerun unrelated benchmark campaigns.

Review the spec's naming table: each changed name/location must convey its role without altering capability semantics. Verify no provider/module/backend/package IDs changed. Re-scan live consumers for old paths; permitted matches must be explicitly historical or legacy compatibility fixtures. Check historical evidence against the base revision. Re-index, analyze graph changes and commit only the intended files. Commit message: `Move build tools and clarify source image entrypoint`.

## Completion evidence

Record the NanoFaaS safeguard/library/tooling commits and compatible NanoLab commit, passing profile/check commands, real image/native results and any explicitly pending environment checks. #236 remains open until its required checks and cross-repository compatibility are verified. Execution of this plan does not authorize posting issue comments, changing publication policy or publishing releases.
