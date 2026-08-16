# Multi-stage native build for word-stats-lite.
# Stage 1: GraalVM native-image compilation via Gradle.
# Stage 2: Minimal runtime image with the static binary.

FROM oraclelinux:9-slim AS builder

# Install findutils (provides xargs, required by gradlew)
ARG TARGETARCH
RUN microdnf install -y ca-certificates curl findutils gcc glibc-devel gzip tar zlib-devel && microdnf clean all
COPY gradle.properties scripts/install-graalvm.sh /tmp/
RUN sh /tmp/install-graalvm.sh "$TARGETARCH" "$(sed -n 's/^graalvmVersion=//p' /tmp/gradle.properties)" "$(sed -n 's/^graalvmJavaVersion=//p' /tmp/gradle.properties)"
ENV JAVA_HOME=/opt/graalvm
ENV PATH="$JAVA_HOME/bin:$PATH"

WORKDIR /build

# Copy Gradle wrapper and root build config
COPY gradlew build.gradle gradle.properties ./
COPY gradle/ gradle/

# Use a minimal settings.gradle with only required modules
COPY functions/java/word-stats-lite/settings.gradle.docker settings.gradle

# Copy only required modules
COPY platform/common/ common/
COPY sdks/java-lite/ sdks/java-lite/
COPY functions/java/word-stats-lite/ functions/java/word-stats-lite/

# Build the native binary
RUN ./gradlew :functions:java:word-stats-lite:nativeCompile --no-daemon

# Stage 2: minimal runtime
FROM gcr.io/distroless/base-debian13:nonroot
WORKDIR /app
COPY --from=builder --chown=nonroot:nonroot /build/functions/java/word-stats-lite/build/native/nativeCompile/word-stats-lite /app/word-stats-lite
EXPOSE 8080
ENTRYPOINT ["/app/word-stats-lite"]
