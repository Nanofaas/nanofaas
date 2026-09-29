FROM docker.io/library/golang:1.24-alpine AS build
WORKDIR /src
COPY sdks/go /src/sdks/go
COPY functions/go/roman-numeral /src/functions/go/roman-numeral
WORKDIR /src/functions/go/roman-numeral
# A static, stripped binary: it runs on scratch and carries no symbol or debug tables.
RUN CGO_ENABLED=0 go build -trimpath -ldflags "-s -w" -o /out/roman-numeral .

FROM scratch
# scratch has no CA bundle: HTTPS calls, callbacks or the handler's own, need one.
COPY --from=build /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/
COPY --from=build /out/roman-numeral /app/roman-numeral
EXPOSE 8080
# scratch has no /etc/passwd, so the user is numeric (65534 is nobody).
USER 65534:65534
ENTRYPOINT ["/app/roman-numeral"]
