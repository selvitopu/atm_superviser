FROM maven:3.9.11-eclipse-temurin-25 AS build
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -pl supervisor -am package -DskipTests

FROM eclipse-temurin:25-jre
ENV TZ=UTC
RUN mkdir /data && chown 10001:10001 /data
COPY --from=build /src/supervisor/target/*-exec.jar /app/app.jar
USER 10001
WORKDIR /app
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
