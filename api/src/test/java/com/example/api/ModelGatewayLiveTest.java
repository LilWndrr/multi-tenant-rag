package com.example.api;

import com.example.common.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

// Calls the real OpenRouter API: runs only when OPENROUTER_API_KEY is set, skipped in CI
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@EnabledIfEnvironmentVariable(named = "OPENROUTER_API_KEY", matches = ".+")
class ModelGatewayLiveTest {

    @Autowired
    ModelGateway gateway;
    @Autowired
    JdbcClient jdbc;

    @Value("${rag.models.chat}")
    String chatModel;
    @Value("${rag.models.embedding}")
    String embeddingModel;

    @Test
    void chat_getsReply_andLogsTheProviderTokenCounts() {
        String purpose = "test-" + UUID.randomUUID();
        ModelCall call = new ModelCall(null, purpose, chatModel);

        ModelChatResponse response = gateway.chat(call,
                List.of(new ChatMessage(Role.USER, "Reply with one word: hello")));

        assertThat(response.chat()).isNotBlank();
        assertThat(response.usage().inputTokens()).isPositive();
        assertThat(response.usage().outputTokens()).isPositive();
        // free models report 0; not-null proves OpenRouter's cost field is read
        assertThat(response.usage().costUsd()).isNotNull();

        Map<String, Object> row = jdbc.sql("select * from model_calls where purpose = :purpose and status = 'ok'")
                .param("purpose", purpose)
                .query().singleRow();
        assertThat(row.get("status")).isEqualTo("ok");
        assertThat(row.get("provider")).isEqualTo("openrouter");
        // Task 7 "done when": logged token counts match the provider response
        assertThat(row.get("input_tokens")).isEqualTo(response.usage().inputTokens());
        assertThat(row.get("output_tokens")).isEqualTo(response.usage().outputTokens());
        assertThat((BigDecimal) row.get("cost_usd")).isEqualByComparingTo(response.usage().costUsd());
    }
    @Test
    void embed_returnsOneVectorPerText_andLogsTokens(){
        String purpose = "test-" + UUID.randomUUID();
        ModelCall call = new ModelCall(null, purpose, embeddingModel);

        ModelEmbedResponse embedResponse = gateway.embed(call,List.of("first text", "second text"));

        assertThat(embedResponse.vectors()).hasSize(2);
        assertThat(embedResponse.vectors().get(0)).isNotEmpty();
        assertThat(embedResponse.vectors().get(1)).hasSameSizeAs(embedResponse.vectors().get(0));
        assertThat(embedResponse.usage().inputTokens()).isPositive();
        assertThat(embedResponse.usage().outputTokens()).isZero();
        assertThat(embedResponse.usage().costUsd()).isNotNull();

        Map<String, Object> row = jdbc.sql("select * from model_calls where purpose = :purpose and status = 'ok'")
                .param("purpose", purpose)
                .query().singleRow();
        assertThat(row.get("status")).isEqualTo("ok");
        assertThat(row.get("provider")).isEqualTo("openrouter");
        assertThat(row.get("input_tokens")).isEqualTo(embedResponse.usage().inputTokens());
        assertThat(row.get("output_tokens")).isEqualTo(0);
        assertThat((BigDecimal) row.get("cost_usd")).isEqualByComparingTo(embedResponse.usage().costUsd());
    }

    @Test
    void rerank_scoresEveryDocument_putsTheAnswerFirst_andLogsTokens() throws Exception {
        String purpose = "test-" + UUID.randomUUID();
        String model = new com.example.common.profile.LanguageProfiles().get("en").reranker();
        ModelCall call = new ModelCall(null, purpose, model);

        ModelRerankResponse response = gateway.rerank(call, "Which port does the kubelet API listen on?", List.of(
                "etcd is a consistent key-value store for all cluster data.",
                "The kubelet serves its API on port 10250.",
                "Rome is famous for pizza al taglio."));

        assertThat(response.scores()).extracting(ModelRerankResponse.Score::index).containsExactlyInAnyOrder(0, 1, 2);
        ModelRerankResponse.Score best = response.scores().stream()
                .max(java.util.Comparator.comparingDouble(ModelRerankResponse.Score::relevance)).orElseThrow();
        assertThat(best.index()).isEqualTo(1);

        Map<String, Object> row = jdbc.sql("select * from model_calls where purpose = :purpose")
                .param("purpose", purpose)
                .query().singleRow();
        assertThat(row.get("status")).isEqualTo("ok");
        assertThat(row.get("input_tokens")).isEqualTo(response.usage().inputTokens());
    }

    @Test
    void stream_deliversTokensAsTheyArrive_andLogsOnce() {
        String purpose = "test-" + UUID.randomUUID();
        ModelCall call = new ModelCall(null, purpose, chatModel);
        // chunks arrive on a Reactor thread, so collect them in a thread-safe list
        List<String> pieces = new CopyOnWriteArrayList<>();

        ModelChatResponse response = gateway.stream(call, pieces::add,
                List.of(new ChatMessage(Role.USER, "Count from 1 to 10, one number per line.")));

        assertThat(pieces).hasSizeGreaterThan(1);   // it really streamed, not one block
        assertThat(String.join("", pieces)).isEqualTo(response.chat());
        assertThat(response.usage().inputTokens()).isPositive();
        assertThat(response.usage().outputTokens()).isPositive();
        assertThat(response.usage().costUsd()).isNotNull();

        // exactly one ok row per stream, not one per chunk (a retried attempt adds an error row)
        List<Map<String, Object>> rows = jdbc.sql("select * from model_calls where purpose = :purpose and status = 'ok'")
                .param("purpose", purpose)
                .query().listOfRows();
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row.get("status")).isEqualTo("ok");
        assertThat(row.get("input_tokens")).isEqualTo(response.usage().inputTokens());
        assertThat(row.get("output_tokens")).isEqualTo(response.usage().outputTokens());
        assertThat((BigDecimal) row.get("cost_usd")).isEqualByComparingTo(response.usage().costUsd());
    }
}
