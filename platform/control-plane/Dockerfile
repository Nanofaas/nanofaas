# JVM-only local development Dockerfile.
# Native images are built via scripts/native-java-image.sh + deploy/native-java/Dockerfile;
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
# The default reproduces what was hard-coded here before, so a build that passes
# nothing is byte-for-byte the build that was measured.
ARG JVM_TUNING="-XX:+UseSerialGC -XX:TieredStopAtLevel=1"
RUN printf '%s\n' $JVM_TUNING > /jvm.options

FROM ${RUNTIME_IMAGE}
ARG BUILDER_IMAGE
ARG RUNTIME_IMAGE
ENV NANOFAAS_BUILD_BASE_IMAGE=${BUILDER_IMAGE}
ENV NANOFAAS_RUNTIME_BASE_IMAGE=${RUNTIME_IMAGE}
WORKDIR /app
COPY --from=jre-builder --chown=nonroot:nonroot /jre /opt/jre
COPY build/libs/app.jar app.jar
COPY --from=jre-builder --chown=nonroot:nonroot /jvm.options /app/jvm.options
EXPOSE 8080 8081

# Collector and JIT tiering come from JVM_TUNING (see the builder stage). The
# defaults were chosen for a single-core deployment - serial GC and C1 only -
# which is the assumption the CPU sweep of 2026-08-23 showed the chart was
# quietly making. Everything below is unconditional.
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
