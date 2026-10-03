FROM mcr.microsoft.com/playwright:v1.63.0-noble@sha256:eff16c30e6f3f4af0a03fa4b706120d5e9b0891c344a27d64559aff5900a4a27

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
