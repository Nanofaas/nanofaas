FROM rust:1.86-bookworm AS builder

WORKDIR /app

COPY Cargo.toml Cargo.lock ./
COPY src ./src

RUN cargo build --release --bin control-plane-rust

FROM debian:bookworm-slim

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates \
    && rm -rf /var/lib/apt/lists/*

COPY --from=builder /app/target/release/control-plane-rust /usr/local/bin/control-plane-rust

EXPOSE 8080

ENTRYPOINT ["/usr/local/bin/control-plane-rust"]
