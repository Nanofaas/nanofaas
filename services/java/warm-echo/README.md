# Warm Echo service

`warm-echo` is a small Java service that echoes its invocation input. It is a runnable example of a long-running service compatible with NanoFaaS WARM execution, not a reusable runtime dependency or shared platform infrastructure.

The HTTP invocation runtime comes from `sdks/java`; this module only supplies the application and example handler. Register its image through the normal NanoFaaS API to create the managed Deployment and Service. The SDK exposes `/invoke`, health endpoints, and metrics.

Build its native Distroless image with `./scripts/native-java-image.sh warm-echo`. For a JVM image, use `docker build -f services/java/warm-echo/Dockerfile -t nanofaas/warm-echo .`.
