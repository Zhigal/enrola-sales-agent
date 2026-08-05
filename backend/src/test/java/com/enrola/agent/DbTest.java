package com.enrola.agent;

import com.enrola.agent.calendly.RecordingCalendlyClient;
import com.enrola.agent.engine.ScriptedLlmClient;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

// Every DbTest subclass now shares the exact same context configuration (this class plus
// Stubs), so Spring's context cache would otherwise hand the second test class the first
// test class's cached ApplicationContext - including its DataSource. But the static
// @Container Postgres field is stopped and restarted between test classes (that's how
// JUnit5 Testcontainers handles a static field), so a reused context's DataSource ends up
// pointing at a container that no longer exists. @DirtiesContext forces a fresh context -
// and therefore a fresh DataSource bound to the container that is actually running - for
// every test class.
@SpringBootTest
@Testcontainers
@Import(DbTest.Stubs.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class DbTest {

    /** Wednesday 5 August 2026, 08:00 in Australia/Perth - the customer's timezone. */
    public static final Instant NOW = Instant.parse("2026-08-05T00:00:00Z");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    /**
     * Every DB-backed test needs both of these: AgentService cannot be constructed without an
     * LlmClient, and any assertion about "tomorrow morning" needs the clock pinned.
     *
     * They are @Primary rather than same-name overrides of ClockConfig.clock() and any future
     * production LlmClient bean. A same-name override needs a Spring Boot flag that switches
     * off duplicate-bean protection for the whole suite - so a genuine accidental duplicate
     * elsewhere would stop failing too. One collision is not worth disabling the check that
     * catches the next one.
     */
    @TestConfiguration
    public static class Stubs {

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @Bean
        @Primary
        ScriptedLlmClient scriptedLlm() {
            return new ScriptedLlmClient();
        }

        /** Same behaviour as the component-scanned stub, but it remembers what it was asked to book. */
        @Bean
        @Primary
        RecordingCalendlyClient recordingCalendly(Clock clock) {
            return new RecordingCalendlyClient(clock);
        }
    }
}
