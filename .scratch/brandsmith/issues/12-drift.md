# 12: Drift Check

**What to build:** Paste text + asset type (post/email/landing) → same 5-dimension scoring as S7 → pass/fail per dimension, flagged phrases, on-brand rewrite.

**Blocked by:** 09.

**Status:** ready-for-agent

- [ ] API `POST /api/sessions/{id}/drift-check` scores pasted copy
- [ ] UI returns per-dimension pass/fail + flagged phrases + rewrite
- [ ] Reuses Consistency Guardian scoring, not a parallel implementation
