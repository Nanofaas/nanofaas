# Dockerfile for the Figlet function.
#
# See the original implementation here: https://github.com/openfaas/store-functions/blob/master/figlet/Dockerfile

# Layer required to build the NanoFaaS watchdog.
#
# You can skip this step by using the prebuilt image available
# here: https://github.com/miciav/nanofaas/pkgs/container/nanofaas%2Fwatchdog
FROM docker.io/library/rust:1.97-alpine AS watchdog-builder
RUN apk add --no-cache musl-dev
WORKDIR /build/watchdog
COPY runtimes/watchdog/Cargo.toml runtimes/watchdog/Cargo.lock* ./
COPY runtimes/watchdog/src ./src
RUN cargo build --release && cp target/release/nanofaas-watchdog /watchdog

# Layer required to build the function.
FROM docker.io/library/alpine:3.19
RUN apk add --no-cache bash jq figlet
COPY --from=watchdog-builder /watchdog /usr/local/bin/watchdog
COPY functions/bash/figlet/handler.sh /app/handler.sh
RUN chmod +x /usr/local/bin/watchdog /app/handler.sh

# Since we are invoking a binary, we use stdio mode. However, we also need a
# custom wrapper because the input is provided as JSON rather than directly as
# the payload.
#
# Note that most of these env will be overwrited by NanoFaaS control-plane.
ENV EXECUTION_MODE=STDIO
ENV WATCHDOG_CMD="/app/handler.sh"
ENV WARM=true
ENV WARM_PORT=8080
ENV TIMEOUT_MS=5000

EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/watchdog"]
