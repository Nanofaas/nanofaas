#!/bin/bash
#
# nanofaas Watchdog Test Runner
#
# Builds the test image and runs all integration tests using Apple container.
#
# Usage:
#   ./test.sh              # Run all tests
#   ./test.sh --build      # Force rebuild test image
#   ./test.sh --http       # Run only HTTP mode tests
#   ./test.sh --stdio      # Run only STDIO mode tests
#   ./test.sh --file       # Run only FILE mode tests
#   ./test.sh --callback   # Run only callback tests
#   ./test.sh --shell      # Start interactive shell in test container
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Configuration
IMAGE_NAME="nanofaas/watchdog-test"
IMAGE_TAG="latest"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

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
        # Check if container service is running
        if container system status &> /dev/null; then
            echo "container"
            return
        fi
    fi
    if command -v docker &> /dev/null; then
        echo "docker"
        return
    fi
    log_error "No container runtime found."
    log_error "Install 'container' (brew install container) or Docker."
    exit 1
}

# Build test image
build_test_image() {
    local runtime="$1"
    local force_build="${2:-false}"

    log_info "Building test image..."

    if [ "$runtime" = "container" ]; then
        container build -t "$IMAGE_NAME:$IMAGE_TAG" -f tests/Dockerfile.test .
    else
        docker build -t "$IMAGE_NAME:$IMAGE_TAG" -f tests/Dockerfile.test .
    fi

    log_info "Test image built: $IMAGE_NAME:$IMAGE_TAG"
}

# Check if image exists
image_exists() {
    local runtime="$1"

    if [ "$runtime" = "container" ]; then
        # container doesn't have easy image listing, assume rebuild needed
        return 1
    else
        docker image inspect "$IMAGE_NAME:$IMAGE_TAG" &> /dev/null
    fi
}

# Run tests in container
run_tests() {
    local runtime="$1"
    local test_cmd="${2:-/tests/integration/run_all.sh}"

    log_info "Running tests..."

    if [ "$runtime" = "container" ]; then
        container run --rm -it "$IMAGE_NAME:$IMAGE_TAG" $test_cmd
    else
        docker run --rm -it "$IMAGE_NAME:$IMAGE_TAG" $test_cmd
    fi
}

# Run interactive shell
run_shell() {
    local runtime="$1"

    log_info "Starting interactive shell..."

    if [ "$runtime" = "container" ]; then
        container run --rm -it "$IMAGE_NAME:$IMAGE_TAG" /bin/bash
    else
        docker run --rm -it "$IMAGE_NAME:$IMAGE_TAG" /bin/bash
    fi
}

# Print usage
usage() {
    cat << EOF
Usage: $0 [OPTIONS]

Run integration tests for the nanofaas watchdog.

Options:
    --build         Force rebuild test image
    --http          Run only HTTP mode tests
    --stdio         Run only STDIO mode tests
    --file          Run only FILE mode tests
    --callback      Run only callback tests
    --shell         Start interactive shell in test container
    --help          Show this help message

Examples:
    $0                  # Run all tests
    $0 --build          # Rebuild and run all tests
    $0 --http           # Run only HTTP tests
    $0 --shell          # Debug in container

Test Coverage:
    HTTP Mode:     Server startup, health checks, timeouts, errors
    STDIO Mode:    stdin/stdout handling, exit codes, JSON parsing
    FILE Mode:     File I/O, path handling, permissions
    Callback:      Retry logic, error reporting, trace propagation
EOF
}

# Main
main() {
    local force_build="false"
    local test_cmd="/tests/integration/run_all.sh"
    local run_shell_flag="false"

    while [[ $# -gt 0 ]]; do
        case $1 in
            --build)
                force_build="true"
                shift
                ;;
            --http)
                test_cmd="bash -c 'source /tests/integration/test_helpers.sh && source /tests/integration/test_http_mode.sh && run_http_tests && print_summary'"
                shift
                ;;
            --stdio)
                test_cmd="bash -c 'source /tests/integration/test_helpers.sh && source /tests/integration/test_stdio_mode.sh && run_stdio_tests && print_summary'"
                shift
                ;;
            --file)
                test_cmd="bash -c 'source /tests/integration/test_helpers.sh && source /tests/integration/test_file_mode.sh && run_file_tests && print_summary'"
                shift
                ;;
            --callback)
                test_cmd="bash -c 'source /tests/integration/test_helpers.sh && source /tests/integration/test_callback.sh && run_callback_tests && print_summary'"
                shift
                ;;
            --shell)
                run_shell_flag="true"
                shift
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

    # Detect runtime
    local runtime
    runtime=$(detect_runtime)
    log_info "Using container runtime: $runtime"

    # Start container service if needed
    if [ "$runtime" = "container" ]; then
        if ! container system status &> /dev/null; then
            log_info "Starting container service..."
            container system start
            sleep 2
        fi
    fi

    # Build image if needed
    if [ "$force_build" = "true" ] || ! image_exists "$runtime"; then
        build_test_image "$runtime" "$force_build"
    else
        log_info "Using existing test image"
    fi

    # Run tests or shell
    if [ "$run_shell_flag" = "true" ]; then
        run_shell "$runtime"
    else
        run_tests "$runtime" "$test_cmd"
    fi
}

main "$@"
