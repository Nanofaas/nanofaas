FROM --platform=linux/amd64 rust:1.86-bookworm AS builder

WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends musl-tools \
    && rm -rf /var/lib/apt/lists/* \
    && rustup target add x86_64-unknown-linux-musl

COPY Cargo.toml Cargo.lock ./
COPY src ./src

# Target-scoped flag: applies only to the musl binary, not to proc-macro crates
# (proc-macros compile for the host x86_64-unknown-linux-gnu and are unaffected)
ENV CARGO_TARGET_X86_64_UNKNOWN_LINUX_MUSL_RUSTFLAGS="-C target-feature=+crt-static"
RUN cargo build --release --target x86_64-unknown-linux-musl --bin control-plane-rust

FROM scratch

COPY --from=builder /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/
COPY --from=builder /app/target/x86_64-unknown-linux-musl/release/control-plane-rust /control-plane-rust

EXPOSE 8080
EXPOSE 8081

ENTRYPOINT ["/control-plane-rust"]
