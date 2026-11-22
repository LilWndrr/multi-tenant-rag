package com.example.common.profile;

import java.util.Locale;

// One profiles/<id>.yaml file; id comes from the file name
public record LanguageProfile(
        String id,
        String embeddingModel,
        Integer embeddingDimensions,
        String reranker,
        Chunk chunk,
        String textSearchConfig,
        String locale,
        Retrieval retrieval,
        Prompts prompts) {

    public record Chunk(int size, int overlap) {}

    public record Prompts(String system, String noAnswer) {

        public static final String NO_ANSWER_PLACEHOLDER = "{no-answer}";

        // the system prompt as sent: the same text for every question of this profile
        public String systemWithNoAnswer() {
            return system.replace(NO_ANSWER_PLACEHOLDER, noAnswer);
        }
    }

    // The hybrid retriever answers only when the best vector similarity or the best full-text score reaches its
    // minimum; otherwise the question gets no sources. Per language, because the embedding model's similarities
    // sit at different levels for each (docs/decisions.md, Task 22). Double, not double, so a missing value reaches our validation (see embeddingDimensions).
    public record Retrieval(Double minVectorSimilarity, Double minTextScore) {}

    LanguageProfile withId(String id) {
        return new LanguageProfile(id, embeddingModel, embeddingDimensions, reranker, chunk, textSearchConfig, locale, retrieval, prompts);
    }

    // locale is a BCP 47 tag ("en", "tr"): case rules differ by language (tr has a dotted and a dotless i), so text is
    // lowercased with the profile's locale, never the default one
    public Locale textLocale() {
        return Locale.forLanguageTag(locale);
    }
}
