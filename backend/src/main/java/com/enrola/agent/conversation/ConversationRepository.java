package com.enrola.agent.conversation;

import java.util.Optional;
import org.springframework.data.repository.CrudRepository;

public interface ConversationRepository extends CrudRepository<Conversation, Long> {

    /** Most recent by id, because ids are the only monotonic thing here. */
    Optional<Conversation> findFirstByLeadIdOrderByIdDesc(Long leadId);
}
