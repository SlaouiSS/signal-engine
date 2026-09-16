# Signal Engine backend image (docs/03-technical-spec.md Section 4.10, 17.2).
#
# Target runtime is Java 25 LTS (D1); Java 21 LTS is the documented accepted
# fallback and is used here until the toolchain moves to 25. Keep this in step
# with backend/gradle/libs.versions.toml (`java`).

# --- build stage ---
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Warm the Gradle cache on dependency descriptors first.
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies --quiet || true

COPY src ./src
RUN ./gradlew --no-daemon clean bootJar -x test

# --- runtime stage ---
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

# curl is used only by the container health check in docker-compose.yml.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

RUN groupadd --system signalengine && useradd --system --gid signalengine signalengine
USER signalengine

COPY --from=build /workspace/build/libs/*.jar app.jar

EXPOSE 8080
ENV SERVER_ADDRESS=0.0.0.0
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
