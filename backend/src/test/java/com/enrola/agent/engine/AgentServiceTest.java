package com.enrola.agent.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.enrola.agent.DbTest;
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
    @Autowired ConversationRepository conversations;
    @Autowired MessageRepository messages;
    @Autowired BookingRepository bookings;

    @BeforeEach
    void resetStub() {
        llm.reset();
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
                LlmResponse.toolCalls(new InputItem.FunctionCall("c2", "book_call",
                        ("{\"name\":\"John\",\"phone\":\"+61457099876\","
                         + "\"email\":\"john@example.com\",\"start_time\":\"" + slotIso + "\"}"))),
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
