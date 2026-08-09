# JVM-only local development Dockerfile.
# Production native images are built via GitHub Actions using Cloud Native Buildpacks.
FROM eclipse-temurin:25-jdk AS jre-builder
WORKDIR /tmp/build
COPY build/libs/app.jar app.jar
# jlink a runtime with only the modules this jar actually touches, instead of shipping the
# full stock JRE. jdk.unsupported (Netty's sun.misc.Unsafe) and jdk.crypto.ec (TLS EC cipher
# suites, loaded via ServiceLoader) aren't visible to jdeps' static analysis, so add them explicitly.
RUN jar xf app.jar BOOT-INF/lib BOOT-INF/classes && \
    jdeps --ignore-missing-deps --multi-release 25 --recursive \
      --print-module-deps --class-path 'BOOT-INF/lib/*' \
      BOOT-INF/classes app.jar > modules.txt && \
    jlink --add-modules "$(cat modules.txt),jdk.unsupported,jdk.crypto.ec" \
      --strip-debug --no-header-files --no-man-pages --compress=zip-9 \
      --output /jre

FROM gcr.io/distroless/base-debian13:nonroot
WORKDIR /app
COPY --from=jre-builder --chown=nonroot:nonroot /jre /opt/jre
COPY build/libs/app.jar app.jar
EXPOSE 8080 8081

# Optimization flags:
# - Use SerialGC for lower memory overhead in single-core environments
# - Disable JMX and other features not needed in a function runtime
# - Tune RAM usage percentages for container awareness
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
