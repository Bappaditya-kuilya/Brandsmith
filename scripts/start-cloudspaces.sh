#!/usr/bin/env bash
# Start everything so Cloudspaces port 5173 serves the app + /api.
set -euo pipefail
cd "$(dirname "$0")/.."

if [ -f .env ]; then
  set -a
  # shellcheck disable=SC1091
  . ./.env
  set +a
fi

export PATH="${HOME}/.local/bin:${HOME}/.tools/apache-maven-3.9.11/bin:${PATH}"

# Public origin Cloudspaces proxies (Vite is same-origin via /api, but Spring still checks Origin).
export CORS_ORIGINS="${CORS_ORIGINS:-http://localhost:5173,http://127.0.0.1:5173,http://localhost:4173,http://127.0.0.1:4173,https://*.litng.ai,https://*.cloudspaces.litng.ai,https://*.vercel.app}"
# HTTPS deploy: cookies must be Secure
if [ "${SECURE_COOKIES:-}" = "1" ]; then
  export COOKIE_SECURE=true
fi

if command -v docker >/dev/null 2>&1; then
  docker compose up -d || true
fi

# Wait for Postgres if present (skip silently if not)
for _ in $(seq 1 30); do
  if (echo > /dev/tcp/127.0.0.1/5432) >/dev/null 2>&1; then
    break
  fi
  sleep 1
done

trap 'kill 0' EXIT INT TERM

(cd backend && mvn -q spring-boot:run) &
(cd frontend && npm ci --silent && npm run dev -- --host 0.0.0.0 --port 5173) &

wait
