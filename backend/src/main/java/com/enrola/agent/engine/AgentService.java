package com.enrola.agent.engine;

import com.enrola.agent.calendly.StubCalendlyClient;
import com.enrola.agent.conversation.Booking;
import com.enrola.agent.conversation.BookingRepository;
import com.enrola.agent.conversation.Conversation;
import com.enrola.agent.conversation.ConversationRepository;
import com.enrola.agent.conversation.ConversationStatus;
import com.enrola.agent.conversation.Message;
import com.enrola.agent.conversation.MessageDirection;
import com.enrola.agent.conversation.MessageRepository;
import com.enrola.agent.customer.CustomerConfig;
import com.enrola.agent.customer.CustomerRegistry;
import com.enrola.agent.lead.Lead;
import com.enrola.agent.lead.LeadRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentService {

    static final int MAX_TOOL_ROUNDS = 2;

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);
    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(com.fasterxml.jackson.databind.DeserializationFeature
                    .FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final CustomerRegistry customers;
    private final LeadRepository leads;
    private final ConversationRepository conversations;
    private final MessageRepository messages;
    private final BookingRepository bookings;
    private final StubCalendlyClient calendly;
    private final PromptBuilder prompts;
    private final LlmClient llm;
    private final Clock clock;
    private final String model;

    public AgentService(CustomerRegistry customers, LeadRepository leads,
                        ConversationRepository conversations, MessageRepository messages,
                        BookingRepository bookings, StubCalendlyClient calendly,
                        PromptBuilder prompts, LlmClient llm, Clock clock,
                        @Value("${enrola.openai.model}") String model) {
        this.customers = customers;
        this.leads = leads;
        this.conversations = conversations;
        this.messages = messages;
        this.bookings = bookings;
        this.calendly = calendly;
        this.prompts = prompts;
        this.llm = llm;
        this.clock = clock;
        this.model = model;
    }

    @Transactional
    public Conversation start(Long leadId) {
        var lead = leads.findById(leadId).orElseThrow(
                () -> new IllegalArgumentException("Unknown lead: " + leadId));
        var now = clock.instant();
        var conversation = conversations.save(new Conversation(
                null, lead.id(), lead.customerId(), ConversationStatus.ACTIVE, 0, now, now));
        return runTurn(conversation, lead, null, List.of());
    }

    @Transactional
    public Conversation handleInbound(Long conversationId, String body) {
        var conversation = load(conversationId);
        if (conversation.status().isTerminal()) {
            throw new ConversationClosedException(conversationId, conversation.status());
        }
        var lead = leads.findById(conversation.leadId()).orElseThrow();

        // History is read before the inbound is saved, so the current turn's message is not
        // also in the transcript. Saving still happens before the model call, so a model
        // failure leaves a record that the lead texted.
        var history = messages.findByConversationIdOrderByIdAsc(conversation.id());
        messages.save(Message.inbound(conversation.id(), body, clock.instant()));
        return runTurn(conversation, lead, body, history);
    }

    @Transactional
    public Conversation reset(Long conversationId) {
        var conversation = load(conversationId);
        bookings.deleteByConversationId(conversationId);
        messages.deleteByConversationId(conversationId);
        var now = clock.instant();
        var fresh = conversations.save(new Conversation(conversation.id(), conversation.leadId(),
                conversation.customerId(), ConversationStatus.ACTIVE, 0,
                conversation.createdAt(), now));
        var lead = leads.findById(fresh.leadId()).orElseThrow();
        return runTurn(fresh, lead, null, List.of());
    }

    private Conversation load(Long id) {
        return conversations.findById(id).orElseThrow(
                () -> new IllegalArgumentException("Unknown conversation: " + id));
    }

    /** One turn: build input, run the bounded tool loop, persist the outbound message. */
    private Conversation runTurn(Conversation conversation, Lead lead, String inbound,
                                 List<Message> history) {
        var customer = customers.get(conversation.customerId());

        var input = new ArrayList<InputItem>(
                prompts.build(customer, lead, conversation, history, inbound));

        var pending = new PendingBooking();
        var response = callWithTools(customer, lead, conversation, input, pending);
        var turn = parse(response.structuredJson());

        var isFirstOutbound = history.stream()
                .noneMatch(m -> m.direction() == MessageDirection.OUTBOUND);
        var text = finalise(turn, customer, isFirstOutbound);

        messages.save(new Message(null, conversation.id(), MessageDirection.OUTBOUND, text,
                customer.prompt().version(), model, response.tokensIn(), response.tokensOut(),
                response.structuredJson(), clock.instant()));

        if (pending.booking != null) {
            bookings.save(new Booking(null, conversation.id(),
                    customer.calendlyEventId(), pending.booking));
        }
        return conversations.save(nextState(conversation, turn));
    }

    private LlmResponse callWithTools(CustomerConfig customer, Lead lead,
                                      Conversation conversation, List<InputItem> input,
                                      PendingBooking pending) {
        for (var round = 0; ; round++) {
            var response = llm.respond(input);
            if (response.calls().isEmpty()) {
                return response;
            }
            if (round >= MAX_TOOL_ROUNDS) {
                throw new IllegalStateException(
                        "Model exceeded " + MAX_TOOL_ROUNDS + " tool rounds on conversation "
                                + conversation.id());
            }
            for (var call : response.calls()) {
                input.add(call);
                input.add(new InputItem.FunctionCallOutput(
                        call.callId(), dispatch(call, customer, lead, pending)));
            }
        }
    }

    private String dispatch(InputItem.FunctionCall call, CustomerConfig customer,
                            Lead lead, PendingBooking pending) {
        try {
            var args = JSON.readTree(call.argumentsJson());
            return switch (call.name()) {
                case "get_available_times" -> availableTimes(args, customer.timezone());
                case "book_call" -> bookCall(args, customer, lead, pending);
                default -> "{\"error\":\"unknown tool " + call.name() + "\"}";
            };
        } catch (Exception e) {
            log.warn("Tool {} failed: {}", call.name(), e.toString());
            return "{\"error\":\"" + e.getClass().getSimpleName() + "\"}";
        }
    }

    private String availableTimes(JsonNode args, ZoneId timezone) throws Exception {
        var from = Instant.parse(args.get("start_time").asText());
        var to = Instant.parse(args.get("end_time").asText());
        var local = calendly.availableTimes(timezone, from, to).stream()
                .map(slot -> OffsetDateTime.ofInstant(slot, timezone).toString())
                .toList();
        return JSON.writeValueAsString(local);
    }

    private String bookCall(JsonNode args, CustomerConfig customer, Lead lead,
                            PendingBooking pending) throws Exception {
        var start = OffsetDateTime.parse(args.get("start_time").asText()).toInstant();
        var id = calendly.book(customer.calendlyEventId(), args.get("name").asText(),
                args.get("phone").asText(), args.get("email").asText(), start);
        pending.booking = start;
        return JSON.writeValueAsString(java.util.Map.of("id", id));
    }

    private AgentTurn parse(String structuredJson) {
        if (structuredJson == null) {
            throw new IllegalStateException("Model returned no message");
        }
        try {
            return JSON.readValue(structuredJson, AgentTurn.class);
        } catch (Exception e) {
            throw new IllegalStateException("Unparseable model output: " + structuredJson, e);
        }
    }

    /** Extended in Task 9 into the full guardrail chain. */
    private String finalise(AgentTurn turn, CustomerConfig customer, boolean isFirstOutbound) {
        return isFirstOutbound ? turn.message() + Guardrails.OPT_OUT_FOOTER : turn.message();
    }

    /** Extended in Task 9 into the full state machine. */
    private Conversation nextState(Conversation conversation, AgentTurn turn) {
        var now = clock.instant();
        if (turn.goalMet()) {
            return conversation.withStatus(ConversationStatus.GOAL_MET, now);
        }
        return conversation.withStatus(conversation.status(), now);
    }

    private static final class PendingBooking {
        private Instant booking;
    }

    public static class ConversationClosedException extends RuntimeException {
        public ConversationClosedException(Long id, ConversationStatus status) {
            super("Conversation " + id + " is " + status + " and accepts no further messages");
        }
    }
}
