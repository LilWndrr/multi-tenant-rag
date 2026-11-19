package com.example.common.model.openrouter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// OpenRouter-only request fields for chat calls (docs/decisions.md, Task 24).
// providers: OpenRouter otherwise spreads requests over every provider of a model, and a prompt cache lives at one
// provider, so a repeat would miss it. The list is an order, not a lock: if the first is down the next one answers.
// reasoning: the answer step needs no hidden reasoning; with it on, answers took 3 to 10 s and several times the tokens.
@Component
public class OpenRouterChatSettings {

    private final List<String> providers;
    private final Boolean reasoning;

    public OpenRouterChatSettings(@Value("${rag.models.chat-providers:}") List<String> providers,
                                  @Value("${rag.models.chat-reasoning:#{null}}") Boolean reasoning) {
        this.providers = providers.stream().map(String::trim).filter(p -> !p.isEmpty()).toList();
        this.reasoning = reasoning;
    }

    public static OpenRouterChatSettings none() {
        return new OpenRouterChatSettings(List.of(), null);
    }

    Map<String, Object> extraBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        if (!providers.isEmpty()) {
            body.put("provider", Map.of("order", providers, "allow_fallbacks", true));
        }
        if (reasoning != null) {
            body.put("reasoning", Map.of("enabled", reasoning));
        }
        return body;
    }
}
