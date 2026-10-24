# NanoFaaS build integration tests

These tests exercise NanoFaaS's actual module descriptors and warm-echo build
scripts against the external Gradle plugin. The plugin implementation, schemas
and independent tests live in [nanofaas-gradle-plugin](https://github.com/Nanofaas/nanofaas-gradle-plugin).

Run `./gradlew -p tools/build-tests test` from the NanoFaaS root, or run
`./gradlew test`, which includes these checks. For development before a Portal
release, append `-PnanofaasGradlePluginDir=../nanofaas-gradle-plugin`.
See [the plugin guide](../../docs/gradle-plugin.md) for versioning and bootstrap.
