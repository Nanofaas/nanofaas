FROM docker.io/library/golang:1.24-alpine AS build
WORKDIR /src
COPY sdks/go /src/sdks/go
COPY functions/go/roman-numeral /src/functions/go/roman-numeral
WORKDIR /src/functions/go/roman-numeral
RUN go build -o /out/roman-numeral .

FROM docker.io/library/alpine:3.22
WORKDIR /app
COPY --from=build /out/roman-numeral /app/roman-numeral
EXPOSE 8080
USER nobody
ENTRYPOINT ["/app/roman-numeral"]
