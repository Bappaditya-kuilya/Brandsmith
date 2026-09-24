# Prompt pack

Files under `backend/src/main/resources/prompts/`. Frontmatter `version:` is stored on each stage run.

| File | Stage | Version |
|---|---|---|
| s0-intake.md | S0 intake + moderation | 1 |
| s1-interview.md | S1 adaptive interview | owned by interview agent |
| s2-positioning.md | S2 positioning agent (mandate via input `mandate`: native/contrarian/emotional) | 1 |
| s2-judge.md | S2 positioning judge (5 criteria + difference check input) | 1 |
| s3-personality.md | S3 traits + voice | 1 |
| s4-naming.md | S4 territories + names | 1 |
| s5-messages.md | S5 tagline, pitch, hierarchy | 1 |
| s6-visual.md | S6 visual fields only (never SVG) | 1 |
| s7-judge.md | S7 consistency judge | 1 |
| s7-revise.md | S7 auto-revise rewriter | 1 |
| s8-launch.md | S8 launch assets | 1 |
| anti-generic-critic.md | Anti-generic critic | 1 |
| anti-generic-rewrite.md | Anti-generic rewriter | 1 |
| eval-judge.md | Eval harness blind judge (distinctiveness/consistency/usefulness) | 1 |

Eval fixtures: `fixtures/ideas.json` (10 ideas), `fixtures/baseline-single-prompt.md` (single-shot baseline, version 1).

User content always in `<data>` blocks with an untrusted-data instruction. Bump `version` on every edit.
