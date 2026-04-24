FROM maven:3.8-openjdk-17 AS build
COPY . /app
WORKDIR /app
RUN mvn clean package -DskipTests

FROM eclipse-temurin:17-jre-jammy
COPY --from=build /app/target/motor-fiscal-1.0.9-FIX.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]