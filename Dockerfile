# syntax=docker/dockerfile:1.7
FROM eclipse-temurin:25.0.2_10-jdk-noble@sha256:b866059783e3dd2dd183937b713e77d0fd94d87ce7bdd4ed4a77d79e97a276cd AS build

WORKDIR /workspace/backend
COPY backend/.mvn .mvn
COPY backend/mvnw backend/pom.xml ./
COPY backend/src src
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw --batch-mode --no-transfer-progress -Pcontainer-image -DskipTests package \
    && cp target/line-item-watch-backend-*.jar /workspace/application.jar \
    && ! jar --list --file /workspace/application.jar \
        | grep -q '^BOOT-INF/classes/application-local.yml$'

FROM eclipse-temurin:25.0.2_10-jre-noble@sha256:a051234f864d7ab78bf0188c3c540ac06c711a3b566f00f246be37073cc99dce AS runtime

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        libssl3t64=3.0.13-0ubuntu3.16 \
        openssl=3.0.13-0ubuntu3.16 \
    && rm -rf /var/lib/apt/lists/*

ARG SOURCE_REVISION=unknown
ARG BUILD_TIME=unknown
LABEL org.opencontainers.image.title="Line Item Watch" \
      org.opencontainers.image.description="Line Item Watch modular-monolith service and one-shot jobs" \
      org.opencontainers.image.source="https://github.com/udmconsulting/line-item-watch" \
      org.opencontainers.image.revision="${SOURCE_REVISION}" \
      org.opencontainers.image.version="${SOURCE_REVISION}" \
      org.opencontainers.image.created="${BUILD_TIME}" \
      com.udmconsulting.line-item-watch.image-type="application" \
      com.udmconsulting.line-item-watch.runtime-roles="SERVICE,MIGRATE,OPERATOR"

RUN groupadd --system --gid 10001 app \
    && useradd --system --uid 10001 --gid app --home-dir /opt/app --shell /usr/sbin/nologin app

WORKDIR /opt/app
COPY --from=build --chown=app:app --chmod=0444 /workspace/application.jar application.jar
COPY --chown=app:app --chmod=0555 backend/container/entrypoint.sh entrypoint.sh

USER 10001:10001
EXPOSE 8080
ENV PORT=8080 \
    APPLICATION_RUNTIME_ROLE=SERVICE

ENTRYPOINT ["/opt/app/entrypoint.sh"]
