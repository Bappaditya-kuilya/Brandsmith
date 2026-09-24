import { Component, type ErrorInfo, type ReactNode } from 'react'

interface Props {
  children: ReactNode
}

interface State {
  error: Error | null
}

/** Keep a render crash from wiping the whole SPA to a blank page. */
export class ErrorBoundary extends Component<Props, State> {
  state: State = { error: null }

  static getDerivedStateFromError(error: Error): State {
    return { error }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('[brandsmith-ui]', error, info.componentStack)
  }

  render() {
    if (this.state.error) {
      return (
        <main className="mx-auto flex min-h-svh max-w-xl flex-col justify-center gap-4 p-8">
          <p role="alert" className="rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
            This screen hit an unexpected error. Reload to continue.
          </p>
          <button
            type="button"
            onClick={() => window.location.reload()}
            className="rounded-lg bg-accent px-4 py-2.5 text-sm font-semibold text-ink hover:bg-accent-strong"
          >
            Reload
          </button>
        </main>
      )
    }
    return this.props.children
  }
}
