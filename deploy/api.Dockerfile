# The API as one jar on a JRE, run as an unprivileged user. Build context: services/api.
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN ./gradlew --no-daemon -q dependencies > /dev/null
COPY src src
RUN ./gradlew --no-daemon -q bootJar -x test && cp build/libs/*-SNAPSHOT.jar /app.jar

FROM eclipse-temurin:21-jre-alpine
# A fixed uid: secret files on the host are owned by it, and KeyFile accepts only files the API user owns.
RUN addgroup -S -g 10001 ups && adduser -S -D -H -u 10001 -G ups ups && mkdir -p /data/photos && chown ups:ups /data/photos
COPY --from=build /app.jar /app/api.jar
USER ups
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/api.jar"]
