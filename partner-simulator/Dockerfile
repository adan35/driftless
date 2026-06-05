# syntax=docker/dockerfile:1

# Driftless partner-simulator — its OWN image and container: the one genuinely separate service that
# gives the auth saga a real network boundary to fail against. Multi-stage: Maven build -> slim JRE.
# Build context is the repository root (see docker-compose.yml).

# ---- build stage: resolve deps, then build only the partner-simulator module --------------------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Maven wrapper + the reactor descriptors first (cache-friendly dependency layer).
COPY mvnw mvnw
COPY .mvn .mvn
COPY pom.xml pom.xml
COPY common/pom.xml common/pom.xml
COPY ledger/pom.xml ledger/pom.xml
COPY idempotency/pom.xml idempotency/pom.xml
COPY auth/pom.xml auth/pom.xml
COPY rules/pom.xml rules/pom.xml
COPY tokens/pom.xml tokens/pom.xml
COPY recon/pom.xml recon/pom.xml
COPY observability/pom.xml observability/pom.xml
COPY app/pom.xml app/pom.xml
COPY partner-simulator/pom.xml partner-simulator/pom.xml

RUN chmod +x mvnw
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -pl partner-simulator -am dependency:go-offline -DskipTests

# The partner-simulator is self-contained (depends only on the parent), so only its sources are needed.
COPY partner-simulator/src partner-simulator/src

RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -pl partner-simulator -am package -DskipTests \
    && cp partner-simulator/target/partner-simulator-*.jar /workspace/partner-simulator.jar

# ---- runtime stage: slim JRE, non-root, boot jar only -------------------------------------------
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
RUN groupadd --system driftless && useradd --system --gid driftless --home-dir /app driftless
COPY --from=build /workspace/partner-simulator.jar /app/partner-simulator.jar
USER driftless
EXPOSE 8081
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/partner-simulator.jar"]
