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
}
