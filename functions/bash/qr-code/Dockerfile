FROM docker.io/library/rust:1.97-alpine AS watchdog-builder
RUN apk add --no-cache musl-dev
WORKDIR /build/watchdog
COPY runtimes/watchdog/Cargo.toml runtimes/watchdog/Cargo.lock* ./
COPY runtimes/watchdog/src ./src
RUN cargo build --release && cp target/release/nanofaas-watchdog /watchdog

FROM docker.io/library/debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends bash imagemagick jq qrencode && rm -rf /var/lib/apt/lists/*
COPY --from=watchdog-builder /watchdog /usr/local/bin/watchdog
COPY functions/bash/qr-code/handler.sh /app/handler.sh
RUN chmod +x /usr/local/bin/watchdog /app/handler.sh
ENV EXECUTION_MODE=STDIO WATCHDOG_CMD="/app/handler.sh" WARM=true WARM_PORT=8080 TIMEOUT_MS=30000
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/watchdog"]
