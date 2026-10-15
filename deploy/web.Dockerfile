# The web app built once and served by Caddy with the site settings. Build context: the repository root.
FROM node:22-alpine AS build
WORKDIR /src/apps/web
COPY apps/web/package.json apps/web/package-lock.json ./
# The prepare script installs a lint plugin; the build does not need it.
RUN npm ci --ignore-scripts
COPY apps/web ./
COPY contract /src/contract
RUN npx vite build

FROM caddy:2.10.2-alpine
COPY deploy/Caddyfile /etc/caddy/Caddyfile
COPY --from=build /src/apps/web/dist /srv/web
