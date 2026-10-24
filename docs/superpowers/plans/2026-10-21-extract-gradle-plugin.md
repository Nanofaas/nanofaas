# Extract the NanoFaaS Gradle plugin

Goal: prepare `Nanofaas/nanofaas-gradle-plugin` as an independent, publishable repository.

The plugin owns Java build logic and the recipe v1/v2 JSON schemas. NanoFaaS owns
packaging policy: recipe Dockerfiles move to `deploy/recipes/`; the shared native
builder stays in `tools/native-java/`. Public Java package names stay unchanged.
The Plugin Portal ID becomes `io.github.nanofaas.control-plane-modules` and the
artifact group becomes `io.github.nanofaas`, matching the GitHub organization.
Initial version: `0.1.0`. Java 25 and the Gradle 9.7.1 wrapper are preserved.

1. Copy plugin sources, schemas and self-contained tests to the sibling repository.
   Give it its own wrapper, license, properties, publication metadata and README tutorial.
2. Keep tests of the actual module descriptors and warm-echo scripts in NanoFaaS,
   in the independent `tools/build-tests` build, wired into the root `test` task.
3. Change the recipe Dockerfile convention to `deploy/recipes/` and update its
   existing integration assertions before the implementation; verify failure then success.
4. Use the versioned Portal plugin by default. Support an explicit
   `-PnanofaasGradlePluginDir=<checkout>` composite override for development and bootstrap.
5. Add GitHub Actions verification on pushes/PRs and publication only on `v<version>`
   tags after checks pass, with version matching and Portal credentials from secrets.
6. Update NanoFaaS documentation, schema editor links and container packaging tests.
7. Run the autonomous plugin suite and local Maven publication, consume the published
   marker from a clean fixture, run NanoFaaS build integration tests, validate the real
   recipes and run container-image tests. Inspect both diffs and GitNexus changes.

Release order: push the new repository, configure Portal credentials, tag and publish
`v0.1.0`, then integrate the NanoFaaS consumer migration. Until publication use the
explicit checkout override; do not imply the first Portal release already exists.
