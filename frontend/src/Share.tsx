import { useEffect, useState } from 'react'
import { useParams } from 'react-router-dom'
import { getShareKit } from './api/client'
import type { BrandDna } from './api/types'
import { KitBoard } from './components/KitBoard'

function errorMessage(err: unknown): string {
  return err instanceof Error ? err.message : 'Something went wrong. Please try again.'
}

function toDna(raw: unknown): BrandDna | null {
  if (!raw || typeof raw !== 'object') return null
  return raw as BrandDna
}

/** Public read-only kit page — fetches by share token, no owner cookie, printable. */
export function SharePage() {
  const { token = '' } = useParams()
  const [dna, setDna] = useState<BrandDna | null>(null)
  const [phase, setPhase] = useState<'loading' | 'ready' | 'error'>('loading')
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!token) return
    let cancelled = false
    getShareKit(token)
      .then((body) => {
        if (cancelled) return
        setDna(toDna(body))
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
  }, [token])

  return (
    <main className="mx-auto w-full max-w-4xl space-y-6 p-8" data-stub="share-page">
      <div className="space-y-1">
        <p className="text-xs font-medium text-ink-3">
          Shared brand kit · read-only
        </p>
        <h1 className="text-3xl font-bold tracking-tight text-ink">Brand kit</h1>
        <p className="text-sm text-ink-2">
          This link is read-only and expires 30 days after it was created.
        </p>
      </div>

      <div className="flex gap-2 print:hidden">
        <button
          type="button"
          onClick={() => window.print()}
          className="rounded-lg border border-line-2 bg-surface px-4 py-2 text-sm font-medium text-ink-2 hover:border-ink hover:text-ink"
        >
          Print / Save PDF
        </button>
      </div>

      {phase === 'loading' && (
        <div className="animate-pulse space-y-3" aria-busy="true" aria-label="Loading shared kit">
          {[0, 1, 2].map((i) => (
            <div key={i} className="h-28 rounded-lg bg-line" />
          ))}
        </div>
      )}

      {phase === 'error' && (
        <div className="rounded-lg border border-red-200 bg-red-50 p-6 text-center">
          <p role="alert" className="text-sm text-red-700">
            {error ?? 'Share not found or expired'}
          </p>
          <p className="mt-2 text-xs text-red-700">
            Ask the person who shared this kit for a fresh link.
          </p>
        </div>
      )}

      {phase === 'ready' && dna && <KitBoard dna={dna} />}
    </main>
  )
}
