# Static glibc avoids allocator contention observed under concurrent QR workloads.
FROM docker.io/library/rust:1.97-slim-bookworm AS build
COPY sdks/rust /src/sdks/rust
COPY functions/rust/qr-code /src/functions/rust/qr-code
WORKDIR /src/functions/rust/qr-code
RUN RUST_TARGET="$(uname -m)-unknown-linux-gnu" && \
    RUSTFLAGS='-C target-feature=+crt-static' cargo build --locked --release --target "$RUST_TARGET" && \
    cp "target/${RUST_TARGET}/release/qr-code" /out && \
    readelf -d /out > /dynamic.txt && ! grep -q NEEDED /dynamic.txt

FROM scratch
# scratch has no CA bundle: HTTPS calls from the handler need one to verify certificates.
COPY --from=build /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/
COPY --from=build /out /app/qr-code
EXPOSE 8080
USER 65534:65534
ENTRYPOINT ["/app/qr-code"]
