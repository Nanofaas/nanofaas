FROM docker.io/library/golang:1.24-alpine AS build
WORKDIR /src
COPY sdks/go /src/sdks/go
COPY functions/go/qr-code /src/functions/go/qr-code
WORKDIR /src/functions/go/qr-code
# A static, stripped binary: it runs on scratch and carries no symbol or debug tables.
RUN CGO_ENABLED=0 go build -trimpath -ldflags "-s -w" -o /out/qr-code .

FROM scratch
# scratch has no CA bundle: HTTPS calls, callbacks or the handler's own, need one.
COPY --from=build /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/
COPY --from=build /out/qr-code /app/qr-code
EXPOSE 8080
# scratch has no /etc/passwd, so the user is numeric (65534 is nobody).
USER 65534:65534
ENTRYPOINT ["/app/qr-code"]
