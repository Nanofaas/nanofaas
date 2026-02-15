# Multi-stage native build for json-transform-lite.
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
COPY examples/java/json-transform-lite/settings.gradle.docker settings.gradle

# Copy only required modules
COPY common/ common/
COPY function-sdk-java-lite/ function-sdk-java-lite/
COPY examples/java/json-transform-lite/ examples/java/json-transform-lite/

# Build the native binary
RUN ./gradlew :examples:java:json-transform-lite:nativeCompile --no-daemon

# Stage 2: minimal runtime
FROM debian:bookworm-slim
WORKDIR /app
COPY --from=builder /build/examples/java/json-transform-lite/build/native/nativeCompile/json-transform-lite /app/json-transform-lite
EXPOSE 8080
ENTRYPOINT ["/app/json-transform-lite"]
