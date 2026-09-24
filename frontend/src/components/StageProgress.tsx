export interface StageProgressProps {
  message: string
  bars?: number
}

export function StageProgress({ message, bars = 3 }: StageProgressProps) {
  return (
    <div role="status" aria-live="polite" aria-busy="true" className="space-y-4">
      <p className="flex items-center gap-2 text-sm text-ink-2">
        <span aria-hidden="true" className="h-2 w-2 shrink-0 animate-pulse rounded-full bg-accent" />
        {message}
      </p>
      <div aria-hidden="true" className="space-y-2">
        {Array.from({ length: bars }, (_, i) => (
          <div
            key={i}
            className={`h-3 animate-pulse rounded bg-line ${i === bars - 1 ? 'w-2/3' : 'w-full'}`}
          />
        ))}
      </div>
    </div>
  )
}
