ARG BUILDER_IMAGE=eclipse-temurin:25-jdk
ARG RUNTIME_IMAGE=gcr.io/distroless/base-debian13:nonroot

FROM ${BUILDER_IMAGE} AS build

ARG NANOFAAS_CONTROL_PLANE_MODULES=container-deployment-provider

WORKDIR /workspace
COPY . .
RUN ./gradlew :control-plane:bootJar \
    -PcontrolPlaneModules=${NANOFAAS_CONTROL_PLANE_MODULES} \
    --no-daemon

# jlink a runtime with only the modules app.jar actually touches, instead of shipping the full
# stock JRE. jdk.unsupported (Netty's sun.misc.Unsafe) and jdk.crypto.ec (TLS EC cipher suites,
# loaded via ServiceLoader), and jdk.httpserver (the container proxy) aren't visible to jdeps'
# static analysis, so add them explicitly.
RUN cd /workspace/platform/control-plane/build/libs && \
    jar xf app.jar BOOT-INF/lib BOOT-INF/classes && \
    jdeps --ignore-missing-deps --multi-release 25 --recursive \
      --print-module-deps --class-path 'BOOT-INF/lib/*' \
      BOOT-INF/classes app.jar > modules.txt && \
    jlink --add-modules "$(cat modules.txt),jdk.unsupported,jdk.crypto.ec,jdk.httpserver" \
      --strip-debug --no-header-files --no-man-pages --compress=zip-9 \
      --output /jre

FROM ${RUNTIME_IMAGE}

ARG BUILDER_IMAGE
ARG RUNTIME_IMAGE
ENV NANOFAAS_BUILD_BASE_IMAGE=${BUILDER_IMAGE}
ENV NANOFAAS_RUNTIME_BASE_IMAGE=${RUNTIME_IMAGE}

WORKDIR /app
COPY --from=build --chown=nonroot:nonroot /jre /opt/jre
COPY --from=build /workspace/platform/control-plane/build/libs/app.jar /app/app.jar
EXPOSE 8080 8081

ENTRYPOINT ["/opt/jre/bin/java", \
    "-XX:+UseSerialGC", \
    "-XX:MaxRAMPercentage=70", \
    "-XX:TieredStopAtLevel=1", \
    "-Xss256k", \
    "-Dspring.main.banner-mode=off", \
    "-Dspring.jmx.enabled=false", \
    "-Dspring.devtools.restart.enabled=false", \
    "-Dmanagement.endpoints.enabled-by-default=false", \
    "-jar", "/app/app.jar"]
