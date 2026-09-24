import { useNavigate, useParams } from 'react-router-dom'
import { STAGE_IDS, STAGE_LABELS, STAGE_ROUTES, type StageId, type StageStatus } from '../lib/stages'

const STATUS_DOT: Record<StageStatus, string> = {
  done: 'bg-ink',
  running: 'bg-accent animate-pulse',
  stale: 'bg-amber-500',
  locked: 'bg-line-2',
  idle: 'bg-line border border-line-2',
}

const STATUS_TEXT: Record<StageStatus, string> = {
  done: 'text-ink',
  running: 'text-emerald-700',
  stale: 'text-amber-700',
  locked: 'text-ink-3',
  idle: 'text-ink-3',
}

export interface StageRailProps {
  statuses: Partial<Record<StageId, StageStatus>>
  current?: StageId | null
  routes?: Partial<Record<StageId, string | null>>
  onNavigate?: (stage: StageId) => void
}

export function StageRail({ statuses, current, routes = STAGE_ROUTES, onNavigate }: StageRailProps) {
  const navigate = useNavigate()
  const { id } = useParams()

  function handleClick(stage: StageId) {
    if (onNavigate) {
      onNavigate(stage)
      return
    }
    const segment = routes[stage]
    if (segment && id) navigate(`/s/${id}/${segment}`)
  }

  return (
    <nav aria-label="Pipeline stages" className="w-14 shrink-0 border-r border-line bg-paper-2 py-4 md:w-52">
      <ol className="flex flex-col gap-1 px-2 md:px-3">
        {STAGE_IDS.map((stage) => {
          const status = statuses[stage] ?? 'idle'
          const segment = routes[stage]
          const label = STAGE_LABELS[stage]
          const isCurrent = current === stage
          const enabled = Boolean(onNavigate ?? (segment && id))

          return (
            <li key={stage}>
              <button
                type="button"
                onClick={() => handleClick(stage)}
                disabled={!enabled}
                aria-current={isCurrent ? 'step' : undefined}
                title={enabled ? `${label} — ${status}` : `${label} — not available yet`}
                className={`flex w-full items-center gap-2 border-l-2 px-2 py-2 text-left text-sm focus:outline-none focus-visible:ring-2 focus-visible:ring-ink disabled:cursor-not-allowed disabled:opacity-60 ${
                  isCurrent
                    ? 'border-l-accent bg-surface font-medium'
                    : 'border-l-transparent hover:bg-surface'
                }`}
              >
                <span
                  aria-hidden="true"
                  className={`h-2.5 w-2.5 shrink-0 rounded-full ${STATUS_DOT[status]}`}
                />
                <span className={`font-mono text-xs font-semibold ${STATUS_TEXT[status]}`}>{stage}</span>
                <span className={`hidden truncate text-xs md:block ${STATUS_TEXT[status]}`}>{label}</span>
                <span className="sr-only">, {status}</span>
                {status === 'locked' && (
                  <svg aria-hidden="true" viewBox="0 0 16 16" className="ml-auto hidden h-3.5 w-3.5 fill-ink-3 md:block">
                    <path d="M4 7V5a4 4 0 1 1 8 0v2h1a1 1 0 0 1 1 1v5a1 1 0 0 1-1 1H3a1 1 0 0 1-1-1V8a1 1 0 0 1 1-1h1Zm2 0h4V5a2 2 0 1 0-4 0v2Z" />
                  </svg>
                )}
              </button>
            </li>
          )
        })}
      </ol>
    </nav>
  )
}
