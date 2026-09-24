import assert from 'node:assert/strict'

// Mirrors Position.tsx guards against backend payloads that omit optional fields.
function summarize(judge: unknown) {
  const j = judge as { scores?: unknown[]; differenceCheck?: { ok: boolean; note?: string } } | null | undefined
  const scores = Array.isArray(j?.scores) ? j.scores : []
  const diff = j?.differenceCheck
  const diffFailed = Boolean(diff && !diff.ok)
  return { scoreCount: scores.length, diffFailed, note: diff?.note }
}

const backendShape = {
  scores: [
    { mandate: 'native', audienceFit: 4, distinctiveness: 3, credibility: 4, memorability: 3, feasibility: 4, explanation: 'ok' },
  ],
  // no differenceCheck, no index on scores — actual JudgeResult JSON
}
const s = summarize(backendShape)
assert.equal(s.scoreCount, 1)
assert.equal(s.diffFailed, false)
assert.equal(s.note, undefined)

assert.equal(summarize(null).diffFailed, false)
assert.equal(summarize(undefined).diffFailed, false)
assert.equal(summarize({}).diffFailed, false)
assert.equal(summarize({ scores: null }).diffFailed, false)

const failed = summarize({ scores: [], differenceCheck: { ok: false, note: 'shared frame' } })
assert.equal(failed.diffFailed, true)
assert.equal(failed.note, 'shared frame')

console.log('position.judge check: ok')
