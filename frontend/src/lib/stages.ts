import type { StageRun } from '../api/types'

export const STAGE_IDS = [
  'S0',
  'S1',
  'S2',
  'S3',
  'S4',
  'S5',
  'S6',
  'S7',
  'S8',
  'kit',
  'drift',
] as const

export type StageId = (typeof STAGE_IDS)[number]

export type StageStatus = 'done' | 'running' | 'stale' | 'locked' | 'idle'

export const STAGE_LABELS: Record<StageId, string> = {
  S0: 'Intake',
  S1: 'Interview',
  S2: 'Positioning battle',
  S3: 'Personality',
  S4: 'Naming',
  S5: 'Message hierarchy',
  S6: 'Visual direction',
  S7: 'Consistency audit',
  S8: 'Launch assets',
  kit: 'Brand kit',
  drift: 'Drift check',
}

/** Path segment under /s/:id/. null = no session screen yet (S0 runs at intake). */
export const STAGE_ROUTES: Record<StageId, string | null> = {
  S0: null,
  S1: 'interview',
  S2: 'position',
  S3: 'identity',
  S4: 'naming',
  S5: 'naming',
  S6: 'visual',
  S7: 'audit',
  S8: 'launch',
  kit: 'kit',
  drift: 'drift',
}

/** Page that owns a segment when several stages share one route (identity hosts S3; naming hosts S4/S5). */
const SEGMENT_OWNER: Record<string, StageId> = {
  interview: 'S1',
  brief: 'S1',
  position: 'S2',
  identity: 'S3',
  naming: 'S4',
  visual: 'S6',
  audit: 'S7',
  launch: 'S8',
  kit: 'kit',
  drift: 'drift',
}

export function stageForPath(pathname: string): StageId | null {
  const segment = pathname.split('/')[3]
  if (!segment) return null
  return SEGMENT_OWNER[segment] ?? null
}

/** Latest run per stage — list endpoint returns insert order (oldest first within a stage). */
export function latestRuns(runs: readonly StageRun[]): Map<StageId, StageRun> {
  const latest = new Map<StageId, StageRun>()
  for (const run of runs) {
    if ((STAGE_IDS as readonly string[]).includes(run.stage)) {
      latest.set(run.stage as StageId, run)
    }
  }
  return latest
}

/**
 * Rail status from stage-runs merged with live page status.
 * Priority: running → locked → stale → done → idle (error counts as idle).
 */
export function deriveRailStatuses(
  runs: readonly StageRun[],
  pageStatuses: Partial<Record<StageId, StageStatus>> = {},
): Partial<Record<StageId, StageStatus>> {
  const latest = latestRuns(runs)
  const statuses: Partial<Record<StageId, StageStatus>> = {}
  for (const stage of STAGE_IDS) {
    const run = latest.get(stage)
    const page = pageStatuses[stage]
    if (page === 'running' || run?.status === 'running') {
      statuses[stage] = 'running'
    } else if (run?.locked) {
      statuses[stage] = 'locked'
    } else if (run?.stale) {
      statuses[stage] = 'stale'
    } else if (
      page === 'done' ||
      run?.status === 'ok' ||
      run?.status === 'degraded' ||
      run?.status === 'done'
    ) {
      statuses[stage] = 'done'
    } else {
      statuses[stage] = 'idle'
    }
  }
  return statuses
}
