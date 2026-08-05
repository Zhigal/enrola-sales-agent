package com.enrola.agent.engine;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/** A model that does exactly what the test tells it to, including misbehaving. */
public class ScriptedLlmClient implements LlmClient {

    private final Deque<LlmResponse> queued = new ArrayDeque<>();
    private int callCount;
    private List<InputItem> lastInput = List.of();

    public ScriptedLlmClient queue(LlmResponse... responses) {
        queued.addAll(List.of(responses));
        return this;
    }

    public int callCount() {
        return callCount;
    }

    public List<InputItem> lastInput() {
        return lastInput;
    }

    public void reset() {
        queued.clear();
        callCount = 0;
        lastInput = List.of();
    }

    @Override
    public LlmResponse respond(List<InputItem> input) {
        callCount++;
        lastInput = List.copyOf(input);
        var next = queued.poll();
        if (next == null) {
            throw new IllegalStateException(
                    "ScriptedLlmClient called " + callCount + " times but nothing was queued");
        }
        return next;
    }
}
