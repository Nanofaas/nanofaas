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
    # containerd-java resolves libcni-java from GitHub Packages, which needs credentials, unless a
    # checkout is named: build it against the staged libcni-java at LIBCNI_JAVA_REV instead.
    ./gradlew publishToMavenLocal -PlibcniDir="$stage/libcni-java" \
        -Dmaven.repo.local="$maven_repository" --no-daemon
)

receipt=$maven_repository/containerd-source-revisions.txt
{
    printf 'libcni-java source commit: %s\n' "$LIBCNI_JAVA_REV"
    printf 'containerd-java source commit: %s\n' "$CONTAINERD_JAVA_REV"
    printf 'Maven outputs: %s, %s, %s\n' \
        "$LIBCNI_JAVA_COORD" "$CONTAINERD_JAVA_COORD" "$CONTAINERD_JAVA_CNI_COORD"
    for coord in "$LIBCNI_JAVA_COORD" "$CONTAINERD_JAVA_COORD" "$CONTAINERD_JAVA_CNI_COORD"; do
        IFS=: read -r group artifact version <<< "$coord"
        (cd "$maven_repository" && sha256sum "${group//.//}/$artifact/$version/$artifact-$version.jar")
    done
} > "$receipt"
echo "Staged $LIBCNI_JAVA_COORD, $CONTAINERD_JAVA_COORD and $CONTAINERD_JAVA_CNI_COORD"
echo "Receipt: $receipt"
