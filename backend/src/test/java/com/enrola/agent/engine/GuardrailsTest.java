package com.enrola.agent.engine;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GuardrailsTest {

    @ParameterizedTest
    @ValueSource(strings = {"stop", "STOP", " Stop ", "stop.", "unsubscribe", "UNSUBSCRIBE!",
                            "opt out", "optout", "stop all"})
    void exactOptOutWordsMatch(String inbound) {
        assertThat(Guardrails.isExactOptOut(inbound)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"take me off this list", "stop calling me about this please",
                            "I want to stop paying so much", "no thanks", ""})
    void everythingElseGoesToTheModel(String inbound) {
        assertThat(Guardrails.isExactOptOut(inbound)).isFalse();
    }

    @Test
    void truncationPrefersTheLastSentenceBoundary() {
        var text = "First sentence. Second sentence. Third one runs past the limit here.";
        assertThat(Guardrails.truncateAtSentence(text, 40)).isEqualTo("First sentence. Second sentence.");
    }

    @Test
    void truncationFallsBackToAHardCutWhenThereIsNoBoundary() {
        var text = "no punctuation anywhere in this long run of words at all";
        assertThat(Guardrails.truncateAtSentence(text, 20)).hasSize(20);
    }

    @Test
    void shortTextIsReturnedUnchanged() {
        assertThat(Guardrails.truncateAtSentence("Short.", 320)).isEqualTo("Short.");
    }

    @Test
    void everyTypographicCharacterIsMappedToItsGsm7Equivalent() {
        assertThat(Guardrails.toGsm7("\u2018a\u2019")).isEqualTo("'a'");
        assertThat(Guardrails.toGsm7("\u201Ca\u201D")).isEqualTo("\"a\"");
        assertThat(Guardrails.toGsm7("a\u2013b\u2014c")).isEqualTo("a-b-c");
        assertThat(Guardrails.toGsm7("a\u00A0b")).isEqualTo("a b");
        assertThat(Guardrails.toGsm7("it\u2019s Anna")).isEqualTo("it's Anna");

        // The one replacement that changes the length, which is why normalisation has to run
        // before the limit check rather than after it.
        assertThat(Guardrails.toGsm7("wait\u2026")).isEqualTo("wait...").hasSize(7);
    }

    @Test
    void plainGsm7TextIsReturnedUnchanged() {
        var text = "Hi John, it's Anna from Comparato - you asked us about health cover. "
                + "Are you free today? Reply 'stop' to opt out.";
        assertThat(Guardrails.toGsm7(text)).isEqualTo(text);
    }
}
