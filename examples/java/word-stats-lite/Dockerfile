# Multi-stage native build for word-stats-lite.
# Stage 1: GraalVM native-image compilation via Gradle.
# Stage 2: Minimal runtime image with the static binary.

FROM ghcr.io/graalvm/native-image-community:21 AS builder

# Install findutils (provides xargs, required by gradlew)
RUN microdnf install -y findutils && microdnf clean all

WORKDIR /build

# Copy Gradle wrapper and root build config
COPY gradlew build.gradle gradle.properties ./
COPY gradle/ gradle/

# Use a minimal settings.gradle with only required modules
COPY examples/java/word-stats-lite/settings.gradle.docker settings.gradle

# Copy only required modules
COPY common/ common/
COPY function-sdk-java-lite/ function-sdk-java-lite/
COPY examples/java/word-stats-lite/ examples/java/word-stats-lite/

# Build the native binary
RUN ./gradlew :examples:java:word-stats-lite:nativeCompile --no-daemon

# Stage 2: minimal runtime
FROM debian:bookworm-slim
WORKDIR /app
COPY --from=builder /build/examples/java/word-stats-lite/build/native/nativeCompile/word-stats-lite /app/word-stats-lite
EXPOSE 8080
ENTRYPOINT ["/app/word-stats-lite"]
