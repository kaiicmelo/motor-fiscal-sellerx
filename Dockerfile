FROM maven:3.8.5-openjdk-17-slim AS build
COPY . /app
WORKDIR /app
RUN mvn clean package -DskipTests
FROM openjdk:17-jdk-slim
COPY --from=build /app/target/motor-fiscal-1.0.6-FINAL.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]