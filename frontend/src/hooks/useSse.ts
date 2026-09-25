import { useEffect, useState } from 'react'

export interface StageStartedData {
  stage: string
}

export interface ProgressData {
  stage?: string
  message: string
  current?: number
  total?: number
}

export interface PartialData {
  stage?: string
  text?: string
  data?: unknown
}

export interface StageCompletedData {
  stage?: string
  status?: string
  score?: number
  latencyMs?: number
  /** Stage-specific payload (e.g. personality traits/voice/avoidList). */
  data?: unknown
}

export interface SseErrorData {
  stage?: string
  message: string
}

export interface SseCallbacks {
  onStageStarted?: (data: StageStartedData) => void
  onProgress?: (data: ProgressData) => void
  onPartial?: (data: PartialData) => void
  onStageCompleted?: (data: StageCompletedData) => void
  onError?: (data: SseErrorData) => void
}

/** Zero-dep SSE block parser: push decoded text, get (event, data) pairs back. */
export function createSseParser(onEvent: (event: string, data: string) => void) {
  let buffer = ''

  function dispatch(block: string) {
    if (!block.trim()) return
    let event = 'message'
    const dataLines: string[] = []
    for (const line of block.split('\n')) {
      if (line.startsWith(':')) continue
      const colon = line.indexOf(':')
      const field = colon === -1 ? line : line.slice(0, colon)
      let value = colon === -1 ? '' : line.slice(colon + 1)
      if (value.startsWith(' ')) value = value.slice(1)
      if (field === 'event') event = value
      else if (field === 'data') dataLines.push(value)
    }
    onEvent(event, dataLines.join('\n'))
  }

  return {
    push(chunk: string) {
      buffer += chunk.replace(/\r\n/g, '\n')
      let idx: number
      while ((idx = buffer.indexOf('\n\n')) !== -1) {
        const block = buffer.slice(0, idx)
        buffer = buffer.slice(idx + 2)
        dispatch(block)
      }
    },
    flush() {
      if (buffer.trim()) dispatch(buffer)
      buffer = ''
    },
  }
}

function parseData<T>(raw: string, fallback: T): T {
  if (!raw) return fallback
  try {
    return JSON.parse(raw) as T
  } catch {
    return fallback
  }
}

function routeEvent(event: string, raw: string, callbacks: SseCallbacks) {
  switch (event) {
    case 'stage_started':
      callbacks.onStageStarted?.(parseData<StageStartedData>(raw, { stage: '' }))
      break
    case 'progress':
      callbacks.onProgress?.(parseData<ProgressData>(raw, { message: raw || 'Working…' }))
      break
    case 'partial':
      callbacks.onPartial?.(parseData<PartialData>(raw, { text: raw }))
      break
    case 'stage_completed':
      callbacks.onStageCompleted?.(parseData<StageCompletedData>(raw, {}))
      break
    case 'error':
      callbacks.onError?.(parseData<SseErrorData>(raw, { message: raw || 'The stage failed.' }))
      break
  }
}

export interface SseStream {
  /** POST `body` to `path` and stream `text/event-stream` events into the callbacks. */
  run: (path: string, body?: unknown) => Promise<void>
  cancel: () => void
  streaming: boolean
}

export function createSseRunner(
  initialCallbacks: SseCallbacks,
  onStreamingChange?: (streaming: boolean) => void,
) {
  let callbacks = initialCallbacks
  let controller: AbortController | null = null

  async function run(path: string, body?: unknown) {
    controller?.abort()
    const active = new AbortController()
    controller = active
    onStreamingChange?.(true)

    try {
      const res = await fetch(path, {
        method: 'POST',
        credentials: 'include',
        headers: {
          Accept: 'text/event-stream',
          ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
        },
        body: body !== undefined ? JSON.stringify(body) : undefined,
        signal: active.signal,
      })

      if (!res.ok || !res.body) {
        callbacks.onError?.({ message: `Stream failed (${res.status}). Retry to resume this stage.` })
        return
      }

      const reader = res.body.getReader()
      const decoder = new TextDecoder()
      let settled = false
      const parser = createSseParser((event, data) => {
        if (event === 'stage_completed' || event === 'error') settled = true
        routeEvent(event, data, callbacks)
      })

      for (;;) {
        const { done, value } = await reader.read()
        if (done) break
        parser.push(decoder.decode(value, { stream: true }))
      }
      parser.push(decoder.decode())
      parser.flush()

      if (!settled && !active.signal.aborted) {
        callbacks.onError?.({
          message: 'Stream ended before this stage completed. Retry to resume this stage.',
        })
      }
    } catch (err) {
      if (active.signal.aborted) return
      callbacks.onError?.({
        message: err instanceof Error && err.message ? err.message : 'Connection dropped mid-run.',
      })
    } finally {
      if (controller === active) {
        controller = null
        onStreamingChange?.(false)
      }
    }
  }

  const abort = () => controller?.abort()
  return {
    run,
    cancel: abort,
    unmount: abort,
    setCallbacks: (next: SseCallbacks) => {
      callbacks = next
    },
  }
}

export function useSse(callbacks: SseCallbacks): SseStream {
  const [streaming, setStreaming] = useState(false)
  const [runner] = useState(() => createSseRunner(callbacks, setStreaming))
  useEffect(() => {
    runner.setCallbacks(callbacks)
  }, [runner, callbacks])
  useEffect(() => () => runner.unmount(), [runner])

  return { run: runner.run, cancel: runner.cancel, streaming }
}
