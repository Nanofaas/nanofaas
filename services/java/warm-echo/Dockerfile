FROM eclipse-temurin:25-jdk AS jre-builder
WORKDIR /tmp/build
COPY build/libs/app.jar app.jar
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
EXPOSE 8080

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
