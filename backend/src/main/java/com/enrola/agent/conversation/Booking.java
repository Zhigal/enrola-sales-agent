package com.enrola.agent.conversation;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("bookings")
public record Booking(
        @Id Long id,
        Long conversationId,
        String calendlyEventId,
        Instant startTime) {}
