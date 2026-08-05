export type Direction = 'INBOUND' | 'OUTBOUND'

export type Stage =
  | 'SITUATION' | 'PREFERENCE' | 'SUGGEST_CALL' | 'OFFER_TIMES' | 'CONFIRM' | 'CLOSED'

export type EndReason = 'NONE' | 'BOOKED' | 'UNSUBSCRIBED' | 'ABUSE' | 'GAVE_UP'

export interface StructuredOutput {
  message: string
  stage: Stage
  goalMet: boolean
  unsubscribed: boolean
  endConversation: boolean
  endReason: EndReason
  objectionRaised: boolean
}

export interface Lead {
  id: number
  customerId: string
  givenName: string
  phone: string
  state: string
  email: string
  currentProvider: string | null
  currentPremium: string | null
}

export interface Message {
  id: number
  direction: Direction
  body: string
  characters: number
  promptVersion: string | null
  model: string | null
  tokensIn: number | null
  tokensOut: number | null
  structuredOutput: StructuredOutput | null
  createdAt: string
}

export interface Booking {
  calendlyEventId: string
  startTime: string
}

export interface Conversation {
  id: number
  leadId: number
  customerId: string
  status: string
  terminal: boolean
  objectionCount: number
  smsCharLimit: number
  lead: Lead
  messages: Message[]
  bookings: Booking[]
}

export interface ApiError {
  error: string
  message: string
}
