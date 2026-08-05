package com.enrola.agent.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * The only class that knows the Responses API wire shape.
 *
 * Note the tool definitions are flat (type/name/parameters/strict at the top level). That is
 * the Responses API shape, not the Chat Completions one.
 */
@Component
public class OpenAiLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiLlmClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final RestClient http;
    private final String model;
    private final String apiKey;

    public OpenAiLlmClient(@Value("${enrola.openai.base-url}") String baseUrl,
                           @Value("${enrola.openai.api-key}") String apiKey,
                           @Value("${enrola.openai.model}") String model,
                           @Value("${enrola.openai.timeout-seconds}") int timeoutSeconds) {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(timeoutSeconds).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(timeoutSeconds).toMillis());
        this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.apiKey = apiKey;
        this.model = model;
    }

    @Override
    public LlmResponse respond(List<InputItem> input) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("OPENAI_API_KEY is not set");
        }
        var body = request(input);
        JsonNode response;
        try {
            response = http.post()
                    .uri("/responses")
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Responses API call failed: " + e.getMessage(), e);
        }
        return parse(response);
    }

    private ObjectNode request(List<InputItem> input) {
        var body = JSON.createObjectNode();
        body.put("model", model);
        body.set("input", inputArray(input));
        body.set("tools", tools());

        var format = JSON.createObjectNode();
        format.put("type", "json_schema");
        format.put("name", "agent_turn");
        format.put("strict", true);
        try {
            format.set("schema", JSON.readTree(AgentTurn.SCHEMA_JSON));
        } catch (Exception e) {
            throw new IllegalStateException("AgentTurn.SCHEMA_JSON is not valid JSON", e);
        }
        body.set("text", JSON.createObjectNode().set("format", format));
        return body;
    }

    private ArrayNode inputArray(List<InputItem> input) {
        var array = JSON.createArrayNode();
        for (var item : input) {
            switch (item) {
                case InputItem.Text text -> {
                    var node = array.addObject();
                    node.put("role", text.role());
                    node.put("content", text.content());
                }
                case InputItem.FunctionCall call -> {
                    var node = array.addObject();
                    node.put("type", "function_call");
                    node.put("call_id", call.callId());
                    node.put("name", call.name());
                    node.put("arguments", call.argumentsJson());
                }
                case InputItem.FunctionCallOutput output -> {
                    var node = array.addObject();
                    node.put("type", "function_call_output");
                    node.put("call_id", output.callId());
                    node.put("output", output.outputJson());
                }
            }
        }
        return array;
    }

    private ArrayNode tools() {
        var tools = JSON.createArrayNode();

        var times = tools.addObject();
        times.put("type", "function");
        times.put("name", "get_available_times");
        times.put("description",
                "Available advisor call times between two instants. Returns a JSON array of "
                        + "ISO-8601 start times with the lead's UTC offset applied.");
        times.put("strict", true);
        var timesParams = times.putObject("parameters");
        timesParams.put("type", "object");
        timesParams.put("additionalProperties", false);
        timesParams.putArray("required").add("start_time").add("end_time");
        var timesProps = timesParams.putObject("properties");
        timesProps.putObject("start_time").put("type", "string")
                .put("description", "ISO-8601 instant, e.g. 2026-08-05T00:00:00Z");
        timesProps.putObject("end_time").put("type", "string")
                .put("description", "ISO-8601 instant, e.g. 2026-08-09T00:00:00Z");

        var book = tools.addObject();
        book.put("type", "function");
        book.put("name", "book_call");
        book.put("description", "Book the advisor call. Returns the booking id.");
        book.put("strict", true);
        var bookParams = book.putObject("parameters");
        bookParams.put("type", "object");
        bookParams.put("additionalProperties", false);
        bookParams.putArray("required")
                .add("name").add("phone").add("email").add("start_time");
        var bookProps = bookParams.putObject("properties");
        bookProps.putObject("name").put("type", "string");
        bookProps.putObject("phone").put("type", "string");
        bookProps.putObject("email").put("type", "string");
        bookProps.putObject("start_time").put("type", "string")
                .put("description", "One of the start times returned by get_available_times");

        return tools;
    }

    private LlmResponse parse(JsonNode response) {
        if (response == null || !response.has("output")) {
            throw new IllegalStateException("Responses API returned no output: " + response);
        }
        String structuredJson = null;
        var calls = new ArrayList<InputItem.FunctionCall>();

        for (var item : response.get("output")) {
            var type = item.path("type").asText();
            if ("function_call".equals(type)) {
                calls.add(new InputItem.FunctionCall(item.path("call_id").asText(),
                        item.path("name").asText(), item.path("arguments").asText()));
            } else if ("message".equals(type)) {
                for (var part : item.path("content")) {
                    if ("output_text".equals(part.path("type").asText())) {
                        structuredJson = part.path("text").asText();
                    }
                }
            }
        }
        var usage = response.path("usage");
        var result = new LlmResponse(structuredJson, calls,
                usage.path("input_tokens").asInt(), usage.path("output_tokens").asInt());
        log.debug("Responses API: {} tool calls, {} in / {} out tokens",
                calls.size(), result.tokensIn(), result.tokensOut());
        return result;
    }
}
