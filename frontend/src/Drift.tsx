import { useMemo, useState, type FormEvent } from 'react'
import { Link, useParams } from 'react-router-dom'
import { driftCheck } from './api/client'
import type { DriftAssetType, DriftCheckResponse } from './api/types'
import { ErrorToast } from './components/ErrorToast'
import type { StageStatus } from './lib/stages'

const MAX_TEXT = 4000
const FAIL_SCORE = 70

const ASSET_OPTIONS: { value: DriftAssetType; label: string }[] = [
  { value: 'post', label: 'Social post' },
  { value: 'email', label: 'Email' },
  { value: 'landing', label: 'Landing page' },
]

const DIMENSION_LABELS: Record<string, string> = {
  personalityFit: 'Personality fit',
  voiceCompliance: 'Voice compliance',
  audienceFit: 'Audience fit',
  positioningAlignment: 'Positioning alignment',
  visualCoherence: 'Visual coherence',
}

function errorMessage(err: unknown): string {
  return err instanceof Error ? err.message : 'Something went wrong. Please try again.'
}

function scoreBarClass(score: number): string {
  if (score >= 80) return 'bg-emerald-500'
  if (score >= FAIL_SCORE) return 'bg-accent'
  if (score >= 50) return 'bg-amber-500'
  return 'bg-red-500'
}

function overallBadge(pass: boolean, overall: number): string {
  if (pass && overall >= 80) return 'bg-emerald-100 text-emerald-800'
  if (pass) return 'bg-accent/25 text-ink'
  if (overall >= 50) return 'bg-amber-100 text-amber-800'
  return 'bg-red-100 text-red-700'
}

/** Split text so each flagged phrase is wrapped in <mark>. Longest-first so nested phrases match. */
function HighlightedText({ text, phrases }: { text: string; phrases: string[] }) {
  const parts = useMemo(() => {
    const sorted = [...phrases].filter(Boolean).sort((a, b) => b.length - a.length)
    if (!sorted.length) return [{ text, hit: false }]
    const escaped = sorted.map((p) => p.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'))
    const re = new RegExp(`(${escaped.join('|')})`, 'gi')
    return text
      .split(re)
      .filter((part) => part.length > 0)
      .map((part) => ({
        text: part,
        hit: sorted.some((p) => p.toLowerCase() === part.toLowerCase()),
      }))
  }, [text, phrases])

  return (
    <p className="whitespace-pre-wrap rounded-md border border-line bg-paper-2 p-3 text-sm text-ink">
      {parts.map((part, i) =>
        part.hit ? (
          <mark key={i} className="rounded bg-amber-200 px-0.5 text-ink">
            {part.text}
          </mark>
        ) : (
          <span key={i}>{part.text}</span>
        ),
      )}
    </p>
  )
}

export interface DriftPageProps {
  onStatus?: (status: StageStatus) => void
}

export function DriftPage({ onStatus }: DriftPageProps) {
  const { id = '' } = useParams()
  const [text, setText] = useState('')
  const [assetType, setAssetType] = useState<DriftAssetType>('post')
  const [result, setResult] = useState<DriftCheckResponse | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [checking, setChecking] = useState(false)
  const [copied, setCopied] = useState(false)

  const canSubmit = text.trim().length > 0 && text.length <= MAX_TEXT && !checking

  async function handleSubmit(e: FormEvent) {
    e.preventDefault()
    if (!canSubmit) return
    setChecking(true)
    setError(null)
    setCopied(false)
    onStatus?.('running')
    try {
      const res = await driftCheck(id, { text: text.trim(), assetType })
      setResult(res)
      onStatus?.('done')
    } catch (err) {
      setError(errorMessage(err))
      onStatus?.('idle')
    } finally {
      setChecking(false)
    }
  }

  async function copyRewrite() {
    if (!result?.rewrite) return
    try {
      await navigator.clipboard.writeText(result.rewrite)
      setCopied(true)
    } catch {
      setError('Could not copy to clipboard. Select the rewrite and copy manually.')
    }
  }

  return (
    <main className="mx-auto w-full max-w-4xl space-y-6 p-8">
      <div className="space-y-1">
        <div className="flex items-center justify-between text-xs font-medium uppercase tracking-wide text-ink-3">
          <Link to={`/s/${id}/audit`} className="text-ink-2 hover:underline">
            ← Consistency audit
          </Link>
          <span className="font-mono">Drift</span>
        </div>
        <div className="flex flex-wrap items-center gap-3">
          <h1 className="text-3xl font-bold tracking-tight text-ink">Drift check</h1>
          {result && (
            <span
              className={`rounded-full px-3 py-1 text-sm font-bold tabular-nums ${overallBadge(
                result.pass,
                result.overall,
              )}`}
            >
              {result.pass ? 'On brand' : 'Drifted'} · {result.overall}
            </span>
          )}
        </div>
        <p className="text-sm text-ink-2">
          Paste copy built outside the studio. Same five-dimension guardian scores it against your
          brand DNA, flags off-voice phrases, and offers an on-brand rewrite.
        </p>
      </div>

      <form onSubmit={handleSubmit} className="space-y-4" noValidate>
        <div className="space-y-2">
          <label htmlFor="drift-asset" className="block text-sm font-medium text-ink">
            Asset type
          </label>
          <select
            id="drift-asset"
            value={assetType}
            onChange={(e) => setAssetType(e.target.value as DriftAssetType)}
            disabled={checking}
            className="rounded-lg border border-line-2 bg-surface px-3 py-2 text-sm text-ink focus:border-ink focus:outline-none focus:ring-2 focus:ring-accent/50 disabled:opacity-60"
          >
            {ASSET_OPTIONS.map((opt) => (
              <option key={opt.value} value={opt.value}>
                {opt.label}
              </option>
            ))}
          </select>
        </div>

        <div className="space-y-2">
          <label htmlFor="drift-text" className="block text-sm font-medium text-ink">
            Paste text
          </label>
          <textarea
            id="drift-text"
            value={text}
            onChange={(e) => setText(e.target.value)}
            rows={8}
            maxLength={MAX_TEXT}
            placeholder="Paste a post, email or landing snippet to score…"
            disabled={checking}
            className="w-full resize-y rounded-lg border border-line-2 px-3 py-2 text-sm text-ink placeholder:text-ink-3 focus:border-ink focus:outline-none focus:ring-2 focus:ring-accent/50 disabled:opacity-60"
          />
          <div className="flex justify-between text-xs text-ink-3">
            <span>{text.trim().length === 0 ? 'Required' : 'Scored against brand DNA'}</span>
            <span>
              {text.length}/{MAX_TEXT}
            </span>
          </div>
        </div>

        <button
          type="submit"
          disabled={!canSubmit}
          className="rounded-lg bg-accent px-5 py-2.5 text-sm font-semibold text-ink hover:bg-accent-strong disabled:cursor-not-allowed disabled:opacity-50"
        >
          {checking ? 'Checking…' : 'Check drift'}
        </button>
      </form>

      {checking && (
        <div
          role="status"
          aria-live="polite"
          aria-busy="true"
          className="rounded-lg border border-line bg-surface p-6"
        >
          <p className="flex items-center gap-2 text-sm text-ink-2">
            <span
              aria-hidden="true"
              className="h-2 w-2 shrink-0 animate-pulse rounded-full bg-accent"
            />
            Scoring 5 dimensions…
          </p>
          <div aria-hidden="true" className="mt-4 space-y-2">
            {[0, 1, 2, 3, 4].map((i) => (
              <div
                key={i}
                className={`h-3 animate-pulse rounded bg-line ${i === 4 ? 'w-2/3' : 'w-full'}`}
              />
            ))}
          </div>
        </div>
      )}

      {!checking && result && (
        <div className="space-y-6">
          <ul className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-5">
            {result.dimensions.map((v) => {
              const label = DIMENSION_LABELS[v.dimension] ?? v.dimension
              return (
                <li key={v.dimension} className="rounded-lg border border-line bg-surface p-4">
                  <div className="flex items-start justify-between gap-2">
                    <div>
                      <h3 className="text-sm font-semibold text-ink">{label}</h3>
                      <p className="text-xs text-ink-3">Weight {v.weight}</p>
                    </div>
                    <span
                      className={`shrink-0 rounded-full px-2 py-0.5 text-xs font-bold ${
                        v.pass ? 'bg-emerald-100 text-emerald-800' : 'bg-red-100 text-red-700'
                      }`}
                    >
                      {v.pass ? 'Pass' : 'Fail'}
                    </span>
                  </div>
                  <div className="mt-2 flex items-center gap-2">
                    <div className="h-2 flex-1 overflow-hidden rounded-full bg-line" aria-hidden="true">
                      <div
                        className={`h-full rounded-full ${scoreBarClass(v.score)}`}
                        style={{ width: `${Math.min(100, Math.max(0, v.score))}%` }}
                      />
                    </div>
                    <span className="text-xs font-bold tabular-nums text-ink-2">{v.score}</span>
                  </div>
                  {v.evidence.length > 0 && (
                    <ul className="mt-2 space-y-1">
                      {v.evidence.map((quote, i) => (
                        <li
                          key={i}
                          className="border-l-2 border-accent pl-2 text-xs italic text-ink-2"
                        >
                          “{quote}”
                        </li>
                      ))}
                    </ul>
                  )}
                </li>
              )
            })}
          </ul>

          <section aria-labelledby="flagged-heading" className="space-y-2">
            <h2 id="flagged-heading" className="text-lg font-semibold text-ink">
              Flagged phrases
            </h2>
            {result.flaggedPhrases.length === 0 ? (
              <p className="rounded-lg border border-dashed border-line-2 p-4 text-sm text-ink-2">
                None. No banned or off-voice phrases detected.
              </p>
            ) : (
              <>
                <ul className="flex flex-wrap gap-2">
                  {result.flaggedPhrases.map((phrase) => (
                    <li
                      key={phrase}
                      className="rounded-full border border-amber-300 bg-amber-100 px-2.5 py-1 text-xs font-medium text-amber-900"
                    >
                      {phrase}
                    </li>
                  ))}
                </ul>
                <HighlightedText text={text} phrases={result.flaggedPhrases} />
              </>
            )}
          </section>

          <section aria-labelledby="rewrite-heading" className="space-y-2">
            <div className="flex items-center justify-between gap-2">
              <h2 id="rewrite-heading" className="text-lg font-semibold text-ink">
                On-brand rewrite
              </h2>
              <button
                type="button"
                onClick={() => void copyRewrite()}
                className="rounded-lg border border-line-2 bg-surface px-3 py-1.5 text-sm font-medium text-ink-2 hover:border-ink hover:text-ink"
              >
                {copied ? 'Copied' : 'Copy'}
              </button>
            </div>
            <div className="rounded-lg border border-line bg-surface p-4">
              <p className="whitespace-pre-wrap text-sm text-ink">{result.rewrite}</p>
            </div>
            {!result.pass && (
              <p className="text-xs text-ink-3">
                Replace your draft with this rewrite, or tighten the flagged phrases above.
              </p>
            )}
          </section>
        </div>
      )}

      <ErrorToast message={error} onDismiss={() => setError(null)} />
    </main>
  )
}
