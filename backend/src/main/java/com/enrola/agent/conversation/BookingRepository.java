package com.enrola.agent.conversation;

import java.util.List;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;

public interface BookingRepository extends CrudRepository<Booking, Long> {

    List<Booking> findByConversationId(Long conversationId);

    @Modifying
    @Query("delete from bookings where conversation_id = :conversationId")
    void deleteByConversationId(Long conversationId);
}
