# syntax=docker/dockerfile:1.7

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY backend/pom.xml backend/pom.xml
COPY backend/core/pom.xml backend/core/pom.xml
COPY backend/persistence/pom.xml backend/persistence/pom.xml
COPY backend/ratelimit/pom.xml backend/ratelimit/pom.xml
COPY backend/broker-redis/pom.xml backend/broker-redis/pom.xml
COPY backend/broker-postgres/pom.xml backend/broker-postgres/pom.xml
COPY backend/observability/pom.xml backend/observability/pom.xml
COPY backend/worker/pom.xml backend/worker/pom.xml
COPY backend/api/pom.xml backend/api/pom.xml
COPY backend/app/pom.xml backend/app/pom.xml
RUN --mount=type=cache,target=/root/.m2 \
    mvn -f backend/pom.xml -DskipTests dependency:go-offline -B
COPY backend backend
RUN --mount=type=cache,target=/root/.m2 \
    mvn -f backend/pom.xml -Dmaven.test.skip=true -Djacoco.skip=true -B clean package

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S taskq && adduser -S -G taskq taskq
WORKDIR /app
COPY --from=build /workspace/backend/app/target/reach-taskq.jar app.jar
USER taskq
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -Djava.security.egd=file:/dev/./urandom"
ENTRYPOINT ["java","-jar","/app/app.jar"]
