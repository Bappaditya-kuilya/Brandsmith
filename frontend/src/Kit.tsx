import { useCallback, useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { createShare, exportKit, getSession, shareUrl } from './api/client'
import type { BrandDna, ExportFormat } from './api/types'
import { ErrorToast } from './components/ErrorToast'
import { KitBoard } from './components/KitBoard'
import type { StageStatus } from './lib/stages'

function errorMessage(err: unknown): string {
  return err instanceof Error ? err.message : 'Something went wrong. Please try again.'
}

function toDna(raw: unknown): BrandDna | null {
  if (!raw || typeof raw !== 'object') return null
  return raw as BrandDna
}

export interface KitPageProps {
  onStatus?: (status: StageStatus) => void
}

/** Full brand board + export / print / share actions. */
export function KitPage({ onStatus }: KitPageProps) {
  const { id = '' } = useParams()
  const [dna, setDna] = useState<BrandDna | null>(null)
  const [phase, setPhase] = useState<'loading' | 'ready' | 'error'>('loading')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState<'md' | 'json' | 'share' | null>(null)
  const [url, setUrl] = useState<string | null>(null)
  const [copied, setCopied] = useState(false)
  const [reloadKey, setReloadKey] = useState(0)

  const load = useCallback(() => {
    if (!id) return
    let cancelled = false
    getSession(id)
      .then((session) => {
        if (cancelled) return
        setDna(toDna(session.brandDna))
        setPhase('ready')
        onStatus?.('done')
      })
      .catch((err: unknown) => {
        if (cancelled) return
        setError(errorMessage(err))
        setPhase('error')
      })
    return () => {
      cancelled = true
    }
  }, [id, onStatus])

  useEffect(() => load(), [load, reloadKey])

  async function handleExport(format: ExportFormat) {
    setBusy(format)
    setError(null)
    try {
      await exportKit(id, format)
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setBusy(null)
    }
  }

  async function handleShare() {
    setBusy('share')
    setError(null)
    try {
      const created = await createShare(id)
      setUrl(shareUrl(created.token))
      setCopied(false)
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setBusy(null)
    }
  }

  async function handleCopy() {
    if (!url) return
    try {
      await navigator.clipboard.writeText(url)
      setCopied(true)
    } catch {
      setError('Could not copy automatically — select the link and copy it.')
    }
  }

  if (!id) {
    return (
      <p role="alert" className="rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
        Missing session id.
      </p>
    )
  }

  const btn =
    'rounded-lg border border-line-2 bg-surface px-4 py-2 text-sm font-medium text-ink-2 hover:border-ink hover:text-ink disabled:cursor-not-allowed disabled:opacity-50'
  const btnPrimary =
    'rounded-lg bg-accent px-4 py-2 text-sm font-semibold text-ink hover:bg-accent-strong disabled:cursor-not-allowed disabled:opacity-50'

  return (
    <main className="mx-auto w-full max-w-4xl space-y-6 p-8" data-stub="kit-page">
      <div className="space-y-1">
        <div className="flex items-center justify-between text-xs font-medium text-ink-3">
          <Link to={`/s/${id}/launch`} className="text-ink-2 hover:underline">
            ← Launch
          </Link>
          <span className="font-mono">Kit</span>
        </div>
        <h1 className="text-3xl font-bold tracking-tight text-ink">Brand kit</h1>
        <p className="text-sm text-ink-2">
          Everything the brand stands for, on one board. Export it, print it, or share a read-only
          link that expires in 30 days.
        </p>
      </div>

      <div className="flex flex-wrap gap-2 print:hidden">
        <button type="button" onClick={() => void handleExport('md')} disabled={busy !== null} className={btn}>
          {busy === 'md' ? 'Exporting…' : 'Export MD'}
        </button>
        <button type="button" onClick={() => void handleExport('json')} disabled={busy !== null} className={btn}>
          {busy === 'json' ? 'Exporting…' : 'Export JSON'}
        </button>
        <button type="button" onClick={() => window.print()} className={btn}>
          Print
        </button>
        <button type="button" onClick={() => void handleShare()} disabled={busy !== null} className={btnPrimary}>
          {busy === 'share' ? 'Creating link…' : url ? 'Create another share link' : 'Create share link'}
        </button>
        <Link
          to={`/s/${id}/drift`}
          className={`${btn} no-underline`}
        >
          Drift check
        </Link>
      </div>

      {url && (
        <div className="flex flex-wrap items-center gap-2 rounded-lg border border-accent bg-accent/15 px-3 py-2 print:hidden">
          <label htmlFor="share-url" className="sr-only">
            Share link
          </label>
          <input
            id="share-url"
            readOnly
            value={url}
            onFocus={(e) => e.currentTarget.select()}
            className="min-w-0 flex-1 rounded border border-accent bg-surface px-2 py-1.5 font-mono text-xs text-ink"
          />
          <button type="button" onClick={() => void handleCopy()} className={btn}>
            {copied ? 'Copied' : 'Copy'}
          </button>
        </div>
      )}

      {phase === 'loading' && (
        <div className="animate-pulse space-y-3" aria-busy="true" aria-label="Loading brand kit">
          {[0, 1, 2].map((i) => (
            <div key={i} className="h-28 rounded-lg bg-line" />
          ))}
        </div>
      )}

      {phase === 'error' && (
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
            className={btn}
          >
            Try again
          </button>
        </div>
      )}

      {phase === 'ready' && dna && <KitBoard dna={dna} />}

      <ErrorToast message={phase === 'ready' ? error : null} onDismiss={() => setError(null)} />
    </main>
  )
}
