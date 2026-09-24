# 02: LLM layer + Intake

**What to build:** User pastes one idea (10–1000 chars) or picks an example chip → moderated create session → lands on interview route. Backend: `LlmClient` interface + Anthropic impl, per-stage JSON schemas, retry-once-then-degrade, token budget cap, prompt loader from `resources/prompts/`, S0 intake/moderation stage, `POST /api/sessions` + owner cookie + session persistence.

**Blocked by:** 01.

**Status:** ready-for-agent

- [ ] Idea 10–1000 chars accepted; out-of-range rejected with message
- [ ] Harmful/illegal idea refused with plain message (S0)
- [ ] Three example ideas load in one click
- [ ] Session created, id + HttpOnly owner cookie, row in Postgres
- [ ] Budget guard tracks tokens; over-cap fails closed with message
- [ ] Schema-invalid LLM output retried once, then degraded flag (never raw crash to UI)
