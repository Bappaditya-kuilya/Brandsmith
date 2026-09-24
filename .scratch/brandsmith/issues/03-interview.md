# 03: Adaptive Interview

**What to build:** S1 adaptive interview: 8 brief fields with value/confidence/evidence; next question = max(weight × (1−confidence)); stop at confidence ≥ 0.75 or 6 questions; skip → low confidence + assumption flag; brief review screen with edit.

**Blocked by:** 02.

**Status:** ready-for-agent

- [ ] Vague idea → ≥4 questions; detailed idea → ≤2
- [ ] Confidence meter visible and rises with answers
- [ ] Skip marks field assumed, low confidence
- [ ] Brief review allows PATCH of fields
- [ ] Question asks about behavior/moment, not generic opinions (prompt-enforced)
