ARG BUILDER_IMAGE=oraclelinux:9-slim
ARG RUNTIME_IMAGE=gcr.io/distroless/cc-debian13:nonroot

FROM ${BUILDER_IMAGE} AS builder

ARG TARGETARCH
# gcc-c++ is needed only by G1: its collector library (libg1gc-cr) is C++, and
# linking it fails with "cannot find -lstdc++". The serial collector needs none
# of it, which is why nothing here was required until G1 was tried.
#
# gcc-c++ and not libstdc++-devel, which is the obvious guess and does not work.
# On Oracle Linux 9 that package installs cleanly and ships only
# libstdc++fs.a — no libstdc++.so for the linker to find — so a build reaches
# the link step after eleven minutes of compiling and dies there. gcc-c++ brings
# /usr/lib/gcc/x86_64-redhat-linux/11/libstdc++.so, which is what `-lstdc++`
# resolves against.
RUN microdnf install -y ca-certificates curl findutils gcc gcc-c++ glibc-devel gzip tar \
      zlib-devel && microdnf clean all

# `oracle` only where G1 is wanted: Community's Native Image ships a serial
# collector and nothing else, which cost 32% of wall-clock time on a sustained
# load. Oracle GraalVM is GFTC-licensed, so it stays opt-in.
ARG GRAALVM_DISTRIBUTION=community
COPY gradle.properties scripts/install-graalvm.sh /tmp/
RUN graalvm_version="$(sed -n 's/^graalvmVersion=//p' /tmp/gradle.properties)" && \
    graalvm_java_version="$(sed -n 's/^graalvmJavaVersion=//p' /tmp/gradle.properties)" && \
    sh /tmp/install-graalvm.sh "$TARGETARCH" "$graalvm_version" "$graalvm_java_version" \
       "$GRAALVM_DISTRIBUTION"

ENV JAVA_HOME=/opt/graalvm
ENV PATH="$JAVA_HOME/bin:$PATH"

WORKDIR /workspace
COPY . .
# The named context is an empty directory for ordinary builds. For the optional
# containerd provider, the image script supplies the pinned local Maven staging
# directory instead; .dockerignore deliberately excludes the host's .gradle tree.
COPY --from=containerd_maven_repo . /tmp/containerd-m2/

ARG NATIVE_TASK
ARG NATIVE_BINARY
ARG GRADLE_ARGS
# The Gradle user home lives in a BuildKit cache mount: dependencies and the Gradle distribution
# survive across builds (nanolab assembles several variants in a row). It is not part of the image.
# Locked, because concurrent builds on one builder, and the platforms of one multi-platform build,
# would otherwise run Gradle in one user home at the same time from separate network namespaces,
# where Gradle cannot hand its lock over.
RUN --mount=type=cache,target=/root/.gradle,sharing=locked ./gradlew "$NATIVE_TASK" $GRADLE_ARGS --no-daemon && \
    cp "$NATIVE_BINARY" /tmp/application && \
    mkdir -p /var/lib/nanofaas && touch /var/lib/nanofaas/.keep

# assembleRecipe's container builder exports only the executable
# (docker build --target native-executable --output type=local,...) and packages it itself.
FROM scratch AS native-executable
COPY --from=builder /tmp/application /application

# `cc` rather than `base`: it carries libstdc++, which a G1 binary loads at run
# time because the collector itself is C++. A serial binary does not need it and
# only pays the few megabytes, which is cheaper than two runtime images that can
# drift apart — the base image failed at startup, not at build, with
# "libstdc++.so.6: cannot open shared object file".
#
# assembleRecipe's multi-architecture path (registry.platforms) compiles and packages a
# container-built native image in this one build, so the image's provenance names the builder
# stage above. The staged runtime files come from the named context `recipe`; the layout
# follows deploy/recipes/Dockerfile.native.
FROM ${RUNTIME_IMAGE} AS recipe-native
ARG BUILDER_IMAGE
ARG RUNTIME_IMAGE
ENV NANOFAAS_BUILD_BASE_IMAGE=${BUILDER_IMAGE}
ENV NANOFAAS_RUNTIME_BASE_IMAGE=${RUNTIME_IMAGE}
ENV NANOFAAS_REGISTRY_PATH=/var/lib/nanofaas/functions.json
ENV SPRING_CONFIG_ADDITIONALLOCATION=optional:file:/app/config/recipe.yaml
WORKDIR /app
COPY --from=builder --chown=nonroot:nonroot /var/lib/nanofaas /var/lib/nanofaas
COPY --from=recipe . /app/
COPY --from=builder /tmp/application /app/application
EXPOSE 8080 8081
ENTRYPOINT ["/app/application"]

# The release builds the default target, this final stage, which must stay last.
FROM ${RUNTIME_IMAGE}
ARG BUILDER_IMAGE
ARG RUNTIME_IMAGE
ENV NANOFAAS_BUILD_BASE_IMAGE=${BUILDER_IMAGE}
ENV NANOFAAS_RUNTIME_BASE_IMAGE=${RUNTIME_IMAGE}
ENV NANOFAAS_REGISTRY_PATH=/var/lib/nanofaas/functions.json
WORKDIR /app
COPY --from=builder --chown=nonroot:nonroot /tmp/application /app/application
COPY --from=builder --chown=nonroot:nonroot /var/lib/nanofaas /var/lib/nanofaas
EXPOSE 8080 8081
ENTRYPOINT ["/app/application"]
