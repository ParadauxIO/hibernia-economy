# Multi-stage Next.js production build using `output: 'standalone'`.
# Mirrors the layout treasury-rest-api uses (non-root user, healthcheck, minimal
# runtime). Run image only carries server.js + the deps it actually imports.

FROM node:26-alpine AS deps
WORKDIR /app
COPY package.json package-lock.json ./
COPY packages/design-system/package.json ./packages/design-system/package.json
RUN npm ci --ignore-scripts

FROM node:26-alpine AS builder
WORKDIR /app
COPY --from=deps /app/node_modules ./node_modules
COPY . .
ENV NEXT_TELEMETRY_DISABLED=1
RUN npm run build

FROM node:26-alpine AS runner
WORKDIR /app
ENV NODE_ENV=production
ENV NEXT_TELEMETRY_DISABLED=1
ENV PORT=3000
ENV HOSTNAME=0.0.0.0

RUN addgroup --system --gid 1001 nodejs \
 && adduser --system --uid 1001 nextjs

COPY --from=builder /app/public ./public
COPY --from=builder --chown=nextjs:nodejs /app/.next/standalone ./
COPY --from=builder --chown=nextjs:nodejs /app/.next/static ./.next/static
# Doc pages are statically rendered, but the dynamic /sitemap.xml enumerates
# them from docs/ at request time, so the markdown source ships too (tiny).
COPY --from=builder /app/docs ./docs

USER nextjs
EXPOSE 3000

HEALTHCHECK --interval=30s --timeout=3s --start-period=15s --retries=3 \
  CMD wget --quiet --spider http://127.0.0.1:3000/healthz || exit 1

CMD ["node", "server.js"]
