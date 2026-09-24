import { useEffect, useState, type FormEvent } from 'react'
import { Link, Route, Routes, useNavigate } from 'react-router-dom'
import { createSession } from './api/client'
import { AuditPage } from './Audit'
import { SessionShell } from './components/AppShell'
import { DriftPage } from './Drift'
import { Identity } from './Identity'
import { BriefReview, Interview } from './Interview'
import { KitPage } from './Kit'
import { LaunchPageShell } from './Launch'
import { NamingPage } from './Naming'
import { PositionBattle } from './Position'
import { SharePage } from './Share'
import { VisualPage } from './Visual'
import type { StageStatus } from './lib/stages'

const MIN_IDEA = 10
const MAX_IDEA = 1000

const EXAMPLES = [
  {
    label: 'Student team tool',
    idea: 'A tool that helps university student teams find missing members, pick meeting times across time zones, and track who owes what before a deadline.',
  },
  {
    label: 'Creator newsletter',
    idea: 'A weekly newsletter for indie creators who want to grow an audience without paid ads, with one practical tactic and teardown per issue.',
  },
  {
    label: 'Local bakery',
    idea: 'A neighborhood sourdough bakery that takes custom celebration cake orders online and sells daily loaves through a Saturday pickup window.',
  },
]

type HealthStatus = 'loading' | 'ok' | 'error'

function HealthBadge() {
  const [status, setStatus] = useState<HealthStatus>('loading')

  useEffect(() => {
    let cancelled = false
    fetch('/api/health', { credentials: 'include' })
      .then((res) => {
        if (!res.ok) throw new Error(String(res.status))
        if (!cancelled) setStatus('ok')
      })
      .catch(() => {
        if (!cancelled) setStatus('error')
      })
    return () => {
      cancelled = true
    }
  }, [])

  const styles: Record<HealthStatus, string> = {
    loading: 'bg-line text-ink-2',
    ok: 'bg-green-100 text-green-800',
    error: 'bg-red-100 text-red-800',
  }
  const label: Record<HealthStatus, string> = {
    loading: 'checking…',
    ok: 'api ok',
    error: 'api down',
  }

  return (
    <span
      className={`inline-flex items-center rounded-full px-3 py-1 text-xs font-medium ${styles[status]}`}
      data-status={status}
    >
      {label[status]}
    </span>
  )
}

function Home() {
  const navigate = useNavigate()
  const [idea, setIdea] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  const length = idea.length
  const outOfRange = length > 0 && (length < MIN_IDEA || length > MAX_IDEA)
  const canSubmit = length >= MIN_IDEA && length <= MAX_IDEA && !submitting

  async function handleSubmit(e: FormEvent) {
    e.preventDefault()
    if (!canSubmit) return

    setError(null)
    setSubmitting(true)
    try {
      const session = await createSession(idea)
      navigate(`/s/${session.id}/interview`)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Something went wrong. Please try again.')
      setSubmitting(false)
    }
  }

  return (
    <main className="mx-auto flex min-h-svh max-w-xl flex-col justify-center gap-8 p-8">
      <header className="space-y-3">
        <div className="flex items-center justify-between gap-3">
          <span className="font-mono text-xs uppercase tracking-widest text-ink-3">
            Brand kit studio
          </span>
          <HealthBadge />
        </div>
        <div className="border-t-2 border-ink pt-4">
          <h1 className="text-5xl font-bold tracking-tighter text-ink">Brandsmith</h1>
          <p className="mt-2 text-lg text-ink-2">One sentence to a launch-ready brand kit.</p>
        </div>
      </header>

      <form onSubmit={handleSubmit} className="space-y-4" noValidate>
        <div className="space-y-2">
          <label htmlFor="idea" className="block text-sm font-medium text-ink">
            Your idea
          </label>
          <textarea
            id="idea"
            value={idea}
            onChange={(e) => setIdea(e.target.value)}
            rows={5}
            maxLength={MAX_IDEA + 1}
            placeholder="Describe the product, club, newsletter or shop in one or two sentences…"
            className={`w-full resize-y rounded-lg border px-3 py-2 text-sm text-ink placeholder:text-ink-3 focus:outline-none focus:ring-2 ${
              outOfRange
                ? 'border-red-400 focus:ring-red-300'
                : 'border-line-2 focus:ring-accent'
            }`}
          />
          <div className="flex items-center justify-between text-xs">
            <span className={outOfRange ? 'font-medium text-red-600' : 'text-ink-3'}>
              {length < MIN_IDEA
                ? `At least ${MIN_IDEA} characters`
                : length > MAX_IDEA
                  ? `At most ${MAX_IDEA} characters`
                  : '10–1000 characters'}
            </span>
            <span className={length > MAX_IDEA ? 'font-medium text-red-600' : 'text-ink-3'}>
              {length}/{MAX_IDEA}
            </span>
          </div>
        </div>

        <div className="space-y-2">
          <span className="text-xs font-medium uppercase tracking-wide text-ink-3">
            Or try an example
          </span>
          <div className="flex flex-wrap gap-2">
            {EXAMPLES.map((example) => (
              <button
                key={example.label}
                type="button"
                onClick={() => {
                  setIdea(example.idea)
                  setError(null)
                }}
                disabled={submitting}
                className="rounded-full border border-line-2 bg-surface px-3 py-1.5 text-sm text-ink-2 hover:border-ink hover:text-ink disabled:opacity-50"
              >
                {example.label}
              </button>
            ))}
          </div>
        </div>

        {error && (
          <p role="alert" className="rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
            {error}
          </p>
        )}

        <button
          type="submit"
          disabled={!canSubmit}
          className={`w-full rounded-lg px-4 py-2.5 text-sm font-semibold disabled:cursor-not-allowed ${
            canSubmit
              ? 'bg-accent text-ink hover:bg-accent-strong'
              : 'bg-line text-ink-3'
          }`}
        >
          {submitting ? 'Creating session…' : 'Start brand interview'}
        </button>

        <p className="text-center text-xs text-ink-3">
          Ideas are sent to an LLM provider. Do not enter secrets or personal data.
        </p>
      </form>
    </main>
  )
}

function SessionPlaceholder() {
  return (
    <main className="mx-auto flex min-h-svh max-w-xl flex-col items-center justify-center gap-4 p-8 text-center">
      <h1 className="text-2xl font-semibold text-ink">Session</h1>
      <p className="text-ink-2">Placeholder for /s/:id/* stage screens.</p>
      <Link to="/" className="text-sm text-ink-2 hover:underline">
        ← Home
      </Link>
    </main>
  )
}

function PositionRoute() {
  const [s2Status, setS2Status] = useState<StageStatus>('idle')
  return (
    <SessionShell statuses={{ S2: s2Status }}>
      <PositionBattle onStatus={setS2Status} />
    </SessionShell>
  )
}

function NamingRoute() {
  const [s4Status, setS4Status] = useState<StageStatus>('idle')
  return (
    <SessionShell statuses={{ S4: s4Status }}>
      <NamingPage
        onStatus={(status) =>
          setS4Status(status === 'done' ? 'done' : status === 'running' ? 'running' : 'idle')
        }
      />
    </SessionShell>
  )
}

function VisualRoute() {
  const [s6Status, setS6Status] = useState<StageStatus>('idle')
  return (
    <SessionShell statuses={{ S6: s6Status }}>
      <VisualPage onStatus={setS6Status} />
    </SessionShell>
  )
}

function AuditRoute() {
  const [s7Status, setS7Status] = useState<StageStatus>('idle')
  return (
    <SessionShell statuses={{ S7: s7Status }}>
      <AuditPage onStatus={setS7Status} />
    </SessionShell>
  )
}

function DriftRoute() {
  const [driftStatus, setDriftStatus] = useState<StageStatus>('idle')
  return (
    <SessionShell statuses={{ drift: driftStatus }}>
      <DriftPage onStatus={setDriftStatus} />
    </SessionShell>
  )
}

function LaunchRoute() {
  const [s8Status, setS8Status] = useState<StageStatus>('idle')
  return (
    <SessionShell statuses={{ S8: s8Status }}>
      <LaunchPageShell onStatus={setS8Status} />
    </SessionShell>
  )
}

function KitRoute() {
  const [kitStatus, setKitStatus] = useState<StageStatus>('idle')
  return (
    <SessionShell statuses={{ kit: kitStatus }}>
      <KitPage onStatus={setKitStatus} />
    </SessionShell>
  )
}

function App() {
  return (
    <Routes>
      <Route path="/" element={<Home />} />
      <Route path="/share/:token" element={<SharePage />} />
      <Route path="/s/:id/interview" element={<SessionShell><Interview /></SessionShell>} />
      <Route path="/s/:id/brief" element={<SessionShell><BriefReview /></SessionShell>} />
      <Route path="/s/:id/position" element={<PositionRoute />} />
      <Route path="/s/:id/identity" element={<SessionShell><Identity /></SessionShell>} />
      <Route path="/s/:id/naming" element={<NamingRoute />} />
      <Route path="/s/:id/visual" element={<VisualRoute />} />
      <Route path="/s/:id/audit" element={<AuditRoute />} />
      <Route path="/s/:id/launch" element={<LaunchRoute />} />
      <Route path="/s/:id/kit" element={<KitRoute />} />
      <Route path="/s/:id/drift" element={<DriftRoute />} />
      <Route path="/s/:id/*" element={<SessionShell><SessionPlaceholder /></SessionShell>} />
    </Routes>
  )
}

export default App
