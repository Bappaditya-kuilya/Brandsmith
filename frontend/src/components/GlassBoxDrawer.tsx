import { useEffect, useRef } from 'react'
import type { StageRun } from '../api/types'
import { latestRuns, type StageId } from '../lib/stages'

export interface GlassBoxDrawerProps {
  open: boolean
  onClose: () => void
  runs: StageRun[]
  current?: StageId | null
  onToggleLock?: (stage: StageId) => void
  lockBusy?: boolean
}

const STATUS_STYLE: Record<string, string> = {
  ok: 'bg-green-100 text-green-800',
  done: 'bg-green-100 text-green-800',
  degraded: 'bg-amber-100 text-amber-800',
  error: 'bg-red-100 text-red-800',
  running: 'bg-accent/25 text-ink',
}

function formatLatency(ms: number | null): string {
  if (ms == null) return '—'
  return ms >= 1000 ? `${(ms / 1000).toFixed(1)}s` : `${ms}ms`
}

export function GlassBoxDrawer({
  open,
  onClose,
  runs,
  current,
  onToggleLock,
  lockBusy,
}: GlassBoxDrawerProps) {
  const closeRef = useRef<HTMLButtonElement>(null)

  useEffect(() => {
    if (!open) return
    closeRef.current?.focus()
    function onKeydown(e: KeyboardEvent) {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKeydown)
    return () => window.removeEventListener('keydown', onKeydown)
  }, [open, onClose])

  const currentRun = current ? latestRuns(runs).get(current) : undefined
  // Backend lock accepts S0–S8 only (not kit/drift).
  const canToggle =
    /^S[0-8]$/.test(current ?? '') &&
    Boolean(currentRun) &&
    Boolean(onToggleLock) &&
    currentRun?.status !== 'running'

  return (
    <>
      {open && (
        <button
          type="button"
          aria-label="Close Glass Box"
          onClick={onClose}
          className="fixed inset-0 z-40 cursor-default bg-black/20"
        />
      )}
      <aside
        id="glass-box-drawer"
        role="dialog"
        aria-modal={open}
        aria-label="Glass Box"
        aria-hidden={!open}
        className={`fixed inset-y-0 right-0 z-50 flex w-full max-w-md transform flex-col border-l border-line bg-surface transition-transform duration-200 ${
          open ? 'translate-x-0' : 'translate-x-full'
        }`}
      >
        <header className="flex items-center justify-between border-b border-line px-4 py-3">
          <div>
            <h2 className="text-sm font-semibold text-ink">Glass Box</h2>
            <p className="text-xs text-ink-3">Every model call behind this brand kit</p>
          </div>
          <button
            ref={closeRef}
            type="button"
            onClick={onClose}
            tabIndex={open ? 0 : -1}
            className="rounded-lg px-2 py-1 text-sm text-ink-2 hover:bg-line hover:text-ink focus:outline-none focus-visible:ring-2 focus-visible:ring-ink"
          >
            Close
          </button>
        </header>

        <div className="flex-1 overflow-y-auto p-4">
          {canToggle && current && currentRun && onToggleLock && (
            <div className="mb-3 flex items-center justify-between rounded-lg border border-line bg-paper-2 px-3 py-2">
              <span className="text-xs font-medium text-ink-2">
                Lock {current} output against re-runs
              </span>
              <button
                type="button"
                disabled={lockBusy}
                onClick={() => onToggleLock(current)}
                aria-pressed={currentRun.locked}
                className="rounded-sm border border-line-2 bg-surface px-2 py-1 text-xs font-semibold text-ink-2 hover:bg-line focus:outline-none focus-visible:ring-2 focus-visible:ring-ink disabled:opacity-50"
              >
                {currentRun.locked ? 'Unlock' : 'Lock'}
              </button>
            </div>
          )}

          {runs.length === 0 ? (
            <div className="rounded-lg border border-dashed border-line-2 p-6 text-center">
              <p className="text-sm font-medium text-ink">No stage runs yet</p>
              <p className="mt-1 text-xs leading-relaxed text-ink-3">
                Run a stage and its model, latency, prompt version and score will show up here — inputs and raw
                output included.
              </p>
            </div>
          ) : (
            <ol className="space-y-3">
              {runs.map((run) => (
                <li key={run.id} className="rounded-lg border border-line p-3">
                  <div className="flex items-center justify-between gap-2">
                    <span className="font-mono text-xs font-semibold text-ink">
                      {run.stage}
                    </span>
                    <span className="flex items-center gap-1.5">
                      {run.stale && (
                        <span className="rounded-full bg-amber-100 px-2 py-0.5 text-[11px] font-medium text-amber-800">
                          stale
                        </span>
                      )}
                      {run.locked && (
                        <span className="rounded-full bg-line px-2 py-0.5 text-[11px] font-medium text-ink-2">
                          locked
                        </span>
                      )}
                      <span
                        className={`rounded-full px-2 py-0.5 text-[11px] font-medium ${
                          STATUS_STYLE[run.status] ?? 'bg-line text-ink-2'
                        }`}
                      >
                        {run.status}
                      </span>
                    </span>
                  </div>
                  <dl className="mt-2 grid grid-cols-2 gap-x-3 gap-y-1 text-xs text-ink-2">
                    <div>
                      <dt className="inline font-medium text-ink">Model </dt>
                      <dd className="inline break-all">{run.model ?? '—'}</dd>
                    </div>
                    <div>
                      <dt className="inline font-medium text-ink">Latency </dt>
                      <dd className="inline">{formatLatency(run.latencyMs)}</dd>
                    </div>
                    <div>
                      <dt className="inline font-medium text-ink">Prompt </dt>
                      <dd className="inline">{run.promptVersion ?? '—'}</dd>
                    </div>
                    {run.score != null && (
                      <div>
                        <dt className="inline font-medium text-ink">Score </dt>
                        <dd className="inline">{run.score}</dd>
                      </div>
                    )}
                  </dl>
                </li>
              ))}
            </ol>
          )}
        </div>
      </aside>
    </>
  )
}
