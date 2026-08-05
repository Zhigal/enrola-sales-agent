package com.enrola.agent.conversation;

import java.util.List;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;

public interface MessageRepository extends CrudRepository<Message, Long> {

    List<Message> findByConversationIdOrderByIdAsc(Long conversationId);

    @Modifying
    @Query("delete from messages where conversation_id = :conversationId")
    void deleteByConversationId(Long conversationId);
}
