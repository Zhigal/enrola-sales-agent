package com.enrola.agent.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.enrola.agent.DbTest;
import com.enrola.agent.calendly.RecordingCalendlyClient;
import com.enrola.agent.conversation.ConversationRepository;
import com.enrola.agent.conversation.ConversationStatus;
import com.enrola.agent.conversation.MessageDirection;
import com.enrola.agent.conversation.MessageRepository;
import com.enrola.agent.conversation.BookingRepository;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class AgentServiceTest extends DbTest {

    // NOW and the scripted model both come from DbTest.Stubs.

    @Autowired AgentService agent;
    @Autowired ScriptedLlmClient llm;
    @Autowired RecordingCalendlyClient calendly;
    @Autowired ConversationRepository conversations;
    @Autowired MessageRepository messages;
    @Autowired BookingRepository bookings;

    @BeforeEach
    void resetStub() {
        llm.reset();
        calendly.reset();
    }

    private static String turn(String message, Stage stage) {
        return """
            {"message":"%s","stage":"%s","goalMet":false,"unsubscribed":false,
             "endConversation":false,"endReason":"NONE","objectionRaised":false}
            """.formatted(message, stage);
    }

    @Test
    void startSendsAnOpeningMessageWithTheOptOutFooter() {
        llm.queue(LlmResponse.message(turn("Are you looking to save money or improve your cover?",
                Stage.SITUATION)));

        var conversation = agent.start(1L);
        var sent = messages.findByConversationIdOrderByIdAsc(conversation.id());

        assertThat(sent).singleElement().satisfies(m -> {
            assertThat(m.direction()).isEqualTo(MessageDirection.OUTBOUND);
            assertThat(m.body()).endsWith("Reply 'stop' to opt out");
            assertThat(m.promptVersion()).startsWith("system-v1@");
            assertThat(m.structuredOutput()).contains("SITUATION");
        });
        assertThat(conversation.status()).isEqualTo(ConversationStatus.ACTIVE);
    }

    @Test
    void theFooterIsOnTheFirstMessageOnly() {
        llm.queue(LlmResponse.message(turn("Opening question?", Stage.SITUATION)),
                  LlmResponse.message(turn("Second question?", Stage.PREFERENCE)));

        var conversation = agent.start(1L);
        agent.handleInbound(conversation.id(), "both");

        var outbound = messages.findByConversationIdOrderByIdAsc(conversation.id()).stream()
                .filter(m -> m.direction() == MessageDirection.OUTBOUND).toList();

        assertThat(outbound).hasSize(2);
        assertThat(outbound.get(1).body()).doesNotContain("Reply 'stop'");
    }

    @Test
    void inboundIsPersistedBeforeTheModelIsCalled() {
        llm.queue(LlmResponse.message(turn("Opening question?", Stage.SITUATION)),
                  LlmResponse.message(turn("Fair enough. Hospital or extras?", Stage.PREFERENCE)));

        var conversation = agent.start(1L);
        agent.handleInbound(conversation.id(), "all of the above");

        assertThat(messages.findByConversationIdOrderByIdAsc(conversation.id()))
                .extracting(m -> m.direction() + ":" + m.body().split("\\R")[0])
                .containsExactly(
                        "OUTBOUND:Opening question?",
                        "INBOUND:all of the above",
                        "OUTBOUND:Fair enough. Hospital or extras?");
    }

    @Test
    void toolCallsAreDispatchedAndTheBookingIsPersisted() {
        var slotIso = "2026-08-06T09:00:00+08:00"; // Perth, per customer.yaml
        llm.queue(
                LlmResponse.message(turn("Opening question?", Stage.SITUATION)),
                LlmResponse.toolCalls(new InputItem.FunctionCall("c1", "get_available_times",
                        "{\"start_time\":\"2026-08-05T00:00:00Z\",\"end_time\":\"2026-08-09T00:00:00Z\"}")),
                // Time only - the tool schema no longer asks the model for an identity.
                LlmResponse.toolCalls(new InputItem.FunctionCall("c2", "book_call",
                        "{\"start_time\":\"" + slotIso + "\"}")),
                LlmResponse.message("""
                    {"message":"Booked - Thursday 6 August at 9:00 AM.","stage":"CONFIRM",
                     "goalMet":true,"unsubscribed":false,"endConversation":true,
                     "endReason":"BOOKED","objectionRaised":false}
                    """));

        var conversation = agent.start(1L);
        agent.handleInbound(conversation.id(), "tomorrow morning works");

        assertThat(bookings.findByConversationId(conversation.id())).singleElement()
                .satisfies(b -> {
                    assertThat(b.calendlyEventId()).isEqualTo("evt_stub_comparato");
                    assertThat(b.startTime()).isEqualTo(Instant.parse("2026-08-06T01:00:00Z"));
                });
        assertThat(conversations.findById(conversation.id()).orElseThrow().status())
                .isEqualTo(ConversationStatus.GOAL_MET);
    }

    /**
     * The security property behind shrinking the book_call schema. The model here supplies an
     * identity anyway - which is exactly what a successful prompt injection would produce, since
     * the transcript is attacker-controlled text and the model is the thing reading it. The
     * booking must still go to the lead on file. An injection may move the appointment; it must
     * not redirect the invite.
     */
    @Test
    void theBookingUsesTheLeadOnFileAndNotTheIdentityTheModelSupplied() {
        var slotIso = "2026-08-06T09:00:00+08:00";
        llm.queue(
                LlmResponse.message(turn("Opening question?", Stage.SITUATION)),
                LlmResponse.toolCalls(new InputItem.FunctionCall("c1", "book_call",
                        ("{\"name\":\"Mallory\",\"phone\":\"+61400000000\","
                         + "\"email\":\"attacker@evil.example\",\"start_time\":\""
                         + slotIso + "\"}"))),
                LlmResponse.message(turn("Booked.", Stage.CONFIRM)));

        var conversation = agent.start(1L); // John, per data.sql
        agent.handleInbound(conversation.id(), "book me in");

        assertThat(calendly.lastBooking()).isNotNull().satisfies(b -> {
            assertThat(b.email()).isEqualTo("john@example.com");
            assertThat(b.phone()).isEqualTo("+61457099876");
            assertThat(b.name()).isEqualTo("John");
            // The one thing the model is still trusted with.
            assertThat(b.startTime()).isEqualTo(Instant.parse("2026-08-06T01:00:00Z"));
        });
    }

    /**
     * The number the prompt promises the model and the number the guardrail enforces must be the
     * same number, on the same message. They are computed in two different classes, so nothing
     * but this test stops them drifting - and the drift is silent: the model writes to the budget
     * it was told about and the guardrail truncates it for exceeding one it was not.
     *
     * Pinned from both sides on the first outbound message, where the mandatory opt-out footer
     * eats into the limit. Exactly the promised length must go out untouched; one character more
     * must trigger the regenerate. Assert only the first half and a too-generous promise still
     * passes.
     */
    @Test
    void thePromisedCharacterLimitIsTheOneTheGuardrailEnforces() {
        llm.queue(LlmResponse.message(turn("Opening question?", Stage.SITUATION)));
        var id = agent.start(1L).id();
        var promised = promisedLimit();

        // reset() wipes the thread, so its opener is a first outbound again: same footer,
        // same budget, same promise.
        var exact = "a".repeat(promised);
        llm.reset();
        llm.queue(LlmResponse.message(turn(exact, Stage.SITUATION)));
        agent.reset(id);

        assertThat(promisedLimit()).isEqualTo(promised);
        assertThat(llm.callCount()).isEqualTo(1); // no regenerate: it was inside the budget
        assertThat(lastOutbound(id)).isEqualTo(exact + Guardrails.OPT_OUT_FOOTER);

        var overBy1 = "b".repeat(promised + 1);
        llm.reset();
        llm.queue(LlmResponse.message(turn(overBy1, Stage.SITUATION)),
                  LlmResponse.message(turn("Short enough.", Stage.SITUATION)));
        agent.reset(id);

        assertThat(llm.callCount()).isEqualTo(2); // one over the promise is one over the limit
        assertThat(lastOutbound(id)).isEqualTo("Short enough." + Guardrails.OPT_OUT_FOOTER);
    }

    /** The limit as the model was told it, read back out of the prompt it actually received. */
    private int promisedLimit() {
        var prompt = llm.lastInput().stream()
                .filter(InputItem.Text.class::isInstance)
                .map(i -> ((InputItem.Text) i).content())
                .collect(java.util.stream.Collectors.joining("\n"));
        var matcher = java.util.regex.Pattern
                .compile("Character limit for each message: (\\d+)").matcher(prompt);
        assertThat(matcher.find()).as("runtime context states a character limit").isTrue();
        return Integer.parseInt(matcher.group(1));
    }

    private String lastOutbound(Long id) {
        return messages.findByConversationIdOrderByIdAsc(id).stream()
                .filter(m -> m.direction() == MessageDirection.OUTBOUND)
                .map(m -> m.body()).reduce((a, b) -> b).orElseThrow();
    }

    @Test
    void theModelSeesTheAvailableTimesItAskedFor() {
        llm.queue(
                LlmResponse.message(turn("Opening question?", Stage.SITUATION)),
                LlmResponse.toolCalls(new InputItem.FunctionCall("c1", "get_available_times",
                        "{\"start_time\":\"2026-08-05T00:00:00Z\",\"end_time\":\"2026-08-07T00:00:00Z\"}")),
                LlmResponse.message(turn("I have Thursday 9:00 or 9:30. Either work?",
                        Stage.OFFER_TIMES)));

        var conversation = agent.start(1L);
        agent.handleInbound(conversation.id(), "tomorrow morning");

        var toolOutput = llm.lastInput().stream()
                .filter(InputItem.FunctionCallOutput.class::isInstance)
                .map(InputItem.FunctionCallOutput.class::cast)
                .findFirst().orElseThrow();

        assertThat(toolOutput.outputJson()).contains("+08:00").doesNotContain("T10:00");
    }

    @Test
    void resetWipesTheThreadAndSendsAFreshOpener() {
        llm.queue(LlmResponse.message(turn("Opening question?", Stage.SITUATION)),
                  LlmResponse.message(turn("Second question?", Stage.PREFERENCE)),
                  LlmResponse.message(turn("Opening question again?", Stage.SITUATION)));

        var conversation = agent.start(1L);
        agent.handleInbound(conversation.id(), "yes");
        agent.reset(conversation.id());

        var after = messages.findByConversationIdOrderByIdAsc(conversation.id());
        assertThat(after).singleElement().satisfies(m -> {
            assertThat(m.body()).startsWith("Opening question again?");
            // A reset restarts the conversation, so the fresh opener is a first message and
            // carries the footer again. Asserted rather than left to startsWith.
            assertThat(m.body()).endsWith("Reply 'stop' to opt out");
        });
        assertThat(conversations.findById(conversation.id()).orElseThrow())
                .satisfies(c -> {
                    assertThat(c.status()).isEqualTo(ConversationStatus.ACTIVE);
                    assertThat(c.objectionCount()).isZero();
                });
    }

    @Test
    void aRunawayToolLoopIsBounded() {
        var call = new InputItem.FunctionCall("c1", "get_available_times",
                "{\"start_time\":\"2026-08-05T00:00:00Z\",\"end_time\":\"2026-08-07T00:00:00Z\"}");
        llm.queue(LlmResponse.message(turn("Opening question?", Stage.SITUATION)),
                  LlmResponse.toolCalls(call), LlmResponse.toolCalls(call),
                  LlmResponse.toolCalls(call));

        var conversation = agent.start(1L);

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> agent.handleInbound(conversation.id(), "when?"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tool rounds");
        assertThat(llm.callCount()).isEqualTo(4); // opener + 3 bounded rounds
    }
}
