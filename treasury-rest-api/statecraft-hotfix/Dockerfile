# syntax=docker/dockerfile:1
# StateCraft is pinned to the pre-release treasury image production-sha-a621f26
# (gitops#107). Its source commit no longer exists, so it can't be rebuilt.
# This re-packages that exact image with only the JRE replaced: OpenJDK
# 21.0.11 -> Adoptium 21.0.13.1+2, the Java4Shell (CVE-2026-114938) fix.
# Delete this directory and its workflow once StateCraft is unpinned.
FROM eclipse-temurin:21-jre AS patched-jre
ADD --checksum=sha256:18f44fab9065217f3ba61d0eef8a7b5f3c3713aa7293001b0d4ac10ede3850f0 \
    https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13.1%2B2/OpenJDK21U-jre_x64_linux_hotspot_21.0.13.1_2.tar.gz /tmp/jre.tar.gz
RUN mkdir /jre && tar -xzf /tmp/jre.tar.gz -C /jre --strip-components=1 \
    && /jre/bin/java -version 2>&1 | grep -q '21.0.13.1+2'

# production-sha-a621f26, pinned by digest.
FROM harbor.paradaux.io/paradaux-public/treasury-rest-api@sha256:5905b933ea8f6e83fc3a3562ed4f8226b7fd3152a0a67b0bc05fbb205627ef92
USER root
RUN rm -rf /opt/java/openjdk
COPY --from=patched-jre /jre /opt/java/openjdk
ENV JAVA_VERSION=jdk-21.0.13.1+2
USER appuser
