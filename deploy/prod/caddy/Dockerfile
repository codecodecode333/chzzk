# Production entry point (ADR-0006): Caddy with the SPA built in. Built by deploy/prod/compose.yaml, which passes the
# frontend directory as the named context `frontend`.

FROM node:24.22.3-bookworm-slim AS spa
WORKDIR /app
COPY --from=frontend package.json package-lock.json ./
RUN npm ci
# Only the sources the build reads (never a local node_modules or dist).
COPY --from=frontend index.html tsconfig.json tsconfig.app.json tsconfig.node.json vite.config.ts ./
COPY --from=frontend public public
COPY --from=frontend src src
RUN npm run build

FROM caddy:2.10.2-alpine
COPY Caddyfile /etc/caddy/Caddyfile
COPY --from=spa /app/dist /srv
