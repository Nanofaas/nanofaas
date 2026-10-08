# JVM-only local development Dockerfile.
# Native images are built via scripts/native-java-image.sh + tools/native-java/Dockerfile;
# this project does not use Cloud Native Buildpacks, and .github/workflows/ builds no image.
ARG BUILDER_IMAGE=eclipse-temurin:25-jdk
ARG RUNTIME_IMAGE=gcr.io/distroless/base-debian13:nonroot

FROM ${BUILDER_IMAGE} AS jre-builder
WORKDIR /tmp/build
COPY build/libs/app.jar app.jar
# jlink a runtime with only the modules this jar actually touches, instead of shipping the
# full stock JRE. jdk.unsupported (Netty's sun.misc.Unsafe) and jdk.crypto.ec (TLS EC cipher
# suites, loaded reflectively), and jdk.httpserver (the container proxy) aren't visible to
# jdeps' static analysis, so add them explicitly.
RUN jar xf app.jar BOOT-INF/lib BOOT-INF/classes && \
    jdeps --ignore-missing-deps --multi-release 25 --recursive \
      --print-module-deps --class-path 'BOOT-INF/lib/*' \
      BOOT-INF/classes app.jar > modules.txt && \
    jlink --add-modules "$(cat modules.txt),jdk.unsupported,jdk.crypto.ec,jdk.httpserver" \
      --strip-debug --no-header-files --no-man-pages --compress=zip-9 \
      --output /jre

# The tuning flags live in a @argfile because the final image is distroless: an
# exec-form ENTRYPOINT does no variable expansion and there is no shell to do it.
#
# Full tiering (no TieredStopAtLevel), matching platform/control-plane/Dockerfile.from-source: the
# 2026-08-23 comparison found C2 40% faster in dispatch at 1 core, and
# docs/experiments/overload-path-2026-09 (2026-09-05, post-ExecutionStore-fix)
# revalidated it on today's code - p99 19.7ms vs 176.5ms, shed 0.46% vs
# 26.63% at 4 event loops. See docs/plans/2026-09-04-overload-path-fixes.md
# Part I.3.
ARG JVM_TUNING="-XX:+UseSerialGC"
RUN printf '%s\n' $JVM_TUNING > /jvm.options
RUN mkdir -p /var/lib/nanofaas && touch /var/lib/nanofaas/.keep

FROM ${RUNTIME_IMAGE}
ARG BUILDER_IMAGE
ARG RUNTIME_IMAGE
ENV NANOFAAS_BUILD_BASE_IMAGE=${BUILDER_IMAGE}
ENV NANOFAAS_RUNTIME_BASE_IMAGE=${RUNTIME_IMAGE}
ENV NANOFAAS_REGISTRY_PATH=/var/lib/nanofaas/functions.json
WORKDIR /app
COPY --from=jre-builder --chown=nonroot:nonroot /jre /opt/jre
COPY build/libs/app.jar app.jar
COPY --from=jre-builder --chown=nonroot:nonroot /jvm.options /app/jvm.options
COPY --from=jre-builder --chown=nonroot:nonroot /var/lib/nanofaas /var/lib/nanofaas
EXPOSE 8080 8081

# Collector and JIT tiering come from JVM_TUNING (see the builder stage). The
# default is serial GC with full tiering: serial because the chart's default
# is a single-core deployment (the assumption the CPU sweep of 2026-08-23
# surfaced), full tiering because C1-only lost decisively at every core count
# measured (see Part I.3 of the overload-path-fixes plan). Everything below is
# unconditional.
# - Container-aware RAM ceiling
# - JMX and other features a function runtime does not need are off
ENTRYPOINT ["/opt/jre/bin/java", \
    "@/app/jvm.options", \
    "-XX:MaxRAMPercentage=70", \
    "-Xss256k", \
    "-Dspring.main.banner-mode=off", \
    "-Dspring.jmx.enabled=false", \
    "-Dspring.devtools.restart.enabled=false", \
    "-Dmanagement.endpoints.enabled-by-default=false", \
    "-jar", "/app/app.jar"]
