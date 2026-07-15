#!/bin/bash
set -euo pipefail

# Build script for nanofaas watchdog using Apple container/buildx
#
# Prerequisites:
#   - container (brew install container) OR docker with buildx
#   - Rust toolchain (for local builds)
#
# Usage:
#   ./build.sh                    # Build for current platform
#   ./build.sh --multi            # Build for linux/amd64 and linux/arm64
#   ./build.sh --push             # Build and push to registry
#   ./build.sh --local            # Build locally without container (requires Rust)

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Configuration
IMAGE_NAME="${IMAGE_NAME:-nanofaas/watchdog}"
IMAGE_TAG="${IMAGE_TAG:-latest}"
REGISTRY="${REGISTRY:-}"
PLATFORMS="${PLATFORMS:-linux/amd64,linux/arm64}"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

log_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Detect container runtime
detect_runtime() {
    if command -v container &> /dev/null; then
        echo "container"
    elif command -v docker &> /dev/null; then
        echo "docker"
    else
        log_error "No container runtime found. Install 'container' (brew install container) or Docker."
        exit 1
    fi
}

# Build using Apple container
build_with_container() {
    local push_flag="$1"
    local multi_arch="$2"

    log_info "Building with Apple container..."

    local full_image="${IMAGE_NAME}:${IMAGE_TAG}"
    if [[ -n "$REGISTRY" ]]; then
        full_image="${REGISTRY}/${full_image}"
    fi

    # Use simple Dockerfile for multi-arch (QEMU emulation, more reliable)
    local dockerfile="Dockerfile"
    if [[ "$multi_arch" == "true" ]]; then
        log_info "Multi-arch build: $PLATFORMS"
        log_info "Using Dockerfile.simple for QEMU-based cross-compilation"
        dockerfile="Dockerfile.simple"
    fi

    local build_args=(
        "build"
        "-t" "$full_image"
        "-f" "$dockerfile"
    )

    if [[ "$multi_arch" == "true" ]]; then
        build_args+=("--platform" "$PLATFORMS")
    fi

    if [[ "$push_flag" == "true" ]]; then
        build_args+=("--push")
    fi

    build_args+=(".")

    container "${build_args[@]}"

    log_info "Built: $full_image"
}

# Build using Docker buildx
build_with_docker() {
    local push_flag="$1"
    local multi_arch="$2"

    log_info "Building with Docker buildx..."

    local full_image="${IMAGE_NAME}:${IMAGE_TAG}"
    if [[ -n "$REGISTRY" ]]; then
        full_image="${REGISTRY}/${full_image}"
    fi

    # Ensure buildx builder exists
    if ! docker buildx inspect nanofaas-builder &> /dev/null; then
        log_info "Creating buildx builder..."
        docker buildx create --name nanofaas-builder --use --bootstrap
    else
        docker buildx use nanofaas-builder
    fi

    # Use simple Dockerfile for multi-arch (QEMU emulation, more reliable)
    local dockerfile="Dockerfile"
    if [[ "$multi_arch" == "true" ]]; then
        log_info "Multi-arch build: $PLATFORMS"
        log_info "Using Dockerfile.simple for QEMU-based cross-compilation"
        dockerfile="Dockerfile.simple"
    fi

    local build_args=(
        "buildx" "build"
        "-t" "$full_image"
        "-f" "$dockerfile"
    )

    if [[ "$multi_arch" == "true" ]]; then
        build_args+=("--platform" "$PLATFORMS")
    fi

    if [[ "$push_flag" == "true" ]]; then
        build_args+=("--push")
    else
        # Note: --load doesn't work with multi-arch, only single platform
        if [[ "$multi_arch" != "true" ]]; then
            build_args+=("--load")
        fi
    fi

    build_args+=(".")

    docker "${build_args[@]}"

    log_info "Built: $full_image"
}

# Build locally without container (requires Rust)
build_local() {
    log_info "Building locally with Cargo..."

    if ! command -v cargo &> /dev/null; then
        log_error "Rust/Cargo not found. Install from https://rustup.rs"
        exit 1
    fi

    # Build release
    cargo build --release

    local binary="target/release/nanofaas-watchdog"
    local size=$(du -h "$binary" | cut -f1)

    log_info "Built: $binary ($size)"

    # Strip binary for smaller size
    if command -v strip &> /dev/null; then
        strip "$binary"
        size=$(du -h "$binary" | cut -f1)
        log_info "Stripped: $binary ($size)"
    fi
}

# Build for musl target (static binary for Alpine/scratch)
build_local_musl() {
    log_info "Building static musl binary..."

    if ! command -v cargo &> /dev/null; then
        log_error "Rust/Cargo not found. Install from https://rustup.rs"
        exit 1
    fi

    # Check if musl target is installed
    local target="x86_64-unknown-linux-musl"
    if [[ "$(uname -m)" == "arm64" ]]; then
        target="aarch64-unknown-linux-musl"
    fi

    if ! rustup target list --installed | grep -q "$target"; then
        log_info "Installing musl target: $target"
        rustup target add "$target"
    fi

    # Build with musl
    cargo build --release --target "$target"

    local binary="target/${target}/release/nanofaas-watchdog"
    local size=$(du -h "$binary" | cut -f1)

    log_info "Built: $binary ($size)"
}

# Build combined image (watchdog + Java runtime)
build_combined() {
    local runtime="$1"
    local push_flag="$2"

    log_info "Building combined image with $runtime..."

    local full_image="${IMAGE_NAME}:${IMAGE_TAG}-combined"
    if [[ -n "$REGISTRY" ]]; then
        full_image="${REGISTRY}/${full_image}"
    fi

    local build_args=(
        "build"
        "-t" "$full_image"
        "-f" "Dockerfile.combined"
        ".."
    )

    if [[ "$push_flag" == "true" ]]; then
        build_args+=("--push")
    fi

    if [[ "$runtime" == "container" ]]; then
        container "${build_args[@]}"
    else
        docker "${build_args[@]}"
    fi

    log_info "Built: $full_image"
}

# Print usage
usage() {
    cat << EOF
Usage: $0 [OPTIONS]

Build the nanofaas watchdog container image.

Options:
    --local         Build locally with Cargo (requires Rust)
    --musl          Build static musl binary locally
    --multi         Build for multiple architectures (amd64, arm64)
    --push          Push image to registry after build
    --combined      Build combined image (watchdog + Java runtime)
    --tag TAG       Set image tag (default: latest)
    --registry REG  Set registry prefix
    --help          Show this help message

Environment Variables:
    IMAGE_NAME      Image name (default: nanofaas/watchdog)
    IMAGE_TAG       Image tag (default: latest)
    REGISTRY        Registry prefix (default: none)
    PLATFORMS       Target platforms (default: linux/amd64,linux/arm64)

Examples:
    $0                              # Build for current platform
    $0 --multi --push               # Build multi-arch and push
    $0 --local                      # Build locally with Cargo
    $0 --tag v0.5.0 --push          # Build and push with specific tag
    $0 --registry ghcr.io/myorg     # Build with registry prefix
EOF
}

# Main
main() {
    local push_flag="false"
    local multi_arch="false"
    local local_build="false"
    local musl_build="false"
    local combined="false"

    while [[ $# -gt 0 ]]; do
        case $1 in
            --local)
                local_build="true"
                shift
                ;;
            --musl)
                musl_build="true"
                shift
                ;;
            --multi)
                multi_arch="true"
                shift
                ;;
            --push)
                push_flag="true"
                shift
                ;;
            --combined)
                combined="true"
                shift
                ;;
            --tag)
                IMAGE_TAG="$2"
                shift 2
                ;;
            --registry)
                REGISTRY="$2"
                shift 2
                ;;
            --help|-h)
                usage
                exit 0
                ;;
            *)
                log_error "Unknown option: $1"
                usage
                exit 1
                ;;
        esac
    done

    # Local builds
    if [[ "$local_build" == "true" ]]; then
        build_local
        exit 0
    fi

    if [[ "$musl_build" == "true" ]]; then
        build_local_musl
        exit 0
    fi

    # Container builds
    local runtime
    runtime=$(detect_runtime)
    log_info "Using container runtime: $runtime"

    if [[ "$combined" == "true" ]]; then
        build_combined "$runtime" "$push_flag"
    elif [[ "$runtime" == "container" ]]; then
        build_with_container "$push_flag" "$multi_arch"
    else
        build_with_docker "$push_flag" "$multi_arch"
    fi

    log_info "Build complete!"
}

main "$@"
