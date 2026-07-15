# Multi-arch build stage
FROM --platform=$BUILDPLATFORM rust:1.83-alpine AS builder

ARG TARGETPLATFORM
ARG BUILDPLATFORM
ARG TARGETOS
ARG TARGETARCH

RUN apk add --no-cache musl-dev

# Install cross-compilation targets
RUN case "$TARGETARCH" in \
        amd64) RUST_TARGET="x86_64-unknown-linux-musl" ;; \
        arm64) RUST_TARGET="aarch64-unknown-linux-musl" ;; \
        *) echo "Unsupported arch: $TARGETARCH" && exit 1 ;; \
    esac && \
    rustup target add "$RUST_TARGET" && \
    echo "$RUST_TARGET" > /rust_target

# Install cross-compiler for arm64 if building on amd64
RUN if [ "$BUILDPLATFORM" = "linux/amd64" ] && [ "$TARGETARCH" = "arm64" ]; then \
        apk add --no-cache gcc-aarch64-none-elf; \
    fi

WORKDIR /build
COPY Cargo.toml Cargo.lock* ./
COPY src ./src

# Build release binary with static linking
RUN RUST_TARGET=$(cat /rust_target) && \
    RUSTFLAGS='-C target-feature=+crt-static' cargo build --release --target "$RUST_TARGET" && \
    cp "target/${RUST_TARGET}/release/nanofaas-watchdog" /watchdog && \
    strip /watchdog || true

# Runtime stage - scratch image for minimal size (~2MB)
FROM scratch

LABEL org.opencontainers.image.title="mcFaas Watchdog"
LABEL org.opencontainers.image.description="Lightweight Rust process supervisor for function containers"
LABEL org.opencontainers.image.source="https://github.com/nanofaas/nanofaas"

COPY --from=builder /watchdog /watchdog

ENTRYPOINT ["/watchdog"]
