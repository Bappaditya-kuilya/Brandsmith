import { useEffect, useMemo, useRef, useState, type FormEvent } from 'react'
import { Link, useParams } from 'react-router-dom'
import {
  getSession,
  selectName,
  selectTagline,
  stageRegeneratePath,
  stageRunPath,
} from './api/client'
import type {
  BrandDna,
  BrandName,
  MessagesStageData,
  NamingStageData,
  NamingTerritory,
  TaglineOption,
} from './api/types'
import { ErrorToast } from './components/ErrorToast'
import { StageProgress } from './components/StageProgress'
import { useSse, type StageCompletedData } from './hooks/useSse'

function errorMessage(err: unknown): string {
  return err instanceof Error ? err.message : 'Something went wrong. Please try again.'
}

function toNaming(raw: StageCompletedData): NamingStageData | null {
  const top = raw as StageCompletedData & Partial<NamingStageData>
  const nested =
    top.data && typeof top.data === 'object'
      ? (top.data as Partial<NamingStageData>)
      : null
  const territories = nested?.territories ?? top.territories
  const names = nested?.names ?? top.names
  if (!names?.length) return null
  return {
    territories: territories ?? [],
    names,
    domainDisclaimer: nested?.domainDisclaimer ?? top.domainDisclaimer,
  }
}

function toMessages(raw: StageCompletedData): MessagesStageData | null {
  const top = raw as StageCompletedData & Partial<MessagesStageData>
  const nested =
    top.data && typeof top.data === 'object'
      ? (top.data as Partial<MessagesStageData>)
      : null
  const taglines = nested?.taglines ?? top.taglines
  if (!taglines?.length) return null
  const hierarchy = nested?.hierarchy ?? top.hierarchy
  return {
    taglines,
    pitch: nested?.pitch ?? top.pitch ?? '',
    hierarchy: hierarchy ?? { primary: '', secondary: [], proof: [] },
    selected: nested?.selected ?? top.selected ?? null,
  }
}

function fromBrandDna(dna: unknown): {
  naming: NamingStageData | null
  selected: string | null
  messages: MessagesStageData | null
} {
  if (!dna || typeof dna !== 'object') return { naming: null, selected: null, messages: null }
  const b = dna as BrandDna
  const naming =
    b.naming?.names?.length
      ? { territories: b.naming.territories ?? [], names: b.naming.names, domainDisclaimer: b.naming.domainDisclaimer }
      : null
  const messages = b.messages?.taglines?.length ? b.messages : null
  return { naming, selected: b.identity?.name ?? null, messages }
}

function deltaFor(name: BrandName): { from: number; to: number } | null {
  const attempts = name.attempts
  if (!attempts || attempts.length < 2) return null
  const first = attempts[0]
  const last = attempts[attempts.length - 1]
  if (!first || !last) return null
  if (first.antiGenericScore === last.antiGenericScore) return null
  return { from: first.antiGenericScore, to: last.antiGenericScore }
}

function taglineDelta(tagline: TaglineOption): { from: number; to: number } | null {
  const attempts = tagline.attempts
  if (!attempts || attempts.length < 2) return null
  const first = attempts[0]
  const last = attempts[attempts.length - 1]
  if (!first || !last || first.score === last.score) return null
  return { from: first.score, to: last.score }
}

function groupByTerritory(data: NamingStageData): { territory: NamingTerritory | null; names: BrandName[] }[] {
  const territories = data.territories
  if (territories.length > 0) {
    return territories.map((territory) => ({
      territory,
      names: data.names.filter((n) => n.territory === territory.name),
    }))
  }
  const seen = new Map<string, BrandName[]>()
  for (const n of data.names) {
    const list = seen.get(n.territory) ?? []
    list.push(n)
    seen.set(n.territory, list)
  }
  return [...seen.entries()].map(([name, names]) => ({
    territory: { name, rationale: '', whyFitsPersonality: '' },
    names,
  }))
}

function NameCard({
  name,
  selected,
  disabled,
  onPick,
}: {
  name: BrandName
  selected: boolean
  disabled: boolean
  onPick: () => void
}) {
  const delta = deltaFor(name)
  return (
    <li
      className={`flex flex-col rounded-lg border p-4 ${
        selected ? 'border-accent bg-accent/10 ring-1 ring-accent' : 'border-line bg-surface'
      }`}
    >
      <div className="flex items-start justify-between gap-2">
        <h4 className="text-base font-semibold text-ink">{name.name}</h4>
        {selected && (
          <span className="shrink-0 rounded-full bg-accent/25 px-2 py-0.5 text-xs font-semibold text-ink">
            Selected
          </span>
        )}
      </div>

      <div className="mt-3 flex items-end justify-between gap-3 border-t border-line pt-3">
        <div>
          <span
            className="block font-mono text-[10px] font-medium uppercase tracking-widest text-ink-3"
            title="Anti-generic score (higher is less generic)"
          >
            Anti-generic
          </span>
          <span
            className={`font-mono text-3xl font-bold leading-none tabular-nums ${
              name.antiGenericScore < 50 ? 'text-red-600' : 'text-ink'
            }`}
          >
            {Math.round(name.antiGenericScore)}
          </span>
        </div>
        <div className="flex flex-wrap justify-end gap-1.5 pb-0.5">
          <span className="rounded-full bg-line px-2 py-0.5 font-mono text-xs tabular-nums text-ink-2">
            say {name.pronounceability}
          </span>
          <span className="rounded-full bg-line px-2 py-0.5 font-mono text-xs tabular-nums text-ink-2">
            {name.length} ch
          </span>
        </div>
      </div>

      {delta && (
        <p className="mt-2 font-mono text-xs tabular-nums text-emerald-700">
          {Math.round(delta.from)} → {Math.round(delta.to)} (
          {delta.to > delta.from ? '+' : ''}
          {Math.round(delta.to - delta.from)})
        </p>
      )}

      <p className="mt-3 flex-1 text-sm text-ink-2">{name.rationale}</p>

      <button
        type="button"
        onClick={onPick}
        disabled={disabled}
        className={`mt-4 rounded-lg px-3 py-2 text-sm font-semibold disabled:cursor-not-allowed disabled:opacity-50 ${
          selected
            ? 'border border-accent bg-surface text-ink'
            : 'bg-accent text-ink hover:bg-accent-strong'
        }`}
      >
        {selected ? 'Selected' : 'Pick this name'}
      </button>
    </li>
  )
}

function TaglineCard({
  tagline,
  index,
  selected,
  disabled,
  onPick,
}: {
  tagline: TaglineOption
  index: number
  selected: boolean
  disabled: boolean
  onPick: () => void
}) {
  const delta = taglineDelta(tagline)
  return (
    <li
      className={`flex flex-col rounded-lg border p-4 ${
        selected ? 'border-accent bg-accent/10 ring-1 ring-accent' : 'border-line bg-surface'
      }`}
    >
      <div className="flex items-start justify-between gap-2">
        <p className="text-base font-semibold text-ink">“{tagline.text}”</p>
        {selected && (
          <span className="shrink-0 rounded-full bg-accent/25 px-2 py-0.5 text-xs font-semibold text-ink">
            Selected
          </span>
        )}
      </div>

      <div className="mt-3 flex items-end justify-between gap-3 border-t border-line pt-3">
        <div>
          <span
            className="block font-mono text-[10px] font-medium uppercase tracking-widest text-ink-3"
            title="Anti-generic score (higher is less generic)"
          >
            Anti-generic
          </span>
          <span
            className={`font-mono text-3xl font-bold leading-none tabular-nums ${
              tagline.score < 50 ? 'text-red-600' : 'text-ink'
            }`}
          >
            {Math.round(tagline.score)}
          </span>
        </div>
        <span className="rounded-full bg-line px-2 py-0.5 pb-0.5 font-mono text-xs tabular-nums text-ink-2">
          {tagline.attempts.length} attempt{tagline.attempts.length === 1 ? '' : 's'}
        </span>
      </div>

      {delta && (
        <p className="mt-2 font-mono text-xs tabular-nums text-emerald-700">
          {Math.round(delta.from)} → {Math.round(delta.to)} (
          {delta.to > delta.from ? '+' : ''}
          {Math.round(delta.to - delta.from)})
        </p>
      )}

      <button
        type="button"
        onClick={onPick}
        disabled={disabled}
        className={`mt-4 rounded-lg px-3 py-2 text-sm font-semibold disabled:cursor-not-allowed disabled:opacity-50 ${
          selected
            ? 'border border-accent bg-surface text-ink'
            : 'bg-accent text-ink hover:bg-accent-strong'
        }`}
      >
        {selected ? 'Selected' : 'Pick this tagline'}
        <span className="sr-only">, option {index + 1}</span>
      </button>
    </li>
  )
}

function HierarchyPanel({ messages }: { messages: MessagesStageData }) {
  const { hierarchy, pitch } = messages
  return (
    <div className="space-y-3 rounded-lg border border-line bg-surface p-4">
      {pitch && (
        <div>
          <p className="text-xs font-medium uppercase tracking-wide text-ink-3">One-line pitch</p>
          <p className="mt-1 text-sm text-ink">{pitch}</p>
        </div>
      )}
      <div>
        <p className="text-xs font-medium uppercase tracking-wide text-ink-3">Message hierarchy</p>
        <div className="mt-2 space-y-2 text-sm">
          <div className="border-l-4 border-accent pl-3">
            <span className="block text-xs font-semibold uppercase tracking-wide text-ink">
              Primary
            </span>
            <p className="mt-0.5 text-ink">{hierarchy.primary}</p>
          </div>
          {hierarchy.secondary.length > 0 && (
            <div className="border-l-4 border-line pl-3">
              <span className="block text-xs font-semibold uppercase tracking-wide text-ink-3">
                Secondary
              </span>
              <ul className="mt-0.5 list-disc space-y-0.5 pl-4 text-ink">
                {hierarchy.secondary.map((item) => (
                  <li key={item}>{item}</li>
                ))}
              </ul>
            </div>
          )}
          {hierarchy.proof.length > 0 && (
            <div className="border-l-4 border-line pl-3">
              <span className="block text-xs font-semibold uppercase tracking-wide text-ink-3">
                Proof
              </span>
              <ul className="mt-0.5 list-disc space-y-0.5 pl-4 text-ink">
                {hierarchy.proof.map((item) => (
                  <li key={item}>{item}</li>
                ))}
              </ul>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}

export interface NamingStageProps {
  onStatus?: (status: 'idle' | 'running' | 'done') => void
}

/** S4 naming + S5 tagline/messages: run/regenerate SSE, pick name → tagline → /visual. */
export function NamingStage({ onStatus }: NamingStageProps) {
  const { id = '' } = useParams()
  const [naming, setNaming] = useState<NamingStageData | null>(null)
  const [selected, setSelected] = useState<string | null>(null)
  const [messages, setMessages] = useState<MessagesStageData | null>(null)
  const [selectedTagline, setSelectedTagline] = useState<number | null>(null)
  const [phase, setPhase] = useState<'loading' | 'ready' | 'error'>('loading')
  const [progress, setProgress] = useState('Building name territories…')
  const [msgProgress, setMsgProgress] = useState('Writing taglines…')
  const [error, setError] = useState<string | null>(null)
  const [note, setNote] = useState('')
  const [msgNote, setMsgNote] = useState('')
  const [selecting, setSelecting] = useState(false)
  const [reloadKey, setReloadKey] = useState(0)

  const statusRef = useRef(onStatus)

  const sse = useSse({
    onProgress: (d) => setProgress(d.message || 'Working…'),
    onStageCompleted: (d) => {
      const next = toNaming(d)
      if (next) {
        setNaming(next)
        setSelected(null)
        setMessages(null)
        setSelectedTagline(null)
        setError(null)
        statusRef.current?.('done')
      } else {
        setError('Naming finished but returned no names. Try running again.')
      }
    },
    onError: (d) => {
      setError(d.message || 'The naming stage failed.')
      statusRef.current?.('idle')
    },
  })

  const msgSse = useSse({
    onProgress: (d) => setMsgProgress(d.message || 'Working…'),
    onStageCompleted: (d) => {
      const next = toMessages(d)
      if (next) {
        setMessages(next)
        setSelectedTagline(null)
        setError(null)
      } else {
        setError('Taglines finished but returned no options. Try running again.')
      }
    },
    onError: (d) => setError(d.message || 'The tagline stage failed.'),
  })

  useEffect(() => {
    statusRef.current = onStatus
  }, [onStatus])

  useEffect(() => {
    if (!id) return
    let cancelled = false
    getSession(id)
      .then((session) => {
        if (cancelled) return
        const { naming: stored, selected: picked, messages: storedMessages } =
          fromBrandDna(session.brandDna)
        setNaming(stored)
        setSelected(picked)
        setMessages(storedMessages)
        setSelectedTagline(storedMessages?.selected ?? null)
        setPhase('ready')
        if (stored) statusRef.current?.('done')
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
    setProgress(body?.note ? `Regenerating (${body.note})…` : 'Building name territories…')
    statusRef.current?.('running')
    const path = body
      ? stageRegeneratePath(id, 'naming')
      : stageRunPath(id, 'naming')
    void sse.run(path, body)
  }

  function runMessages(body?: { note?: string }) {
    setError(null)
    setMsgProgress(body?.note ? `Regenerating taglines (${body.note})…` : 'Writing taglines…')
    const path = body
      ? stageRegeneratePath(id, 'messages')
      : stageRunPath(id, 'messages')
    void msgSse.run(path, body)
  }

  function handleRegenerate(e: FormEvent) {
    e.preventDefault()
    if (sse.streaming) return
    const trimmed = note.trim()
    runStage(trimmed ? { note: trimmed } : {})
    setNote('')
  }

  function handleMsgRegenerate(e: FormEvent) {
    e.preventDefault()
    if (msgSse.streaming) return
    const trimmed = msgNote.trim()
    runMessages(trimmed ? { note: trimmed } : {})
    setMsgNote('')
  }

  async function pick(name: BrandName) {
    if (selecting) return
    setSelecting(true)
    setError(null)
    try {
      await selectName(id, { name: name.name })
      if (selected !== name.name) {
        setMessages(null)
        setSelectedTagline(null)
      }
      setSelected(name.name)
      statusRef.current?.('done')
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setSelecting(false)
    }
  }

  async function pickTagline(index: number) {
    if (selecting) return
    setSelecting(true)
    setError(null)
    try {
      const res = await selectTagline(id, { index })
      setSelectedTagline(res.selected)
      if (res.messages) setMessages(res.messages)
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setSelecting(false)
    }
  }

  const groups = useMemo(
    () => (naming ? groupByTerritory(naming) : []),
    [naming],
  )

  if (!id) {
    return (
      <p role="alert" className="rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
        Missing session id.
      </p>
    )
  }

  const streamingAny = sse.streaming || msgSse.streaming

  return (
    <section aria-labelledby="naming-heading" className="space-y-4" data-stub="naming-stage">
      <div className="space-y-1">
        <div className="flex items-center justify-between text-xs font-medium uppercase tracking-wide text-ink-3">
          <Link to={`/s/${id}/identity`} className="text-ink-2 underline hover:text-ink">
            ← Personality
          </Link>
          <span className="font-mono">S4 · naming</span>
        </div>
        <h2 id="naming-heading" className="text-3xl font-bold tracking-tight text-ink">
          Naming
        </h2>
        <p className="text-sm text-ink-2">
          Three territories, three names each. Anti-generic scores come from lexicon, embedding and
          critic passes.
        </p>
      </div>

      {phase === 'loading' && !streamingAny && (
        <div className="animate-pulse space-y-3" aria-busy="true" aria-label="Loading naming">
          {[0, 1, 2].map((i) => (
            <div key={i} className="h-24 rounded-lg bg-line" />
          ))}
        </div>
      )}

      {phase === 'error' && error && !streamingAny && (
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

      {sse.streaming && (
        <div className="rounded-lg border border-line bg-surface p-6">
          <StageProgress message={progress} bars={3} />
        </div>
      )}

      {phase === 'ready' && !sse.streaming && !naming && (
        <div className="rounded-lg border border-dashed border-line-2 p-6 text-center">
          <p className="text-sm text-ink-2">
            No names yet. Run the stage to generate territories and scored name options.
          </p>
          <button
            type="button"
            onClick={() => runStage()}
            disabled={Boolean(error) && !naming}
            className="mt-4 rounded-lg bg-accent px-5 py-2.5 text-sm font-semibold text-ink hover:bg-accent-strong disabled:cursor-not-allowed disabled:opacity-50"
          >
            Run naming
          </button>
        </div>
      )}

      {phase === 'ready' && !sse.streaming && naming && (
        <>
          <div className="space-y-6">
            {groups.map((group, gi) => (
              <div key={group.territory?.name ?? gi} className="space-y-3">
                <div className="border-l-4 border-accent pl-3">
                  <h3 className="text-sm font-bold uppercase tracking-wide text-ink">
                    {group.territory?.name ?? `Territory ${gi + 1}`}
                  </h3>
                  {group.territory?.rationale && (
                    <p className="mt-0.5 text-sm text-ink-2">{group.territory.rationale}</p>
                  )}
                  {group.territory?.whyFitsPersonality && (
                    <p className="mt-0.5 text-xs italic text-ink-3">
                      Fits personality: {group.territory.whyFitsPersonality}
                    </p>
                  )}
                </div>
                <ul className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
                  {group.names.map((n) => (
                    <NameCard
                      key={n.name}
                      name={n}
                      selected={selected === n.name}
                      disabled={selecting || msgSse.streaming}
                      onPick={() => void pick(n)}
                    />
                  ))}
                </ul>
              </div>
            ))}
          </div>

          {naming.domainDisclaimer && (
            <p className="text-xs text-ink-3">{naming.domainDisclaimer}</p>
          )}

          <form onSubmit={handleRegenerate} className="space-y-2" noValidate>
            <label htmlFor="naming-regen-note" className="block text-sm font-medium text-ink">
              Regenerate names
            </label>
            <div className="flex flex-wrap gap-2">
              <input
                id="naming-regen-note"
                value={note}
                onChange={(e) => setNote(e.target.value)}
                placeholder="Optional note — e.g. fewer compound words, more invented names"
                maxLength={300}
                disabled={selecting || msgSse.streaming}
                className="min-w-0 flex-1 rounded-lg border border-line-2 px-3 py-2 text-sm text-ink placeholder:text-ink-3 focus:border-ink focus:outline-none focus:ring-2 focus:ring-accent/50 disabled:opacity-60"
              />
              <button
                type="submit"
                disabled={selecting || msgSse.streaming}
                className="rounded-lg border border-line-2 bg-surface px-4 py-2 text-sm font-medium text-ink-2 hover:border-ink hover:text-ink disabled:opacity-50"
              >
                Regenerate
              </button>
            </div>
          </form>

          {selected && (
            <section
              aria-labelledby="tagline-heading"
              data-stub="tagline"
              className="space-y-4 rounded-lg border border-line bg-paper-2/60 p-4"
            >
              <div className="space-y-1">
                <p className="text-xs font-medium uppercase tracking-wide text-ink-3">
                  Chosen name
                </p>
                <p className="text-lg font-semibold text-ink">{selected}</p>
                <h3 id="tagline-heading" className="text-lg font-semibold text-ink">
                  Taglines
                </h3>
                <p className="text-sm text-ink-2">
                  Three or more options scored through the anti-generic loop. Pick one for the pitch
                  and message hierarchy.
                </p>
              </div>

              {msgSse.streaming && (
                <div className="rounded-lg border border-line bg-surface p-6">
                  <StageProgress message={msgProgress} bars={3} />
                </div>
              )}

              {!msgSse.streaming && !messages && (
                <div className="rounded-lg border border-dashed border-line-2 bg-surface p-6 text-center">
                  <p className="text-sm text-ink-2">
                    No taglines yet. Run the stage to write scored options, a one-line pitch and the
                    message hierarchy.
                  </p>
                  <button
                    type="button"
                    onClick={() => runMessages()}
                    disabled={selecting}
                    className="mt-4 rounded-lg bg-accent px-5 py-2.5 text-sm font-semibold text-ink hover:bg-accent-strong disabled:cursor-not-allowed disabled:opacity-50"
                  >
                    Run taglines
                  </button>
                </div>
              )}

              {!msgSse.streaming && messages && (
                <>
                  <ul className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
                    {messages.taglines.map((t, i) => (
                      <TaglineCard
                        key={`${t.text}-${i}`}
                        tagline={t}
                        index={i}
                        selected={selectedTagline === i}
                        disabled={selecting}
                        onPick={() => void pickTagline(i)}
                      />
                    ))}
                  </ul>

                  <HierarchyPanel messages={messages} />

                  <form onSubmit={handleMsgRegenerate} className="space-y-2" noValidate>
                    <label htmlFor="tagline-regen-note" className="block text-sm font-medium text-ink">
                      Regenerate taglines
                    </label>
                    <div className="flex flex-wrap gap-2">
                      <input
                        id="tagline-regen-note"
                        value={msgNote}
                        onChange={(e) => setMsgNote(e.target.value)}
                        placeholder="Optional note — e.g. shorter, more concrete, less hype"
                        maxLength={300}
                        disabled={selecting}
                        className="min-w-0 flex-1 rounded-lg border border-line-2 bg-surface px-3 py-2 text-sm text-ink placeholder:text-ink-3 focus:border-ink focus:outline-none focus:ring-2 focus:ring-accent/50 disabled:opacity-60"
                      />
                      <button
                        type="submit"
                        disabled={selecting}
                        className="rounded-lg border border-line-2 bg-surface px-4 py-2 text-sm font-medium text-ink-2 hover:border-ink hover:text-ink disabled:opacity-50"
                      >
                        Regenerate
                      </button>
                    </div>
                  </form>

                  {selectedTagline != null && (
                    <div className="flex justify-end border-t border-line pt-4">
                      <Link
                        to={`/s/${id}/visual`}
                        className="rounded-lg bg-accent px-5 py-2.5 text-sm font-semibold text-ink hover:bg-accent-strong"
                      >
                        Continue to visual →
                      </Link>
                    </div>
                  )}
                </>
              )}
            </section>
          )}
        </>
      )}

      <ErrorToast
        message={streamingAny ? null : error}
        onRetry={
          phase === 'ready' && naming
            ? selectedTagline != null || messages
              ? () =>
                  runMessages(msgNote.trim() ? { note: msgNote.trim() } : {})
              : () => runStage(note.trim() ? { note: note.trim() } : {})
            : phase === 'error'
              ? () => {
                  setPhase('loading')
                  setError(null)
                  setReloadKey((k) => k + 1)
                }
              : undefined
        }
        onDismiss={() => setError(null)}
      />
    </section>
  )
}

export interface NamingPageProps {
  onStatus?: (status: 'idle' | 'running' | 'done') => void
}

export function NamingPage({ onStatus }: NamingPageProps) {
  return (
    <main className="mx-auto flex min-h-svh max-w-4xl flex-col gap-6 p-8">
      <NamingStage onStatus={onStatus} />
    </main>
  )
}
