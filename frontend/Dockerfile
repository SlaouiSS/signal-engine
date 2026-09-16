# Signal Engine frontend image (docs/03-technical-spec.md Section 4.10, 17.2).
#
# Phase 1 runs the Vite dev server for hot reload during local development
# (Section 17.3). The production choice — static assets served by the backend vs.
# an own container — is deferred (Section 24, T8) and not built here.
#
# Node 24 LTS (docs/03-technical-spec.md Section 4.17); keep in step with .nvmrc.

FROM node:26-slim
WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci --no-fund --no-audit

COPY . .

EXPOSE 5173
# Bind to all interfaces inside the container; the host publishes to 127.0.0.1 only.
CMD ["npm", "run", "dev", "--", "--host", "0.0.0.0"]
