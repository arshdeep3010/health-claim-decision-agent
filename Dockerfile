# syntax=docker/dockerfile:1

# ---- Build stage: compile and package with Maven (JDK 21) ----
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build

# Build the application (tests run in CI, not during image build). A BuildKit
# cache mount for the Maven repo makes repeat builds fast without a fragile
# offline-resolve step.
COPY pom.xml .
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp clean package -DskipTests \
    && cp target/health-claim-decision-agent-*.jar app.jar

# ---- Runtime stage: lightweight JRE, non-root ----
FROM eclipse-temurin:21-jre AS runtime

# curl is used by the container HEALTHCHECK.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    # Non-root user; make /app (incl. dev document storage) writable by it.
    && groupadd --system app \
    && useradd --system --gid app --home /app app \
    && mkdir -p /app/data/storage \
    && chown -R app:app /app

WORKDIR /app
COPY --from=build --chown=app:app /build/app.jar /app/app.jar

USER app
EXPOSE 8080

# Container-friendly heap sizing; override/extend via JAVA_OPTS.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"

# Liveness probe (does not depend on external AI providers).
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -fsS http://localhost:8080/actuator/health/liveness || exit 1

# exec form via sh so JAVA_OPTS expands; exec keeps the JVM as PID 1 for signals.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
