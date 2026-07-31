FROM node:22-alpine@sha256:16e22a550f3863206a3f701448c45f7912c6896a62de43add43bb9c86130c3e2 AS build

WORKDIR /app

COPY package.json package-lock.json ./
RUN npm config set fetch-retries 5 \
    && npm config set fetch-retry-mintimeout 20000 \
    && npm config set fetch-retry-maxtimeout 120000 \
    && npm ci --no-audit --prefer-offline

COPY . .

ARG VITE_CORTEX_API_BASE_URL=/api
ARG VITE_CORTEX_AUTH_MODE
ARG VITE_CORTEX_OFFLINE_GRANT_PUBLIC_KEY_SHA256
ARG VITE_CORTEX_MESSAGE_MAX_ATTACHMENT_BYTES=26214400
ARG VITE_MAP_PROVIDER=maplibre
ARG VITE_MAPLIBRE_STYLE_URL
ARG VITE_MAPTILER_API_KEY
ARG VITE_MAPBOX_ACCESS_TOKEN
ARG VITE_GEOCODER_URL
ENV VITE_CORTEX_API_BASE_URL=${VITE_CORTEX_API_BASE_URL}
ENV VITE_CORTEX_AUTH_MODE=${VITE_CORTEX_AUTH_MODE}
ENV VITE_CORTEX_OFFLINE_GRANT_PUBLIC_KEY_SHA256=${VITE_CORTEX_OFFLINE_GRANT_PUBLIC_KEY_SHA256}
ENV VITE_CORTEX_MESSAGE_MAX_ATTACHMENT_BYTES=${VITE_CORTEX_MESSAGE_MAX_ATTACHMENT_BYTES}
ENV VITE_MAP_PROVIDER=${VITE_MAP_PROVIDER}
ENV VITE_MAPLIBRE_STYLE_URL=${VITE_MAPLIBRE_STYLE_URL}
ENV VITE_MAPTILER_API_KEY=${VITE_MAPTILER_API_KEY}
ENV VITE_MAPBOX_ACCESS_TOKEN=${VITE_MAPBOX_ACCESS_TOKEN}
ENV VITE_GEOCODER_URL=${VITE_GEOCODER_URL}

RUN sh ./validate-docker-build-args.sh
RUN npm run build

FROM nginx:1.27-alpine@sha256:65645c7bb6a0661892a8b03b89d0743208a18dd2f3f17a54ef4b76fb8e2f2a10

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY security-headers.conf /etc/nginx/snippets/cortex-security-headers.conf
COPY --from=build /app/dist /usr/share/nginx/html

RUN mkdir -p \
      /var/cache/nginx/client_temp \
      /var/cache/nginx/proxy_temp \
      /var/cache/nginx/fastcgi_temp \
      /var/cache/nginx/uwsgi_temp \
      /var/cache/nginx/scgi_temp \
    && chown -R nginx:nginx /var/cache/nginx \
    && chown nginx:nginx /run

USER nginx

EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=5s --start-period=10s --retries=5 \
  CMD wget -q -O - http://127.0.0.1:8080/healthz | grep -q '^ok$' || exit 1

ENTRYPOINT ["nginx"]
CMD ["-g", "daemon off;"]
