# 01: Skeleton

**What to build:** Runnable monorepo: Spring Boot 3 (Java 21) API with `/api/health`, React 18 + Vite + TS + Tailwind frontend calling it, Postgres via Docker Compose, GitHub Actions CI that builds both. Deployable hello world path.

**Blocked by:** None (can start immediately).

**Status:** ready-for-agent

- [ ] `GET /api/health` returns 200
- [ ] Frontend loads and shows health status from API
- [ ] `docker compose up` starts Postgres; app connects
- [ ] CI workflow builds backend + frontend
