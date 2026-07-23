# Multi-arch build stage. The builder runs on the TARGET platform (QEMU for
# non-native arches): cross-compiling from amd64 would need an
# aarch64-linux-musl cross gcc for ring's C code, which alpine does not ship.
FROM rust:1.83-alpine AS builder

RUN apk add --no-cache musl-dev

WORKDIR /build
COPY Cargo.toml Cargo.lock* ./
COPY src ./src

# Build release binary with static linking. The explicit --target keeps
# RUSTFLAGS away from proc-macros (host dylibs, incompatible with
# crt-static on musl). strip failures are tolerated, build failures are not.
RUN RUST_TARGET="$(uname -m)-unknown-linux-musl" && \
    RUSTFLAGS='-C target-feature=+crt-static' cargo build --release --target "$RUST_TARGET" && \
    cp "target/${RUST_TARGET}/release/nanofaas-watchdog" /watchdog && \
    (strip /watchdog || true)

# Runtime stage - scratch image for minimal size (~2MB)
FROM scratch

LABEL org.opencontainers.image.title="mcFaas Watchdog"
LABEL org.opencontainers.image.description="Lightweight Rust process supervisor for function containers"
LABEL org.opencontainers.image.source="https://github.com/nanofaas/nanofaas"

COPY --from=builder /watchdog /watchdog

ENTRYPOINT ["/watchdog"]
