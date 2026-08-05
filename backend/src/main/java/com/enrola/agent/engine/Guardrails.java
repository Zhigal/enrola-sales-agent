package com.enrola.agent.engine;

import com.enrola.agent.conversation.Message;
import com.enrola.agent.conversation.MessageDirection;
import java.util.List;
import java.util.Set;

public final class Guardrails {

    public static final String OPT_OUT_FOOTER = "\n\nReply 'stop' to opt out";

    /** One compliance-approved wording, not a generated variant per conversation. */
    public static final String OPT_OUT_REPLY =
            "You're unsubscribed and won't get any more messages from us. Thanks for your time.";

    private static final Set<String> OPT_OUT_WORDS =
            Set.of("stop", "stop all", "unsubscribe", "opt out", "optout", "end", "quit");

    private Guardrails() {}

    /**
     * The opt-out footer is mandatory on the first outbound message and absent after it.
     * {@code history} is the transcript as it stood before this turn's outbound was written.
     */
    public static String footerFor(List<Message> history) {
        return history.stream().noneMatch(m -> m.direction() == MessageDirection.OUTBOUND)
                ? OPT_OUT_FOOTER
                : "";
    }

    /**
     * How many characters the model may actually spend on this turn. The footer counts against
     * the SMS limit, so the opening message has less room than every later one - and this is
     * the single place that says so, because {@code PromptBuilder} tells the model this number
     * and {@code AgentService} enforces it. Two copies of the arithmetic would drift, and the
     * symptom is the model generating a legal-looking message the guardrail then truncates.
     */
    public static int charBudget(int smsCharLimit, List<Message> history) {
        return smsCharLimit - footerFor(history).length();
    }

    public static boolean isExactOptOut(String inbound) {
        if (inbound == null) {
            return false;
        }
        var normalised = inbound.trim().toLowerCase()
                .replaceAll("[.!?,]+$", "")
                .replaceAll("\\s+", " ");
        return OPT_OUT_WORDS.contains(normalised);
    }

    /**
     * Typographic punctuation to its GSM-7 equivalent. One character outside the GSM-7 alphabet
     * forces the whole SMS into UCS-2, which drops the segment size from 160 to 70 - so a single
     * invisible curly apostrophe doubles the send cost of every message it appears in.
     *
     * Note {@code ...} is longer than the ellipsis it replaces, so this has to run before any
     * length check, not after.
     *
     * ponytail: covers the punctuation an LLM realistically emits, not the GSM-7 table. An emoji
     * or an accented character still forces UCS-2 and is not caught here; real validation would
     * need the full alphabet plus its extension set. Add that when a message actually gets one.
     */
    public static String toGsm7(String text) {
        return text
                .replace('\u2018', '\'')   // left single quotation mark
                .replace('\u2019', '\'')   // right single quotation mark - the apostrophe
                .replace('\u201C', '"')    // left double quotation mark
                .replace('\u201D', '"')    // right double quotation mark
                .replace('\u2013', '-')    // en dash
                .replace('\u2014', '-')    // em dash
                .replace('\u00A0', ' ')    // non-breaking space
                .replace("\u2026", "..."); // ellipsis - one character becomes three
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
