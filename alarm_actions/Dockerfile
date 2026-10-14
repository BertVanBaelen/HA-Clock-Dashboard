# Alarm Actions add-on image (same pattern as the TravelSteps add-on).
# Images are prebuilt by CI for amd64 and aarch64; the Supervisor never builds this file.
# BUILD_FROM is passed by Home Assistant tooling and ignored on purpose: the runtime is the
# official Node image, which also provides prebuilt better-sqlite3 binaries.
ARG NODE_VERSION=22

FROM node:${NODE_VERSION}-bookworm-slim AS build
# Toolchain only needed when no prebuilt native binary is available (better-sqlite3).
RUN apt-get update \
  && apt-get install -y --no-install-recommends python3 make g++ \
  && rm -rf /var/lib/apt/lists/*
WORKDIR /app

COPY package.json package-lock.json* ./
COPY shared/package.json shared/
COPY server/package.json server/
COPY web/package.json web/
RUN if [ -f package-lock.json ]; then npm ci --no-audit --no-fund; \
    else npm install --no-audit --no-fund; fi

COPY tsconfig.base.json ./
COPY shared shared
COPY server server
COPY web web
RUN npm run build && npm prune --omit=dev --no-audit --no-fund

FROM node:${NODE_VERSION}-bookworm-slim AS runtime
ENV NODE_ENV=production \
    DATA_DIR=/data
WORKDIR /app

COPY --from=build /app/package.json ./
COPY --from=build /app/node_modules ./node_modules
COPY --from=build /app/shared/package.json shared/
COPY --from=build /app/shared/dist shared/dist
COPY --from=build /app/server/package.json server/
COPY --from=build /app/server/dist server/dist
COPY --from=build /app/web/dist web/dist
COPY docker/alarm-actions-backup /usr/local/bin/alarm-actions-backup
RUN chmod +x /usr/local/bin/alarm-actions-backup

EXPOSE 8099
# TCP check only: every HTTP route requires an ingress identity and would answer 401.
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD ["node", "-e", "require('net').connect(8099,'127.0.0.1').on('connect',()=>process.exit(0)).on('error',()=>process.exit(1))"]
CMD ["node", "server/dist/index.js"]
