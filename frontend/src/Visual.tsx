import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { getSession, patchVisualTokens, stageRunPath } from './api/client'
import type { BrandDna, Palette, PatchVisualTokensRequest, VisualBoard } from './api/types'
import { ErrorToast } from './components/ErrorToast'
import { StageProgress } from './components/StageProgress'
import { useSse, type StageCompletedData } from './hooks/useSse'
import { contrastRatio } from './lib/wcag'
import type { StageStatus } from './lib/stages'

function errorMessage(err: unknown): string {
  return err instanceof Error ? err.message : 'Something went wrong. Please try again.'
}

function toBoard(raw: StageCompletedData): VisualBoard | null {
  const top = raw as StageCompletedData & Partial<VisualBoard>
  const nested =
    top.data && typeof top.data === 'object' ? (top.data as Partial<VisualBoard>) : null
  const board = nested ?? top
  if (!board.palette || !board.logoSvg || !board.direction) return null
  return board as VisualBoard
}

function fromBrandDna(dna: unknown): VisualBoard | null {
  if (!dna || typeof dna !== 'object') return null
  const visual = (dna as BrandDna).visual
  if (!visual?.palette || !visual.logoSvg || !visual.direction) return null
  return visual
}

const PALETTE_KEYS: { key: keyof Palette; label: string }[] = [
  { key: 'bg', label: 'Background' },
  { key: 'surface', label: 'Surface' },
  { key: 'accent', label: 'Accent' },
  { key: 'fg', label: 'Foreground' },
  { key: 'muted', label: 'Muted' },
]

function ratioTone(ratio: number): string {
  if (ratio >= 7) return 'text-emerald-700'
  if (ratio >= 4.5) return 'text-amber-700'
  return 'text-red-600'
}

function PaletteSwatches({ palette }: { palette: Palette }) {
  return (
    <ul className="grid grid-cols-2 gap-2 sm:grid-cols-5">
      {PALETTE_KEYS.map(({ key, label }) => (
        <li key={key} className="overflow-hidden rounded-lg border border-line bg-surface">
          <div className="h-12" style={{ backgroundColor: palette[key] }} aria-hidden="true" />
          <div className="space-y-0.5 p-2">
            <p className="text-xs font-semibold text-ink">{label}</p>
            <p className="font-mono text-[11px] text-ink-3">{palette[key]}</p>
            <p className={`text-[11px] font-medium tabular-nums ${ratioTone(contrastRatio(palette[key], palette.bg))}`}>
              {contrastRatio(palette[key], palette.bg).toFixed(1)}:1 vs bg
            </p>
          </div>
        </li>
      ))}
    </ul>
  )
}

function BrandBoard({ board }: { board: VisualBoard }) {
  const { palette, logoSvg, fonts, direction } = board
  return (
    <div
      className="overflow-hidden rounded-xl border border-line shadow-sm"
      style={
        {
          backgroundColor: palette.bg,
          color: palette.fg,
          '--board-accent': palette.accent,
          '--board-muted': palette.muted,
          '--board-surface': palette.surface,
        } as React.CSSProperties
      }
      data-stub="brand-board"
    >
      <div className="flex items-center justify-between px-6 pt-6">
        <span className="text-xs font-medium uppercase tracking-wide" style={{ color: palette.muted }}>
          Brand board
        </span>
        <span className="text-xs" style={{ color: palette.muted }}>
          {direction.shapeLanguage} · {fonts.join(' + ')}
        </span>
      </div>
      <div className="flex flex-col items-center gap-5 px-6 py-10">
        <img
          src={`data:image/svg+xml;charset=utf-8,${encodeURIComponent(logoSvg)}`}
          alt="Generated logo"
          className="max-h-28 w-auto"
        />
        <div className="text-center">
          <p className="text-2xl font-bold tracking-tight" style={{ color: palette.accent }}>
            Your brand
          </p>
          <p className="mt-1 text-sm" style={{ color: palette.muted }}>
            {direction.moodWords.join(' · ')}
          </p>
        </div>
        <div className="flex w-full max-w-md flex-col gap-2 sm:flex-row">
          <div
            className="flex-1 rounded-lg px-4 py-3 text-center text-sm font-semibold"
            style={{ backgroundColor: palette.surface, color: palette.fg }}
          >
            Surface card
          </div>
          <button
            type="button"
            className="flex-1 rounded-lg px-4 py-3 text-sm font-semibold"
            style={{ backgroundColor: palette.accent, color: palette.bg }}
          >
            Primary action
          </button>
        </div>
        <p className="max-w-md text-center text-sm" style={{ color: palette.muted }}>
          {direction.imagery}
        </p>
      </div>
      <div
        className="flex flex-wrap items-center justify-between gap-2 border-t px-6 py-3 text-xs"
        style={{ borderColor: palette.surface, color: palette.muted }}
      >
        <span>Shape: {board.shape}</span>
        <span>
          fg/bg {contrastRatio(palette.fg, palette.bg).toFixed(1)}:1 · accent/bg{' '}
          {contrastRatio(palette.accent, palette.bg).toFixed(1)}:1
        </span>
      </div>
    </div>
  )
}

function TokenEditor({
  board,
  onPatch,
  disabled,
}: {
  board: VisualBoard
  onPatch: (body: PatchVisualTokensRequest) => void
  disabled: boolean
}) {
  const [hue, setHue] = useState(board.direction.seedHue)
  const [prevHue, setPrevHue] = useState(board.direction.seedHue)
  if (prevHue !== board.direction.seedHue) {
    setPrevHue(board.direction.seedHue)
    setHue(board.direction.seedHue)
  }

  return (
    <div className="space-y-4 rounded-lg border border-line bg-surface p-4">
      <h3 className="text-sm font-semibold text-ink">Tokens</h3>

      <div>
        <label htmlFor="token-hue" className="flex items-center justify-between text-xs font-medium text-ink-2">
          <span>Seed hue</span>
          <span className="tabular-nums text-ink">{hue}°</span>
        </label>
        <input
          id="token-hue"
          type="range"
          min={0}
          max={360}
          value={hue}
          disabled={disabled}
          onChange={(e) => {
            const next = Number(e.target.value)
            setHue(next)
            onPatch({ seedHue: next })
          }}
          className="mt-1 w-full accent-accent disabled:opacity-50"
        />
      </div>

      <div>
        <label htmlFor="token-accent" className="text-xs font-medium text-ink-2">
          Accent
        </label>
        <div className="mt-1 flex items-center gap-2">
          <input
            id="token-accent"
            type="color"
            value={board.palette.accent}
            disabled={disabled}
            onChange={(e) => onPatch({ accent: e.target.value })}
            className="h-9 w-14 cursor-pointer rounded border border-line-2 bg-surface disabled:cursor-not-allowed disabled:opacity-50"
          />
          <span className="font-mono text-xs text-ink-3">{board.palette.accent}</span>
        </div>
      </div>

      <div>
        <label htmlFor="token-saturation" className="text-xs font-medium text-ink-2">
          Saturation
        </label>
        <select
          id="token-saturation"
          value={board.direction.saturation}
          disabled={disabled}
          onChange={(e) =>
            onPatch({ saturation: e.target.value as PatchVisualTokensRequest['saturation'] })
          }
          className="mt-1 w-full rounded-md border border-line-2 bg-surface px-2 py-1.5 text-sm text-ink focus:border-ink focus:outline-none focus:ring-2 focus:ring-accent/50 disabled:opacity-50"
        >
          <option value="low">Low</option>
          <option value="medium">Medium</option>
          <option value="high">High</option>
        </select>
      </div>
    </div>
  )
}

export interface VisualPageProps {
  onStatus?: (status: StageStatus) => void
}

/** S6 visual stage: direction + live brand board with token patching. */
export function VisualPage({ onStatus }: VisualPageProps) {
  const { id = '' } = useParams()
  const [board, setBoard] = useState<VisualBoard | null>(null)
  const [phase, setPhase] = useState<'loading' | 'ready' | 'error'>('loading')
  const [progress, setProgress] = useState('Building palette and logo…')
  const [error, setError] = useState<string | null>(null)
  const [patching, setPatching] = useState(false)
  const [reloadKey, setReloadKey] = useState(0)
  const patchTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const pendingPatch = useRef<PatchVisualTokensRequest>({})
  const patchSeq = useRef(0)

  const statusRef = useRef(onStatus)
  useEffect(() => {
    statusRef.current = onStatus
  }, [onStatus])

  const sse = useSse({
    onProgress: (d) => setProgress(d.message || 'Working…'),
    onStageCompleted: (d) => {
      const next = toBoard(d)
      if (next) {
        setBoard(next)
        setError(null)
        statusRef.current?.('done')
      } else {
        setError('Visual finished but returned no board. Try running again.')
      }
    },
    onError: (d) => {
      setError(d.message || 'The visual stage failed.')
      statusRef.current?.('idle')
    },
  })

  useEffect(() => {
    if (!id) return
    let cancelled = false
    getSession(id)
      .then((session) => {
        if (cancelled) return
        const stored = fromBrandDna(session.brandDna)
        setBoard(stored)
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

  useEffect(() => {
    return () => {
      if (patchTimer.current) clearTimeout(patchTimer.current)
    }
  }, [])

  const flushPatch = useCallback(async () => {
    const body = pendingPatch.current
    pendingPatch.current = {}
    if (Object.keys(body).length === 0) return
    const seq = ++patchSeq.current
    setPatching(true)
    try {
      const next = await patchVisualTokens(id, body)
      if (seq === patchSeq.current) setBoard(next)
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      if (seq === patchSeq.current) setPatching(false)
    }
  }, [id])

  const schedulePatch = useCallback(
    (body: PatchVisualTokensRequest) => {
      pendingPatch.current = { ...pendingPatch.current, ...body }
      if (patchTimer.current) clearTimeout(patchTimer.current)
      patchTimer.current = setTimeout(() => {
        void flushPatch()
      }, 300)
    },
    [flushPatch],
  )

  function runStage() {
    setError(null)
    setProgress('Building palette and logo…')
    statusRef.current?.('running')
    void sse.run(stageRunPath(id, 'visual'))
  }

  if (!id) {
    return (
      <p role="alert" className="rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
        Missing session id.
      </p>
    )
  }

  return (
    <section aria-labelledby="visual-heading" className="space-y-4" data-stub="visual-stage">
      <div className="space-y-1">
        <div className="flex items-center justify-between text-xs font-medium uppercase tracking-wide text-ink-3">
          <Link to={`/s/${id}/naming`} className="text-ink-2 hover:underline">
            ← Naming
          </Link>
          <span className="font-mono">Stage S6</span>
        </div>
        <h1 id="visual-heading" className="text-3xl font-bold tracking-tight text-ink">
          Visual direction
        </h1>
        <p className="text-sm text-ink-2">
          Mood, palette and logo from a shape grammar. Edit a token — the board updates live.
        </p>
      </div>

      {phase === 'loading' && !sse.streaming && (
        <div className="animate-pulse space-y-3" aria-busy="true" aria-label="Loading visual">
          <div className="h-40 rounded-lg bg-line" />
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

      {sse.streaming && (
        <div className="rounded-lg border border-line bg-surface p-6">
          <StageProgress message={progress} />
        </div>
      )}

      {phase === 'ready' && !sse.streaming && !board && (
        <div className="rounded-lg border border-dashed border-line-2 p-8 text-center">
          <p className="text-sm text-ink-2">
            No visual direction yet. Run the stage to generate a palette, type pair and logo.
          </p>
          <button
            type="button"
            onClick={runStage}
            className="mt-4 rounded-lg bg-accent px-5 py-2.5 text-sm font-semibold text-ink hover:bg-accent-strong"
          >
            Run visual stage
          </button>
        </div>
      )}

      {phase === 'ready' && !sse.streaming && board && (
        <>
          <div className="grid gap-4 lg:grid-cols-3">
            <div className="space-y-3 lg:col-span-2">
              <BrandBoard board={board} />
              <PaletteSwatches palette={board.palette} />
            </div>
            <div className="space-y-3">
              <TokenEditor board={board} onPatch={schedulePatch} disabled={patching} />
              <div className="space-y-3 rounded-lg border border-line bg-surface p-4 text-sm">
                <div>
                  <p className="text-xs font-medium uppercase tracking-wide text-ink-3">Mood</p>
                  <p className="mt-0.5 flex flex-wrap gap-1.5">
                    {board.direction.moodWords.map((word) => (
                      <span
                        key={word}
                        className="rounded-full bg-accent/10 px-2 py-0.5 text-xs font-medium text-ink"
                      >
                        {word}
                      </span>
                    ))}
                  </p>
                </div>
                <div>
                  <p className="text-xs font-medium uppercase tracking-wide text-ink-3">
                    Shape language
                  </p>
                  <p className="mt-0.5 capitalize text-ink">{board.direction.shapeLanguage}</p>
                </div>
                <div>
                  <p className="text-xs font-medium uppercase tracking-wide text-ink-3">
                    Font pair
                  </p>
                  <p className="mt-0.5 text-ink">{board.fonts.join(' + ')}</p>
                </div>
                <div>
                  <p className="text-xs font-medium uppercase tracking-wide text-ink-3">
                    Avoid
                  </p>
                  <p className="mt-0.5 text-ink-2">{board.direction.avoidList.join(', ')}</p>
                </div>
              </div>
              <button
                type="button"
                onClick={runStage}
                className="w-full rounded-lg border border-line-2 bg-surface px-4 py-2 text-sm font-medium text-ink-2 hover:border-ink hover:text-ink"
              >
                Re-run visual stage
              </button>
            </div>
          </div>

          <div className="flex justify-end border-t border-line pt-4">
            <Link
              to={`/s/${id}/audit`}
              className="rounded-lg bg-accent px-5 py-2.5 text-sm font-semibold text-ink hover:bg-accent-strong"
            >
              Continue to audit →
            </Link>
          </div>
        </>
      )}

      <ErrorToast
        message={sse.streaming ? null : error}
        onRetry={board ? runStage : undefined}
        onDismiss={() => setError(null)}
      />
    </section>
  )
}
