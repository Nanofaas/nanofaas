#!/bin/sh
# Installs the GraalVM used to compile native images, from either distribution.
#
# Community is the default and carries the GPL terms the rest of this project
# assumes. Oracle GraalVM exists here for one measured reason: its Native Image
# ships G1, and Community ships only a serial collector. On a sustained WebFlux
# load the serial collector spent 32% of wall-clock time in stop-the-world
# collection, with complete collections averaging 883ms and a tail reaching
# 1.9s, against 0.9% and ~50ms for the same code on HotSpot. No heap size
# avoided it: a 512MB heap merely traded 23 long pauses for 81 shorter ones.
#
# Oracle GraalVM is distributed under the GFTC licence, which is not GPL. Choosing
# it is a licensing decision, which is why it is opt-in rather than the default.
set -eu

architecture="${1:-}"
release="${2:-}"
java_version="${3:-}"
distribution="${4:-community}"

case "$distribution:$release:$java_version:$architecture" in
    community:25.2.4:25.0.4:amd64)
        archive="graalvm-community-jdk-25i2-25.0.4_linux-x64_bin.tar.gz"
        checksum="3f4a89de8eaa96f2ed677f09957c7e872cd8467aad3537f8b5394c1b8c4b942e"
        url="https://github.com/graalvm/graalvm-ce-builds/releases/download/graal-$release/$archive"
        ;;
    community:25.2.4:25.0.4:arm64)
        archive="graalvm-community-jdk-25i2-25.0.4_linux-aarch64_bin.tar.gz"
        checksum="22286f7ecd21b9aedb3226b9bf797469e1bd3eefc491e12ef3dd49b452d230b7"
        url="https://github.com/graalvm/graalvm-ce-builds/releases/download/graal-$release/$archive"
        ;;
    oracle:*:25.0.4:amd64)
        archive="graalvm-jdk-25.0.4_linux-x64_bin.tar.gz"
        checksum="76007c309f821aaf435bce63162ea0395587fc77350801c81643fe7feea37276"
        url="https://download.oracle.com/graalvm/25/archive/$archive"
        ;;
    oracle:*:25.0.4:arm64)
        archive="graalvm-jdk-25.0.4_linux-aarch64_bin.tar.gz"
        checksum="7031aead8da4c6a7816c4e2be4eabfddaf7f4abcfe8e5f16134c4da69a5e70de"
        url="https://download.oracle.com/graalvm/25/archive/$archive"
        ;;
    *)
        echo "Unsupported GraalVM: ${distribution:-unset}/${release:-unset}/${java_version:-unset}/${architecture:-unset}" >&2
        exit 1
        ;;
esac

curl -fsSL "$url" -o /tmp/graalvm.tar.gz
echo "$checksum  /tmp/graalvm.tar.gz" | sha256sum -c -
mkdir -p /opt/graalvm
tar -xzf /tmp/graalvm.tar.gz -C /opt/graalvm --strip-components=1
rm /tmp/graalvm.tar.gz
