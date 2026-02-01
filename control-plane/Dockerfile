FROM eclipse-temurin:21-jre
WORKDIR /app
COPY build/libs/control-plane-0.5.0.jar app.jar
EXPOSE 8080 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
