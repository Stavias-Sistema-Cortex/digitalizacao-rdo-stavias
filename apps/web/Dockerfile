FROM node:22-alpine AS build

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
ENV VITE_CORTEX_API_BASE_URL=${VITE_CORTEX_API_BASE_URL}
ENV VITE_CORTEX_AUTH_MODE=${VITE_CORTEX_AUTH_MODE}
ENV VITE_CORTEX_OFFLINE_GRANT_PUBLIC_KEY_SHA256=${VITE_CORTEX_OFFLINE_GRANT_PUBLIC_KEY_SHA256}
ENV VITE_CORTEX_MESSAGE_MAX_ATTACHMENT_BYTES=${VITE_CORTEX_MESSAGE_MAX_ATTACHMENT_BYTES}

RUN test "${VITE_CORTEX_AUTH_MODE}" = "legacy" \
      || test "${VITE_CORTEX_AUTH_MODE}" = "postgresql"
RUN npm run build

FROM nginx:1.27-alpine

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY security-headers.conf /etc/nginx/snippets/cortex-security-headers.conf
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=5s --start-period=10s --retries=5 \
  CMD wget -q -O - http://127.0.0.1:8080/healthz | grep -q '^ok$' || exit 1
