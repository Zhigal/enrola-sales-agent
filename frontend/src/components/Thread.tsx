import { useEffect, useRef } from 'react'
import { ScrollArea } from '@/components/ui/scroll-area'
import type { Message } from '@/types'

interface Props {
  messages: Message[]
  pending: string | null
  thinking: boolean
}

export function Thread({ messages, pending, thinking }: Props) {
  const end = useRef<HTMLDivElement | null>(null)
  // ponytail: braces matter — smooth scrollIntoView returns a Promise in current Chrome, and a
  // concise arrow would hand that Promise to React as the effect's cleanup function.
  useEffect(() => {
    end.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages.length, pending, thinking])

  return (
    <ScrollArea className="h-[70vh] rounded-lg border p-4">
      <div className="flex flex-col gap-3">
        {messages.map((message) => (
          <div
            key={message.id}
            className={`max-w-[80%] whitespace-pre-wrap rounded-2xl px-4 py-2 text-sm ${
              message.direction === 'OUTBOUND'
                ? 'self-start bg-muted'
                : 'self-end bg-blue-600 text-white'
            }`}
          >
            {message.body}
          </div>
        ))}
        {pending && (
          <div className="max-w-[80%] self-end whitespace-pre-wrap rounded-2xl bg-blue-600 px-4 py-2 text-sm text-white opacity-60">
            {pending}
          </div>
        )}
        {thinking && (
          <div className="flex gap-1 self-start rounded-2xl bg-muted px-4 py-3" aria-label="Typing">
            {[0, 150, 300].map((delay) => (
              <span
                key={delay}
                className="h-2 w-2 animate-bounce rounded-full bg-muted-foreground"
                style={{ animationDelay: `${delay}ms` }}
              />
            ))}
          </div>
        )}
        <div ref={end} />
      </div>
    </ScrollArea>
  )
}
