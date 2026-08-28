# Control-Plane Module Constraints Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` to implement this plan task-by-task.

**Goal:** Require every optional control-plane module to declare its identity, default-selection status, strong and weak requirements, and incompatibilities, then reject invalid selections before compilation.

**Architecture:** Add one settings plugin as an included build under `platform/gradle-plugin`. The plugin discovers every Gradle project directly below `platform/modules`, requires a descriptor beside its `build.gradle`, resolves the requested module set, validates it, includes the projects, and automatically applies an internal project convention that wires declared module dependencies. No Spring runtime validator is included in this iteration: Gradle is the only supported artifact assembly path and is the earliest useful enforcement point.

**Tech Stack:** Gradle 9.3, Java Gradle Plugin API, Gradle TestKit, JUnit 5, Java `Properties`.

---

## Decisions and scope

- Plugin source lives in this repository at `platform/gradle-plugin/` as an included build. It never enters the control-plane runtime classpath.
- There is one public plugin ID: `it.unimib.datai.nanofaas.control-plane-modules`.
- Modules do not apply or implement anything individually. The settings plugin automatically applies its internal project convention to every discovered module project.
- Every directory immediately below `platform/modules` containing `build.gradle` or `build.gradle.kts` must contain `module.properties`. Missing or malformed descriptors fail the build; they are never silently skipped.
- `requires.strong` requires the dependency to be selected and adds it to `implementation`.
- `requires.weak` allows the dependency to be absent and adds it to `compileOnly`; the corresponding Spring integration remains conditional.
- `conflicts` is treated symmetrically during validation even when only one module declares it.
- With no selector, the plugin selects descriptors with `defaultEnabled=true`. `async-queue` is default-enabled and `sync-queue` is not, preserving a valid ordinary build.
- `all` still means every available module. It fails if that set violates a constraint; it never silently chooses between alternatives.
- `schemaVersion=1` versions the descriptor format. Independent module versions and version ranges are deferred until modules can be released independently; currently every module inherits the same root project version.
- The existing hard-coded async/sync checks in root settings and the control-plane build are removed after descriptor validation replaces them.
- A Spring Boot startup guard is deferred. Add it only if NanoFaaS starts supporting artifacts or classpaths assembled outside this Gradle build.

Descriptor example:

```properties
schemaVersion=1
id=sync-queue
defaultEnabled=false
requires.strong=
requires.weak=runtime-config
conflicts=async-queue
```

### Task 1: Build and unit-test the constraint engine

**Files:**
- Create: `platform/gradle-plugin/settings.gradle`
- Create: `platform/gradle-plugin/build.gradle`
- Create: `platform/gradle-plugin/src/main/java/it/unimib/datai/nanofaas/gradle/ModuleDescriptor.java`
- Create: `platform/gradle-plugin/src/main/java/it/unimib/datai/nanofaas/gradle/ModuleDescriptorReader.java`
- Create: `platform/gradle-plugin/src/main/java/it/unimib/datai/nanofaas/gradle/ModuleConstraintResolver.java`
- Create: `platform/gradle-plugin/src/test/java/it/unimib/datai/nanofaas/gradle/ModuleDescriptorReaderTest.java`
- Create: `platform/gradle-plugin/src/test/java/it/unimib/datai/nanofaas/gradle/ModuleConstraintResolverTest.java`

**Step 1: Create the standalone plugin test build.**

Use `java-gradle-plugin`, Java 21 for Gradle runtime compatibility, `gradleTestKit()`, AssertJ, and JUnit Jupiter. Set the included-build group to `it.unimib.datai.nanofaas`.

Run:

```bash
./gradlew -p platform/gradle-plugin test
```

Expected: pass with no tests, proving the isolated build resolves.

**Step 2: Write failing descriptor-reader tests.**

Using JUnit `@TempDir`, cover a valid descriptor plus missing file, missing `schemaVersion`, unsupported schema, blank ID, duplicate list entries, and invalid boolean values.

Example assertion:

```java
assertThat(reader.read(moduleDirectory)).isEqualTo(new ModuleDescriptor(
        1, "sync-queue", false, Set.of(), Set.of("runtime-config"), Set.of("async-queue")));
```

Run:

```bash
./gradlew -p platform/gradle-plugin test --tests '*ModuleDescriptorReaderTest'
```

Expected: fail because the reader does not exist.

**Step 3: Implement the smallest descriptor model and reader.**

Use an immutable Java record. Parse comma-separated values by trimming and discarding empty entries. Reject duplicate non-empty values and unknown property names so descriptor typos cannot be ignored.

**Step 4: Write failing resolver tests.**

Cover:

```java
assertThatThrownBy(() -> resolver.validate(selected("sync-queue", "async-queue"), descriptors))
        .hasMessageContaining("sync-queue")
        .hasMessageContaining("async-queue");

assertThatThrownBy(() -> resolver.validate(selected("concurrency-control"), descriptors))
        .hasMessageContaining("requires strong module 'async-queue'");

assertThatCode(() -> resolver.validate(selected("sync-queue"), descriptors))
        .doesNotThrowAnyException();
```

Also reject duplicate module IDs and requirements/conflicts referencing unavailable modules. Weak requirements may be absent.

**Step 5: Implement the resolver and run the focused suite.**

The resolver validates but never mutates the selected set or automatically adds missing strong requirements.

Run:

```bash
./gradlew -p platform/gradle-plugin test
```

Expected: all descriptor and resolver tests pass.

**Step 6: Commit.**

```bash
git add platform/gradle-plugin
git commit -m "build: add module constraint engine"
```

### Task 2: Implement settings-time discovery and selection

**Files:**
- Create: `platform/gradle-plugin/src/main/java/it/unimib/datai/nanofaas/gradle/ControlPlaneModulesPlugin.java`
- Create: `platform/gradle-plugin/src/test/java/it/unimib/datai/nanofaas/gradle/ControlPlaneModulesPluginTest.java`
- Modify: `platform/gradle-plugin/build.gradle`
- Modify: `settings.gradle:1-22`

**Step 1: Write failing Gradle TestKit tests.**

Create temporary repositories containing minimal module directories and execute `help` with `GradleRunner`. Test these selections independently:

- no property selects only `defaultEnabled=true` modules;
- `none` selects none;
- `none,async-queue` fails;
- `async-queue` succeeds;
- `sync-queue` succeeds even when its weak `runtime-config` dependency is absent;
- `concurrency-control` fails without its strong `async-queue` dependency;
- `async-queue,sync-queue` fails with both IDs in the message;
- `all` expands first and then fails on the same conflict;
- unknown IDs fail and list available IDs;
- a Gradle module directory without `module.properties` fails explicitly;
- descriptor ID and directory name mismatch fails.

Run:

```bash
./gradlew -p platform/gradle-plugin test --tests '*ControlPlaneModulesPluginTest'
```

Expected: fail because the settings plugin does not exist.

**Step 2: Register the single public plugin.**

In `gradlePlugin` register:

```groovy
plugins {
    controlPlaneModules {
        id = 'it.unimib.datai.nanofaas.control-plane-modules'
        implementationClass = 'it.unimib.datai.nanofaas.gradle.ControlPlaneModulesPlugin'
    }
}
```

**Step 3: Implement discovery and selection.**

The plugin must:

1. enumerate every immediate module directory with a Gradle build file;
2. require and parse its descriptor;
3. include it as `:control-plane-modules:<id>`;
4. resolve `controlPlaneModules` from the Gradle property, then `NANOFAAS_CONTROL_PLANE_MODULES`, then descriptor defaults;
5. expand `all` and handle `none`;
6. validate the final set before project task configuration;
7. publish the immutable sorted selection as the Gradle extra property `nanofaasSelectedControlPlaneModules`.

**Step 4: Wire the included build in root settings.**

Add at the start of `pluginManagement`:

```groovy
includeBuild('platform/gradle-plugin')
```

Apply the plugin in the root settings `plugins` block.

**Step 5: Run the focused tests.**

```bash
./gradlew -p platform/gradle-plugin test --tests '*ControlPlaneModulesPluginTest'
```

Expected: all discovery and selection cases pass.

**Step 6: Commit.**

```bash
git add platform/gradle-plugin settings.gradle
git commit -m "build: discover control-plane modules"
```

### Task 3: Declare every current module and wire requirements automatically

**Files:**
- Create: `platform/modules/async-queue/module.properties`
- Create: `platform/modules/autoscaler/module.properties`
- Create: `platform/modules/build-metadata/module.properties`
- Create: `platform/modules/concurrency-control/module.properties`
- Create: `platform/modules/container-deployment-provider/module.properties`
- Create: `platform/modules/k8s-deployment-provider/module.properties`
- Create: `platform/modules/offload/module.properties`
- Create: `platform/modules/runtime-config/module.properties`
- Create: `platform/modules/sync-queue/module.properties`
- Create: `platform/gradle-plugin/src/main/java/it/unimib/datai/nanofaas/gradle/ControlPlaneModuleProjectPlugin.java`
- Create: `platform/gradle-plugin/src/test/java/it/unimib/datai/nanofaas/gradle/ControlPlaneModuleProjectPluginTest.java`
- Modify: `platform/gradle-plugin/src/main/java/it/unimib/datai/nanofaas/gradle/ControlPlaneModulesPlugin.java`
- Modify: module `build.gradle` files only where a dependency now comes from the descriptor

**Step 1: Write a failing real-repository descriptor test.**

Add `RepositoryModuleDescriptorsTest` and point it at this repository. Assert every Gradle module directory has exactly one valid descriptor and every referenced module exists.

Run:

```bash
./gradlew -p platform/gradle-plugin test --tests '*RepositoryModuleDescriptorsTest'
```

Expected: fail because descriptors are absent.

**Step 2: Audit and create descriptors.**

Before editing each module, inspect its auto-configuration, conditional integrations, and Gradle project dependencies. Run GitNexus impact analysis on every configuration class whose loading assumptions are being changed. Do not infer dependencies from test-only project dependencies.

Seed these confirmed declarations:

```properties
# platform/modules/async-queue/module.properties
schemaVersion=1
id=async-queue
defaultEnabled=true
requires.strong=
requires.weak=
conflicts=sync-queue

# platform/modules/sync-queue/module.properties
schemaVersion=1
id=sync-queue
defaultEnabled=false
requires.strong=
requires.weak=runtime-config
conflicts=async-queue

# platform/modules/concurrency-control/module.properties
schemaVersion=1
id=concurrency-control
defaultEnabled=true
requires.strong=async-queue
requires.weak=
conflicts=
```

Mark the remaining modules default-enabled only if the resulting default selection passes the resolver. Record no relationship unless source and build evidence support it.

**Step 3: Write failing project-convention tests.**

Using TestKit, verify an automatically configured module receives:

- `implementation project(':control-plane-modules:async-queue')` for a strong requirement;
- `compileOnly project(':control-plane-modules:runtime-config')` for a weak requirement;
- no test dependency added implicitly.

Also verify users do not need a `plugins` block entry in each module.

**Step 4: Implement and auto-apply the internal project convention.**

The settings plugin registers a `beforeProject` callback for paths beginning with `:control-plane-modules:` and applies `ControlPlaneModuleProjectPlugin` by class. The project convention reads the descriptor already discovered by the settings plugin and adds dependencies after `java-library` is present. It must not be exposed as a second public plugin ID.

Remove equivalent manual `implementation` or `compileOnly` module dependencies from module build files. Leave dependencies on `:common`, `:control-plane`, external libraries, and explicit test fixtures unchanged.

**Step 5: Run plugin and module suites.**

```bash
./gradlew -p platform/gradle-plugin test
./gradlew :control-plane-modules:async-queue:test -PcontrolPlaneModules=async-queue
./gradlew :control-plane-modules:sync-queue:test -PcontrolPlaneModules=sync-queue,runtime-config
./gradlew :control-plane-modules:concurrency-control:test -PcontrolPlaneModules=async-queue,concurrency-control
```

Expected: all commands pass.

**Step 6: Commit.**

```bash
git add platform/gradle-plugin platform/modules
git commit -m "feat: declare module requirements"
```

### Task 4: Remove legacy selection logic and verify supported compositions

**Files:**
- Modify: `settings.gradle:23-86`
- Modify: `platform/control-plane/build.gradle:7-46`
- Modify: `docs/control-plane.md`
- Modify: `README.md`

**Step 1: Write a failing root-build integration test.**

Extend `ControlPlaneModulesPluginTest` to execute the real root task `:control-plane:printSelectedControlPlaneModules` and assert:

- no selector prints the descriptor-derived default set containing `async-queue` but not `sync-queue`;
- explicit async and explicit sync compositions print the requested sets;
- the incompatible pair fails during settings evaluation, before `:control-plane:bootJar` starts.

**Step 2: Remove duplicated code.**

Delete module discovery, CSV parsing, `none`/`all` handling, unknown-module validation, and the hard-coded async/sync conflict from root `settings.gradle`. In `platform/control-plane/build.gradle`, retain only conversion of `nanofaasSelectedControlPlaneModules` to `runtimeOnly project(...)` dependencies and the reporting task.

**Step 3: Document the contract and default.**

Document `module.properties`, default selection, strong/weak/conflict semantics, and these examples:

```bash
./gradlew :control-plane:bootJar
./gradlew :control-plane:bootJar -PcontrolPlaneModules=async-queue
./gradlew :control-plane:bootJar -PcontrolPlaneModules=sync-queue,runtime-config
```

State that `all` may fail when the repository intentionally contains alternative modules.

**Step 4: Run final verification.**

```bash
./gradlew -p platform/gradle-plugin test
./gradlew :control-plane:test
./gradlew :control-plane:test -PcontrolPlaneModules=async-queue
./gradlew :control-plane:test -PcontrolPlaneModules=sync-queue,runtime-config
./gradlew :control-plane:bootJar -PcontrolPlaneModules=async-queue,sync-queue
git diff --check
```

Expected: plugin tests and the first three control-plane compositions pass. The final incompatible build fails in settings with both module IDs and no compile or packaging task executed. `git diff --check` is clean.

**Step 5: Run impact analysis and commit.**

Run `gitnexus_detect_changes({scope: "all"})`, verify only module selection/build flows are affected, then:

```bash
git add settings.gradle platform/control-plane/build.gradle platform/gradle-plugin platform/modules docs/control-plane.md README.md
git commit -m "build: enforce module constraints"
```
