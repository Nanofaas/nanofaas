FROM golang:1.24-alpine AS build
WORKDIR /src
COPY function-sdk-go /src/function-sdk-go
COPY examples/go/word-stats /src/examples/go/word-stats
WORKDIR /src/examples/go/word-stats
RUN go build -o /out/word-stats .

FROM alpine:3.22
WORKDIR /app
COPY --from=build /out/word-stats /app/word-stats
EXPOSE 8080
ENTRYPOINT ["/app/word-stats"]
