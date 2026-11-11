package com.example.common.profile;

// One profiles/<id>.yaml file; id comes from the file name
public record LanguageProfile(
        String id,
        String embeddingModel,
        Integer embeddingDimensions,
        String reranker,
        Chunk chunk,
        String textSearchConfig,
        Prompts prompts) {

    public record Chunk(int size, int overlap) {}

    public record Prompts(String system, String noAnswer) {}

    LanguageProfile withId(String id) {
        return new LanguageProfile(id, embeddingModel, embeddingDimensions, reranker, chunk, textSearchConfig, prompts);
    }
}
