FROM docker.io/library/rust:1.97-alpine AS watchdog-builder
RUN apk add --no-cache musl-dev
WORKDIR /build/watchdog
COPY runtimes/watchdog/Cargo.toml runtimes/watchdog/Cargo.lock* ./
COPY runtimes/watchdog/src ./src
RUN cargo build --release && cp target/release/nanofaas-watchdog /watchdog
FROM docker.io/library/alpine:3.19
RUN apk add --no-cache bash jq
COPY --from=watchdog-builder /watchdog /usr/local/bin/watchdog
COPY functions/bash/handler-envelope/handler.sh /app/handler.sh
RUN chmod +x /usr/local/bin/watchdog /app/handler.sh
ENV EXECUTION_MODE=STDIO WATCHDOG_CMD="/app/handler.sh" WARM=true WARM_PORT=8080 TIMEOUT_MS=5000
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/watchdog"]
