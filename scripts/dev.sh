#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

if [ -f .env ]; then
  set -a
  # shellcheck disable=SC1091
  . ./.env
  set +a
fi

docker compose up -d

trap 'kill 0' EXIT

(cd backend && mvn spring-boot:run) &
(cd frontend && npm run dev) &

wait
