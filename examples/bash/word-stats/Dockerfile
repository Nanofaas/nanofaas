# Build a self-contained image: watchdog + bash handler.
#
# Build from repo root:
#   docker build -f examples/bash/word-stats/Dockerfile -t nanofaas/bash-word-stats:dev .
#

FROM rust:1.83-alpine AS watchdog-builder
RUN apk add --no-cache musl-dev
WORKDIR /build/watchdog
COPY watchdog/Cargo.toml watchdog/Cargo.lock* ./
COPY watchdog/src ./src
RUN cargo build --release && cp target/release/nanofaas-watchdog /watchdog

FROM alpine:3.19
RUN apk add --no-cache bash jq coreutils
COPY --from=watchdog-builder /watchdog /usr/local/bin/watchdog
COPY examples/bash/word-stats/handler.sh /app/handler.sh
RUN chmod +x /usr/local/bin/watchdog /app/handler.sh

ENV EXECUTION_MODE=STDIO
ENV WATCHDOG_CMD="/app/handler.sh"
ENV WARM=true
ENV WARM_PORT=8080
ENV TIMEOUT_MS=30000

EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/watchdog"]

