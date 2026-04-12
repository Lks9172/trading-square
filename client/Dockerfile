FROM node:22-slim AS builder

WORKDIR /app

COPY package.json package-lock.json ./
RUN npm install --force && npm install lightningcss-linux-x64-gnu @tailwindcss/oxide-linux-x64-gnu

COPY tsconfig.json next.config.ts postcss.config.mjs eslint.config.mjs ./
COPY src ./src
COPY public ./public

ARG NEXT_PUBLIC_API_URL=http://localhost:5846
ENV NEXT_PUBLIC_API_URL=${NEXT_PUBLIC_API_URL}

RUN npm run build

FROM node:22-slim AS runner

WORKDIR /app

COPY --from=builder /app/.next/standalone ./
COPY --from=builder /app/.next/static ./.next/static
COPY --from=builder /app/public ./public

EXPOSE 5847

ENV PORT=5847
ENV HOSTNAME=0.0.0.0

CMD ["node", "server.js"]
