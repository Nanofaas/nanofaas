# Builds a static musl binary on the target platform, as runtimes/watchdog does.
FROM docker.io/library/rust:1.97-alpine AS build
RUN apk add --no-cache musl-dev ca-certificates
COPY sdks/rust /src/sdks/rust
COPY functions/rust/word-stats /src/functions/rust/word-stats
WORKDIR /src/functions/rust/word-stats
RUN RUST_TARGET="$(uname -m)-unknown-linux-musl" && \
    RUSTFLAGS='-C target-feature=+crt-static' cargo build --release --target "$RUST_TARGET" && \
    cp "target/${RUST_TARGET}/release/word-stats" /out

FROM scratch
# scratch has no CA bundle: HTTPS calls from the handler need one to verify certificates.
COPY --from=build /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/
COPY --from=build /out /app/word-stats
EXPOSE 8080
USER 65534:65534
ENTRYPOINT ["/app/word-stats"]
