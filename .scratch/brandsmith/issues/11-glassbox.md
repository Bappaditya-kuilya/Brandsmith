# 11: Glass Box + Lock/Stale

**What to build:** Glass Box drawer listing every stage run (input, output, score, model, latency, prompt version); stage rail status done/running/stale/locked; lock endpoint; edit-earlier marks downstream stale + Refresh downstream; regenerate with note; error toast retries stage not session.

**Blocked by:** 02 (usable as stages land; complete after 10).

**Status:** ready-for-agent

- [ ] Drawer shows stage runs from DB with model + latency
- [ ] Lock prevents regeneration of that stage
- [ ] Editing earlier stage marks later stages stale
- [ ] Refresh downstream re-runs only unlocked stale stages
- [ ] Failed stage retry resumes that stage
