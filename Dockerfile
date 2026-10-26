# Build: the Gradle wrapper downloads its own Gradle, so only a JDK is needed.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY chaos chaos
COPY server server
RUN ./gradlew --no-daemon -q :server:installDist

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/server/build/install/server .
EXPOSE 8080
ENTRYPOINT ["/app/bin/server"]
