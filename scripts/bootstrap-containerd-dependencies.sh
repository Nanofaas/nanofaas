#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
# shellcheck source=../deploy/containerd-rootless/dependencies.env
source "$repo_root/deploy/containerd-rootless/dependencies.env"

if [[ $# -lt 2 || $# -gt 3 ]]; then
    echo "Usage: $0 LIBCNI_JAVA_CHECKOUT CONTAINERD_JAVA_CHECKOUT [MAVEN_REPOSITORY]" >&2
    exit 2
fi

libcni_checkout=$1
containerd_checkout=$2
maven_repository=${3:-$repo_root/.gradle/containerd-m2}
mkdir -p "$maven_repository"
maven_repository=$(cd "$maven_repository" && pwd)

for input in "$libcni_checkout:$LIBCNI_JAVA_REV" "$containerd_checkout:$CONTAINERD_JAVA_REV"; do
    checkout=${input%%:*}
    revision=${input#*:}
    git -C "$checkout" cat-file -e "$revision^{commit}" || {
        echo "Required commit $revision is missing from $checkout" >&2
        exit 1
    }
done

stage=$(mktemp -d)
trap 'rm -rf "$stage"' EXIT
mkdir -p "$stage/libcni-java" "$stage/containerd-java"
git -C "$libcni_checkout" archive "$LIBCNI_JAVA_REV" | tar -x -C "$stage/libcni-java"
git -C "$containerd_checkout" archive "$CONTAINERD_JAVA_REV" | tar -x -C "$stage/containerd-java"

(
    cd "$stage/libcni-java"
    ./gradlew publishToMavenLocal -Dmaven.repo.local="$maven_repository" --no-daemon
)
(
    cd "$stage/containerd-java"
    ./gradlew publishToMavenLocal -PlibcniFromPackages=true \
        -Dmaven.repo.local="$maven_repository" --no-daemon
)

receipt=$maven_repository/containerd-source-revisions.txt
{
    printf 'libcni-java source commit: %s\n' "$LIBCNI_JAVA_REV"
    printf 'containerd-java source commit: %s\n' "$CONTAINERD_JAVA_REV"
    printf 'Maven outputs: %s, %s, %s\n' \
        "$LIBCNI_JAVA_COORD" "$CONTAINERD_JAVA_COORD" "$CONTAINERD_JAVA_CNI_COORD"
    for artifact in \
        io/libcni/libcni-java/0.1.1-SNAPSHOT/libcni-java-0.1.1-SNAPSHOT.jar \
        io/nanofaas/containerd-java/0.4.0-SNAPSHOT/containerd-java-0.4.0-SNAPSHOT.jar \
        io/nanofaas/containerd-java-cni/0.4.0-SNAPSHOT/containerd-java-cni-0.4.0-SNAPSHOT.jar; do
        (cd "$maven_repository" && sha256sum "$artifact")
    done
} > "$receipt"
echo "Staged $LIBCNI_JAVA_COORD, $CONTAINERD_JAVA_COORD and $CONTAINERD_JAVA_CNI_COORD"
echo "Receipt: $receipt"
