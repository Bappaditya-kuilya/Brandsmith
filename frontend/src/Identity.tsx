import { useEffect, useState, type FormEvent } from 'react'
import { Link, useParams } from 'react-router-dom'
import { getSession, stageRegeneratePath, stageRunPath } from './api/client'
import type { BrandDna, PersonalityStageData, VoiceSpec } from './api/types'
import { ErrorToast } from './components/ErrorToast'
import { StageProgress } from './components/StageProgress'
import { useSse, type StageCompletedData } from './hooks/useSse'

function errorMessage(err: unknown): string {
  return err instanceof Error ? err.message : 'Something went wrong. Please try again.'
}

function fromBrandDna(dna: unknown): PersonalityStageData | null {
  if (!dna || typeof dna !== 'object') return null
  const b = dna as BrandDna
  const traits = b.personality?.traits
  const voice = b.voice
  if (!traits?.length || !voice) return null
  return { traits, voice, avoidList: b.personality?.avoidList ?? [] }
}

function toPersonality(raw: StageCompletedData): PersonalityStageData | null {
  const top = raw as StageCompletedData & Partial<PersonalityStageData>
  const nested =
    top.data && typeof top.data === 'object'
      ? (top.data as Partial<PersonalityStageData>)
      : null
  const traits = nested?.traits ?? top.traits
  const voice = nested?.voice ?? top.voice
  const avoidList = nested?.avoidList ?? top.avoidList
  if (!traits?.length || !voice) return null
  return { traits, voice, avoidList: avoidList ?? [] }
}

function formalLabel(n: number): string {
  if (n <= 1) return '1 · casual'
  if (n >= 5) return '5 · formal'
  return String(n)
}

function VoicePanel({ voice }: { voice: VoiceSpec }) {
  const [min, max] = voice.sentenceWords
  return (
    <section aria-labelledby="voice-heading" className="space-y-3">
      <h2 id="voice-heading" className="text-lg font-semibold text-ink">
        Voice
      </h2>
      <div className="rounded-lg border border-line bg-surface p-4">
        <div className="space-y-4">
          <div>
            <span className="text-xs font-medium text-ink-3">
              Formality
            </span>
            <label htmlFor="formality" className="sr-only">
              Formality {voice.formality} of 5
            </label>
            <input
              id="formality"
              type="range"
              min={1}
              max={5}
              step={1}
              value={voice.formality}
              readOnly
              disabled
              className="mt-2 w-full cursor-default accent-accent"
            />
            <div className="flex justify-between text-xs text-ink-3" aria-hidden="true">
              <span>1 casual</span>
              <span className="font-semibold text-ink">{formalLabel(voice.formality)}</span>
              <span>5 formal</span>
            </div>
          </div>

          <dl className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            <div className="rounded-md bg-paper-2 p-3">
              <dt className="text-xs font-medium text-ink-3">
                Sentence length
              </dt>
              <dd className="mt-1 text-sm text-ink">
                {min}–{max} words
              </dd>
            </div>
            <div className="rounded-md bg-paper-2 p-3">
              <dt className="text-xs font-medium text-ink-3">Humor</dt>
              <dd className="mt-1 text-sm capitalize text-ink">{voice.humorLevel}</dd>
            </div>
          </dl>

          <div>
            <span className="text-xs font-medium text-ink-3">
              Banned words
            </span>
            <ul className="mt-2 flex flex-wrap gap-1.5">
              {voice.bannedWords.length === 0 && (
                <li className="text-sm text-ink-3">None</li>
              )}
              {voice.bannedWords.map((word) => (
                <li
                  key={word}
                  className="rounded-full border border-red-200 bg-red-50 px-2.5 py-0.5 text-xs text-red-700"
                >
                  {word}
                </li>
              ))}
            </ul>
          </div>

          <div>
            <span className="text-xs font-medium text-ink-3">
              Signature moves
            </span>
            <ol className="mt-2 list-decimal space-y-1 pl-5 text-sm text-ink">
              {voice.signatureMoves.map((move) => (
                <li key={move}>{move}</li>
              ))}
            </ol>
          </div>
        </div>
      </div>
    </section>
  )
}

function PersonalitySection({
  data,
  avoidOpen,
}: {
  data: PersonalityStageData
  avoidOpen: boolean
}) {
  return (
    <section aria-labelledby="personality-heading" className="space-y-3">
      <div className="flex items-baseline justify-between gap-2">
        <h2 id="personality-heading" className="text-lg font-semibold text-ink">
          Personality
        </h2>
        <span className="text-xs text-ink-3">{data.traits.length} traits</span>
      </div>

      <ul className="grid gap-3 sm:grid-cols-2">
        {data.traits.map((trait) => (
          <li
            key={trait.name}
            className="flex flex-col gap-2 rounded-lg border border-line bg-surface p-4"
          >
            <h3 className="text-sm font-semibold text-ink">{trait.name}</h3>
            <div className="space-y-1.5 text-sm">
              <div>
                <span className="block text-xs font-medium text-ink-3">
                  Why it fits
                </span>
                <p className="mt-0.5 border-l-2 border-accent pl-2 italic text-ink-2">
                  {trait.whyFits}
                </p>
              </div>
              <div>
                <span className="block text-xs font-medium text-ink-3">
                  Behavior
                </span>
                <p className="mt-0.5 text-ink">{trait.behavior}</p>
              </div>
              <div>
                <span className="block text-xs font-medium text-ink-3">
                  Never become
                </span>
                <p className="mt-0.5 text-ink">{trait.neverBecome}</p>
              </div>
            </div>
          </li>
        ))}
      </ul>

      <details open={avoidOpen} className="rounded-lg border border-line bg-surface p-4">
        <summary className="cursor-pointer text-sm font-semibold text-ink">
          Avoid list
        </summary>
        <ul className="mt-3 flex flex-wrap gap-1.5">
          {data.avoidList.length === 0 && (
            <li className="text-sm text-ink-3">Nothing on the avoid list yet</li>
          )}
          {data.avoidList.map((item) => (
            <li
              key={item}
              className="rounded-full border border-line-2 bg-paper-2 px-2.5 py-0.5 text-xs text-ink-2"
            >
              {item}
            </li>
          ))}
        </ul>
      </details>
    </section>
  )
}

function Identity() {
  const { id = '' } = useParams()
  const [phase, setPhase] = useState<'loading' | 'ready' | 'error'>('loading')
  const [personality, setPersonality] = useState<PersonalityStageData | null>(null)
  const [progress, setProgress] = useState('Working…')
  const [error, setError] = useState<string | null>(null)
  const [note, setNote] = useState('')
  const [reloadKey, setReloadKey] = useState(0)

  const sse = useSse({
    onProgress: (d) => setProgress(d.message || 'Working…'),
    onStageCompleted: (d) => {
      const next = toPersonality(d)
      if (next) {
        setPersonality(next)
        setError(null)
      } else {
        setError('Personality finished but returned no data. Try running again.')
      }
    },
    onError: (d) => setError(d.message || 'The personality stage failed.'),
  })

  useEffect(() => {
    if (!id) return
    let cancelled = false
    getSession(id)
      .then((session) => {
        if (cancelled) return
        setPersonality(fromBrandDna(session.brandDna))
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

  function runStage(body?: { note?: string }) {
    setError(null)
    setProgress(body?.note ? `Regenerating (${body.note})…` : 'Building personality…')
    const path = body
      ? stageRegeneratePath(id, 'personality')
      : stageRunPath(id, 'personality')
    void sse.run(path, body)
  }

  function handleRegenerate(e: FormEvent) {
    e.preventDefault()
    if (sse.streaming) return
    const trimmed = note.trim()
    runStage(trimmed ? { note: trimmed } : {})
    setNote('')
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

  const done = Boolean(personality && personality.traits.length > 0)

  return (
    <main className="mx-auto flex min-h-svh max-w-3xl flex-col gap-6 p-8 pb-24">
      <div className="space-y-1">
        <div className="flex items-center justify-between text-xs font-medium text-ink-3">
          <Link to="/" className="text-ink-2 hover:underline">
            Brandsmith
          </Link>
          <span className="font-mono">S3 · personality</span>
        </div>
        <h1 className="text-3xl font-bold tracking-tight text-ink">Personality & voice</h1>
        <p className="text-sm text-ink-2">
          Traits and a mechanical voice spec later stages can check against.
        </p>
      </div>

      {phase === 'loading' && !sse.streaming && (
        <div className="animate-pulse space-y-3" aria-busy="true" aria-label="Loading personality">
          {[0, 1, 2].map((i) => (
            <div key={i} className="h-28 rounded-lg bg-line" />
          ))}
        </div>
      )}

      {phase === 'error' && error && !sse.streaming && (
        <div className="space-y-3">
          <p role="alert" className="rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
            {error}
          </p>
          <button
            type="button"
            onClick={() => {
              setPhase('loading')
              setError(null)
              setReloadKey((k) => k + 1)
            }}
            className="rounded-lg border border-line-2 bg-surface px-4 py-2 text-sm font-medium text-ink-2 hover:border-ink hover:text-ink"
          >
            Try again
          </button>
        </div>
      )}

      {sse.streaming && <StageProgress message={progress} />}

      {phase === 'ready' && !sse.streaming && !done && (
        <div className="rounded-lg border border-dashed border-line-2 p-6 text-center">
          <p className="text-sm text-ink-2">
            No personality yet. Run the stage to generate traits and a voice spec.
          </p>
          <button
            type="button"
            onClick={() => runStage()}
            className="mt-4 rounded-lg bg-accent px-5 py-2.5 text-sm font-semibold text-ink hover:bg-accent-strong"
          >
            Run personality
          </button>
        </div>
      )}

      {phase === 'ready' && !sse.streaming && done && personality && (
        <>
          <PersonalitySection data={personality} avoidOpen />
          <VoicePanel voice={personality.voice} />

          <form onSubmit={handleRegenerate} className="space-y-2" noValidate>
            <label htmlFor="regen-note" className="block text-sm font-medium text-ink">
              Regenerate
            </label>
            <div className="flex flex-wrap gap-2">
              <input
                id="regen-note"
                value={note}
                onChange={(e) => setNote(e.target.value)}
                placeholder="Optional note — e.g. less playful, more technical"
                maxLength={300}
                className="min-w-0 flex-1 rounded-lg border border-line-2 px-3 py-2 text-sm text-ink placeholder:text-ink-3 focus:border-ink focus:outline-none focus:ring-2 focus:ring-accent/50"
              />
              <button
                type="submit"
                className="rounded-lg border border-line-2 bg-surface px-4 py-2 text-sm font-medium text-ink-2 hover:border-ink hover:text-ink"
              >
                Regenerate
              </button>
            </div>
          </form>

          <div className="flex justify-end border-t border-line pt-4">
            <Link
              to={`/s/${id}/naming`}
              className="rounded-lg bg-accent px-5 py-2.5 text-sm font-semibold text-ink hover:bg-accent-strong"
            >
              Continue to naming →
            </Link>
          </div>
        </>
      )}

      <ErrorToast
        message={sse.streaming ? null : error}
        onRetry={() => runStage(done && note.trim() ? { note: note.trim() } : undefined)}
        onDismiss={() => setError(null)}
      />
    </main>
  )
}

export { Identity }
