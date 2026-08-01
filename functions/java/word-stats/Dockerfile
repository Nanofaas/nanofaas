# JVM-only local development Dockerfile. 
# Production native images are built via GitHub Actions using Cloud Native Buildpacks.
FROM gcr.io/distroless/java25-debian13:nonroot
WORKDIR /app
COPY build/libs/word-stats.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
