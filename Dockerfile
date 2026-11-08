# One Dockerfile for all three modules; pick one with --build-arg MODULE=<module>.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src

# Resolve dependencies first so code changes don't re-download them.
COPY pom.xml .
COPY aggregator/pom.xml aggregator/
COPY mock-poll-provider/pom.xml mock-poll-provider/
COPY mock-push-provider/pom.xml mock-push-provider/
RUN mvn -B -q dependency:go-offline

COPY . .
ARG MODULE
RUN mvn -B -q -pl ${MODULE} package -DskipTests

FROM eclipse-temurin:21-jre
ARG MODULE
WORKDIR /app
COPY --from=build /src/${MODULE}/target/${MODULE}-1.0.0.jar app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
