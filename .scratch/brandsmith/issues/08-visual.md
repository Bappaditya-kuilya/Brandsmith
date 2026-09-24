# 08: Visual Board

**What to build:** S6: model emits mood/hue/sat/shape language only; deterministic OKLCH 5-token palette with WCAG AA text pairs; typography from curated 24 pairs; logo from JSON shape grammar rendered to SVG backend-side; live brand board updates on token edit.

**Blocked by:** 07.

**Status:** ready-for-agent

- [ ] Palette meets WCAG AA on text/background pairs (unit tested)
- [ ] Model never emits raw SVG; backend renders from grammar
- [ ] Brand board live-updates when user changes a token
- [ ] Font ids restricted to allowed set
