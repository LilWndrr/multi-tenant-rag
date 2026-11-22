package com.example.api.chat;

import com.example.api.TestcontainersConfiguration;
import com.example.api.apiKey.ApiKeyService;
import com.example.api.tenant.TenantService;
import com.example.common.model.ModelChatResponse;
import com.example.common.model.ModelEmbedResponse;
import com.example.common.model.ModelGateway;
import com.example.common.model.ModelRerankResponse;
import com.example.common.model.Usage;
import com.example.common.profile.LanguageProfiles;
import com.example.common.tenant.TenantTransactions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

// The plan's checks for Task 27 through the real endpoint, retrievers, Postgres and Redis; only the model provider is
// replaced, so its calls show whether an answer was cached. Timing is measured on the real app (decisions.md, Task 27).
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AnswerCacheChatTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    TenantService tenantService;
    @Autowired
    ApiKeyService apiKeyService;
    @Autowired
    TenantTransactions tenantTransactions;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    LanguageProfiles profiles;
    @Autowired
    StringRedisTemplate redis;

    @MockitoBean
    ModelGateway modelGateway;

    @BeforeEach
    void providerAnswers() {
        float[] vector = new float[profiles.get("en").embeddingDimensions()];
        Arrays.fill(vector, 0.01f);
        when(modelGateway.embed(any(), anyList())).thenReturn(
                new ModelEmbedResponse(List.of(vector), "m/embed", new Usage(5, 0, 0, null)));
        when(modelGateway.rerank(any(), anyString(), anyList())).thenAnswer(inv -> {
            List<String> documents = inv.getArgument(2);
            List<ModelRerankResponse.Score> scores = new ArrayList<>();
            for (int i = 0; i < documents.size(); i++) {
                scores.add(new ModelRerankResponse.Score(i, 0.9));
            }
            return new ModelRerankResponse(scores, "m/rerank", new Usage(100, 0, 0, null));
        });
        when(modelGateway.stream(any(), any(), anyList())).thenAnswer(inv -> {
            Consumer<String> onToken = inv.getArgument(1);
            onToken.accept("The scheduler");
            onToken.accept(" runs every minute [1].");
            return new ModelChatResponse("The scheduler runs every minute [1].", "m/chat", new Usage(100, 10, 0, null));
        });
    }

    private record Tenant(UUID id, String key, UUID document) {}

    private Tenant tenantWithAChunk() {
        UUID id = tenantService.create("t-" + UUID.randomUUID(), "en").id();
        UUID document = newDocument(id);
        addChunk(id, document, 0, "The scheduler interval setting controls how often the scheduler runs.");
        return new Tenant(id, apiKeyService.create(id, "website").key(), document);
    }

    private String chat(Tenant tenant, String question) throws Exception {
        MvcResult result = mvc.perform(post("/v1/chat").header("Authorization", "Bearer " + tenant.key())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"question\": \"" + question + "\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        result.getAsyncResult(5_000);
        return result.getResponse().getContentAsString();
    }

    @Test
    void theSameQuestionAgain_isAnsweredFromTheCache_withTheSameEvents_andNoModelCall() throws Exception {
        Tenant tenant = tenantWithAChunk();

        String first = chat(tenant, "How often does the scheduler run?");
        clearInvocations(modelGateway);
        String second = chat(tenant, "how often does the scheduler run");

        verifyNoInteractions(modelGateway);
        assertThat(text(second)).isEqualTo(text(first)).isEqualTo("The scheduler runs every minute [1].");
        assertThat(citationsEvent(second)).isEqualTo(citationsEvent(first)).contains("\"chunkId\"");
    }

    // the plan's "uploading a document invalidates it": ingestion adds chunks, which changes the docs version
    @Test
    void aNewDocumentsChunks_makeTheNextAnswerFresh() throws Exception {
        Tenant tenant = tenantWithAChunk();
        chat(tenant, "How often does the scheduler run?");

        addChunk(tenant.id(), newDocument(tenant.id()), 0, "Since version 2 the scheduler runs every 30 seconds.");
        clearInvocations(modelGateway);
        chat(tenant, "How often does the scheduler run?");

        verify(modelGateway, times(1)).stream(any(), any(), anyList());
    }

    @Test
    void deletingADocument_makesTheNextAnswerFresh() throws Exception {
        Tenant tenant = tenantWithAChunk();
        chat(tenant, "How often does the scheduler run?");

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/v1/documents/" + tenant.document()).header("Authorization", "Bearer " + tenant.key()));
        clearInvocations(modelGateway);
        chat(tenant, "How often does the scheduler run?");

        verify(modelGateway).embed(any(), anyList());
    }

    // two tenants with the same question and the same text still each get their own answer
    @Test
    void anotherTenant_neverGetsThisTenantsAnswer() throws Exception {
        Tenant a = tenantWithAChunk();
        Tenant b = tenantWithAChunk();
        chat(a, "How often does the scheduler run?");

        clearInvocations(modelGateway);
        String fromB = chat(b, "How often does the scheduler run?");

        verify(modelGateway).stream(any(), any(), anyList());
        assertThat(citationsEvent(fromB)).doesNotContain(a.document().toString());
    }

    // the question is not in Redis: keys hold a hash of it (Task 26)
    @Test
    void theCacheHoldsNoQuestionText() throws Exception {
        Tenant tenant = tenantWithAChunk();
        String marker = "Wombatquill" + UUID.randomUUID().toString().substring(0, 8);

        chat(tenant, "How often does the scheduler run for " + marker + "?");

        List<String> keys = new ArrayList<>(redis.keys("answer:*"));
        assertThat(keys).isNotEmpty().noneMatch(key -> key.contains(marker));
        assertThat(redis.opsForValue().multiGet(keys)).noneMatch(value -> value != null && value.contains(marker));
    }

    private static String text(String stream) {
        StringBuilder text = new StringBuilder();
        String event = null;
        for (String line : stream.split("\n")) {
            if (line.startsWith("event:")) {
                event = line.substring(6);
            } else if (line.startsWith("data:") && "token".equals(event)) {
                text.append(com.jayway.jsonpath.JsonPath.<String>read(line.substring(5), "$.text"));
            }
        }
        return text.toString();
    }

    private static String citationsEvent(String stream) {
        return stream.substring(stream.indexOf("event:citations"));
    }

    private UUID newDocument(UUID tenant) {
        return tenantTransactions.run(tenant, () -> jdbc.sql("""
                        insert into documents (tenant_id, file_name, content_type, size_bytes, checksum, s3_key, status)
                        values (:tenant, 'scheduler.md', 'text/markdown', 10, decode(md5(random()::text), 'hex'), 'k', 'ready')
                        returning id""")
                .param("tenant", tenant).query(UUID.class).single());
    }

    private void addChunk(UUID tenant, UUID document, int ordinal, String content) {
        tenantTransactions.run(tenant, () -> jdbc.sql("""
                        insert into chunks (tenant_id, document_id, ordinal, content, token_count, ts_config)
                        values (:tenant, :document, :ordinal, :content, 1, cast(:config as regconfig))""")
                .param("tenant", tenant).param("document", document).param("ordinal", ordinal)
                .param("content", content).param("config", profiles.get("en").textSearchConfig())
                .update());
    }
}
