package com.enrola.agent.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import com.enrola.agent.DbTest;
import com.enrola.agent.lead.LeadRepository;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class RepositoryTest extends DbTest {

    @Autowired LeadRepository leads;
    @Autowired ConversationRepository conversations;
    @Autowired MessageRepository messages;

    @Test
    void seedsThreeLeadShapes() {
        var all = leads.findByCustomerId("comparato");
        assertThat(all).hasSize(3);
        assertThat(all).anyMatch(l -> l.currentProvider() != null && l.currentPremium() != null);
        assertThat(all).anyMatch(l -> l.currentProvider() != null && l.currentPremium() == null);
        assertThat(all).anyMatch(l -> l.currentProvider() == null);
    }

    @Test
    void objectionCountSurvivesAReload() {
        var now = Instant.parse("2026-08-05T00:00:00Z");
        var saved = conversations.save(new Conversation(
                null, 1L, "comparato", ConversationStatus.ACTIVE, 0, now, now));

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
