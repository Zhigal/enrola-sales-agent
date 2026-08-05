package com.enrola.agent.engine;

import java.util.List;

/** structuredJson is null when the model only emitted tool calls this round. */
public record LlmResponse(
        String structuredJson,
        List<InputItem.FunctionCall> calls,
        int tokensIn,
        int tokensOut) {

    public static LlmResponse message(String structuredJson) {
        return new LlmResponse(structuredJson, List.of(), 0, 0);
    }

    public static LlmResponse toolCalls(InputItem.FunctionCall... calls) {
        return new LlmResponse(null, List.of(calls), 0, 0);
    }
}
