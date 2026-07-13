# Warm Echo service

`warm-echo` is a small Java service that echoes its invocation input. It is a runnable example of a long-running service compatible with NanoFaaS WARM execution, not a reusable runtime dependency.

The HTTP invocation runtime comes from `sdks/java`; this module only supplies the application and example handler.

Build its image with `./gradlew :services:java:warm-echo:bootBuildImage` or set `WARM_ECHO_IMAGE` to override the destination image name.
