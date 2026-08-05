package com.enrola.agent.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import com.enrola.agent.DbTest;
import com.enrola.agent.engine.AgentService;
import com.enrola.agent.engine.LlmResponse;
import com.enrola.agent.engine.ScriptedLlmClient;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/**
 * data.sql runs on every boot ({@code spring.sql.init.mode: always}), so the seeded thread has
 * to survive a restart in whatever state the reviewer left it. Its own test class because it
 * deliberately mutates the seed, and every other test that reads the seed shares one database
 * with no ordering guarantee.
 */
class SeedRerunTest extends DbTest {

    private static final String OPENER = """
        {"message":"Fresh opener?","stage":"SITUATION","goalMet":false,"unsubscribed":false,
         "endConversation":false,"endReason":"NONE","objectionRaised":false}
        """;

    @Autowired AgentService agent;
    @Autowired ScriptedLlmClient llm;
    @Autowired ConversationRepository conversations;
    @Autowired MessageRepository messages;
    @Autowired BookingRepository bookings;
    @Autowired DataSource dataSource;

    @Test
    void rebootingNeitherDuplicatesTheSeedNorUndoesAReset() {
        var id = conversations.findFirstByLeadIdOrderByIdDesc(1L).orElseThrow().id();

        // Boot two, untouched: the seed is already there and must not be laid down twice.
        reboot();
        assertThat(messages.findByConversationIdOrderByIdAsc(id)).hasSize(9);
        assertThat(bookings.findByConversationId(id)).hasSize(1);

        llm.reset();
        llm.queue(LlmResponse.message(OPENER));
        agent.reset(id);

        // Boot three, after a reset. The seeded messages' ids went with them, so `on conflict
        // (id) do nothing` would happily put all nine back into a conversation that is now
        // ACTIVE with no booking - a thread whose transcript says a call was booked and whose
        // status says it was not. The guard in data.sql is the only thing stopping that.
        reboot();

        assertThat(conversations.findById(id).orElseThrow().status())
                .isEqualTo(ConversationStatus.ACTIVE);
        assertThat(messages.findByConversationIdOrderByIdAsc(id)).singleElement()
                .satisfies(m -> assertThat(m.body()).startsWith("Fresh opener?"));
        assertThat(bookings.findByConversationId(id)).isEmpty();
    }

    /** Exactly what Spring Boot does on startup, against the database that is already there. */
    private void reboot() {
        DatabasePopulatorUtils.execute(
                new ResourceDatabasePopulator(new ClassPathResource("data.sql")), dataSource);
    }
}
