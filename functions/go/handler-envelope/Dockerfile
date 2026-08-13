FROM docker.io/library/golang:1.24-alpine AS build
WORKDIR /src
COPY sdks/go /src/sdks/go
COPY functions/go/handler-envelope /src/functions/go/handler-envelope
WORKDIR /src/functions/go/handler-envelope
RUN go build -o /out/handler-envelope .
FROM docker.io/library/alpine:3.22
WORKDIR /app
COPY --from=build /out/handler-envelope /app/handler-envelope
EXPOSE 8080
ENTRYPOINT ["/app/handler-envelope"]
