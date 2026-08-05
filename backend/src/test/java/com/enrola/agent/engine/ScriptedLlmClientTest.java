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

        // Membership, not size. Two sets can have the same size while naming different things -
        // one renamed key on one side only - and strict mode rejects the whole request for it.
        assertThat(names(schema.get("required")))
                .isEqualTo(fieldNames(schema.get("properties")));
    }

    /**
     * The schema is hand-written text while the Java types are code, so there are three places
     * the same names live and nothing but this test keeps them in step. Drift here fails at
     * runtime - a rejected API request, or Jackson quietly binding null into a record component -
     * which is the worst place to find out.
     */
    @Test
    void schemaMatchesTheJavaTypes() throws Exception {
        var schema = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(AgentTurn.SCHEMA_JSON);

        assertThat(fieldNames(schema.get("properties")))
                .isEqualTo(java.util.Arrays.stream(AgentTurn.class.getRecordComponents())
                        .map(java.lang.reflect.RecordComponent::getName)
                        .collect(java.util.stream.Collectors.toSet()));

        assertThat(enumValues(schema, "stage"))
                .containsExactly(java.util.Arrays.stream(Stage.values())
                        .map(Enum::name).toArray(String[]::new));
        assertThat(enumValues(schema, "endReason"))
                .containsExactly(java.util.Arrays.stream(EndReason.values())
                        .map(Enum::name).toArray(String[]::new));
    }

    private static java.util.Set<String> names(com.fasterxml.jackson.databind.JsonNode array) {
        var out = new java.util.HashSet<String>();
        array.forEach(node -> out.add(node.asText()));
        return out;
    }

    private static java.util.Set<String> fieldNames(
            com.fasterxml.jackson.databind.JsonNode object) {
        var out = new java.util.HashSet<String>();
        object.fieldNames().forEachRemaining(out::add);
        return out;
    }

    private static java.util.List<String> enumValues(
            com.fasterxml.jackson.databind.JsonNode schema, String property) {
        var out = new java.util.ArrayList<String>();
        schema.get("properties").get(property).get("enum").forEach(node -> out.add(node.asText()));
        return out;
    }
}
