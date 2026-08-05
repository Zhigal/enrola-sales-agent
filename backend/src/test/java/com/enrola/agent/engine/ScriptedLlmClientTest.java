package com.enrola.agent.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ScriptedLlmClientTest {

    @Test
    void returnsQueuedResponsesInOrderAndRecordsInput() {
        var client = new ScriptedLlmClient().queue(
                LlmResponse.toolCalls(new InputItem.FunctionCall("c1", "get_available_times", "{}")),
                LlmResponse.message("{\"message\":\"hi\"}"));

        var first = client.respond(java.util.List.of(InputItem.user("hello")));
        assertThat(first.structuredJson()).isNull();
        assertThat(first.calls()).singleElement()
                .extracting(InputItem.FunctionCall::name).isEqualTo("get_available_times");

        var second = client.respond(java.util.List.of(InputItem.user("again")));
        assertThat(second.structuredJson()).isEqualTo("{\"message\":\"hi\"}");
        assertThat(client.callCount()).isEqualTo(2);
        assertThat(client.lastInput()).containsExactly(InputItem.user("again"));
    }

    @Test
    void throwsWhenCalledMoreOftenThanScripted() {
        var client = new ScriptedLlmClient().queue(LlmResponse.message("{}"));
        client.respond(java.util.List.of());

        assertThatThrownBy(() -> client.respond(java.util.List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nothing was queued");
    }

    @Test
    void schemaIsStrictLegal() throws Exception {
        var schema = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(AgentTurn.SCHEMA_JSON);

        assertThat(schema.get("additionalProperties").asBoolean()).isFalse();
        assertThat(schema.get("required")).hasSize(schema.get("properties").size());
    }
}
