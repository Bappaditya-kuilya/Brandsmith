# Brandsmith

A founder has one sentence; generic AI returns one long, forgettable answer. Brandsmith interviews the founder, debates positioning, rewrites weak names until they score, and audits every asset against a locked Brand DNA.

## Architecture

```
+------------------+       HTTP + SSE (/api proxy)      +--------------------------------------+
| React 18 + Vite  | ---------------------------------> | Spring Boot 3 (Java 21) API :8080   |
| SPA :5173        |                                    |                                      |
|  stage screens   |                                    |  controllers                         |
|  Glass Box drawer|                                    |    -> rate limit + budget guard      |
+------------------+                                    |    -> stage services S0..S8          |
         |                                              |         |                            |
         |                                              |         +-> LlmClient --------------+--> LLM API (Groq / Gemini / Anthropic)
         |                                              |         +-> deterministic checks     |
         |                                              |         +-> embedding index (RAM)    |
         |                                              |    sessions / stage_runs / scores    |
         |                                              |         |                            |
         |                                              |         v                            |
         |                                              |  Postgres 16 (docker compose)        |
         |                                              +--------------------------------------+
         |
         +-- optional: OPENAI_API_KEY for corpus embeddings at build/query time
```

- Prompts live in `backend/src/main/resources/prompts/` (versioned files, not inline strings).
- Fixtures for evals: `fixtures/ideas.json`, baseline prompt `fixtures/baseline-single-prompt.md`.

## Stage status (S0–S8)

Status is what exists in this repo today, not the PRD target.

| Stage | Name | Backend API | UI |
|---|---|---|---|
| S0 | Intake + moderation | Done (`POST /api/sessions`) | Done (home form + examples) |
| S1 | Adaptive interview + brief | Done (`.../interview/answer`, `PATCH .../brief`) | Done (`/s/:id/interview`, `/s/:id/brief`) |
| S2 | Positioning battle | Done (`stages/position/run|select|regenerate`) | Done (`/s/:id/position`) |
| S3 | Personality + voice | Done (`stages/personality/run|regenerate`) | Done (`/s/:id/identity`) |
| S4 | Naming + anti-generic scores | Done (`stages/naming/run|select|regenerate`) | Done (`/s/:id/naming`) |
| S5 | Message hierarchy (tagline, pitch) | Done (`stages/messages/run|select|regenerate`) | Done — taglines + hierarchy panel on `/s/:id/naming` |
| S6 | Visual direction | Done (`stages/visual/run`, `PATCH .../visual/tokens`) | Done (`/s/:id/visual`) |
| S7 | Consistency audit + drift-check | Done (`POST .../audit`, `POST .../drift-check`) | Done (`/s/:id/audit`, `/s/:id/drift`) |
| S8 | Launch assets | Done (`stages/launch/run`) | Done (`/s/:id/launch`) |

Also present: `GET .../stage-runs` + stage lock (Glass Box data), `POST .../share`, `POST .../export`, `GET /api/share/{token}`, `GET /api/health`. Glass Box drawer component exists; brand kit screen at `/s/:id/kit`. Only the `/s/:id/*` catch-all is still a placeholder.

## Run locally

Prerequisites: Docker, Java 21, Maven, Node 22+.

```bash
# 1. Postgres (compose includes a `pg_isready` healthcheck; `docker compose ps` should show `healthy`)
docker compose up -d

# 2. Backend (port 8080)
export GROQ_API_KEY=gsk_...        # free tier, preferred — or GEMINI_API_KEY / ANTHROPIC_API_KEY
cd backend && mvn spring-boot:run

# 3. Frontend (port 5173, proxies /api to :8080)
cd frontend && npm ci && npm run dev
```

Or start compose + backend + frontend together:

```bash
./scripts/dev.sh
```

Tests: `cd backend && mvn test` · `cd frontend && npm run lint && npm run build`. CI runs both (`.github/workflows/ci.yml`).

## Environment variables

Read by `backend/src/main/resources/application.yml`:

| Variable | Required | Default | Purpose |
|---|---|---|---|
| `GROQ_API_KEY` | No (free keys primary) | empty | Groq free-tier LLM calls. First choice in `auto`. Get a key at console.groq.com. |
| `GEMINI_API_KEY` | No (free keys primary) | empty | Google Gemini free-tier LLM calls. Used when Groq key is unset. |
| `LLM_PROVIDER` | No | `auto` | `auto` \| `groq` \| `gemini` \| `anthropic` \| `none`. `auto` picks GROQ → GEMINI → ANTHROPIC → none. |
| `ANTHROPIC_API_KEY` | No | empty | Paid Anthropic fallback (used only if neither free key is set / provider is `anthropic`). |
| `DB_URL` | No | `jdbc:postgresql://localhost:5432/brandsmith` | Postgres JDBC URL |
| `DB_USER` / `DB_PASSWORD` | No | `brandsmith` / `brandsmith` | Match `docker-compose.yml` |
| `BUDGET_CAP_USD` | No | `0.40` | Per-session USD spend cap |
| `OPENAI_API_KEY` | Optional | empty | Corpus/query embeddings. Unset → deterministic hash embedder (no paid API). |

Others with defaults: `PORT` (8080), `CORS_ORIGINS` (`http://localhost:5173`), `COOKIE_SECURE` (false for local http; set `true` in production HTTPS), `SESSION_TTL_DAYS`, `RATE_LIMIT_CAPACITY`/`RATE_LIMIT_REFILL`, `LLM_MAIN_MODEL`/`LLM_SMALL_MODEL`, LLM timeout and per-MTok prices. Secrets belong in the environment only, never the repo.

Free-key defaults: Groq `qwen/qwen3.8-27b`; Gemini `gemini-3.6-flash` (thinking off for JSON stages). Budget prices for free providers default to 0.

## Disclosure

- **Ideas go to the LLM provider.** User text (intake idea, interview answers, pasted drift-check copy) is sent to the configured LLM provider — Groq, Gemini, or Anthropic (OpenAI embeddings only if `OPENAI_API_KEY` is set). Do not enter secrets or personal data. Same notice appears on the intake screen.
- **Anti-generic corpus honesty.** The overused-phrase corpus (`backend/src/main/resources/corpus/`) is generated once and curated by hand. It is a heuristic for embedding/lexicon scores, not ground truth. See `corpus/README.md`.
- **Eval judge bias.** LLM judges can favor their own style; the eval design calls for a different model or rubric than the generator. Treat scores as comparative, not absolute.

## Eval numbers

Run:

`./scripts/run-evals.sh` runs `EvalHarness` via the exec-maven-plugin in offline mode (`mvn -o`) and needs a reachable Postgres (`docker compose up -d`).

```bash
./scripts/run-evals.sh          # all 10 fixtures
./scripts/run-evals.sh 2        # first 2 (smoke)
```

Optional: set an LLM key (`GROQ_API_KEY` / `GEMINI_API_KEY` / `ANTHROPIC_API_KEY`) so kits and the judge use the live LLM; without it the harness falls back to offline templates + a deterministic lexicon/structure judge (table still prints). Maven always runs with `-o` (offline, local repo only); network calls happen only if an LLM key is set.

Results land in `eval_run`. Fixtures: `fixtures/ideas.json`; baseline prompt: `fixtures/baseline-single-prompt.md`.

Offline smoke run (2026-09-24, no `ANTHROPIC_API_KEY` — heuristic judge, template baseline, offline pipeline; comparative only, not LLM-judged):

```markdown
| fixture | variant | distinctiveness | consistency | usefulness |
|---|---|---:|---:|---:|
| fx-01-student-tool | baseline | 24 | 100 | 58 |
| fx-01-student-tool | brandsmith | 68 | 100 | 88 |
| fx-02-creator-newsletter | baseline | 24 | 100 | 58 |
| fx-02-creator-newsletter | brandsmith | 64 | 100 | 88 |

**Averages** · baseline 24/100/58 · brandsmith 66/100/88 · Δ distinctiveness +42
```
