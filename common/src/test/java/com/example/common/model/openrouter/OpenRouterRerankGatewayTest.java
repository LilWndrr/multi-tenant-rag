package com.example.common.model.openrouter;

import com.example.common.model.ModelCall;
import com.example.common.model.ModelCallLogger;
import com.example.common.model.ModelRerankResponse;
import com.example.common.model.Usage;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpenRouterRerankGatewayTest {

    private static final ModelCall CALL = new ModelCall(null, "rerank", "some/reranker");

    private final OpenRouterRerankClient client = mock(OpenRouterRerankClient.class);
    private final ModelCallLogger logger = mock(ModelCallLogger.class);
    private final OpenRouterModelGateway gateway = new OpenRouterModelGateway(
            mock(ChatModel.class), mock(EmbeddingModel.class), logger, new OpenRouterRetry(4, 1), client);

    @Test
    void aSuccessfulCall_isLoggedWithTheProviderUsage() {
        Usage usage = new Usage(42, 0, 0, BigDecimal.ZERO);
        ModelRerankResponse response = new ModelRerankResponse(List.of(new ModelRerankResponse.Score(0, 0.5)), "some/reranker", usage);
        when(client.rerank("some/reranker", "q", List.of("a"))).thenReturn(response);

        assertThat(gateway.rerank(CALL, "q", List.of("a"))).isSameAs(response);
        verify(logger).createModelCall(eq(CALL), eq("openrouter"), eq("some/reranker"), eq("ok"), eq(usage), anyInt());
    }

    // the reranker has a fallback, so a failure is not retried: a retry would only delay the answer
    @Test
    void aFailure_isLoggedOnce_andNotRetried() {
        when(client.rerank(anyString(), anyString(), anyList()))
                .thenThrow(new OpenRouterRerankClient.RerankException("rerank returned HTTP 429", null));

        assertThatThrownBy(() -> gateway.rerank(CALL, "q", List.of("a")))
                .isInstanceOf(OpenRouterRerankClient.RerankException.class);
        verify(client, times(1)).rerank(anyString(), anyString(), anyList());
        verify(logger, times(1)).createModelCall(eq(CALL), eq("openrouter"), eq("some/reranker"), eq("error"), any(), anyInt());
    }
}
