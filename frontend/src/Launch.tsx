import { useEffect, useRef, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { getSession, stageRunPath } from './api/client'
import type { BrandDna, LaunchAssets } from './api/types'
import { ErrorToast } from './components/ErrorToast'
import { StageProgress } from './components/StageProgress'
import { LaunchAssetsBoard } from './components/KitBoard'
import { useSse, type StageCompletedData } from './hooks/useSse'
import type { StageStatus } from './lib/stages'

function errorMessage(err: unknown): string {
  return err instanceof Error ? err.message : 'Something went wrong. Please try again.'
}

function isLaunchAssets(value: unknown): value is LaunchAssets {
  if (!value || typeof value !== 'object') return false
  const a = value as Partial<LaunchAssets>
  return Boolean(a.hero?.headline && a.pitch && Array.isArray(a.posts) && a.posts.length >= 3)
}

function toLaunch(raw: StageCompletedData): LaunchAssets | null {
  const top = raw as StageCompletedData & Partial<LaunchAssets>
  const nested =
    top.data && typeof top.data === 'object' ? (top.data as Partial<LaunchAssets>) : null
  const candidate = nested ?? top
  return isLaunchAssets(candidate) ? (candidate as LaunchAssets) : null
}

function fromBrandDna(dna: unknown): LaunchAssets | null {
  if (!dna || typeof dna !== 'object') return null
  const assets = (dna as BrandDna).assets
  return isLaunchAssets(assets) ? assets : null
}

export interface LaunchPageProps {
  onStatus?: (status: StageStatus) => void
}

/** S8 launch assets: hero, pitch, 3 posts, bio — run/regenerate via SSE. */
export function LaunchPage({ onStatus }: LaunchPageProps) {
  const { id = '' } = useParams()
  const [assets, setAssets] = useState<LaunchAssets | null>(null)
  const [phase, setPhase] = useState<'loading' | 'ready' | 'error'>('loading')
  const [progress, setProgress] = useState('Writing hero, pitch, posts and bio…')
  const [error, setError] = useState<string | null>(null)
  const [streamError, setStreamError] = useState(false)
  const [reloadKey, setReloadKey] = useState(0)
  const statusRef = useRef(onStatus)

  useEffect(() => {
    statusRef.current = onStatus
  }, [onStatus])

  const sse = useSse({
    onProgress: (d) => setProgress(d.message || 'Writing hero, pitch, posts and bio…'),
    onStageCompleted: (d) => {
      const next = toLaunch(d)
      if (next) {
        setAssets(next)
        setError(null)
        setStreamError(false)
        statusRef.current?.('done')
      } else {
        setError('Launch finished but returned no assets. Try running again.')
        setStreamError(true)
        statusRef.current?.('idle')
      }
    },
    onError: (d) => {
      setError(d.message || 'The launch stage failed.')
      setStreamError(true)
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
        setAssets(stored)
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

  function runStage() {
    setError(null)
    setStreamError(false)
    setProgress('Writing hero, pitch, posts and bio…')
    statusRef.current?.('running')
    void sse.run(stageRunPath(id, 'launch'))
  }

  if (!id) {
    return (
      <p role="alert" className="rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
        Missing session id.
      </p>
    )
  }

  return (
    <section aria-labelledby="launch-heading" className="space-y-4" data-stub="launch-stage">
      <div className="space-y-1">
        <div className="flex items-center justify-between text-xs font-medium uppercase tracking-wide text-ink-3">
          <Link to={`/s/${id}/audit`} className="text-ink-2 hover:underline">
            ← Audit
          </Link>
          <span className="font-mono">Stage S8</span>
        </div>
        <h1 id="launch-heading" className="text-3xl font-bold tracking-tight text-ink">
          Launch assets
        </h1>
        <p className="text-sm text-ink-2">
          Hero, pitch, three social posts and a bio — ready to paste wherever you launch.
        </p>
      </div>

      {phase === 'loading' && !sse.streaming && (
        <div className="animate-pulse space-y-3" aria-busy="true" aria-label="Loading launch assets">
          {[0, 1, 2].map((i) => (
            <div key={i} className="h-24 rounded-lg bg-line" />
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

      {sse.streaming && (
        <div className="rounded-lg border border-line bg-surface p-6">
          <StageProgress message={progress} bars={4} />
        </div>
      )}

      {phase === 'ready' && !sse.streaming && !assets && (
        <div className="rounded-lg border border-dashed border-line-2 p-6 text-center">
          <p className="text-sm text-ink-2">
            No launch assets yet. Run the stage to write the hero, pitch, posts and bio.
          </p>
          <button
            type="button"
            onClick={runStage}
            className="mt-4 rounded-lg bg-accent px-5 py-2.5 text-sm font-semibold text-ink hover:bg-accent-strong"
          >
            Run launch assets
          </button>
        </div>
      )}

      {phase === 'ready' && !sse.streaming && assets && (
        <>
          <LaunchAssetsBoard assets={assets} />

          <div className="flex flex-wrap items-center gap-2 border-t border-line pt-4">
            <button
              type="button"
              onClick={runStage}
              className="rounded-lg bg-accent px-4 py-2 text-sm font-semibold text-ink hover:bg-accent-strong"
            >
              Regenerate
            </button>
            <Link
              to={`/s/${id}/kit`}
              className="rounded-lg border border-line-2 bg-surface px-4 py-2 text-sm font-medium text-ink-2 hover:border-ink hover:text-ink"
            >
              Open brand kit →
            </Link>
          </div>
        </>
      )}

      <ErrorToast
        message={sse.streaming ? null : error}
        onRetry={streamError || (error && phase === 'ready' && !assets) ? runStage : undefined}
        onDismiss={() => setError(null)}
      />
    </section>
  )
}

export function LaunchPageShell({ onStatus }: LaunchPageProps) {
  return (
    <main className="mx-auto flex min-h-svh max-w-4xl flex-col gap-6 p-8">
      <LaunchPage onStatus={onStatus} />
    </main>
  )
}
