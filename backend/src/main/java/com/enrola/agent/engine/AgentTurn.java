package com.enrola.agent.engine;

public record AgentTurn(
        String message,
        Stage stage,
        boolean goalMet,
        boolean unsubscribed,
        boolean endConversation,
        EndReason endReason,
        boolean objectionRaised) {

    /** Strict JSON Schema for the Responses API text.format. */
    public static final String SCHEMA_JSON = """
        {
          "type": "object",
          "additionalProperties": false,
          "required": ["message", "stage", "goalMet", "unsubscribed",
                       "endConversation", "endReason", "objectionRaised"],
          "properties": {
            "message": {
              "type": "string",
              "description": "The SMS body to send, and nothing else."
            },
            "stage": {
              "type": "string",
              "enum": ["SITUATION","PREFERENCE","SUGGEST_CALL","OFFER_TIMES","CONFIRM","CLOSED"]
            },
            "goalMet": {
              "type": "boolean",
              "description": "True only once book_call has returned an id."
            },
            "unsubscribed": {
              "type": "boolean",
              "description": "True on any intent to stop being contacted, however phrased."
            },
            "endConversation": {
              "type": "boolean",
              "description": "True when no further messages should be sent."
            },
            "endReason": {
              "type": "string",
              "enum": ["NONE","BOOKED","UNSUBSCRIBED","ABUSE","GAVE_UP"]
            },
            "objectionRaised": {
              "type": "boolean",
              "description": "True when the lead pushed back on the call this turn."
            }
          }
        }
        """;
}
