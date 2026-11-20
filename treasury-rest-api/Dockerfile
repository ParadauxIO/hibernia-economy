# syntax=docker/dockerfile:1
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
# Java4Shell (CVE-2026-114938): eclipse-temurin:25-jre still ships the affected 25.0.5+8.
# Swap in Adoptium's patched 25.0.5.1+2 JRE (checksum-pinned, amd64 — CI only
# builds linux/amd64) until the official image catches up, then drop this stage.
FROM eclipse-temurin:25-jre AS patched-jre
ADD --checksum=sha256:2edef9e4f81ec5ee426d023336d4cfbd7b4644e99884e7a73a6a867b70b17acf \
    https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.5.1%2B2/OpenJDK25U-jre_x64_linux_hotspot_25.0.5.1_2.tar.gz /tmp/jre.tar.gz
RUN mkdir /jre && tar -xzf /tmp/jre.tar.gz -C /jre --strip-components=1 \
    && /jre/bin/java -version 2>&1 | grep -q '25.0.5.1+2'

FROM eclipse-temurin:25-jre

# Patched JRE from the stage above (Java4Shell).
RUN rm -rf /opt/java/openjdk
COPY --from=patched-jre /jre /opt/java/openjdk

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
