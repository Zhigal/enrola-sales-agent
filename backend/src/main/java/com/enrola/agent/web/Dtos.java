package com.enrola.agent.web;

import com.enrola.agent.conversation.Booking;
import com.enrola.agent.conversation.Conversation;
import com.enrola.agent.conversation.Message;
import com.enrola.agent.lead.Lead;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;

public final class Dtos {

    private static final ObjectMapper JSON = new ObjectMapper();

    private Dtos() {}

    public record LeadDto(Long id, String customerId, String givenName, String phone,
                          String state, String email, String currentProvider,
                          String currentPremium) {
        static LeadDto of(Lead lead) {
            return new LeadDto(lead.id(), lead.customerId(), lead.givenName(), lead.phone(),
                    lead.state(), lead.email(), lead.currentProvider(), lead.currentPremium());
        }
    }

    public record MessageDto(Long id, String direction, String body, int characters,
                             String promptVersion, String model, Integer tokensIn,
                             Integer tokensOut, JsonNode structuredOutput, Instant createdAt) {
        static MessageDto of(Message m) {
            return new MessageDto(m.id(), m.direction().name(), m.body(), m.body().length(),
                    m.promptVersion(), m.model(), m.tokensIn(), m.tokensOut(),
                    parse(m.structuredOutput()), m.createdAt());
        }

        private static JsonNode parse(String json) {
            try {
                return json == null ? null : JSON.readTree(json);
            } catch (Exception e) {
                return null;
            }
        }
    }

    public record BookingDto(String calendlyEventId, Instant startTime) {
        static BookingDto of(Booking b) {
            return new BookingDto(b.calendlyEventId(), b.startTime());
        }
    }

    public record ConversationDto(Long id, Long leadId, String customerId, String status,
                                  boolean terminal, int objectionCount, int smsCharLimit,
                                  LeadDto lead, List<MessageDto> messages,
                                  List<BookingDto> bookings) {

        public static ConversationDto of(Conversation c, Lead lead, int smsCharLimit,
                                         List<Message> messages, List<Booking> bookings) {
            return new ConversationDto(c.id(), c.leadId(), c.customerId(), c.status().name(),
                    c.status().isTerminal(), c.objectionCount(), smsCharLimit,
                    LeadDto.of(lead), messages.stream().map(MessageDto::of).toList(),
                    bookings.stream().map(BookingDto::of).toList());
        }
    }

    public record StartRequest(Long leadId) {}

    public record InboundRequest(String body) {}

    public record ErrorResponse(String error, String message) {}
}
