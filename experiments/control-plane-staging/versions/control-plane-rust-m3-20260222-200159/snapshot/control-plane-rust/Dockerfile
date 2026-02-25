FROM rust:1.86-bookworm AS builder

WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates \
    && rm -rf /var/lib/apt/lists/*

COPY Cargo.toml Cargo.lock ./
COPY src ./src

RUN cargo build --release --bin control-plane-rust

# distroless/cc contains only libc + libgcc (no shell, no package manager).
# Multi-arch: works on both linux/amd64 and linux/arm64 without target downloads.
# ~20MB final image vs ~80MB debian:bookworm-slim.
FROM gcr.io/distroless/cc-debian12

COPY --from=builder /app/target/release/control-plane-rust /control-plane-rust

EXPOSE 8080
EXPOSE 8081

ENTRYPOINT ["/control-plane-rust"]
