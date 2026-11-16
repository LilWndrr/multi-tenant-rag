package com.example.common.model;

import java.util.List;

// One score per document the provider returned; index points into the documents that were sent
public record ModelRerankResponse(List<Score> scores, String model, Usage usage) {

    public record Score(int index, double relevance) {}
}
