FROM maven:3.8.5-openjdk-17 AS build
COPY . /app
WORKDIR /app
RUN mvn clean package -DskipTests
FROM amazoncorretto:17-al2-jdk
COPY --from=build /app/target/motor-fiscal-1.0.8-FIX.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]