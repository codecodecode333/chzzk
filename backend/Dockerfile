# syntax=docker/dockerfile:1
# Backend container image (ADR-0005): build the Spring Boot jar with JDK 21, run it on a JRE 21.
# From the repo root:  docker build -t chzzk-backend backend

FROM eclipse-temurin:21-jdk-noble AS build
WORKDIR /src
# Wrapper and build scripts first, so the Gradle distribution and dependencies stay cached until they change.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN --mount=type=cache,target=/root/.gradle chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null
COPY src src
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon bootJar \
    && find build/libs -name '*.jar' ! -name '*-plain.jar' -exec cp {} /src/app.jar \;

FROM eclipse-temurin:21-jre-noble
# Non-root; the VOD storage root is where the shared volume is mounted (owned by group 10001: use fsGroup 10001).
RUN groupadd --system --gid 10001 chzzk \
    && useradd --system --uid 10001 --gid chzzk --no-create-home chzzk \
    && mkdir -p /var/lib/chzzk/vod && chown -R chzzk:chzzk /var/lib/chzzk
WORKDIR /app
COPY --from=build /src/app.jar app.jar
COPY --chmod=755 docker-entrypoint.sh /app/docker-entrypoint.sh
USER 10001:10001
ENV VOD_STORAGE_ROOT=/var/lib/chzzk/vod \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
EXPOSE 8080
# A small shell runs the JVM so a graceful stop (SIGTERM) ends with exit 0, not 143 (see the script).
ENTRYPOINT ["/app/docker-entrypoint.sh"]
