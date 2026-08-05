package com.enrola.agent.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enrola.agent.DbTest;
import com.enrola.agent.conversation.ConversationRepository;
import com.enrola.agent.conversation.ConversationStatus;
import com.enrola.agent.conversation.MessageDirection;
import com.enrola.agent.conversation.MessageRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class GuardrailFlowTest extends DbTest {

    @Autowired AgentService agent;
    @Autowired ScriptedLlmClient llm;
    @Autowired ConversationRepository conversations;
    @Autowired MessageRepository messages;

    @BeforeEach
    void resetStub() {
        llm.reset();
    }

    private static String turn(String message) {
        return """
            {"message":"%s","stage":"SITUATION","goalMet":false,"unsubscribed":false,
             "endConversation":false,"endReason":"NONE","objectionRaised":false}
            """.formatted(message);
    }

    private Long startedConversation() {
        llm.queue(LlmResponse.message(turn("Opening question?")));
        return agent.start(1L).id();
    }

    private List<String> outbound(Long id) {
        return messages.findByConversationIdOrderByIdAsc(id).stream()
                .filter(m -> m.direction() == MessageDirection.OUTBOUND)
                .map(m -> m.body()).toList();
    }

    @Test
    void fastOptOutSendsTheCannedReplyWithoutCallingTheModel() {
        var id = startedConversation();
        var callsAfterOpener = llm.callCount();

        agent.handleInbound(id, "STOP");

        assertThat(llm.callCount()).isEqualTo(callsAfterOpener);
        assertThat(outbound(id)).last().isEqualTo(Guardrails.OPT_OUT_REPLY);
        assertThat(conversations.findById(id).orElseThrow().status())
                .isEqualTo(ConversationStatus.UNSUBSCRIBED);
    }

    @Test
    void fuzzyOptOutDiscardsTheModelMessageAndSendsTheSameCannedReply() {
        var id = startedConversation();
        llm.queue(LlmResponse.message("""
            {"message":"No worries at all. Before you go - can I ask what put you off?",
             "stage":"CLOSED","goalMet":false,"unsubscribed":true,"endConversation":true,
             "endReason":"UNSUBSCRIBED","objectionRaised":false}
            """));

        agent.handleInbound(id, "take me off this list");

        assertThat(outbound(id)).last().isEqualTo(Guardrails.OPT_OUT_REPLY);
        assertThat(outbound(id)).last().asString().doesNotContain("what put you off");
        assertThat(conversations.findById(id).orElseThrow().status())
                .isEqualTo(ConversationStatus.UNSUBSCRIBED);

        // The text is discarded; the attribution is not. Which prompt version set the flag is
        // the only thing that makes the fuzzy path reviewable later.
        var recorded = messages.findByConversationIdOrderByIdAsc(id).getLast();
        assertThat(recorded.promptVersion()).startsWith("system-v1@");
        assertThat(recorded.structuredOutput()).contains("\"unsubscribed\":true");
    }

    @Test
    void bothOptOutPathsProduceByteIdenticalText() {
        var fast = startedConversation();
        agent.handleInbound(fast, "unsubscribe");

        llm.reset();
        var fuzzy = startedConversation();
        llm.queue(LlmResponse.message("""
            {"message":"Sure thing, sorry to bother you. Anything else?","stage":"CLOSED",
             "goalMet":false,"unsubscribed":true,"endConversation":true,
             "endReason":"UNSUBSCRIBED","objectionRaised":false}
            """));
        agent.handleInbound(fuzzy, "lose my number");

        assertThat(outbound(fast).getLast()).isEqualTo(outbound(fuzzy).getLast());
    }

    @Test
    void anOverlongMessageIsRegeneratedOnceAndThenTruncated() {
        var id = startedConversation();
        var tooLong = "x".repeat(400);
        llm.queue(LlmResponse.message(turn(tooLong)), LlmResponse.message(turn(tooLong)));

        agent.handleInbound(id, "tell me everything");

        var sent = outbound(id).getLast();
        assertThat(sent.length()).isLessThanOrEqualTo(320);
        assertThat(llm.callCount()).isEqualTo(3); // opener, first attempt, one regenerate
    }

    @Test
    void aSuccessfulRegenerateIsUsedAsIs() {
        var id = startedConversation();
        llm.queue(LlmResponse.message(turn("y".repeat(400))),
                  LlmResponse.message(turn("Short enough now.")));

        agent.handleInbound(id, "tell me everything");

        assertThat(outbound(id)).last().isEqualTo("Short enough now.");

        // The persisted audit trail must describe the message that was actually sent, not the
        // discarded over-length attempt.
        var recorded = messages.findByConversationIdOrderByIdAsc(id).getLast();
        assertThat(recorded.structuredOutput()).contains("Short enough now.");
    }

    @Test
    void afterTheGoalIsMetExactlyOneMoreMessageGoesOutThenItIsTerminal() {
        var id = startedConversation();

        // One objection first, so the conversation carries objectionCount 1 into the booking.
        llm.queue(LlmResponse.message("""
            {"message":"Are you sure I can't change your mind?","stage":"SUGGEST_CALL",
             "goalMet":false,"unsubscribed":false,"endConversation":false,"endReason":"NONE",
             "objectionRaised":true}
            """));
        agent.handleInbound(id, "not really interested");
        assertThat(conversations.findById(id).orElseThrow().objectionCount()).isEqualTo(1);

        llm.queue(LlmResponse.message("""
            {"message":"Booked - Thursday 6 August at 9:00 AM.","stage":"CONFIRM","goalMet":true,
             "unsubscribed":false,"endConversation":true,"endReason":"BOOKED",
             "objectionRaised":false}
            """));
        agent.handleInbound(id, "go on then, 9am works");
        assertThat(conversations.findById(id).orElseThrow().status())
                .isEqualTo(ConversationStatus.GOAL_MET);

        // The final grumble carries objectionRaised, taking the count from 1 to 2 - past the
        // backstop threshold. That is what makes this test discriminate: with the objection
        // check placed above the goodbye-loop check, this turn lands in ENDED_GIVE_UP and the
        // assertion below fails. Without the earlier objection the count only reaches 1, the
        // backstop never fires either way, and the two orderings are indistinguishable.
        llm.queue(LlmResponse.message("""
            {"message":"No worries.","stage":"CLOSED","goalMet":true,"unsubscribed":false,
             "endConversation":true,"endReason":"BOOKED","objectionRaised":true}
            """));
        agent.handleInbound(id, "thank you! bit of a hassle though");
        assertThat(conversations.findById(id).orElseThrow())
                .satisfies(c -> {
                    assertThat(c.objectionCount()).isEqualTo(2);
                    assertThat(c.status()).isEqualTo(ConversationStatus.GOAL_MET_CLOSED);
                });

        assertThatThrownBy(() -> agent.handleInbound(id, "you too!"))
                .isInstanceOf(AgentService.ConversationClosedException.class);
        assertThat(outbound(id)).hasSize(4); // opener, one push-back, confirmation, closing line
    }

    @Test
    void abuseEndsTheConversation() {
        var id = startedConversation();
        llm.queue(LlmResponse.message("""
            {"message":"I'll leave it there.","stage":"CLOSED","goalMet":false,
             "unsubscribed":false,"endConversation":true,"endReason":"ABUSE",
             "objectionRaised":false}
            """));

        agent.handleInbound(id, "[abusive message]");

        assertThat(conversations.findById(id).orElseThrow().status())
                .isEqualTo(ConversationStatus.ENDED_ABUSE);
        assertThatThrownBy(() -> agent.handleInbound(id, "hello?"))
                .isInstanceOf(AgentService.ConversationClosedException.class);
    }

    @Test
    void aSecondObjectionEndsTheConversationEvenIfTheModelKeepsPushing() {
        var id = startedConversation();
        var objecting = """
            {"message":"Are you sure I can't change your mind?","stage":"SUGGEST_CALL",
             "goalMet":false,"unsubscribed":false,"endConversation":false,"endReason":"NONE",
             "objectionRaised":true}
            """;
        llm.queue(LlmResponse.message(objecting), LlmResponse.message(objecting));

        agent.handleInbound(id, "not interested");
        assertThat(conversations.findById(id).orElseThrow())
                .satisfies(c -> {
                    assertThat(c.objectionCount()).isEqualTo(1);
                    assertThat(c.status()).isEqualTo(ConversationStatus.ACTIVE);
                });

        agent.handleInbound(id, "still no");

        assertThat(conversations.findById(id).orElseThrow())
                .satisfies(c -> {
                    assertThat(c.objectionCount()).isEqualTo(2);
                    assertThat(c.status()).isEqualTo(ConversationStatus.ENDED_GIVE_UP);
                });
    }

    /**
     * The ordering property. Raw the body is 300 characters and legal; normalised it is 340 and
     * is not, because each ellipsis becomes three characters. If normalisation ran after the
     * limit check this turn would sail through and a 340-character body would be persisted.
     */
    @Test
    void aBodyThatOnlyGoesOverBudgetOnceNormalisedIsStillCaughtByTheLimit() {
        var id = startedConversation();
        var ellipsisHeavy = "x".repeat(280) + "\u2026".repeat(20);
        assertThat(ellipsisHeavy).hasSize(300); // under the 320 budget before normalisation
        assertThat(Guardrails.toGsm7(ellipsisHeavy)).hasSize(340); // over it after

        llm.queue(LlmResponse.message(turn(ellipsisHeavy)),
                  LlmResponse.message(turn("Short enough now.")));

        agent.handleInbound(id, "tell me everything");

        assertThat(outbound(id)).last().isEqualTo("Short enough now.");
        assertThat(llm.callCount()).isEqualTo(3); // opener, over-budget attempt, one regenerate
    }

    @Test
    void typographicPunctuationNeverReachesThePersistedBody() {
        llm.queue(LlmResponse.message(
                turn("Hi John, it\u2019s Anna \u2014 we\u2019ve found better value\u2026 free today?")));
        var id = agent.start(1L).id();

        var body = outbound(id).getLast();
        assertThat(body).doesNotContain("\u2019").contains("it's Anna").contains("Anna - we've");
        assertThat(body).matches("\\A\\p{ASCII}*\\z"); // the transcripts must stay pure ASCII

        // Normalisation happened before the length was taken, so the recorded length is the
        // length of what went out - ellipsis expansion included.
        var recorded = messages.findByConversationIdOrderByIdAsc(id).getLast();
        assertThat(recorded.body()).isEqualTo(body).endsWith(Guardrails.OPT_OUT_FOOTER);
    }
}
