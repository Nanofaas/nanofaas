#!/bin/sh
set -eu

architecture="${1:-}"
release="${2:-}"
java_version="${3:-}"

case "$release:$java_version:$architecture" in
    25.2.4:25.0.4:amd64)
        archive="graalvm-community-jdk-25i2-25.0.4_linux-x64_bin.tar.gz"
        checksum="3f4a89de8eaa96f2ed677f09957c7e872cd8467aad3537f8b5394c1b8c4b942e"
        ;;
    25.2.4:25.0.4:arm64)
        archive="graalvm-community-jdk-25i2-25.0.4_linux-aarch64_bin.tar.gz"
        checksum="22286f7ecd21b9aedb3226b9bf797469e1bd3eefc491e12ef3dd49b452d230b7"
        ;;
    *)
        echo "Unsupported GraalVM release: ${release:-unset}/${java_version:-unset}/${architecture:-unset}" >&2
        exit 1
        ;;
esac

curl -fsSL "https://github.com/graalvm/graalvm-ce-builds/releases/download/graal-$release/$archive" -o /tmp/graalvm.tar.gz
echo "$checksum  /tmp/graalvm.tar.gz" | sha256sum -c -
mkdir -p /opt/graalvm
tar -xzf /tmp/graalvm.tar.gz -C /opt/graalvm --strip-components=1
rm /tmp/graalvm.tar.gz
