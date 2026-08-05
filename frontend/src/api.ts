import type { ApiError, Conversation, Lead } from '@/types'

const BASE = import.meta.env.VITE_API_URL ?? 'http://localhost:8080'

export class ApiFailure extends Error {
  constructor(readonly code: string, message: string) {
    super(message)
  }
}

async function call<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${BASE}${path}`, {
    ...init,
    headers: { 'Content-Type': 'application/json', ...(init?.headers ?? {}) },
  })
  if (!response.ok) {
    const problem = (await response.json().catch(() => null)) as ApiError | null
    throw new ApiFailure(problem?.error ?? 'unknown', problem?.message ?? response.statusText)
  }
  return (await response.json()) as T
}

export const api = {
  leads: () => call<Lead[]>('/api/leads'),
  start: (leadId: number) =>
    call<Conversation>('/api/conversations', {
      method: 'POST',
      body: JSON.stringify({ leadId }),
    }),
  get: (id: number) => call<Conversation>(`/api/conversations/${id}`),
  send: (id: number, body: string) =>
    call<Conversation>(`/api/conversations/${id}/messages`, {
      method: 'POST',
      body: JSON.stringify({ body }),
    }),
  reset: (id: number) => call<Conversation>(`/api/conversations/${id}/reset`, { method: 'POST' }),
}
