import type {
  CreateSessionRequest,
  CreateSessionResponse,
  CreatedShare,
  DriftCheckRequest,
  DriftCheckResponse,
  ExportFormat,
  InterviewAnswerRequest,
  InterviewAnswerResponse,
  PatchBriefRequest,
  PatchBriefResponse,
  PatchVisualTokensRequest,
  SelectNameRequest,
  SelectNameResponse,
  SelectPositionRequest,
  SelectPositionResponse,
  SelectTaglineRequest,
  SelectTaglineResponse,
  SessionState,
  StageRun,
  VisualBoard,
} from './types'

export class ApiError extends Error {
  status: number

  constructor(status: number, message: string) {
    super(message)
    this.status = status
    this.name = 'ApiError'
  }
}

/** User-facing copy when the body has no message (Spring default = status text only). */
function friendlyStatus(status: number): string {
  if (status === 401) return 'This session is no longer yours. Open your original link.'
  if (status === 403) return 'Not allowed. Refresh the page and try again.'
  if (status === 404) return 'Not found. It may have expired.'
  if (status === 409) return 'Something changed. Reload and try again.'
  if (status === 413) return 'That input is too long. Shorten it and try again.'
  if (status === 429) return 'Too many attempts. Wait a moment and try again.'
  if (status >= 500) return 'Server error. Try again in a moment.'
  if (status === 400) return "That request wasn't valid. Check the form and try again."
  return `Request failed (${status}). Please try again.`
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  let res: Response
  try {
    res = await fetch(path, { credentials: 'include', ...init })
  } catch {
    throw new ApiError(0, 'Could not reach the server. Check your connection and try again.')
  }

  let body: unknown = null
  try {
    body = await res.json()
  } catch {
    // non-JSON body; fall through to status-based message
  }

  if (!res.ok) {
    const b = body as { error?: unknown; message?: unknown } | null
    const message =
      typeof b?.message === 'string' && b.message.trim()
        ? b.message
        : friendlyStatus(res.status)
    throw new ApiError(res.status, message)
  }

  return body as T
}

export function createSession(idea: string): Promise<CreateSessionResponse> {
  const body: CreateSessionRequest = { idea }
  return request<CreateSessionResponse>('/api/sessions', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}

export function getSession(id: string): Promise<SessionState> {
  return request<SessionState>(`/api/sessions/${encodeURIComponent(id)}`)
}

export function submitInterviewAnswer(
  id: string,
  body: InterviewAnswerRequest,
): Promise<InterviewAnswerResponse> {
  return request<InterviewAnswerResponse>(
    `/api/sessions/${encodeURIComponent(id)}/interview/answer`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    },
  )
}

export function patchBrief(id: string, body: PatchBriefRequest): Promise<PatchBriefResponse> {
  return request<PatchBriefResponse>(`/api/sessions/${encodeURIComponent(id)}/brief`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}

export function selectPosition(id: string, body: SelectPositionRequest): Promise<SelectPositionResponse> {
  return request<SelectPositionResponse>(
    `/api/sessions/${encodeURIComponent(id)}/stages/position/select`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    },
  )
}

export function selectName(id: string, body: SelectNameRequest): Promise<SelectNameResponse> {
  return request<SelectNameResponse>(
    `/api/sessions/${encodeURIComponent(id)}/stages/naming/select`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    },
  )
}

export function selectTagline(
  id: string,
  body: SelectTaglineRequest,
): Promise<SelectTaglineResponse> {
  return request<SelectTaglineResponse>(
    `/api/sessions/${encodeURIComponent(id)}/stages/messages/select`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    },
  )
}

export function listStageRuns(id: string): Promise<StageRun[]> {
  return request<StageRun[]>(`/api/sessions/${encodeURIComponent(id)}/stage-runs`)
}

export function setStageLock(id: string, stage: string, locked: boolean): Promise<unknown> {
  return request(`/api/sessions/${encodeURIComponent(id)}/stages/${encodeURIComponent(stage)}/lock`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ locked }),
  })
}

export function stageRunPath(id: string, stage: string): string {
  return `/api/sessions/${encodeURIComponent(id)}/stages/${stage}/run`
}

/** Path for a synchronous stage re-run; null = no endpoint (S0/S1/kit/drift — skip). */
export function stageRerunPath(id: string, stage: string): string | null {
  const base = `/api/sessions/${encodeURIComponent(id)}`
  switch (stage) {
    case 'S2':
      return `${base}/stages/position/run?sync=1`
    case 'S3':
      return `${base}/stages/personality/run`
    case 'S4':
      return `${base}/stages/naming/run`
    case 'S5':
      return `${base}/stages/messages/run`
    case 'S6':
      return `${base}/stages/visual/run`
    case 'S7':
      return `${base}/audit`
    case 'S8':
      return `${base}/stages/launch/run`
    default:
      return null
  }
}

/** Best-effort re-run: missing endpoint (null path or 404) skips quietly. */
export async function rerunStage(id: string, stage: string): Promise<void> {
  const path = stageRerunPath(id, stage)
  if (!path) return
  let res: Response
  try {
    res = await fetch(path, {
      method: 'POST',
      credentials: 'include',
      headers: { Accept: 'application/json' },
    })
  } catch {
    throw new ApiError(0, 'Could not reach the server. Check your connection and try again.')
  }
  await res.text().catch(() => '')
  if (res.status === 404) return
  if (!res.ok) {
    throw new ApiError(res.status, `Re-running ${stage} failed (${res.status}). Please try again.`)
  }
}

export function auditRunPath(id: string): string {
  return `/api/sessions/${encodeURIComponent(id)}/audit`
}

export function driftCheck(id: string, body: DriftCheckRequest): Promise<DriftCheckResponse> {
  return request<DriftCheckResponse>(
    `/api/sessions/${encodeURIComponent(id)}/drift-check`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    },
  )
}

export function stageRegeneratePath(id: string, stage: string): string {
  return `/api/sessions/${encodeURIComponent(id)}/stages/${stage}/regenerate`
}

export function patchVisualTokens(id: string, body: PatchVisualTokensRequest): Promise<VisualBoard> {
  return request<VisualBoard>(`/api/sessions/${encodeURIComponent(id)}/visual/tokens`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}

export function createShare(id: string): Promise<CreatedShare> {
  return request<CreatedShare>(`/api/sessions/${encodeURIComponent(id)}/share`, {
    method: 'POST',
  })
}

export function shareUrl(token: string): string {
  return `${window.location.origin}/share/${encodeURIComponent(token)}`
}

/** Public kit by share token — no owner cookie required. */
export function getShareKit(token: string): Promise<unknown> {
  return request<unknown>(`/api/share/${encodeURIComponent(token)}`, { credentials: 'omit' })
}

/** POST export and trigger a browser file download (fetch blob + a[download]). */
export async function exportKit(id: string, format: ExportFormat): Promise<void> {
  let res: Response
  try {
    res = await fetch(
      `/api/sessions/${encodeURIComponent(id)}/export?format=${encodeURIComponent(format)}`,
      { method: 'POST', credentials: 'include' },
    )
  } catch {
    throw new ApiError(0, 'Could not reach the server. Check your connection and try again.')
  }

  if (!res.ok) {
    let message = `Export failed (${res.status}). Please try again.`
    try {
      const body = (await res.json()) as { message?: string; error?: string }
      message = body.message ?? body.error ?? message
    } catch {
      // non-JSON error body; keep default message
    }
    throw new ApiError(res.status, message)
  }

  const blob = await res.blob()
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `brand-kit.${format}`
  link.click()
  URL.revokeObjectURL(url)
}
