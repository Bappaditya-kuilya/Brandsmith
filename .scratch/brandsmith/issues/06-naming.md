# 06: Naming + Anti-Generic

**What to build:** S4: 3 territories × 3 names; each name has rationale, length, pronounceability (local heuristic), anti-generic score; anti-generic loop (lexicon + embedding + critic) up to 3 rounds with visible before/after delta on shortlist.

**Blocked by:** 05.

**Status:** ready-for-agent

- [ ] 9 names across 3 territories with scores
- [ ] Pronounceability is deterministic (no LLM)
- [ ] Anti-generic score = 0.30 lexicon + 0.30 embedding + 0.40 critic
- [ ] Loop stops at ≥70, 3 rounds, or <3 pt improvement; shows delta
- [ ] Embedding index loads precomputed corpus in memory (build script generates corpus vectors)
