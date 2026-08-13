FROM docker.io/library/golang:1.24-alpine AS build
WORKDIR /src
COPY sdks/go /src/sdks/go
COPY functions/go/qr-code /src/functions/go/qr-code
WORKDIR /src/functions/go/qr-code
RUN go build -o /out/qr-code .

FROM docker.io/library/alpine:3.22
WORKDIR /app
COPY --from=build /out/qr-code /app/qr-code
EXPOSE 8080
ENTRYPOINT ["/app/qr-code"]
