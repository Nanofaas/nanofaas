FROM eclipse-temurin:25-jdk AS jre-builder
WORKDIR /tmp/build
COPY build/libs/figlet.jar app.jar
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
COPY build/libs/figlet.jar app.jar
EXPOSE 8080
ENTRYPOINT ["/opt/jre/bin/java", "-jar", "/app/app.jar"]
