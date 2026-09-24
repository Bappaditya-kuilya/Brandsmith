import { useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { getSession, selectPosition } from './api/client'
import type { JudgeResult, Position } from './api/types'
import { ErrorToast } from './components/ErrorToast'
import { StageProgress } from './components/StageProgress'
import { useSse } from './hooks/useSse'
import type { StageStatus } from './lib/stages'

const MANDATE_LABELS: Record<Position['mandate'], string> = {
  native: 'Category native',
  contrarian: 'Contrarian',
  emotional: 'Emotional anchor',
}

const CRITERIA = [
  ['audienceFit', 'Audience fit'],
  ['distinctiveness', 'Distinctiveness'],
  ['credibility', 'Credibility'],
  ['memorability', 'Memorability'],
  ['feasibility', 'Feasibility'],
] as const

const EDIT_FIELDS = ['category', 'differentiator', 'valueProposition'] as const

interface Draft {
  category: string
  differentiator: string
  valueProposition: string
  proofPoints: string
}

function toDraft(p: Position): Draft {
  return {
    category: p.category,
    differentiator: p.differentiator,
    valueProposition: p.valueProposition,
    proofPoints: p.proofPoints.join('\n'),
  }
}

function buildEdits(p: Position, d: Draft): Partial<Position> | undefined {
  const edits: Partial<Position> = {}
  for (const field of EDIT_FIELDS) {
    const value = d[field].trim()
    if (value !== p[field]) edits[field] = value
  }
  const proofPoints = d.proofPoints
    .split('\n')
    .map((s) => s.trim())
    .filter(Boolean)
  if (proofPoints.join('\n') !== p.proofPoints.join('\n')) edits.proofPoints = proofPoints
  return Object.keys(edits).length > 0 ? edits : undefined
}

const FIELD_LABELS: Record<(typeof EDIT_FIELDS)[number] | 'proofPoints', string> = {
  category: 'Category',
  differentiator: 'Differentiator',
  valueProposition: 'Value proposition',
  proofPoints: 'Proof points (one per line)',
}

export interface PositionBattleProps {
  onStatus?: (status: StageStatus) => void
}

export function PositionBattle({ onStatus }: PositionBattleProps) {
  const { id = '' } = useParams()
  const navigate = useNavigate()
  const [loadError, setLoadError] = useState<string | null>(null)
  const [positions, setPositions] = useState<Position[]>([])
  const [judge, setJudge] = useState<JudgeResult | null>(null)
  const [progress, setProgress] = useState('Three positioning agents are working…')
  const [error, setError] = useState<string | null>(null)
  const [editing, setEditing] = useState<number | null>(null)
  const [draft, setDraft] = useState<Draft | null>(null)
  const [selecting, setSelecting] = useState(false)
  const [note, setNote] = useState('')
  const [streamError, setStreamError] = useState(false)

  const sse = useSse({
    onProgress: (d) => setProgress(d.message),
    onStageCompleted: (d) => {
      const payload = d as unknown as { positions?: Position[]; judge?: JudgeResult }
      if (!payload.positions?.length) {
        setStreamError(true)
        setError('The battle finished without positions. Retry to run it again.')
        return
      }
      setPositions(payload.positions)
      setJudge(payload.judge ?? null)
      setEditing(null)
      setDraft(null)
      onStatus?.('done')
    },
    onError: (d) => {
      setStreamError(true)
      setError(d.message)
    },
  })

  useEffect(() => {
    if (!id) return
    let cancelled = false
    getSession(id)
      .then((session) => {
        if (cancelled) return
        const dna = session.brandDna as { position?: unknown } | undefined
        if (dna?.position) {
          navigate(`/s/${id}/identity`, { replace: true })
          return
        }
        setLoadError(null)
      })
      .catch((err: unknown) => {
        if (cancelled) return
        setLoadError(err instanceof Error ? err.message : 'Could not load this session.')
      })
    return () => {
      cancelled = true
    }
  }, [id, navigate])

  function runBattle(mode: 'run' | 'regenerate') {
    setError(null)
    setStreamError(false)
    setProgress('Three positioning agents are working…')
    onStatus?.('running')
    const trimmed = mode === 'regenerate' ? note.trim() : ''
    const path = `/api/sessions/${encodeURIComponent(id)}/stages/position/run${
      trimmed ? `?note=${encodeURIComponent(trimmed)}` : ''
    }`
    void sse.run(path)
  }

  async function pick(index: number) {
    setSelecting(true)
    setError(null)
    try {
      const pos = positions[index]
      const edits = draft && editing === index && pos ? buildEdits(pos, draft) : undefined
      await selectPosition(id, edits ? { index, edits } : { index })
      onStatus?.('done')
      navigate(`/s/${id}/identity`)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not save this position.')
    } finally {
      setSelecting(false)
    }
  }

  const scoreByMandate = new Map((judge?.scores ?? []).map((s) => [s.mandate, s]))
  const diff = judge?.differenceCheck
  const diffFailed = Boolean(diff && !diff.ok)

  return (
    <main className="mx-auto w-full max-w-6xl space-y-6 p-8">
      <div className="space-y-1">
        <div className="flex items-center justify-between text-xs font-medium uppercase tracking-wide text-ink-3">
          <Link to={`/s/${id}/brief`} className="text-ink-2 hover:underline">
            ← Brief
          </Link>
          <span className="font-mono">Stage S2</span>
        </div>
        <h1 className="text-3xl font-bold tracking-tight text-ink">Positioning battle</h1>
        <p className="text-sm text-ink-2">
          Three agents argue a position each — a category native, a contrarian, and an emotional
          anchor. A judge scores all five criteria and checks the frames actually differ. Pick one,
          edit it inline, or regenerate with a note.
        </p>
      </div>

      {loadError && (
        <p role="alert" className="rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
          {loadError}
        </p>
      )}

      {diffFailed && diff?.note && (
        <div role="alert" className="rounded-lg border border-amber-300 bg-amber-50 px-4 py-3 text-sm text-amber-900">
          <span className="font-semibold">Difference check failed. </span>
          {diff.note} Regenerate with a note asking for sharper category frames.
        </div>
      )}

      {sse.streaming && (
        <div className="rounded-lg border border-line bg-surface p-6">
          <StageProgress message={progress} bars={3} />
        </div>
      )}

      {!sse.streaming && positions.length === 0 && (
        <div className="rounded-lg border border-dashed border-line-2 p-8 text-center">
          <p className="text-sm text-ink-2">
            No positions yet. The battle runs all three agents in parallel, then the judge scores
            them.
          </p>
          <button
            type="button"
            onClick={() => runBattle('run')}
            disabled={Boolean(loadError)}
            className="mt-4 rounded-lg bg-accent px-5 py-2.5 text-sm font-semibold text-ink hover:bg-accent-strong disabled:cursor-not-allowed disabled:opacity-50"
          >
            Run battle
          </button>
        </div>
      )}

      {!sse.streaming && positions.length > 0 && (
        <>
          <ul className="grid gap-4 md:grid-cols-3">
            {positions.map((pos, index) => {
              const score = scoreByMandate.get(pos.mandate)
              const isEditing = editing === index
              return (
                <li
                  key={index}
                  className="flex flex-col rounded-lg border border-line bg-surface p-4"
                >
                  <div className="mb-3 flex items-start justify-between gap-2">
                    <div>
                      <span className="inline-block rounded-full bg-accent/25 px-2 py-0.5 text-xs font-semibold text-ink">
                        {MANDATE_LABELS[pos.mandate] ?? pos.mandate}
                      </span>
                      <p className="mt-2 text-sm font-semibold text-ink">
                        {isEditing ? (
                          <>
                            <label htmlFor={`edit-category-${index}`} className="mb-1 block text-xs font-medium text-ink-3">
                              {FIELD_LABELS.category}
                            </label>
                            <input
                              id={`edit-category-${index}`}
                              value={draft?.category ?? ''}
                              onChange={(e) => setDraft((d) => (d ? { ...d, category: e.target.value } : d))}
                              disabled={selecting}
                              className="w-full rounded-md border border-line-2 px-2 py-1.5 text-sm text-ink focus:border-ink focus:outline-none focus:ring-2 focus:ring-accent/50 disabled:opacity-60"
                            />
                          </>
                        ) : (
                          pos.category
                        )}
                      </p>
                    </div>
                    {score && score.total != null && (
                      <span className="shrink-0 rounded-md bg-line px-2 py-1 text-sm font-bold tabular-nums text-ink">
                        {score.total}
                      </span>
                    )}
                  </div>

                  <div className="flex-1 space-y-3 text-sm">
                    <div>
                      <p className="text-xs font-medium uppercase tracking-wide text-ink-3">Differentiator</p>
                      {isEditing ? (
                        <textarea
                          value={draft?.differentiator ?? ''}
                          onChange={(e) => setDraft((d) => (d ? { ...d, differentiator: e.target.value } : d))}
                          rows={3}
                          disabled={selecting}
                          className="mt-1 w-full resize-y rounded-md border border-line-2 px-2 py-1.5 text-sm text-ink focus:border-ink focus:outline-none focus:ring-2 focus:ring-accent/50 disabled:opacity-60"
                        />
                      ) : (
                        <p className="mt-0.5 text-ink-2">{pos.differentiator}</p>
                      )}
                    </div>
                    <div>
                      <p className="text-xs font-medium uppercase tracking-wide text-ink-3">Value proposition</p>
                      {isEditing ? (
                        <textarea
                          value={draft?.valueProposition ?? ''}
                          onChange={(e) => setDraft((d) => (d ? { ...d, valueProposition: e.target.value } : d))}
                          rows={3}
                          disabled={selecting}
                          className="mt-1 w-full resize-y rounded-md border border-line-2 px-2 py-1.5 text-sm text-ink focus:border-ink focus:outline-none focus:ring-2 focus:ring-accent/50 disabled:opacity-60"
                        />
                      ) : (
                        <p className="mt-0.5 text-ink-2">{pos.valueProposition}</p>
                      )}
                    </div>
                    <div>
                      <p className="text-xs font-medium uppercase tracking-wide text-ink-3">Proof points</p>
                      {isEditing ? (
                        <textarea
                          value={draft?.proofPoints ?? ''}
                          onChange={(e) => setDraft((d) => (d ? { ...d, proofPoints: e.target.value } : d))}
                          rows={4}
                          disabled={selecting}
                          aria-label={FIELD_LABELS.proofPoints}
                          className="mt-1 w-full resize-y rounded-md border border-line-2 px-2 py-1.5 text-sm text-ink focus:border-ink focus:outline-none focus:ring-2 focus:ring-accent/50 disabled:opacity-60"
                        />
                      ) : (
                        <ul className="mt-0.5 list-disc space-y-0.5 pl-5 text-ink-2">
                          {(pos.proofPoints ?? []).map((point, i) => (
                            <li key={i}>{point}</li>
                          ))}
                        </ul>
                      )}
                    </div>
                  </div>

                  <div className="mt-4 space-y-2 border-t border-line pt-3">
                    <p className="text-xs font-medium uppercase tracking-wide text-ink-3">Judge scores</p>
                    {score ? (
                      <>
                        <ul className="grid grid-cols-2 gap-x-3 gap-y-1 text-xs">
                          {CRITERIA.map(([key, label]) => (
                            <li key={key} className="flex justify-between gap-2">
                              <span className="text-ink-3">{label}</span>
                              <span className="font-medium tabular-nums text-ink">{score[key]}/5</span>
                            </li>
                          ))}
                        </ul>
                        {score.explanation && <p className="text-xs italic text-ink-3">{score.explanation}</p>}
                      </>
                    ) : (
                      <p className="text-xs text-ink-3">No judge score for this card.</p>
                    )}
                  </div>

                  <div className="mt-4 flex gap-2">
                    <button
                      type="button"
                      onClick={() => void pick(index)}
                      disabled={selecting}
                      className="flex-1 rounded-lg bg-accent px-3 py-2 text-sm font-semibold text-ink hover:bg-accent-strong disabled:cursor-not-allowed disabled:opacity-50"
                    >
                      {selecting && editing === index ? 'Saving…' : 'Pick'}
                    </button>
                    <button
                      type="button"
                      onClick={() => {
                        if (isEditing) {
                          setEditing(null)
                          setDraft(null)
                        } else {
                          setEditing(index)
                          setDraft(toDraft(pos))
                        }
                      }}
                      disabled={selecting}
                      className="rounded-lg border border-line-2 px-3 py-2 text-sm font-medium text-ink-2 hover:border-line-2 hover:text-ink disabled:opacity-50"
                    >
                      {isEditing ? 'Cancel edit' : 'Edit'}
                    </button>
                  </div>
                  {isEditing && (
                    <p className="mt-2 text-xs text-ink-3">
                      Edits are saved when you pick this position.
                    </p>
                  )}
                </li>
              )
            })}
          </ul>

          <div className="space-y-3 rounded-lg border border-line bg-surface p-4">
            <label htmlFor="regen-note" className="block text-sm font-medium text-ink">
              Regenerate with a note
            </label>
            <textarea
              id="regen-note"
              value={note}
              onChange={(e) => setNote(e.target.value)}
              rows={2}
              placeholder="Optional — e.g. push the contrarian further from the incumbent frame…"
              disabled={selecting}
              className="w-full resize-y rounded-lg border border-line-2 px-3 py-2 text-sm text-ink placeholder:text-ink-3 focus:border-ink focus:outline-none focus:ring-2 focus:ring-accent/50 disabled:opacity-60"
            />
            <button
              type="button"
              onClick={() => runBattle('regenerate')}
              disabled={selecting}
              className="rounded-lg border border-line-2 bg-surface px-4 py-2 text-sm font-medium text-ink-2 hover:border-ink hover:text-ink disabled:opacity-50"
            >
              Regenerate
            </button>
          </div>
        </>
      )}

      <ErrorToast
        message={error}
        onRetry={streamError ? () => runBattle(positions.length > 0 ? 'regenerate' : 'run') : undefined}
        onDismiss={() => setError(null)}
      />
    </main>
  )
}
