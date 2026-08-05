package com.enrola.agent.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Verifies the wire shape against the real API. Excluded from CI; needs OPENAI_API_KEY. */
@Tag("live")
class OpenAiLlmClientLiveTest {

    private static final String KEY = System.getenv("OPENAI_API_KEY");

    private OpenAiLlmClient client() {
        return new OpenAiLlmClient("https://api.openai.com/v1", KEY, "gpt-5.6-terra", 60);
    }

    @Test
    void structuredOutputRoundTrips() throws Exception {
        assumeTrue(KEY != null && !KEY.isBlank(), "OPENAI_API_KEY not set");

        var response = client().respond(List.of(
                InputItem.system("You are a test fixture. Set stage SITUATION and endReason NONE. "
                        + "Do not call any tool."),
                InputItem.user("Say exactly: hello")));

        assertThat(response.structuredJson()).isNotNull();
        var turn = new ObjectMapper().readValue(response.structuredJson(), AgentTurn.class);
        assertThat(turn.message()).isNotBlank();
        assertThat(turn.stage()).isEqualTo(Stage.SITUATION);
        assertThat(response.tokensIn()).isPositive();
    }

    @Test
    void toolDefinitionsAreAcceptedAndCalled() {
        assumeTrue(KEY != null && !KEY.isBlank(), "OPENAI_API_KEY not set");

        var response = client().respond(List.of(
                InputItem.system("You book advisor calls. To find times you must call "
                        + "get_available_times. Never guess a time."),
                InputItem.user("What times are free between 2026-08-05T00:00:00Z "
                        + "and 2026-08-07T00:00:00Z?")));

        assertThat(response.calls()).isNotEmpty();
        assertThat(response.calls().getFirst().name()).isEqualTo("get_available_times");
    }
}
