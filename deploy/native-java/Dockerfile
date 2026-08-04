FROM oraclelinux:9-slim AS builder

ARG TARGETARCH
RUN microdnf install -y ca-certificates curl findutils gcc glibc-devel gzip tar zlib-devel && microdnf clean all

COPY gradle.properties scripts/install-graalvm-ce.sh /tmp/
RUN graalvm_version="$(sed -n 's/^graalvmVersion=//p' /tmp/gradle.properties)" && \
    graalvm_java_version="$(sed -n 's/^graalvmJavaVersion=//p' /tmp/gradle.properties)" && \
    sh /tmp/install-graalvm-ce.sh "$TARGETARCH" "$graalvm_version" "$graalvm_java_version"

ENV JAVA_HOME=/opt/graalvm
ENV PATH="$JAVA_HOME/bin:$PATH"

WORKDIR /workspace
COPY . .

ARG NATIVE_TASK
ARG NATIVE_BINARY
ARG GRADLE_ARGS
RUN ./gradlew "$NATIVE_TASK" $GRADLE_ARGS --no-daemon && \
    cp "$NATIVE_BINARY" /tmp/application

FROM gcr.io/distroless/base-debian13:nonroot
WORKDIR /app
COPY --from=builder --chown=nonroot:nonroot /tmp/application /app/application
EXPOSE 8080 8081
ENTRYPOINT ["/app/application"]
