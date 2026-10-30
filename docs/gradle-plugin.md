# NanoFaaS Gradle build plugin

Build composition is maintained in
[`Nanofaas/nanofaas-gradle-plugin`](https://github.com/Nanofaas/nanofaas-gradle-plugin).
Its README provides the installation tutorial, development instructions and Plugin
Portal release procedure.

## Ownership

| Location | Responsibility |
| --- | --- |
| External plugin repository | Module discovery and constraints, OpenAPI composition, recipe build logic, recipe v1/v2 JSON Schemas and plugin tests |
| `deploy/recipes/Dockerfile.jvm` and `Dockerfile.native` | NanoFaaS packaging and runtime-image policy |
| `tools/native-java/` and `scripts/install-graalvm.sh` | NanoFaaS native container builder and GraalVM installation |
| `tools/build-tests/` | Tests of NanoFaaS's actual module descriptors and warm-echo scripts |

The schemas are included in the plugin JAR; recipe validation reads them locally.
Dockerfiles are read from the consuming NanoFaaS checkout. Changing packaging
policy therefore does not require releasing the Gradle plugin.

## Versioned dependency

`settings.gradle` applies the **settings plugin**
`io.github.nanofaas.build`. Its version is pinned by
`nanofaasGradlePluginVersion` in the root `gradle.properties`; the default is `0.1.3`.
Gradle resolves it from the Gradle Plugin Portal, with Maven Central available for
transitive dependencies. Java package names remain `it.unimib.datai.nanofaas.gradle`.

NanoFaaS needs at least `0.1.2`: `0.1.1` rejects `build.native.maxHeap` and cannot
build the one-shot Rust functions, whose `Dockerfile` is a symbolic link to a shared one.

Version `0.1.3` records JVM recipes using `-Xint` as `interpreted` in build
metadata and distribution reports, instead of incorrectly labeling them `c2`.

The old repository-local ID `it.unimib.datai.nanofaas.control-plane-modules` was not
published and is replaced by the organization-owned Portal namespace.
Module selectors, recipe formats and task names are unchanged.

## Work on both repositories

Clone the plugin beside NanoFaaS, then select the checkout explicitly:

```bash
git clone https://github.com/Nanofaas/nanofaas-gradle-plugin.git ../nanofaas-gradle-plugin
./gradlew validateRecipe -Precipe=recipes/local-demo.yaml \
  -PnanofaasGradlePluginDir=../nanofaas-gradle-plugin
./gradlew -p tools/build-tests test \
  -PnanofaasGradlePluginDir=../nanofaas-gradle-plugin
```

`nanofaasGradlePluginDir` accepts a relative path from the NanoFaaS root or an
absolute path. It replaces Portal resolution with a composite build of that
checkout, including the implementation dependency used by the build integration
tests. Ordinary builds do not depend on the presence of a sibling checkout.
For repeated development you can set the absolute path in your **user-level**
`~/.gradle/gradle.properties`; keep machine-specific paths out of repository files.

The plugin has its own wrapper and tests. Run them from the plugin checkout:

```bash
./gradlew build validatePlugins
./gradlew pmdMain
```

`./gradlew test` in NanoFaaS continues to run NanoFaaS's build integration tests;
the plugin's independent suite runs in its own CI. Do not run builds concurrently
when they compile into the same plugin checkout's `build/` directory.

## Plugin releases

The plugin repository publishes its own releases: a `v<version>` tag runs its CI,
which verifies the standalone build and published Maven marker, then publishes to
the Gradle Plugin Portal. No Portal credentials belong in NanoFaaS CI.

Pin a new release by updating `nanofaasGradlePluginVersion` and checking the real
recipes and `tools/build-tests` before merging. Until the release is available on
the Portal, use the checkout override above.
