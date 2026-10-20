# Runtime-only image.
#
# In the single root Gradle build, treasury-rest-api can't build standalone, so the
# Spring Boot fat jar is produced by CI (or locally) via the root build:
#
#   ./gradlew :treasury-rest-api:bootJar
#   cp treasury-rest-api/build/libs/*.jar treasury-rest-api/app.jar
#   docker build treasury-rest-api
#
# This image just packages that prebuilt app.jar.
FROM eclipse-temurin:21-jre

# Security: run as non-root
RUN groupadd --system appgroup && useradd --system --gid appgroup appuser

WORKDIR /app

# The fat jar, staged next to this Dockerfile by the build before `docker build`.
COPY app.jar app.jar

RUN chown -R appuser:appgroup /app

USER appuser

# Default Spring profile – override at runtime via SPRING_PROFILES_ACTIVE
ENV SPRING_PROFILES_ACTIVE=prod
ENV JAVA_OPTS=""

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD curl -sf http://127.0.0.1:${MANAGEMENT_PORT:-8081}/actuator/health || exit 1

# exec so the JVM becomes PID 1 and receives SIGTERM directly — otherwise the
# orchestrator's SIGTERM goes to sh and graceful shutdown (drain in-flight
# transfers) can be skipped before the SIGKILL grace period (ADT entrypoint-not-pid1).
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
