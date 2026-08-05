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

        if (Guardrails.isExactOptOut(body)) {
            return sendOptOutConfirmation(conversation);
        }
        return runTurn(conversation, lead, body, history);
    }

    /** Fast path: zero tokens, nothing to attribute. */
    private Conversation sendOptOutConfirmation(Conversation conversation) {
        return sendOptOutConfirmation(conversation, null, null, null);
    }

    /**
     * Both paths send byte-identical text. They differ in what is recorded: on the fuzzy path
     * the model's flag is what fired the guardrail, so the prompt version, model and the
     * discarded structured output are kept. Without them there is no evidence for whether the
     * model got that flag right, which is the only question worth asking about the fuzzy path.
     */
    private Conversation sendOptOutConfirmation(Conversation conversation, String promptVersion,
                                                String modelId, LlmResponse response) {
        var now = clock.instant();
        messages.save(new Message(null, conversation.id(), MessageDirection.OUTBOUND,
                Guardrails.OPT_OUT_REPLY, promptVersion, modelId,
                response == null ? null : response.tokensIn(),
                response == null ? null : response.tokensOut(),
                response == null ? null : response.structuredJson(), now));
        return conversations.save(
                conversation.withStatus(ConversationStatus.UNSUBSCRIBED, now));
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

        // Fuzzy opt-out: the model's message is discarded outright. One approved wording exists,
        // and the prompt tells the agent to end every message with a question - which is exactly
        // wrong immediately after someone asks to be left alone.
        if (turn.unsubscribed()) {
            return sendOptOutConfirmation(
                    conversation, customer.prompt().version(), model, response);
        }

        var isFirstOutbound = history.stream()
                .noneMatch(m -> m.direction() == MessageDirection.OUTBOUND);
        var footer = isFirstOutbound ? Guardrails.OPT_OUT_FOOTER : "";
        var budget = customer.smsCharLimit() - footer.length();

        var sent = turn.message().length() <= budget
                ? new Sent(turn.message(), turn, response)
                : regenerateShorter(input, turn, response, budget, conversation);
        var text = sent.body() + footer;

        messages.save(new Message(null, conversation.id(), MessageDirection.OUTBOUND, text,
                customer.prompt().version(), model, sent.response().tokensIn(),
                sent.response().tokensOut(), sent.response().structuredJson(), clock.instant()));

        if (pending.booking != null) {
            bookings.save(new Booking(null, conversation.id(),
                    customer.calendlyEventId(), pending.booking));
        }
        return conversations.save(nextState(conversation, sent.turn()));
    }

    /**
     * What actually went out, and the model output that produced it. These travel together
     * because a successful regenerate replaces the message: persisting the first attempt's
     * structured output alongside the second attempt's text would leave an audit trail
     * describing a message that was never sent - and that record is what a compliance review
     * or a prompt-version comparison would be reading.
     */
    private record Sent(String body, AgentTurn turn, LlmResponse response) {}

    /** One nudge, then a hard truncation. The log line is the eval signal that the prompt drifted. */
    private Sent regenerateShorter(List<InputItem> input, AgentTurn original,
                                   LlmResponse originalResponse, int budget,
                                   Conversation conversation) {
        input.add(InputItem.developer(("Your last message was %d characters. The hard limit is %d. "
                + "Send the same thing, shorter.").formatted(original.message().length(), budget)));
        try {
            var retry = llm.respond(input);
            if (retry.structuredJson() != null) {
                var retryTurn = parse(retry.structuredJson());
                if (retryTurn.message().length() <= budget) {
                    return new Sent(retryTurn.message(), retryTurn, retry);
                }
            }
        } catch (RuntimeException e) {
            log.warn("Regenerate failed on conversation {}: {}", conversation.id(), e.toString());
        }
        log.warn("GUARDRAIL truncate: conversation {} message was {} chars, limit {}. "
                + "The prompt needs work.", conversation.id(), original.message().length(), budget);
        // Truncation keeps the original output: the text is a prefix of what it described.
        return new Sent(Guardrails.truncateAtSentence(original.message(), budget),
                original, originalResponse);
    }

    /**
     * Order matters, and every position here is a decision:
     *
     * 1. Abuse wins outright. Nothing else about the turn changes the answer.
     * 2. The goodbye-loop guard sits above both the objection counter and goalMet. Above
     *    goalMet because the model keeps reporting goalMet true on every turn after the
     *    booking, so testing goalMet first would hold the conversation in GOAL_MET forever
     *    and never close it. Above the objection counter because a booked call that then
     *    draws a grumble is still a booked call - landing it in ENDED_GIVE_UP would misreport
     *    the outcome to the platform.
     * 3. The objection backstop, so the model cannot push a third time.
     */
    private Conversation nextState(Conversation conversation, AgentTurn turn) {
        var now = clock.instant();
        var objections = conversation.objectionCount() + (turn.objectionRaised() ? 1 : 0);
        var updated = conversation.withObjectionCount(objections, now);

        if (turn.endReason() == EndReason.ABUSE) {
            return updated.withStatus(ConversationStatus.ENDED_ABUSE, now);
        }
        if (conversation.status() == ConversationStatus.GOAL_MET) {
            return updated.withStatus(ConversationStatus.GOAL_MET_CLOSED, now);
        }
        if (objections >= 2) {
            return updated.withStatus(ConversationStatus.ENDED_GIVE_UP, now);
        }
        if (turn.goalMet()) {
            return updated.withStatus(ConversationStatus.GOAL_MET, now);
        }
        if (turn.endConversation()) {
            return updated.withStatus(ConversationStatus.ENDED_GIVE_UP, now);
        }
        return updated.withStatus(ConversationStatus.ACTIVE, now);
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

    private static final class PendingBooking {
        private Instant booking;
    }

    public static class ConversationClosedException extends RuntimeException {
        public ConversationClosedException(Long id, ConversationStatus status) {
            super("Conversation " + id + " is " + status + " and accepts no further messages");
        }
    }
}
