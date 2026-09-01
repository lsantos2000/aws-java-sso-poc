FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src src
RUN mvn -B package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app

# curl is installed explicitly for the compose healthcheck rather than assumed to be present in
# the base image. docker-compose gates the frontend on "condition: service_healthy", so a check
# that can never pass would hang startup forever - worse than the 502s it exists to prevent.
RUN apt-get update \
    && apt-get install --no-install-recommends -y curl \
    && rm -rf /var/lib/apt/lists/*

# Wildcard rather than a pinned version: bumping the pom version must not break the image build.
COPY --from=build /workspace/target/*.jar app.jar

RUN useradd --system --uid 10001 --no-create-home spring && chown spring /app/app.jar
USER spring

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
