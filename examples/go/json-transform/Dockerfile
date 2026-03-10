FROM golang:1.24-alpine AS build
WORKDIR /src
COPY function-sdk-go /src/function-sdk-go
COPY examples/go/json-transform /src/examples/go/json-transform
WORKDIR /src/examples/go/json-transform
RUN go build -o /out/json-transform .

FROM alpine:3.22
WORKDIR /app
COPY --from=build /out/json-transform /app/json-transform
EXPOSE 8080
ENTRYPOINT ["/app/json-transform"]
