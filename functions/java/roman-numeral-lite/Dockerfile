FROM ghcr.io/graalvm/native-image-community:21 AS builder

RUN microdnf install -y findutils && microdnf clean all

WORKDIR /build
COPY gradlew build.gradle gradle.properties ./
COPY gradle/ gradle/
COPY functions/java/roman-numeral-lite/settings.gradle.docker settings.gradle
COPY platform/common/ common/
COPY sdks/java-lite/ sdks/java-lite/
COPY functions/java/roman-numeral-lite/ functions/java/roman-numeral-lite/

RUN ./gradlew :functions:java:roman-numeral-lite:nativeCompile --no-daemon

FROM docker.io/library/debian:bookworm-slim
WORKDIR /app
COPY --from=builder /build/functions/java/roman-numeral-lite/build/native/nativeCompile/roman-numeral-lite /app/roman-numeral-lite
EXPOSE 8080
ENTRYPOINT ["/app/roman-numeral-lite"]
