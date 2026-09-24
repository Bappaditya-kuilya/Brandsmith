import { useCallback, useEffect, useState, type ReactNode } from 'react'
import { useLocation, useNavigate, useParams } from 'react-router-dom'
import { GlassBoxDrawer } from './GlassBoxDrawer'
import { ErrorToast } from './ErrorToast'
import { StageRail } from './StageRail'
import { listStageRuns, rerunStage, setStageLock } from '../api/client'
import type { StageRun } from '../api/types'
import {
  STAGE_IDS,
  STAGE_ROUTES,
  deriveRailStatuses,
  latestRuns,
  stageForPath,
  type StageId,
  type StageStatus,
} from '../lib/stages'

export interface AppShellProps {
  children: ReactNode
  statuses?: Partial<Record<StageId, StageStatus>>
}

/**
 * Shared session chrome: StageRail | main | Glass Box toggle + drawer.
 * Use under /s/:id/* routes (needs useParams for rail navigation).
 */
export function AppShell({ children, statuses: pageStatuses = {} }: AppShellProps) {
  const { id } = useParams()
  const { pathname } = useLocation()
  const navigate = useNavigate()
  const [drawerOpen, setDrawerOpen] = useState(false)
  const [runs, setRuns] = useState<StageRun[]>([])
  const [error, setError] = useState<string | null>(null)
  const [lockBusy, setLockBusy] = useState(false)
  const current = stageForPath(pathname)

  const refreshRuns = useCallback(() => {
    if (!id) return Promise.resolve()
    return listStageRuns(id)
      .then((list) => {
        setRuns(list)
        setError(null)
      })
      .catch(() => {
        // page-level load errors already render inline; avoid a second surface
      })
  }, [id])

  useEffect(() => {
    void refreshRuns()
  }, [refreshRuns])

  useEffect(() => {
    if (drawerOpen) void refreshRuns()
  }, [drawerOpen, refreshRuns])

  const railStatuses = deriveRailStatuses(runs, pageStatuses)
  const latest = latestRuns(runs)
  const staleStages = STAGE_IDS.filter((stage) => {
    const run = latest.get(stage)
    return run?.stale && !run.locked
  })

  function goToStage(stage: StageId) {
    const segment = STAGE_ROUTES[stage]
    if (segment && id) navigate(`/s/${id}/${segment}`)
  }

  async function toggleLock(stage: StageId) {
    if (!id || lockBusy) return
    const run = latestRuns(runs).get(stage)
    if (!run) return
    setLockBusy(true)
    try {
      await setStageLock(id, stage, !run.locked)
      await refreshRuns()
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to update lock.')
    } finally {
      setLockBusy(false)
    }
  }

  async function refreshDownstream() {
    if (!id || staleStages.length === 0) return
    let firstError: unknown = null
    for (const stage of staleStages) {
      try {
        await rerunStage(id, stage)
      } catch (e) {
        if (!firstError) firstError = e
      }
    }
    await refreshRuns()
    if (firstError) {
      setError(firstError instanceof Error ? firstError.message : 'Some re-runs failed.')
    }
  }

  return (
    <div className="flex min-h-svh bg-paper">
      <StageRail statuses={railStatuses} current={current} onNavigate={goToStage} />
      <div className="relative min-w-0 flex-1">
        <div className="flex justify-end p-4 pb-0">
          <button
            type="button"
            onClick={() => setDrawerOpen(true)}
            aria-expanded={drawerOpen}
            aria-controls="glass-box-drawer"
            className="rounded-lg border border-line bg-surface px-3 py-1.5 text-xs font-semibold text-ink-2 shadow-sm hover:bg-paper-2 focus:outline-none focus-visible:ring-2 focus-visible:ring-ink"
          >
            Glass Box
          </button>
        </div>
        {staleStages.length > 0 && (
          <div
            role="status"
            className="mx-4 mt-4 flex flex-wrap items-center gap-3 rounded-lg border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-900"
          >
            <span>
              {staleStages.join(', ')} {staleStages.length === 1 ? 'is' : 'are'} out of date upstream.
            </span>
            <button
              type="button"
              onClick={() => void refreshDownstream()}
              className="rounded-md border border-amber-300 bg-surface px-2.5 py-1 text-xs font-semibold text-amber-900 shadow-sm hover:bg-amber-100 focus:outline-none focus-visible:ring-2 focus-visible:ring-amber-500"
            >
              Re-run downstream stages
            </button>
          </div>
        )}
        <div className="min-h-svh">{children}</div>
      </div>
      <GlassBoxDrawer
        open={drawerOpen}
        onClose={() => setDrawerOpen(false)}
        runs={runs}
        current={current}
        onToggleLock={(stage) => void toggleLock(stage)}
        lockBusy={lockBusy}
      />
      <ErrorToast message={error} onRetry={() => void refreshRuns()} onDismiss={() => setError(null)} />
    </div>
  )
}

/** Lightweight wrapper for /s/:id/* elements. */
export function SessionShell(props: AppShellProps) {
  return <AppShell {...props} />
}
