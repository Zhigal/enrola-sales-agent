package com.enrola.agent.engine;

import java.util.List;

public interface LlmClient {
    LlmResponse respond(List<InputItem> input);
}
