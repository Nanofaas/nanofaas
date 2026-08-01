# Unified Java Native Builds Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Compile every Java native artifact with GraalVM CE 25.2.4, Native Build Tools, and size optimization, while packaging native services in a shared Distroless image flow.

**Architecture:** Keep Gradle `nativeCompile` and `nativeTestCompile` as the only compilers. Centralize `-Os`, use one verified GraalVM installer for host and container builds, and replace native Paketo builds with one parameterized Dockerfile that copies the selected binary into `gcr.io/distroless/base-debian13:nonroot`.

**Tech Stack:** Java 25, GraalVM CE 25.2.4, GraalVM Native Build Tools 0.11.6, Gradle 9.3.1, Docker BuildKit, Distroless Debian 13.

---

### Task 1: Centralize the native compiler contract

**Files:**
- Modify: `gradle.properties`
- Modify: `build.gradle`
- Modify: `scripts/native-build.sh`
- Modify: `scripts/install-graalvm-ce.sh`
- Test: `scripts/tests/test_native_build_wrapper.py`

1. Add failing assertions for the shared GraalVM version and root `-Os` convention.
2. Run `pytest -q scripts/tests/test_native_build_wrapper.py` and require failure.
3. Move the GraalVM release identifiers into shared properties and configure every Native Build Tools binary with `-Os` in the root build.
4. Remove project-local duplicate `-Os` arguments.
5. Run the focused test and `git diff --check`.

### Task 2: Add one native OCI image builder

**Files:**
- Create: `deploy/native-java/Dockerfile`
- Create: `scripts/native-java-image.sh`
- Modify: `.dockerignore`
- Test: `scripts/tests/test_java_container_images.py`

1. Add failing contract assertions for the generic builder, supported targets, exact compiler, and Distroless native runtime.
2. Run the focused test and require failure.
3. Implement a target-to-Gradle-task mapping for control-plane, Warm Echo, four Spring functions, and three lite functions.
4. Build through the generic Dockerfile and copy the selected executable to a fixed runtime path.
5. Exclude repository content not used by container builds.
6. Run focused tests and build one Spring and one lite native image.

### Task 3: Retire native Paketo compilation

**Files:**
- Modify: `platform/control-plane/build.gradle`
- Modify: `services/java/warm-echo/build.gradle`
- Modify: `functions/java/word-stats/build.gradle`
- Modify: `functions/java/json-transform/build.gradle`
- Modify: `functions/java/roman-numeral/build.gradle`
- Modify: `functions/java/figlet/build.gradle`
- Modify: `tools/fn-init/src/fn_init/templates/java/build.gradle.tmpl`
- Test: `scripts/tests/test_java_container_images.py`

1. Add failing assertions that Java native builds no longer set `BP_NATIVE_IMAGE=true`.
2. Remove native Paketo configuration while retaining JVM container paths.
3. Add `graalvmNative` to the generated Java function template.
4. Run container contract and generator tests.

### Task 4: Compile every Java native target from the wrapper

**Files:**
- Modify: `scripts/native-build.sh`
- Modify: `scripts/tests/test_native_build_wrapper.py`

1. Add failing assertions for all Spring and lite function tasks.
2. Add their `nativeCompile` tasks to the existing wrapper.
3. Run the focused tests, then run the wrapper with smoke checks disabled if the full smoke was already covered independently.

### Task 5: Document and verify the unified flow

**Files:**
- Modify: `README.md`
- Modify: `docs/quickstart.md`
- Modify: `services/java/warm-echo/README.md`
- Modify: `AGENTS.md`
- Modify: `CLAUDE.md`

1. Replace native `bootBuildImage` instructions with `scripts/native-java-image.sh <target> [image]`.
2. Run all focused Python tests and relevant JVM tests.
3. Run `gitnexus_detect_changes(scope: "all")` and review the affected scope.
4. Run `git diff --check` and inspect the final diff without committing.
