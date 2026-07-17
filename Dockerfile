# syntax=docker/dockerfile:1.7@sha256:a57df69d0ea827fb7266491f2813635de6f17269be881f696fbfdf2d83dda33e

FROM node:24.18.0-alpine3.24@sha256:a0b9bf06e4e6193cf7a0f58816cc935ff8c2a908f81e6f1a95432d679c54fbfd AS deps

WORKDIR /app
ENV CI=true

COPY package.json pnpm-lock.yaml pnpm-workspace.yaml .npmrc ./
RUN --mount=type=cache,id=pnpm-store,target=/root/.local/share/pnpm/store \
	corepack enable && corepack pnpm install --frozen-lockfile

FROM deps AS build

ARG RUNWAY_BUILD_ID=development
ENV RUNWAY_BUILD_ID=${RUNWAY_BUILD_ID}
COPY . .
RUN corepack pnpm run build

FROM deps AS prod-deps

COPY . .
RUN --mount=type=cache,id=pnpm-store,target=/root/.local/share/pnpm/store \
	NPM_CONFIG_IGNORE_SCRIPTS=true \
	corepack pnpm --filter runway deploy --prod --no-optional --legacy /prod

FROM node:24.18.0-alpine3.24@sha256:a0b9bf06e4e6193cf7a0f58816cc935ff8c2a908f81e6f1a95432d679c54fbfd AS runtime

ARG RUNWAY_BUILD_ID=development

# The running app only needs Node. Removing the bundled package managers keeps
# their transitive dependency tree and unrelated advisories out of production.
RUN rm -rf /usr/local/lib/node_modules/npm /usr/local/lib/node_modules/corepack \
	&& rm -f /usr/local/bin/npm /usr/local/bin/npx /usr/local/bin/corepack /usr/local/bin/pnpm /usr/local/bin/pnpx

LABEL org.opencontainers.image.title="runway" \
	org.opencontainers.image.description="Self-hosted running plan and activity decision ledger" \
	org.opencontainers.image.source="https://github.com/deftmartian/runway" \
	org.opencontainers.image.licenses="AGPL-3.0-only" \
	org.opencontainers.image.revision="${RUNWAY_BUILD_ID}"

WORKDIR /app
ENV NODE_ENV=production \
	HOST=0.0.0.0 \
	PORT=4100 \
	BODY_SIZE_LIMIT=12M

COPY --from=build --chown=node:node /app/build ./build
COPY --from=prod-deps --chown=node:node /prod/node_modules ./node_modules
COPY --from=build --chown=node:node /app/package.json ./package.json
COPY --from=build --chown=node:node /app/drizzle ./drizzle
COPY --from=build --chown=node:node /app/scripts/run-migrations.mjs ./scripts/run-migrations.mjs

USER node
EXPOSE 4100

HEALTHCHECK --interval=15s --timeout=5s --start-period=10s --retries=4 \
	CMD node -e "fetch('http://127.0.0.1:'+(process.env.PORT||4100)+'/health/ready').then(r=>{if(!r.ok)process.exit(1)}).catch(()=>process.exit(1))"

CMD ["node", "build"]
