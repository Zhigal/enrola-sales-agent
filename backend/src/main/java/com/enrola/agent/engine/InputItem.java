package com.enrola.agent.engine;

/** One item of Responses API input. Mirrors the wire shape so the real client is a mapping. */
public sealed interface InputItem {

    /** role is one of system, developer, user, assistant. */
    record Text(String role, String content) implements InputItem {}

    record FunctionCall(String callId, String name, String argumentsJson) implements InputItem {}

    record FunctionCallOutput(String callId, String outputJson) implements InputItem {}

    static Text system(String content) { return new Text("system", content); }
    static Text developer(String content) { return new Text("developer", content); }
    static Text user(String content) { return new Text("user", content); }
    static Text assistant(String content) { return new Text("assistant", content); }
}
