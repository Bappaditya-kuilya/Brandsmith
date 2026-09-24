import { useEffect, useRef, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { auditRunPath } from './api/client'
import type { AuditDiff, AuditDimension, AuditFinding, AuditResponse, AuditResult } from './api/types'
import { ErrorToast } from './components/ErrorToast'
import { StageProgress } from './components/StageProgress'
import { useSse, type StageCompletedData } from './hooks/useSse'
import type { StageStatus } from './lib/stages'

const DIMENSION_LABELS: Record<string, string> = {
  personalityFit: 'Personality fit',
  voiceCompliance: 'Voice compliance',
  audienceFit: 'Audience fit',
  positioningAlignment: 'Positioning alignment',
  visualCoherence: 'Visual coherence',
}

const FAIL_SCORE = 70

interface AuditRun {
  result: AuditResult
  diffs: AuditDiff[]
  reviseRounds: number
  degraded: boolean
}

function normalize(raw: unknown): AuditRun | null {
  if (!raw || typeof raw !== 'object') return null
  const top = raw as AuditResponse & Partial<AuditResult>
  const nested = (top.result ?? top) as Partial<AuditResult> & Partial<AuditResponse>
  const dimensions = nested.dimensions ?? top.dimensions
  if (!dimensions?.length) return null
  const result: AuditResult = {
    dimensions,
    overall: nested.overall ?? top.overall ?? 0,
    conflicts: nested.conflicts ?? top.conflicts ?? [],
    reviseInstructions: nested.reviseInstructions,
  }
  return {
    result,
    diffs: top.diffs ?? [],
    reviseRounds: top.reviseRounds ?? (top.revised ? 1 : 0),
    degraded: Boolean(top.degraded),
  }
}

function toAudit(raw: StageCompletedData): AuditRun | null {
  const top = raw as StageCompletedData & AuditResponse
  if (top.data && typeof top.data === 'object') return normalize(top.data)
  return normalize(top)
}

function scoreBarClass(score: number): string {
  if (score >= 80) return 'bg-emerald-500'
  if (score >= FAIL_SCORE) return 'bg-accent'
  if (score >= 50) return 'bg-amber-500'
  return 'bg-red-500'
}

function overallBadge(overall: number): string {
  if (overall >= 80) return 'bg-emerald-100 text-emerald-800'
  if (overall >= FAIL_SCORE) return 'bg-accent/25 text-ink'
  if (overall >= 50) return 'bg-amber-100 text-amber-800'
  return 'bg-red-100 text-red-700'
}

function FindingList({ findings, title }: { findings: AuditFinding[]; title: string }) {
  if (!findings.length) return null
  return (
    <div className="space-y-1">
      <p className="text-xs font-medium uppercase tracking-wide text-ink-3">{title}</p>
      <ul className="space-y-1">
        {findings.map((f, i) => (
          <li
            key={i}
            className={`rounded-md border px-2 py-1.5 text-xs ${
              f.severity === 'fail'
                ? 'border-red-200 bg-red-50 text-red-800'
                : 'border-amber-200 bg-amber-50 text-amber-900'
            }`}
          >
            {f.quote && <span className="font-semibold">“{f.quote}” </span>}
            {f.rule ?? f.dimension}
            {f.asset ? <span className="text-ink-3"> · {f.asset}</span> : null}
          </li>
        ))}
      </ul>
    </div>
  )
}

function DimensionCard({ dim }: { dim: AuditDimension }) {
  const label = DIMENSION_LABELS[dim.dimension] ?? dim.dimension
  const failing = dim.score < FAIL_SCORE
  return (
    <li className="flex flex-col rounded-lg border border-line bg-surface p-4">
      <div className="flex items-start justify-between gap-2">
        <div>
          <h3 className="text-sm font-semibold text-ink">{label}</h3>
          <p className="text-xs text-ink-3">Weight {dim.weight}</p>
        </div>
        <span
          className={`shrink-0 rounded-md px-2 py-1 text-sm font-bold tabular-nums ${
            failing ? 'bg-red-100 text-red-700' : 'bg-line text-ink'
          }`}
          title={failing ? `Below ${FAIL_SCORE}` : undefined}
        >
          {dim.score}
        </span>
      </div>

      <div className="mt-3" aria-hidden="true">
        <div className="h-2 w-full overflow-hidden rounded-full bg-line">
          <div
            className={`h-full rounded-full ${scoreBarClass(dim.score)}`}
            style={{ width: `${Math.min(100, Math.max(0, dim.score))}%` }}
          />
        </div>
      </div>

      {dim.evidence.length > 0 && (
        <ul className="mt-3 flex-1 space-y-1.5">
          {dim.evidence.map((quote, i) => (
            <li key={i} className="border-l-2 border-accent pl-2 text-xs italic text-ink-2">
              “{quote}”
            </li>
          ))}
        </ul>
      )}

      <div className="mt-3">
        <FindingList findings={dim.deterministicFindings} title="Deterministic checks" />
      </div>
    </li>
  )
}

function DiffCard({ diff }: { diff: AuditDiff }) {
  return (
    <li className="rounded-lg border border-line bg-surface p-4">
      <div className="flex flex-wrap items-center gap-2 text-xs">
        {diff.asset && (
          <span className="rounded-full bg-accent/25 px-2 py-0.5 font-semibold text-ink">
            {diff.asset}
          </span>
        )}
        {diff.dimension && (
          <span className="rounded-full bg-line px-2 py-0.5 text-ink-2">
            {DIMENSION_LABELS[diff.dimension] ?? diff.dimension}
          </span>
        )}
      </div>
      <div className="mt-3 grid gap-3 md:grid-cols-2">
        <div>
          <p className="text-xs font-medium uppercase tracking-wide text-ink-3">Before</p>
          <p className="mt-1 whitespace-pre-wrap rounded-md border border-red-100 bg-red-50 p-2 text-sm text-ink">
            {diff.before}
          </p>
        </div>
        <div>
          <p className="text-xs font-medium uppercase tracking-wide text-ink-3">After</p>
          <p className="mt-1 whitespace-pre-wrap rounded-md border border-emerald-100 bg-emerald-50 p-2 text-sm text-ink">
            {diff.after}
          </p>
        </div>
      </div>
    </li>
  )
}

export interface AuditPageProps {
  onStatus?: (status: StageStatus) => void
}

export function AuditPage({ onStatus }: AuditPageProps) {
  const { id = '' } = useParams()
  const [audit, setAudit] = useState<AuditRun | null>(null)
  const [progress, setProgress] = useState('Scoring 5 dimensions…')
  const [error, setError] = useState<string | null>(null)
  const [streamError, setStreamError] = useState(false)
  const statusRef = useRef(onStatus)

  useEffect(() => {
    statusRef.current = onStatus
  }, [onStatus])

  const sse = useSse({
    onProgress: (d) => setProgress(d.message || 'Scoring 5 dimensions…'),
    onStageCompleted: (d) => {
      const next = toAudit(d)
      if (next) {
        setAudit(next)
        setError(null)
        setStreamError(false)
        statusRef.current?.('done')
      } else {
        setError('Audit finished without scores. Retry to run it again.')
        setStreamError(true)
        statusRef.current?.('idle')
      }
    },
    onError: (d) => {
      setError(d.message || 'The audit failed.')
      setStreamError(true)
      statusRef.current?.('idle')
    },
  })

  function runAudit() {
    setError(null)
    setStreamError(false)
    setProgress('Scoring 5 dimensions…')
    statusRef.current?.('running')
    void sse.run(auditRunPath(id))
  }

  const revised = Boolean(audit?.reviseRounds)
  const overall = audit?.result.overall ?? 0

  return (
    <main className="mx-auto w-full max-w-6xl space-y-6 p-8">
      <div className="space-y-1">
        <div className="flex items-center justify-between text-xs font-medium uppercase tracking-wide text-ink-3">
          <Link to={`/s/${id}/naming`} className="text-ink-2 hover:underline">
            ← Naming
          </Link>
          <span className="font-mono">Stage S7</span>
        </div>
        <div className="flex flex-wrap items-center gap-3">
          <h1 className="text-3xl font-bold tracking-tight text-ink">Consistency audit</h1>
          {audit && (
            <span
              className={`rounded-full px-3 py-1 text-sm font-bold tabular-nums ${overallBadge(overall)}`}
            >
              Overall {overall}
            </span>
          )}
        </div>
        <p className="text-sm text-ink-2">
          Five weighted dimensions score personality, voice, audience, positioning and visual
          coherence. Failing dimensions trigger up to two auto-revise rounds — only rewritten assets
          are diffed below.
        </p>
      </div>

      {sse.streaming && (
        <div className="rounded-lg border border-line bg-surface p-6">
          <StageProgress
            message={
              progress.includes('Auto-revise')
                ? `${progress} — rewriting only failing assets`
                : progress
            }
            bars={5}
          />
          {revised && audit && (
            <p className="mt-3 text-xs text-ink-3">
              Previous run revised {audit.reviseRounds} round{audit.reviseRounds === 1 ? '' : 's'}.
            </p>
          )}
        </div>
      )}

      {!sse.streaming && !audit && !error && (
        <div className="rounded-lg border border-dashed border-line-2 p-8 text-center">
          <p className="text-sm text-ink-2">
            No audit yet. Run the consistency guardian to score all five dimensions with evidence.
          </p>
          <button
            type="button"
            onClick={runAudit}
            className="mt-4 rounded-lg bg-accent px-5 py-2.5 text-sm font-semibold text-ink hover:bg-accent-strong"
          >
            Run audit
          </button>
        </div>
      )}

      {!sse.streaming && audit && (
        <>
          {audit.degraded && (
            <div
              role="status"
              className="rounded-lg border border-amber-300 bg-amber-50 px-4 py-3 text-sm text-amber-900"
            >
              Judge ran in degraded mode. Scores may be lower quality — retry if needed.
            </div>
          )}

          {revised && (
            <div
              role="status"
              className="rounded-lg border border-emerald-300 bg-emerald-50 px-4 py-3 text-sm text-emerald-900"
            >
              Auto-revised {audit.reviseRounds} of max 2 rounds. Diffs below show only what changed.
            </div>
          )}

          <ul className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-5">
            {audit.result.dimensions.map((dim) => (
              <DimensionCard key={dim.dimension} dim={dim} />
            ))}
          </ul>

          <section aria-labelledby="conflicts-heading" className="space-y-3">
            <div className="flex items-center justify-between">
              <h2 id="conflicts-heading" className="text-lg font-semibold text-ink">
                Conflicts
              </h2>
              <span className="text-xs text-ink-3">{audit.result.conflicts.length}</span>
            </div>
            {audit.result.conflicts.length === 0 ? (
              <p className="rounded-lg border border-dashed border-line-2 p-4 text-sm text-ink-2">
                No hard conflicts. All failing checks were addressed or none were found.
              </p>
            ) : (
              <div className="rounded-lg border border-line bg-surface p-4">
                <FindingList findings={audit.result.conflicts} title="Failing rules" />
              </div>
            )}
          </section>

          {audit.diffs.length > 0 && (
            <section aria-labelledby="diffs-heading" className="space-y-3">
              <h2 id="diffs-heading" className="text-lg font-semibold text-ink">
                Auto-revise diffs
              </h2>
              <ul className="space-y-3">
                {audit.diffs.map((diff, i) => (
                  <DiffCard key={i} diff={diff} />
                ))}
              </ul>
            </section>
          )}

          <div className="flex flex-wrap gap-2">
            <button
              type="button"
              onClick={runAudit}
              className="rounded-lg bg-accent px-4 py-2 text-sm font-semibold text-ink hover:bg-accent-strong"
            >
              Re-run audit
            </button>
            <Link
              to={`/s/${id}/drift`}
              className="rounded-lg border border-line-2 bg-surface px-4 py-2 text-sm font-medium text-ink-2 hover:border-ink hover:text-ink"
            >
              Drift check
            </Link>
          </div>
        </>
      )}

      <ErrorToast
        message={sse.streaming ? null : error}
        onRetry={streamError || (error && !audit) ? runAudit : undefined}
        onDismiss={() => setError(null)}
      />
    </main>
  )
}
