# syntax=docker/dockerfile:1

# Driftless modular monolith (app) — multi-stage: Maven reactor build -> slim, non-root JRE runtime.
# Build context is the repository root (see docker-compose.yml). Layers are ordered cache-friendly:
# the wrapper + every module pom are copied first and dependencies resolved into their own layer, so
# a source-only change never re-downloads the dependency graph.

# ---- build stage: resolve deps, then build only `app` and its reactor dependencies --------------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Maven wrapper + the reactor descriptors (parent + every module pom) first.
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

# Warm the dependency cache for the app reactor slice. The .m2 cache mount persists across builds.
RUN chmod +x mvnw
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -pl app -am dependency:go-offline -DskipTests

# Now the sources for the app reactor slice, then build the runnable boot jar (no tests in-image).
COPY common/src common/src
COPY ledger/src ledger/src
COPY idempotency/src idempotency/src
COPY auth/src auth/src
COPY rules/src rules/src
COPY tokens/src tokens/src
COPY recon/src recon/src
COPY observability/src observability/src
COPY app/src app/src

RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -pl app -am package -DskipTests \
    && cp app/target/app-*.jar /workspace/app.jar

# ---- runtime stage: slim JRE, non-root, boot jar only -------------------------------------------
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
RUN groupadd --system driftless && useradd --system --gid driftless --home-dir /app driftless
COPY --from=build /workspace/app.jar /app/app.jar
USER driftless
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
