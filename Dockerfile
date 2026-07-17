# syntax=docker/dockerfile:1.7@sha256:a57df69d0ea827fb7266491f2813635de6f17269be881f696fbfdf2d83dda33e

FROM node:26.5.0-alpine3.24@sha256:e88a35be04478413b7c71c455cd9865de9b9360e1f43456be5951032d7ac1a66 AS deps

WORKDIR /app
ENV CI=true

COPY package.json pnpm-lock.yaml pnpm-workspace.yaml .npmrc ./
RUN --mount=type=cache,id=pnpm-store,target=/root/.local/share/pnpm/store \
	corepack enable && corepack pnpm install --frozen-lockfile

FROM deps AS migrate

COPY . .

FROM deps AS build

ARG RUNWAY_BUILD_ID
ENV RUNWAY_BUILD_ID=${RUNWAY_BUILD_ID}
COPY . .
RUN corepack pnpm run build

FROM node:26.5.0-alpine3.24@sha256:e88a35be04478413b7c71c455cd9865de9b9360e1f43456be5951032d7ac1a66 AS prod-deps

WORKDIR /app
ENV CI=true

COPY package.json pnpm-lock.yaml pnpm-workspace.yaml .npmrc ./
RUN --mount=type=cache,id=pnpm-prod-store,target=/root/.local/share/pnpm/store \
	corepack enable && corepack pnpm install --frozen-lockfile --prod --ignore-scripts

FROM node:26.5.0-alpine3.24@sha256:e88a35be04478413b7c71c455cd9865de9b9360e1f43456be5951032d7ac1a66 AS runtime

WORKDIR /app
ENV NODE_ENV=production \
	HOST=0.0.0.0 \
	PORT=4100 \
	BODY_SIZE_LIMIT=12M

COPY --from=build --chown=node:node /app/build ./build
COPY --from=prod-deps --chown=node:node /app/node_modules ./node_modules
COPY --from=build --chown=node:node /app/package.json ./package.json

USER node
EXPOSE 4100

HEALTHCHECK --interval=15s --timeout=5s --start-period=10s --retries=4 \
	CMD node -e "fetch('http://127.0.0.1:'+(process.env.PORT||4100)+'/health/ready').then(r=>{if(!r.ok)process.exit(1)}).catch(()=>process.exit(1))"

CMD ["node", "build"]
