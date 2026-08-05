import { useEffect, useState } from 'react'
import { Inspector } from '@/components/Inspector'
import { LeadPicker } from '@/components/LeadPicker'
import { Thread } from '@/components/Thread'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { ApiFailure, api } from '@/api'
import type { Conversation, Lead } from '@/types'

const STORAGE_KEY = 'conversationId'

export default function App() {
  const [leads, setLeads] = useState<Lead[]>([])
  const [conversation, setConversation] = useState<Conversation | null>(null)
  const [draft, setDraft] = useState('')
  const [busy, setBusy] = useState(false)
  const [pending, setPending] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    api.leads().then(setLeads).catch((e: Error) => setError(e.message))
    const stored = localStorage.getItem(STORAGE_KEY)
    if (!stored) return
    // ponytail: a stale id (wiped db) is expected, not an error — drop it and show the lead
    // list. Only the server answering discards the id: a network error means the backend is
    // still booting, and the conversation is still there once it is up.
    api
      .get(Number(stored))
      .then(setConversation)
      .catch((e: Error) => {
        if (e instanceof ApiFailure) localStorage.removeItem(STORAGE_KEY)
        else setError(e.message)
      })
  }, [])

  async function run(action: () => Promise<Conversation>) {
    setBusy(true)
    setError(null)
    try {
      const next = await action()
      setConversation(next)
      localStorage.setItem(STORAGE_KEY, String(next.id))
    } catch (e) {
      setError(e instanceof ApiFailure ? e.message : String(e))
    } finally {
      setBusy(false)
      setPending(null)
    }
  }

  const send = () => {
    if (!conversation || !draft.trim()) return
    const body = draft
    setDraft('')
    setPending(body)
    void run(() => api.send(conversation.id, body))
  }

  return (
    <div className="mx-auto grid max-w-[1400px] grid-cols-1 gap-6 p-6 lg:grid-cols-[240px_1fr_380px]">
      <LeadPicker
        leads={leads}
        selectedId={conversation?.leadId ?? null}
        busy={busy}
        onSelect={(lead) => void run(() => api.start(lead.id))}
        onReset={() => conversation && void run(() => api.reset(conversation.id))}
      />

      <div className="flex flex-col gap-3">
        <h1 className="text-sm font-medium text-muted-foreground">
          {conversation ? `${conversation.lead.givenName}'s phone` : 'No conversation'}
        </h1>
        <Thread messages={conversation?.messages ?? []} pending={pending} thinking={busy} />
        <div className="flex gap-2">
          <Input
            value={draft}
            placeholder={conversation?.terminal ? 'Conversation closed' : 'Reply as the lead'}
            disabled={busy || !conversation || conversation.terminal}
            onChange={(e) => setDraft(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && send()}
          />
          <Button onClick={send} disabled={busy || !conversation || conversation.terminal}>
            Send
          </Button>
        </div>
        {error && <p className="text-sm text-red-600">{error}</p>}
      </div>

      <Inspector conversation={conversation} />
    </div>
  )
}
