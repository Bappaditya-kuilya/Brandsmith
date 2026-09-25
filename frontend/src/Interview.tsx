import { useEffect, useState, type FormEvent } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { getSession, patchBrief, submitInterviewAnswer } from './api/client'
import type { BriefState, FieldId } from './api/types'

const MAX_QUESTIONS = 6
const DONE_CONFIDENCE = 0.75

const FIELD_ORDER: FieldId[] = [
  'target_user',
  'problem_alternative',
  'desired_outcome',
  'category_competitors',
  'founder_goal',
  'constraints',
  'tone_hints',
  'proof_advantage',
]

const FIELD_LABELS: Record<FieldId, string> = {
  target_user: 'Target user',
  problem_alternative: 'Problem & alternative',
  desired_outcome: 'Desired outcome',
  category_competitors: 'Category & competitors',
  founder_goal: 'Founder goal',
  constraints: 'Constraints',
  tone_hints: 'Tone hints',
  proof_advantage: 'Proof / advantage',
}

function errorMessage(err: unknown): string {
  return err instanceof Error ? err.message : 'Something went wrong. Please try again.'
}

/** Weights mirror BriefFieldId priorities on the backend. */
const FIELD_WEIGHTS: Record<FieldId, number> = {
  target_user: 5,
  problem_alternative: 5,
  desired_outcome: 4,
  category_competitors: 3,
  founder_goal: 3,
  constraints: 2,
  tone_hints: 2,
  proof_advantage: 2,
}
const TOTAL_WEIGHT = Object.values(FIELD_WEIGHTS).reduce((a, b) => a + b, 0)

function questionCountOf(brief?: BriefState): number {
  if (!brief) return 0
  if (brief.questionCount !== undefined) return brief.questionCount
  return brief.question_count ?? 0
}

/** Client-side overall confidence from answered field confidences + weights. */
function briefConfidence(brief?: BriefState): number {
  if (!brief?.fields) return 0
  let earned = 0
  for (const field of FIELD_ORDER) {
    const value = brief.fields[field]
    if (value?.value) earned += (value.confidence || 0) * (FIELD_WEIGHTS[field] / TOTAL_WEIGHT)
  }
  return earned
}

function isDone(brief?: BriefState): boolean {
  if (!brief) return false
  return briefConfidence(brief) >= DONE_CONFIDENCE || questionCountOf(brief) >= MAX_QUESTIONS
}

function ConfidenceMeter({ value }: { value: number }) {
  const pct = Math.round(Math.min(1, Math.max(0, value)) * 100)
  return (
    <div className="flex items-center gap-2" aria-label={`Confidence ${pct} percent`}>
      <div className="h-2 flex-1 overflow-hidden rounded-full bg-line">
        <div
          className="h-full rounded-full bg-accent transition-all duration-500"
          style={{ width: `${pct}%` }}
        />
      </div>
      <span className="w-10 text-right text-xs tabular-nums text-ink-2">{pct}%</span>
    </div>
  )
}

function confidenceBadge(confidence: number | undefined, assumption?: boolean) {
  if (assumption) {
    return (
      <span className="rounded-full bg-amber-100 px-2 py-0.5 text-xs font-medium text-amber-800">
        Assumed
      </span>
    )
  }
  if (confidence === undefined) {
    return (
      <span className="rounded-full bg-line px-2 py-0.5 text-xs font-medium text-ink-2">
        —
      </span>
    )
  }
  const pct = Math.round(Math.min(1, Math.max(0, confidence)) * 100)
  const tone =
    pct >= 75
      ? 'bg-green-100 text-green-800'
      : pct >= 40
        ? 'bg-amber-100 text-amber-800'
        : 'bg-red-100 text-red-700'
  return (
    <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${tone}`}>{pct}%</span>
  )
}

function LoadingSkeleton() {
  return (
    <div className="animate-pulse space-y-4" aria-busy="true" aria-label="Loading">
      <div className="h-4 w-28 rounded bg-line" />
      <div className="h-6 w-3/4 rounded bg-line" />
      <div className="h-4 w-full rounded bg-line" />
      <div className="h-32 rounded-lg bg-line" />
      <div className="h-10 w-40 rounded-lg bg-line" />
    </div>
  )
}

function ErrorPanel({ message, onRetry }: { message: string; onRetry: () => void }) {
  return (
    <div className="space-y-3">
      <p role="alert" className="rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
        {message}
      </p>
      <button
        type="button"
        onClick={onRetry}
        className="rounded-lg border border-line-2 bg-surface px-4 py-2 text-sm font-medium text-ink-2 hover:border-ink hover:text-ink"
      >
        Try again
      </button>
    </div>
  )
}

function Interview() {
  const { id = '' } = useParams()
  const navigate = useNavigate()
  const [phase, setPhase] = useState<'loading' | 'ready' | 'error'>('loading')
  const [error, setError] = useState<string | null>(null)
  const [question, setQuestion] = useState<string | null>(null)
  const [fieldId, setFieldId] = useState<string | undefined>(undefined)
  const [confidence, setConfidence] = useState(0)
  const [count, setCount] = useState(0)
  const [answer, setAnswer] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [reloadKey, setReloadKey] = useState(0)

  useEffect(() => {
    if (!id) return
    let cancelled = false

    async function load() {
      try {
        const session = await getSession(id)
        if (cancelled) return
        const brief = session.briefState
        setConfidence(briefConfidence(brief))
        setCount(questionCountOf(brief))
        if (isDone(brief)) {
          navigate(`/s/${id}/brief`, { replace: true })
          return
        }
        // Mid-question reload: current_field set but question text isn't persisted.
        // Don't POST {} (400 "Answer or skip is required") — show field + wait for answer/skip.
        if (brief?.current_field) {
          setFieldId(brief.current_field)
          setQuestion(null)
          setPhase('ready')
          return
        }
        const res = await submitInterviewAnswer(id, {})
        if (cancelled) return
        if (res.done || !res.nextQuestion) {
          navigate(`/s/${id}/brief`, { replace: true })
          return
        }
        setConfidence(res.overallConfidence)
        setCount(res.questionCount)
        setFieldId(res.fieldId)
        setQuestion(res.nextQuestion)
        setPhase('ready')
      } catch (err) {
        if (cancelled) return
        setError(errorMessage(err))
        setPhase('error')
      }
    }

    void load()
    return () => {
      cancelled = true
    }
  }, [id, navigate, reloadKey])

  async function send(body: { answer?: string; skip?: boolean }) {
    setSubmitting(true)
    setError(null)
    try {
      const res = await submitInterviewAnswer(id, body)
      setAnswer('')
      setConfidence(res.overallConfidence)
      setCount(res.questionCount)
      setFieldId(res.fieldId)
      if (res.done || !res.nextQuestion) {
        navigate(`/s/${id}/brief`, { replace: true })
        return
      }
      setQuestion(res.nextQuestion)
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setSubmitting(false)
    }
  }

  function handleSubmit(e: FormEvent) {
    e.preventDefault()
    if (submitting) return
    const trimmed = answer.trim()
    if (!trimmed) return
    void send({ answer: trimmed })
  }

  const label =
    fieldId && fieldId in FIELD_LABELS ? FIELD_LABELS[fieldId as FieldId] : null

  function retry() {
    setPhase('loading')
    setError(null)
    setReloadKey((k) => k + 1)
  }

  if (!id) {
    return (
      <main className="mx-auto flex min-h-svh max-w-xl flex-col justify-center gap-4 p-8">
        <p role="alert" className="rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
          Missing session id.
        </p>
        <Link to="/" className="text-sm text-ink-2 hover:underline">
          ← Home
        </Link>
      </main>
    )
  }

  return (
    <main className="mx-auto flex min-h-svh max-w-xl flex-col justify-center gap-6 p-8">
      <div className="space-y-3">
        <div className="flex items-center justify-between text-xs font-medium text-ink-3">
          <Link to="/" className="text-ink-2 hover:underline">
            Brandsmith
          </Link>
          <span className="font-mono">
            {count}/{MAX_QUESTIONS} questions
          </span>
        </div>
        <ConfidenceMeter value={confidence} />
      </div>

      {phase === 'loading' && <LoadingSkeleton />}

      {phase === 'error' && error && <ErrorPanel message={error} onRetry={retry} />}

      {phase === 'ready' && (
        <form onSubmit={handleSubmit} className="space-y-4" noValidate>
          <div className="space-y-2">
            {label && (
              <span className="text-xs font-medium text-ink-2">
                {label}
              </span>
            )}
            <h1 className="text-2xl font-semibold leading-snug text-ink">
              {question ?? 'Interview'}
            </h1>
          </div>

          <label htmlFor="answer" className="sr-only">
            Your answer
          </label>
          <textarea
            id="answer"
            value={answer}
            onChange={(e) => setAnswer(e.target.value)}
            rows={5}
            placeholder="Answer in a sentence or two — what actually happens, not the pitch…"
            disabled={submitting}
            className="w-full resize-y rounded-lg border border-line-2 bg-surface px-3 py-2 text-sm text-ink placeholder:text-ink-3 focus:border-ink focus:outline-none focus:ring-2 focus:ring-accent/50 disabled:opacity-60"
          />

          {error && (
            <p
              role="alert"
              className="rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700"
            >
              {error}
            </p>
          )}

          <div className="flex flex-wrap items-center gap-3">
            <button
              type="submit"
              disabled={submitting || answer.trim().length === 0}
              className="rounded-lg bg-accent px-4 py-2.5 text-sm font-semibold text-ink hover:bg-accent-strong disabled:cursor-not-allowed disabled:opacity-50"
            >
              {submitting ? 'Saving…' : 'Submit'}
            </button>
            <button
              type="button"
              onClick={() => {
                if (!submitting) void send({ skip: true })
              }}
              disabled={submitting}
              className="rounded-lg border border-line-2 bg-surface px-4 py-2.5 text-sm font-medium text-ink-2 hover:border-line-2 hover:text-ink disabled:opacity-50"
            >
              Skip
            </button>
            <span className="text-xs text-ink-3">Skip marks this as an assumption.</span>
          </div>
        </form>
      )}
    </main>
  )
}

function BriefReview() {
  const { id = '' } = useParams()
  const navigate = useNavigate()
  const [phase, setPhase] = useState<'loading' | 'ready' | 'error'>('loading')
  const [error, setError] = useState<string | null>(null)
  const [brief, setBrief] = useState<BriefState | null>(null)
  const [editing, setEditing] = useState<FieldId | null>(null)
  const [draft, setDraft] = useState('')
  const [saving, setSaving] = useState(false)
  const [saveError, setSaveError] = useState<string | null>(null)
  const [reloadKey, setReloadKey] = useState(0)

  useEffect(() => {
    if (!id) return
    let cancelled = false
    getSession(id)
      .then((session) => {
        if (cancelled) return
        setBrief(session.briefState ?? null)
        setPhase('ready')
      })
      .catch((err: unknown) => {
        if (cancelled) return
        setError(errorMessage(err))
        setPhase('error')
      })
    return () => {
      cancelled = true
    }
  }, [id, reloadKey])

  async function save(fieldId: FieldId) {
    setSaving(true)
    setSaveError(null)
    try {
      const next = await patchBrief(id, { fields: { [fieldId]: { value: draft } } })
      setBrief(next)
      setEditing(null)
    } catch (err) {
      setSaveError(errorMessage(err))
    } finally {
      setSaving(false)
    }
  }

  const entries = FIELD_ORDER.map((field) => ({ field, data: brief?.fields?.[field] }))
  const hasAnyValue = entries.some((e) => e.data?.value)

  function retry() {
    setPhase('loading')
    setError(null)
    setReloadKey((k) => k + 1)
  }

  if (!id) {
    return (
      <main className="mx-auto flex min-h-svh max-w-xl flex-col justify-center gap-4 p-8">
        <p role="alert" className="rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
          Missing session id.
        </p>
        <Link to="/" className="text-sm text-ink-2 hover:underline">
          ← Home
        </Link>
      </main>
    )
  }

  return (
    <main className="mx-auto flex min-h-svh max-w-xl flex-col gap-6 p-8">
      <div className="space-y-1">
        <div className="flex items-center justify-between text-xs font-medium text-ink-3">
          <Link to="/" className="text-ink-2 hover:underline">
            Brandsmith
          </Link>
          <span className="font-mono">Brief</span>
        </div>
        <h1 className="text-3xl font-bold tracking-tight text-ink">Your brief</h1>
        {brief && briefConfidence(brief) > 0 && (
          <ConfidenceMeter value={briefConfidence(brief)} />
        )}
      </div>

      {phase === 'loading' && (
        <div className="animate-pulse space-y-3" aria-busy="true" aria-label="Loading brief">
          {[0, 1, 2, 3].map((i) => (
            <div key={i} className="rounded-lg border border-line p-4">
              <div className="mb-2 h-4 w-40 rounded bg-line" />
              <div className="h-4 w-full rounded bg-line" />
            </div>
          ))}
        </div>
      )}

      {phase === 'error' && error && <ErrorPanel message={error} onRetry={retry} />}

      {phase === 'ready' && !hasAnyValue && (
        <div className="rounded-lg border border-dashed border-line-2 p-6 text-center">
          <p className="text-sm text-ink-2">No answers in the brief yet.</p>
          <Link
            to={`/s/${id}/interview`}
            className="mt-3 inline-block rounded-lg bg-accent px-4 py-2 text-sm font-semibold text-ink hover:bg-accent-strong"
          >
            Start the interview
          </Link>
        </div>
      )}

      {phase === 'ready' && hasAnyValue && (
        <>
          {saveError && (
            <p
              role="alert"
              className="rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700"
            >
              {saveError}
            </p>
          )}
          <ul className="space-y-3">
            {entries.map(({ field, data }) => {
              const isEditing = editing === field
              return (
                <li key={field} className="rounded-lg border border-line bg-surface p-4">
                  <div className="mb-2 flex items-center justify-between gap-2">
                    <span className="text-sm font-semibold text-ink">
                      {FIELD_LABELS[field]}
                    </span>
                    <span className="flex items-center gap-1.5">
                      {confidenceBadge(data?.confidence, data?.assumption)}
                    </span>
                  </div>
                  {isEditing ? (
                    <div className="space-y-2">
                      <label htmlFor={`edit-${field}`} className="sr-only">
                        Edit {FIELD_LABELS[field]}
                      </label>
                      <textarea
                        id={`edit-${field}`}
                        value={draft}
                        onChange={(e) => setDraft(e.target.value)}
                        rows={3}
                        disabled={saving}
                        className="w-full resize-y rounded-lg border border-line-2 px-3 py-2 text-sm text-ink focus:border-ink focus:outline-none focus:ring-2 focus:ring-accent/50 disabled:opacity-60"
                      />
                      <div className="flex gap-2">
                        <button
                          type="button"
                          onClick={() => void save(field)}
                          disabled={saving || draft.trim().length === 0}
                          className="rounded-lg bg-accent px-3 py-1.5 text-sm font-semibold text-ink hover:bg-accent-strong disabled:cursor-not-allowed disabled:opacity-50"
                        >
                          {saving ? 'Saving…' : 'Save'}
                        </button>
                        <button
                          type="button"
                          onClick={() => {
                            setEditing(null)
                            setSaveError(null)
                          }}
                          disabled={saving}
                          className="rounded-lg border border-line-2 px-3 py-1.5 text-sm font-medium text-ink-2 hover:border-line-2 disabled:opacity-50"
                        >
                          Cancel
                        </button>
                      </div>
                    </div>
                  ) : (
                    <div className="space-y-2">
                      <p className="text-sm text-ink-2">
                        {data?.value?.trim() ? data.value : <span className="text-ink-3">Not answered</span>}
                      </p>
                      {data?.evidence && (
                        <p className="border-l-2 border-accent pl-2 text-xs italic text-ink-3">
                          {data.evidence}
                        </p>
                      )}
                      <button
                        type="button"
                        onClick={() => {
                          setEditing(field)
                          setDraft(data?.value ?? '')
                          setSaveError(null)
                        }}
                        className="text-xs font-medium text-ink-2 hover:underline"
                      >
                        Edit
                      </button>
                    </div>
                  )}
                </li>
              )
            })}
          </ul>

          <div className="flex items-center justify-between gap-3 pt-2">
            <Link
              to={`/s/${id}/interview`}
              className="text-sm font-medium text-ink-2 hover:underline"
            >
              ← Back to interview
            </Link>
            <button
              type="button"
              onClick={() => navigate(`/s/${id}/position`)}
              className="rounded-lg bg-accent px-5 py-2.5 text-sm font-semibold text-ink hover:bg-accent-strong"
            >
              Continue
            </button>
          </div>
        </>
      )}
    </main>
  )
}

export { BriefReview, Interview }
