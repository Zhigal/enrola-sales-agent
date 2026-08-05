package com.enrola.agent.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import com.enrola.agent.DbTest;
import com.enrola.agent.engine.Guardrails;
import com.enrola.agent.lead.LeadRepository;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class RepositoryTest extends DbTest {

    @Autowired LeadRepository leads;
    @Autowired ConversationRepository conversations;
    @Autowired MessageRepository messages;
    @Autowired BookingRepository bookings;

    @Test
    void seedsThreeLeadShapes() {
        var all = leads.findByCustomerId("comparato");
        assertThat(all).hasSize(3);
        assertThat(all).anyMatch(l -> l.currentProvider() != null && l.currentPremium() != null);
        assertThat(all).anyMatch(l -> l.currentProvider() != null && l.currentPremium() == null);
        assertThat(all).anyMatch(l -> l.currentProvider() == null);
    }

    /**
     * The demo without an API key. data.sql seeds a finished thread for lead 1, and start()
     * resumes it, so this is what a reviewer sees on their first click.
     */
    @Test
    void seedsJohnsCompletedConversation() {
        var seeded = conversations.findFirstByLeadIdOrderByIdDesc(1L).orElseThrow();
        assertThat(seeded.status()).isEqualTo(ConversationStatus.GOAL_MET);
        assertThat(seeded.objectionCount()).isZero();

        var thread = messages.findByConversationIdOrderByIdAsc(seeded.id());
        assertThat(thread).extracting(Message::direction).containsExactly(
                MessageDirection.OUTBOUND, MessageDirection.INBOUND,
                MessageDirection.OUTBOUND, MessageDirection.INBOUND,
                MessageDirection.OUTBOUND, MessageDirection.INBOUND,
                MessageDirection.OUTBOUND, MessageDirection.INBOUND,
                MessageDirection.OUTBOUND);
        assertThat(thread.getFirst().body())
                .startsWith("Hi John, it's Anna from Comparato")
                // The footer's blank line has to survive the SQL literal, or the seeded opener
                // is not the message that actually went out.
                .endsWith(Guardrails.OPT_OUT_FOOTER);
        assertThat(thread.getLast().body()).isEqualTo(
                "You're booked for Thursday 6 August at 9:00am. The call takes about 15 minutes.");

        // The inspector column renders these three, so an empty one is a blank demo.
        assertThat(thread.getFirst().promptVersion()).startsWith("system-v1@");
        assertThat(thread.getFirst().model()).isNotBlank();
        assertThat(thread.getLast().structuredOutput()).contains("\"endReason\":\"BOOKED\"");

        assertThat(bookings.findByConversationId(seeded.id())).singleElement().satisfies(b -> {
            assertThat(b.calendlyEventId()).isEqualTo("evt_stub_comparato");
            assertThat(b.startTime()).isEqualTo(Instant.parse("2026-08-06T01:00:00Z"));
        });
    }

    @Test
    void objectionCountSurvivesAReload() {
        var now = Instant.parse("2026-08-05T00:00:00Z");
        // Lead 3, not lead 1: lead 1 owns the seeded conversation the test above looks up.
        var saved = conversations.save(new Conversation(
                null, 3L, "comparato", ConversationStatus.ACTIVE, 0, now, now));

        conversations.save(saved.withObjectionCount(1, now));

        assertThat(conversations.findById(saved.id()).orElseThrow().objectionCount()).isEqualTo(1);
    }

    @Test
    void messagesComeBackInOrder() {
        var now = Instant.parse("2026-08-05T00:00:00Z");
        var c = conversations.save(new Conversation(
                null, 2L, "comparato", ConversationStatus.ACTIVE, 0, now, now));
        messages.save(Message.inbound(c.id(), "first", now));
        // Deliberately backdated. Inserted sequentially, id order and created_at order coincide,
        // so the test could not tell OrderByIdAsc from OrderByCreatedAtAsc - and it is id order
        // that is the contract, because ids are the only monotonic thing here. Backdating the
        // second row makes the two orderings disagree, so the assertion now discriminates.
        messages.save(Message.inbound(c.id(), "second", now.minusSeconds(3600)));

        assertThat(messages.findByConversationIdOrderByIdAsc(c.id()))
                .extracting(Message::body)
                .containsExactly("first", "second");
    }
}
