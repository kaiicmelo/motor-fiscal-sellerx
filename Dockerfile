FROM maven:3.8.5-openjdk-17 AS build
COPY . /app
WORKDIR /app
RUN mvn clean package -DskipTests
FROM openjdk:17.0.1-jdk-slim
COPY --from=build /app/target/motor-fiscal-1.0.5-FINAL.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
# Quebra Cache: 2026-04-24T17:36:59.462Z-p5p7p4