package com.example.common.model.openrouter;

import com.example.common.model.ModelRerankResponse;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// A local HTTP server plays OpenRouter, so the real request and response handling run without the network
class OpenRouterRerankClientTest {

    private static final String OK = """
            {"id":"gen-1","model":"some/reranker","provider":"Nvidia",
             "results":[{"index":1,"relevance_score":0.9,"document":{"text":"b"}},
                        {"index":0,"relevance_score":0.1,"document":{"text":"a"}}],
             "usage":{"total_tokens":42,"cost":0.00012}}
            """;

    private HttpServer server;
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> auth = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private OpenRouterRerankClient serving(int status, String response, long delayMs, long timeoutMs) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1/";
        return new OpenRouterRerankClient(baseUrl, "test-key", timeoutMs);
    }

    @Test
    void sendsModelQueryAndDocuments_toTheRerankEndpoint() throws IOException {
        serving(200, OK, 0, 5000).rerank("some/reranker", "which port?", List.of("a", "b"));

        assertThat(path.get()).isEqualTo("/api/v1/rerank");
        assertThat(auth.get()).isEqualTo("Bearer test-key");
        JsonNode sent = JsonMapper.builder().build().readTree(body.get());
        assertThat(sent.path("model").asString()).isEqualTo("some/reranker");
        assertThat(sent.path("query").asString()).isEqualTo("which port?");
        assertThat(sent.path("documents").size()).isEqualTo(2);
        assertThat(sent.path("documents").get(1).asString()).isEqualTo("b");
    }

    @Test
    void readsScoresModelAndUsage() throws IOException {
        ModelRerankResponse response = serving(200, OK, 0, 5000).rerank("some/reranker", "q", List.of("a", "b"));

        assertThat(response.scores()).containsExactly(
                new ModelRerankResponse.Score(1, 0.9), new ModelRerankResponse.Score(0, 0.1));
        assertThat(response.model()).isEqualTo("some/reranker");
        assertThat(response.usage().inputTokens()).isEqualTo(42);
        assertThat(response.usage().costUsd()).isEqualByComparingTo(new BigDecimal("0.00012"));
    }

    // the error body may repeat the question; only the status may reach the message (and so the logs)
    @Test
    void anErrorStatus_failsWithTheStatusOnly() throws IOException {
        OpenRouterRerankClient client = serving(429, "{\"error\":{\"message\":\"secret question text\"}}", 0, 5000);

        assertThatThrownBy(() -> client.rerank("m", "secret question text", List.of("a")))
                .isInstanceOf(OpenRouterRerankClient.RerankException.class)
                .hasMessageContaining("429")
                .hasMessageNotContaining("secret");
    }

    @Test
    void aSlowProvider_failsAtTheTimeout() throws IOException {
        OpenRouterRerankClient client = serving(200, OK, 2000, 200);

        long start = System.nanoTime();
        assertThatThrownBy(() -> client.rerank("m", "q", List.of("a", "b")))
                .isInstanceOf(OpenRouterRerankClient.RerankException.class)
                .hasMessageContaining("Timeout");
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(1500);
    }

    @Test
    void aResponseWithoutUsableResults_fails() {
        assertThatThrownBy(() -> OpenRouterRerankClient.parse("{\"model\":\"m\"}", "m"))
                .isInstanceOf(OpenRouterRerankClient.RerankException.class);
        assertThatThrownBy(() -> OpenRouterRerankClient.parse("{\"results\":[{\"index\":0}]}", "m"))
                .isInstanceOf(OpenRouterRerankClient.RerankException.class);
        assertThatThrownBy(() -> OpenRouterRerankClient.parse("not json", "m"))
                .isInstanceOf(OpenRouterRerankClient.RerankException.class);
    }

    @Test
    void missingModelAndUsage_fallBackToTheRequestedModelAndZero() {
        ModelRerankResponse response = OpenRouterRerankClient.parse(
                "{\"results\":[{\"index\":0,\"relevance_score\":0.5}]}", "requested/model");

        assertThat(response.model()).isEqualTo("requested/model");
        assertThat(response.usage().inputTokens()).isZero();
        assertThat(response.usage().costUsd()).isNull();
    }
}
