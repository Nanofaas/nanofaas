#!/bin/bash
#
# Run watchdog tests locally (without containers)
#
# Prerequisites:
#   - Rust/Cargo
#   - Python 3
#   - jq, curl, nc (netcat)
#
# Usage:
#   ./test-local.sh              # Run all tests
#   ./test-local.sh --http       # Run only HTTP mode tests
#   ./test-local.sh --stdio      # Run only STDIO mode tests
#   ./test-local.sh --file       # Run only FILE mode tests
#   ./test-local.sh --callback   # Run only callback tests
#   ./test-local.sh --no-build   # Skip cargo build
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

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

# Check prerequisites
check_prerequisites() {
    local missing=()

    if ! command -v cargo &> /dev/null; then
        missing+=("cargo (Rust)")
    fi

    if ! command -v python3 &> /dev/null; then
        missing+=("python3")
    fi

    if ! command -v jq &> /dev/null; then
        missing+=("jq")
    fi

    if ! command -v curl &> /dev/null; then
        missing+=("curl")
    fi

    # Check for netcat (nc or netcat)
    if ! command -v nc &> /dev/null && ! command -v netcat &> /dev/null; then
        missing+=("nc (netcat)")
    fi

    if [ ${#missing[@]} -gt 0 ]; then
        log_error "Missing prerequisites:"
        for dep in "${missing[@]}"; do
            echo "  - $dep"
        done
        echo ""
        echo "Install with:"
        echo "  brew install jq curl netcat"
        echo "  # Rust: https://rustup.rs"
        exit 1
    fi

    log_info "All prerequisites found"
}

# Build watchdog
build_watchdog() {
    log_info "Building watchdog..."
    cargo build --release
    log_info "Build complete: target/release/nanofaas-watchdog"
}

# Setup test environment
setup_test_env() {
    # Export watchdog path
    export PATH="$SCRIPT_DIR/target/release:$PATH"
    export WATCHDOG_BIN="$SCRIPT_DIR/target/release/nanofaas-watchdog"
    export TESTS_ROOT="$SCRIPT_DIR/tests"

    # Create a convenience symlink for local runs
    mkdir -p "$SCRIPT_DIR/tests/.bin"
    ln -sf "$SCRIPT_DIR/target/release/nanofaas-watchdog" "$SCRIPT_DIR/tests/.bin/watchdog"

    # Update PATH for tests
    export PATH="$SCRIPT_DIR/tests/.bin:$PATH"

    # Make fixtures executable
    chmod +x "$SCRIPT_DIR/tests/fixtures/"*.py "$SCRIPT_DIR/tests/fixtures/"*.sh 2>/dev/null || true
    chmod +x "$SCRIPT_DIR/tests/integration/"*.sh 2>/dev/null || true
}

# Cleanup
cleanup() {
    # Kill any leftover test processes
    pkill -f "http_server.py" 2>/dev/null || true
    pkill -f "stdio_handler.py" 2>/dev/null || true
    pkill -f "file_handler.sh" 2>/dev/null || true
    pkill -f "callback_server.py" 2>/dev/null || true

    # Remove temp symlink dir
    rm -rf "$SCRIPT_DIR/tests/.bin" 2>/dev/null || true
}

trap cleanup EXIT

# Run tests
run_tests() {
    local test_suite="$1"

    cd "$SCRIPT_DIR/tests/integration"

    case "$test_suite" in
        all)
            log_info "Running all tests..."
            bash run_all.sh
            ;;
        http)
            log_info "Running HTTP mode tests..."
            bash -c 'source test_helpers.sh && source test_http_mode.sh && run_http_tests && print_summary'
            ;;
        stdio)
            log_info "Running STDIO mode tests..."
            bash -c 'source test_helpers.sh && source test_stdio_mode.sh && run_stdio_tests && print_summary'
            ;;
        file)
            log_info "Running FILE mode tests..."
            bash -c 'source test_helpers.sh && source test_file_mode.sh && run_file_tests && print_summary'
            ;;
        callback)
            log_info "Running callback tests..."
            bash -c 'source test_helpers.sh && source test_callback.sh && run_callback_tests && print_summary'
            ;;
    esac
}

# Print usage
usage() {
    cat << EOF
Usage: $0 [OPTIONS]

Run watchdog integration tests locally (no containers needed).

Options:
    --http          Run only HTTP mode tests
    --stdio         Run only STDIO mode tests
    --file          Run only FILE mode tests
    --callback      Run only callback tests
    --no-build      Skip cargo build (use existing binary)
    --help          Show this help message

Prerequisites:
    - Rust/Cargo (https://rustup.rs)
    - Python 3
    - jq (brew install jq)
    - curl
    - netcat (brew install netcat)

Examples:
    $0                  # Build and run all tests
    $0 --no-build       # Run tests with existing binary
    $0 --http           # Run only HTTP tests
EOF
}

# Main
main() {
    local skip_build="false"
    local test_suite="all"

    while [[ $# -gt 0 ]]; do
        case $1 in
            --http)
                test_suite="http"
                shift
                ;;
            --stdio)
                test_suite="stdio"
                shift
                ;;
            --file)
                test_suite="file"
                shift
                ;;
            --callback)
                test_suite="callback"
                shift
                ;;
            --no-build)
                skip_build="true"
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

    echo "========================================"
    echo "nanofaas Watchdog Local Tests"
    echo "========================================"
    echo ""

    # Check prerequisites
    check_prerequisites

    # Build if needed
    if [ "$skip_build" = "false" ]; then
        build_watchdog
    else
        if [ ! -f "$SCRIPT_DIR/target/release/nanofaas-watchdog" ]; then
            log_error "Watchdog binary not found. Run without --no-build first."
            exit 1
        fi
        log_info "Using existing watchdog binary"
    fi

    # Setup environment
    setup_test_env

    # Verify watchdog works
    log_info "Watchdog binary: $WATCHDOG_BIN"

    echo ""

    # Run tests
    run_tests "$test_suite"
}

main "$@"
