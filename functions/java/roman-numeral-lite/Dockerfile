FROM oraclelinux:9-slim AS builder

ARG TARGETARCH
RUN microdnf install -y ca-certificates curl findutils gcc glibc-devel gzip tar zlib-devel && microdnf clean all
COPY gradle.properties scripts/install-graalvm.sh /tmp/
RUN sh /tmp/install-graalvm.sh "$TARGETARCH" "$(sed -n 's/^graalvmVersion=//p' /tmp/gradle.properties)" "$(sed -n 's/^graalvmJavaVersion=//p' /tmp/gradle.properties)"
ENV JAVA_HOME=/opt/graalvm
ENV PATH="$JAVA_HOME/bin:$PATH"

WORKDIR /build
COPY gradlew build.gradle gradle.properties ./
COPY gradle/ gradle/
COPY functions/java/roman-numeral-lite/settings.gradle.docker settings.gradle
COPY platform/common/ common/
COPY sdks/java-lite/ sdks/java-lite/
COPY functions/java/roman-numeral-lite/ functions/java/roman-numeral-lite/

RUN ./gradlew :functions:java:roman-numeral-lite:nativeCompile --no-daemon

FROM gcr.io/distroless/base-debian13:nonroot
WORKDIR /app
COPY --from=builder --chown=nonroot:nonroot /build/functions/java/roman-numeral-lite/build/native/nativeCompile/roman-numeral-lite /app/roman-numeral-lite
EXPOSE 8080
ENTRYPOINT ["/app/roman-numeral-lite"]
