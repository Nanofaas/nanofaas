FROM golang:1.24-alpine AS build
WORKDIR /src
COPY function-sdk-go /src/function-sdk-go
COPY examples/go/roman-numeral /src/examples/go/roman-numeral
WORKDIR /src/examples/go/roman-numeral
RUN go build -o /out/roman-numeral .

FROM alpine:3.22
WORKDIR /app
COPY --from=build /out/roman-numeral /app/roman-numeral
EXPOSE 8080
ENTRYPOINT ["/app/roman-numeral"]
