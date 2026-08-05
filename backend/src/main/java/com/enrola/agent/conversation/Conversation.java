package com.enrola.agent.conversation;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("conversations")
public record Conversation(
        @Id Long id,
        Long leadId,
        String customerId,
        ConversationStatus status,
        int objectionCount,
        Instant createdAt,
        Instant updatedAt) {

    public Conversation withStatus(ConversationStatus s, Instant now) {
        return new Conversation(id, leadId, customerId, s, objectionCount, createdAt, now);
    }

    public Conversation withObjectionCount(int n, Instant now) {
        return new Conversation(id, leadId, customerId, status, n, createdAt, now);
    }
}
