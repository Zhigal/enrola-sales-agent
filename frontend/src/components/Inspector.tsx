import { Badge } from '@/components/ui/badge'
import { Card } from '@/components/ui/card'
import { ScrollArea } from '@/components/ui/scroll-area'
import type { Conversation } from '@/types'

export function Inspector({ conversation }: { conversation: Conversation | null }) {
  if (!conversation) {
    return <p className="text-sm text-muted-foreground">Pick a lead to start.</p>
  }
  const turns = conversation.messages.filter((m) => m.structuredOutput !== null)

  return (
    <div className="flex flex-col gap-3">
      <h2 className="text-sm font-medium text-muted-foreground">
        What the platform receives
      </h2>
      <div className="flex flex-wrap gap-2">
        <Badge variant={conversation.terminal ? 'destructive' : 'default'}>
          {conversation.status}
        </Badge>
        <Badge variant="outline">objections {conversation.objectionCount}</Badge>
        {conversation.bookings.map((booking) => (
          <Badge key={booking.startTime} variant="secondary">
            booked {new Date(booking.startTime).toLocaleString()}
          </Badge>
        ))}
      </div>
      <ScrollArea className="h-[62vh]">
        <div className="flex flex-col gap-3">
          {[...turns].reverse().map((message) => (
            <Card key={message.id} className="p-3">
              <div className="mb-2 flex justify-between text-xs text-muted-foreground">
                <span>{message.promptVersion}</span>
                <span
                  className={
                    message.characters > conversation.smsCharLimit ? 'text-red-600' : ''
                  }
                >
                  {message.characters}/{conversation.smsCharLimit} chars
                </span>
              </div>
              <pre className="overflow-x-auto text-xs">
                {JSON.stringify(message.structuredOutput, null, 2)}
              </pre>
              <div className="mt-2 text-xs text-muted-foreground">
                {/* Seeded messages carry no token counts - inventing them would make a
                    fabricated number indistinguishable from a measured one. */}
                {message.model}
                {message.tokensIn !== null && message.tokensOut !== null
                  && ` · ${message.tokensIn}/${message.tokensOut} tokens`}
              </div>
            </Card>
          ))}
        </div>
      </ScrollArea>
    </div>
  )
}
