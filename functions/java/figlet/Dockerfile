FROM gcr.io/distroless/java25-debian13:nonroot
WORKDIR /app
COPY build/libs/figlet.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
