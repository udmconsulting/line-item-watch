# syntax=docker/dockerfile:1.7
FROM eclipse-temurin:25.0.2_10-jdk-noble AS build

WORKDIR /workspace/backend
COPY backend/.mvn .mvn
COPY backend/mvnw backend/pom.xml ./
RUN ./mvnw --batch-mode --no-transfer-progress dependency:go-offline
COPY backend/src src
RUN ./mvnw --batch-mode --no-transfer-progress -DskipTests package \
    && cp target/line-item-watch-backend-*.jar /workspace/application.jar

FROM eclipse-temurin:25.0.2_10-jre-noble AS runtime

ARG SOURCE_REVISION=unknown
ARG BUILD_TIME=unknown
LABEL org.opencontainers.image.title="Line Item Watch" \
      org.opencontainers.image.description="Line Item Watch modular-monolith service and one-shot jobs" \
      org.opencontainers.image.source="https://github.com/udmconsulting/line-item-watch" \
      org.opencontainers.image.revision="${SOURCE_REVISION}" \
      org.opencontainers.image.created="${BUILD_TIME}"

RUN groupadd --system --gid 10001 app \
    && useradd --system --uid 10001 --gid app --home-dir /opt/app --shell /usr/sbin/nologin app

WORKDIR /opt/app
COPY --from=build --chown=app:app /workspace/application.jar application.jar
COPY --chown=app:app backend/container/entrypoint.sh entrypoint.sh
RUN chmod 0555 entrypoint.sh && chmod 0444 application.jar

USER 10001:10001
EXPOSE 8080
ENV PORT=8080 \
    APPLICATION_RUNTIME_ROLE=SERVICE

ENTRYPOINT ["/opt/app/entrypoint.sh"]
