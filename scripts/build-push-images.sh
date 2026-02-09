#!/usr/bin/env bash
#
# Build and push all nanofaas OCI images.
#
# Usage:
#   ./scripts/build-push-images.sh                  # build+push all, version from build.gradle
#   ./scripts/build-push-images.sh --tag v0.7.0     # explicit tag
#   ./scripts/build-push-images.sh --no-push        # build only, skip docker push
#   ./scripts/build-push-images.sh --only java-demos # build a subset (see --help)
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# --- defaults ----------------------------------------------------------------
REGISTRY="ghcr.io"
GH_OWNER="miciav"
GH_REPO="nanofaas"
TAG=""
PUSH=true
PLATFORM=""
TARGETS="all"

usage() {
    cat <<EOF
Usage: $(basename "$0") [OPTIONS]

Build and push nanofaas OCI images to $REGISTRY/$GH_OWNER/$GH_REPO.

Options:
  --tag TAG          Image tag (default: vVERSION from build.gradle)
  --no-push          Build images without pushing
  --platform PLAT    Docker --platform flag, e.g. linux/arm64 (default: native)
  --suffix SUFFIX    Tag suffix, e.g. "-arm64" (default: none)
  --only TARGETS     Comma-separated list of targets to build:
                       control-plane, function-runtime, watchdog,
                       java-demos, python-demos, all (default: all)
  -h, --help         Show this help message
EOF
    exit 0
}

TAG_SUFFIX=""

# --- parse args --------------------------------------------------------------
while [[ $# -gt 0 ]]; do
    case "$1" in
        --tag)       TAG="$2"; shift 2 ;;
        --no-push)   PUSH=false; shift ;;
        --platform)  PLATFORM="$2"; shift 2 ;;
        --suffix)    TAG_SUFFIX="$2"; shift 2 ;;
        --only)      TARGETS="$2"; shift 2 ;;
        -h|--help)   usage ;;
        *) echo "Unknown option: $1"; usage ;;
    esac
done

# --- resolve tag from build.gradle if not provided ---------------------------
if [[ -z "$TAG" ]]; then
    VERSION=$(grep -m1 "version\s*=" "$ROOT/build.gradle" | sed "s/.*'\(.*\)'.*/\1/")
    TAG="v${VERSION}"
fi

BASE="${REGISTRY}/${GH_OWNER}/${GH_REPO}"
OCI_SOURCE="https://github.com/${GH_OWNER}/${GH_REPO}"

# --- helpers -----------------------------------------------------------------
info()  { echo -e "\033[1;34m==> $*\033[0m"; }
ok()    { echo -e "\033[1;32m  ✓ $*\033[0m"; }
warn()  { echo -e "\033[1;33m  ⚠ $*\033[0m"; }

should_build() {
    [[ "$TARGETS" == "all" ]] || [[ ",$TARGETS," == *",$1,"* ]]
}

push_image() {
    if $PUSH; then
        docker push "$1"
        ok "Pushed $1"
    fi
}

DOCKER_PLATFORM_FLAG=""
if [[ -n "$PLATFORM" ]]; then
    DOCKER_PLATFORM_FLAG="--platform $PLATFORM"
fi

cd "$ROOT"

# --- 1. Control Plane --------------------------------------------------------
if should_build "control-plane"; then
    IMG="${BASE}/control-plane:${TAG}${TAG_SUFFIX}"
    info "Building control-plane → $IMG"
    BP_OCI_SOURCE="$OCI_SOURCE" ./gradlew :control-plane:bootBuildImage \
        -PcontrolPlaneImage="$IMG"
    ok "Built $IMG"
    push_image "$IMG"
fi

# --- 2. Function Runtime -----------------------------------------------------
if should_build "function-runtime"; then
    IMG="${BASE}/function-runtime:${TAG}${TAG_SUFFIX}"
    info "Building function-runtime → $IMG"
    BP_OCI_SOURCE="$OCI_SOURCE" ./gradlew :function-runtime:bootBuildImage \
        -PfunctionRuntimeImage="$IMG" || { warn "function-runtime build failed, skipping"; }
    if docker image inspect "$IMG" &>/dev/null; then
        ok "Built $IMG"
        push_image "$IMG"
    fi
fi

# --- 3. Watchdog --------------------------------------------------------------
if should_build "watchdog"; then
    IMG="${BASE}/watchdog:${TAG}${TAG_SUFFIX}"
    info "Building watchdog → $IMG"
    docker build $DOCKER_PLATFORM_FLAG \
        --label "org.opencontainers.image.source=$OCI_SOURCE" \
        -t "$IMG" watchdog/ || { warn "watchdog build failed, skipping"; }
    if docker image inspect "$IMG" &>/dev/null; then
        ok "Built $IMG"
        push_image "$IMG"
    fi
fi

# --- 4. Java Demo Functions ---------------------------------------------------
if should_build "java-demos"; then
    for example in word-stats json-transform; do
        IMG="${BASE}/java-${example}:${TAG}${TAG_SUFFIX}"
        info "Building java/${example} → $IMG"
        BP_OCI_SOURCE="$OCI_SOURCE" ./gradlew ":examples:java:${example}:bootBuildImage" \
            -PfunctionImage="$IMG"
        ok "Built $IMG"
        push_image "$IMG"
    done
fi

# --- 5. Python Demo Functions -------------------------------------------------
if should_build "python-demos"; then
    for example in word-stats json-transform; do
        IMG="${BASE}/python-${example}:${TAG}${TAG_SUFFIX}"
        info "Building python/${example} → $IMG"
        docker build $DOCKER_PLATFORM_FLAG \
            --label "org.opencontainers.image.source=$OCI_SOURCE" \
            -t "$IMG" -f "examples/python/${example}/Dockerfile" .
        ok "Built $IMG"
        push_image "$IMG"
    done
fi

# --- done ---------------------------------------------------------------------
echo ""
info "Done! Images tagged with ${TAG}${TAG_SUFFIX}"
if ! $PUSH; then
    warn "Push was skipped (--no-push). Run 'docker push <image>' manually."
fi
