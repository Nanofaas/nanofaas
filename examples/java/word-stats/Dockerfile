FROM eclipse-temurin:21-jre
WORKDIR /app
COPY build/libs/word-stats.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
