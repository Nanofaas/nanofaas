# JVM-only local development Dockerfile for lite SDK.
# Production native images are built via GraalVM native-image.
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY build/install/word-stats-lite/ /app/
EXPOSE 8080
ENTRYPOINT ["/app/bin/word-stats-lite"]
