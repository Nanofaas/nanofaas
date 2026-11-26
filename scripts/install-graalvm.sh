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

# GraalVM 25.2.4 and 25.0.4 both use JDK 25.0.4, but only the innovation
# distribution matches Truffle 25.2.4. Pin the Graal release as well as the JDK.
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
    oracle:25.2.4:25.0.4:amd64)
        archive="graalvm-jdk-25i2-25.0.4_linux-x64_bin.tar.gz"
        checksum="7100d99cbfec68b03b669cc60c7e8592bbcda1732e8eaebc460fe0b75849a894"
        url="https://gds.oracle.com/download/graal/25i2/archive/$archive"
        ;;
    oracle:25.2.4:25.0.4:arm64)
        archive="graalvm-jdk-25i2-25.0.4_linux-aarch64_bin.tar.gz"
        checksum="0bc65f9c36ae77bd83aad46a2b4de4b0ec97da1b4ac83fedb59e19f868873dee"
        url="https://gds.oracle.com/download/graal/25i2/archive/$archive"
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
