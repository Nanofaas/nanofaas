FROM rust:1.86-bookworm AS builder

WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends musl-tools ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# Detect native arch and add the corresponding musl target.
# HOST stays *-linux-gnu so proc-macro crates (axum-macros etc.) compile normally.
# TARGET is *-linux-musl so the final binary is fully statically linked.
RUN ARCH=$(uname -m) && \
    case "$ARCH" in \
      x86_64)  MUSL_TARGET="x86_64-unknown-linux-musl"  ;; \
      aarch64) MUSL_TARGET="aarch64-unknown-linux-musl" ;; \
      *) echo "Unsupported arch: $ARCH" >&2 && exit 1 ;; \
    esac && \
    rustup target add "$MUSL_TARGET" && \
    echo "$MUSL_TARGET" > /tmp/musl_target

COPY Cargo.toml Cargo.lock ./
COPY src ./src

# CARGO_TARGET_<UPPER>_RUSTFLAGS scopes +crt-static to the musl target only,
# leaving proc-macro crates (which compile for the gnu host) unaffected.
RUN MUSL_TARGET=$(cat /tmp/musl_target) && \
    UPPER=$(echo "$MUSL_TARGET" | tr 'a-z-' 'A-Z_') && \
    export "CARGO_TARGET_${UPPER}_RUSTFLAGS=-C target-feature=+crt-static" && \
    cargo build --release --target "$MUSL_TARGET" --bin control-plane-rust && \
    cp "target/$MUSL_TARGET/release/control-plane-rust" /usr/local/bin/control-plane-rust

# scratch: zero-dependency final image (~10 MB).
# The binary is fully statically linked so no libc is needed at runtime.
FROM scratch

COPY --from=builder /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/
COPY --from=builder /usr/local/bin/control-plane-rust /control-plane-rust

EXPOSE 8080
EXPOSE 8081

ENTRYPOINT ["/control-plane-rust"]
