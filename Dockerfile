# For free-tier container hosts. Not built during development, because Docker was unavailable (see README).
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/cinema-hall-booking.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
