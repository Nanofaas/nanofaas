FROM oraclelinux:9-slim AS builder

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

ARG NATIVE_TASK
ARG NATIVE_BINARY
ARG GRADLE_ARGS
RUN ./gradlew "$NATIVE_TASK" $GRADLE_ARGS --no-daemon && \
    cp "$NATIVE_BINARY" /tmp/application

# `cc` rather than `base`: it carries libstdc++, which a G1 binary loads at run
# time because the collector itself is C++. A serial binary does not need it and
# only pays the few megabytes, which is cheaper than two runtime images that can
# drift apart — the base image failed at startup, not at build, with
# "libstdc++.so.6: cannot open shared object file".
FROM gcr.io/distroless/cc-debian13:nonroot
WORKDIR /app
COPY --from=builder --chown=nonroot:nonroot /tmp/application /app/application
EXPOSE 8080 8081
ENTRYPOINT ["/app/application"]
