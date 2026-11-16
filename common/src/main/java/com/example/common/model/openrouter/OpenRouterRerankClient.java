package com.example.common.model.openrouter;

import com.example.common.model.ModelRerankResponse;
import com.example.common.model.Usage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

// OpenRouter's POST /rerank. Spring AI has no rerank model, so this is plain HTTP. Errors carry only the status,
// never the response body: it may repeat the question or the documents, which must not reach the logs.
@Component
public class OpenRouterRerankClient {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpClient http;
    private final URI endpoint;
    private final String apiKey;
    private final Duration timeout;

    public OpenRouterRerankClient(@Value("${spring.ai.openai.base-url:https://openrouter.ai/api/v1}") String baseUrl,
                                  @Value("${spring.ai.openai.api-key:}") String apiKey,
                                  @Value("${rag.models.rerank.timeout-ms:5000}") long timeoutMs) {
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        this.endpoint = URI.create(baseUrl.replaceAll("/+$", "") + "/rerank");
        this.apiKey = apiKey;
        this.timeout = Duration.ofMillis(timeoutMs);
    }

    record Request(String model, String query, List<String> documents) {}

    public ModelRerankResponse rerank(String model, String query, List<String> documents) {
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(new Request(model, query, documents))))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {

            throw new RerankException("rerank request failed: " + e.getClass().getSimpleName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RerankException("rerank request interrupted", e);
        }
        if (response.statusCode() / 100 != 2) {
            throw new RerankException("rerank returned HTTP " + response.statusCode(), null);
        }
        return parse(response.body(), model);
    }

    static ModelRerankResponse parse(String body, String requestedModel) {
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (RuntimeException e) {
            throw new RerankException("rerank response is not JSON", null);
        }
        JsonNode results = root.path("results");
        if (!results.isArray()) {
            throw new RerankException("rerank response has no results", null);
        }
        List<ModelRerankResponse.Score> scores = new ArrayList<>();
        for (JsonNode result : results) {
            if (!result.path("index").isIntegralNumber() || !result.path("relevance_score").isNumber()) {
                throw new RerankException("rerank result without index or relevance_score", null);
            }
            scores.add(new ModelRerankResponse.Score(result.path("index").asInt(), result.path("relevance_score").asDouble()));
        }
        String model = root.path("model").isString() ? root.path("model").asString() : requestedModel;
        JsonNode usage = root.path("usage");
        BigDecimal cost = usage.path("cost").isNumber() ? usage.path("cost").decimalValue() : null;
        return new ModelRerankResponse(scores, model, new Usage(usage.path("total_tokens").asInt(0), 0, 0, cost));
    }

    public static final class RerankException extends RuntimeException {
        RerankException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
