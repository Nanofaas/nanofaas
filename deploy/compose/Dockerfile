FROM eclipse-temurin:21-jdk AS build

WORKDIR /workspace
COPY . .
RUN ./gradlew :control-plane:bootJar \
    -PcontrolPlaneModules=container-deployment-provider \
    --no-daemon

FROM eclipse-temurin:21-jre

WORKDIR /app
COPY --from=build /workspace/platform/control-plane/build/libs/app.jar /app/app.jar
EXPOSE 8080 8081

ENTRYPOINT ["java", \
    "-XX:+UseSerialGC", \
    "-XX:MaxRAMPercentage=70", \
    "-XX:TieredStopAtLevel=1", \
    "-Xss256k", \
    "-Dspring.main.banner-mode=off", \
    "-Dspring.jmx.enabled=false", \
    "-Dspring.devtools.restart.enabled=false", \
    "-Dmanagement.endpoints.enabled-by-default=false", \
    "-jar", "/app/app.jar"]
