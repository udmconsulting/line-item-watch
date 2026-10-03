FROM mcr.microsoft.com/playwright:v1.63.0-noble@sha256:eff16c30e6f3f4af0a03fa4b706120d5e9b0891c344a27d64559aff5900a4a27

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        libssl3t64=3.0.13-0ubuntu3.16 \
        openssl=3.0.13-0ubuntu3.16 \
    && rm -rf /var/lib/apt/lists/*

ARG SOURCE_REVISION=unknown
ARG BUILD_TIME=unknown
LABEL org.opencontainers.image.title="Line Item Watch UI Assurance" \
      org.opencontainers.image.description="Read-only Playwright synthetic runner for Line Item Watch" \
      org.opencontainers.image.source="https://github.com/udmconsulting/line-item-watch" \
      org.opencontainers.image.revision="${SOURCE_REVISION}" \
      org.opencontainers.image.version="${SOURCE_REVISION}" \
      org.opencontainers.image.created="${BUILD_TIME}" \
      com.udmconsulting.line-item-watch.image-type="assurance"

WORKDIR /work

COPY assurance/package.json assurance/package-lock.json ./
RUN npm ci --omit=dev && npm cache clean --force

COPY assurance/playwright.config.ts ./playwright.config.ts
COPY assurance/lib ./lib
COPY assurance/reporters ./reporters
COPY assurance/scripts ./scripts
COPY assurance/tests/live.spec.ts ./tests/live.spec.ts
RUN mkdir -p /work/test-results && chown -R pwuser:pwuser /work/test-results

USER pwuser
CMD ["node", "scripts/run-synthetic.mjs"]
