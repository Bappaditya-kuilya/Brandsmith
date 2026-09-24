import { deriveRailStatuses, latestRuns } from './stages.ts'
import type { StageRun as SR } from '../api/types'

function run(partial: Partial<SR> & { stage: string; status: string }): SR {
  return {
    id: partial.id ?? `${partial.stage}-1`,
    stage: partial.stage,
    model: partial.model ?? null,
    latencyMs: partial.latencyMs ?? null,
    promptVersion: partial.promptVersion ?? null,
    status: partial.status,
    tokensIn: partial.tokensIn ?? null,
    tokensOut: partial.tokensOut ?? null,
    stale: partial.stale ?? false,
    locked: partial.locked ?? false,
    score: partial.score ?? null,
  }
}

function eq(label: string, actual: unknown, expected: unknown) {
  const a = JSON.stringify(actual)
  const e = JSON.stringify(expected)
  if (a !== e) throw new Error(`${label}: got ${a}, want ${e}`)
}

// empty → all idle
eq('empty', deriveRailStatuses([]), {
  S0: 'idle', S1: 'idle', S2: 'idle', S3: 'idle', S4: 'idle',
  S5: 'idle', S6: 'idle', S7: 'idle', S8: 'idle', kit: 'idle', drift: 'idle',
})

// ok → done; degraded → done; error → idle; running → running
eq('ok→done', deriveRailStatuses([run({ stage: 'S2', status: 'ok' })]).S2, 'done')
eq('degraded→done', deriveRailStatuses([run({ stage: 'S3', status: 'degraded' })]).S3, 'done')
eq('error→idle', deriveRailStatuses([run({ stage: 'S4', status: 'error' })]).S4, 'idle')
eq('running', deriveRailStatuses([run({ stage: 'S5', status: 'running' })]).S5, 'running')

// stale / locked / locked+stale
eq('stale', deriveRailStatuses([run({ stage: 'S2', status: 'ok', stale: true })]).S2, 'stale')
eq('locked', deriveRailStatuses([run({ stage: 'S2', status: 'ok', locked: true })]).S2, 'locked')
eq(
  'locked beats stale',
  deriveRailStatuses([run({ stage: 'S2', status: 'ok', stale: true, locked: true })]).S2,
  'locked',
)

// page merge: done w/o run; idle never clobbers; running beats stale; done loses to stale
eq('page done w/o run', deriveRailStatuses([], { S1: 'done' }).S1, 'done')
eq(
  'page idle does not clobber',
  deriveRailStatuses([run({ stage: 'S2', status: 'ok', stale: true })], { S2: 'idle' }).S2,
  'stale',
)
eq(
  'page running beats stale',
  deriveRailStatuses([run({ stage: 'S3', status: 'ok', stale: true })], { S3: 'running' }).S3,
  'running',
)
eq(
  'page done loses to stale',
  deriveRailStatuses([run({ stage: 'S4', status: 'ok', stale: true })], { S4: 'done' }).S4,
  'stale',
)

// multi-version: latest (last) wins
eq(
  'latest wins',
  deriveRailStatuses([
    run({ id: 'a', stage: 'S6', status: 'ok', stale: true }),
    run({ id: 'b', stage: 'S6', status: 'ok' }),
  ]).S6,
  'done',
)
eq('latestRuns includes kit', latestRuns([run({ stage: 'S0', status: 'ok' }), run({ stage: 'kit', status: 'ok' })]).size, 2)
eq('latestRuns ignores unknown', latestRuns([run({ stage: 'nope', status: 'ok' })]).size, 0)

// unknown stage ignored
eq('unknown ignored', deriveRailStatuses([run({ stage: 'nope', status: 'ok' })]).S0, 'idle')

console.log('stages.check: all assertions passed')
