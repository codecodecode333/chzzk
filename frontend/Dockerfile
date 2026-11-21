# Cluster-mode frontend (ADR-0005): build the SPA, serve it from nginx over HTTPS on 8443.
#   docker build -t chzzk-frontend frontend
#   docker run -p 8443:8443 -e BACKEND_UPSTREAM=host.docker.internal:8080 chzzk-frontend

FROM node:24.22.3-bookworm-slim AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:1.30.7-alpine3.24
RUN apk add --no-cache openssl

# BACKEND_UPSTREAM / MEDIAMTX_UPSTREAM: host:port of the backend Service and of MediaMTX's WebRTC signaling (HTTP) port.
# CLIENT_MAX_BODY_SIZE: >= the backend's vod.upload.max-file-size (1GB) plus multipart overhead.
# NGINX_ENTRYPOINT_LOCAL_RESOLVERS: the image's entrypoint puts /etc/resolv.conf's nameservers into
# NGINX_LOCAL_RESOLVERS for the `resolver` line; NGINX_ENVSUBST_FILTER limits templating to these variables.
ENV BACKEND_UPSTREAM=backend:8080 \
    MEDIAMTX_UPSTREAM=mediamtx:8889 \
    CLIENT_MAX_BODY_SIZE=1100m \
    NGINX_ENTRYPOINT_LOCAL_RESOLVERS=1 \
    NGINX_ENVSUBST_FILTER="^(BACKEND_UPSTREAM|MEDIAMTX_UPSTREAM|CLIENT_MAX_BODY_SIZE|NGINX_LOCAL_RESOLVERS)$"

COPY nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY --chmod=755 nginx/05-self-signed-cert.sh /docker-entrypoint.d/05-self-signed-cert.sh
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 8443
