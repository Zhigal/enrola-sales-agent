package com.enrola.agent.conversation;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("messages")
public record Message(
        @Id Long id,
        Long conversationId,
        MessageDirection direction,
        String body,
        String promptVersion,
        String model,
        Integer tokensIn,
        Integer tokensOut,
        String structuredOutput,
        Instant createdAt) {

    public static Message inbound(Long conversationId, String body, Instant at) {
        return new Message(null, conversationId, MessageDirection.INBOUND, body,
                null, null, null, null, null, at);
    }
}
