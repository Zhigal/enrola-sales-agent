import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import type { Lead } from '@/types'

interface Props {
  leads: Lead[]
  selectedId: number | null
  onSelect: (lead: Lead) => void
  onReset: () => void
  busy: boolean
}

export function LeadPicker({ leads, selectedId, onSelect, onReset, busy }: Props) {
  return (
    <div className="flex flex-col gap-3">
      <h2 className="text-sm font-medium text-muted-foreground">You are texting as</h2>
      {leads.map((lead) => (
        <Card
          key={lead.id}
          // ponytail: a click while a turn is in flight starts a second conversation for the
          // lead - start() only resumes what is already committed, and the turn holds its
          // transaction open for the whole model call. A unique index on lead_id if this ever
          // needs to hold for two browsers.
          onClick={() => {
            if (!busy) onSelect(lead)
          }}
          aria-current={lead.id === selectedId}
          className={`p-3 text-sm ${busy ? 'opacity-60' : 'cursor-pointer'} ${
            lead.id === selectedId
              ? 'border-foreground bg-accent ring-2 ring-foreground'
              : 'hover:bg-accent/50'
          }`}
        >
          <div className="font-medium">
            {lead.givenName} · {lead.state}
          </div>
          <div className="text-muted-foreground">
            {lead.currentProvider ?? 'no current insurer'}
            {lead.currentPremium ? ` · ${lead.currentPremium}/mo` : ''}
          </div>
        </Card>
      ))}
      <Button variant="outline" onClick={onReset} disabled={busy || selectedId === null}>
        Reset conversation
      </Button>
    </div>
  )
}
