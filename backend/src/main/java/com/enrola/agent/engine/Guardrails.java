package com.enrola.agent.engine;

import java.util.Set;

public final class Guardrails {

    public static final String OPT_OUT_FOOTER = "\n\nReply 'stop' to opt out";

    /** One compliance-approved wording, not a generated variant per conversation. */
    public static final String OPT_OUT_REPLY =
            "You're unsubscribed and won't get any more messages from us. Thanks for your time.";

    private static final Set<String> OPT_OUT_WORDS =
            Set.of("stop", "stop all", "unsubscribe", "opt out", "optout", "end", "quit");

    private Guardrails() {}

    public static boolean isExactOptOut(String inbound) {
        if (inbound == null) {
            return false;
        }
        var normalised = inbound.trim().toLowerCase()
                .replaceAll("[.!?,]+$", "")
                .replaceAll("\\s+", " ");
        return OPT_OUT_WORDS.contains(normalised);
    }

    public static String truncateAtSentence(String text, int limit) {
        if (text.length() <= limit) {
            return text;
        }
        var head = text.substring(0, limit);
        var cut = Math.max(head.lastIndexOf('.'), Math.max(head.lastIndexOf('?'), head.lastIndexOf('!')));
        return cut > 0 ? head.substring(0, cut + 1).trim() : head;
    }
}
