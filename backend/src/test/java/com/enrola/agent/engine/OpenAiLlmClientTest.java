package com.enrola.agent.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The tool schemas, asserted without an HTTP call. This is the twin of
 * {@code ScriptedLlmClientTest}'s checks on {@code AgentTurn.SCHEMA_JSON}: that one covers the
 * structured output, this one covers the function tools, and both exist because the schema is
 * hand-built while the code that consumes it is elsewhere.
 */
class OpenAiLlmClientTest {

    private static JsonNode tool(String name) {
        for (var node : OpenAiLlmClient.tools()) {
            if (name.equals(node.path("name").asText())) {
                return node;
            }
        }
        throw new AssertionError("No tool named " + name);
    }

    private static Set<String> names(JsonNode array) {
        var out = new HashSet<String>();
        array.forEach(node -> out.add(node.asText()));
        return out;
    }

    private static Set<String> fieldNames(JsonNode object) {
        var out = new HashSet<String>();
        object.fieldNames().forEachRemaining(out::add);
        return out;
    }

    /**
     * Strict mode requires every property to be listed in required, and it rejects the whole
     * request - not just the offending tool - when one is not. Membership, not size: two sets of
     * the same size can name different things.
     */
    @Test
    void everyToolSchemaIsStrictLegal() {
        assertThat(OpenAiLlmClient.tools()).isNotEmpty();
        for (var tool : OpenAiLlmClient.tools()) {
            var params = tool.get("parameters");
            assertThat(tool.get("strict").asBoolean())
                    .as("%s is strict", tool.path("name").asText()).isTrue();
            assertThat(params.get("additionalProperties").asBoolean())
                    .as("%s forbids extra properties", tool.path("name").asText()).isFalse();
            assertThat(names(params.get("required")))
                    .as("%s: required must name exactly the properties", tool.path("name").asText())
                    .isEqualTo(fieldNames(params.get("properties")));
        }
    }

    /**
     * book_call asks the model for the time and nothing else. The name, phone and email are read
     * from the Lead record by {@code AgentService.bookCall}, so putting them back in this schema
     * would both ask the model for data it is never shown - it stops and asks the lead to type
     * out an email the system already has - and hand a prompt injection a way to redirect the
     * invite. This assertion is the guard on that decision, not a restatement of the code.
     */
    @Test
    void bookCallAsksTheModelForTheTimeOnly() {
        var params = tool("book_call").get("parameters");

        assertThat(fieldNames(params.get("properties"))).containsExactly("start_time");
        assertThat(names(params.get("required"))).containsExactly("start_time");
    }

    /** Untouched by the book_call change, and the model still needs both ends of the window. */
    @Test
    void getAvailableTimesStillTakesAWindow() {
        var params = tool("get_available_times").get("parameters");

        assertThat(fieldNames(params.get("properties")))
                .containsExactlyInAnyOrder("start_time", "end_time");
    }
}
