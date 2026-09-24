export interface ErrorToastProps {
  message: string | null
  onRetry?: () => void
  onDismiss?: () => void
}

export function ErrorToast({ message, onRetry, onDismiss }: ErrorToastProps) {
  if (!message) return null

  return (
    <div
      role="alert"
      className="fixed bottom-4 right-4 z-50 flex max-w-sm items-start gap-3 rounded-lg border border-red-200 bg-red-50 px-4 py-3 shadow-lg"
    >
      <p className="flex-1 text-sm text-red-800">{message}</p>
      <div className="flex shrink-0 items-center gap-1">
        {onRetry && (
          <button
            type="button"
            onClick={onRetry}
            className="rounded-md px-2 py-1 text-sm font-semibold text-red-700 hover:bg-red-100 focus:outline-none focus-visible:ring-2 focus-visible:ring-red-500"
          >
            Retry
          </button>
        )}
        {onDismiss && (
          <button
            type="button"
            onClick={onDismiss}
            aria-label="Dismiss error"
            className="rounded-md px-2 py-1 text-sm text-red-700 hover:bg-red-100 focus:outline-none focus-visible:ring-2 focus-visible:ring-red-500"
          >
            Dismiss
          </button>
        )}
      </div>
    </div>
  )
}
