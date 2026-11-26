# Build the jar first: mvn -pl api package
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/card-txn-api-0.1.0.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
