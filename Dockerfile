# ── Stage 1: Build ──
FROM node:22-alpine AS builder

RUN corepack enable && corepack prepare pnpm@latest --activate

WORKDIR /app

# Copy package manifests first for better layer caching
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
COPY packages/proxy/package.json packages/proxy/
COPY packages/web/package.json packages/web/
COPY packages/shared/package.json packages/shared/ 2>/dev/null || true

RUN pnpm install --frozen-lockfile

# Copy source
COPY . .

# Build both packages
RUN pnpm build

# ── Stage 2: Production ──
FROM node:22-alpine

RUN corepack enable && corepack prepare pnpm@latest --activate

WORKDIR /app

# Copy package manifests
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
COPY packages/proxy/package.json packages/proxy/
COPY packages/web/package.json packages/web/
COPY packages/shared/package.json packages/shared/ 2>/dev/null || true

# Install production deps only
RUN pnpm install --frozen-lockfile --prod

# Copy build artifacts
COPY --from=builder /app/packages/proxy/dist packages/proxy/dist
COPY --from=builder /app/packages/web/dist packages/web/dist

# The proxy serves the web UI from the web/dist folder
ENV NODE_ENV=production
ENV PORTA_PORT=3170
ENV PORTA_HOST=0.0.0.0

EXPOSE 3170

# Health check
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s \
  CMD wget --no-verbose --tries=1 --spider http://localhost:3170/api/health || exit 1

CMD ["node", "packages/proxy/dist/index.js"]
