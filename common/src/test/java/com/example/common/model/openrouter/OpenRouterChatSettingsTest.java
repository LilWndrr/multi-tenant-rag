package com.example.common.model.openrouter;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OpenRouterChatSettingsTest {

    @Test
    void providersBecomeAnOrderThatMayFallBack_andReasoningAFlag() {
        Map<String, Object> body = new OpenRouterChatSettings(List.of(" CoreWeave", "DeepInfra "), false).extraBody();

        assertThat(body).containsEntry("provider", Map.of("order", List.of("CoreWeave", "DeepInfra"), "allow_fallbacks", true));
        assertThat(body).containsEntry("reasoning", Map.of("enabled", false));
    }

    // nothing configured (the worker, tests): OpenRouter's own defaults
    @Test
    void nothingSet_sendsNothingExtra() {
        assertThat(OpenRouterChatSettings.none().extraBody()).isEmpty();
        assertThat(new OpenRouterChatSettings(List.of(""), null).extraBody()).isEmpty();
    }
}
